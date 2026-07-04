from __future__ import annotations

import base64
import hashlib
import hmac
import json
import time
from typing import Any

from fastapi import Header, HTTPException

from app import admin_repositories
from app.core.config import settings


def create_admin_access_token(admin_id: str, username: str) -> str:
    now = int(time.time())
    payload = {
        "sub": admin_id,
        "username": username,
        "iat": now,
        "exp": now + settings.admin_token_ttl_seconds,
        "typ": "admin_access",
    }
    header = {"alg": "HS256", "typ": "JWT"}
    signing_input = f"{_b64_json(header)}.{_b64_json(payload)}"
    signature = hmac.new(settings.admin_token_secret.encode("utf-8"), signing_input.encode("ascii"), hashlib.sha256).digest()
    return f"{signing_input}.{_b64_encode(signature)}"


def require_current_admin(authorization: str | None = Header(default=None)) -> dict[str, Any]:
    token = _bearer_token(authorization)
    if not token:
        raise HTTPException(status_code=401, detail="请先登录后台")
    payload = verify_admin_access_token(token)
    admin = admin_repositories.get_admin_by_id(str(payload["sub"]))
    if admin is None or not bool(admin.get("active")):
        raise HTTPException(status_code=401, detail="后台登录已失效")
    return admin


def verify_admin_access_token(token: str) -> dict[str, Any]:
    try:
        header_raw, payload_raw, signature_raw = token.split(".", 2)
    except ValueError as exc:
        raise HTTPException(status_code=401, detail="后台登录凭证无效") from exc
    signing_input = f"{header_raw}.{payload_raw}"
    expected = hmac.new(settings.admin_token_secret.encode("utf-8"), signing_input.encode("ascii"), hashlib.sha256).digest()
    supplied = _b64_decode(signature_raw)
    if not hmac.compare_digest(expected, supplied):
        raise HTTPException(status_code=401, detail="后台登录凭证无效")
    try:
        payload = json.loads(_b64_decode(payload_raw))
    except Exception as exc:
        raise HTTPException(status_code=401, detail="后台登录凭证无效") from exc
    if payload.get("typ") != "admin_access" or not payload.get("sub"):
        raise HTTPException(status_code=401, detail="后台登录凭证无效")
    if int(payload.get("exp") or 0) <= int(time.time()):
        raise HTTPException(status_code=401, detail="后台登录已过期，请重新登录")
    return payload


def _bearer_token(authorization: str | None) -> str | None:
    if not authorization:
        return None
    scheme, _, token = authorization.partition(" ")
    if scheme.lower() != "bearer" or not token.strip():
        raise HTTPException(status_code=401, detail="后台登录凭证格式无效")
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
        raise HTTPException(status_code=401, detail="后台登录凭证无效") from exc
