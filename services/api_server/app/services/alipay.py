from __future__ import annotations

import base64
import hashlib
import json
from json import JSONDecoder
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from decimal import Decimal, ROUND_HALF_UP
from typing import Any
from urllib.parse import quote_plus, urlencode

import requests
from fastapi import HTTPException

from app.core.config import settings


SHA256_DIGEST_INFO_PREFIX = bytes.fromhex("3031300d060960864801650304020105000420")
ALIPAY_TIMEZONE = timezone(timedelta(hours=8))
ALIPAY_AES_IV = b"0102030405060708"


@dataclass(frozen=True)
class RsaPrivateKey:
    n: int
    e: int
    d: int


@dataclass(frozen=True)
class RsaPublicKey:
    n: int
    e: int


_PRIVATE_KEY_CACHE: dict[str, RsaPrivateKey] = {}
_PUBLIC_KEY_CACHE: dict[str, RsaPublicKey] = {}


def payment_enabled() -> bool:
    return bool(
        settings.alipay_app_id
        and settings.alipay_private_key
        and settings.alipay_public_key
        and settings.alipay_notify_url
    )


def payment_config_diagnostics() -> dict[str, Any]:
    private_key_parse_ok = _can_parse_private_key(settings.alipay_private_key)
    public_key_parse_ok = _can_parse_public_key(settings.alipay_public_key)
    aes_key_length = _aes_key_length(settings.alipay_aes_key)
    private_key_self_check = _private_key_self_check(settings.alipay_private_key) if private_key_parse_ok else False
    alipay_verify: dict[str, Any] = {
        "checked": False,
        "ok": False,
        "code": "",
        "sub_code": "",
        "message": "",
    }
    if payment_enabled() and private_key_parse_ok and public_key_parse_ok:
        alipay_verify = _diagnose_alipay_response_verify()
    return {
        "payment_enabled": payment_enabled(),
        "app_id": settings.alipay_app_id,
        "notify_url": settings.alipay_notify_url,
        "gateway_url": settings.alipay_gateway_url,
        "payment_mode": normalized_payment_mode(),
        "return_url": payment_return_url(),
        "private_key_loaded": bool(settings.alipay_private_key),
        "public_key_loaded": bool(settings.alipay_public_key),
        "private_key_fingerprint": key_fingerprint(settings.alipay_private_key),
        "public_key_fingerprint": key_fingerprint(settings.alipay_public_key),
        "private_key_parse_ok": private_key_parse_ok,
        "public_key_parse_ok": public_key_parse_ok,
        "private_key_self_check": private_key_self_check,
        "content_encryption_enabled": bool(settings.alipay_aes_key),
        "aes_key_loaded": bool(settings.alipay_aes_key),
        "aes_key_parse_ok": (not settings.alipay_aes_key) or aes_key_length in {16, 24, 32},
        "aes_key_bits": aes_key_length * 8 if aes_key_length in {16, 24, 32} else 0,
        "aes_key_fingerprint": aes_key_fingerprint(settings.alipay_aes_key),
        "alipay_response_verify": alipay_verify,
    }


def require_payment_config() -> None:
    if not payment_enabled():
        raise HTTPException(status_code=503, detail="支付宝支付配置未完成，请补充应用ID、通知地址和秘钥配置")


def normalized_payment_mode() -> str:
    mode = str(settings.alipay_payment_mode or "wap").strip().lower()
    if mode not in {"wap", "app"}:
        return "wap"
    return mode


def build_app_pay_order_string(*, out_trade_no: str, subject: str, total_amount_cents: int, body: str = "") -> str:
    require_payment_config()
    biz_content = json.dumps(
        {
            "subject": subject[:256],
            "out_trade_no": out_trade_no,
            "total_amount": cents_to_yuan(total_amount_cents),
            "product_code": "QUICK_MSECURITY_PAY",
            "body": body[:128],
        },
        ensure_ascii=False,
        separators=(",", ":"),
    )
    params: dict[str, str] = {
        "app_id": settings.alipay_app_id,
        "method": "alipay.trade.app.pay",
        "format": "JSON",
        "charset": "utf-8",
        "sign_type": "RSA2",
        "timestamp": alipay_timestamp(),
        "version": "1.0",
    }
    if settings.alipay_aes_key:
        params["encrypt_type"] = "AES"
        params["biz_content"] = aes_encrypt_content(biz_content, settings.alipay_aes_key)
    else:
        params["biz_content"] = biz_content
    if settings.alipay_notify_url:
        params["notify_url"] = settings.alipay_notify_url
    sign_content = canonical_content(params)
    params["sign"] = rsa2_sign(sign_content, settings.alipay_private_key)
    return "&".join(f"{key}={quote_plus(value)}" for key, value in params.items())


def build_wap_pay_url(*, out_trade_no: str, subject: str, total_amount_cents: int, body: str = "") -> str:
    require_payment_config()
    return_url = payment_return_url()
    biz_content = json.dumps(
        {
            "subject": subject[:256],
            "out_trade_no": out_trade_no,
            "total_amount": cents_to_yuan(total_amount_cents),
            "product_code": "QUICK_WAP_WAY",
            "body": body[:128],
            "quit_url": return_url,
        },
        ensure_ascii=False,
        separators=(",", ":"),
    )
    params = _base_request_params("alipay.trade.wap.pay")
    if settings.alipay_aes_key:
        params["encrypt_type"] = "AES"
        params["biz_content"] = aes_encrypt_content(biz_content, settings.alipay_aes_key)
    else:
        params["biz_content"] = biz_content
    if settings.alipay_notify_url:
        params["notify_url"] = settings.alipay_notify_url
    if return_url:
        params["return_url"] = return_url
    params["sign"] = rsa2_sign(canonical_content(params), settings.alipay_private_key)
    return f"{settings.alipay_gateway_url}?{urlencode(params)}"


def payment_return_url() -> str:
    if settings.alipay_return_url:
        return settings.alipay_return_url
    if settings.alipay_notify_url.endswith("/notify"):
        return settings.alipay_notify_url[: -len("/notify")] + "/return"
    return settings.alipay_notify_url


def query_trade(out_trade_no: str) -> dict[str, Any]:
    require_payment_config()
    response_key = "alipay_trade_query_response"
    params = _base_request_params("alipay.trade.query")
    params["biz_content"] = json.dumps(
        {"out_trade_no": out_trade_no},
        ensure_ascii=False,
        separators=(",", ":"),
    )
    params["sign"] = rsa2_sign(canonical_content(params), settings.alipay_private_key)
    try:
        response = requests.post(settings.alipay_gateway_url, data=params, timeout=2)
        response.raise_for_status()
        raw_content = response.content
        payload = response.json()
    except Exception as exc:
        raise HTTPException(status_code=502, detail="支付宝订单查询失败，请稍后重试") from exc
    response_payload = payload.get(response_key) or {}
    sign = payload.get("sign")
    if sign:
        verify_content = _extract_json_response_content_bytes(raw_content, response_key)
        if not rsa2_verify_bytes(verify_content, str(sign), settings.alipay_public_key):
            raise HTTPException(status_code=502, detail="支付宝订单查询验签失败")
    if response_payload.get("code") not in {"10000", "40004"}:
        raise HTTPException(status_code=502, detail=str(response_payload.get("sub_msg") or response_payload.get("msg") or "支付宝订单查询失败"))
    return response_payload


def verify_notify_params(params: dict[str, Any]) -> bool:
    sign = str(params.get("sign") or "")
    if not sign:
        return False
    filtered = {
        str(key): str(value)
        for key, value in params.items()
        if key not in {"sign", "sign_type"} and value is not None and str(value) != ""
    }
    return rsa2_verify(canonical_content(filtered), sign, settings.alipay_public_key)


def notify_app_id_matches(params: dict[str, Any]) -> bool:
    return bool(settings.alipay_app_id) and str(params.get("app_id") or "") == settings.alipay_app_id


def canonical_content(params: dict[str, str]) -> str:
    return "&".join(f"{key}={params[key]}" for key in sorted(params) if params[key] != "")


def alipay_timestamp() -> str:
    return datetime.now(ALIPAY_TIMEZONE).strftime("%Y-%m-%d %H:%M:%S")


def _base_request_params(method: str) -> dict[str, str]:
    return {
        "app_id": settings.alipay_app_id,
        "method": method,
        "format": "JSON",
        "charset": "utf-8",
        "sign_type": "RSA2",
        "timestamp": alipay_timestamp(),
        "version": "1.0",
    }


def _extract_json_response_content(raw_text: str, response_key: str) -> str:
    marker = f'"{response_key}"'
    key_index = raw_text.find(marker)
    if key_index < 0:
        return ""
    colon_index = raw_text.find(":", key_index + len(marker))
    if colon_index < 0:
        return ""
    start = colon_index + 1
    while start < len(raw_text) and raw_text[start].isspace():
        start += 1
    _, end = JSONDecoder().raw_decode(raw_text[start:])
    return raw_text[start : start + end]


def _extract_json_response_content_bytes(raw_content: bytes, response_key: str) -> bytes:
    marker = f'"{response_key}"'.encode("ascii")
    key_index = raw_content.find(marker)
    if key_index < 0:
        return b""
    colon_index = raw_content.find(b":", key_index + len(marker))
    if colon_index < 0:
        return b""
    start = colon_index + 1
    while start < len(raw_content) and raw_content[start] in b" \t\r\n":
        start += 1
    if start >= len(raw_content) or raw_content[start] != 0x7B:
        return b""

    depth = 0
    in_string = False
    escaped = False
    for index in range(start, len(raw_content)):
        char = raw_content[index]
        if in_string:
            if escaped:
                escaped = False
            elif char == 0x5C:
                escaped = True
            elif char == 0x22:
                in_string = False
            continue
        if char == 0x22:
            in_string = True
        elif char == 0x7B:
            depth += 1
        elif char == 0x7D:
            depth -= 1
            if depth == 0:
                return raw_content[start : index + 1]
    return b""


def cents_to_yuan(cents: int) -> str:
    amount = (Decimal(max(0, int(cents))) / Decimal(100)).quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)
    return format(amount, "f")


def yuan_to_cents(value: Any) -> int:
    amount = Decimal(str(value or "0")).quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)
    return int(amount * 100)


def rsa2_sign(content: str, private_key_text: str) -> str:
    key = _cached_private_key(private_key_text)
    digest = hashlib.sha256(content.encode("utf-8")).digest()
    encoded = _pkcs1_v15_encode(digest, _byte_length(key.n))
    signature = pow(int.from_bytes(encoded, "big"), key.d, key.n).to_bytes(_byte_length(key.n), "big")
    return base64.b64encode(signature).decode("ascii")


def rsa2_verify(content: str, signature_text: str, public_key_text: str) -> bool:
    return rsa2_verify_bytes(content.encode("utf-8"), signature_text, public_key_text)


def rsa2_verify_bytes(content: bytes, signature_text: str, public_key_text: str) -> bool:
    try:
        key = _cached_public_key(public_key_text)
        signature = base64.b64decode(_strip_pem(signature_text), validate=True)
        decoded = pow(int.from_bytes(signature, "big"), key.e, key.n).to_bytes(_byte_length(key.n), "big")
        expected = _pkcs1_v15_encode(hashlib.sha256(content).digest(), _byte_length(key.n))
        return decoded == expected
    except Exception:
        return False


def key_fingerprint(text: str) -> str:
    body = _strip_pem(text)
    if not body:
        return ""
    return hashlib.sha256(body.encode("utf-8")).hexdigest()[:16]


def aes_key_fingerprint(text: str) -> str:
    if not str(text or "").strip():
        return ""
    try:
        return hashlib.sha256(_parse_aes_key(text)).hexdigest()[:16]
    except Exception:
        return ""


def aes_encrypt_content(content: str, key_text: str) -> str:
    key = _parse_aes_key(key_text)
    try:
        from cryptography.hazmat.primitives import padding
        from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
    except Exception as exc:
        raise HTTPException(status_code=503, detail="支付宝 AES 内容加密依赖未安装，请重新构建服务") from exc

    padder = padding.PKCS7(128).padder()
    padded = padder.update(content.encode("utf-8")) + padder.finalize()
    encryptor = Cipher(algorithms.AES(key), modes.CBC(ALIPAY_AES_IV)).encryptor()
    encrypted = encryptor.update(padded) + encryptor.finalize()
    return base64.b64encode(encrypted).decode("ascii")


def _parse_aes_key(text: str) -> bytes:
    try:
        key = base64.b64decode(str(text or "").strip(), validate=True)
    except Exception as exc:
        raise HTTPException(status_code=503, detail="支付宝 AES 内容加密密钥格式不正确") from exc
    if len(key) not in {16, 24, 32}:
        raise HTTPException(status_code=503, detail="支付宝 AES 内容加密密钥长度不正确")
    return key


def _aes_key_length(text: str) -> int:
    if not str(text or "").strip():
        return 0
    try:
        return len(base64.b64decode(str(text or "").strip(), validate=True))
    except Exception:
        return -1


def _pkcs1_v15_encode(digest: bytes, length: int) -> bytes:
    payload = SHA256_DIGEST_INFO_PREFIX + digest
    padding_length = length - len(payload) - 3
    if padding_length < 8:
        raise ValueError("RSA key is too short")
    return b"\x00\x01" + (b"\xff" * padding_length) + b"\x00" + payload


def _parse_private_key(text: str) -> RsaPrivateKey:
    data = base64.b64decode(_strip_pem(text), validate=True)
    reader = DerReader(data)
    seq = reader.read_sequence()
    version = seq.read_integer()
    if version == 0 and seq.peek_tag() == 0x30:
        seq.read_sequence()
        rsa_data = seq.read_octet_string()
        rsa = DerReader(rsa_data).read_sequence()
    else:
        rsa = seq
    rsa.read_integer()
    n = rsa.read_integer()
    e = rsa.read_integer()
    d = rsa.read_integer()
    return RsaPrivateKey(n=n, e=e, d=d)


def _cached_private_key(text: str) -> RsaPrivateKey:
    fingerprint = key_fingerprint(text)
    if not fingerprint:
        return _parse_private_key(text)
    key = _PRIVATE_KEY_CACHE.get(fingerprint)
    if key is None:
        key = _parse_private_key(text)
        _PRIVATE_KEY_CACHE.clear()
        _PRIVATE_KEY_CACHE[fingerprint] = key
    return key


def _parse_public_key(text: str) -> RsaPublicKey:
    data = base64.b64decode(_strip_pem(text), validate=True)
    reader = DerReader(data)
    seq = reader.read_sequence()
    if seq.peek_tag() == 0x30:
        seq.read_sequence()
        bit_string = seq.read_bit_string()
        rsa = DerReader(bit_string).read_sequence()
    else:
        rsa = seq
    n = rsa.read_integer()
    e = rsa.read_integer()
    return RsaPublicKey(n=n, e=e)


def _cached_public_key(text: str) -> RsaPublicKey:
    fingerprint = key_fingerprint(text)
    if not fingerprint:
        return _parse_public_key(text)
    key = _PUBLIC_KEY_CACHE.get(fingerprint)
    if key is None:
        key = _parse_public_key(text)
        _PUBLIC_KEY_CACHE.clear()
        _PUBLIC_KEY_CACHE[fingerprint] = key
    return key


def _strip_pem(text: str) -> str:
    normalized = _normalize_key_text(text)
    return "".join(
        clean
        for line in normalized.splitlines()
        for clean in [line.strip()]
        if clean and not clean.startswith("-----")
    )


def _normalize_key_text(text: str) -> str:
    value = str(text or "").strip().strip('"').strip("'").strip()
    value = value.replace("\\r\\n", "\n").replace("\\n", "\n").replace("\\r", "\n")
    value = value.replace("\r\n", "\n").replace("\r", "\n")
    return value


def _can_parse_private_key(text: str) -> bool:
    try:
        _parse_private_key(text)
        return True
    except Exception:
        return False


def _can_parse_public_key(text: str) -> bool:
    try:
        _parse_public_key(text)
        return True
    except Exception:
        return False


def _private_key_self_check(text: str) -> bool:
    try:
        key = _parse_private_key(text)
        content = "huiyi-alipay-private-key-self-check"
        digest = hashlib.sha256(content.encode("utf-8")).digest()
        encoded = _pkcs1_v15_encode(digest, _byte_length(key.n))
        signature = pow(int.from_bytes(encoded, "big"), key.d, key.n).to_bytes(_byte_length(key.n), "big")
        decoded = pow(int.from_bytes(signature, "big"), key.e, key.n).to_bytes(_byte_length(key.n), "big")
        return decoded == encoded
    except Exception:
        return False


def _diagnose_alipay_response_verify() -> dict[str, Any]:
    response_key = "alipay_trade_query_response"
    params = _base_request_params("alipay.trade.query")
    params["biz_content"] = json.dumps(
        {"out_trade_no": f"KQDIAG{datetime.now(ALIPAY_TIMEZONE).strftime('%Y%m%d%H%M%S')}"},
        ensure_ascii=False,
        separators=(",", ":"),
    )
    params["sign"] = rsa2_sign(canonical_content(params), settings.alipay_private_key)
    try:
        response = requests.post(settings.alipay_gateway_url, data=params, timeout=3)
        response.raise_for_status()
    except Exception as exc:
        return {
            "checked": True,
            "ok": False,
            "code": "",
            "sub_code": "",
            "message": f"支付宝网关请求失败：{type(exc).__name__}",
        }
    try:
        raw_content = response.content
        payload = response.json()
    except Exception:
        return {
            "checked": True,
            "ok": False,
            "code": "",
            "sub_code": "",
            "message": f"支付宝响应格式异常：HTTP {response.status_code} / {response.headers.get('content-type', '-')}",
        }
    response_payload = payload.get(response_key) or {}
    verify_content = _extract_json_response_content_bytes(raw_content, response_key)
    sign = str(payload.get("sign") or "")
    return {
        "checked": True,
        "ok": bool(sign and rsa2_verify_bytes(verify_content, sign, settings.alipay_public_key)),
        "code": str(response_payload.get("code") or ""),
        "sub_code": str(response_payload.get("sub_code") or ""),
        "message": str(response_payload.get("sub_msg") or response_payload.get("msg") or ""),
    }


def _byte_length(value: int) -> int:
    return (value.bit_length() + 7) // 8


class DerReader:
    def __init__(self, data: bytes):
        self.data = data
        self.offset = 0

    def peek_tag(self) -> int:
        return self.data[self.offset]

    def read_sequence(self) -> DerReader:
        return DerReader(self._read_value(0x30))

    def read_integer(self) -> int:
        value = self._read_value(0x02)
        return int.from_bytes(value, "big")

    def read_octet_string(self) -> bytes:
        return self._read_value(0x04)

    def read_bit_string(self) -> bytes:
        value = self._read_value(0x03)
        if not value or value[0] != 0:
            raise ValueError("Unsupported DER bit string")
        return value[1:]

    def _read_value(self, expected_tag: int) -> bytes:
        tag = self._read_byte()
        if tag != expected_tag:
            raise ValueError(f"Unexpected DER tag: {tag}")
        length = self._read_length()
        value = self.data[self.offset : self.offset + length]
        if len(value) != length:
            raise ValueError("Truncated DER value")
        self.offset += length
        return value

    def _read_byte(self) -> int:
        if self.offset >= len(self.data):
            raise ValueError("Unexpected DER end")
        value = self.data[self.offset]
        self.offset += 1
        return value

    def _read_length(self) -> int:
        first = self._read_byte()
        if first < 0x80:
            return first
        size = first & 0x7F
        if size <= 0 or size > 4:
            raise ValueError("Unsupported DER length")
        raw = self.data[self.offset : self.offset + size]
        self.offset += size
        return int.from_bytes(raw, "big")
