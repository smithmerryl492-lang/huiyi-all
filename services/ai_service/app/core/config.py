import os
from pathlib import Path


def bool_env(name: str, default: bool) -> bool:
    raw = os.getenv(name)
    if raw is None:
        return default
    return raw.strip().lower() in {"1", "true", "yes", "on"}


def load_local_env() -> None:
    env_path = Path(__file__).resolve().parents[2] / ".env"
    if not env_path.exists():
        return
    for line in env_path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        os.environ.setdefault(key.strip(), value.strip().strip("'\""))


load_local_env()


class Settings:
    base_url: str = os.getenv("HUIXIAO_LLM_BASE_URL", "https://dashscope.aliyuncs.com/compatible-mode/v1").rstrip("/")
    api_key: str = os.getenv("HUIXIAO_LLM_API_KEY", "")
    llm_model: str = os.getenv("HUIXIAO_LLM_MODEL", "qwen3.7-plus")
    embedding_model: str = os.getenv("HUIXIAO_EMBEDDING_MODEL", "text-embedding-v4")
    embedding_dimensions: int = int(os.getenv("HUIXIAO_EMBEDDING_DIMENSIONS", "1024"))
    timeout_seconds: int = int(os.getenv("HUIXIAO_LLM_TIMEOUT_SECONDS", "120"))
    attempt_timeout_seconds: int = int(os.getenv("HUIXIAO_LLM_ATTEMPT_TIMEOUT_SECONDS", "120"))
    retry_count: int = int(os.getenv("HUIXIAO_LLM_RETRY_COUNT", "4"))
    temperature: float = float(os.getenv("HUIXIAO_LLM_TEMPERATURE", "0.2"))
    max_tokens: int = int(os.getenv("HUIXIAO_LLM_MAX_TOKENS", "4096"))
    embedding_timeout_seconds: int = int(os.getenv("HUIXIAO_EMBEDDING_TIMEOUT_SECONDS", "600"))
    knowledge_plan_max_tokens: int = int(os.getenv("HUIXIAO_KNOWLEDGE_PLAN_MAX_TOKENS", "512"))
    knowledge_plan_enable_thinking: bool = bool_env("HUIXIAO_KNOWLEDGE_PLAN_ENABLE_THINKING", False)
    knowledge_plan_timeout_seconds: int = int(os.getenv("HUIXIAO_KNOWLEDGE_PLAN_TIMEOUT_SECONDS", "180"))
    knowledge_answer_max_tokens: int = int(os.getenv("HUIXIAO_KNOWLEDGE_ANSWER_MAX_TOKENS", str(max_tokens)))
    knowledge_answer_analysis_thinking_budget: int = int(os.getenv("HUIXIAO_KNOWLEDGE_ANSWER_ANALYSIS_THINKING_BUDGET", "256"))
    knowledge_answer_retry_thinking_budget: int = int(os.getenv("HUIXIAO_KNOWLEDGE_ANSWER_RETRY_THINKING_BUDGET", "512"))
    knowledge_answer_timeout_seconds: int = int(os.getenv("HUIXIAO_KNOWLEDGE_ANSWER_TIMEOUT_SECONDS", "600"))
    minutes_max_tokens: int = int(os.getenv("HUIXIAO_MINUTES_MAX_TOKENS", "1024"))
    minutes_enable_thinking: bool = bool_env("HUIXIAO_MINUTES_ENABLE_THINKING", False)
    minutes_thinking_budget: int = int(os.getenv("HUIXIAO_MINUTES_THINKING_BUDGET", "0"))
    minutes_timeout_min_seconds: int = int(os.getenv("HUIXIAO_MINUTES_TIMEOUT_MIN_SECONDS", "900"))
    minutes_timeout_buffer_seconds: int = int(os.getenv("HUIXIAO_MINUTES_TIMEOUT_BUFFER_SECONDS", "900"))
    minutes_timeout_per_audio_second: float = float(os.getenv("HUIXIAO_MINUTES_TIMEOUT_PER_AUDIO_SECOND", "2.0"))
    minutes_timeout_max_seconds: int = int(os.getenv("HUIXIAO_MINUTES_TIMEOUT_MAX_SECONDS", str(5 * 60 * 60)))
    minutes_retry_count: int = int(os.getenv("HUIXIAO_MINUTES_RETRY_COUNT", "1"))
    minutes_concurrency: int = max(1, int(os.getenv("HUIXIAO_AI_MINUTES_CONCURRENCY", "2")))
    knowledge_answer_concurrency: int = max(1, int(os.getenv("HUIXIAO_AI_KNOWLEDGE_ANSWER_CONCURRENCY", "2")))
    embedding_concurrency: int = max(1, int(os.getenv("HUIXIAO_AI_EMBEDDING_CONCURRENCY", "2")))

    @property
    def model_connected(self) -> bool:
        return bool(self.api_key)


settings = Settings()
