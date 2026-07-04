package com.huiyi.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale

class SharedPrefsMeetingProcessingResultStore(context: Context) : MeetingProcessingResultStore {
    private val preferences = context.getSharedPreferences("huixiao_meeting_results", Context.MODE_PRIVATE)
    private val cache = mutableMapOf<String, MeetingProcessingResult>()

    override fun loadResult(taskId: String): MeetingProcessingResult? {
        synchronized(cache) {
            cache[taskId]?.let { return it }
        }
        val raw = preferences.getString(taskId, null) ?: return null
        val parsed = runCatching {
            val item = JSONObject(raw)
            MeetingProcessingResult(
                taskId = item.getString("taskId"),
                remoteTaskId = item.optString("remoteTaskId").ifBlank { null },
                sourceFilePath = item.optString("sourceFilePath"),
                source = item.optString("source").ifBlank { null }?.let { MeetingTaskSource.valueOf(it) } ?: MeetingTaskSource.Import,
                participants = item.optString("participants").ifBlank { null },
                tags = item.optJSONArray("tags").orEmpty().toStringList(),
                summary = item.getString("summary"),
                topics = item.optJSONArray("topics").orEmpty().toTopicList(),
                decisions = item.getJSONArray("decisions").toStringList(),
                todos = item.getJSONArray("todos").toTodoList(),
                risks = item.optJSONArray("risks").orEmpty().toRiskList(),
                transcripts = item.getJSONArray("transcripts").toTranscriptList(),
                generatedAtLabel = item.optString("generatedAtLabel", "刚刚")
            )
        }.getOrNull()
        if (parsed != null) {
            synchronized(cache) {
                cache[taskId] = parsed
            }
        }
        return parsed
    }

    override fun saveResult(result: MeetingProcessingResult) {
        val item = JSONObject()
            .put("taskId", result.taskId)
            .put("remoteTaskId", result.remoteTaskId ?: "")
            .put("sourceFilePath", result.sourceFilePath)
            .put("source", result.source.name)
            .put("participants", result.participants ?: "")
            .put("tags", JSONArray(result.tags))
            .put("summary", result.summary)
            .put("topics", result.topics.toTopicJsonArray())
            .put("decisions", JSONArray(result.decisions))
            .put("todos", result.todos.toTodoJsonArray())
            .put("risks", result.risks.toRiskJsonArray())
            .put("transcripts", result.transcripts.toTranscriptJsonArray())
            .put("generatedAtLabel", result.generatedAtLabel)
        preferences.edit().putString(result.taskId, item.toString()).commit()
        synchronized(cache) {
            cache[result.taskId] = result
        }
    }

    override fun deleteResult(taskId: String) {
        preferences.edit().remove(taskId).commit()
        synchronized(cache) {
            cache.remove(taskId)
        }
    }

    override fun clearAll() {
        preferences.edit().clear().commit()
        synchronized(cache) {
            cache.clear()
        }
    }

    private fun JSONArray.toStringList(): List<String> {
        return buildList {
            for (index in 0 until length()) {
                add(getString(index))
            }
        }
    }

    private fun JSONArray.toTodoList(): List<TodoItem> {
        return buildList {
            for (index in 0 until length()) {
                val item = getJSONObject(index)
                add(
                    TodoItem(
                        id = item.getString("id"),
                        title = item.getString("title"),
                        source = item.getString("source"),
                        done = item.optBoolean("done", false),
                        sourceTimestamp = item.cleanString("sourceTimestamp"),
                        meetingId = item.cleanString("meetingId"),
                        meetingTitle = item.cleanString("meetingTitle"),
                        description = item.cleanString("description").orEmpty(),
                        assigneeName = item.cleanString("assigneeName"),
                        assigneeId = item.cleanString("assigneeId"),
                        dueAtLabel = item.cleanString("dueAtLabel"),
                        dueAtMillis = item.optNullableLong("dueAtMillis") ?: item.optString("dueAtLabel").parseTodoDueMillis(),
                        priority = item.cleanString("priority").normalizedTodoPriority(),
                        status = item.optString("status").toTodoStatus(item.optBoolean("done", false)),
                        completedAtMillis = item.optNullableLong("completedAtMillis"),
                        completedAtLabel = item.cleanString("completedAtLabel"),
                        sourceSegmentIndex = item.optNullableInt("sourceSegmentIndex"),
                        lockedFields = item.optJSONArray("lockedFields").orEmpty().toStringList().toSet()
                    )
                )
            }
        }
    }

    private fun JSONArray.toTopicList(): List<TopicItem> {
        return buildList {
            for (index in 0 until length()) {
                val item = getJSONObject(index)
                add(
                    TopicItem(
                        id = item.getString("id"),
                        title = item.getString("title"),
                        summary = item.optString("summary"),
                        source = item.optString("source"),
                        sourceTimestamp = item.optString("sourceTimestamp").ifBlank { null }
                    )
                )
            }
        }
    }

    private fun JSONArray.toRiskList(): List<RiskItem> {
        return buildList {
            for (index in 0 until length()) {
                val item = getJSONObject(index)
                add(
                    RiskItem(
                        id = item.getString("id"),
                        title = item.getString("title"),
                        level = item.optString("level"),
                        description = item.optString("description"),
                        recommendation = item.optString("recommendation"),
                        source = item.optString("source"),
                        sourceTimestamp = item.optString("sourceTimestamp").ifBlank { null }
                    )
                )
            }
        }
    }

    private fun JSONArray.toTranscriptList(): List<TranscriptSegment> {
        return buildList {
            for (index in 0 until length()) {
                val item = getJSONObject(index)
                add(
                    TranscriptSegment(
                        speaker = item.optString("speaker", "说话人"),
                        text = item.getString("text"),
                        timestamp = item.getString("timestamp"),
                        startMs = if (item.has("startMs") && !item.isNull("startMs")) item.optLong("startMs") else null,
                        endMs = if (item.has("endMs") && !item.isNull("endMs")) item.optLong("endMs") else null,
                        speakerId = item.cleanString("speakerId")
                    )
                )
            }
        }
    }

    private fun List<TodoItem>.toTodoJsonArray(): JSONArray {
        val array = JSONArray()
        forEach { todo ->
            array.put(
                JSONObject()
                    .put("id", todo.id)
                    .put("title", todo.title)
                    .put("source", todo.source)
                    .put("done", todo.done)
                    .put("sourceTimestamp", todo.sourceTimestamp ?: "")
                    .put("meetingId", todo.meetingId ?: "")
                    .put("meetingTitle", todo.meetingTitle ?: "")
                    .put("description", todo.description)
                    .put("assigneeName", todo.assigneeName ?: "")
                    .put("assigneeId", todo.assigneeId ?: "")
                    .put("dueAtMillis", todo.dueAtMillis ?: JSONObject.NULL)
                    .put("dueAtLabel", todo.dueAtLabel ?: "")
                    .put("priority", todo.priority.normalizedTodoPriority())
                    .put("status", todo.effectiveStatus.name)
                    .put("completedAtMillis", todo.completedAtMillis ?: JSONObject.NULL)
                    .put("completedAtLabel", todo.completedAtLabel ?: "")
                    .put("sourceSegmentIndex", todo.sourceSegmentIndex ?: JSONObject.NULL)
                    .put("lockedFields", JSONArray(todo.lockedFields.toList()))
            )
        }
        return array
    }

    private fun List<TopicItem>.toTopicJsonArray(): JSONArray {
        val array = JSONArray()
        forEach { topic ->
            array.put(
                JSONObject()
                    .put("id", topic.id)
                    .put("title", topic.title)
                    .put("summary", topic.summary)
                    .put("source", topic.source)
                    .put("sourceTimestamp", topic.sourceTimestamp ?: "")
            )
        }
        return array
    }

    private fun List<RiskItem>.toRiskJsonArray(): JSONArray {
        val array = JSONArray()
        forEach { risk ->
            array.put(
                JSONObject()
                    .put("id", risk.id)
                    .put("title", risk.title)
                    .put("level", risk.level)
                    .put("description", risk.description)
                    .put("recommendation", risk.recommendation)
                    .put("source", risk.source)
                    .put("sourceTimestamp", risk.sourceTimestamp ?: "")
            )
        }
        return array
    }

    private fun List<TranscriptSegment>.toTranscriptJsonArray(): JSONArray {
        val array = JSONArray()
        forEach { segment ->
            array.put(
                JSONObject()
                    .put("speakerId", segment.stableSpeakerId)
                    .put("speaker", segment.speaker)
                    .put("text", segment.text)
                    .put("timestamp", segment.timestamp)
                    .put("startMs", segment.startMs ?: JSONObject.NULL)
                    .put("endMs", segment.endMs ?: JSONObject.NULL)
            )
        }
        return array
    }
}

private fun JSONArray?.orEmpty(): JSONArray = this ?: JSONArray()

private fun JSONObject.optNullableLong(name: String): Long? {
    return if (has(name) && !isNull(name)) optLong(name) else null
}

private fun JSONObject.optNullableInt(name: String): Int? {
    return if (has(name) && !isNull(name)) optInt(name) else null
}

private fun String.toTodoStatus(done: Boolean): TodoStatus {
    return runCatching { TodoStatus.valueOf(this) }.getOrDefault(if (done) TodoStatus.Done else TodoStatus.PendingConfirm)
}

private fun JSONObject.cleanString(name: String): String? {
    val clean = optString(name).trim()
    return clean.takeUnless { it.isBlank() || it.equals("null", ignoreCase = true) }
}

private fun String.parseTodoDueMillis(): Long? {
    val clean = trim()
    if (clean.isBlank() || clean == "待确认" || clean.equals("null", ignoreCase = true)) return null
    val patterns = listOf("yyyy-MM-dd HH:mm", "yyyy-MM-dd", "MM-dd HH:mm", "MM-dd")
    for (pattern in patterns) {
        val parsed = runCatching {
            val format = SimpleDateFormat(pattern, Locale.CHINA).apply { isLenient = false }
            val position = java.text.ParsePosition(0)
            val date = format.parse(clean, position)
            date?.takeIf { position.index == clean.length }
        }.getOrNull() ?: continue
        val calendar = java.util.Calendar.getInstance()
        calendar.time = parsed
        if (pattern.startsWith("MM")) {
            calendar.set(java.util.Calendar.YEAR, java.util.Calendar.getInstance().get(java.util.Calendar.YEAR))
        }
        if (!pattern.contains("HH")) {
            calendar.set(java.util.Calendar.HOUR_OF_DAY, 23)
            calendar.set(java.util.Calendar.MINUTE, 59)
        }
        calendar.set(java.util.Calendar.SECOND, 0)
        calendar.set(java.util.Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }
    return null
}
