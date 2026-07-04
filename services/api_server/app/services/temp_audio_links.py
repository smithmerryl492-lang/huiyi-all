import hashlib
import hmac
import time

from app.core.config import settings


def generate_temp_audio_download_url(task_id: str) -> str:
    base_url = settings.public_api_base_url.rstrip("/")
    if not base_url:
        return ""
    expires = int(time.time()) + settings.temp_audio_url_ttl_seconds
    signature = sign_temp_audio_url(task_id, expires)
    return f"{base_url}{settings.api_prefix}/tasks/{task_id}/audio-temp?expires={expires}&signature={signature}"


def verify_temp_audio_signature(task_id: str, expires: int, signature: str) -> bool:
    if expires < int(time.time()):
        return False
    expected = sign_temp_audio_url(task_id, expires)
    return hmac.compare_digest(expected, str(signature or ""))


def sign_temp_audio_url(task_id: str, expires: int) -> str:
    secret = settings.auth_token_secret.encode("utf-8")
    message = f"{task_id}:{expires}".encode("utf-8")
    return hmac.new(secret, message, hashlib.sha256).hexdigest()
