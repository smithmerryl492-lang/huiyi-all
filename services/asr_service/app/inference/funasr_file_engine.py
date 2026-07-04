import asyncio
import contextlib
import json
import logging
import re
import threading
import time
import wave
from pathlib import Path
from typing import Any

import numpy as np
import websockets

from app.inference.audio_prepare import prepare_audio
from app.inference.aliyun_file_transcriber import AliyunFileTranscriptionError, transcribe_url_with_aliyun_file
from app.inference.aliyun_pcm_transcriber import transcribe_pcm_with_aliyun
from app.inference.campplus_diarization import attach_speaker_voiceprints, extract_voiceprint_embedding_from_pcm, repair_speaker_segments
from app.schemas import (
    TranscriptSegment,
    TranscribeRequest,
    TranscribeResponse,
    VoiceprintEnrollAudioRequest,
    VoiceprintEnrollAudioResponse,
    VoiceprintExtractRequest,
    VoiceprintExtractResponse,
)
from app.core.config import settings


logger = logging.getLogger(__name__)


class CancelledTranscriptionError(RuntimeError):
    pass


_CANCEL_LOCK = threading.Lock()
_CANCELLED_TASK_IDS: set[str] = set()


class FunasrFileEngine:
    @property
    def model_loaded(self) -> bool:
        return bool(settings.aliyun_dashscope_api_key)

    def warmup(self) -> None:
        if not settings.aliyun_dashscope_api_key:
            raise RuntimeError("阿里云语音识别配置缺失")

    def cancel(self, task_id: str) -> bool:
        return request_transcription_cancel(task_id)

    def transcribe(self, request: TranscribeRequest) -> TranscribeResponse:
        _clear_transcription_cancel(request.task_id)
        _raise_if_transcription_cancelled(request.task_id)
        source_path = Path(request.source_file_path)
        if not source_path.exists():
            raise FileNotFoundError(f"音频文件不存在：{source_path}")

        prepared_path, temp_dir = prepare_audio(source_path)
        try:
            _raise_if_transcription_cancelled(request.task_id)
            pcm, duration_ms = _read_pcm16_mono(prepared_path)
            _raise_if_transcription_cancelled(request.task_id)
            if not _has_audible_pcm(pcm):
                raise RuntimeError("录音没有检测到有效声音，请检查麦克风输入后重新录制")
            segments, text = self._transcribe_offline(
                prepared_path,
                pcm,
                duration_ms,
                request.task_id,
                request.language_hint,
                request.source_file_url or "",
            )
            _raise_if_transcription_cancelled(request.task_id)
        finally:
            if temp_dir is not None:
                temp_dir.cleanup()

        if not segments:
            raise RuntimeError("阿里云语音识别未返回真实转写结果")

        return TranscribeResponse(
            task_id=request.task_id,
            text=text,
            language=_response_language(request.language_hint),
            region="CN",
            segments=segments,
            word_timestamps=[],
            model=f"Aliyun {settings.aliyun_realtime_model} + CAMPPlus",
        )

    def extract_voiceprints(self, request: VoiceprintExtractRequest) -> VoiceprintExtractResponse:
        source_path = Path(request.source_file_path)
        if not source_path.exists():
            raise FileNotFoundError(f"音频文件不存在：{source_path}")
        if not request.segments:
            return VoiceprintExtractResponse(task_id=request.task_id, segments=[])

        prepared_path, temp_dir = prepare_audio(source_path)
        try:
            pcm, duration_ms = _read_pcm16_mono(prepared_path)
            timed = _ensure_segment_times(request.segments, duration_ms)
            repaired = repair_speaker_segments(timed, pcm, force_recluster=False)
            segments = attach_speaker_voiceprints(repaired, pcm)
        finally:
            if temp_dir is not None:
                temp_dir.cleanup()
        return VoiceprintExtractResponse(task_id=request.task_id, segments=segments)

    def enroll_voiceprint_audio(self, request: VoiceprintEnrollAudioRequest) -> VoiceprintEnrollAudioResponse:
        source_path = Path(request.source_file_path)
        if not source_path.exists():
            raise FileNotFoundError(f"音频文件不存在：{source_path}")

        prepared_path, temp_dir = prepare_audio(source_path)
        try:
            pcm, _ = _read_pcm16_mono(prepared_path)
            if not _has_audible_pcm(pcm):
                raise RuntimeError("采样音频没有检测到有效声音，请重新录制清晰人声")
            result = extract_voiceprint_embedding_from_pcm(pcm)
        finally:
            if temp_dir is not None:
                temp_dir.cleanup()
        return VoiceprintEnrollAudioResponse(
            request_id=request.request_id,
            embedding=result["embedding"],
            quality=result["quality"],
            duration_ms=result["duration_ms"],
        )

    def _transcribe_offline(
        self,
        prepared_path: Path,
        pcm: bytes,
        duration_ms: int,
        task_id: str,
        language_hint: str,
        source_file_url: str = "",
    ) -> tuple[list[TranscriptSegment], str]:
        del prepared_path
        clean_source_file_url = str(source_file_url or "").strip()
        if clean_source_file_url:
            try:
                segments, text = transcribe_url_with_aliyun_file(
                    file_url=clean_source_file_url,
                    task_id=task_id,
                    language_hint=language_hint,
                    is_cancelled=_raise_if_transcription_cancelled,
                )
            except AliyunFileTranscriptionError:
                logger.exception("阿里云文件 URL 转写失败，回退 PCM 实时通道 task=%s", task_id)
                segments, text = self._transcribe_pcm(pcm, duration_ms, task_id, language_hint)
        else:
            segments, text = self._transcribe_pcm(pcm, duration_ms, task_id, language_hint)
        if not segments and text:
            segments = [
                TranscriptSegment(
                    speaker="说话人 1",
                    text=text,
                    timestamp="00:00",
                    start_ms=0,
                    end_ms=duration_ms,
                    confidence=None,
                )
            ]
        if not segments:
            raise RuntimeError("阿里云语音识别未返回真实转写结果")
        normalized = _attach_leading_punctuation(_dedupe_segments(segments))
        timed_before_repair = _ensure_segment_times(normalized, duration_ms)
        silence_split = _split_segments_on_internal_silence(timed_before_repair, pcm)
        _raise_if_transcription_cancelled(task_id)
        speaker_repaired = repair_speaker_segments(silence_split, pcm, force_recluster=False)
        processed = _split_long_segments(_merge_adjacent_same_speaker_segments(speaker_repaired, pcm))
        timed = _ensure_segment_times(processed, duration_ms)
        _ensure_result_coverage(timed, duration_ms)
        final_text = text or "".join(segment.text for segment in timed).strip()
        return timed, final_text

    def _transcribe_pcm(
        self,
        pcm: bytes,
        duration_ms: int,
        task_id: str,
        language_hint: str,
    ) -> tuple[list[TranscriptSegment], str]:
        return asyncio.run(
            transcribe_pcm_with_aliyun(
                pcm=pcm,
                duration_ms=duration_ms,
                task_id=task_id,
                language_hint=language_hint,
                is_cancelled=_raise_if_transcription_cancelled,
            )
        )


def _read_pcm16_mono(path: Path) -> tuple[bytes, int]:
    with wave.open(str(path), "rb") as wav:
        channels = wav.getnchannels()
        sample_width = wav.getsampwidth()
        sample_rate = wav.getframerate()
        if channels != 1 or sample_width != 2 or sample_rate != 16000:
            raise ValueError("ASR 预处理结果不是 16kHz 单声道 PCM16 WAV")
        frames = wav.readframes(wav.getnframes())
    duration_ms = int(len(frames) / (16000 * 2) * 1000)
    return frames, duration_ms


def _has_audible_pcm(pcm: bytes) -> bool:
    if not pcm or len(pcm) < 2:
        return False
    if len(pcm) % 2 == 1:
        pcm = pcm[:-1]
    samples = np.frombuffer(pcm, dtype=np.int16).astype(np.float32)
    if len(samples) == 0:
        return False
    abs_samples = np.abs(samples)
    peak = float(np.max(abs_samples))
    if peak < 80.0:
        return False
    rms = float(np.sqrt(np.mean(np.square(samples))))
    nonzero_ratio = float(np.count_nonzero(samples) / len(samples))
    return rms >= 8.0 and nonzero_ratio >= 0.002


def request_transcription_cancel(task_id: str) -> bool:
    clean_task_id = _clean_task_id(task_id)
    if not clean_task_id:
        return False
    with _CANCEL_LOCK:
        _CANCELLED_TASK_IDS.add(clean_task_id)
    return True


def _clear_transcription_cancel(task_id: str) -> None:
    clean_task_id = _clean_task_id(task_id)
    if not clean_task_id:
        return
    with _CANCEL_LOCK:
        _CANCELLED_TASK_IDS.discard(clean_task_id)


def _is_transcription_cancelled(task_id: str) -> bool:
    clean_task_id = _clean_task_id(task_id)
    if not clean_task_id:
        return False
    with _CANCEL_LOCK:
        return clean_task_id in _CANCELLED_TASK_IDS


def _raise_if_transcription_cancelled(task_id: str) -> None:
    if _is_transcription_cancelled(task_id):
        raise CancelledTranscriptionError("任务已终止")


def _response_language(language_hint: str | None) -> str:
    clean = str(language_hint or "").strip().lower()
    if clean in {"en", "en-us", "english"}:
        return "en"
    if clean in {"auto", "mixed", "zh-en"}:
        return "auto"
    return "zh"


def _clean_task_id(task_id: str | None) -> str:
    return str(task_id or "").strip()


async def _send_runtime_cancel(task_id: str) -> None:
    async with websockets.connect(
        settings.funasr_runtime_ws_url,
        max_size=None,
        ping_interval=None,
        subprotocols=["binary"],
    ) as websocket:
        await websocket.send(json.dumps({"type": "file_pcm_cancel", "task_id": task_id}, ensure_ascii=False))
        with contextlib.suppress(asyncio.TimeoutError):
            await asyncio.wait_for(websocket.recv(), timeout=1.0)


async def _check_runtime_ready() -> None:
    async with websockets.connect(
        settings.funasr_runtime_ws_url,
        max_size=None,
        ping_interval=20,
        ping_timeout=20,
        subprotocols=["binary"],
    ):
        return


async def _transcribe_with_runtime(pcm: bytes, duration_ms: int, task_id: str) -> list[dict[str, Any]]:
    events: list[dict[str, Any]] = []
    duration_seconds = max(0.0, duration_ms / 1000)
    timeout_seconds = _file_asr_runtime_timeout_seconds(duration_seconds)
    logger.info(
        "FunASR runtime 文件转写开始 task=%s duration=%.1fs timeout=%.1fs audio_bytes=%s",
        task_id,
        duration_seconds,
        timeout_seconds,
        len(pcm),
    )
    _raise_if_transcription_cancelled(task_id)
    started = time.perf_counter()
    async with websockets.connect(
        settings.funasr_runtime_ws_url,
        max_size=None,
        ping_interval=None,
        subprotocols=["binary"],
    ) as websocket:
        await websocket.send(
            json.dumps(
                {
                    "type": "file_pcm_transcribe_binary",
                    "task_id": task_id,
                    "segment_index": 0,
                    "segment_start_ms": 0,
                    "audio_fs": 16000,
                    "wav_name": "file-transcription",
                    "audio_bytes": len(pcm),
                },
                ensure_ascii=False,
            )
        )
        await websocket.send(pcm)
        deadline = time.monotonic() + timeout_seconds
        while time.monotonic() < deadline:
            _raise_if_transcription_cancelled(task_id)
            event = await _recv_runtime_event(websocket, timeout=min(1.0, max(0.2, deadline - time.monotonic())))
            if event is None:
                continue
            if event.get("type") != "file_pcm_result":
                continue
            if _to_int_or_none(event.get("segment_index")) != 0:
                continue
            if event.get("canceled"):
                raise CancelledTranscriptionError("任务已终止")
            if event.get("error"):
                raise RuntimeError(str(event["error"]))
            events.append(event)
            break
        else:
            raise TimeoutError(
                f"FunASR runtime file batch transcription timed out after {timeout_seconds:.0f}s "
                f"for audio {duration_seconds:.1f}s"
            )
    logger.info(
        "FunASR runtime 文件转写完成 task=%s duration=%.1fs timeout=%.1fs elapsed=%.2fs events=%s",
        task_id,
        duration_seconds,
        timeout_seconds,
        time.perf_counter() - started,
        len(events),
    )
    return events


def _file_asr_runtime_timeout_seconds(duration_seconds: float) -> float:
    calculated = settings.file_asr_runtime_timeout_buffer_seconds + (
        max(0.0, duration_seconds) * settings.file_asr_runtime_timeout_per_audio_second
    )
    return max(
        float(settings.file_asr_runtime_timeout_min_seconds),
        min(float(settings.file_asr_runtime_timeout_max_seconds), calculated),
    )


async def _recv_runtime_event(websocket, timeout: float) -> dict[str, Any] | None:
    try:
        message = await asyncio.wait_for(websocket.recv(), timeout=timeout)
    except asyncio.TimeoutError:
        return None
    if isinstance(message, bytes):
        return None
    try:
        parsed = json.loads(message)
    except json.JSONDecodeError:
        return None
    return parsed if isinstance(parsed, dict) else None


def _is_offline_final_event(event: dict[str, Any]) -> bool:
    mode = str(event.get("mode") or "")
    return mode.endswith("offline") or bool(event.get("is_final"))


def _covered_ms_from_events(events: list[dict[str, Any]]) -> int:
    covered = 0
    for event in events:
        offset_ms = _to_int_or_none(event.get("segment_start_ms")) or 0
        for key in ("stamp_sents", "sentence_info"):
            items = event.get(key)
            if not isinstance(items, list):
                continue
            for item in items:
                if not isinstance(item, dict):
                    continue
                end_ms = _to_int_or_none(item.get("end"))
                if end_ms is not None:
                    covered = max(covered, offset_ms + end_ms)
        timestamps = event.get("timestamp")
        if isinstance(timestamps, list):
            for item in timestamps:
                if isinstance(item, list) and len(item) >= 2:
                    end_ms = _to_int_or_none(item[1])
                    if end_ms is not None:
                        covered = max(covered, offset_ms + end_ms)
    return covered


def _segments_from_runtime_event(event: dict[str, Any]) -> list[TranscriptSegment]:
    speaker_labels: dict[str, str] = {}
    segments = _segments_from_stamp_sents(event.get("stamp_sents"), event, speaker_labels)
    if not segments:
        segments = _segments_from_sentence_info(event.get("sentence_info"), event, speaker_labels)
    if not segments:
        text = str(event.get("text") or "").strip()
        if text:
            offset_ms = _to_int_or_none(event.get("segment_start_ms")) or 0
            segments = _segments_from_token_timestamps(text, event.get("timestamp"), offset_ms)
    return segments


def _segments_from_stamp_sents(stamp_sents: Any, event: dict[str, Any], speaker_labels: dict[str, str]) -> list[TranscriptSegment]:
    if not isinstance(stamp_sents, list):
        return []
    offset_ms = _to_int_or_none(event.get("segment_start_ms")) or 0
    fallback_speaker = event.get("spk_name") or event.get("speaker") or event.get("spk")
    segments: list[TranscriptSegment] = []
    for item in stamp_sents:
        if not isinstance(item, dict):
            continue
        text = str(item.get("text_seg") or item.get("punc") or item.get("text") or "").strip()
        if not text:
            continue
        start_ms = _to_int_or_none(item.get("start"))
        end_ms = _to_int_or_none(item.get("end"))
        if start_ms is not None:
            start_ms += offset_ms
        if end_ms is not None:
            end_ms += offset_ms
        raw_speaker = item.get("spk_name") or item.get("speaker") or item.get("spk") or fallback_speaker
        segments.append(
            TranscriptSegment(
                speaker=_normalized_speaker_label(raw_speaker, speaker_labels),
                text=text,
                timestamp=_format_timestamp(start_ms or offset_ms),
                start_ms=start_ms,
                end_ms=end_ms,
                confidence=None,
            )
        )
    return segments


def _segments_from_sentence_info(sentence_info: Any, event: dict[str, Any] | None = None, speaker_labels: dict[str, str] | None = None) -> list[TranscriptSegment]:
    if not isinstance(sentence_info, list):
        return []
    event = event or {}
    speaker_labels = speaker_labels or {}
    offset_ms = _to_int_or_none(event.get("segment_start_ms")) or 0
    fallback_speaker = event.get("spk_name") or event.get("speaker") or event.get("spk")
    segments: list[TranscriptSegment] = []
    for item in sentence_info:
        if not isinstance(item, dict):
            continue
        text = str(item.get("text") or item.get("sentence") or "").strip()
        if not text:
            continue
        start_ms = _to_int_or_none(item.get("start"))
        end_ms = _to_int_or_none(item.get("end"))
        if start_ms is not None:
            start_ms += offset_ms
        if end_ms is not None:
            end_ms += offset_ms
        raw_speaker = item.get("spk_name") or item.get("speaker") or item.get("spk") or fallback_speaker
        segments.append(
            TranscriptSegment(
                speaker=_normalized_speaker_label(raw_speaker, speaker_labels),
                text=text,
                timestamp=_format_timestamp(start_ms or 0),
                start_ms=start_ms,
                end_ms=end_ms,
                confidence=None,
            )
        )
    return segments


def _segments_from_token_timestamps(text: str, timestamps: Any, offset_ms: int = 0) -> list[TranscriptSegment]:
    start_ms = None
    end_ms = None
    if isinstance(timestamps, list) and timestamps:
        first = timestamps[0]
        last = timestamps[-1]
        if isinstance(first, list) and len(first) >= 2:
            start_ms = _to_int_or_none(first[0])
        if isinstance(last, list) and len(last) >= 2:
            end_ms = _to_int_or_none(last[1])
    if start_ms is not None:
        start_ms += offset_ms
    if end_ms is not None:
        end_ms += offset_ms
    return [
        TranscriptSegment(
            speaker="说话人 1",
            text=text,
            timestamp=_format_timestamp(start_ms or offset_ms),
            start_ms=start_ms,
            end_ms=end_ms,
            confidence=None,
        )
    ]


def _speaker_label(raw: Any) -> str:
    if raw is None or str(raw).strip() == "":
        return "说话人 1"
    try:
        number = int(float(str(raw).strip()))
        return f"说话人 {number + 1}"
    except ValueError:
        return str(raw).strip()


def _normalized_speaker_label(raw: Any, speaker_labels: dict[str, str]) -> str:
    label = _speaker_label(raw)
    if label == "未分离":
        return label
    key = str(raw).strip()
    if key not in speaker_labels:
        speaker_labels[key] = f"说话人 {len(speaker_labels) + 1}"
    return speaker_labels[key]


def _dedupe_segments(segments: list[TranscriptSegment]) -> list[TranscriptSegment]:
    deduped: list[TranscriptSegment] = []
    seen: set[tuple[int | None, int | None, str, str]] = set()
    for segment in segments:
        key = (segment.start_ms, segment.end_ms, segment.speaker, segment.text)
        if key in seen:
            continue
        seen.add(key)
        deduped.append(segment)
    return sorted(deduped, key=lambda item: (item.start_ms is None, item.start_ms or 0, item.end_ms or 0))


def _merge_adjacent_segments(segments: list[TranscriptSegment]) -> list[TranscriptSegment]:
    if not segments:
        return []
    merged: list[TranscriptSegment] = []
    for segment in segments:
        if not merged:
            merged.append(segment)
            continue
        previous = merged[-1]
        gap_ms = None
        if previous.end_ms is not None and segment.start_ms is not None:
            gap_ms = segment.start_ms - previous.end_ms
        should_merge = (
            previous.speaker == segment.speaker
            and gap_ms is not None
            and gap_ms <= 900
            and _combined_duration_ms(previous, segment) <= 20_000
            and len(previous.text) + len(segment.text) <= 140
        )
        if not should_merge:
            merged.append(segment)
            continue
        merged[-1] = previous.model_copy(
            update={
                "text": _concat_without_overlap(previous.text, segment.text),
                "end_ms": segment.end_ms or previous.end_ms,
            }
        )
    return merged


def _merge_adjacent_same_speaker_segments(segments: list[TranscriptSegment], pcm: bytes) -> list[TranscriptSegment]:
    if not segments:
        return []
    merged: list[TranscriptSegment] = []
    for segment in segments:
        if not merged:
            merged.append(segment)
            continue
        previous = merged[-1]
        if not _should_merge_adjacent_same_speaker(previous, segment, pcm):
            merged.append(segment)
            continue
        merged[-1] = previous.model_copy(
            update={
                "text": _concat_without_overlap(previous.text, segment.text),
                "end_ms": segment.end_ms or previous.end_ms,
            }
        )
    return merged


def _split_segments_on_internal_silence(
    segments: list[TranscriptSegment],
    pcm: bytes,
    sample_rate: int = 16000,
) -> list[TranscriptSegment]:
    if not segments or not pcm:
        return segments
    output: list[TranscriptSegment] = []
    for segment in segments:
        output.extend(_split_segment_on_internal_silence(segment, pcm, sample_rate))
    return output


def _split_segment_on_internal_silence(
    segment: TranscriptSegment,
    pcm: bytes,
    sample_rate: int,
) -> list[TranscriptSegment]:
    if segment.start_ms is None or segment.end_ms is None:
        return [segment]
    duration_ms = segment.end_ms - segment.start_ms
    if duration_ms < 6_000 or len(segment.text.strip()) < 28:
        return [segment]
    silence = _longest_internal_silence(pcm, segment.start_ms, segment.end_ms, sample_rate)
    if silence is None:
        return [segment]
    silence_start, silence_end = silence
    split_text = _split_text_at_audio_position(segment.text, segment.start_ms, segment.end_ms, silence_start)
    if split_text is None:
        return [segment]
    left_text, right_text = split_text
    left = segment.model_copy(
        update={
            "text": left_text,
            "end_ms": silence_start,
        }
    )
    right = segment.model_copy(
        update={
            "text": right_text,
            "timestamp": _format_timestamp(silence_end),
            "start_ms": silence_end,
        }
    )
    return _split_segment_on_internal_silence(left, pcm, sample_rate) + _split_segment_on_internal_silence(right, pcm, sample_rate)


def _longest_internal_silence(
    pcm: bytes,
    start_ms: int,
    end_ms: int,
    sample_rate: int,
) -> tuple[int, int] | None:
    start_byte = max(0, int(start_ms * sample_rate * 2 / 1000))
    end_byte = min(len(pcm), int(end_ms * sample_rate * 2 / 1000))
    start_byte -= start_byte % 2
    end_byte -= end_byte % 2
    if end_byte <= start_byte:
        return None
    samples = np.frombuffer(pcm[start_byte:end_byte], dtype=np.int16).astype(np.float32)
    frame_samples = max(1, int(sample_rate * 0.05))
    frame_count = len(samples) // frame_samples
    if frame_count < 20:
        return None
    samples = samples[: frame_count * frame_samples].reshape(frame_count, frame_samples)
    rms = np.sqrt(np.mean(np.square(samples), axis=1))
    active = rms[rms > 0]
    if len(active) == 0:
        return None
    threshold = max(60.0, float(np.percentile(active, 15)) * 0.45, float(np.max(active)) * 0.018)
    silent = rms <= threshold
    min_frames = int(2_800 / 50)
    ignore_edge_frames = int(500 / 50)
    best: tuple[int, int] | None = None
    index = ignore_edge_frames
    upper = max(ignore_edge_frames, len(silent) - ignore_edge_frames)
    while index < upper:
        if not silent[index]:
            index += 1
            continue
        begin = index
        while index < upper and silent[index]:
            index += 1
        end = index
        if end - begin >= min_frames and (best is None or end - begin > best[1] - best[0]):
            best = (begin, end)
    if best is None:
        return None
    silence_start_ms = start_ms + best[0] * 50
    silence_end_ms = start_ms + best[1] * 50
    return silence_start_ms, silence_end_ms


def _split_text_at_audio_position(text: str, start_ms: int, end_ms: int, split_ms: int) -> tuple[str, str] | None:
    clean = text.strip()
    if len(clean) < 28 or end_ms <= start_ms:
        return None
    ratio = (split_ms - start_ms) / max(end_ms - start_ms, 1)
    approximate = int(len(clean) * ratio)
    punctuation_indices = [
        index + 1
        for index, char in enumerate(clean)
        if char in "。！？；，、,.!?;"
    ]
    if punctuation_indices:
        max_distance = max(8, int(len(clean) * 0.22))
        candidates = [
            index
            for index in punctuation_indices
            if 8 <= index <= len(clean) - 8 and abs(index - approximate) <= max_distance
        ]
        split_index = min(candidates, key=lambda item: abs(item - approximate)) if candidates else approximate
    else:
        split_index = approximate
    split_index = min(max(split_index, 8), len(clean) - 8)
    left = clean[:split_index].strip()
    right = clean[split_index:].strip()
    if len(left) < 8 or len(right) < 8:
        return None
    return left, right


def _should_merge_adjacent_same_speaker(previous: TranscriptSegment, current: TranscriptSegment, pcm: bytes) -> bool:
    if previous.speaker != current.speaker:
        return False
    if previous.start_ms is None or previous.end_ms is None or current.start_ms is None or current.end_ms is None:
        return False
    if _longest_internal_silence(pcm, previous.start_ms, current.end_ms, 16000) is not None:
        return False
    gap_ms = current.start_ms - previous.end_ms
    if gap_ms < -200 or gap_ms >= 2_800:
        return False
    combined_duration = current.end_ms - previous.start_ms
    if combined_duration <= 0 or combined_duration > 34_000:
        return False
    previous_text = previous.text.strip()
    current_text = current.text.strip()
    combined_text_len = len(previous_text) + len(current_text)
    if combined_text_len > 220:
        return False
    if gap_ms <= 1_600:
        return True
    previous_tiny = _is_tiny_utterance(previous)
    current_tiny = _is_tiny_utterance(current)
    if previous_tiny or current_tiny:
        return True
    previous_is_continuing = _ends_like_continuation(previous_text)
    current_is_continuing = _starts_like_continuation(current_text)
    compact_turn = combined_duration <= 24_000 and combined_text_len <= 160 and gap_ms <= 2_500
    return compact_turn and (previous_is_continuing or current_is_continuing)


def _is_tiny_utterance(segment: TranscriptSegment) -> bool:
    text = segment.text.strip()
    if not text:
        return True
    clean_len = len(re.sub(r"[\s，。！？；、,.!?;]", "", text))
    duration = None
    if segment.start_ms is not None and segment.end_ms is not None:
        duration = segment.end_ms - segment.start_ms
    return clean_len <= 6 or (duration is not None and duration <= 1_300 and clean_len <= 10)


def _ends_like_continuation(text: str) -> bool:
    clean = text.strip()
    return bool(clean) and clean[-1] in {"，", "、", ",", "；", ";"}


def _starts_like_continuation(text: str) -> bool:
    clean = text.strip()
    if not clean:
        return False
    prefixes = ("但是", "所以", "然后", "而且", "同时", "另外", "再", "就", "也", "并且", "或者")
    return clean[0] in {"，", "、", ","} or clean.startswith(prefixes)


def _attach_leading_punctuation(segments: list[TranscriptSegment]) -> list[TranscriptSegment]:
    output: list[TranscriptSegment] = []
    for segment in segments:
        text = segment.text.strip()
        if output and text:
            leading = re.match(r"^[。！？；，、,.!?;]+", text)
            if leading:
                mark = leading.group(0)
                previous = output[-1]
                output[-1] = previous.model_copy(update={"text": previous.text.rstrip() + mark})
                text = text[len(mark):].lstrip()
        if text:
            output.append(segment.model_copy(update={"text": text}))
    return output


def _combined_duration_ms(left: TranscriptSegment, right: TranscriptSegment) -> int:
    start = left.start_ms if left.start_ms is not None else right.start_ms
    end = right.end_ms if right.end_ms is not None else left.end_ms
    if start is None or end is None:
        return 0
    return max(0, end - start)


def _split_long_segments(segments: list[TranscriptSegment]) -> list[TranscriptSegment]:
    split: list[TranscriptSegment] = []
    for segment in segments:
        duration = None
        if segment.start_ms is not None and segment.end_ms is not None:
            duration = segment.end_ms - segment.start_ms
        if (duration is None or duration <= 28_000) and len(segment.text) <= 180:
            split.append(segment)
            continue
        parts = _split_text_by_utterance(segment.text)
        if len(parts) <= 1:
            split.append(segment)
            continue
        split.extend(_segments_from_text_parts(segment, parts))
    return split


def _split_text_by_utterance(text: str) -> list[str]:
    clean = text.strip()
    if not clean:
        return []
    sentences = [item.strip() for item in re.split(r"(?<=[。！？；])", clean) if item.strip()]
    if len(sentences) <= 1:
        return [clean]
    groups: list[str] = []
    current = ""
    for sentence in sentences:
        if len(current) + len(sentence) > 120 and current:
            groups.append(current)
            current = sentence
        else:
            current += sentence
    if current:
        groups.append(current)
    return groups


def _segments_from_text_parts(segment: TranscriptSegment, parts: list[str]) -> list[TranscriptSegment]:
    if segment.start_ms is None or segment.end_ms is None:
        return [
            segment.model_copy(update={"text": part})
            for part in parts
        ]
    total_chars = max(sum(len(part) for part in parts), 1)
    duration = max(segment.end_ms - segment.start_ms, len(parts))
    cursor = segment.start_ms
    output: list[TranscriptSegment] = []
    for index, part in enumerate(parts):
        if index == len(parts) - 1:
            end = segment.end_ms
        else:
            end = cursor + max(1, int(duration * len(part) / total_chars))
        output.append(
            segment.model_copy(
                update={
                    "text": part,
                    "timestamp": _format_timestamp(cursor),
                    "start_ms": cursor,
                    "end_ms": end,
                }
            )
        )
        cursor = end
    return output


def _ensure_segment_times(segments: list[TranscriptSegment], duration_ms: int) -> list[TranscriptSegment]:
    if not segments:
        return []
    starts = [
        segment.start_ms if segment.start_ms is not None else _parse_timestamp_ms(segment.timestamp)
        for segment in segments
    ]
    output: list[TranscriptSegment] = []
    for index, segment in enumerate(segments):
        start = starts[index]
        if start is None:
            start = 0 if index == 0 else output[-1].end_ms or output[-1].start_ms or 0
        next_start = next((item for item in starts[index + 1:] if item is not None and item > start), None)
        end = segment.end_ms
        if end is None or end <= start:
            end = next_start if next_start is not None else duration_ms
        if end <= start:
            end = min(duration_ms, start + 1000)
        output.append(
            segment.model_copy(
                update={
                    "timestamp": _format_timestamp(start),
                    "start_ms": start,
                    "end_ms": end,
                }
            )
        )
    return output


def _ensure_result_coverage(segments: list[TranscriptSegment], duration_ms: int) -> None:
    if duration_ms < 30_000 or not segments:
        return
    covered_ms = max((segment.end_ms or segment.start_ms or 0 for segment in segments), default=0)
    if covered_ms < int(duration_ms * 0.65):
        raise RuntimeError(
            f"阿里云转写结果覆盖不足：音频 {duration_ms // 1000} 秒，仅返回到 {covered_ms // 1000} 秒"
        )


def _concat_without_overlap(left: str, right: str) -> str:
    clean_left = left.strip()
    clean_right = right.strip()
    if not clean_left:
        return clean_right
    if not clean_right:
        return clean_left
    max_overlap = min(len(clean_left), len(clean_right), 12)
    for size in range(max_overlap, 0, -1):
        if clean_left[-size:] == clean_right[:size]:
            return clean_left + clean_right[size:]
    return clean_left + clean_right


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


def _parse_timestamp_ms(value: str) -> int | None:
    parts = str(value or "").split(":")
    if len(parts) != 2:
        return None
    try:
        minutes = int(parts[0])
        seconds = int(parts[1])
    except ValueError:
        return None
    return max(0, (minutes * 60 + seconds) * 1000)


file_engine = FunasrFileEngine()
