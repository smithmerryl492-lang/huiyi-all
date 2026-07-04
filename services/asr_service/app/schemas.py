from pydantic import BaseModel, Field


class TranscribeRequest(BaseModel):
    task_id: str
    source_file_path: str
    source_file_url: str | None = None
    language_hint: str = "zh-CN"
    hotwords: list[str] = Field(default_factory=list, description="Reserved for ASR A/B experiments; ignored by current mainline.")


class WordTimestamp(BaseModel):
    word: str
    start: float | None = None
    end: float | None = None


class TranscriptSegment(BaseModel):
    speaker: str
    text: str
    timestamp: str
    start_ms: int | None = None
    end_ms: int | None = None
    speaker_id: str | None = None
    confidence: float | None = None
    voiceprint_embedding: list[float] | None = None
    voiceprint_quality: float | None = None


class TranscribeResponse(BaseModel):
    task_id: str
    text: str
    language: str | None = None
    region: str | None = None
    segments: list[TranscriptSegment]
    word_timestamps: list[WordTimestamp] = Field(default_factory=list)
    model: str


class VoiceprintExtractRequest(BaseModel):
    task_id: str
    source_file_path: str
    segments: list[TranscriptSegment]


class VoiceprintExtractResponse(BaseModel):
    task_id: str
    segments: list[TranscriptSegment]


class VoiceprintEnrollAudioRequest(BaseModel):
    request_id: str
    source_file_path: str


class VoiceprintEnrollAudioResponse(BaseModel):
    request_id: str
    embedding: list[float]
    quality: float
    duration_ms: int
