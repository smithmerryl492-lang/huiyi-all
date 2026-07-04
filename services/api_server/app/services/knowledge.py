import math
import re
import uuid
import logging
import time
from dataclasses import dataclass, field
from datetime import UTC, datetime, timedelta, timezone

from app import repositories
from app.core.config import settings
from app.schemas import KnowledgeContextItem, KnowledgeScope, KnowledgeSource, LocalKnowledgeSource, MeetingProcessingResult
from app.services.ai_client import answer_with_ai_service, create_embeddings_with_ai_service, plan_with_ai_service


NO_EVIDENCE_ANSWER = "当前会议记录中没有找到明确依据。"
NO_RANGE_EVIDENCE_ANSWER = "没有找到相关会议内容。"
CHINA_TZ = timezone(timedelta(hours=8))
DEFAULT_LOOKBACK_DAYS = 90
logger = logging.getLogger(__name__)
_cloud_chunk_cache: dict[str, tuple[float, list[dict]]] = {}
_CLOUD_CHUNK_CACHE_SECONDS = max(0.0, settings.knowledge_chunk_cache_seconds)
_FAST_PLAN_BLOCKERS = ("为什么", "原因", "怎么", "如何", "建议", "方案", "分析")
_FAST_PLAN_FOLLOWUP_MARKERS = ("这个", "那个", "它", "第二个", "第三个", "上面", "刚才", "还有呢", "这些")
_FAST_PLAN_SUMMARY_WORDS = ("总结", "概括", "归纳", "复盘", "重点")


@dataclass
class QueryPlan:
    original_question: str
    effective_question: str = ""
    intent: str = "general"
    content_types: set[str] = field(default_factory=set)
    entity_terms: list[str] = field(default_factory=list)
    hard_entity_terms: list[str] = field(default_factory=list)
    participant_terms: list[str] = field(default_factory=list)
    start_at: datetime | None = None
    end_at: datetime | None = None
    time_label: str | None = None
    has_explicit_time: bool = False
    meeting_limit: int | None = None
    is_meeting_lookup: bool = False
    is_participant_query: bool = False
    is_chitchat: bool = False
    is_help: bool = False
    is_followup: bool = False
    context_task_ids: list[str] = field(default_factory=list)
    confidence: float = 0.0
    use_query_embedding: bool = True
    fast_path: bool = False


def index_meeting_result(title: str, result: MeetingProcessingResult) -> None:
    raw_chunks = _raw_chunks_for_meeting_result(result)
    embeddings = _embeddings_for_chunks(raw_chunks)
    chunks = []
    for index, item in enumerate(raw_chunks):
        chunks.append(
            {
                **item,
                "embedding": embeddings[index] if index < len(embeddings) else [],
            }
        )
    repositories.replace_knowledge_chunks(result.task_id, title, chunks)
    clear_knowledge_cache()


def reindex_meeting_result_reusing_embeddings(title: str, result: MeetingProcessingResult) -> None:
    existing_chunks = repositories.list_knowledge_chunks_for_task(result.task_id, include_embeddings=True)
    reusable_chunks: dict[tuple, list[dict]] = {}
    for chunk in existing_chunks:
        reusable_chunks.setdefault(_chunk_reuse_key(chunk), []).append(chunk)

    chunks: list[dict] = []
    missing_embedding_indexes: list[int] = []
    missing_embedding_texts: list[str] = []
    for item in _raw_chunks_for_meeting_result(result):
        reusable = reusable_chunks.get(_chunk_reuse_key(item), [])
        previous = reusable.pop(0) if reusable else None
        embedding = previous.get("embedding") if previous else None
        chunk = {
            **item,
            "id": previous.get("id") if previous else item["id"],
            "embedding": embedding or [],
        }
        if not chunk["embedding"] and str(item.get("text") or "").strip():
            missing_embedding_indexes.append(len(chunks))
            missing_embedding_texts.append(str(item["text"]))
        chunks.append(chunk)

    if missing_embedding_texts:
        embeddings = create_embeddings_with_ai_service(missing_embedding_texts)
        for index, embedding in zip(missing_embedding_indexes, embeddings, strict=False):
            chunks[index]["embedding"] = embedding

    repositories.replace_knowledge_chunks(result.task_id, title, chunks)
    clear_knowledge_cache()


def _raw_chunks_for_meeting_result(result: MeetingProcessingResult) -> list[dict]:
    raw_chunks: list[dict] = []
    metadata_text = "。".join(
        item
        for item in [
            f"参会人：{result.participants}" if result.participants else "",
            f"标签：{'、'.join(result.tags)}" if result.tags else "",
        ]
        if item
    )
    if metadata_text:
        raw_chunks.append(
            {
                "id": str(uuid.uuid4()),
                "chunk_type": "metadata",
                "text": metadata_text,
                "speaker": "会议信息",
                "timestamp": None,
                "start_ms": None,
                "end_ms": None,
            }
        )
    for segment in result.transcripts:
        text = segment.text.strip()
        if not text:
            continue
        raw_chunks.append(
            {
                "id": str(uuid.uuid4()),
                "chunk_type": "transcript",
                "text": text,
                "speaker": segment.speaker,
                "timestamp": segment.timestamp,
                "start_ms": segment.start_ms,
                "end_ms": segment.end_ms,
            }
        )

    if result.summary.strip():
        raw_chunks.append(
            {
                "id": str(uuid.uuid4()),
                "chunk_type": "summary",
                "text": result.summary.strip(),
                "speaker": "AI 纪要",
                "timestamp": None,
                "start_ms": None,
                "end_ms": None,
            }
        )

    for decision in result.decisions:
        if decision.strip():
            raw_chunks.append(
                {
                    "id": str(uuid.uuid4()),
                    "chunk_type": "decision",
                    "text": decision.strip(),
                    "speaker": "AI 决策",
                    "timestamp": None,
                    "start_ms": None,
                    "end_ms": None,
                }
            )

    for topic in result.topics:
        text = f"{topic.title}。{topic.summary}。依据：{topic.source}".strip("。")
        if text:
            raw_chunks.append(
                {
                    "id": str(uuid.uuid4()),
                    "chunk_type": "topic",
                    "text": text,
                    "speaker": "AI 议题",
                    "timestamp": topic.source_timestamp,
                    "start_ms": None,
                    "end_ms": None,
                }
            )

    for todo in result.todos:
        text = "。".join(
            item
            for item in [
                todo.title,
                todo.description,
                f"负责人：{todo.assignee}" if todo.assignee else "",
                f"截止时间：{todo.due_at}" if todo.due_at else "",
                f"状态：{todo.status}",
                f"依据：{todo.source}",
            ]
            if item
        ).strip()
        raw_chunks.append(
            {
                "id": str(uuid.uuid4()),
                "chunk_type": "todo",
                "text": text,
                "speaker": "AI 待办",
                "timestamp": todo.source_timestamp,
                "start_ms": None,
                "end_ms": None,
            }
        )

    for risk in result.risks:
        text = f"{risk.title}。{risk.description}。{risk.recommendation}。依据：{risk.source}".strip("。")
        if text:
            raw_chunks.append(
                {
                    "id": str(uuid.uuid4()),
                    "chunk_type": "risk",
                    "text": text,
                    "speaker": "AI 风险",
                    "timestamp": risk.source_timestamp,
                    "start_ms": None,
                    "end_ms": None,
                }
            )
    return raw_chunks


def _chunk_reuse_key(item: dict) -> tuple:
    return (
        str(item.get("chunk_type") or ""),
        str(item.get("text") or "").strip(),
        str(item.get("timestamp") or ""),
        item.get("start_ms"),
        item.get("end_ms"),
    )


def clear_knowledge_cache(user_id: str | None = None) -> None:
    if not user_id:
        _cloud_chunk_cache.clear()
        return
    prefix = f"{user_id}:"
    for key in [key for key in _cloud_chunk_cache if key.startswith(prefix)]:
        _cloud_chunk_cache.pop(key, None)


def answer_question(
    question: str,
    limit: int = 5,
    task_ids: list[str] | None = None,
    context_task_ids: list[str] | None = None,
    user_id: str | None = None,
    scope: KnowledgeScope = KnowledgeScope.cloud,
    local_sources: list[LocalKnowledgeSource] | None = None,
    user_name: str | None = None,
    context_messages: list[KnowledgeContextItem] | None = None,
) -> dict:
    started_at = time.perf_counter()
    clean_question = question.strip()
    identity = _clean_user_name(user_name)
    context_payload = _knowledge_context_payload(context_messages or [])
    plan = _build_query_plan(clean_question, identity, context_payload)
    logger.debug("knowledge.ask plan %.3fs intent=%s question=%s", time.perf_counter() - started_at, plan.intent, clean_question)
    if plan.is_chitchat:
        return {
            "answer": "你好，可以直接问会议、待办、风险、决策或某段原话。",
            "sources": [],
            "model": None,
        }
    if plan.is_help:
        return {
            "answer": "可以问：本周有哪些待办、最近有哪些风险、最近决定了什么、某句话是谁说的。",
            "sources": [],
            "model": None,
        }
    if not user_id:
        return {"answer": "请先登录后再使用知识库问答。", "sources": [], "model": None}
    step_started = time.perf_counter()
    include_embeddings = plan.use_query_embedding
    cloud_chunks = _list_cloud_chunks_cached(user_id, include_embeddings) if scope in {KnowledgeScope.cloud, KnowledgeScope.all} else []
    local_chunks = _local_sources_to_chunks(local_sources or []) if scope in {KnowledgeScope.local, KnowledgeScope.all} else []
    chunks = cloud_chunks + local_chunks
    logger.debug("knowledge.ask load_chunks %.3fs count=%s", time.perf_counter() - step_started, len(chunks))
    allowed_task_ids = {item.strip() for item in task_ids or [] if item.strip()}
    if allowed_task_ids:
        chunks = [item for item in chunks if item.get("task_id") in allowed_task_ids]
    scoped_task_ids = {
        item.strip()
        for item in (plan.context_task_ids if plan.context_task_ids else (context_task_ids or []))
        if item.strip()
    }
    if plan.is_followup and scoped_task_ids:
        scoped_chunks = [item for item in chunks if item.get("task_id") in scoped_task_ids]
        if scoped_chunks:
            chunks = scoped_chunks
    step_started = time.perf_counter()
    chunks = _filter_chunks_by_time(chunks, plan)
    logger.debug("knowledge.ask time_filter %.3fs count=%s", time.perf_counter() - step_started, len(chunks))
    if not chunks:
        return {"answer": _empty_scope_answer(plan), "sources": [], "model": None}

    step_started = time.perf_counter()
    chunks = _filter_chunks_by_entities(chunks, plan)
    logger.debug("knowledge.ask entity_filter %.3fs count=%s", time.perf_counter() - step_started, len(chunks))
    if not chunks:
        return {"answer": _no_match_answer(plan), "sources": [], "model": None}

    step_started = time.perf_counter()
    ranked = _rank_chunks_by_plan(plan, chunks, include_embeddings)
    logger.debug("knowledge.ask rank %.3fs count=%s", time.perf_counter() - step_started, len(ranked))
    source_limit = max(min(limit, 12), 1)
    if plan.is_meeting_lookup or plan.is_participant_query or plan.meeting_limit:
        source_limit = max(source_limit, min(20, plan.meeting_limit or 10))
    step_started = time.perf_counter()
    sources = _select_sources(ranked, source_limit)
    logger.debug("knowledge.ask select_sources %.3fs count=%s", time.perf_counter() - step_started, len(sources))
    if not sources:
        return {"answer": _no_match_answer(plan), "sources": [], "model": None}

    structured = _structured_answer(plan, ranked, sources)
    if structured is not None:
        logger.debug("knowledge.ask structured total=%.3fs", time.perf_counter() - started_at)
        return structured

    logger.debug("knowledge.ask call_answer_ai total_before_ai=%.3fs", time.perf_counter() - started_at)
    ai_answer = answer_with_ai_service(plan.effective_question or clean_question, [item.model_dump() for item in sources], identity)
    answer = str(ai_answer.get("answer") or "").strip() or NO_EVIDENCE_ANSWER
    if _is_no_evidence_answer(answer):
        return {
            "answer": _no_match_answer(plan),
            "sources": [],
            "model": ai_answer.get("model"),
        }

    source_ids = {str(item) for item in ai_answer.get("source_chunk_ids", [])}
    cited_sources = _cited_sources_for_plan(plan, sources, source_ids)
    answer = _ensure_source_entities_in_answer(answer, cited_sources or sources, clean_question)
    return {
        "answer": answer,
        "sources": cited_sources,
        "model": ai_answer.get("model"),
    }


def _rank_chunks_by_plan(plan: QueryPlan, chunks: list[dict], use_query_embedding: bool = True) -> list[dict]:
    query_embedding = _embedding_for_query(plan.effective_question or plan.original_question) if use_query_embedding else None
    return _rank_chunks(plan, query_embedding, chunks)


def _embeddings_for_chunks(chunks: list[dict]) -> list[list[float]]:
    texts = [str(item.get("text") or "").strip() for item in chunks]
    if not texts:
        return []
    try:
        embeddings = create_embeddings_with_ai_service(texts)
    except Exception:
        logger.exception("知识库向量索引生成失败，降级为空向量")
        return [[] for _ in texts]
    if len(embeddings) != len(texts):
        logger.warning("知识库向量数量不匹配：texts=%s embeddings=%s，降级为空向量", len(texts), len(embeddings))
        return [[] for _ in texts]
    return embeddings


def _embedding_for_query(question: str) -> list[float] | None:
    clean = question.strip()
    if not clean:
        return None
    try:
        embeddings = create_embeddings_with_ai_service([clean])
    except Exception:
        logger.exception("知识库查询向量生成失败，降级为结构化排序")
        return None
    return embeddings[0] if embeddings else None


def _list_cloud_chunks_cached(user_id: str, include_embeddings: bool = True) -> list[dict]:
    now = time.monotonic()
    cache_key = f"{user_id}:{'full' if include_embeddings else 'light'}"
    cached = _cloud_chunk_cache.get(cache_key)
    if cached and now - cached[0] <= _CLOUD_CHUNK_CACHE_SECONDS:
        return [item.copy() for item in cached[1]]
    chunks = repositories.list_knowledge_chunks_for_user(user_id, include_embeddings=include_embeddings)
    _cloud_chunk_cache[cache_key] = (now, [item.copy() for item in chunks])
    return chunks


def _knowledge_context_payload(messages: list[KnowledgeContextItem]) -> list[dict]:
    payload: list[dict] = []
    for message in messages[-6:]:
        clean_text = message.text.strip()
        if not clean_text:
            continue
        payload.append(
            {
                "role": message.role,
                "text": clean_text[:500],
                "sources": [
                    {
                        "index": index,
                        "task_id": source.task_id,
                        "title": source.title,
                        "chunk_type": source.chunk_type,
                        "speaker": source.speaker,
                        "timestamp": source.timestamp,
                        "text": source.text[:160],
                    }
                    for index, source in enumerate(message.sources[:8], start=1)
                ],
            }
        )
    return payload


def _clean_user_name(user_name: str | None) -> str | None:
    clean = (user_name or "").strip()
    if not clean:
        return None
    return clean[:24]


def _mentions_self(question: str) -> bool:
    clean = re.sub(r"\s+", "", question)
    return any(word in clean for word in ("我", "我的", "本人", "自己", "我负责", "分配给我"))


def _cosine_similarity(left: list[float], right: list[float]) -> float:
    if not left or not right or len(left) != len(right):
        return 0.0
    dot = sum(a * b for a, b in zip(left, right))
    left_norm = math.sqrt(sum(a * a for a in left))
    right_norm = math.sqrt(sum(b * b for b in right))
    if left_norm == 0 or right_norm == 0:
        return 0.0
    return dot / (left_norm * right_norm)


def _rank_chunks(plan: QueryPlan, query_embedding: list[float] | None, chunks: list[dict]) -> list[dict]:
    intent_types = plan.content_types or {"metadata", "summary", "topic", "decision", "todo", "risk", "transcript"}
    scored: list[dict] = []
    for item in chunks:
        if query_embedding and item.get("embedding"):
            vector_score = _cosine_similarity(query_embedding, item["embedding"])
        else:
            vector_score = min(0.72, float(item.get("score") or 1.0) * 0.72)
        type_score = 0.24 if item.get("chunk_type") in intent_types else -0.06
        entity_score = _entity_score(plan, item)
        recency_score = _recency_score(item)
        score = vector_score + type_score + entity_score + recency_score
        if score > 0:
            scored.append({**item, "score": score})
    return sorted(scored, key=lambda item: item["score"], reverse=True)


def _structured_answer(plan: QueryPlan, ranked: list[dict], sources: list[KnowledgeSource]) -> dict | None:
    if plan.is_meeting_lookup or plan.meeting_limit:
        meetings = _meeting_records(ranked)
        if not meetings:
            return {"answer": _no_match_answer(plan), "sources": [], "model": None}
        limit = plan.meeting_limit or min(5, len(meetings))
        selected = meetings[:limit]
        label = plan.time_label or "最近"
        if plan.meeting_limit == 1:
            meeting = selected[0]
            answer = f"{label}的一次会议是：{meeting['name']}（{meeting['date']}）。"
            return {"answer": answer, "sources": _sources_for_meetings(selected, ranked, preferred_types=("metadata", "summary")), "model": "structured"}
        if plan.meeting_limit:
            answer = _meeting_limited_answer_intro(plan, selected) + "\n" + "\n".join(
                f"{index}. {meeting['name']}（{meeting['date']}）" for index, meeting in enumerate(selected, 1)
            )
            return {"answer": answer, "sources": _sources_for_meetings(selected, ranked, preferred_types=("metadata", "summary")), "model": "structured"}
        answer = f"{label}有{len(selected)}场会议：\n" + "\n".join(
            f"{index}. {meeting['name']}（{meeting['date']}）" for index, meeting in enumerate(selected, 1)
        )
        return {"answer": answer, "sources": _sources_for_meetings(selected, ranked, preferred_types=("metadata", "summary")), "model": "structured"}

    if plan.is_participant_query:
        meetings = _participant_meetings(plan, ranked)
        if not meetings:
            return {"answer": _no_match_answer(plan), "sources": [], "model": None}
        names = "、".join(plan.participant_terms) if plan.participant_terms else "相关人员"
        answer = f"{names}参加了{len(meetings)}场会议：\n" + "\n".join(
            f"{index}. {meeting['name']}（{meeting['date']}）" for index, meeting in enumerate(meetings, 1)
        )
        return {
            "answer": answer,
            "sources": _sources_for_participant_meetings(plan, meetings, ranked),
            "model": "structured",
        }

    if plan.intent == "todo_lookup":
        todos = _todo_records(plan, ranked)
        if not todos:
            return {"answer": _no_match_answer(plan), "sources": [], "model": None}
        subject = "、".join(plan.participant_terms) if plan.participant_terms else "相关人员"
        answer = f"{subject}有{len(todos)}项待办：\n" + "\n".join(
            f"{index}. {todo['title']}，负责人：{todo['assignee']}，截止时间：{todo['due_at']}。"
            for index, todo in enumerate(todos, 1)
        )
        return {"answer": answer, "sources": _sources_for_chunk_ids([todo["chunk_id"] for todo in todos], sources), "model": "structured"}

    if plan.intent == "risk_lookup":
        risks = _risk_records(ranked)
        if not risks:
            return {"answer": _no_match_answer(plan), "sources": [], "model": None}
        label = plan.time_label or "最近"
        lines = []
        for index, risk in enumerate(risks, 1):
            detail = f"：{risk['detail']}" if risk["detail"] else ""
            lines.append(f"{index}. {risk['title']}{detail}")
        answer = f"{label}有{len(risks)}项风险：\n" + "\n".join(lines)
        return {"answer": answer, "sources": _sources_for_chunk_ids([risk["chunk_id"] for risk in risks], sources), "model": "structured"}

    if plan.intent == "summary" and plan.fast_path:
        summaries = _summary_records(ranked, plan.meeting_limit or 3)
        if not summaries:
            return {"answer": _no_match_answer(plan), "sources": [], "model": None}
        label = plan.time_label or "最近"
        answer_lines = [f"{label}会议重点："]
        for index, record in enumerate(summaries, 1):
            answer_lines.append(f"{index}. {record['name']}（{record['date']}）")
            if record["summary"]:
                answer_lines.append(f"   - 概要：{record['summary'][0]}")
            if record["topics"]:
                answer_lines.append(f"   - 议题：{'；'.join(record['topics'][:2])}")
            if record["decisions"]:
                answer_lines.append(f"   - 决策：{'；'.join(record['decisions'][:2])}")
            if record["risks"]:
                answer_lines.append(f"   - 风险：{'；'.join(record['risks'][:2])}")
            if record["todos"]:
                answer_lines.append(f"   - 待办：{'；'.join(record['todos'][:2])}")
        return {"answer": "\n".join(answer_lines), "sources": _sources_for_summary_records(summaries, ranked), "model": "structured"}

    return None


def _meeting_limited_answer_intro(plan: QueryPlan, selected: list[dict]) -> str:
    count = len(selected)
    requested = plan.meeting_limit or count
    if plan.has_explicit_time and plan.time_label:
        if count < requested:
            return f"{plan.time_label}内目前找到{count}场会议："
        return f"{plan.time_label}内的{count}场会议是："
    if count < requested:
        return f"目前找到的最近{count}场会议是："
    return f"最近{count}场会议是："


def _build_query_plan(question: str, user_name: str | None = None, context: list[dict] | None = None) -> QueryPlan:
    fast_plan = _build_fast_query_plan(question, user_name, context or [])
    if fast_plan is not None:
        return _normalize_query_plan(fast_plan, question, user_name)
    plan = QueryPlan(original_question=question.strip())
    ai_plan = plan_with_ai_service(question, user_name, datetime.now(CHINA_TZ).date().isoformat(), context)
    rewritten = str(ai_plan.get("rewritten_question") or "").strip()
    plan.effective_question = rewritten or plan.original_question
    plan.intent = _normalize_intent(ai_plan.get("intent"))
    plan.is_chitchat = plan.intent == "chitchat"
    plan.is_help = plan.intent == "help"
    plan.content_types = _normalize_content_types(ai_plan.get("content_types"), plan.intent)
    plan.start_at, plan.end_at, plan.time_label, plan.has_explicit_time = _time_range_from_ai_plan(ai_plan)
    plan.meeting_limit = _positive_int(ai_plan.get("meeting_limit"))
    clean_question = re.sub(r"\s+", "", question)
    local_meeting_limit = _fast_meeting_limit(clean_question)
    if local_meeting_limit is not None:
        plan.meeting_limit = local_meeting_limit
    plan.entity_terms = _unique_terms([str(item) for item in ai_plan.get("entities", []) if str(item).strip()])
    plan.participant_terms = _unique_terms([str(item) for item in ai_plan.get("participants", []) if str(item).strip()])
    plan.hard_entity_terms = _unique_terms(plan.entity_terms + plan.participant_terms)
    plan.is_meeting_lookup = plan.intent == "meeting_lookup"
    plan.is_participant_query = plan.intent == "participant_meetings"
    plan.is_followup = bool(ai_plan.get("is_followup", False))
    plan.context_task_ids = list(dict.fromkeys([str(item).strip() for item in ai_plan.get("context_task_ids", []) if str(item).strip()]))
    plan.confidence = float(ai_plan.get("confidence") or 0.0)
    plan.use_query_embedding = _should_use_query_embedding(plan)
    return _normalize_query_plan(plan, question, user_name)


def _normalize_query_plan(plan: QueryPlan, question: str, user_name: str | None = None) -> QueryPlan:
    clean_question = re.sub(r"\s+", "", question)
    local_range_name, local_recent_days = _fast_time_range(clean_question)
    if local_range_name != "default":
        plan.start_at, plan.end_at, plan.time_label, plan.has_explicit_time = _time_range_from_name(local_range_name, local_recent_days)
    local_meeting_limit = _fast_meeting_limit(clean_question)
    if local_meeting_limit is not None:
        plan.meeting_limit = local_meeting_limit

    identity = _clean_user_name(user_name)
    if identity and _mentions_self(clean_question) and plan.intent in {"todo_lookup", "participant_meetings"}:
        plan.participant_terms = _unique_terms([identity] + plan.participant_terms)

    plan.entity_terms = _unique_terms(plan.entity_terms)
    plan.participant_terms = _unique_terms(plan.participant_terms)
    plan.hard_entity_terms = _unique_terms(plan.entity_terms + plan.participant_terms)
    plan.is_meeting_lookup = plan.intent == "meeting_lookup"
    plan.is_participant_query = plan.intent == "participant_meetings"
    plan.is_chitchat = plan.intent == "chitchat"
    plan.is_help = plan.intent == "help"
    return plan


def _build_fast_query_plan(question: str, user_name: str | None = None, context: list[dict] | None = None) -> QueryPlan | None:
    original = question.strip()
    clean = re.sub(r"\s+", "", original)
    if not clean:
        return None
    if _looks_like_followup(clean) and context:
        return None
    if clean in {"你好", "您好", "hello", "hi"}:
        return _fast_plan(original, "chitchat", set(), confidence=0.99)
    if any(word in clean for word in ("怎么用", "如何使用", "帮助", "能问什么")):
        return _fast_plan(original, "help", set(), confidence=0.99)

    range_name, recent_days = _fast_time_range(clean)
    meeting_limit = _fast_meeting_limit(clean)
    entity_terms = _fast_entity_terms(clean)
    participant_terms = _fast_participant_terms(clean)
    identity = _clean_user_name(user_name)
    self_terms = [identity] if identity and _mentions_self(clean) else []
    if any(word in clean for word in _FAST_PLAN_BLOCKERS):
        if _looks_like_followup(clean) and context:
            return None
        return _fast_plan(
            original,
            "general",
            {"summary", "topic", "decision", "todo", "risk", "transcript"},
            range_name,
            recent_days,
            meeting_limit,
            entity_terms=entity_terms,
            participant_terms=participant_terms,
            use_query_embedding=True,
        )

    if _is_self_participant_meeting_lookup(clean):
        return _fast_plan(
            original,
            "participant_meetings",
            {"metadata", "transcript", "todo", "topic", "summary"},
            range_name,
            recent_days,
            meeting_limit,
            participant_terms=self_terms,
        )
    if _is_self_todo_lookup(clean):
        return _fast_plan(
            original,
            "todo_lookup",
            {"todo"},
            range_name,
            recent_days,
            meeting_limit,
            participant_terms=self_terms,
        )

    if entity_terms:
        if "风险" in clean and _looks_like_lookup(clean):
            return _fast_plan(
                original,
                "risk_lookup",
                {"risk"},
                range_name,
                recent_days,
                meeting_limit,
                entity_terms=entity_terms,
            )
        if ("待办" in clean or "待处理" in clean or "负责" in clean) and _looks_like_lookup(clean):
            return _fast_plan(
                original,
                "todo_lookup",
                {"todo"},
                range_name,
                recent_days,
                meeting_limit,
                entity_terms=entity_terms,
                participant_terms=participant_terms,
            )
        if any(word in clean for word in ("关心", "关注", "问题", "提到", "说了什么", "内容")):
            return _fast_plan(
                original,
                "general",
                {"summary", "topic", "decision", "todo", "risk", "transcript"},
                range_name,
                recent_days,
                meeting_limit,
                entity_terms=entity_terms,
                use_query_embedding=True,
            )
    if participant_terms:
        if "参加" in clean and ("会议" in clean or "开会" in clean):
            return _fast_plan(
                original,
                "participant_meetings",
                {"metadata", "transcript", "todo", "topic", "summary"},
                range_name,
                recent_days,
                meeting_limit,
                participant_terms=participant_terms,
            )
        if "负责" in clean or "待办" in clean or "任务" in clean:
            return _fast_plan(
                original,
                "todo_lookup",
                {"todo"},
                range_name,
                recent_days,
                meeting_limit,
                participant_terms=participant_terms,
            )
    if any(word in clean for word in _FAST_PLAN_SUMMARY_WORDS) and _is_generic_fast_query(clean, _FAST_PLAN_SUMMARY_WORDS + ("会议",)):
        return _fast_plan(
            original,
            "summary",
            {"summary", "topic", "decision", "todo", "risk"},
            range_name,
            recent_days,
            meeting_limit,
            use_query_embedding=False,
        )
    if "风险" in clean and _looks_like_lookup(clean) and _is_generic_fast_query(clean, ("风险",)):
        return _fast_plan(original, "risk_lookup", {"risk"}, range_name, recent_days, meeting_limit)
    if ("待办" in clean or "待处理" in clean) and not _mentions_self(clean) and _looks_like_lookup(clean) and _is_generic_fast_query(clean, ("待办", "待处理")):
        return _fast_plan(original, "todo_lookup", {"todo"}, range_name, recent_days, meeting_limit)
    if ("会议" in clean or "开会" in clean) and "参加" not in clean and _looks_like_meeting_lookup(clean) and _is_generic_meeting_query(clean):
        return _fast_plan(
            original,
            "meeting_lookup",
            {"metadata", "summary", "topic", "decision", "todo", "risk", "transcript"},
            range_name,
            recent_days,
            meeting_limit,
        )
    if any(word in clean for word in ("决策", "决定")) and _looks_like_lookup(clean) and _is_generic_fast_query(clean, ("决策", "决定")):
        return _fast_plan(original, "decision_lookup", {"decision"}, range_name, recent_days, meeting_limit, use_query_embedding=True)
    return None


def _fast_plan(
    question: str,
    intent: str,
    content_types: set[str],
    range_name: str = "default",
    recent_days: int | None = None,
    meeting_limit: int | None = None,
    entity_terms: list[str] | None = None,
    participant_terms: list[str] | None = None,
    confidence: float = 0.94,
    use_query_embedding: bool | None = None,
) -> QueryPlan:
    plan = QueryPlan(original_question=question.strip(), effective_question=question.strip())
    plan.intent = intent
    plan.content_types = content_types
    plan.start_at, plan.end_at, plan.time_label, plan.has_explicit_time = _time_range_from_name(range_name, recent_days)
    plan.meeting_limit = meeting_limit
    plan.entity_terms = _unique_terms(entity_terms or [])
    plan.participant_terms = _unique_terms(participant_terms or [])
    plan.hard_entity_terms = _unique_terms(plan.entity_terms + plan.participant_terms)
    plan.is_meeting_lookup = intent == "meeting_lookup"
    plan.is_participant_query = intent == "participant_meetings"
    plan.is_chitchat = intent == "chitchat"
    plan.is_help = intent == "help"
    plan.confidence = confidence
    plan.use_query_embedding = _should_use_query_embedding(plan) if use_query_embedding is None else use_query_embedding
    plan.fast_path = True
    return plan


def _looks_like_followup(clean_question: str) -> bool:
    return any(marker in clean_question for marker in _FAST_PLAN_FOLLOWUP_MARKERS)


def _looks_like_lookup(clean_question: str) -> bool:
    return any(word in clean_question for word in ("哪些", "有什么", "有没有", "有几", "多少", "列出", "查看", "查询", "最近", "今天", "本周", "上周", "昨天"))


def _looks_like_meeting_lookup(clean_question: str) -> bool:
    return _looks_like_lookup(clean_question) or any(word in clean_question for word in ("开过", "开了", "几场", "哪场", "哪次", "最新", "最后", "会议列表"))


def _is_self_todo_lookup(clean_question: str) -> bool:
    return (
        _mentions_self(clean_question)
        and _looks_like_lookup(clean_question)
        and any(word in clean_question for word in ("待办", "待处理", "任务"))
    )


def _is_self_participant_meeting_lookup(clean_question: str) -> bool:
    return (
        _mentions_self(clean_question)
        and _looks_like_meeting_lookup(clean_question)
        and any(word in clean_question for word in ("参加", "参与", "参会"))
        and any(word in clean_question for word in ("会议", "开会"))
    )


def _fast_entity_terms(clean_question: str) -> list[str]:
    patterns = (
        r"项目[A-Za-z0-9一二三四五六七八九十]+",
        r"客户[A-Za-z0-9一二三四五六七八九十]+",
        r"门店[A-Za-z0-9一二三四五六七八九十]+",
        r"供应商[A-Za-z0-9一二三四五六七八九十]+",
    )
    terms: list[str] = []
    for pattern in patterns:
        terms.extend(re.findall(pattern, clean_question))
    return list(dict.fromkeys(terms))


def _fast_participant_terms(clean_question: str) -> list[str]:
    terms = re.findall(r"说话人\d+", clean_question)
    for pattern in (
        r"([\u4e00-\u9fa5]{2,4}?)(?:最近|本周|这周|今天|昨日|昨天)?负责",
        r"([\u4e00-\u9fa5]{2,4}?)(?:最近|本周|这周|今天|昨日|昨天)?参加",
        r"([\u4e00-\u9fa5]{2,4}?)有哪些待办",
        r"([\u4e00-\u9fa5]{2,4}?)有什么待办",
    ):
        terms.extend(re.findall(pattern, clean_question))
    excluded = {"我", "我们", "客户", "项目", "门店", "供应商", "最近", "本周", "这周", "今天", "今日", "昨日", "昨天"}
    cleaned_terms: list[str] = []
    for term in terms:
        clean = term.replace(" ", "").strip()
        clean = re.sub(r"^(?:最近|本周|这周|今天|今日|昨日|昨天)", "", clean)
        clean = re.sub(r"(?:最近|本周|这周|今天|今日|昨日|昨天|上周)$", "", clean)
        if len(clean) < 2 or len(clean) > 12:
            continue
        if clean in excluded or any(word in clean for word in ("我", "我们")):
            continue
        cleaned_terms.append(clean)
    return list(dict.fromkeys(cleaned_terms))


def _is_generic_fast_query(clean_question: str, category_words: tuple[str, ...]) -> bool:
    residue = clean_question
    common_words = (
        "请问",
        "帮我",
        "一下",
        "最近",
        "近期",
        "今天",
        "今日",
        "昨天",
        "昨日",
        "本周",
        "这周",
        "上周",
        "有哪些",
        "有什么",
        "有没有",
        "是什么",
        "有几",
        "哪些",
        "哪场",
        "哪次",
        "什么",
        "有",
        "是",
        "多少",
        "列出",
        "查看",
        "查询",
        "场",
        "次",
        "一",
        "二",
        "两",
        "三",
        "四",
        "五",
        "六",
        "七",
        "八",
        "九",
        "十",
        "吗",
        "呢",
        "的",
        "了",
    )
    for word in common_words + category_words:
        residue = residue.replace(word, "")
    residue = re.sub(r"(?:近)?\d{1,2}(?:天|场|次)", "", residue)
    residue = re.sub(r"[，。？！?！、:：；;,.\\s]", "", residue)
    return residue == ""


def _is_generic_meeting_query(clean_question: str) -> bool:
    residue = clean_question
    phrase_patterns = (
        r"最近\d{1,2}天",
        r"近\d{1,2}天",
        r"最近(?:的)?\d{1,2}(?:场|次|个)?",
        r"最近(?:的)?[一二两三四五六七八九十](?:场|次|个)?",
        r"(?:前)?\d{1,2}(?:场|次|个)",
        r"(?:前)?[一二两三四五六七八九十](?:场|次|个)",
        r"哪(?:几|两|二|三|\d{1,2})?个",
        r"哪(?:几|两|二|三|\d{1,2})?场",
        r"哪(?:几|两|二|三|\d{1,2})?次",
    )
    for pattern in phrase_patterns:
        residue = re.sub(pattern, "", residue)
    removable_words = (
        "请问",
        "帮我",
        "一下",
        "最近",
        "近期",
        "今天",
        "今日",
        "昨天",
        "昨日",
        "本周",
        "这周",
        "上周",
        "会议列表",
        "会议",
        "开会",
        "开过",
        "开了",
        "有哪些",
        "有什么",
        "有没有",
        "有几",
        "有多少",
        "是多少",
        "是什么",
        "叫什么名字",
        "叫什么",
        "名称是",
        "名字是",
        "分别是",
        "分别",
        "是哪些",
        "是哪",
        "名称",
        "名字",
        "哪些",
        "哪",
        "什么",
        "多少",
        "列出",
        "查看",
        "查询",
        "列表",
        "有",
        "是",
        "个",
        "场",
        "次",
        "的",
        "了",
        "吗",
        "呢",
    )
    for word in removable_words:
        residue = residue.replace(word, "")
    residue = re.sub(r"\d+", "", residue)
    residue = re.sub(r"[一二两三四五六七八九十]+", "", residue)
    residue = re.sub(r"[，。？！?！、:：；;,.\\s]", "", residue)
    return residue == ""


def _fast_time_range(clean_question: str) -> tuple[str, int | None]:
    if "今天" in clean_question or "今日" in clean_question:
        return "today", None
    if "昨天" in clean_question or "昨日" in clean_question:
        return "yesterday", None
    if "本周" in clean_question or "这周" in clean_question:
        return "this_week", None
    if "上周" in clean_question:
        return "last_week", None
    match = re.search(r"(?:最近|近)(\d{1,2})天", clean_question)
    if match:
        return "recent_days", int(match.group(1))
    return "default", None


def _fast_meeting_limit(clean_question: str) -> int | None:
    if any(word in clean_question for word in ("最近的一次", "最近一次", "最近一场", "最新一次", "最新一场", "最后一次", "最后一场")):
        return 1
    match = re.search(r"(?:最近|前)?(\d{1,2})(?:场|次|个)(?:会议)?", clean_question)
    if match:
        value = int(match.group(1))
        return value if 0 < value <= 20 else None
    match = re.search(r"(?:最近|前)?([一二两三四五六七八九十])(?:场|次|个)(?:会议)?", clean_question)
    if not match:
        return None
    value = _small_chinese_number(match.group(1))
    return value if 0 < value <= 20 else None


def _small_chinese_number(value: str) -> int:
    mapping = {
        "一": 1,
        "二": 2,
        "两": 2,
        "三": 3,
        "四": 4,
        "五": 5,
        "六": 6,
        "七": 7,
        "八": 8,
        "九": 9,
        "十": 10,
    }
    return mapping.get(value, 0)


def _should_use_query_embedding(plan: QueryPlan) -> bool:
    return not (
        plan.is_chitchat
        or plan.is_help
        or plan.is_meeting_lookup
        or plan.is_participant_query
        or plan.intent in {"todo_lookup", "risk_lookup"}
    )


def _normalize_intent(value: object) -> str:
    allowed = {
        "meeting_lookup",
        "participant_meetings",
        "todo_lookup",
        "risk_lookup",
        "decision_lookup",
        "transcript_lookup",
        "summary",
        "general",
        "chitchat",
        "help",
    }
    clean = str(value or "general").strip()
    return clean if clean in allowed else "general"


def _normalize_content_types(value: object, intent: str) -> set[str]:
    allowed = {"metadata", "summary", "topic", "decision", "todo", "risk", "transcript"}
    structural_defaults = {
        "meeting_lookup": {"metadata", "summary", "topic", "decision", "todo", "risk", "transcript"},
        "participant_meetings": {"metadata", "transcript", "todo", "topic", "summary"},
    }
    if intent in structural_defaults:
        return structural_defaults[intent]
    if isinstance(value, list):
        parsed = {str(item).strip() for item in value if str(item).strip() in allowed}
    else:
        parsed = set()
    if parsed:
        return parsed
    defaults = {
        "meeting_lookup": {"metadata", "summary", "topic", "decision", "todo", "risk", "transcript"},
        "participant_meetings": {"metadata", "transcript", "todo", "topic", "summary"},
        "todo_lookup": {"todo"},
        "risk_lookup": {"risk"},
        "decision_lookup": {"decision"},
        "transcript_lookup": {"transcript"},
        "summary": {"summary", "topic", "decision", "todo", "risk"},
    }
    return defaults.get(intent, {"summary", "topic", "decision", "todo", "risk", "transcript"})


def _time_range_from_ai_plan(ai_plan: dict) -> tuple[datetime | None, datetime | None, str | None, bool]:
    range_name = str(ai_plan.get("time_range") or "default").strip()
    return _time_range_from_name(range_name, _positive_int(ai_plan.get("recent_days")))


def _time_range_from_name(range_name: str, recent_days: int | None = None) -> tuple[datetime | None, datetime | None, str | None, bool]:
    now = datetime.now(CHINA_TZ)
    today = now.replace(hour=0, minute=0, second=0, microsecond=0)
    if range_name == "today":
        return today, today + timedelta(days=1), "今天", True
    if range_name == "yesterday":
        return today - timedelta(days=1), today, "昨天", True
    if range_name == "this_week":
        start = (now - timedelta(days=now.weekday())).replace(hour=0, minute=0, second=0, microsecond=0)
        return start, start + timedelta(days=7), "本周", True
    if range_name == "last_week":
        this_week = (now - timedelta(days=now.weekday())).replace(hour=0, minute=0, second=0, microsecond=0)
        return this_week - timedelta(days=7), this_week, "上周", True
    if range_name == "recent_days":
        days = recent_days or DEFAULT_LOOKBACK_DAYS
        return today - timedelta(days=days - 1), today + timedelta(days=1), f"最近{days}天", True
    return today - timedelta(days=DEFAULT_LOOKBACK_DAYS - 1), today + timedelta(days=1), None, False


def _positive_int(value: object) -> int | None:
    if isinstance(value, int) and value > 0:
        return value
    if isinstance(value, str) and value.strip().isdigit():
        parsed = int(value.strip())
        return parsed if parsed > 0 else None
    return None


def _entity_score(plan: QueryPlan, item: dict) -> float:
    terms = plan.entity_terms + plan.participant_terms
    if not terms:
        return 0.0
    text = f"{item.get('title', '')} {item.get('speaker', '')} {item.get('text', '')}".replace(" ", "")
    hits = sum(1 for term in terms if term.replace(" ", "") in text)
    return min(0.42, hits * 0.14)


def _recency_score(item: dict) -> float:
    created_at = _parse_datetime(item.get("task_created_at") or item.get("created_at"))
    if created_at is None:
        return 0.0
    age_days = max((datetime.now(UTC) - created_at.astimezone(UTC)).days, 0)
    return max(0.0, 0.12 - min(age_days, 30) * 0.004)


def _unique_terms(terms: list[str]) -> list[str]:
    excluded = {"我", "我们", "本周", "上周", "最近", "今天", "今日", "昨天", "哪些", "什么", "会议", "课程", "事项", "待办", "谁"}
    clean_terms: list[str] = []
    for term in terms:
        clean = term.replace(" ", "").strip()
        if not clean or clean in excluded or any(word in clean for word in excluded):
            continue
        clean_terms.append(clean)
    return list(dict.fromkeys(clean_terms))


def _meeting_records(items: list[dict]) -> list[dict]:
    records: list[dict] = []
    seen: set[str] = set()
    for item in sorted(items, key=_meeting_sort_key):
        title = str(item.get("title") or "")
        key = f"{_normalized_meeting_title(title)}|{_meeting_date_label(item)}"
        if key in seen:
            continue
        seen.add(key)
        records.append(
            {
                "task_id": str(item.get("task_id") or ""),
                "name": _display_meeting_name(title),
                "date": _meeting_date_label(item),
            }
        )
    return records


def _participant_meetings(plan: QueryPlan, items: list[dict]) -> list[dict]:
    terms = [term.replace(" ", "") for term in plan.participant_terms if term.strip()]
    matched = [
        item for item in items
        if terms and _contains_any_entity(item, terms)
    ]
    if not matched:
        return []
    return _meeting_records(matched)


def _todo_records(plan: QueryPlan, items: list[dict]) -> list[dict]:
    terms = [term.replace(" ", "") for term in plan.participant_terms if term.strip()]
    if _mentions_self(plan.original_question) and not terms:
        return []
    records: list[dict] = []
    seen: set[str] = set()
    for item in sorted(items, key=_meeting_sort_key):
        if item.get("chunk_type") != "todo":
            continue
        if plan.has_explicit_time and plan.start_at is not None and plan.end_at is not None and not _is_todo_in_range(item, plan.start_at, plan.end_at):
            continue
        text = str(item.get("text") or "")
        if terms and not any(term in text.replace(" ", "") for term in terms):
            continue
        key = str(item.get("id") or text)
        if key in seen:
            continue
        seen.add(key)
        records.append(
            {
                "chunk_id": str(item.get("id") or ""),
                "title": _extract_todo_title(text),
                "assignee": _extract_field(text, "负责人") or "待确认",
                "due_at": _clean_null_text(_extract_field(text, "截止时间")) or "待确认",
            }
        )
    return records


def _risk_records(items: list[dict]) -> list[dict]:
    records: list[dict] = []
    seen: set[str] = set()
    for item in sorted(items, key=_meeting_sort_key):
        if item.get("chunk_type") != "risk":
            continue
        text = str(item.get("text") or "")
        title, detail = _split_risk_text(text)
        key = str(item.get("id") or text)
        if key in seen:
            continue
        seen.add(key)
        records.append(
            {
                "chunk_id": str(item.get("id") or ""),
                "title": title,
                "detail": detail,
            }
        )
    return records


def _summary_records(items: list[dict], limit: int) -> list[dict]:
    records: dict[str, dict] = {}
    for item in sorted(items, key=_meeting_sort_key):
        chunk_type = str(item.get("chunk_type") or "")
        if chunk_type not in {"summary", "topic", "decision", "todo", "risk"}:
            continue
        created_at = _parse_datetime(item.get("task_created_at") or item.get("created_at"))
        date_key = created_at.date().isoformat() if created_at else str(item.get("created_at") or "")
        key = f"{_normalized_meeting_title(str(item.get('title') or ''))}|{date_key}"
        record = records.setdefault(
            key,
            {
                "task_id": str(item.get("task_id") or ""),
                "name": _display_meeting_name(str(item.get("title") or "")),
                "date": _meeting_date_label(item),
                "summary": [],
                "topics": [],
                "decisions": [],
                "todos": [],
                "risks": [],
                "source_ids": [],
            },
        )
        text = str(item.get("text") or "").strip()
        if not text:
            continue
        record["source_ids"].append(str(item.get("id") or ""))
        if chunk_type == "summary":
            record["summary"].append(_brief_knowledge_text(text, 120))
        elif chunk_type == "topic":
            record["topics"].append(_brief_knowledge_text(text.split("依据：", 1)[0], 90))
        elif chunk_type == "decision":
            record["decisions"].append(_brief_knowledge_text(text, 90))
        elif chunk_type == "todo":
            record["todos"].append(_brief_knowledge_text(_extract_todo_title(text), 80))
        elif chunk_type == "risk":
            title, detail = _split_risk_text(text)
            record["risks"].append(_brief_knowledge_text(f"{title}：{detail}" if detail else title, 90))
    return [
        record for record in records.values()
        if record["summary"] or record["topics"] or record["decisions"] or record["todos"] or record["risks"]
    ][: max(1, min(limit, 6))]


def _brief_knowledge_text(text: str, max_chars: int) -> str:
    clean = re.sub(r"\s+", " ", text).strip(" 。；;")
    if len(clean) <= max_chars:
        return clean
    return clean[:max_chars].rstrip(" ，。；;") + "..."


def _meeting_sort_key(item: dict) -> tuple[float, float]:
    created_at = _parse_datetime(item.get("task_created_at") or item.get("created_at"))
    timestamp = created_at.timestamp() if created_at else 0.0
    score = float(item.get("score") or 0.0)
    return (-timestamp, -score)


def _display_meeting_name(title: str) -> str:
    clean = re.sub(r"\.[^.]+$", "", title or "").strip()
    clean = re.sub(r"_停顿测试", "", clean)
    clean = re.sub(r"_约\d+秒(?:_\d+)?$", "", clean)
    clean = re.sub(r"_\d+$", "", clean)
    return clean or "会议记录"


def _extract_todo_title(text: str) -> str:
    return _clean_null_text(text.split("。", 1)[0]) or "待办事项"


def _split_risk_text(text: str) -> tuple[str, str]:
    parts = [_clean_null_text(part) for part in re.split(r"[。；\n]+", text) if _clean_null_text(part)]
    if not parts:
        return "风险事项", ""
    title = parts[0]
    detail_parts = [part for part in parts[1:] if not part.startswith("依据：")]
    detail = "；".join(detail_parts[:2])
    return title, detail


def _extract_field(text: str, field_name: str) -> str | None:
    match = re.search(rf"{field_name}：([^。]+)", text)
    return match.group(1).strip() if match else None


def _clean_null_text(value: str | None) -> str | None:
    clean = (value or "").strip()
    if not clean or clean.lower() == "null":
        return None
    return clean


def _filter_chunks_by_time(chunks: list[dict], plan: QueryPlan) -> list[dict]:
    if plan.intent == "todo_lookup":
        todo_chunks = [item for item in chunks if item.get("chunk_type") == "todo"]
        if plan.start_at is None or plan.end_at is None or not plan.has_explicit_time:
            return todo_chunks
        return [item for item in todo_chunks if _is_todo_in_range(item, plan.start_at, plan.end_at)]
    if plan.start_at is None or plan.end_at is None:
        return chunks
    return [item for item in chunks if _is_meeting_in_range(item, plan.start_at, plan.end_at)]


def _filter_chunks_by_entities(chunks: list[dict], plan: QueryPlan) -> list[dict]:
    if not plan.entity_terms:
        return chunks
    hard_matched = [item for item in chunks if _contains_any_entity(item, plan.hard_entity_terms)]
    if plan.hard_entity_terms:
        return hard_matched
    matched = [item for item in chunks if _entity_score(plan, item) > 0]
    return matched


def _contains_any_entity(item: dict, terms: list[str]) -> bool:
    if not terms:
        return False
    text = f"{item.get('title', '')} {item.get('speaker', '')} {item.get('text', '')}".replace(" ", "")
    return any(re.sub(r"\s+", "", term) in text for term in terms)


def _is_meeting_in_range(item: dict, start: datetime, end: datetime) -> bool:
    created_at = _parse_datetime(item.get("task_created_at") or item.get("created_at"))
    return created_at is not None and start <= created_at < end


def _is_todo_in_range(item: dict, start: datetime, end: datetime) -> bool:
    due_text = _clean_null_text(_extract_field(str(item.get("text") or ""), "截止时间"))
    for due_at in _todo_due_candidates(due_text, item):
        if start <= due_at < end:
            return True
    return False


def _todo_due_candidates(due_text: str | None, item: dict) -> list[datetime]:
    if not due_text:
        return []
    clean = due_text.strip()
    if not clean or clean in {"待确认", "无", "暂无", "未定", "待定"}:
        return []

    base = _parse_datetime(item.get("task_created_at") or item.get("created_at")) or datetime.now(CHINA_TZ)
    dates: list[datetime] = []
    for match in re.finditer(r"(20\d{2})[-/.年](\d{1,2})[-/.月](\d{1,2})日?", clean):
        parsed = _date_start(int(match.group(1)), int(match.group(2)), int(match.group(3)))
        if parsed is not None:
            dates.append(parsed)
    for match in re.finditer(r"(?<!\d)(\d{1,2})[-/.月](\d{1,2})日?", clean):
        parsed = _date_start(base.year, int(match.group(1)), int(match.group(2)))
        if parsed is not None:
            dates.append(parsed)

    if "今天" in clean or "今日" in clean or "今晚" in clean or "今晚上" in clean:
        dates.append(_date_start_from_datetime(base))
    if "明天" in clean or "明日" in clean or "明晚" in clean:
        dates.append(_date_start_from_datetime(base + timedelta(days=1)))
    if "后天" in clean:
        dates.append(_date_start_from_datetime(base + timedelta(days=2)))

    weekday_map = {
        "一": 0,
        "二": 1,
        "三": 2,
        "四": 3,
        "五": 4,
        "六": 5,
        "日": 6,
        "天": 6,
    }
    if "上周" in clean or "下周" in clean or "本周" in clean or "这周" in clean or "周" in clean or "星期" in clean:
        week_offset = 0
        if "上周" in clean:
            week_offset = -7
        elif "下周" in clean:
            week_offset = 7
        week_start = (base - timedelta(days=base.weekday())).replace(hour=0, minute=0, second=0, microsecond=0)
        if "本周" in clean or "这周" in clean or "上周" in clean or "下周" in clean:
            prefix_match = re.search(r"(?:上周|下周|本周|这周)([一二三四五六日天])", clean)
            if prefix_match:
                weekday = weekday_map.get(prefix_match.group(1))
                if weekday is not None:
                    dates.append(week_start + timedelta(days=week_offset + weekday))
            elif re.search(r"(?:上周|下周|本周|这周)(?:内|前|之前|以前|完成|结束)?", clean):
                dates.append(week_start + timedelta(days=week_offset))
        if "周" in clean or "星期" in clean:
            for match in re.finditer(r"(?<![上下])(?:周|星期)([一二三四五六日天])", clean):
                weekday = weekday_map.get(match.group(1))
                if weekday is not None:
                    dates.append(week_start + timedelta(days=weekday))

    deduped: list[datetime] = []
    seen: set[str] = set()
    for item_date in dates:
        key = item_date.date().isoformat()
        if key in seen:
            continue
        seen.add(key)
        deduped.append(item_date)
    return deduped


def _date_start(year: int, month: int, day: int) -> datetime | None:
    try:
        return datetime(year, month, day, tzinfo=CHINA_TZ)
    except ValueError:
        return None


def _date_start_from_datetime(value: datetime) -> datetime:
    return value.astimezone(CHINA_TZ).replace(hour=0, minute=0, second=0, microsecond=0)


def _parse_datetime(value: object) -> datetime | None:
    if not isinstance(value, str) or not value.strip():
        return None
    clean = value.strip().replace("Z", "+00:00")
    try:
        parsed = datetime.fromisoformat(clean)
    except ValueError:
        return None
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=UTC)
    return parsed.astimezone(CHINA_TZ)


def _meeting_date_label(item: dict) -> str:
    created_at = _parse_datetime(item.get("task_created_at") or item.get("created_at"))
    if created_at is None:
        return "时间未知"
    today = datetime.now(CHINA_TZ).date()
    if created_at.date() == today:
        return "今天"
    if created_at.date() == today - timedelta(days=1):
        return "昨天"
    return created_at.strftime("%m-%d")


def _empty_scope_answer(plan: QueryPlan) -> str:
    period = plan.time_label or ""
    if _mentions_self(plan.original_question) and (plan.is_participant_query or plan.is_meeting_lookup):
        return f"{period}没有找到你参加过的会议记录。".strip()
    if _mentions_self(plan.original_question) and "todo" in plan.content_types:
        return f"{period}没有找到分配给你的待办。".strip()
    if plan.is_participant_query or plan.is_meeting_lookup:
        return f"{period}没有找到相关会议记录。".strip()
    if plan.content_types == {"todo"}:
        return f"{period}没有找到相关待办。".strip()
    if plan.content_types == {"risk"}:
        return f"{period}没有找到相关风险。".strip()
    if plan.content_types == {"decision"}:
        return f"{period}没有找到相关决策。".strip()
    return f"{period}没有找到相关会议内容。".strip()


def _no_match_answer(plan: QueryPlan) -> str:
    period = plan.time_label if plan.time_label and plan.has_explicit_time else ""
    if _mentions_self(plan.original_question) and (plan.is_participant_query or plan.is_meeting_lookup):
        return f"{period}没有找到你参加过的会议记录。".strip()
    if _mentions_self(plan.original_question) and "todo" in plan.content_types:
        return f"{period}没有找到分配给你的待办。".strip()
    if plan.is_participant_query or plan.is_meeting_lookup:
        return f"{period}没有找到相关会议记录。".strip()
    if plan.content_types == {"todo"}:
        return f"{period}没有找到相关待办。".strip()
    if plan.content_types == {"risk"}:
        return f"{period}没有找到相关风险。".strip()
    if plan.content_types == {"decision"}:
        return f"{period}没有找到相关决策。".strip()
    if plan.content_types == {"transcript"}:
        return f"{period}没有找到相关原文。".strip()
    return f"{period}没有找到相关会议内容。".strip()


def _select_sources(items: list[dict], limit: int) -> list[KnowledgeSource]:
    if not items:
        return []
    top_score = float(items[0].get("score", 0))
    threshold = max(0.08, top_score * 0.45)
    selected = [item for item in items if float(item.get("score", 0)) >= threshold][:limit]
    return _to_sources(selected)


def _cited_sources_for_plan(plan: QueryPlan, sources: list[KnowledgeSource], source_ids: set[str]) -> list[KnowledgeSource]:
    cited = [item for item in sources if not source_ids or item.chunk_id in source_ids]
    if not cited:
        cited = sources
    if plan.is_meeting_lookup or plan.is_participant_query or plan.meeting_limit:
        return _dedupe_sources_by_task(cited)
    if len(plan.content_types) == 1:
        target_type = next(iter(plan.content_types))
        typed_sources = [item for item in cited if item.chunk_type == target_type]
        if typed_sources:
            return typed_sources[:3]
    return cited[:3]


def _sources_for_meetings(meetings: list[dict], items: list[dict], preferred_types: tuple[str, ...]) -> list[KnowledgeSource]:
    task_ids = [meeting["task_id"] for meeting in meetings if meeting.get("task_id")]
    selected_items: list[dict] = []
    for task_id in task_ids:
        task_items = [item for item in items if item.get("task_id") == task_id]
        source = next((item for item in task_items if item.get("chunk_type") in preferred_types), None)
        if source is None:
            source = task_items[0] if task_items else None
        if source is not None:
            selected_items.append(source)
    return _to_sources(selected_items)


def _sources_for_participant_meetings(plan: QueryPlan, meetings: list[dict], items: list[dict]) -> list[KnowledgeSource]:
    terms = [term.replace(" ", "") for term in plan.participant_terms if term.strip()]
    task_ids = [meeting["task_id"] for meeting in meetings if meeting.get("task_id")]
    selected_items: list[dict] = []
    for task_id in task_ids:
        task_items = [item for item in items if item.get("task_id") == task_id and _contains_any_entity(item, terms)]
        source = next((item for item in task_items if item.get("chunk_type") == "transcript"), None)
        if source is None:
            source = task_items[0] if task_items else None
        if source is not None:
            selected_items.append(source)
    return _to_sources(selected_items)


def _sources_for_summary_records(records: list[dict], items: list[dict]) -> list[KnowledgeSource]:
    source_ids: list[str] = []
    for record in records:
        source_ids.extend([chunk_id for chunk_id in record.get("source_ids", []) if chunk_id])
    source_id_set = set(source_ids)
    selected = [item for item in items if str(item.get("id") or "") in source_id_set]
    preferred_order = {"summary": 0, "topic": 1, "decision": 2, "risk": 3, "todo": 4}
    selected = sorted(selected, key=lambda item: (preferred_order.get(str(item.get("chunk_type") or ""), 9), _meeting_sort_key(item)))
    return _to_sources(selected[:8])


def _sources_for_chunk_ids(chunk_ids: list[str], sources: list[KnowledgeSource]) -> list[KnowledgeSource]:
    selected: list[KnowledgeSource] = []
    for chunk_id in chunk_ids:
        source = next((item for item in sources if item.chunk_id == chunk_id), None)
        if source is not None:
            selected.append(source)
    return selected


def _dedupe_sources_by_task(sources: list[KnowledgeSource]) -> list[KnowledgeSource]:
    deduped: list[KnowledgeSource] = []
    seen: set[str] = set()
    preferred_types = {"metadata": 0, "summary": 1, "topic": 2, "transcript": 3}
    for source in sorted(sources, key=lambda item: (preferred_types.get(item.chunk_type or "", 9), -item.score)):
        key = _normalized_meeting_title(source.title)
        if key in seen:
            continue
        seen.add(key)
        deduped.append(source)
    return deduped


def _normalized_meeting_title(title: str) -> str:
    clean = re.sub(r"\.[^.]+$", "", title or "")
    clean = re.sub(r"_停顿测试_约\d+秒(?:_\d+)?$", "", clean)
    clean = re.sub(r"_约\d+秒(?:_\d+)?$", "", clean)
    clean = re.sub(r"_\d+$", "", clean)
    return re.sub(r"\s+", "", clean)


def _to_sources(items: list[dict]) -> list[KnowledgeSource]:
    return [
        KnowledgeSource(
            chunk_id=item["id"],
            task_id=item["task_id"],
            title=item["title"],
            text=item["text"],
            chunk_type=item.get("chunk_type"),
            meeting_date=item.get("meeting_date") or _meeting_date_label(item),
            speaker=item.get("speaker"),
            timestamp=item.get("timestamp"),
            start_ms=item.get("start_ms"),
            end_ms=item.get("end_ms"),
            score=round(float(item["score"]), 6),
            scope=item.get("scope") or "cloud",
        )
        for item in items
        if item.get("score", 0) > 0
    ]


def _is_no_evidence_answer(answer: str) -> bool:
    clean = answer.strip().strip("。")
    return clean in {"当前会议记录中没有找到明确依据", "未在当前范围找到依据", "未检索到相关内容"}


def _ensure_source_entities_in_answer(answer: str, sources: list[KnowledgeSource], question: str) -> str:
    labels = _source_entity_labels(sources, question)
    missing = [label for label in labels if label not in answer]
    if not missing:
        return answer
    return f"{'、'.join(missing)}：{answer}"


def _source_entity_labels(sources: list[KnowledgeSource], question: str) -> list[str]:
    patterns: list[str] = []
    if "项目" in question:
        patterns.append(r"项目[A-Za-z0-9一二三四五六七八九十]+")
    if "客户" in question:
        patterns.append(r"客户[A-Za-z0-9一二三四五六七八九十]+")
    if not patterns:
        patterns.append(r"(?:项目|客户)[A-Za-z0-9一二三四五六七八九十]+")
    labels: list[str] = []
    for source in sources:
        for pattern in patterns:
            labels.extend(re.findall(pattern, source.title))
    unique = list(dict.fromkeys(labels))
    return unique if len(unique) == 1 else []


def _local_sources_to_chunks(sources: list[LocalKnowledgeSource]) -> list[dict]:
    chunks: list[dict] = []
    for source in sources:
        text = source.text.strip()
        if not text:
            continue
        chunks.append(
            {
                "id": source.chunk_id,
                "task_id": source.task_id,
                "title": source.title,
                "text": text,
                "chunk_type": source.chunk_type or "transcript",
                "meeting_date": source.meeting_date,
                "created_at": source.created_at or datetime.now(UTC).isoformat(),
                "speaker": source.speaker,
                "timestamp": source.timestamp,
                "start_ms": source.start_ms,
                "end_ms": source.end_ms,
                "score": max(0.01, float(source.score)),
                "scope": "local",
            }
        )
    return chunks
