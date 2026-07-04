package com.huiyi.app.data

interface AudioTranscriptionService {
    fun transcribe(request: AsrRequest): AsrResult
}

interface MinutesGenerationService {
    fun generate(request: MinutesGenerationRequest): MinutesResult
}

interface MeetingProcessingResultStore {
    fun loadResult(taskId: String): MeetingProcessingResult?
    fun saveResult(result: MeetingProcessingResult)
    fun deleteResult(taskId: String)
    fun clearAll()
}

fun MeetingProcessingResultStore.loadResultForTask(task: MeetingTask): MeetingProcessingResult? {
    loadResult(task.id)?.let { return it.normalizedForTask(task) }
    val remoteTaskId = task.remoteTaskId ?: return null
    val remoteResult = loadResult(remoteTaskId) ?: return null
    val normalized = remoteResult.normalizedForTask(task)
    saveResult(normalized)
    return normalized
}

data class AsrRequest(
    val taskId: String,
    val localFilePath: String,
    val source: MeetingTaskSource,
    val languageHint: String = "zh-CN"
)

data class AsrResult(
    val taskId: String,
    val segments: List<TranscriptSegment>
)

data class MinutesGenerationRequest(
    val taskId: String,
    val title: String,
    val transcripts: List<TranscriptSegment>
)

data class MinutesResult(
    val summary: String,
    val topics: List<TopicItem>,
    val decisions: List<String>,
    val todos: List<TodoItem>,
    val risks: List<RiskItem>
)

data class MeetingProcessingResult(
    val taskId: String,
    val remoteTaskId: String? = null,
    val sourceFilePath: String,
    val source: MeetingTaskSource,
    val participants: String? = null,
    val tags: List<String> = emptyList(),
    val summary: String,
    val topics: List<TopicItem>,
    val decisions: List<String>,
    val todos: List<TodoItem>,
    val risks: List<RiskItem>,
    val transcripts: List<TranscriptSegment>,
    val generatedAtLabel: String
)

fun MeetingProcessingResult.normalizedForTask(task: MeetingTask): MeetingProcessingResult {
    val normalized = copy(
        taskId = task.id,
        remoteTaskId = remoteTaskId ?: task.remoteTaskId,
        sourceFilePath = task.localFilePath.ifBlank { sourceFilePath },
        transcripts = transcripts.map { it.normalizedSpeakerIdentity() }
    )
    return normalized.copy(
        todos = normalized.todos.map { todo ->
            if (todo.sourceSegmentIndex != null) {
                todo
            } else {
                todo.copy(
                    sourceSegmentIndex = normalized.sourceIndexFor(
                        timestamp = todo.sourceTimestamp,
                        text = listOf(todo.title, todo.description, todo.source).joinToString(" ")
                    )
                )
            }
        }
    )
}

private fun MeetingProcessingResult.sourceIndexFor(timestamp: String?, text: String): Int? {
    if (transcripts.isEmpty()) return null
    val cleanTimestamp = timestamp?.trim().orEmpty()
    val byTime = if (cleanTimestamp.isBlank()) {
        -1
    } else {
        transcripts.indexOfFirst { segment ->
            segment.timestamp == cleanTimestamp || segment.timeRangeLabel.contains(cleanTimestamp)
        }
    }
    if (byTime >= 0) return byTime.coerceIn(0, transcripts.lastIndex)
    val cleanText = text.trim()
    if (cleanText.isBlank()) return null
    val byText = transcripts.indexOfFirst { segment ->
        val segmentText = segment.text.take(32)
        val itemText = cleanText.take(32)
        segmentText.isNotBlank() && (segmentText in cleanText || itemText in segment.text)
    }
    return byText.takeIf { it >= 0 }?.coerceIn(0, transcripts.lastIndex)
}

abstract class MeetingProcessingPipeline(
    protected val resultStore: MeetingProcessingResultStore
) {
    abstract fun process(task: MeetingTask): MeetingProcessingResult
}
