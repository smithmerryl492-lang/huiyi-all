import asyncio
import json
import logging
import time
from dataclasses import dataclass
from urllib.parse import urlencode

import websockets
from fastapi import APIRouter, Depends, HTTPException, WebSocket, WebSocketDisconnect
from starlette.websockets import WebSocketState

from app import admin_repositories, membership_repositories, repositories
from app.core.config import settings
from app.services.aliyun_realtime import AliyunRealtimeCredentialError, create_realtime_session
from app.services.auth import require_current_user_id_unchecked, verify_access_token


router = APIRouter()
logger = logging.getLogger(__name__)
LIVE_USER_MESSAGE = "实时转写服务暂时不可用，请结束本次记录后稍后重试"
LIVE_GRACE_MINUTES = 30
LOW_REMAINING_WARN_MINUTES = 30
PCM_BYTES_PER_MINUTE = 16_000 * 2 * 60
LIVE_PREFLIGHT_CACHE_TTL_SECONDS = 30.0
_live_preflight_cache: dict[str, "LivePreflight"] = {}


@router.post("/session")
def create_live_direct_session(user_id: str = Depends(require_current_user_id_unchecked)) -> dict:
    identity = LiveIdentity(user_id=user_id)
    started = time.perf_counter()
    preflight = _get_cached_preflight(identity.user_id)
    if preflight is None:
        preflight = _load_live_preflight(identity)
        _set_cached_preflight(identity.user_id, preflight)
        logger.info(
            "实时转写直连预检查完成 user_id=%s remaining=%d elapsed_ms=%d",
            identity.user_id,
            preflight.remaining_minutes,
            _elapsed_ms(started),
        )
    else:
        logger.info("实时转写直连复用预检查缓存 user_id=%s remaining=%d", identity.user_id, preflight.remaining_minutes)
    remaining = preflight.remaining_minutes
    if remaining <= 0:
        raise HTTPException(status_code=402, detail="额度已耗尽，请充值后继续享受权益")
    started = time.perf_counter()
    try:
        session = create_realtime_session()
    except AliyunRealtimeCredentialError as exc:
        logger.warning("实时转写直连凭证生成失败：%s", exc)
        raise HTTPException(status_code=503, detail="实时转写服务暂时不可用，请稍后重试") from exc
    logger.info(
        "实时转写直连凭证就绪 user_id=%s provider=%s elapsed_ms=%d",
        identity.user_id,
        session.provider,
        _elapsed_ms(started),
    )
    return {
        "provider": "aliyun",
        "api_key": session.api_key,
        "expires_at": session.expires_at,
        "websocket_url": session.websocket_url,
        "workspace_id": session.workspace_id,
        "model": session.model,
        "sample_rate": session.sample_rate,
        "vad_threshold": session.vad_threshold,
        "vad_silence_duration_ms": session.vad_silence_duration_ms,
        "filter_filler": session.filter_filler,
        "filler_max_duration_ms": session.filler_max_duration_ms,
        "remaining_minutes": remaining,
        "grace_minutes": LIVE_GRACE_MINUTES,
        "low_remaining_warning_minutes": LOW_REMAINING_WARN_MINUTES,
    }


@router.websocket("/ws")
async def live_transcription_proxy(websocket: WebSocket) -> None:
    await websocket.accept()
    identity = await _authenticate_live_user(websocket)
    if identity is None:
        return
    meter = await _build_live_meter(websocket, identity)
    if meter is None:
        return
    session_id = websocket.query_params.get("session_id") or "live-session"
    query = urlencode({"session_id": session_id})
    upstream_url = f"{settings.asr_live_ws_url}?{query}"

    try:
        connect_started = time.perf_counter()
        async with websockets.connect(upstream_url, max_size=None, ping_interval=20, ping_timeout=20) as upstream:
            logger.info("实时转写上游连接完成 session_id=%s elapsed_ms=%d", session_id, _elapsed_ms(connect_started))
            client_to_upstream = asyncio.create_task(_client_to_upstream(websocket, upstream, meter))
            upstream_to_client = asyncio.create_task(_upstream_to_client(websocket, upstream, meter))
            done, pending = await asyncio.wait(
                {client_to_upstream, upstream_to_client},
                return_when=asyncio.FIRST_COMPLETED,
            )
            for task in pending:
                task.cancel()
            for task in done:
                task.result()
    except WebSocketDisconnect:
        return
    except Exception as exc:
        logger.warning("实时转写代理不可用：%s", exc)
        await _send_error(websocket, LIVE_USER_MESSAGE)


async def _authenticate_live_user(websocket: WebSocket) -> "LiveIdentity | None":
    token = websocket.query_params.get("access_token") or ""
    authorization = websocket.headers.get("authorization") or ""
    if not token and authorization.lower().startswith("bearer "):
        token = authorization.partition(" ")[2].strip()
    if not token:
        await _send_error(websocket, "请先登录后再开始实时记录", code="auth_required")
        await websocket.close(code=1008)
        return None
    try:
        payload = verify_access_token(token)
        user_id = str(payload["sub"])
        return LiveIdentity(user_id=user_id, phone=payload.get("phone"))
    except HTTPException as exc:
        await _send_error(websocket, str(exc.detail or "登录已过期，请重新登录"), code="auth_failed")
        await websocket.close(code=1008)
        return None


async def _build_live_meter(websocket: WebSocket, identity: "LiveIdentity") -> "LiveQuotaMeter | None":
    cached = _get_cached_preflight(identity.user_id)
    if cached is not None:
        remaining = cached.remaining_minutes
        logger.info("实时转写复用预检查缓存 user_id=%s remaining=%d", identity.user_id, remaining)
    else:
        started = time.perf_counter()
        try:
            preflight = await asyncio.to_thread(_load_live_preflight, identity)
        except HTTPException as exc:
            await _send_error(websocket, str(exc.detail or "实时转写暂不可用"), code="live_preflight_failed")
            await websocket.close(code=1008 if exc.status_code in {401, 402, 403, 404} else 1011)
            return None
        except Exception:
            logger.exception("实时转写预检查失败：%s", identity.user_id)
            await _send_error(websocket, "会员额度查询失败，请稍后重试", code="quota_unavailable")
            await websocket.close(code=1011)
            return None
        logger.info(
            "实时转写预检查完成 user_id=%s remaining=%d elapsed_ms=%d",
            identity.user_id,
            preflight.remaining_minutes,
            _elapsed_ms(started),
        )
        _set_cached_preflight(identity.user_id, preflight)
        remaining = preflight.remaining_minutes
    if remaining <= 0:
        await _send_error(websocket, "额度已耗尽，请充值后继续享受权益", code="quota_exhausted")
        await websocket.close(code=1008)
        return None
    if remaining <= LOW_REMAINING_WARN_MINUTES:
        await websocket.send_json({
            "type": "quota.warning",
            "message": f"当前剩余转写时长{remaining}分钟，实时记录最多可使用{LIVE_GRACE_MINUTES}分钟缓冲。",
            "remaining_minutes": remaining,
            "grace_minutes": LIVE_GRACE_MINUTES,
        })
    return LiveQuotaMeter(user_id=identity.user_id, allowed_minutes=remaining + LIVE_GRACE_MINUTES)


def _load_live_preflight(identity: "LiveIdentity") -> "LivePreflight":
    started = time.perf_counter()
    repositories.ensure_user_exists(identity.user_id, identity.phone)
    logger.info("实时转写用户校验完成 user_id=%s elapsed_ms=%d", identity.user_id, _elapsed_ms(started))

    started = time.perf_counter()
    if admin_repositories.is_user_frozen(identity.user_id):
        logger.info("实时转写冻结校验拦截 user_id=%s elapsed_ms=%d", identity.user_id, _elapsed_ms(started))
        raise HTTPException(status_code=403, detail="该账户状态异常已经被冻结")
    logger.info("实时转写冻结校验完成 user_id=%s elapsed_ms=%d", identity.user_id, _elapsed_ms(started))

    started = time.perf_counter()
    try:
        remaining = membership_repositories.get_transcription_quota_remaining(identity.user_id)
    finally:
        logger.info("实时转写额度查询完成 user_id=%s elapsed_ms=%d", identity.user_id, _elapsed_ms(started))
    return LivePreflight(remaining_minutes=remaining, cached_at=time.monotonic())


def _get_cached_preflight(user_id: str) -> "LivePreflight | None":
    cached = _live_preflight_cache.get(user_id)
    if cached is None:
        return None
    if time.monotonic() - cached.cached_at > LIVE_PREFLIGHT_CACHE_TTL_SECONDS:
        _live_preflight_cache.pop(user_id, None)
        return None
    return cached


def _set_cached_preflight(user_id: str, preflight: "LivePreflight") -> None:
    _live_preflight_cache[user_id] = preflight
    if len(_live_preflight_cache) > 256:
        cutoff = time.monotonic() - LIVE_PREFLIGHT_CACHE_TTL_SECONDS
        for key, value in list(_live_preflight_cache.items()):
            if value.cached_at < cutoff:
                _live_preflight_cache.pop(key, None)


async def _client_to_upstream(websocket: WebSocket, upstream, meter: "LiveQuotaMeter") -> None:
    while True:
        message = await websocket.receive()
        if message["type"] == "websocket.disconnect":
            await upstream.close()
            return
        if message.get("bytes") is not None:
            chunk = message["bytes"]
            meter.received_audio_bytes += len(chunk)
            if meter.session_minutes > meter.allowed_minutes:
                await _send_error(
                    websocket,
                    "转写时长和缓冲时长已用完，已自动结束本次实时记录并开始生成纪要。",
                    code="quota_exhausted",
                )
                await upstream.close()
                await websocket.close(code=1000)
                return
            await upstream.send(chunk)
        elif message.get("text") is not None:
            await upstream.send(message["text"])


async def _upstream_to_client(websocket: WebSocket, upstream, meter: "LiveQuotaMeter") -> None:
    async for message in upstream:
        if isinstance(message, bytes):
            await websocket.send_bytes(message)
        else:
            _track_final_transcript_minutes(meter, message)
            await websocket.send_text(message)


def _track_final_transcript_minutes(meter: "LiveQuotaMeter", message: str) -> None:
    try:
        data = json.loads(message)
    except Exception:
        return
    if data.get("type") != "transcript.final":
        return
    max_end_ms = 0
    for item in data.get("segments") or []:
        try:
            max_end_ms = max(max_end_ms, int(item.get("end_ms") or 0))
        except (TypeError, ValueError):
            continue
    if max_end_ms > 0:
        meter.final_transcript_minutes = max(meter.final_transcript_minutes, (max_end_ms + 59_999) // 60_000)


async def _send_error(websocket: WebSocket, message: str, code: str = "live_unavailable") -> None:
    try:
        if websocket.client_state != WebSocketState.CONNECTED:
            return
        await websocket.send_json({"type": "error", "code": code, "message": message})
    except Exception:
        pass


def _elapsed_ms(started: float) -> int:
    return int((time.perf_counter() - started) * 1000)


@dataclass
class LiveIdentity:
    user_id: str
    phone: str | None = None


@dataclass
class LivePreflight:
    remaining_minutes: int
    cached_at: float


@dataclass
class LiveQuotaMeter:
    user_id: str
    allowed_minutes: int
    received_audio_bytes: int = 0
    final_transcript_minutes: int = 0

    @property
    def session_minutes(self) -> int:
        if self.received_audio_bytes <= 0:
            return 0
        return (self.received_audio_bytes + PCM_BYTES_PER_MINUTE - 1) // PCM_BYTES_PER_MINUTE
