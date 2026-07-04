from __future__ import annotations

import time
from dataclasses import dataclass
import json
import logging
import threading
import urllib.error
import urllib.request
from urllib.parse import urlencode

from app.core.config import settings


logger = logging.getLogger(__name__)


class AliyunRealtimeCredentialError(RuntimeError):
    pass


@dataclass(frozen=True)
class AliyunRealtimeSession:
    provider: str
    api_key: str
    expires_at: int
    websocket_url: str
    model: str
    sample_rate: int
    vad_threshold: float
    vad_silence_duration_ms: int
    filter_filler: bool
    filler_max_duration_ms: int
    workspace_id: str


_cached_session: AliyunRealtimeSession | None = None
_cached_session_key: tuple[str, str, str, str, int, float, int, bool, int] | None = None
_cache_lock = threading.Lock()
_cache_condition = threading.Condition(_cache_lock)
_refresh_inflight = False
_warmer_started = False
_MIN_USABLE_SESSION_SECONDS = 60


def create_realtime_session() -> AliyunRealtimeSession:
    global _cached_session, _cached_session_key, _refresh_inflight

    if not settings.aliyun_realtime_direct_enabled:
        raise AliyunRealtimeCredentialError("实时转写直连未启用")
    if not settings.aliyun_dashscope_api_key:
        raise AliyunRealtimeCredentialError("阿里云语音识别配置缺失")

    cache_key = _session_cache_key()
    with _cache_condition:
        cached = _valid_cached_session(cache_key)
        if cached is not None:
            return cached
        stale = _usable_cached_session(cache_key)
        if stale is not None and _refresh_inflight:
            return stale
        while _refresh_inflight:
            _cache_condition.wait(timeout=settings.aliyun_realtime_token_request_timeout_seconds + 1)
            cached = _valid_cached_session(cache_key)
            if cached is not None:
                return cached
            stale = _usable_cached_session(cache_key)
            if stale is not None:
                return stale
            if not _refresh_inflight:
                break
        _refresh_inflight = True

    try:
        temporary_key, expires_at = _create_temporary_api_key(settings.aliyun_realtime_token_ttl_seconds)
        session = AliyunRealtimeSession(
            provider="aliyun",
            api_key=temporary_key,
            expires_at=expires_at,
            websocket_url=_realtime_url(),
            model=settings.aliyun_realtime_model,
            sample_rate=16_000,
            vad_threshold=_bounded_float(settings.aliyun_live_vad_threshold, 0.0, 1.0),
            vad_silence_duration_ms=max(200, int(settings.aliyun_live_vad_silence_duration_ms)),
            filter_filler=settings.aliyun_live_filter_filler,
            filler_max_duration_ms=max(300, int(settings.aliyun_live_filler_max_duration_ms)),
            workspace_id=settings.aliyun_workspace_id,
        )
    except Exception:
        with _cache_condition:
            _refresh_inflight = False
            _cache_condition.notify_all()
            stale = _usable_cached_session(cache_key)
        if stale is not None:
            logger.warning(
                "阿里云实时转写临时凭证刷新失败，继续使用未过期缓存 expires_in=%ss",
                max(0, stale.expires_at - int(time.time())),
            )
            return stale
        raise

    with _cache_condition:
        _cached_session = session
        _cached_session_key = cache_key
        _refresh_inflight = False
        _cache_condition.notify_all()
        logger.info(
            "阿里云实时转写临时凭证已缓存 host=%s expires_in=%ss",
            settings.aliyun_api_host,
            max(0, session.expires_at - int(time.time())),
        )
        return session


def start_realtime_session_warmer() -> None:
    global _warmer_started
    if not settings.aliyun_realtime_direct_enabled or not settings.aliyun_realtime_prewarm_enabled:
        return
    if not settings.aliyun_dashscope_api_key:
        logger.warning("阿里云实时转写预热跳过：未配置 API Key")
        return
    with _cache_lock:
        if _warmer_started:
            return
        _warmer_started = True
    thread = threading.Thread(target=_warm_loop, name="aliyun-realtime-token-warmer", daemon=True)
    thread.start()


def _warm_loop() -> None:
    while True:
        try:
            session = create_realtime_session()
            sleep_seconds = max(
                30,
                session.expires_at - int(time.time()) - settings.aliyun_realtime_token_refresh_margin_seconds,
            )
            time.sleep(sleep_seconds)
        except Exception as exc:
            logger.warning("阿里云实时转写临时凭证预热失败：%s", exc)
            time.sleep(30)


def _valid_cached_session(cache_key: tuple[str, str, str, str, int, float, int, bool, int]) -> AliyunRealtimeSession | None:
    return _cached_session_with_min_valid(cache_key, settings.aliyun_realtime_token_refresh_margin_seconds)


def _usable_cached_session(cache_key: tuple[str, str, str, str, int, float, int, bool, int]) -> AliyunRealtimeSession | None:
    return _cached_session_with_min_valid(cache_key, _MIN_USABLE_SESSION_SECONDS)


def _cached_session_with_min_valid(
    cache_key: tuple[str, str, str, str, int, float, int, bool, int],
    min_valid_seconds: int,
) -> AliyunRealtimeSession | None:
    cached = _cached_session
    if (
        cached is not None
        and _cached_session_key == cache_key
        and cached.expires_at - int(time.time()) > min_valid_seconds
    ):
        return cached
    return None


def _session_cache_key() -> tuple[str, str, str, str, int, float, int, bool, int]:
    return (
        settings.aliyun_realtime_ws_url,
        settings.aliyun_realtime_token_url,
        settings.aliyun_workspace_id,
        settings.aliyun_realtime_model,
        16_000,
        _bounded_float(settings.aliyun_live_vad_threshold, 0.0, 1.0),
        max(200, int(settings.aliyun_live_vad_silence_duration_ms)),
        settings.aliyun_live_filter_filler,
        max(300, int(settings.aliyun_live_filler_max_duration_ms)),
    )


def _create_temporary_api_key(ttl_seconds: int) -> tuple[str, int]:
    # DashScope temporary API keys are intended for untrusted clients such as browsers and mobile apps.
    request = urllib.request.Request(
        f"{settings.aliyun_realtime_token_url}?{urlencode({'expire_in_seconds': int(ttl_seconds)})}",
        headers={
            "Authorization": f"Bearer {settings.aliyun_dashscope_api_key}",
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=settings.aliyun_realtime_token_request_timeout_seconds) as response:
            payload = json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        raise AliyunRealtimeCredentialError(f"阿里云临时凭证生成失败：HTTP {exc.code}") from exc
    except Exception as exc:
        raise AliyunRealtimeCredentialError("阿里云临时凭证响应异常") from exc
    api_key = (
        payload.get("token")
        or payload.get("api_key")
        or payload.get("apiKey")
        or payload.get("temporary_api_key")
        or payload.get("temporaryApiKey")
        or (payload.get("data") or {}).get("token")
        or (payload.get("data") or {}).get("api_key")
        or (payload.get("data") or {}).get("apiKey")
        or (payload.get("data") or {}).get("temporary_api_key")
        or (payload.get("data") or {}).get("temporaryApiKey")
    )
    if not isinstance(api_key, str) or not api_key.strip():
        raise AliyunRealtimeCredentialError("阿里云临时凭证响应缺少 api_key")
    expires_at = payload.get("expires_at") or (payload.get("data") or {}).get("expires_at")
    try:
        expires_at_int = int(expires_at)
    except (TypeError, ValueError):
        expires_at_int = int(time.time()) + int(ttl_seconds)
    return api_key.strip(), expires_at_int


def _realtime_url() -> str:
    base = settings.aliyun_realtime_ws_url.rstrip("/")
    separator = "&" if "?" in base else "?"
    return f"{base}{separator}{urlencode({'model': settings.aliyun_realtime_model})}"


def _bounded_float(value: float, minimum: float, maximum: float) -> float:
    return max(minimum, min(maximum, float(value)))
