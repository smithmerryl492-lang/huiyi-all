from __future__ import annotations

import base64
import json
import time
from typing import Any

import requests
from fastapi import HTTPException

from app.core.config import settings


APPLE_AUDIENCE = "appstoreconnect-v1"
APPLE_TRANSACTION_TIMEOUT_SECONDS = 12


def require_iap_config() -> None:
    missing = [
        name
        for name, value in {
            "HUIXIAO_APPLE_BUNDLE_ID": settings.apple_bundle_id,
            "HUIXIAO_APPLE_ISSUER_ID": settings.apple_issuer_id,
            "HUIXIAO_APPLE_KEY_ID": settings.apple_key_id,
            "HUIXIAO_APPLE_PRIVATE_KEY": settings.apple_private_key,
        }.items()
        if not str(value or "").strip()
    ]
    if missing:
        raise HTTPException(status_code=503, detail=f"iOS 内购验签配置缺失：{', '.join(missing)}")


def verify_transaction(
    transaction_id: str,
    expected_product_id: str,
    client_signed_transaction_info: str,
) -> dict[str, Any]:
    require_iap_config()
    clean_transaction_id = str(transaction_id or "").strip()
    clean_product_id = str(expected_product_id or "").strip()
    if not clean_transaction_id or not clean_product_id:
        raise HTTPException(status_code=422, detail="Apple 支付凭证不完整")

    payload = _fetch_transaction_info(clean_transaction_id)
    signed_transaction_info = str(payload.get("signedTransactionInfo") or "").strip()
    if not signed_transaction_info:
        raise HTTPException(status_code=422, detail="Apple 未返回交易签名")
    if client_signed_transaction_info and signed_transaction_info != str(client_signed_transaction_info).strip():
        raise HTTPException(status_code=422, detail="Apple 交易签名不匹配")

    transaction = _decode_jws_payload(signed_transaction_info)
    apple_transaction_id = str(transaction.get("transactionId") or "").strip()
    product_id = str(transaction.get("productId") or "").strip()
    bundle_id = str(transaction.get("bundleId") or "").strip()
    environment = str(transaction.get("environment") or payload.get("environment") or settings.apple_environment).strip()

    if apple_transaction_id != clean_transaction_id:
        raise HTTPException(status_code=422, detail="Apple 交易号不匹配")
    if product_id != clean_product_id:
        raise HTTPException(status_code=422, detail="Apple 商品不匹配")
    if bundle_id != settings.apple_bundle_id:
        raise HTTPException(status_code=422, detail="Apple Bundle ID 不匹配")

    return {
        "product_id": product_id,
        "transaction_id": apple_transaction_id,
        "original_transaction_id": str(transaction.get("originalTransactionId") or "").strip(),
        "environment": environment,
        "purchase_date_ms": _int_or_none(transaction.get("purchaseDate")),
        "signed_transaction_info": signed_transaction_info,
    }


def _fetch_transaction_info(transaction_id: str) -> dict[str, Any]:
    token = _make_app_store_server_token()
    url = f"{_server_base_url()}/inApps/v1/transactions/{transaction_id}"
    try:
        response = requests.get(
            url,
            headers={"Authorization": f"Bearer {token}"},
            timeout=APPLE_TRANSACTION_TIMEOUT_SECONDS,
        )
    except requests.RequestException as exc:
        raise HTTPException(status_code=502, detail=f"Apple 交易验证失败：{exc}") from exc
    if response.status_code == 404:
        fallback_url = _sandbox_base_url() if settings.apple_environment == "production" else "https://api.storekit.itunes.apple.com"
        response = requests.get(
            f"{fallback_url}/inApps/v1/transactions/{transaction_id}",
            headers={"Authorization": f"Bearer {token}"},
            timeout=APPLE_TRANSACTION_TIMEOUT_SECONDS,
        )
    if response.status_code >= 400:
        raise HTTPException(status_code=502, detail=f"Apple 交易验证失败（{response.status_code}）")
    try:
        data = response.json()
    except ValueError as exc:
        raise HTTPException(status_code=502, detail="Apple 交易验证返回异常") from exc
    if not isinstance(data, dict):
        raise HTTPException(status_code=502, detail="Apple 交易验证返回异常")
    return data


def _make_app_store_server_token() -> str:
    try:
        from cryptography.hazmat.primitives import hashes, serialization
        from cryptography.hazmat.primitives.asymmetric import ec, utils
    except ImportError as exc:
        raise HTTPException(status_code=503, detail="Apple 内购验签依赖缺失：cryptography") from exc

    now = int(time.time())
    header = {
        "alg": "ES256",
        "kid": settings.apple_key_id,
        "typ": "JWT",
    }
    payload = {
        "iss": settings.apple_issuer_id,
        "iat": now,
        "exp": now + 900,
        "aud": APPLE_AUDIENCE,
        "bid": settings.apple_bundle_id,
    }
    signing_input = f"{_b64_json(header)}.{_b64_json(payload)}".encode("ascii")
    private_key_text = settings.apple_private_key.replace("\\n", "\n")
    try:
        private_key = serialization.load_pem_private_key(private_key_text.encode("utf-8"), password=None)
    except ValueError as exc:
        raise HTTPException(status_code=503, detail="Apple 私钥格式不正确") from exc
    if not isinstance(private_key, ec.EllipticCurvePrivateKey):
        raise HTTPException(status_code=503, detail="Apple 私钥格式不正确")
    der_signature = private_key.sign(signing_input, ec.ECDSA(hashes.SHA256()))
    r, s = utils.decode_dss_signature(der_signature)
    signature = r.to_bytes(32, "big") + s.to_bytes(32, "big")
    return f"{signing_input.decode('ascii')}.{_b64_bytes(signature)}"


def _decode_jws_payload(jws: str) -> dict[str, Any]:
    parts = jws.split(".")
    if len(parts) != 3:
        raise HTTPException(status_code=422, detail="Apple 交易签名格式异常")
    try:
        payload = json.loads(_b64_decode(parts[1]).decode("utf-8"))
    except (ValueError, UnicodeDecodeError) as exc:
        raise HTTPException(status_code=422, detail="Apple 交易签名解析失败") from exc
    if not isinstance(payload, dict):
        raise HTTPException(status_code=422, detail="Apple 交易签名解析失败")
    return payload


def _server_base_url() -> str:
    if settings.apple_environment == "production":
        return "https://api.storekit.itunes.apple.com"
    return _sandbox_base_url()


def _sandbox_base_url() -> str:
    return "https://api.storekit-sandbox.itunes.apple.com"


def _b64_json(value: dict[str, Any]) -> str:
    return _b64_bytes(json.dumps(value, separators=(",", ":")).encode("utf-8"))


def _b64_bytes(value: bytes) -> str:
    return base64.urlsafe_b64encode(value).rstrip(b"=").decode("ascii")


def _b64_decode(value: str) -> bytes:
    padding = "=" * (-len(value) % 4)
    return base64.urlsafe_b64decode((value + padding).encode("ascii"))


def _int_or_none(value: Any) -> int | None:
    try:
        return int(value)
    except (TypeError, ValueError):
        return None
