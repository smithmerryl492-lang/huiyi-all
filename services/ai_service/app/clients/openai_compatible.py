import json
import logging
import re
import time
import urllib.error
import urllib.request
from typing import Any

from fastapi import HTTPException

from app.core.config import settings


logger = logging.getLogger(__name__)
logger.setLevel(logging.INFO)
if not logger.handlers:
    handler = logging.StreamHandler()
    handler.setFormatter(logging.Formatter("%(levelname)s:%(name)s:%(message)s"))
    logger.addHandler(handler)
logger.propagate = False

MODEL_USER_MESSAGE = "智能处理暂时失败，请稍后重试"
MODEL_FORMAT_MESSAGE = "模型返回格式异常，请稍后重试"


class OpenAICompatibleClient:
    def chat_json(
        self,
        messages: list[dict[str, str]],
        max_tokens: int | None = None,
        omit_max_tokens: bool = False,
        enable_thinking: bool | None = None,
        thinking_budget: int | None = None,
        request_timeout_seconds: int | None = None,
        retry_count: int | None = None,
    ) -> dict[str, Any]:
        if not settings.llm_model:
            logger.error("LLM model is not configured")
            raise HTTPException(status_code=503, detail=MODEL_USER_MESSAGE)
        last_error = ""
        json_retry_count = settings.retry_count if retry_count is None else max(0, retry_count)
        for attempt in range(max(1, json_retry_count + 1)):
            try:
                return self._chat_json_with_model(
                    settings.llm_model,
                    messages,
                    max_tokens=max_tokens,
                    omit_max_tokens=omit_max_tokens,
                    enable_thinking=enable_thinking,
                    thinking_budget=thinking_budget,
                    request_timeout_seconds=request_timeout_seconds,
                    retry_count=retry_count,
                )
            except HTTPException as exc:
                last_error = str(exc.detail)
                if not _is_json_parse_error(last_error) or attempt >= json_retry_count:
                    raise
                time.sleep(_retry_delay_seconds(attempt, None))
        logger.warning("模型多次未返回有效 JSON：%s", last_error)
        raise HTTPException(status_code=502, detail=MODEL_USER_MESSAGE)

    def _chat_json_with_model(
        self,
        model: str,
        messages: list[dict[str, str]],
        max_tokens: int | None = None,
        omit_max_tokens: bool = False,
        enable_thinking: bool | None = None,
        thinking_budget: int | None = None,
        request_timeout_seconds: int | None = None,
        retry_count: int | None = None,
    ) -> dict[str, Any]:
        payload: dict[str, Any] = {
            "model": model,
            "messages": messages,
            "temperature": settings.temperature,
            "response_format": {"type": "json_object"},
        }
        if not omit_max_tokens:
            payload["max_tokens"] = max_tokens or settings.max_tokens
        if enable_thinking is not None:
            payload["enable_thinking"] = enable_thinking
        if thinking_budget is not None and thinking_budget > 0:
            payload["thinking_budget"] = thinking_budget
        data = self._post(
            "/chat/completions",
            payload,
            request_timeout_seconds=request_timeout_seconds,
            retry_count=retry_count,
        )
        content = data["choices"][0]["message"]["content"]
        return _parse_json_object(content)

    def embeddings(self, texts: list[str], request_timeout_seconds: int | None = None) -> list[list[float]]:
        data = self._post(
            "/embeddings",
            {
                "model": settings.embedding_model,
                "input": texts,
                "dimensions": settings.embedding_dimensions,
            },
            request_timeout_seconds=request_timeout_seconds,
        )
        return [item["embedding"] for item in data.get("data", [])]

    def _post(
        self,
        path: str,
        payload: dict[str, Any],
        request_timeout_seconds: int | None = None,
        retry_count: int | None = None,
    ) -> dict[str, Any]:
        if not settings.api_key:
            logger.error("LLM API key is not configured")
            raise HTTPException(status_code=503, detail=MODEL_USER_MESSAGE)
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        timeout = (
            request_timeout_seconds
            or (settings.attempt_timeout_seconds if path == "/chat/completions" else settings.timeout_seconds)
        )
        request_retry_count = settings.retry_count if retry_count is None else max(0, retry_count)
        last_error = ""
        last_status: int | None = None
        for attempt in range(max(1, request_retry_count + 1)):
            request = urllib.request.Request(
                f"{settings.base_url}{path}",
                data=body,
                headers={
                    "Authorization": f"Bearer {settings.api_key}",
                    "Content-Type": "application/json",
                },
                method="POST",
            )
            try:
                started_at = time.perf_counter()
                with urllib.request.urlopen(request, timeout=timeout) as response:
                    data = json.loads(response.read().decode("utf-8"))
                if path == "/chat/completions":
                    _log_chat_usage(payload, data, time.perf_counter() - started_at, attempt)
                return data
            except urllib.error.HTTPError as exc:
                detail = exc.read().decode("utf-8", errors="ignore")
                last_status = exc.code
                last_error = detail or f"HTTP {exc.code}"
                if exc.code not in {429, 500, 502, 503, 504} or attempt >= request_retry_count:
                    logger.warning("模型服务调用失败：status=%s detail=%s", exc.code, last_error)
                    raise HTTPException(status_code=502, detail=MODEL_USER_MESSAGE) from exc
            except Exception as exc:
                last_status = None
                last_error = str(exc)
                if attempt >= request_retry_count:
                    logger.warning("模型服务不可用：%s", last_error)
                    raise HTTPException(status_code=502, detail=MODEL_USER_MESSAGE) from exc
            time.sleep(_retry_delay_seconds(attempt, last_status))
        logger.warning("模型服务重试后仍不可用：%s", last_error)
        raise HTTPException(status_code=502, detail=MODEL_USER_MESSAGE)


def _parse_json_object(content: str) -> dict[str, Any]:
    try:
        return json.loads(content)
    except json.JSONDecodeError:
        match = re.search(r"\{.*\}", content, flags=re.S)
        if not match:
            raise HTTPException(status_code=502, detail=MODEL_FORMAT_MESSAGE)
        try:
            return json.loads(match.group(0))
        except json.JSONDecodeError as exc:
            logger.warning("模型返回 JSON 不完整：%s", exc.msg)
            raise HTTPException(status_code=502, detail=MODEL_FORMAT_MESSAGE) from exc


def _is_json_parse_error(message: str) -> bool:
    return "JSON" in message or "格式异常" in message


def _log_chat_usage(payload: dict[str, Any], data: dict[str, Any], elapsed_seconds: float, attempt: int) -> None:
    usage = data.get("usage") or {}
    completion_details = usage.get("completion_tokens_details") or {}
    prompt_details = usage.get("prompt_tokens_details") or {}
    logger.info(
        "llm.chat_json model=%s elapsed=%.2fs attempt=%s max_tokens=%s enable_thinking=%s thinking_budget=%s "
        "prompt_tokens=%s completion_tokens=%s reasoning_tokens=%s cached_tokens=%s",
        payload.get("model"),
        elapsed_seconds,
        attempt + 1,
        payload.get("max_tokens"),
        payload.get("enable_thinking"),
        payload.get("thinking_budget"),
        usage.get("prompt_tokens"),
        usage.get("completion_tokens"),
        completion_details.get("reasoning_tokens"),
        prompt_details.get("cached_tokens"),
    )


model_client = OpenAICompatibleClient()


def _retry_delay_seconds(attempt: int, status_code: int | None) -> int:
    if status_code == 429:
        return min(12 * (attempt + 1), 45)
    return min(2 ** attempt, 6)
