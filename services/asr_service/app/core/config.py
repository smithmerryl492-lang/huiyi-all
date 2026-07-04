import os
from pathlib import Path


def default_ffmpeg_path() -> str:
    bundled = Path("F:/Program Files/ffmpeg/bin/ffmpeg.exe")
    return str(bundled) if bundled.exists() else "ffmpeg"


def _env_value(name: str, default: str = "") -> str:
    value = os.getenv(name)
    if value is None or not value.strip():
        return default
    return value.strip()


def _aliyun_dedicated_host(workspace_id: str, region: str) -> str:
    clean_workspace = workspace_id.strip()
    clean_region = region.strip() or "cn-beijing"
    if not clean_workspace:
        return "dashscope.aliyuncs.com"
    return f"{clean_workspace}.{clean_region}.maas.aliyuncs.com"


def _https_url(host: str, path: str) -> str:
    clean_host = host.strip().removeprefix("https://").removeprefix("http://").rstrip("/")
    return f"https://{clean_host}{path}"


def _wss_url(host: str, path: str) -> str:
    clean_host = host.strip().removeprefix("wss://").removeprefix("ws://").rstrip("/")
    return f"wss://{clean_host}{path}"


class Settings:
    ffmpeg_path: str = os.getenv("HUIXIAO_FFMPEG_PATH", default_ffmpeg_path())
    device: str = os.getenv("HUIXIAO_ASR_DEVICE", "cpu")
    max_speakers: int = int(os.getenv("HUIXIAO_ASR_MAX_SPEAKERS", "15"))
    voiceprint_max_samples_per_speaker: int = int(os.getenv("HUIXIAO_ASR_VOICEPRINT_MAX_SAMPLES_PER_SPEAKER", "3"))
    voiceprint_max_total_samples: int = int(os.getenv("HUIXIAO_ASR_VOICEPRINT_MAX_TOTAL_SAMPLES", "12"))
    funasr_runtime_ws_url: str = os.getenv("HUIXIAO_FUNASR_RUNTIME_WS_URL", "ws://127.0.0.1:10095")
    live_asr_provider: str = os.getenv("HUIXIAO_LIVE_ASR_PROVIDER", "aliyun").strip().lower()
    aliyun_dashscope_api_key: str = (
        os.getenv("HUIXIAO_ALIYUN_DASHSCOPE_API_KEY")
        or os.getenv("DASHSCOPE_API_KEY")
        or os.getenv("HUIXIAO_LLM_API_KEY")
        or ""
    ).strip()
    aliyun_workspace_id: str = _env_value("HUIXIAO_ALIYUN_WORKSPACE_ID")
    aliyun_region: str = _env_value("HUIXIAO_ALIYUN_REGION", "cn-beijing")
    aliyun_api_host: str = _env_value("HUIXIAO_ALIYUN_API_HOST", _aliyun_dedicated_host(aliyun_workspace_id, aliyun_region))
    aliyun_realtime_ws_url: str = _env_value(
        "HUIXIAO_ALIYUN_REALTIME_WS_URL",
        _wss_url(aliyun_api_host, "/api-ws/v1/realtime"),
    )
    aliyun_realtime_model: str = os.getenv("HUIXIAO_ALIYUN_REALTIME_MODEL", "qwen3-asr-flash-realtime")
    aliyun_file_transcription_url: str = _env_value(
        "HUIXIAO_ALIYUN_FILE_TRANSCRIPTION_URL",
        _https_url(aliyun_api_host, "/api/v1/services/audio/asr/transcription"),
    )
    aliyun_file_task_url: str = _env_value(
        "HUIXIAO_ALIYUN_FILE_TASK_URL",
        _https_url(aliyun_api_host, "/api/v1/tasks"),
    )
    aliyun_file_model: str = os.getenv("HUIXIAO_ALIYUN_FILE_MODEL", "qwen3-asr-flash-filetrans")
    aliyun_file_poll_interval_seconds: float = float(os.getenv("HUIXIAO_ALIYUN_FILE_POLL_INTERVAL_SECONDS", "2"))
    aliyun_file_poll_timeout_seconds: int = int(os.getenv("HUIXIAO_ALIYUN_FILE_POLL_TIMEOUT_SECONDS", str(2 * 60 * 60)))
    aliyun_live_vad_threshold: float = float(os.getenv("HUIXIAO_ALIYUN_LIVE_VAD_THRESHOLD", "0.35"))
    aliyun_live_vad_silence_duration_ms: int = int(os.getenv("HUIXIAO_ALIYUN_LIVE_VAD_SILENCE_DURATION_MS", "450"))
    aliyun_live_filter_filler: bool = os.getenv("HUIXIAO_ALIYUN_LIVE_FILTER_FILLER", "true").lower() in {"1", "true", "yes"}
    aliyun_live_filler_max_duration_ms: int = int(os.getenv("HUIXIAO_ALIYUN_LIVE_FILLER_MAX_DURATION_MS", "1200"))
    audio_prepare_timeout_seconds: int = int(os.getenv("HUIXIAO_AUDIO_PREPARE_TIMEOUT_SECONDS", str(4 * 60 * 60)))
    live_mode: str = os.getenv("HUIXIAO_LIVE_ASR_MODE", "online_refine")
    live_chunk_size: list[int] = [
        int(item.strip())
        for item in os.getenv("HUIXIAO_LIVE_CHUNK_SIZE", "0,8,4").split(",")
        if item.strip()
    ]
    live_chunk_interval: int = int(os.getenv("HUIXIAO_LIVE_CHUNK_INTERVAL", "8"))
    live_encoder_look_back: int = int(os.getenv("HUIXIAO_LIVE_ENCODER_LOOK_BACK", "4"))
    live_decoder_look_back: int = int(os.getenv("HUIXIAO_LIVE_DECODER_LOOK_BACK", "1"))
    live_require_speaker: bool = os.getenv("HUIXIAO_LIVE_REQUIRE_SPEAKER", "false").lower() == "true"
    file_asr_frame_ms: int = int(os.getenv("HUIXIAO_FILE_ASR_FRAME_MS", "100"))
    file_asr_send_realtime_factor: float = float(os.getenv("HUIXIAO_FILE_ASR_SEND_REALTIME_FACTOR", "6.0"))
    file_asr_flush_silence_frames: int = int(os.getenv("HUIXIAO_FILE_ASR_FLUSH_SILENCE_FRAMES", "10"))
    file_asr_inactivity_timeout_sec: int = int(os.getenv("HUIXIAO_FILE_ASR_INACTIVITY_TIMEOUT_SEC", "8"))
    file_asr_runtime_timeout_min_seconds: int = int(os.getenv("HUIXIAO_FILE_ASR_RUNTIME_TIMEOUT_MIN_SECONDS", "1800"))
    file_asr_runtime_timeout_buffer_seconds: int = int(os.getenv("HUIXIAO_FILE_ASR_RUNTIME_TIMEOUT_BUFFER_SECONDS", "1800"))
    file_asr_runtime_timeout_per_audio_second: float = float(os.getenv("HUIXIAO_FILE_ASR_RUNTIME_TIMEOUT_PER_AUDIO_SECOND", "3.0"))
    file_asr_runtime_timeout_max_seconds: int = int(os.getenv("HUIXIAO_FILE_ASR_RUNTIME_TIMEOUT_MAX_SECONDS", str(8 * 60 * 60)))
    max_live_sessions: int = max(1, int(os.getenv("HUIXIAO_ASR_MAX_LIVE_SESSIONS", "24")))
    max_file_transcriptions: int = max(1, int(os.getenv("HUIXIAO_ASR_MAX_FILE_TRANSCRIPTIONS", "2")))
    max_voiceprint_jobs: int = max(1, int(os.getenv("HUIXIAO_ASR_MAX_VOICEPRINT_JOBS", "2")))


settings = Settings()
