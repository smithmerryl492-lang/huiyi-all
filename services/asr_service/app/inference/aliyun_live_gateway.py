import asyncio
import base64
import json
import logging
import re
from dataclasses import dataclass, field
from typing import Any
from urllib.parse import urlencode

import websockets
from fastapi import WebSocket, WebSocketDisconnect

from app.core.config import settings


logger = logging.getLogger(__name__)
LIVE_USER_MESSAGE = "实时转写服务暂时不可用，录音已继续保存，请稍后重试"
SHORT_FILLER_PATTERN = re.compile(r"^[嗯呃额啊唔]+$")


@dataclass
class AliyunLiveItem:
    item_id: str
    start_ms: int = 0
    end_ms: int | None = None
    partial_text: str = ""


@dataclass
class AliyunLiveState:
    items: dict[str, AliyunLiveItem] = field(default_factory=dict)
    active_item_id: str | None = None
    received_audio_bytes: int = 0

    @property
    def received_audio_ms(self) -> int:
        return int(self.received_audio_bytes / 32)

    def touch_item(self, item_id: str | None, start_ms: int | None = None) -> AliyunLiveItem | None:
        if not item_id:
            return None
        if item_id not in self.items:
            self.items[item_id] = AliyunLiveItem(item_id=item_id, start_ms=start_ms or self.received_audio_ms)
        item = self.items[item_id]
        if start_ms is not None:
            item.start_ms = start_ms
        self.active_item_id = item_id
        return item


class AliyunLiveGateway:
    async def run(self, websocket: WebSocket, session_id: str) -> None:
        await websocket.accept()
        if not settings.aliyun_dashscope_api_key:
            await _send_error(websocket, "阿里云语音识别配置缺失，请稍后重试")
            await websocket.close(code=1011)
            return

        state = AliyunLiveState()
        try:
            async with websockets.connect(
                _aliyun_realtime_url(),
                additional_headers=_aliyun_headers(),
                max_size=None,
                ping_interval=20,
                ping_timeout=20,
                open_timeout=12,
                close_timeout=3,
            ) as aliyun:
                await aliyun.send(json.dumps(_session_update_payload(), ensure_ascii=False))
                await _wait_aliyun_session_ready(aliyun)
                await websocket.send_json(
                    {
                        "type": "session.started",
                        "session_id": session_id,
                        "engine": f"Aliyun {settings.aliyun_realtime_model}",
                        "speaker_separation": False,
                    }
                )
                client_to_aliyun = asyncio.create_task(_client_to_aliyun(websocket, aliyun, state))
                aliyun_to_client = asyncio.create_task(_aliyun_to_client(websocket, aliyun, session_id, state))
                done, pending = await asyncio.wait(
                    {client_to_aliyun, aliyun_to_client},
                    return_when=asyncio.FIRST_COMPLETED,
                )
                for task in pending:
                    task.cancel()
                for task in done:
                    task.result()
        except WebSocketDisconnect:
            return
        except Exception as exc:
            logger.warning("阿里云实时语音识别不可用：%s", exc)
            await _send_error(websocket, LIVE_USER_MESSAGE)


def _aliyun_headers() -> dict[str, str]:
    headers = {
        "Authorization": f"Bearer {settings.aliyun_dashscope_api_key}",
        "user-agent": "huiyi-asr-service/0.1",
    }
    if settings.aliyun_workspace_id:
        headers["X-DashScope-WorkSpace"] = settings.aliyun_workspace_id
    return headers


async def _wait_aliyun_session_ready(aliyun) -> None:
    deadline = asyncio.get_running_loop().time() + 8
    while asyncio.get_running_loop().time() < deadline:
        message = await asyncio.wait_for(aliyun.recv(), timeout=2)
        if isinstance(message, bytes):
            continue
        event = _json_or_none(message)
        if not event:
            continue
        event_type = str(event.get("type") or "")
        if event_type in {"session.updated", "session.created"}:
            return
        if event_type == "error" or event.get("error"):
            raise RuntimeError(_safe_error_message(event))
    raise TimeoutError("阿里云实时识别会话初始化超时")


async def _client_to_aliyun(websocket: WebSocket, aliyun, state: AliyunLiveState) -> None:
    while True:
        message = await websocket.receive()
        if message["type"] == "websocket.disconnect":
            await _finish_aliyun_session(aliyun)
            await aliyun.close()
            return
        if message.get("bytes") is not None:
            frame = message["bytes"]
            if not frame:
                continue
            state.received_audio_bytes += len(frame)
            await aliyun.send(
                json.dumps(
                    {
                        "event_id": f"audio_append_{state.received_audio_bytes}",
                        "type": "input_audio_buffer.append",
                        "audio": base64.b64encode(frame).decode("ascii"),
                    },
                    ensure_ascii=False,
                )
            )
            continue
        if message.get("text") is not None:
            payload = _json_or_none(message["text"])
            if payload and payload.get("type") == "audio.end":
                await aliyun.send(json.dumps({"event_id": "audio_commit", "type": "input_audio_buffer.commit"}))
                await asyncio.sleep(0.05)
                await _finish_aliyun_session(aliyun)
                continue
            if payload and payload.get("type") == "audio.start":
                continue


async def _finish_aliyun_session(aliyun) -> None:
    try:
        await aliyun.send(json.dumps({"event_id": "session_finish", "type": "session.finish"}))
    except Exception:
        pass


async def _aliyun_to_client(
    websocket: WebSocket,
    aliyun,
    session_id: str,
    state: AliyunLiveState,
) -> None:
    async for message in aliyun:
        if isinstance(message, bytes):
            continue
        event = _json_or_none(message)
        if not event:
            continue
        if event.get("type") == "session.finished":
            return
        normalized = _normalize_aliyun_event(event, session_id, state)
        if normalized is not None:
            await websocket.send_json(normalized)


def _normalize_aliyun_event(
    event: dict[str, Any],
    session_id: str,
    state: AliyunLiveState,
) -> dict[str, Any] | None:
    event_type = str(event.get("type") or "")
    item_id = str(event.get("item_id") or "").strip() or None
    if event_type == "input_audio_buffer.speech_started":
        state.touch_item(item_id, _to_int_or_none(event.get("audio_start_ms")) or state.received_audio_ms)
        return None
    if event_type == "input_audio_buffer.speech_stopped":
        item = state.touch_item(item_id)
        if item is not None:
            item.end_ms = _to_int_or_none(event.get("audio_end_ms")) or state.received_audio_ms
        return None
    if event_type == "conversation.item.input_audio_transcription.text":
        item = state.touch_item(item_id)
        if item is None:
            return None
        text = _partial_text_from_event(event)
        if not text or _should_filter_live_text(text, item.start_ms, item.end_ms):
            return None
        item.partial_text = text
        return _transcript_event(
            session_id=session_id,
            event_type="transcript.partial",
            text=text,
            start_ms=item.start_ms,
            end_ms=item.end_ms,
            mode="aliyun_realtime",
        )
    if event_type == "conversation.item.input_audio_transcription.completed":
        item = state.touch_item(item_id)
        if item is None:
            return None
        text = str(event.get("transcript") or item.partial_text or "").strip()
        if not text:
            return None
        end_ms = item.end_ms or state.received_audio_ms
        if _should_filter_live_text(text, item.start_ms, end_ms):
            item.partial_text = ""
            return None
        return _transcript_event(
            session_id=session_id,
            event_type="transcript.final",
            text=text,
            start_ms=item.start_ms,
            end_ms=end_ms,
            mode="aliyun_realtime",
        )
    if event_type == "error" or event.get("error"):
        raise RuntimeError(_safe_error_message(event))
    return None


def _partial_text_from_event(event: dict[str, Any]) -> str:
    committed = str(event.get("text") or "").strip()
    stash = str(event.get("stash") or "").strip()
    if committed and stash:
        return f"{committed}{stash}".strip()
    return (committed or stash).strip()


def _transcript_event(
    *,
    session_id: str,
    event_type: str,
    text: str,
    start_ms: int,
    end_ms: int | None,
    mode: str,
) -> dict[str, Any]:
    return {
        "type": event_type,
        "session_id": session_id,
        "mode": mode,
        "segments": [
            {
                "speaker": "发言",
                "text": text,
                "timestamp": _format_timestamp(start_ms),
                "start_ms": start_ms,
                "end_ms": end_ms,
            }
        ],
        "replace_all": False,
        "raw_has_speaker": False,
    }


def _session_update_payload() -> dict[str, Any]:
    return {
        "event_id": "huiyi_session_update",
        "type": "session.update",
        "session": {
            "input_audio_format": "pcm",
            "sample_rate": 16000,
            "input_audio_transcription": {
                "language": "zh",
            },
            "turn_detection": {
                "type": "server_vad",
                "threshold": _bounded_float(settings.aliyun_live_vad_threshold, 0.0, 1.0),
                "silence_duration_ms": max(200, int(settings.aliyun_live_vad_silence_duration_ms)),
            },
        },
    }


def _aliyun_realtime_url() -> str:
    base = settings.aliyun_realtime_ws_url.rstrip("/")
    separator = "&" if "?" in base else "?"
    return f"{base}{separator}{urlencode({'model': settings.aliyun_realtime_model})}"


def _json_or_none(value: str) -> dict[str, Any] | None:
    try:
        parsed = json.loads(value)
    except Exception:
        return None
    return parsed if isinstance(parsed, dict) else None


def _to_int_or_none(value: Any) -> int | None:
    if value is None:
        return None
    try:
        return int(float(value))
    except (TypeError, ValueError):
        return None


def _should_filter_live_text(text: str, start_ms: int | None, end_ms: int | None) -> bool:
    if not settings.aliyun_live_filter_filler:
        return False
    normalized = re.sub(r"[\s，。！？、,.!?…~～]+", "", str(text or ""))
    if not normalized or not SHORT_FILLER_PATTERN.fullmatch(normalized):
        return False
    duration_ms = None
    if start_ms is not None and end_ms is not None and end_ms >= start_ms:
        duration_ms = end_ms - start_ms
    if duration_ms is None:
        return len(normalized) <= 3
    return duration_ms <= max(300, int(settings.aliyun_live_filler_max_duration_ms))


def _bounded_float(value: float, minimum: float, maximum: float) -> float:
    return max(minimum, min(maximum, float(value)))


def _format_timestamp(ms: int) -> str:
    total_seconds = max(ms // 1000, 0)
    minutes = total_seconds // 60
    seconds = total_seconds % 60
    return f"{minutes:02d}:{seconds:02d}"


def _safe_error_message(event: dict[str, Any]) -> str:
    error = event.get("error")
    if isinstance(error, dict):
        return str(error.get("message") or error.get("code") or "aliyun realtime error")
    return str(error or event.get("message") or "aliyun realtime error")


async def _send_error(websocket: WebSocket, message: str, code: str = "upstream_unavailable") -> None:
    try:
        await websocket.send_json({"type": "error", "code": code, "message": message})
    except Exception:
        pass


aliyun_live_gateway = AliyunLiveGateway()
