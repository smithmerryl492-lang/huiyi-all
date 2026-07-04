from __future__ import annotations

import base64
import hashlib
import hmac
import json
import time
from typing import Any

from fastapi import Header, HTTPException, Query

from app import admin_repositories, repositories
from app.core.config import settings


def create_access_token(user_id: str, phone_e164: str | None = None) -> str:
    now = int(time.time())
    payload = {
        "sub": user_id,
        "phone": phone_e164,
        "iat": now,
        "exp": now + settings.auth_token_ttl_seconds,
        "typ": "access",
    }
    header = {"alg": "HS256", "typ": "JWT"}
    signing_input = f"{_b64_json(header)}.{_b64_json(payload)}"
    signature = hmac.new(settings.auth_token_secret.encode("utf-8"), signing_input.encode("ascii"), hashlib.sha256).digest()
    return f"{signing_input}.{_b64_encode(signature)}"


def create_phone_change_token(user_id: str, phone_e164: str, ttl_seconds: int = 600) -> str:
    now = int(time.time())
    payload = {
        "sub": user_id,
        "phone": phone_e164,
        "iat": now,
        "exp": now + ttl_seconds,
        "typ": "phone_change",
    }
    header = {"alg": "HS256", "typ": "JWT"}
    signing_input = f"{_b64_json(header)}.{_b64_json(payload)}"
    signature = hmac.new(settings.auth_token_secret.encode("utf-8"), signing_input.encode("ascii"), hashlib.sha256).digest()
    return f"{signing_input}.{_b64_encode(signature)}"


def verify_phone_change_token(token: str, user_id: str, phone_e164: str) -> None:
    if not token:
        raise HTTPException(status_code=401, detail="请先验证当前手机号")
    try:
        header_raw, payload_raw, signature_raw = token.split(".", 2)
    except ValueError as exc:
        raise HTTPException(status_code=401, detail="手机号验证已失效，请重新验证") from exc
    signing_input = f"{header_raw}.{payload_raw}"
    expected = hmac.new(settings.auth_token_secret.encode("utf-8"), signing_input.encode("ascii"), hashlib.sha256).digest()
    supplied = _b64_decode(signature_raw)
    if not hmac.compare_digest(expected, supplied):
        raise HTTPException(status_code=401, detail="手机号验证已失效，请重新验证")
    try:
        payload = json.loads(_b64_decode(payload_raw))
    except Exception as exc:
        raise HTTPException(status_code=401, detail="手机号验证已失效，请重新验证") from exc
    if payload.get("typ") != "phone_change" or payload.get("sub") != user_id or payload.get("phone") != phone_e164:
        raise HTTPException(status_code=401, detail="手机号验证已失效，请重新验证")
    if int(payload.get("exp") or 0) <= int(time.time()):
        raise HTTPException(status_code=401, detail="手机号验证已过期，请重新验证")


def require_current_user_id(
    authorization: str | None = Header(default=None),
    legacy_user_id: str | None = Query(default=None, alias="user_id"),
) -> str:
    user_id = require_current_user_id_unchecked(authorization, legacy_user_id)
    if admin_repositories.is_user_frozen(user_id):
        raise HTTPException(status_code=403, detail="账号已冻结，请联系管理员")
    return user_id


def require_current_user_id_unchecked(
    authorization: str | None = Header(default=None),
    legacy_user_id: str | None = Query(default=None, alias="user_id"),
) -> str:
    token = _bearer_token(authorization)
    if token:
        payload = verify_access_token(token)
        user_id = payload["sub"]
        try:
            repositories.ensure_user_exists(user_id, payload.get("phone"))
        except HTTPException as exc:
            raise HTTPException(status_code=401, detail="登录已失效，请重新登录") from exc
        return user_id
    if settings.auth_allow_legacy_user_id and legacy_user_id:
        repositories.ensure_user_exists(legacy_user_id)
        return legacy_user_id
    raise HTTPException(status_code=401, detail="请先登录")


def verify_access_token(token: str) -> dict[str, Any]:
    try:
        header_raw, payload_raw, signature_raw = token.split(".", 2)
    except ValueError as exc:
        raise HTTPException(status_code=401, detail="登录凭证无效") from exc
    signing_input = f"{header_raw}.{payload_raw}"
    expected = hmac.new(settings.auth_token_secret.encode("utf-8"), signing_input.encode("ascii"), hashlib.sha256).digest()
    supplied = _b64_decode(signature_raw)
    if not hmac.compare_digest(expected, supplied):
        raise HTTPException(status_code=401, detail="登录凭证无效")
    try:
        payload = json.loads(_b64_decode(payload_raw))
    except Exception as exc:
        raise HTTPException(status_code=401, detail="登录凭证无效") from exc
    if payload.get("typ") != "access" or not payload.get("sub"):
        raise HTTPException(status_code=401, detail="登录凭证无效")
    if int(payload.get("exp") or 0) <= int(time.time()):
        raise HTTPException(status_code=401, detail="登录已过期，请重新登录")
    return payload


def _bearer_token(authorization: str | None) -> str | None:
    if not authorization:
        return None
    scheme, _, token = authorization.partition(" ")
    if scheme.lower() != "bearer" or not token.strip():
        raise HTTPException(status_code=401, detail="登录凭证格式无效")
    return token.strip()


def _b64_json(value: dict[str, Any]) -> str:
    return _b64_encode(json.dumps(value, separators=(",", ":"), ensure_ascii=False).encode("utf-8"))


def _b64_encode(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode("ascii")


def _b64_decode(value: str) -> bytes:
    padding = "=" * (-len(value) % 4)
    try:
        return base64.urlsafe_b64decode((value + padding).encode("ascii"))
    except Exception as exc:
        raise HTTPException(status_code=401, detail="登录凭证无效") from exc
