package com.huiyi.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import kotlin.math.sqrt

data class LocalKnowledgeChunk(
    val chunkId: String,
    val taskId: String,
    val title: String,
    val text: String,
    val chunkType: String,
    val meetingDate: String,
    val createdAt: String? = null,
    val speaker: String? = null,
    val timestamp: String? = null,
    val startMs: Long? = null,
    val endMs: Long? = null,
    val vector: List<Float>,
    val knowledgeScope: KnowledgeIndexScope
)

interface LocalKnowledgeStore {
    fun replaceMeetingIndex(task: MeetingTask, result: MeetingProcessingResult)
    fun deleteMeetingIndex(taskId: String)
    fun list(scope: KnowledgeQueryScope): List<LocalKnowledgeChunk>
    fun search(question: String, scope: KnowledgeQueryScope, limit: Int = 8): List<LocalKnowledgeChunk>
    fun clearAll()
}

class SharedPrefsLocalKnowledgeStore(context: Context) : LocalKnowledgeStore {
    private val preferences = context.getSharedPreferences("huixiao_local_knowledge", Context.MODE_PRIVATE)

    override fun replaceMeetingIndex(task: MeetingTask, result: MeetingProcessingResult) {
        deleteMeetingIndex(task.id)
        if (task.isPrivate || task.knowledgeScope == KnowledgeIndexScope.Excluded) return
        val chunks = buildChunks(task, result)
        if (chunks.isEmpty()) return
        val current = loadAll() + chunks
        saveAll(current)
    }

    override fun deleteMeetingIndex(taskId: String) {
        saveAll(loadAll().filterNot { it.taskId == taskId })
    }

    override fun list(scope: KnowledgeQueryScope): List<LocalKnowledgeChunk> {
        return loadAll().filter { chunk ->
            when (scope) {
                KnowledgeQueryScope.Local -> true
                KnowledgeQueryScope.Cloud -> false
                KnowledgeQueryScope.All -> true
            }
        }
    }

    override fun search(question: String, scope: KnowledgeQueryScope, limit: Int): List<LocalKnowledgeChunk> {
        val query = embed(question)
        return list(scope)
            .map { chunk ->
                chunk to (cosine(query, chunk.vector) + lexicalScore(question, chunk))
            }
            .filter { it.second > 0.03f }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
    }

    override fun clearAll() {
        preferences.edit().clear().commit()
    }

    private fun buildChunks(task: MeetingTask, result: MeetingProcessingResult): List<LocalKnowledgeChunk> {
        val title = task.title.substringBeforeLast('.', task.title)
        val metadataText = listOfNotNull(
            result.participants?.takeIf { it.isNotBlank() }?.let { "参会人：$it" },
            result.tags.takeIf { it.isNotEmpty() }?.joinToString("、")?.let { "标签：$it" }
        ).joinToString("。")
        val scope = task.knowledgeScope
        val chunks = mutableListOf<LocalKnowledgeChunk>()
        if (metadataText.isNotBlank()) {
            chunks += chunk(task, "${task.id}-metadata", title, metadataText, "metadata", "会议信息", null, null, null, scope)
        }
        result.transcripts.forEachIndexed { index, segment ->
            val text = segment.text.trim()
            if (text.isNotBlank()) {
                chunks += chunk(
                    task = task,
                    id = "${task.id}-transcript-$index",
                    title = title,
                    text = text,
                    type = "transcript",
                    speaker = segment.speaker,
                    timestamp = segment.timestamp,
                    startMs = segment.startMs,
                    endMs = segment.endMs,
                    scope = scope
                )
            }
        }
        if (result.summary.isNotBlank()) {
            chunks += chunk(task, "${task.id}-summary", title, result.summary, "summary", "AI 纪要", null, null, null, scope)
        }
        result.topics.forEach { topic ->
            val text = listOf(topic.title, topic.summary, topic.source).filter { it.isNotBlank() }.joinToString("。")
            if (text.isNotBlank()) chunks += chunk(task, "${task.id}-topic-${topic.id}", title, text, "topic", "AI 议题", topic.sourceTimestamp, null, null, scope)
        }
        result.decisions.forEachIndexed { index, decision ->
            if (decision.isNotBlank()) chunks += chunk(task, "${task.id}-decision-$index", title, decision, "decision", "AI 决策", null, null, null, scope)
        }
        result.todos.forEach { todo ->
            val text = listOf(
                todo.title,
                todo.description,
                todo.assigneeName?.let { "负责人：$it" }.orEmpty(),
                todo.dueAtLabel?.let { "截止时间：$it" }.orEmpty(),
                "状态：${todo.effectiveStatus.label}",
                todo.source
            ).filter { it.isNotBlank() }.joinToString("。")
            if (text.isNotBlank()) chunks += chunk(task, "${task.id}-todo-${todo.id}", title, text, "todo", "AI 待办", todo.sourceTimestamp, null, null, scope)
        }
        result.risks.forEach { risk ->
            val text = listOf(risk.title, risk.description, risk.recommendation, risk.source).filter { it.isNotBlank() }.joinToString("。")
            if (text.isNotBlank()) chunks += chunk(task, "${task.id}-risk-${risk.id}", title, text, "risk", "AI 风险", risk.sourceTimestamp, null, null, scope)
        }
        return chunks
    }

    private fun chunk(
        task: MeetingTask,
        id: String,
        title: String,
        text: String,
        type: String,
        speaker: String?,
        timestamp: String?,
        startMs: Long?,
        endMs: Long?,
        scope: KnowledgeIndexScope
    ): LocalKnowledgeChunk {
        return LocalKnowledgeChunk(
            chunkId = id,
            taskId = task.id,
            title = title,
            text = text,
            chunkType = type,
            meetingDate = task.createdAtLabel,
            createdAt = Instant.ofEpochMilli(task.createdAtMillis).toString(),
            speaker = speaker,
            timestamp = timestamp,
            startMs = startMs,
            endMs = endMs,
            vector = embed("$title $speaker $text"),
            knowledgeScope = scope
        )
    }

    private fun loadAll(): List<LocalKnowledgeChunk> {
        val raw = preferences.getString(KEY_CHUNKS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(
                        LocalKnowledgeChunk(
                            chunkId = item.getString("chunkId"),
                            taskId = item.getString("taskId"),
                            title = item.getString("title"),
                            text = item.getString("text"),
                            chunkType = item.optString("chunkType", "transcript"),
                            meetingDate = item.optString("meetingDate", "本机"),
                            createdAt = item.optString("createdAt").ifBlank { null },
                            speaker = item.optString("speaker").ifBlank { null },
                            timestamp = item.optString("timestamp").ifBlank { null },
                            startMs = item.optNullableLong("startMs"),
                            endMs = item.optNullableLong("endMs"),
                            vector = item.getJSONArray("vector").toFloatList(),
                            knowledgeScope = runCatching { KnowledgeIndexScope.valueOf(item.optString("knowledgeScope")) }.getOrDefault(KnowledgeIndexScope.Local)
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun saveAll(chunks: List<LocalKnowledgeChunk>) {
        val unique = chunks.associateBy { it.chunkId }.values
        val array = JSONArray()
        unique.forEach { chunk ->
            array.put(
                JSONObject()
                    .put("chunkId", chunk.chunkId)
                    .put("taskId", chunk.taskId)
                    .put("title", chunk.title)
                    .put("text", chunk.text)
                    .put("chunkType", chunk.chunkType)
                    .put("meetingDate", chunk.meetingDate)
                    .put("createdAt", chunk.createdAt ?: JSONObject.NULL)
                    .put("speaker", chunk.speaker ?: "")
                    .put("timestamp", chunk.timestamp ?: "")
                    .put("startMs", chunk.startMs ?: JSONObject.NULL)
                    .put("endMs", chunk.endMs ?: JSONObject.NULL)
                    .put("vector", JSONArray(chunk.vector))
                    .put("knowledgeScope", chunk.knowledgeScope.name)
            )
        }
        preferences.edit().putString(KEY_CHUNKS, array.toString()).commit()
    }

    private companion object {
        const val KEY_CHUNKS = "chunks"
        const val VECTOR_SIZE = 96

        fun embed(text: String): List<Float> {
            val vector = FloatArray(VECTOR_SIZE)
            val clean = text.lowercase().replace(Regex("\\s+"), "")
            if (clean.isBlank()) return vector.toList()
            val grams = buildList {
                clean.windowed(2, 1, partialWindows = true).forEach { add(it) }
                clean.split(Regex("[^a-z0-9\\u4e00-\\u9fff]+")).filter { it.length >= 2 }.forEach { add(it) }
            }
            grams.forEach { gram ->
                val index = (gram.hashCode() and Int.MAX_VALUE) % VECTOR_SIZE
                vector[index] += 1f
            }
            val norm = sqrt(vector.sumOf { (it * it).toDouble() }).toFloat()
            return if (norm <= 0f) vector.toList() else vector.map { it / norm }
        }

        fun cosine(left: List<Float>, right: List<Float>): Float {
            if (left.size != right.size || left.isEmpty()) return 0f
            return left.indices.sumOf { (left[it] * right[it]).toDouble() }.toFloat()
        }

        fun lexicalScore(question: String, chunk: LocalKnowledgeChunk): Float {
            val keywords = question.split(Regex("[^A-Za-z0-9\\u4e00-\\u9fff]+")).filter { it.length >= 2 }
            if (keywords.isEmpty()) return 0f
            val text = "${chunk.title} ${chunk.speaker.orEmpty()} ${chunk.text}"
            return keywords.count { it in text }.coerceAtMost(5) * 0.08f
        }
    }
}

private fun JSONArray.toFloatList(): List<Float> {
    return buildList {
        for (index in 0 until length()) add(optDouble(index, 0.0).toFloat())
    }
}

private fun JSONObject.optNullableLong(name: String): Long? {
    return if (has(name) && !isNull(name)) optLong(name) else null
}
