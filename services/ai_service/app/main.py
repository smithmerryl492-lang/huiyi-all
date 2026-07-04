import json
import logging
import re
import threading
import time

from fastapi import FastAPI, HTTPException

from app.clients.openai_compatible import model_client
from app.core.config import settings
from app.schemas import (
    EmbeddingRequest,
    EmbeddingResponse,
    KnowledgeAnswerRequest,
    KnowledgeAnswerResponse,
    KnowledgePlanRequest,
    KnowledgePlanResponse,
    MinutesRequest,
    MinutesResponse,
    RiskDraft,
    TodoDraft,
    TopicDraft,
)


logger = logging.getLogger(__name__)
logger.setLevel(logging.INFO)
if not logger.handlers:
    handler = logging.StreamHandler()
    handler.setFormatter(logging.Formatter("%(levelname)s:%(name)s:%(message)s"))
    logger.addHandler(handler)
logger.propagate = False
_minutes_gate = threading.BoundedSemaphore(settings.minutes_concurrency)
_knowledge_answer_gate = threading.BoundedSemaphore(settings.knowledge_answer_concurrency)
_embedding_gate = threading.BoundedSemaphore(settings.embedding_concurrency)


app = FastAPI(
    title="会晓 AI AI Service",
    version="0.1.0",
    description="LLM、embedding 与知识库问答服务。",
)


@app.get("/health")
def health() -> dict:
    return {
        "status": "ok",
        "service": "ai_service",
        "base_url": settings.base_url,
        "llm_model": settings.llm_model,
        "embedding_model": settings.embedding_model,
        "embedding_dimensions": settings.embedding_dimensions,
        "model_connected": settings.model_connected,
        "minutes_concurrency": settings.minutes_concurrency,
        "knowledge_answer_concurrency": settings.knowledge_answer_concurrency,
        "embedding_concurrency": settings.embedding_concurrency,
    }


@app.post("/api/v1/minutes", response_model=MinutesResponse)
def generate_minutes(request: MinutesRequest) -> MinutesResponse:
    _minutes_gate.acquire()
    try:
        started = time.perf_counter()
        duration_seconds = _estimate_minutes_duration_seconds(request)
        request_timeout_seconds = _minutes_request_timeout_seconds(duration_seconds)
        thinking_budget = settings.minutes_thinking_budget if settings.minutes_enable_thinking else None
        transcript_text = "\n".join(
            f"[{item.timestamp}] {item.speaker}: {item.text}"
            for item in request.transcripts
            if item.text.strip()
        )
        note_text = (request.meeting_note or "").strip()
        note_section = f"会议备注：{note_text}\n" if note_text else ""
        result = model_client.chat_json(
            [
                {
                    "role": "system",
                    "content": (
                        "你是会议纪要生成服务。只能基于用户提供的转写内容生成结果，不得编造未在原文中出现的事实。"
                        "无论原文是中文、英文还是中英混合，最终纪要内容都必须使用中文输出；"
                        "英文专有名词、人名、产品名可保留原文。输出必须是合法 JSON，"
                        "只输出一个 JSON 对象，不要输出 Markdown、解释文字或代码块。"
                    ),
                },
                {
                    "role": "user",
                    "content": (
                        "基于转写生成会议纪要 JSON，不得编造。JSON 只能包含字段：summary、topics、decisions、todos、risks。"
                        "summary 概括整场会议核心内容，尽量简洁，但不能遗漏会议主线。"
                        "topics 按会议实际讨论内容提取主要议题；不要设置固定很小的数量上限；多个片段属于同一议题时合并为一项；"
                        "每项包含 title、summary、source、source_timestamp。"
                        "decisions 只提取原文明确形成的决定、结论、共识；没有则返回空数组；每项为简短字符串。"
                        "todos 只提取原文明确出现的行动项；没有则返回空数组；每项包含 title、assignee、due_at、priority、source、source_timestamp；"
                        "没有明确负责人或截止时间时对应字段用空字符串，不要编造。"
                        "priority 只能是 high/medium/low；原文出现紧急、重要、必须马上、今天/今晚/明早前等强时限时用 high，"
                        "明确可延后或低优先级时用 low，其余用 medium。"
                        "risks 只提取原文明确提到的风险、阻塞、不确定性；没有则返回空数组；"
                        "每项包含 title、level、description、recommendation、source、source_timestamp。"
                        "所有条目都要保留 source 和 source_timestamp，用于说明依据来自哪段转写。"
                        "合并重复内容，避免同一事项反复出现。对长会议要完整保留明确的议题、决策、待办和风险，"
                        "不要因为数量较多而丢弃重要事项。每个条目表达要短，不要写空话、套话和泛泛建议。\n\n"
                        f"会议标题：{request.title}\n"
                        f"{note_section}"
                        f"转写内容：\n{transcript_text}"
                    ),
                },
            ],
            omit_max_tokens=True,
            enable_thinking=settings.minutes_enable_thinking,
            thinking_budget=thinking_budget,
            request_timeout_seconds=request_timeout_seconds,
            retry_count=settings.minutes_retry_count,
        )
        logger.info(
            "会议纪要模型调用完成 task=%s segments=%s transcript_chars=%s duration=%.1fs timeout=%ss thinking=%s retry_count=%s elapsed=%.2fs output_keys=%s",
            request.task_id,
            len(request.transcripts),
            len(transcript_text),
            duration_seconds,
            request_timeout_seconds,
            settings.minutes_enable_thinking,
            settings.minutes_retry_count,
            time.perf_counter() - started,
            sorted(result.keys()),
        )
        return MinutesResponse(
            task_id=request.task_id,
            summary=str(result.get("summary", "")).strip(),
            topics=_normalize_topics(result.get("topics", [])),
            decisions=_normalize_string_list(result.get("decisions", [])),
            todos=_normalize_todos(result.get("todos", [])),
            risks=_normalize_risks(result.get("risks", [])),
            questions=[],
            references=[],
            model=settings.llm_model,
        )
    finally:
        _minutes_gate.release()


def _estimate_minutes_duration_seconds(request: MinutesRequest) -> float:
    if request.audio_duration_seconds and request.audio_duration_seconds > 0:
        return request.audio_duration_seconds
    duration_ms = max((item.end_ms or 0) for item in request.transcripts) if request.transcripts else 0
    if duration_ms > 0:
        return duration_ms / 1000
    timestamp_seconds = [
        parsed
        for item in request.transcripts
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


def _minutes_request_timeout_seconds(duration_seconds: float) -> int:
    calculated = settings.minutes_timeout_buffer_seconds + (
        max(0.0, duration_seconds) * settings.minutes_timeout_per_audio_second
    )
    bounded = max(settings.minutes_timeout_min_seconds, min(settings.minutes_timeout_max_seconds, calculated))
    return int(round(bounded))


@app.post("/api/v1/embeddings", response_model=EmbeddingResponse)
def create_embeddings(request: EmbeddingRequest) -> EmbeddingResponse:
    _embedding_gate.acquire()
    try:
        texts = request.input if isinstance(request.input, list) else [request.input]
        embeddings = model_client.embeddings(texts, request_timeout_seconds=settings.embedding_timeout_seconds)
        dimensions = len(embeddings[0]) if embeddings else 0
        return EmbeddingResponse(model=settings.embedding_model, embeddings=embeddings, dimensions=dimensions)
    finally:
        _embedding_gate.release()


@app.post("/api/v1/knowledge/plan", response_model=KnowledgePlanResponse)
def plan_knowledge_query(request: KnowledgePlanRequest) -> KnowledgePlanResponse:
    user_identity = request.user_name.strip() if request.user_name else ""
    context_text = json.dumps(request.context[-6:], ensure_ascii=False)
    result = model_client.chat_json(
        [
            {
                "role": "system",
                "content": (
                    "你是会议知识库查询理解器。你的任务是把用户自然语言问题解析成 JSON 查询计划，"
                    "不是回答问题。必须理解口语、倒装、省略、时间范围、人员指代和复杂句。"
                    "不要用关键词匹配思维；按语义判断用户到底想查会议、参会人、待办、风险、决策、原文还是总结。"
                    "输出只能是 JSON。"
                ),
            },
            {
                "role": "user",
                "content": (
                    "请返回 JSON，字段："
                    "intent 字符串，只能是 meeting_lookup、participant_meetings、todo_lookup、risk_lookup、decision_lookup、transcript_lookup、summary、general、chitchat、help；"
                    "content_types 字符串数组，元素只能是 metadata、summary、topic、decision、todo、risk、transcript；"
                    "entities 字符串数组，填项目、客户、会议标题、门店、供应商等业务对象；"
                    "participants 字符串数组，填用户要查的参会人/发言人/负责人，例如 说话人 1、小红、张三；"
                    "time_range 字符串，只能是 today、yesterday、this_week、last_week、recent_days、default；"
                    "recent_days 整数或 null；meeting_limit 整数或 null；is_followup 布尔；"
                    "rewritten_question 字符串或 null；context_task_ids 字符串数组；confidence 0 到 1。\n\n"
                    "上下文规则：\n"
                    "1. 用户说“这个、那个、它、第二个、上面这场、还有呢、这些”等追问时，必须结合上下文改写成可独立查询的问题。\n"
                    "2. 如果追问包含“第几个/第 N 个”，必须按最近一条 assistant sources 中的 index 选择对应来源；不要按答案文字自行猜。\n"
                    "3. 如果追问指向上一轮某个来源或编号，把对应 task_id 放入 context_task_ids；只指向一个来源时只返回一个 task_id。\n"
                    "4. 如果用户提出新的明确范围，例如“所有会议、最近7天、小红有哪些待办”，不要套旧上下文。\n"
                    "5. rewritten_question 要面向查询，不要写解释。\n\n"
                    "判定示例：\n"
                    "“所有会议中，有哪些说话人1参加了” => intent=participant_meetings，participants=[\"说话人 1\"]，time_range=default。\n"
                    "“最近7天的会议，说话人1参加了” => intent=participant_meetings，participants=[\"说话人 1\"]，time_range=recent_days，recent_days=7。\n"
                    "“最近7天会议，小绿参加了哪些” => intent=participant_meetings，participants=[\"小绿\"]，time_range=recent_days，recent_days=7。\n"
                    "“小红有哪些待办” => intent=todo_lookup，participants=[\"小红\"]，content_types=[\"todo\"]。\n"
                    "“这个会议负责人是谁” => 如果上下文指向会议，intent=todo_lookup，content_types=[\"todo\"]，is_followup=true。\n"
                    "“最近有哪些会议” => intent=meeting_lookup。\n"
                    "“最近风险是什么” => intent=risk_lookup，content_types=[\"risk\"]。\n\n"
                    f"当前日期：{request.current_date}\n"
                    f"当前用户姓名：{user_identity or '未提供'}\n"
                    f"最近上下文：{context_text}\n"
                    f"用户问题：{request.question}"
                ),
            },
        ],
        max_tokens=settings.knowledge_plan_max_tokens,
        enable_thinking=settings.knowledge_plan_enable_thinking,
        request_timeout_seconds=settings.knowledge_plan_timeout_seconds,
    )
    return KnowledgePlanResponse(
        question=request.question,
        intent=str(result.get("intent", "general")).strip() or "general",
        content_types=[str(item) for item in result.get("content_types", []) if str(item).strip()],
        entities=[str(item).strip() for item in result.get("entities", []) if str(item).strip()],
        participants=[str(item).strip() for item in result.get("participants", []) if str(item).strip()],
        time_range=str(result.get("time_range", "default")).strip() or "default",
        recent_days=result.get("recent_days") if isinstance(result.get("recent_days"), int) else None,
        meeting_limit=result.get("meeting_limit") if isinstance(result.get("meeting_limit"), int) else None,
        is_followup=bool(result.get("is_followup", False)),
        rewritten_question=str(result.get("rewritten_question")).strip() if result.get("rewritten_question") else None,
        context_task_ids=[str(item).strip() for item in result.get("context_task_ids", []) if str(item).strip()],
        confidence=float(result.get("confidence", 0.0) or 0.0),
        model=settings.llm_model,
    )


@app.post("/api/v1/knowledge/answer", response_model=KnowledgeAnswerResponse)
def answer_knowledge(request: KnowledgeAnswerRequest) -> KnowledgeAnswerResponse:
    _knowledge_answer_gate.acquire()
    try:
        started = time.perf_counter()
        user_identity = request.user_name.strip() if request.user_name else ""
        source_text = "\n".join(
            (
                f"来源ID：{item.chunk_id}\n"
                f"会议：{item.title}\n"
                f"日期：{item.meeting_date or '无'}\n"
                f"类型：{item.chunk_type or '无'}\n"
                f"时间：{item.timestamp or '无'}\n"
                f"发言人：{item.speaker or '无'}\n"
                f"内容：{item.text}"
            )
            for item in request.sources
        )
        messages = [
            {
                "role": "system",
                "content": (
                    "你是会议知识库问答服务。只能依据给定来源回答。"
                    "如果提供了当前用户姓名，用户问题中的“我、我的、本人、自己”均指该姓名。"
                    "如果来源不足，必须回答“当前会议记录中没有找到明确依据”，这只是内部无依据标记。"
                    "回答要像真实会议助手：直接给结论，不解释检索过程，不说“依据、检索、范围、筛选、重试”等后台词。"
                    "遇到会议数量、参会人、待办、风险、决策、复盘、最近几场会议重点类问题，必须综合多个来源归纳，不能只机械摘录单条片段。"
                    "如果用户问“最近一场/两场/三场会议”，应按来源中的会议标题和时间范围组织答案。"
                    "当来源会议标题包含项目、客户、门店、供应商等实体名称时，答案中必须保留对应实体名称。"
                    "待办类答案必须保留来源中的负责人或发言人姓名，不得把“张三周三前完成”改写成“周三前完成”。"
                    "输出 JSON。"
                ),
            },
            {
                "role": "user",
                "content": (
                    "请根据来源回答问题，JSON 字段必须包含 answer 字符串、source_chunk_ids 字符串数组。"
                    "source_chunk_ids 只能使用给定来源ID。\n\n"
                    "答案要求：\n"
                    "1. 能回答时不要说“可能、也许、看起来”。\n"
                    "2. 多个来源指向同一结论时要合并，不要重复堆砌。\n"
                    "3. 列表类问题使用简短分点；统计类问题先给数量再列名称。\n"
                    "4. 待办事项要包含负责人、事项、时间要求；来源没有负责人时才写待确认。\n"
                    "5. 风险、决策、客户关注点等答案要保留来源会议标题里的项目名或客户名。\n"
                    "6. 参会人问题要从 speaker 字段和来源内容中提取，并说明对应会议。\n"
                    "7. “重点说了什么/总结最近会议”类问题要按会议分组概括每场会议的主要议题、决策、风险和待办。\n"
                    "8. 问到“我”的待办、发言或责任时，只回答当前用户姓名对应的内容；来源没有该姓名时返回无依据固定句。\n"
                    "9. 没有足够来源时只返回无依据固定句。\n"
                    "10. 面向用户的 answer 不要出现“依据、检索、当前范围、项目/客户筛选、重试”这类系统说明。\n\n"
                    f"当前用户姓名：{user_identity or '未提供'}\n"
                    f"问题：{request.question}\n\n"
                    f"来源：\n{source_text}"
                ),
            },
        ]
        enable_thinking, thinking_budget = _knowledge_answer_thinking_options(request.question)
        result = _chat_knowledge_answer_json(messages, enable_thinking, thinking_budget)
        logger.info(
            "knowledge.answer completed sources=%s analysis_thinking=%s thinking_budget=%s elapsed=%.2fs",
            len(request.sources),
            enable_thinking,
            thinking_budget,
            time.perf_counter() - started,
        )
        return KnowledgeAnswerResponse(
            question=request.question,
            answer=str(result.get("answer", "")).strip() or "当前会议记录中没有找到明确依据。",
            source_chunk_ids=[str(item) for item in result.get("source_chunk_ids", [])],
            model=settings.llm_model,
        )
    finally:
        _knowledge_answer_gate.release()


def _chat_knowledge_answer_json(messages: list[dict[str, str]], enable_thinking: bool, thinking_budget: int | None) -> dict:
    try:
        return model_client.chat_json(
            messages,
            max_tokens=settings.knowledge_answer_max_tokens,
            enable_thinking=enable_thinking,
            thinking_budget=thinking_budget,
            request_timeout_seconds=settings.knowledge_answer_timeout_seconds,
        )
    except HTTPException as exc:
        if "JSON" not in str(exc.detail):
            raise
        logger.warning("知识库回答 JSON 解析失败，使用更高思考预算重试：%s", exc.detail)
        return model_client.chat_json(
            messages,
            max_tokens=settings.knowledge_answer_max_tokens,
            enable_thinking=True,
            thinking_budget=settings.knowledge_answer_retry_thinking_budget,
            request_timeout_seconds=settings.knowledge_answer_timeout_seconds,
        )


def _knowledge_answer_thinking_options(question: str) -> tuple[bool, int | None]:
    clean = question.strip()
    analysis_words = ("为什么", "原因", "建议", "方案", "分析", "复盘", "怎么改", "如何改", "帮我看", "怎么看")
    if any(word in clean for word in analysis_words):
        return True, settings.knowledge_answer_analysis_thinking_budget
    return False, None


def _normalize_references(items: object) -> list[dict]:
    if not isinstance(items, list):
        return []
    references: list[dict] = []
    for item in items:
        if isinstance(item, dict):
            references.append(item)
        elif isinstance(item, str) and item.strip():
            references.append({"source": item.strip()})
    return references


def _normalize_todos(items: object) -> list[TodoDraft]:
    if isinstance(items, str) and items.strip():
        return [TodoDraft(title=items.strip(), source="AI 纪要")]
    if not isinstance(items, list):
        return []
    todos: list[TodoDraft] = []
    for item in items:
        if isinstance(item, dict):
            title = str(item.get("title", "")).strip()
            if title:
                todos.append(
                    TodoDraft(
                        title=title,
                        assignee=item.get("assignee"),
                        due_at=item.get("due_at"),
                        priority=_normalize_todo_priority(item.get("priority")),
                        source=str(item.get("source", "")).strip() or "AI 纪要",
                        source_timestamp=item.get("source_timestamp"),
                    )
                )
        elif isinstance(item, str) and item.strip():
            todos.append(TodoDraft(title=item.strip(), source="AI 纪要"))
    return todos


def _normalize_todo_priority(value: object) -> str:
    clean = str(value or "").strip().lower().replace(" ", "").replace("_", "").replace("-", "")
    if clean in {"urgent", "high", "p0", "p1", "top", "critical", "important", "紧急", "急", "重要", "高", "高优先", "高优先级"}:
        return "high"
    if clean in {"low", "p3", "p4", "minor", "optional", "deferred", "低", "低优先", "低优先级", "可延后", "不急"}:
        return "low"
    return "medium"


def _normalize_topics(items: object) -> list[TopicDraft]:
    if isinstance(items, str) and items.strip():
        return [TopicDraft(title=items.strip())]
    if not isinstance(items, list):
        return []
    topics: list[TopicDraft] = []
    for item in items:
        if isinstance(item, dict):
            title = str(item.get("title", "")).strip()
            if title:
                topics.append(
                    TopicDraft(
                        title=title,
                        summary=str(item.get("summary", "")).strip(),
                        source=str(item.get("source", "")).strip(),
                        source_timestamp=item.get("source_timestamp"),
                    )
                )
        elif isinstance(item, str) and item.strip():
            topics.append(TopicDraft(title=item.strip()))
    return topics


def _normalize_risks(items: object) -> list[RiskDraft]:
    if isinstance(items, str) and items.strip():
        return [RiskDraft(title=items.strip())]
    if not isinstance(items, list):
        return []
    risks: list[RiskDraft] = []
    for item in items:
        if isinstance(item, dict):
            title = str(item.get("title", "")).strip()
            description = str(item.get("description", "")).strip()
            if title or description:
                risks.append(
                    RiskDraft(
                        title=title or description,
                        level=str(item.get("level", "")).strip(),
                        description=description,
                        recommendation=str(item.get("recommendation", "")).strip(),
                        source=str(item.get("source", "")).strip(),
                        source_timestamp=item.get("source_timestamp"),
                    )
                )
        elif isinstance(item, str) and item.strip():
            risks.append(RiskDraft(title=item.strip()))
    return risks


def _normalize_string_list(items: object) -> list[str]:
    if isinstance(items, str):
        return [items.strip()] if items.strip() else []
    if not isinstance(items, list):
        return []
    return [str(item).strip() for item in items if str(item).strip()]
