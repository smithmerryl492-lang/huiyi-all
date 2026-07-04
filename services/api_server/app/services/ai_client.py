import json
import logging
import re
import time
import urllib.error
import urllib.request

from fastapi import HTTPException

from app.core.config import settings
from app.schemas import RiskItem, TodoItem, TopicItem, TranscriptSegment
from app.services.errors import AI_USER_MESSAGE


logger = logging.getLogger(__name__)


def generate_minutes_with_ai_service(
    task_id: str,
    title: str,
    transcripts: list[TranscriptSegment],
    meeting_note: str | None = None,
) -> dict:
    duration_seconds = _estimate_transcript_duration_seconds(transcripts)
    timeout_seconds = _minutes_ai_service_timeout_seconds(duration_seconds)
    payload = json.dumps(
        {
            "task_id": task_id,
            "title": title,
            "meeting_note": meeting_note,
            "audio_duration_seconds": duration_seconds,
            "transcripts": [item.model_dump() for item in transcripts],
        },
        ensure_ascii=False,
    ).encode("utf-8")
    request = urllib.request.Request(
        f"{settings.ai_service_url.rstrip('/')}/api/v1/minutes",
        data=payload,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        started = time.perf_counter()
        with urllib.request.urlopen(request, timeout=timeout_seconds) as response:
            data = json.loads(response.read().decode("utf-8"))
        logger.info(
            "AI 纪要服务返回 task=%s segments=%s duration=%.1fs timeout=%ss payload_bytes=%s elapsed=%.2fs",
            task_id,
            len(transcripts),
            duration_seconds,
            timeout_seconds,
            len(payload),
            time.perf_counter() - started,
        )
        return data
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8", errors="ignore")
        logger.warning("AI 纪要服务 HTTP 错误：status=%s detail=%s", exc.code, detail)
        raise HTTPException(status_code=502, detail=AI_USER_MESSAGE) from exc
    except Exception as exc:
        logger.warning("AI 纪要服务不可用：%s", exc)
        raise HTTPException(status_code=502, detail=AI_USER_MESSAGE) from exc


def _estimate_transcript_duration_seconds(transcripts: list[TranscriptSegment]) -> float:
    duration_ms = max((item.end_ms or 0) for item in transcripts) if transcripts else 0
    if duration_ms > 0:
        return duration_ms / 1000
    timestamp_seconds = [
        parsed
        for item in transcripts
        for parsed in [_parse_timestamp_seconds(item.timestamp)]
        if parsed is not None
    ]
    return max(timestamp_seconds) if timestamp_seconds else 0.0


def _parse_timestamp_seconds(value: str | None) -> float | None:
    if not value:
        return None
    matches = re.findall(r"\d{1,2}:\d{2}(?::\d{2})?", value)
    if not matches:
        return None
    parts = [int(part) for part in matches[-1].split(":")]
    if len(parts) == 2:
        minutes, seconds = parts
        return minutes * 60 + seconds
    hours, minutes, seconds = parts
    return hours * 3600 + minutes * 60 + seconds


def _minutes_ai_service_timeout_seconds(duration_seconds: float) -> int:
    calculated = settings.ai_minutes_timeout_buffer_seconds + (
        max(0.0, duration_seconds) * settings.ai_minutes_timeout_per_audio_second
    )
    bounded = max(settings.ai_minutes_timeout_min_seconds, min(settings.ai_minutes_timeout_max_seconds, calculated))
    return int(round(bounded))


def todos_from_ai_response(items: list[dict]) -> list[TodoItem]:
    return todos_from_ai_response_for_task("", items)


def todos_from_ai_response_for_task(task_id: str, items: list[dict]) -> list[TodoItem]:
    todos: list[TodoItem] = []
    for index, item in enumerate(items, start=1):
        title = str(item.get("title", "")).strip()
        if not title:
            continue
        prefix = f"{task_id}-" if task_id else ""
        assignee = _clean_required_todo_text(item.get("assignee"))
        due_at = _clean_required_todo_text(item.get("due_at"))
        todos.append(
            TodoItem(
                id=f"{prefix}todo-ai-{index}",
                title=title,
                source=str(item.get("source", "")).strip() or "AI 纪要",
                source_timestamp=item.get("source_timestamp"),
                assignee=assignee,
                due_at=due_at,
                priority=_normalize_todo_priority(item.get("priority")),
                status="todo" if assignee and due_at else "pending_confirm",
            )
        )
    return todos


def _clean_required_todo_text(value: object) -> str | None:
    clean = str(value or "").strip()
    normalized = "".join(clean.lower().split())
    placeholders = {"", "null", "none", "unknown", "n/a", "na", "-", "未指定", "待确认", "待补充", "暂无", "无"}
    placeholder_prefixes = ("未指定", "待确认", "待补充", "暂无", "无明确", "未明确", "未知", "待定")
    if normalized in placeholders or any(normalized.startswith(prefix) for prefix in placeholder_prefixes):
        return None
    return clean


def _normalize_todo_priority(value: object) -> str:
    clean = str(value or "").strip().lower().replace(" ", "").replace("_", "").replace("-", "")
    if clean in {"urgent", "high", "p0", "p1", "top", "critical", "important", "紧急", "急", "重要", "高", "高优先", "高优先级"}:
        return "high"
    if clean in {"low", "p3", "p4", "minor", "optional", "deferred", "低", "低优先", "低优先级", "可延后", "不急"}:
        return "low"
    return "medium"


def topics_from_ai_response(items: object) -> list[TopicItem]:
    if not isinstance(items, list):
        return []
    topics: list[TopicItem] = []
    for index, item in enumerate(items, start=1):
        if isinstance(item, dict):
            title = str(item.get("title", "")).strip()
            if not title:
                continue
            topics.append(
                TopicItem(
                    id=f"topic-ai-{index}",
                    title=title,
                    summary=str(item.get("summary", "")).strip(),
                    source=str(item.get("source", "")).strip(),
                    source_timestamp=item.get("source_timestamp"),
                )
            )
        elif isinstance(item, str) and item.strip():
            topics.append(TopicItem(id=f"topic-ai-{index}", title=item.strip()))
    return topics


def risks_from_ai_response(items: object) -> list[RiskItem]:
    if not isinstance(items, list):
        return []
    risks: list[RiskItem] = []
    for index, item in enumerate(items, start=1):
        if isinstance(item, dict):
            title = str(item.get("title", "")).strip()
            description = str(item.get("description", "")).strip()
            if not title and not description:
                continue
            risks.append(
                RiskItem(
                    id=f"risk-ai-{index}",
                    title=title or description,
                    level=str(item.get("level", "")).strip(),
                    description=description,
                    recommendation=str(item.get("recommendation", "")).strip(),
                    source=str(item.get("source", "")).strip(),
                    source_timestamp=item.get("source_timestamp"),
                )
            )
        elif isinstance(item, str) and item.strip():
            risks.append(RiskItem(id=f"risk-ai-{index}", title=item.strip()))
    return risks


def create_embeddings_with_ai_service(texts: list[str]) -> list[list[float]]:
    embeddings: list[list[float]] = [[] for _ in texts]
    indexed_texts = [(index, str(text).strip()) for index, text in enumerate(texts) if str(text).strip()]
    if not indexed_texts:
        return embeddings
    for batch_start in range(0, len(indexed_texts), 10):
        batch = indexed_texts[batch_start:batch_start + 10]
        batch_embeddings = _create_embedding_batch([text for _, text in batch])
        for (index, _), embedding in zip(batch, batch_embeddings, strict=False):
            embeddings[index] = embedding
    return embeddings


def _create_embedding_batch(texts: list[str]) -> list[list[float]]:
    payload = json.dumps({"input": texts}, ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(
        f"{settings.ai_service_url.rstrip('/')}/api/v1/embeddings",
        data=payload,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=settings.ai_embedding_timeout_seconds) as response:
            data = json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8", errors="ignore")
        logger.warning("AI 向量服务 HTTP 错误：status=%s detail=%s", exc.code, detail)
        raise HTTPException(status_code=502, detail=AI_USER_MESSAGE) from exc
    except Exception as exc:
        logger.warning("AI 向量服务不可用：%s", exc)
        raise HTTPException(status_code=502, detail=AI_USER_MESSAGE) from exc
    return data.get("embeddings", [])


def answer_with_ai_service(question: str, sources: list[dict], user_name: str | None = None) -> dict:
    payload = json.dumps(
        {
            "question": question,
            "sources": sources,
            "user_name": user_name,
        },
        ensure_ascii=False,
    ).encode("utf-8")
    request = urllib.request.Request(
        f"{settings.ai_service_url.rstrip('/')}/api/v1/knowledge/answer",
        data=payload,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=settings.ai_knowledge_answer_timeout_seconds) as response:
            return json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8", errors="ignore")
        logger.warning("AI 问答服务 HTTP 错误：status=%s detail=%s", exc.code, detail)
        raise HTTPException(status_code=502, detail=AI_USER_MESSAGE) from exc
    except Exception as exc:
        logger.warning("AI 问答服务不可用：%s", exc)
        raise HTTPException(status_code=502, detail=AI_USER_MESSAGE) from exc


def plan_with_ai_service(question: str, user_name: str | None, current_date: str, context: list[dict] | None = None) -> dict:
    payload = json.dumps(
        {
            "question": question,
            "user_name": user_name,
            "current_date": current_date,
            "context": context or [],
        },
        ensure_ascii=False,
    ).encode("utf-8")
    request = urllib.request.Request(
        f"{settings.ai_service_url.rstrip('/')}/api/v1/knowledge/plan",
        data=payload,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=settings.ai_knowledge_plan_timeout_seconds) as response:
            return json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8", errors="ignore")
        logger.warning("AI 查询理解服务 HTTP 错误：status=%s detail=%s", exc.code, detail)
        raise HTTPException(status_code=502, detail=AI_USER_MESSAGE) from exc
    except Exception as exc:
        logger.warning("AI 查询理解服务不可用：%s", exc)
        raise HTTPException(status_code=502, detail=AI_USER_MESSAGE) from exc
