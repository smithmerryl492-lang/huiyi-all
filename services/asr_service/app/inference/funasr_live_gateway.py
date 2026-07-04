import asyncio
import json
import logging
import re
from dataclasses import dataclass, field
from typing import Any

import websockets
from fastapi import WebSocket, WebSocketDisconnect

from app.core.config import settings
from app.schemas import TranscriptSegment


logger = logging.getLogger(__name__)
LIVE_USER_MESSAGE = "实时转写服务暂时不可用，录音已继续保存，请稍后重试"
ONLINE_SUBTITLE_MAX_CHARS = 28
ONLINE_SUBTITLE_MIN_CHARS = 8


@dataclass
class LiveSpeakerMap:
    raw_to_label: dict[str, str] = field(default_factory=dict)

    def label_for(self, raw_speaker: Any) -> str:
        if raw_speaker is None or str(raw_speaker).strip() == "":
            return "说话人 1"
        key = str(raw_speaker).strip()
        if key not in self.raw_to_label:
            self.raw_to_label[key] = f"说话人 {len(self.raw_to_label) + 1}"
        return self.raw_to_label[key]


@dataclass
class LiveDiarizationState:
    pcm: bytearray = field(default_factory=bytearray)
    final_segments: list[TranscriptSegment] = field(default_factory=list)
    online_text: str = ""
    online_start_ms: int | None = None

    def append_pcm(self, chunk: bytes) -> None:
        if chunk:
            self.pcm.extend(chunk)

    def reset_online(self) -> None:
        self.online_text = ""
        self.online_start_ms = None

    def update_online_segments(self, segments: list[dict[str, Any]]) -> list[dict[str, Any]]:
        if not segments:
            return []
        first_start_ms = next((item.get("start_ms") for item in segments if item.get("start_ms") is not None), None)
        if self.online_start_ms is None and first_start_ms is not None:
            self.online_start_ms = first_start_ms
        text = "".join(str(item.get("text") or "") for item in segments)
        if text:
            clean_text = _clean_live_text(text)
            if not self.online_text:
                self.online_text = clean_text
            elif clean_text.startswith(self.online_text) or self.online_text in clean_text:
                self.online_text = clean_text
            elif clean_text in self.online_text:
                pass
            else:
                self.online_text = _merge_streaming_text(self.online_text, clean_text)
        if not self.online_text:
            return []
        end_ms = next((item.get("end_ms") for item in reversed(segments) if item.get("end_ms") is not None), None)
        return _split_live_subtitle_segments(
            text=self.online_text,
            speaker="说话人 1",
            start_ms=self.online_start_ms,
            end_ms=end_ms,
        )

    def update_final_segments(self, segments: list[dict[str, Any]]) -> list[dict[str, Any]]:
        if not segments:
            return []
        self.online_text = ""
        self.online_start_ms = None
        known_keys = {
            (item.start_ms, item.end_ms, item.text)
            for item in self.final_segments
        }
        for item in segments:
            segment = _dict_to_transcript_segment(item)
            key = (segment.start_ms, segment.end_ms, segment.text)
            if key not in known_keys:
                self.final_segments.append(segment)
                known_keys.add(key)
        return [_segment_to_dict(item) for item in self.final_segments]

    def consume_plain_final_segments(
        self,
        segments: list[dict[str, Any]],
        *,
        clear_live_text: bool = True,
    ) -> list[dict[str, Any]]:
        result = _split_long_live_segments(segments)
        if clear_live_text:
            self.online_text = ""
            self.online_start_ms = None
        return result

class FunasrLiveGateway:
    async def run(self, websocket: WebSocket, session_id: str) -> None:
        await websocket.accept()
        speaker_map = LiveSpeakerMap()
        diarization_state = LiveDiarizationState()
        try:
            async with websockets.connect(
                settings.funasr_runtime_ws_url,
                max_size=None,
                ping_interval=20,
                ping_timeout=20,
                subprotocols=["binary"],
            ) as funasr:
                await funasr.send(json.dumps(_start_payload(session_id), ensure_ascii=False))
                await websocket.send_json(
                    {
                        "type": "session.started",
                        "session_id": session_id,
                        "engine": f"FunASR {settings.live_mode}",
                        "speaker_separation": settings.live_require_speaker,
                    }
                )
                client_to_funasr = asyncio.create_task(_client_to_funasr(websocket, funasr))
                funasr_to_client = asyncio.create_task(_funasr_to_client(websocket, funasr, session_id, speaker_map, diarization_state))
                done, pending = await asyncio.wait(
                    {client_to_funasr, funasr_to_client},
                    return_when=asyncio.FIRST_COMPLETED,
                )
                for task in pending:
                    task.cancel()
                for task in done:
                    task.result()
        except WebSocketDisconnect:
            return
        except Exception as exc:
            logger.warning("FunASR 实时服务不可用：%s", exc)
            await _send_error(websocket, LIVE_USER_MESSAGE)


async def _client_to_funasr(
    websocket: WebSocket,
    funasr,
) -> None:
    while True:
        message = await websocket.receive()
        if message["type"] == "websocket.disconnect":
            await funasr.send(json.dumps({"is_speaking": False}, ensure_ascii=False))
            await funasr.close()
            return
        if message.get("bytes") is not None:
            frame = message["bytes"]
            await funasr.send(frame)
        elif message.get("text") is not None:
            payload = _json_or_none(message["text"])
            if payload and payload.get("type") == "audio.end":
                await funasr.send(json.dumps({"is_speaking": False}, ensure_ascii=False))
                await funasr.send(bytes(3200))
            elif payload and payload.get("type") == "audio.start":
                continue
            else:
                await funasr.send(message["text"])


async def _funasr_to_client(
    websocket: WebSocket,
    funasr,
    session_id: str,
    speaker_map: LiveSpeakerMap,
    diarization_state: LiveDiarizationState,
) -> None:
    async for message in funasr:
        if isinstance(message, bytes):
            continue
        raw = _json_or_none(message)
        if raw is None:
            continue
        event = _normalize_funasr_event(raw, session_id, speaker_map, diarization_state)
        if event is not None:
            await websocket.send_json(event)


def _start_payload(session_id: str) -> dict[str, Any]:
    # Hotwords stay disabled until A/B proves they improve ASR without biasing AI knowledge answers.
    return {
        "mode": settings.live_mode,
        "chunk_size": settings.live_chunk_size,
        "chunk_interval": settings.live_chunk_interval,
        "encoder_chunk_look_back": settings.live_encoder_look_back,
        "decoder_chunk_look_back": settings.live_decoder_look_back,
        "audio_fs": 16000,
        "wav_name": session_id,
        "wav_format": "pcm",
        "is_speaking": True,
        "itn": True,
    }


def _normalize_funasr_event(
    raw: dict[str, Any],
    session_id: str,
    speaker_map: LiveSpeakerMap,
    diarization_state: LiveDiarizationState,
) -> dict[str, Any] | None:
    if bool(raw.get("reset_online")):
        diarization_state.reset_online()
        return None
    mode = str(raw.get("mode", ""))
    is_final = mode.endswith("offline") or bool(raw.get("is_final"))
    is_refine = bool(raw.get("refine"))
    fallback_speaker = _raw_speaker_name(raw)
    segments = _segments_from_stamp_sents(raw, speaker_map, fallback_speaker)
    if not segments:
        segments = _segments_from_sentence_info(raw, speaker_map, fallback_speaker)
    if not segments:
        text = str(raw.get("text", "")).strip()
        if not text:
            if is_final:
                diarization_state.reset_online()
            return None
        if is_final:
            text = _clean_live_text(text)
            if not text:
                return None
        start_ms, end_ms = _time_range_from_token_timestamps(raw)
        speaker = "说话人 1"
        if fallback_speaker is not None:
            speaker = speaker_map.label_for(fallback_speaker)
        segments = [
            {
                "speaker": speaker,
                "text": text,
                "timestamp": _format_timestamp(start_ms or 0),
                "start_ms": start_ms,
                "end_ms": end_ms,
            }
        ]
    if is_final and settings.live_require_speaker:
        segments = diarization_state.update_final_segments(segments)
    elif is_final:
        segments = diarization_state.consume_plain_final_segments(segments, clear_live_text=not is_refine)
    elif not is_final:
        segments = diarization_state.update_online_segments(segments)
        if not segments:
            return None
    return {
        "type": "transcript.final" if is_final else "transcript.partial",
        "session_id": session_id,
        "mode": mode or None,
        "segments": segments,
        "replace_all": is_final and settings.live_require_speaker,
        "raw_has_speaker": any(item["speaker"] != "未分离" for item in segments),
    }


def _segments_from_stamp_sents(
    raw: dict[str, Any],
    speaker_map: LiveSpeakerMap,
    fallback_speaker: Any,
) -> list[dict[str, Any]]:
    stamp_sents = raw.get("stamp_sents")
    if not isinstance(stamp_sents, list):
        return []
    segments: list[dict[str, Any]] = []
    for item in stamp_sents:
        if not isinstance(item, dict):
            continue
        text = _clean_live_text(str(item.get("text_seg") or item.get("punc") or item.get("text") or "").strip())
        if not text:
            continue
        start_ms = _to_int_ms(item.get("start"))
        end_ms = _to_int_ms(item.get("end"))
        raw_speaker = _raw_speaker_name(item) or fallback_speaker
        segments.append(
            {
                "speaker": speaker_map.label_for(raw_speaker),
                "text": text,
                "timestamp": _format_timestamp(start_ms or 0),
                "start_ms": start_ms,
                "end_ms": end_ms,
            }
        )
    return segments


def _segments_from_sentence_info(
    raw: dict[str, Any],
    speaker_map: LiveSpeakerMap,
    fallback_speaker: Any,
) -> list[dict[str, Any]]:
    sentence_info = raw.get("sentence_info")
    if not isinstance(sentence_info, list):
        return []
    segments: list[dict[str, Any]] = []
    for item in sentence_info:
        if not isinstance(item, dict):
            continue
        text = _clean_live_text(str(item.get("text") or "").strip())
        if not text:
            continue
        start_ms = _to_int_ms(item.get("start"))
        end_ms = _to_int_ms(item.get("end"))
        raw_speaker = _raw_speaker_name(item) or fallback_speaker
        segments.append(
            {
                "speaker": speaker_map.label_for(raw_speaker),
                "text": text,
                "timestamp": _format_timestamp(start_ms or 0),
                "start_ms": start_ms,
                "end_ms": end_ms,
            }
        )
    return segments


def _raw_speaker_name(source: dict[str, Any]) -> Any:
    for key in ("spk_name", "speaker", "spk"):
        value = source.get(key)
        if value is None:
            continue
        text = str(value).strip()
        if text and text.lower() not in {"unknown", "none", "null", "-1"}:
            return value
    return None


def _clean_live_text(text: str) -> str:
    clean = re.sub(r"\s+", "", text or "")
    clean = clean.lstrip("，。,.、；;：:！？!? ")
    return clean


def _merge_streaming_text(current: str, incoming: str) -> str:
    current = _clean_live_text(current)
    incoming = _clean_live_text(incoming)
    if not current:
        return incoming
    if not incoming:
        return current
    if incoming.startswith(current) or current in incoming:
        return incoming
    if incoming in current:
        return current
    max_overlap = min(len(current), len(incoming))
    for size in range(max_overlap, 1, -1):
        if current[-size:] == incoming[:size]:
            return _clean_live_text(current + incoming[size:])
    return _clean_live_text(current + incoming)


def _split_long_live_segments(segments: list[dict[str, Any]]) -> list[dict[str, Any]]:
    result: list[dict[str, Any]] = []
    for item in segments:
        result.extend(
            _split_live_subtitle_segments(
                text=str(item.get("text") or ""),
                speaker=str(item.get("speaker") or "说话人 1"),
                start_ms=_to_int_ms(item.get("start_ms")),
                end_ms=_to_int_ms(item.get("end_ms")),
            )
        )
    return result


def _split_live_subtitle_segments(
    *,
    text: str,
    speaker: str,
    start_ms: int | None,
    end_ms: int | None,
) -> list[dict[str, Any]]:
    clean = _clean_live_text(text)
    if not clean:
        return []
    pieces = _split_live_subtitle_text(clean)
    if len(pieces) <= 1:
        return [
            {
                "speaker": speaker,
                "text": clean,
                "timestamp": _format_timestamp(start_ms or 0),
                "start_ms": start_ms,
                "end_ms": end_ms,
            }
        ]
    duration = None
    if start_ms is not None and end_ms is not None and end_ms > start_ms:
        duration = end_ms - start_ms
    segments: list[dict[str, Any]] = []
    for index, piece in enumerate(pieces):
        piece_start = start_ms
        piece_end = end_ms
        if duration is not None:
            piece_start = start_ms + int(duration * index / len(pieces))
            piece_end = start_ms + int(duration * (index + 1) / len(pieces))
        segments.append(
            {
                "speaker": speaker,
                "text": piece,
                "timestamp": _format_timestamp(piece_start or 0),
                "start_ms": piece_start,
                "end_ms": piece_end,
            }
        )
    return segments


def _split_live_subtitle_text(text: str) -> list[str]:
    pieces: list[str] = []
    current: list[str] = []
    for char in text:
        current.append(char)
        current_text = "".join(current)
        should_split = (
            char in "。！？!?；;"
            and len(current_text) >= ONLINE_SUBTITLE_MIN_CHARS
        ) or len(current_text) >= ONLINE_SUBTITLE_MAX_CHARS
        if should_split:
            clean = _clean_live_text(current_text)
            if clean:
                pieces.append(clean)
            current = []
    tail = _clean_live_text("".join(current))
    if tail:
        pieces.append(tail)
    if len(pieces) >= 2 and len(pieces[-1]) < 4:
        pieces[-2] = _clean_live_text(pieces[-2] + pieces[-1])
        pieces.pop()
    return pieces or [text]

def _to_int_ms(value: Any) -> int | None:
    if value is None:
        return None
    try:
        return int(float(value))
    except (TypeError, ValueError):
        return None


def _time_range_from_token_timestamps(raw: dict[str, Any]) -> tuple[int | None, int | None]:
    timestamps = raw.get("timestamp")
    if not isinstance(timestamps, list) or not timestamps:
        return None, None
    first = timestamps[0]
    last = timestamps[-1]
    if not isinstance(first, list) or not isinstance(last, list) or len(first) < 2 or len(last) < 2:
        return None, None
    base_ms = _to_int_ms(raw.get("segment_start_ms")) or 0
    start_ms = _to_int_ms(first[0])
    end_ms = _to_int_ms(last[1])
    if start_ms is None or end_ms is None:
        return None, None
    return base_ms + start_ms, base_ms + end_ms


def _dict_to_transcript_segment(item: dict[str, Any]) -> TranscriptSegment:
    return TranscriptSegment(
        speaker=str(item.get("speaker") or "说话人 1"),
        text=str(item.get("text") or "").strip(),
        timestamp=str(item.get("timestamp") or _format_timestamp(_to_int_ms(item.get("start_ms")) or 0)),
        start_ms=_to_int_ms(item.get("start_ms")),
        end_ms=_to_int_ms(item.get("end_ms")),
        speaker_id=str(item.get("speaker_id") or "").strip() or None,
        confidence=None,
    )


def _segment_to_dict(segment: TranscriptSegment) -> dict[str, Any]:
    return {
        "speaker": segment.speaker,
        "text": segment.text,
        "timestamp": segment.timestamp,
        "start_ms": segment.start_ms,
        "end_ms": segment.end_ms,
        "speaker_id": segment.speaker_id,
    }


def _format_timestamp(ms: int) -> str:
    total_seconds = max(ms // 1000, 0)
    minutes = total_seconds // 60
    seconds = total_seconds % 60
    return f"{minutes:02d}:{seconds:02d}"


def _json_or_none(value: str) -> dict[str, Any] | None:
    try:
        parsed = json.loads(value)
    except json.JSONDecodeError:
        return None
    return parsed if isinstance(parsed, dict) else None


async def _send_error(websocket: WebSocket, message: str) -> None:
    try:
        await websocket.send_json({"type": "error", "message": message})
    except Exception:
        pass


live_gateway = FunasrLiveGateway()
