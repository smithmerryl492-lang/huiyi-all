import logging
import threading

from fastapi import FastAPI, HTTPException, WebSocket

from app.core.config import settings
from app.inference.aliyun_live_gateway import aliyun_live_gateway
from app.inference.funasr_file_engine import CancelledTranscriptionError, file_engine
from app.inference.funasr_live_gateway import live_gateway
from app.schemas import (
    TranscribeRequest,
    TranscribeResponse,
    VoiceprintEnrollAudioRequest,
    VoiceprintEnrollAudioResponse,
    VoiceprintExtractRequest,
    VoiceprintExtractResponse,
)


logger = logging.getLogger(__name__)
ASR_USER_MESSAGE = "语音识别暂时失败，请稍后重试"
LIVE_BUSY_MESSAGE = "实时转写暂时繁忙，请稍后再试"

_file_transcription_gate = threading.BoundedSemaphore(settings.max_file_transcriptions)
_voiceprint_gate = threading.BoundedSemaphore(settings.max_voiceprint_jobs)
_live_counter_lock = threading.Lock()
_active_live_sessions = 0


app = FastAPI(
    title="会晓 AI ASR Service",
    version="0.1.0",
    description="ASR 推理服务，接入阿里云实时与文件转写。",
)


@app.get("/health")
def health() -> dict:
    return {
        "status": "ok",
        "service": "asr_service",
        "asr_backend": "Aliyun realtime/file + CAMPPlus speaker postprocess",
        "device": settings.device,
        "live_engine": _live_engine_name(),
        "live_provider": settings.live_asr_provider,
        "file_engine": f"Aliyun {settings.aliyun_realtime_model} + CAMPPlus",
        "offline_model_owner": "aliyun_dashscope",
        "offline_model_loaded": file_engine.model_loaded,
        "funasr_runtime_ws_url": settings.funasr_runtime_ws_url,
        "aliyun_realtime_model": settings.aliyun_realtime_model,
        "aliyun_live_vad_threshold": settings.aliyun_live_vad_threshold,
        "aliyun_live_vad_silence_duration_ms": settings.aliyun_live_vad_silence_duration_ms,
        "aliyun_live_filter_filler": settings.aliyun_live_filter_filler,
        "live_require_speaker": settings.live_require_speaker,
        "max_live_sessions": settings.max_live_sessions,
        "max_file_transcriptions": settings.max_file_transcriptions,
        "max_voiceprint_jobs": settings.max_voiceprint_jobs,
        "active_live_sessions": _active_live_sessions,
    }


@app.post("/admin/warmup")
def warmup() -> dict:
    try:
        file_engine.warmup()
    except Exception as exc:
        logger.exception("ASR warmup failed")
        raise HTTPException(status_code=500, detail=ASR_USER_MESSAGE) from exc
    return {
        "status": "ok",
        "service": "asr_service",
        "offline_model_loaded": file_engine.model_loaded,
    }


@app.post("/api/v1/transcriptions", response_model=TranscribeResponse)
def transcribe(request: TranscribeRequest) -> TranscribeResponse:
    _file_transcription_gate.acquire()
    try:
        return file_engine.transcribe(request)
    except CancelledTranscriptionError as exc:
        raise HTTPException(status_code=409, detail="任务已终止") from exc
    except Exception as exc:
        logger.exception("ASR transcription failed: %s", request.task_id)
        raise HTTPException(status_code=500, detail=ASR_USER_MESSAGE) from exc
    finally:
        _file_transcription_gate.release()


@app.post("/api/v1/voiceprints/extract", response_model=VoiceprintExtractResponse)
def extract_voiceprints(request: VoiceprintExtractRequest) -> VoiceprintExtractResponse:
    _voiceprint_gate.acquire()
    try:
        return file_engine.extract_voiceprints(request)
    except Exception as exc:
        logger.exception("ASR voiceprint extraction failed: %s", request.task_id)
        raise HTTPException(status_code=500, detail=ASR_USER_MESSAGE) from exc
    finally:
        _voiceprint_gate.release()


@app.post("/api/v1/voiceprints/enroll-audio", response_model=VoiceprintEnrollAudioResponse)
def enroll_voiceprint_audio(request: VoiceprintEnrollAudioRequest) -> VoiceprintEnrollAudioResponse:
    _voiceprint_gate.acquire()
    try:
        return file_engine.enroll_voiceprint_audio(request)
    except ValueError as exc:
        raise HTTPException(status_code=422, detail=_safe_voiceprint_message(str(exc))) from exc
    except Exception as exc:
        logger.exception("ASR voiceprint enrollment failed: %s", request.request_id)
        raise HTTPException(status_code=500, detail=ASR_USER_MESSAGE) from exc
    finally:
        _voiceprint_gate.release()


@app.post("/api/v1/transcriptions/{task_id}/cancel")
def cancel_transcription(task_id: str) -> dict:
    canceled = file_engine.cancel(task_id)
    return {
        "status": "ok",
        "task_id": task_id,
        "canceled": canceled,
    }


@app.websocket("/api/v1/live/ws")
async def live_transcribe(websocket: WebSocket) -> None:
    session_id = websocket.query_params.get("session_id") or "live-session"
    if not _try_acquire_live_session():
        await websocket.accept()
        await websocket.send_json({"type": "error", "code": "live_busy", "message": LIVE_BUSY_MESSAGE})
        await websocket.close(code=1013)
        return
    try:
        if settings.live_asr_provider == "aliyun":
            await aliyun_live_gateway.run(websocket, session_id)
        else:
            await live_gateway.run(websocket, session_id)
    finally:
        _release_live_session()


def _safe_voiceprint_message(message: str) -> str:
    clean = str(message or "").strip()
    if any(marker in clean for marker in ("声纹样本", "样本质量", "人声", "音频")) and len(clean) <= 80:
        return clean
    return ASR_USER_MESSAGE


def _live_engine_name() -> str:
    if settings.live_asr_provider == "aliyun":
        return f"Aliyun {settings.aliyun_realtime_model}"
    return f"FunASR {settings.live_mode}"


def _try_acquire_live_session() -> bool:
    global _active_live_sessions
    with _live_counter_lock:
        if _active_live_sessions >= settings.max_live_sessions:
            return False
        _active_live_sessions += 1
        return True


def _release_live_session() -> None:
    global _active_live_sessions
    with _live_counter_lock:
        _active_live_sessions = max(0, _active_live_sessions - 1)
