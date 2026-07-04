from enum import StrEnum
from typing import Any

from pydantic import BaseModel, Field


class MeetingTaskSource(StrEnum):
    recording = "recording"
    import_file = "import"


class MeetingTaskStatus(StrEnum):
    waiting_process = "waiting_process"
    processing = "processing"
    finished = "finished"
    failed = "failed"
    canceled = "canceled"


class TaskSyncScope(StrEnum):
    cloud = "cloud"
    local_processing = "local_processing"


class KnowledgeScope(StrEnum):
    cloud = "cloud"
    local = "local"
    all = "all"
    excluded = "excluded"


class FileRecord(BaseModel):
    id: str
    original_name: str
    stored_path: str
    content_type: str
    size_bytes: int
    created_at: str


class MeetingTask(BaseModel):
    id: str
    file_id: str
    client_task_id: str | None = None
    title: str
    source: MeetingTaskSource
    status: MeetingTaskStatus
    error_message: str | None = None
    progress_percent: float = 0.0
    progress_label: str | None = None
    progress_stage: str | None = None
    sync_scope: TaskSyncScope = TaskSyncScope.cloud
    knowledge_scope: KnowledgeScope = KnowledgeScope.cloud
    is_private: bool = False
    device_id: str | None = None
    confirmed: bool = False
    created_at_millis: int | None = None
    created_at: str
    updated_at: str


class TaskUpdateRequest(BaseModel):
    title: str | None = None
    confirmed: bool | None = None
    created_at_millis: int | None = None
    is_private: bool | None = None
    knowledge_scope: KnowledgeScope | None = None


class TranscriptSegment(BaseModel):
    speaker: str
    text: str
    timestamp: str
    start_ms: int | None = None
    end_ms: int | None = None
    speaker_id: str | None = None
    confidence: float | None = None
    voiceprint_embedding: list[float] | None = Field(default=None, exclude=True)
    voiceprint_quality: float | None = Field(default=None, exclude=True)


class TodoItem(BaseModel):
    id: str
    title: str
    source: str
    done: bool = False
    source_timestamp: str | None = None
    meeting_id: str | None = None
    meeting_title: str | None = None
    description: str = ""
    assignee: str | None = None
    assignee_id: str | None = None
    due_at: str | None = None
    due_at_millis: int | None = None
    priority: str = "medium"
    status: str = "pending_confirm"
    completed_at: str | None = None
    completed_at_millis: int | None = None
    source_segment_index: int | None = None
    locked_fields: list[str] = Field(default_factory=list)


class TopicItem(BaseModel):
    id: str
    title: str
    summary: str = ""
    source: str = ""
    source_timestamp: str | None = None


class RiskItem(BaseModel):
    id: str
    title: str
    level: str = ""
    description: str = ""
    recommendation: str = ""
    source: str = ""
    source_timestamp: str | None = None


class MeetingProcessingResult(BaseModel):
    task_id: str
    source_file_path: str
    participants: str | None = None
    tags: list[str] = Field(default_factory=list)
    summary: str
    topics: list[TopicItem] = Field(default_factory=list)
    decisions: list[str]
    todos: list[TodoItem]
    risks: list[RiskItem] = Field(default_factory=list)
    transcripts: list[TranscriptSegment]
    generated_at: str


class UploadResponse(BaseModel):
    file: FileRecord
    task: MeetingTask


class TaskDetail(BaseModel):
    task: MeetingTask
    file: FileRecord
    result: MeetingProcessingResult | None = None


class ListResponse(BaseModel):
    items: list[Any] = Field(default_factory=list)
    total: int


class MessageResponse(BaseModel):
    message: str


class SmsCodeScene(StrEnum):
    login = "login"
    register = "register"
    change_phone = "change_phone"
    change_password = "change_password"


class SmsCodeRequest(BaseModel):
    phone: str
    scene: SmsCodeScene = SmsCodeScene.login


class SmsCodeResponse(BaseModel):
    message: str
    expires_in: int
    resend_after: int


class SmsLoginRequest(BaseModel):
    phone: str
    code: str


class PhoneChangeRequest(BaseModel):
    old_phone: str
    old_code: str = ""
    old_verification_token: str = ""
    new_phone: str
    new_code: str


class PhoneChangeVerifyRequest(BaseModel):
    old_phone: str
    old_code: str


class PhoneChangeVerifyResponse(BaseModel):
    message: str
    verification_token: str
    expires_in: int


class PasswordRegisterRequest(BaseModel):
    phone: str
    code: str
    password: str


class PasswordLoginRequest(BaseModel):
    phone: str
    password: str


class PasswordSetRequest(BaseModel):
    password: str


class PasswordChangeRequest(BaseModel):
    old_password: str
    new_password: str


class PasswordResetRequest(BaseModel):
    phone: str
    code: str
    password: str


class LoginResponse(BaseModel):
    user_id: str
    username: str
    display_name: str
    phone: str | None = None
    access_token: str
    token_type: str = "bearer"
    expires_in: int


class ScheduledMeetingCloud(BaseModel):
    id: str
    time: str
    title: str
    participants: str
    note: str = ""
    duration_label: str
    reminder_label: str = "提前 5 分钟提醒"
    start_at_millis: int | None = None
    end_at_millis: int | None = None
    created_at_millis: int
    status: str = "pending"
    calendar_event_id: int | None = None


class CloudTaskItem(BaseModel):
    task: MeetingTask
    file: FileRecord
    result: MeetingProcessingResult | None = None


class CloudBootstrapResponse(BaseModel):
    user_id: str
    tasks: list[CloudTaskItem] = Field(default_factory=list)
    schedules: list[ScheduledMeetingCloud] = Field(default_factory=list)


class KnowledgeSource(BaseModel):
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
    score: float
    scope: str = "cloud"


class LocalKnowledgeSource(BaseModel):
    chunk_id: str
    task_id: str
    title: str
    text: str
    chunk_type: str | None = None
    meeting_date: str | None = None
    created_at: str | None = None
    speaker: str | None = None
    timestamp: str | None = None
    start_ms: int | None = None
    end_ms: int | None = None
    score: float = 1.0


class KnowledgeContextItem(BaseModel):
    role: str
    text: str
    sources: list[KnowledgeSource] = Field(default_factory=list)


class KnowledgeAskRequest(BaseModel):
    question: str
    user_id: str
    user_name: str | None = None
    limit: int = 5
    task_ids: list[str] = Field(default_factory=list)
    context_task_ids: list[str] = Field(default_factory=list)
    context_messages: list[KnowledgeContextItem] = Field(default_factory=list)
    scope: KnowledgeScope = KnowledgeScope.cloud
    local_sources: list[LocalKnowledgeSource] = Field(default_factory=list)


class KnowledgeAskResponse(BaseModel):
    question: str
    answer: str
    sources: list[KnowledgeSource]
    model: str | None = None


class ResultUpdateRequest(BaseModel):
    participants: str | None = None
    tags: list[str] | None = None
    summary: str | None = None
    topics: list[TopicItem] | None = None
    decisions: list[str] | None = None
    todos: list[TodoItem] | None = None
    risks: list[RiskItem] | None = None
    transcripts: list[TranscriptSegment] | None = None


class RegenerateMinutesRequest(BaseModel):
    transcripts: list[TranscriptSegment]
    meeting_note: str | None = None


class LocalRegenerateMinutesRequest(BaseModel):
    task_id: str
    title: str
    source_file_path: str
    participants: str | None = None
    tags: list[str] = Field(default_factory=list)
    meeting_note: str | None = None
    transcripts: list[TranscriptSegment]


class TaskProcessingContextRequest(BaseModel):
    meeting_note: str | None = None
    schedule_id: str | None = None
    recognition_language: str | None = None
    transcripts: list[TranscriptSegment] | None = None


class SpeakerProfile(BaseModel):
    id: str
    display_name: str
    sample_count: int = 0
    active: bool = True
    created_at: str
    updated_at: str


class SpeakerProfileUpdateRequest(BaseModel):
    display_name: str | None = None
    active: bool | None = None


class SpeakerProfileEnrollRequest(BaseModel):
    task_id: str
    speaker_id: str | None = None
    speaker_name: str | None = None
    display_name: str
    profile_id: str | None = None


class SpeakerProfileDeleteResponse(BaseModel):
    message: str
