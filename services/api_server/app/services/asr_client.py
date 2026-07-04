import json
import logging
import time
import urllib.error
import urllib.request

from fastapi import HTTPException

from app.core.config import settings
from app.schemas import TranscriptSegment
from app.services.errors import ASR_USER_MESSAGE


logger = logging.getLogger(__name__)


def transcribe_with_asr_service(
    task_id: str,
    source_file_path: str,
    language_hint: str = "zh-CN",
    source_file_url: str = "",
) -> list[TranscriptSegment]:
    payload = json.dumps(
        {
            "task_id": task_id,
            "source_file_path": source_file_path,
            "source_file_url": source_file_url,
            "language_hint": language_hint,
        },
        ensure_ascii=False,
    ).encode("utf-8")
    request = urllib.request.Request(
        f"{settings.asr_service_url.rstrip('/')}/api/v1/transcriptions",
        data=payload,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        started = time.perf_counter()
        with urllib.request.urlopen(request, timeout=settings.asr_file_timeout_seconds) as response:
            data = json.loads(response.read().decode("utf-8"))
        logger.info(
            "ASR 文件转写服务返回 task=%s timeout=%ss elapsed=%.2fs",
            task_id,
            settings.asr_file_timeout_seconds,
            time.perf_counter() - started,
        )
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8", errors="ignore")
        logger.warning("ASR 文件转写服务 HTTP 错误：status=%s detail=%s", exc.code, detail)
        raise HTTPException(status_code=502, detail=ASR_USER_MESSAGE) from exc
    except Exception as exc:
        logger.warning("ASR 文件转写服务不可用：%s", exc)
        raise HTTPException(status_code=502, detail=ASR_USER_MESSAGE) from exc

    return [TranscriptSegment(**item) for item in data.get("segments", [])]


def extract_voiceprints_with_asr_service(
    task_id: str,
    source_file_path: str,
    segments: list[TranscriptSegment],
) -> list[TranscriptSegment]:
    payload = json.dumps(
        {
            "task_id": task_id,
            "source_file_path": source_file_path,
            "segments": [_segment_payload(segment) for segment in segments],
        },
        ensure_ascii=False,
    ).encode("utf-8")
    request = urllib.request.Request(
        f"{settings.asr_service_url.rstrip('/')}/api/v1/voiceprints/extract",
        data=payload,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        timeout_seconds = _voiceprint_timeout_seconds(segments)
        with urllib.request.urlopen(request, timeout=timeout_seconds) as response:
            data = json.loads(response.read().decode("utf-8"))
        logger.info("ASR 声纹抽取服务返回 task=%s timeout=%ss", task_id, timeout_seconds)
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8", errors="ignore")
        logger.warning("ASR 声纹抽取服务 HTTP 错误：status=%s detail=%s", exc.code, detail)
        raise HTTPException(status_code=502, detail=ASR_USER_MESSAGE) from exc
    except Exception as exc:
        logger.warning("ASR 声纹抽取服务不可用：%s", exc)
        raise HTTPException(status_code=502, detail=ASR_USER_MESSAGE) from exc

    return [TranscriptSegment(**item) for item in data.get("segments", [])]


def enroll_voiceprint_audio_with_asr_service(
    request_id: str,
    source_file_path: str,
) -> dict:
    payload = json.dumps(
        {
            "request_id": request_id,
            "source_file_path": source_file_path,
        },
        ensure_ascii=False,
    ).encode("utf-8")
    request = urllib.request.Request(
        f"{settings.asr_service_url.rstrip('/')}/api/v1/voiceprints/enroll-audio",
        data=payload,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=settings.asr_voiceprint_enroll_timeout_seconds) as response:
            data = json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8", errors="ignore")
        logger.warning("ASR 声纹录入服务 HTTP 错误：status=%s detail=%s", exc.code, detail)
        raise HTTPException(status_code=502, detail=ASR_USER_MESSAGE) from exc
    except Exception as exc:
        logger.warning("ASR 声纹录入服务不可用：%s", exc)
        raise HTTPException(status_code=502, detail=ASR_USER_MESSAGE) from exc

    return data if isinstance(data, dict) else {}


def _voiceprint_timeout_seconds(segments: list[TranscriptSegment]) -> int:
    duration_ms = max((segment.end_ms or 0) for segment in segments) if segments else 0
    duration_seconds = duration_ms / 1000 if duration_ms > 0 else 0.0
    calculated = settings.asr_voiceprint_timeout_buffer_seconds + (
        max(0.0, duration_seconds) * settings.asr_voiceprint_timeout_per_audio_second
    )
    bounded = max(
        settings.asr_voiceprint_timeout_min_seconds,
        min(settings.asr_voiceprint_timeout_max_seconds, calculated),
    )
    return int(round(bounded))


def _segment_payload(segment: TranscriptSegment) -> dict:
    return {
        "speaker": segment.speaker,
        "text": segment.text,
        "timestamp": segment.timestamp,
        "start_ms": segment.start_ms,
        "end_ms": segment.end_ms,
        "speaker_id": segment.speaker_id,
        "confidence": segment.confidence,
    }


def cancel_asr_task(task_id: str) -> bool:
    clean_task_id = str(task_id or "").strip()
    if not clean_task_id:
        return False
    request = urllib.request.Request(
        f"{settings.asr_service_url.rstrip('/')}/api/v1/transcriptions/{clean_task_id}/cancel",
        data=b"{}",
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=5) as response:
            response.read()
        return True
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8", errors="ignore")
        logger.warning("ASR cancel request failed for %s: HTTP %s %s", clean_task_id, exc.code, detail)
    except Exception as exc:
        logger.warning("ASR cancel request unavailable for %s: %s", clean_task_id, exc)
    return False
