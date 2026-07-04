import os
from pathlib import Path


def required_env(name: str) -> str:
    value = os.getenv(name, "").strip()
    if not value:
        raise RuntimeError(f"{name} is required. Set it to the shared remote database URL.")
    return value


def required_remote_database_url(name: str) -> str:
    value = required_env(name)
    if value.lower().startswith("sqlite"):
        raise RuntimeError(f"{name} must point to the shared remote database. SQLite/local databases are not supported.")
    return value


def env_value(name: str, default: str = "") -> str:
    value = os.getenv(name)
    if value is None or not value.strip():
        return default
    return value.strip()


def env_or_file_value(name: str, file_name: str = "") -> str:
    value = env_value(name)
    if value:
        return value
    path_value = env_value(f"{name}_PATH")
    if path_value:
        path = Path(path_value)
        if path.is_file():
            return path.read_text(encoding="utf-8").strip()
    if file_name:
        path = Path(file_name)
        if path.is_file():
            return path.read_text(encoding="utf-8").strip()
    return ""


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
    api_prefix: str = "/api/v1"
    host: str = os.getenv("HUIXIAO_API_HOST", "127.0.0.1")
    port: int = int(os.getenv("HUIXIAO_API_PORT", "8080"))
    database_url: str = required_remote_database_url("HUIXIAO_DATABASE_URL")
    db_connect_timeout_seconds: int = int(os.getenv("HUIXIAO_DB_CONNECT_TIMEOUT_SECONDS", "10"))
    db_read_timeout_seconds: int = int(os.getenv("HUIXIAO_DB_READ_TIMEOUT_SECONDS", "60"))
    db_write_timeout_seconds: int = int(os.getenv("HUIXIAO_DB_WRITE_TIMEOUT_SECONDS", "60"))
    asr_service_url: str = os.getenv("HUIXIAO_ASR_SERVICE_URL", "http://127.0.0.1:8081")
    asr_live_ws_url: str = os.getenv("HUIXIAO_ASR_LIVE_WS_URL", "ws://127.0.0.1:8081/api/v1/live/ws")
    public_api_base_url: str = env_value("HUIXIAO_PUBLIC_API_BASE_URL")
    temp_audio_url_ttl_seconds: int = max(60, int(os.getenv("HUIXIAO_TEMP_AUDIO_URL_TTL_SECONDS", "1800")))
    aliyun_dashscope_api_key: str = env_value("HUIXIAO_ALIYUN_DASHSCOPE_API_KEY", env_value("HUIXIAO_LLM_API_KEY"))
    aliyun_workspace_id: str = env_value("HUIXIAO_ALIYUN_WORKSPACE_ID")
    aliyun_region: str = env_value("HUIXIAO_ALIYUN_REGION", "cn-beijing")
    aliyun_api_host: str = env_value("HUIXIAO_ALIYUN_API_HOST", _aliyun_dedicated_host(aliyun_workspace_id, aliyun_region))
    aliyun_realtime_ws_url: str = env_value("HUIXIAO_ALIYUN_REALTIME_WS_URL", _wss_url(aliyun_api_host, "/api-ws/v1/realtime"))
    aliyun_realtime_token_url: str = env_value("HUIXIAO_ALIYUN_REALTIME_TOKEN_URL", _https_url(aliyun_api_host, "/api/v1/tokens"))
    aliyun_realtime_model: str = env_value("HUIXIAO_ALIYUN_REALTIME_MODEL", "qwen3-asr-flash-realtime")
    aliyun_live_vad_threshold: float = float(os.getenv("HUIXIAO_ALIYUN_LIVE_VAD_THRESHOLD", "0.35"))
    aliyun_live_vad_silence_duration_ms: int = int(os.getenv("HUIXIAO_ALIYUN_LIVE_VAD_SILENCE_DURATION_MS", "450"))
    aliyun_live_filter_filler: bool = os.getenv("HUIXIAO_ALIYUN_LIVE_FILTER_FILLER", "true").lower() in {"1", "true", "yes"}
    aliyun_live_filler_max_duration_ms: int = int(os.getenv("HUIXIAO_ALIYUN_LIVE_FILLER_MAX_DURATION_MS", "1200"))
    aliyun_realtime_direct_enabled: bool = os.getenv("HUIXIAO_ALIYUN_REALTIME_DIRECT_ENABLED", "true").lower() in {"1", "true", "yes"}
    aliyun_realtime_token_ttl_seconds: int = max(60, min(1800, int(os.getenv("HUIXIAO_ALIYUN_REALTIME_TOKEN_TTL_SECONDS", "900"))))
    aliyun_realtime_token_request_timeout_seconds: float = float(os.getenv("HUIXIAO_ALIYUN_REALTIME_TOKEN_REQUEST_TIMEOUT_SECONDS", "5"))
    aliyun_realtime_token_refresh_margin_seconds: int = max(30, int(os.getenv("HUIXIAO_ALIYUN_REALTIME_TOKEN_REFRESH_MARGIN_SECONDS", "180")))
    aliyun_realtime_prewarm_enabled: bool = os.getenv("HUIXIAO_ALIYUN_REALTIME_PREWARM_ENABLED", "true").lower() in {"1", "true", "yes"}
    asr_file_timeout_seconds: int = int(os.getenv("HUIXIAO_ASR_FILE_TIMEOUT_SECONDS", str(9 * 60 * 60)))
    asr_voiceprint_timeout_min_seconds: int = int(os.getenv("HUIXIAO_ASR_VOICEPRINT_TIMEOUT_MIN_SECONDS", "600"))
    asr_voiceprint_timeout_buffer_seconds: int = int(os.getenv("HUIXIAO_ASR_VOICEPRINT_TIMEOUT_BUFFER_SECONDS", "600"))
    asr_voiceprint_timeout_per_audio_second: float = float(os.getenv("HUIXIAO_ASR_VOICEPRINT_TIMEOUT_PER_AUDIO_SECOND", "1.5"))
    asr_voiceprint_timeout_max_seconds: int = int(os.getenv("HUIXIAO_ASR_VOICEPRINT_TIMEOUT_MAX_SECONDS", str(4 * 60 * 60)))
    asr_voiceprint_enroll_timeout_seconds: int = int(os.getenv("HUIXIAO_ASR_VOICEPRINT_ENROLL_TIMEOUT_SECONDS", "600"))
    ai_service_url: str = os.getenv("HUIXIAO_AI_SERVICE_URL", "http://127.0.0.1:8082")
    ai_minutes_timeout_min_seconds: int = int(os.getenv("HUIXIAO_AI_MINUTES_TIMEOUT_MIN_SECONDS", "1800"))
    ai_minutes_timeout_buffer_seconds: int = int(os.getenv("HUIXIAO_AI_MINUTES_TIMEOUT_BUFFER_SECONDS", "1800"))
    ai_minutes_timeout_per_audio_second: float = float(os.getenv("HUIXIAO_AI_MINUTES_TIMEOUT_PER_AUDIO_SECOND", "2.5"))
    ai_minutes_timeout_max_seconds: int = int(os.getenv("HUIXIAO_AI_MINUTES_TIMEOUT_MAX_SECONDS", str(6 * 60 * 60)))
    ai_embedding_timeout_seconds: int = int(os.getenv("HUIXIAO_AI_EMBEDDING_TIMEOUT_SECONDS", "600"))
    ai_knowledge_plan_timeout_seconds: int = int(os.getenv("HUIXIAO_AI_KNOWLEDGE_PLAN_TIMEOUT_SECONDS", "180"))
    ai_knowledge_answer_timeout_seconds: int = int(os.getenv("HUIXIAO_AI_KNOWLEDGE_ANSWER_TIMEOUT_SECONDS", "600"))
    knowledge_chunk_cache_seconds: float = float(os.getenv("HUIXIAO_KNOWLEDGE_CHUNK_CACHE_SECONDS", "300"))
    stale_processing_timeout_seconds: int = int(os.getenv("HUIXIAO_STALE_PROCESSING_TIMEOUT_SECONDS", str(18 * 60 * 60)))
    task_processing_concurrency: int = max(1, int(os.getenv("HUIXIAO_TASK_PROCESSING_CONCURRENCY", "1")))
    data_dir: Path = Path(os.getenv("HUIXIAO_DATA_DIR", "./data")).resolve()
    max_upload_mb: int = int(os.getenv("HUIXIAO_MAX_UPLOAD_MB", "500"))
    cors_origins: list[str] = [
        item.strip()
        for item in os.getenv("HUIXIAO_CORS_ORIGINS", "*").split(",")
        if item.strip()
    ]
    auth_token_secret: str = os.getenv("HUIXIAO_AUTH_TOKEN_SECRET", "huixiao-dev-token-secret-change-me")
    auth_token_ttl_seconds: int = int(os.getenv("HUIXIAO_AUTH_TOKEN_TTL_SECONDS", str(60 * 60 * 24 * 30)))
    auth_allow_legacy_user_id: bool = os.getenv("HUIXIAO_AUTH_ALLOW_LEGACY_USER_ID", "false").lower() in {"1", "true", "yes"}
    admin_token_secret: str = os.getenv("HUIXIAO_ADMIN_TOKEN_SECRET", auth_token_secret)
    admin_token_ttl_seconds: int = int(os.getenv("HUIXIAO_ADMIN_TOKEN_TTL_SECONDS", str(60 * 60 * 12)))
    admin_bootstrap_username: str = os.getenv("HUIXIAO_ADMIN_BOOTSTRAP_USERNAME", "").strip()
    admin_bootstrap_password: str = os.getenv("HUIXIAO_ADMIN_BOOTSTRAP_PASSWORD", "").strip()
    admin_bootstrap_display_name: str = os.getenv("HUIXIAO_ADMIN_BOOTSTRAP_DISPLAY_NAME", "内部管理员").strip() or "内部管理员"
    allow_server_data_clear: bool = os.getenv("HUIXIAO_ALLOW_SERVER_DATA_CLEAR", "false").lower() in {"1", "true", "yes"}
    voiceprint_enabled: bool = os.getenv("HUIXIAO_VOICEPRINT_ENABLED", "true").lower() in {"1", "true", "yes"}
    max_speaker_profiles_per_user: int = int(os.getenv("HUIXIAO_MAX_SPEAKER_PROFILES_PER_USER", "50"))
    max_speaker_samples_per_profile: int = int(os.getenv("HUIXIAO_MAX_SPEAKER_SAMPLES_PER_PROFILE", "20"))
    speaker_match_threshold: float = float(os.getenv("HUIXIAO_SPEAKER_MATCH_THRESHOLD", "0.72"))
    speaker_match_margin: float = float(os.getenv("HUIXIAO_SPEAKER_MATCH_MARGIN", "0.04"))
    speaker_min_match_quality: float = float(os.getenv("HUIXIAO_SPEAKER_MIN_MATCH_QUALITY", "0.55"))
    speaker_min_match_duration_ms: int = int(os.getenv("HUIXIAO_SPEAKER_MIN_MATCH_DURATION_MS", "3000"))
    speaker_min_enroll_quality: float = float(os.getenv("HUIXIAO_SPEAKER_MIN_ENROLL_QUALITY", "0.55"))
    speaker_min_enroll_duration_ms: int = int(os.getenv("HUIXIAO_SPEAKER_MIN_ENROLL_DURATION_MS", "5000"))

    alipay_app_id: str = env_value("HUIXIAO_ALIPAY_APP_ID")
    alipay_private_key: str = env_or_file_value("HUIXIAO_ALIPAY_PRIVATE_KEY")
    alipay_public_key: str = env_or_file_value("HUIXIAO_ALIPAY_PUBLIC_KEY")
    alipay_notify_url: str = env_value("HUIXIAO_ALIPAY_NOTIFY_URL")
    alipay_gateway_url: str = env_value("HUIXIAO_ALIPAY_GATEWAY_URL", "https://openapi.alipay.com/gateway.do")
    alipay_aes_key: str = env_or_file_value("HUIXIAO_ALIPAY_AES_KEY")
    alipay_payment_mode: str = env_value("HUIXIAO_ALIPAY_PAYMENT_MODE", "wap").lower()
    alipay_return_url: str = env_value("HUIXIAO_ALIPAY_RETURN_URL")
    apple_iap_enabled: bool = os.getenv("HUIXIAO_APPLE_IAP_ENABLED", "false").lower() in {"1", "true", "yes"}
    apple_product_prefix: str = env_value("HUIXIAO_APPLE_PRODUCT_PREFIX", "com.kunqiong.huiyi")
    apple_bundle_id: str = env_value("HUIXIAO_APPLE_BUNDLE_ID", "com.huiyi.app.ios")
    apple_issuer_id: str = env_value("HUIXIAO_APPLE_ISSUER_ID")
    apple_key_id: str = env_value("HUIXIAO_APPLE_KEY_ID")
    apple_private_key: str = env_or_file_value("HUIXIAO_APPLE_PRIVATE_KEY")
    apple_environment: str = env_value("HUIXIAO_APPLE_ENVIRONMENT", "sandbox").lower()

    tencent_sms_secret_id: str = os.getenv("HUIXIAO_TENCENT_SMS_SECRET_ID", "")
    tencent_sms_secret_key: str = os.getenv("HUIXIAO_TENCENT_SMS_SECRET_KEY", "")
    tencent_sms_sdk_app_id: str = os.getenv("HUIXIAO_TENCENT_SMS_SDK_APP_ID", "")
    tencent_sms_sign_name: str = os.getenv("HUIXIAO_TENCENT_SMS_SIGN_NAME", "")
    tencent_sms_region: str = os.getenv("HUIXIAO_TENCENT_SMS_REGION", "ap-guangzhou")
    sms_template_id: str = env_value("HUIXIAO_SMS_TEMPLATE_ID")
    sms_template_login_id: str = env_value("HUIXIAO_SMS_TEMPLATE_LOGIN_ID", sms_template_id)
    sms_template_change_phone_id: str = env_value("HUIXIAO_SMS_TEMPLATE_CHANGE_PHONE_ID", sms_template_id)
    sms_template_change_password_id: str = env_value("HUIXIAO_SMS_TEMPLATE_CHANGE_PASSWORD_ID", sms_template_id)
    sms_code_ttl_seconds: int = int(os.getenv("HUIXIAO_SMS_CODE_TTL_SECONDS", "300"))
    sms_resend_cooldown_seconds: int = int(os.getenv("HUIXIAO_SMS_RESEND_COOLDOWN_SECONDS", "60"))
    sms_code_length: int = int(os.getenv("HUIXIAO_SMS_CODE_LENGTH", "6"))
    sms_template_param_mode: str = os.getenv("HUIXIAO_SMS_TEMPLATE_PARAM_MODE", "code")
    sms_max_attempts: int = int(os.getenv("HUIXIAO_SMS_MAX_ATTEMPTS", "5"))
    sms_phone_daily_limit: int = int(os.getenv("HUIXIAO_SMS_PHONE_DAILY_LIMIT", "10"))
    sms_ip_daily_limit: int = int(os.getenv("HUIXIAO_SMS_IP_DAILY_LIMIT", "60"))
    sms_dev_code: str = os.getenv("HUIXIAO_SMS_DEV_CODE", "")

    @property
    def upload_dir(self) -> Path:
        return self.data_dir / "uploads"


settings = Settings()
settings.data_dir.mkdir(parents=True, exist_ok=True)
settings.upload_dir.mkdir(parents=True, exist_ok=True)
