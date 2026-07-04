from typing import Any

from pydantic import BaseModel, Field


class TranscriptSegment(BaseModel):
    speaker: str = "说话人"
    text: str
    timestamp: str = "00:00"
    start_ms: int | None = None
    end_ms: int | None = None
    speaker_id: str | None = None
    confidence: float | None = None


class MinutesRequest(BaseModel):
    task_id: str
    title: str
    meeting_note: str | None = None
    audio_duration_seconds: float | None = None
    transcripts: list[TranscriptSegment]


class TodoDraft(BaseModel):
    title: str
    assignee: str | None = None
    due_at: str | None = None
    priority: str = "medium"
    source: str
    source_timestamp: str | None = None


class TopicDraft(BaseModel):
    title: str
    summary: str = ""
    source: str = ""
    source_timestamp: str | None = None


class RiskDraft(BaseModel):
    title: str
    level: str = ""
    description: str = ""
    recommendation: str = ""
    source: str = ""
    source_timestamp: str | None = None


class MinutesResponse(BaseModel):
    task_id: str
    summary: str
    topics: list[TopicDraft] = Field(default_factory=list)
    decisions: list[str] = Field(default_factory=list)
    todos: list[TodoDraft] = Field(default_factory=list)
    risks: list[RiskDraft] = Field(default_factory=list)
    questions: list[str] = Field(default_factory=list)
    references: list[dict[str, Any]] = Field(default_factory=list)
    model: str


class EmbeddingRequest(BaseModel):
    input: str | list[str]


class EmbeddingResponse(BaseModel):
    model: str
    embeddings: list[list[float]]
    dimensions: int


class KnowledgeAnswerSource(BaseModel):
    chunk_id: str
    task_id: str
    title: str
    text: str
    chunk_type: str | None = None
    meeting_date: str | None = None
    speaker: str | None = None
    timestamp: str | None = None
    start_ms: int | None = None
    end_ms: int | None = None
    score: float | None = None


class KnowledgeAnswerRequest(BaseModel):
    question: str
    sources: list[KnowledgeAnswerSource]
    user_name: str | None = None


class KnowledgeAnswerResponse(BaseModel):
    question: str
    answer: str
    source_chunk_ids: list[str] = Field(default_factory=list)
    model: str


class KnowledgePlanRequest(BaseModel):
    question: str
    user_name: str | None = None
    current_date: str
    context: list[dict] = Field(default_factory=list)


class KnowledgePlanResponse(BaseModel):
    question: str
    intent: str = "general"
    content_types: list[str] = Field(default_factory=list)
    entities: list[str] = Field(default_factory=list)
    participants: list[str] = Field(default_factory=list)
    time_range: str = "default"
    recent_days: int | None = None
    meeting_limit: int | None = None
    is_followup: bool = False
    rewritten_question: str | None = None
    context_task_ids: list[str] = Field(default_factory=list)
    confidence: float = 0.0
    model: str
