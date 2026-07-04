import asyncio
import base64
import contextlib
import json
import logging
import time
from dataclasses import dataclass, field
from typing import Any, Callable
from urllib.parse import urlencode

import websockets

from app.core.config import settings
from app.schemas import TranscriptSegment


logger = logging.getLogger(__name__)


@dataclass
class AliyunPcmItem:
    item_id: str
    start_ms: int = 0
    end_ms: int | None = None
    partial_text: str = ""


@dataclass
class AliyunPcmState:
    items: dict[str, AliyunPcmItem] = field(default_factory=dict)
    received_audio_bytes: int = 0
    anonymous_index: int = 0

    @property
    def received_audio_ms(self) -> int:
        return int(self.received_audio_bytes / 32)

    def next_item_id(self) -> str:
        self.anonymous_index += 1
        return f"anonymous-{self.anonymous_index}"

    def touch_item(self, item_id: str | None, start_ms: int | None = None) -> AliyunPcmItem:
        clean_item_id = str(item_id or "").strip() or self.next_item_id()
        if clean_item_id not in self.items:
            self.items[clean_item_id] = AliyunPcmItem(
                item_id=clean_item_id,
                start_ms=start_ms if start_ms is not None else self.received_audio_ms,
            )
        item = self.items[clean_item_id]
        if start_ms is not None:
            item.start_ms = start_ms
        return item


class AliyunPcmTranscriptionError(RuntimeError):
    pass


async def transcribe_pcm_with_aliyun(
    *,
    pcm: bytes,
    duration_ms: int,
    task_id: str,
    language_hint: str = "zh-CN",
    is_cancelled: Callable[[str], None] | None = None,
) -> tuple[list[TranscriptSegment], str]:
    if not settings.aliyun_dashscope_api_key:
        raise AliyunPcmTranscriptionError("阿里云语音识别配置缺失")
    if not pcm:
        return [], ""

    state = AliyunPcmState()
    segments: list[TranscriptSegment] = []
    started = time.perf_counter()
    async with websockets.connect(
        _aliyun_realtime_url(),
        additional_headers={
            "Authorization": f"Bearer {settings.aliyun_dashscope_api_key}",
            "user-agent": "huiyi-asr-service/0.1",
        },
        max_size=None,
        ping_interval=20,
        ping_timeout=20,
        open_timeout=12,
        close_timeout=3,
    ) as aliyun:
        await aliyun.send(json.dumps(_session_update_payload(language_hint), ensure_ascii=False))
        await _wait_aliyun_session_ready(aliyun)
        receiver = asyncio.create_task(_receive_aliyun_segments(aliyun, state, segments))
        try:
            await _send_pcm_fast(aliyun, pcm, state, task_id, is_cancelled)
            await aliyun.send(json.dumps({"event_id": "audio_commit", "type": "input_audio_buffer.commit"}))
            await asyncio.sleep(0.05)
            await aliyun.send(json.dumps({"event_id": "session_finish", "type": "session.finish"}))
            await asyncio.wait_for(receiver, timeout=_aliyun_finish_timeout_seconds(duration_ms))
        finally:
            if not receiver.done():
                receiver.cancel()
            with contextlib.suppress(Exception):
                await aliyun.close()

    segments = _normalize_segments(segments, duration_ms)
    text = "".join(segment.text.strip() for segment in segments).strip()
    logger.info(
        "阿里云文件语音识别完成 task=%s duration=%.1fs elapsed=%.2fs segments=%s",
        task_id,
        max(0.0, duration_ms / 1000),
        time.perf_counter() - started,
        len(segments),
    )
    return segments, text


async def _send_pcm_fast(
    aliyun,
    pcm: bytes,
    state: AliyunPcmState,
    task_id: str,
    is_cancelled: Callable[[str], None] | None,
) -> None:
    frame_ms = max(40, int(settings.file_asr_frame_ms))
    frame_bytes = max(640, int(16000 * 2 * frame_ms / 1000))
    frame_bytes -= frame_bytes % 2
    speed = max(1.0, float(settings.file_asr_send_realtime_factor))
    sleep_seconds = frame_ms / 1000 / speed
    sent = 0
    while sent < len(pcm):
        if is_cancelled is not None:
            is_cancelled(task_id)
        frame = pcm[sent : sent + frame_bytes]
        sent += len(frame)
        state.received_audio_bytes = sent
        await aliyun.send(
            json.dumps(
                {
                    "event_id": f"file_audio_append_{sent}",
                    "type": "input_audio_buffer.append",
                    "audio": base64.b64encode(frame).decode("ascii"),
                },
                ensure_ascii=False,
            )
        )
        if sleep_seconds > 0:
            await asyncio.sleep(sleep_seconds)


async def _receive_aliyun_segments(aliyun, state: AliyunPcmState, segments: list[TranscriptSegment]) -> None:
    async for message in aliyun:
        if isinstance(message, bytes):
            continue
        event = _json_or_none(message)
        if not event:
            continue
        event_type = str(event.get("type") or "")
        if event_type == "session.finished":
            return
        if event_type == "error" or event.get("error"):
            raise AliyunPcmTranscriptionError(_safe_error_message(event))
        segment = _segment_from_aliyun_event(event, state)
        if segment is not None:
            segments.append(segment)


def _segment_from_aliyun_event(event: dict[str, Any], state: AliyunPcmState) -> TranscriptSegment | None:
    event_type = str(event.get("type") or "")
    item_id = str(event.get("item_id") or "").strip() or None
    if event_type == "input_audio_buffer.speech_started":
        state.touch_item(item_id, _to_int_or_none(event.get("audio_start_ms")) or state.received_audio_ms)
        return None
    if event_type == "input_audio_buffer.speech_stopped":
        item = state.touch_item(item_id)
        item.end_ms = _to_int_or_none(event.get("audio_end_ms")) or state.received_audio_ms
        return None
    if event_type == "input_audio_buffer.committed":
        audio_end_ms = _to_int_or_none(event.get("audio_end_ms"))
        if audio_end_ms is not None:
            state.received_audio_bytes = max(state.received_audio_bytes, audio_end_ms * 32)
        return None
    if event_type == "conversation.item.input_audio_transcription.text":
        item = state.touch_item(item_id)
        item.partial_text = _partial_text_from_event(event)
        return None
    if event_type != "conversation.item.input_audio_transcription.completed":
        return None

    item = state.touch_item(item_id)
    text = str(event.get("transcript") or item.partial_text or "").strip()
    if not text:
        return None
    end_ms = item.end_ms or state.received_audio_ms
    if end_ms <= item.start_ms:
        end_ms = item.start_ms + _estimate_text_duration_ms(text)
    return TranscriptSegment(
        speaker="说话人 1",
        text=text,
        timestamp=_format_timestamp(item.start_ms),
        start_ms=item.start_ms,
        end_ms=end_ms,
        confidence=None,
    )


def _normalize_segments(segments: list[TranscriptSegment], duration_ms: int) -> list[TranscriptSegment]:
    output: list[TranscriptSegment] = []
    seen: set[tuple[int | None, int | None, str]] = set()
    for segment in sorted(segments, key=lambda item: (item.start_ms is None, item.start_ms or 0, item.end_ms or 0)):
        text = segment.text.strip()
        if not text:
            continue
        start_ms = max(0, segment.start_ms or 0)
        if duration_ms > 0:
            start_ms = min(start_ms, max(0, duration_ms - 1))
        end_ms = segment.end_ms
        if end_ms is None or end_ms <= start_ms:
            end_ms = min(duration_ms, start_ms + _estimate_text_duration_ms(text))
        if duration_ms > 0:
            end_ms = min(max(end_ms, start_ms + 1), duration_ms)
        key = (start_ms, end_ms, text)
        if key in seen:
            continue
        seen.add(key)
        output.append(
            segment.model_copy(
                update={
                    "speaker": segment.speaker or "说话人 1",
                    "text": text,
                    "timestamp": _format_timestamp(start_ms),
                    "start_ms": start_ms,
                    "end_ms": end_ms,
                }
            )
        )
    return output


def _session_update_payload(language_hint: str = "zh-CN") -> dict[str, Any]:
    transcription: dict[str, Any] = {}
    normalized = _normalize_aliyun_language(language_hint)
    if normalized:
        transcription["language"] = normalized
    return {
        "event_id": "huiyi_file_session_update",
        "type": "session.update",
        "session": {
            "input_audio_format": "pcm",
            "sample_rate": 16000,
            "input_audio_transcription": transcription,
            "turn_detection": {
                "type": "server_vad",
                "threshold": 0.0,
                "silence_duration_ms": 400,
            },
        },
    }


def _normalize_aliyun_language(language_hint: str | None) -> str | None:
    clean = str(language_hint or "").strip().lower()
    if clean in {"en", "en-us", "english"}:
        return "en"
    if clean in {"auto", "mixed", "zh-en"}:
        return None
    return "zh"


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
            raise AliyunPcmTranscriptionError(_safe_error_message(event))
    raise TimeoutError("阿里云语音识别会话初始化超时")


def _aliyun_realtime_url() -> str:
    base = settings.aliyun_realtime_ws_url.rstrip("/")
    separator = "&" if "?" in base else "?"
    return f"{base}{separator}{urlencode({'model': settings.aliyun_realtime_model})}"


def _aliyun_finish_timeout_seconds(duration_ms: int) -> float:
    return max(20.0, min(180.0, 12.0 + duration_ms / 1000 * 0.5))


def _partial_text_from_event(event: dict[str, Any]) -> str:
    committed = str(event.get("text") or "").strip()
    stash = str(event.get("stash") or "").strip()
    if committed and stash:
        return f"{committed}{stash}".strip()
    return (committed or stash).strip()


def _estimate_text_duration_ms(text: str) -> int:
    clean_len = max(1, len(str(text or "").strip()))
    return max(800, min(20_000, clean_len * 180))


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


def _format_timestamp(ms: int) -> str:
    total_seconds = max(ms // 1000, 0)
    minutes = total_seconds // 60
    seconds = total_seconds % 60
    return f"{minutes:02d}:{seconds:02d}"


def _safe_error_message(event: dict[str, Any]) -> str:
    error = event.get("error")
    if isinstance(error, dict):
        raw = str(error.get("message") or error.get("code") or "aliyun asr error")
    else:
        raw = str(error or event.get("message") or "aliyun asr error")
    lower = raw.lower()
    if "invalid audio stream" in lower or "committing input audio buffer" in lower:
        return "音频没有检测到有效声音，请检查文件后重新上传"
    return raw
