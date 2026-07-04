import json
import logging
import time
import urllib.error
import urllib.request
from typing import Any, Callable

from app.core.config import settings
from app.schemas import TranscriptSegment


logger = logging.getLogger(__name__)


class AliyunFileTranscriptionError(RuntimeError):
    pass


def transcribe_url_with_aliyun_file(
    *,
    file_url: str,
    task_id: str,
    language_hint: str = "zh-CN",
    is_cancelled: Callable[[str], None] | None = None,
) -> tuple[list[TranscriptSegment], str]:
    if not settings.aliyun_dashscope_api_key:
        raise AliyunFileTranscriptionError("阿里云语音识别配置缺失")
    clean_url = str(file_url or "").strip()
    if not clean_url:
        raise AliyunFileTranscriptionError("文件转写 URL 为空")

    started = time.perf_counter()
    aliyun_task_id = _submit_file_transcription(clean_url, language_hint)
    result = _wait_file_transcription_result(aliyun_task_id, task_id, is_cancelled)
    transcription_url = _result_url(result)
    if not transcription_url:
        raise AliyunFileTranscriptionError("阿里云文件转写未返回结果地址")
    payload = _request_json("GET", transcription_url, auth=False, timeout=120)
    segments, text = _segments_from_result(payload)
    if not segments:
        raise AliyunFileTranscriptionError("阿里云文件转写未返回真实转写结果")
    logger.info(
        "阿里云文件 URL 转写完成 task=%s aliyun_task=%s elapsed=%.2fs segments=%s",
        task_id,
        aliyun_task_id,
        time.perf_counter() - started,
        len(segments),
    )
    return segments, text


def _submit_file_transcription(file_url: str, language_hint: str) -> str:
    input_data: dict[str, Any] = {"file_url": file_url}
    parameters: dict[str, Any] = {
        "channel_id": [0],
        "enable_itn": False,
        "enable_words": False,
    }
    language = _normalize_language(language_hint)
    if language:
        parameters["language"] = language
    payload = {
        "model": settings.aliyun_file_model,
        "input": input_data,
        "parameters": parameters,
    }
    response = _request_json(
        "POST",
        settings.aliyun_file_transcription_url,
        payload=payload,
        headers={"X-DashScope-Async": "enable"},
        timeout=30,
    )
    task_id = (
        str(response.get("task_id") or "").strip()
        or str(response.get("output", {}).get("task_id") or "").strip()
        or str(response.get("request_id") or "").strip()
    )
    if not task_id:
        raise AliyunFileTranscriptionError("阿里云文件转写提交失败")
    return task_id


def _wait_file_transcription_result(
    aliyun_task_id: str,
    local_task_id: str,
    is_cancelled: Callable[[str], None] | None,
) -> dict[str, Any]:
    deadline = time.monotonic() + settings.aliyun_file_poll_timeout_seconds
    poll_interval = max(1.0, settings.aliyun_file_poll_interval_seconds)
    last_status = ""
    while time.monotonic() < deadline:
        if is_cancelled is not None:
            is_cancelled(local_task_id)
        response = _request_json(
            "GET",
            f"{settings.aliyun_file_task_url.rstrip('/')}/{aliyun_task_id}",
            timeout=30,
        )
        output = response.get("output") if isinstance(response.get("output"), dict) else response
        status = str(output.get("task_status") or output.get("status") or "").upper()
        last_status = status or last_status
        if status in {"SUCCEEDED", "SUCCESS", "COMPLETED"}:
            return output
        if status in {"FAILED", "CANCELED", "CANCELLED"}:
            message = str(output.get("message") or output.get("code") or "阿里云文件转写失败")
            raise AliyunFileTranscriptionError(message)
        time.sleep(poll_interval)
    raise AliyunFileTranscriptionError(f"阿里云文件转写等待超时：{last_status or 'unknown'}")


def _result_url(result: dict[str, Any]) -> str:
    nested_result = result.get("result")
    if isinstance(nested_result, dict):
        clean = str(nested_result.get("transcription_url") or nested_result.get("url") or "").strip()
        if clean:
            return clean
    candidates = [
        result.get("transcription_url"),
        result.get("result_url"),
        result.get("url"),
    ]
    for item in candidates:
        clean = str(item or "").strip()
        if clean:
            return clean
    results = result.get("results")
    if isinstance(results, list):
        for item in results:
            if isinstance(item, dict):
                clean = str(item.get("transcription_url") or item.get("url") or "").strip()
                if clean:
                    return clean
    return ""


def _request_json(
    method: str,
    url: str,
    *,
    payload: dict[str, Any] | None = None,
    headers: dict[str, str] | None = None,
    auth: bool = True,
    timeout: int = 60,
) -> dict[str, Any]:
    data = None if payload is None else json.dumps(payload, ensure_ascii=False).encode("utf-8")
    request_headers = {"Content-Type": "application/json"}
    if auth:
        request_headers["Authorization"] = f"Bearer {settings.aliyun_dashscope_api_key}"
        if settings.aliyun_workspace_id:
            request_headers["X-DashScope-WorkSpace"] = settings.aliyun_workspace_id
    if headers:
        request_headers.update(headers)
    request = urllib.request.Request(url, data=data, headers=request_headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            body = response.read().decode("utf-8")
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8", errors="ignore")
        raise AliyunFileTranscriptionError(f"阿里云文件转写 HTTP {exc.code}: {detail[:300]}") from exc
    except Exception as exc:
        raise AliyunFileTranscriptionError(f"阿里云文件转写请求失败：{exc}") from exc
    try:
        return json.loads(body)
    except json.JSONDecodeError as exc:
        raise AliyunFileTranscriptionError("阿里云文件转写返回非 JSON 内容") from exc


def _segments_from_result(payload: dict[str, Any]) -> tuple[list[TranscriptSegment], str]:
    raw_segments = _find_segments(payload)
    segments: list[TranscriptSegment] = []
    for index, item in enumerate(raw_segments):
        if not isinstance(item, dict):
            continue
        text = str(item.get("text") or item.get("sentence") or item.get("transcript") or "").strip()
        if not text:
            continue
        start_ms = _to_ms(item.get("begin_time", item.get("start_time", item.get("start"))))
        end_ms = _to_ms(item.get("end_time", item.get("end")))
        if start_ms is None:
            start_ms = segments[-1].end_ms if segments else 0
        if end_ms is None or end_ms <= start_ms:
            end_ms = start_ms + max(800, len(text) * 180)
        segments.append(
            TranscriptSegment(
                speaker=str(item.get("speaker") or item.get("speaker_id") or "说话人 1"),
                text=text,
                timestamp=_format_timestamp(start_ms),
                start_ms=start_ms,
                end_ms=end_ms,
                confidence=None,
            )
        )
    text = "".join(segment.text for segment in segments).strip() or _find_text(payload)
    return segments, text


def _find_segments(payload: Any) -> list[Any]:
    if isinstance(payload, dict):
        sentences = payload.get("sentences")
        if isinstance(sentences, list):
            return sentences
        for key in ("transcripts", "segments"):
            value = payload.get(key)
            if isinstance(value, list):
                nested: list[Any] = []
                for item in value:
                    if isinstance(item, dict) and isinstance(item.get("sentences"), list):
                        nested.extend(item["sentences"])
                return nested or value
        for value in payload.values():
            found = _find_segments(value)
            if found:
                return found
    if isinstance(payload, list):
        for item in payload:
            found = _find_segments(item)
            if found:
                return found
    return []


def _find_text(payload: Any) -> str:
    if isinstance(payload, dict):
        for key in ("text", "transcript"):
            value = str(payload.get(key) or "").strip()
            if value:
                return value
        return "".join(_find_text(value) for value in payload.values()).strip()
    if isinstance(payload, list):
        return "".join(_find_text(item) for item in payload).strip()
    return ""


def _to_ms(value: Any) -> int | None:
    if value is None:
        return None
    try:
        number = float(value)
    except (TypeError, ValueError):
        return None
    if number < 0:
        return None
    return int(number)


def _format_timestamp(ms: int) -> str:
    total_seconds = max(0, int(ms / 1000))
    return f"{total_seconds // 60:02d}:{total_seconds % 60:02d}"


def _normalize_language(language_hint: str | None) -> str | None:
    clean = str(language_hint or "").strip().lower()
    if clean in {"en", "en-us", "english"}:
        return "en"
    if clean in {"zh", "zh-cn", "cn", "chinese"}:
        return "zh"
    return None
