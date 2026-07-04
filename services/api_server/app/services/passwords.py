from __future__ import annotations

import base64
import hashlib
import hmac
import re
import secrets

from fastapi import HTTPException


_ALGORITHM = "pbkdf2_sha256"
_ITERATIONS = 260_000
_SALT_BYTES = 16
_KEY_BYTES = 32


def validate_password(raw_password: str) -> str:
    password = raw_password.strip()
    if len(password) < 8 or len(password) > 32:
        raise HTTPException(status_code=422, detail="密码需为 8-32 位")
    if not re.search(r"[A-Za-z]", password) or not re.search(r"\d", password):
        raise HTTPException(status_code=422, detail="密码需同时包含字母和数字")
    return password


def hash_password(raw_password: str) -> str:
    password = validate_password(raw_password)
    salt = secrets.token_bytes(_SALT_BYTES)
    digest = hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), salt, _ITERATIONS, dklen=_KEY_BYTES)
    return "$".join([_ALGORITHM, str(_ITERATIONS), _b64(salt), _b64(digest)])


def verify_password(raw_password: str, stored_hash: str | None) -> bool:
    if not stored_hash:
        return False
    try:
        algorithm, iterations_raw, salt_raw, digest_raw = stored_hash.split("$", 3)
        if algorithm != _ALGORITHM:
            return False
        iterations = int(iterations_raw)
        salt = _unb64(salt_raw)
        expected = _unb64(digest_raw)
    except Exception:
        return False
    supplied = hashlib.pbkdf2_hmac("sha256", raw_password.strip().encode("utf-8"), salt, iterations, dklen=len(expected))
    return hmac.compare_digest(expected, supplied)


def _b64(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode("ascii")


def _unb64(value: str) -> bytes:
    padding = "=" * (-len(value) % 4)
    return base64.urlsafe_b64decode((value + padding).encode("ascii"))
