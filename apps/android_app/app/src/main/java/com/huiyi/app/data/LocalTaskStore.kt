package com.huiyi.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

interface LocalTaskStore {
    fun loadTasks(): List<MeetingTask>
    fun saveTasks(tasks: List<MeetingTask>)
}

class SharedPrefsLocalTaskStore(context: Context) : LocalTaskStore {
    private val preferences = context.getSharedPreferences("huixiao_local_tasks", Context.MODE_PRIVATE)

    override fun loadTasks(): List<MeetingTask> {
        val raw = preferences.getString(KEY_TASKS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(
                        MeetingTask(
                            id = item.getString("id"),
                            remoteTaskId = item.optString("remoteTaskId").ifBlank { null },
                            title = item.getString("title"),
                            source = MeetingTaskSource.valueOf(item.getString("source")),
                            status = MeetingTaskStatus.valueOf(item.getString("status")),
                            localFilePath = item.getString("localFilePath"),
                            createdAtLabel = item.optString("createdAtLabel", "本地"),
                            createdAtMillis = item.optLong("createdAtMillis", 0L),
                            sizeLabel = item.optString("sizeLabel").ifBlank { null },
                            errorMessage = item.optString("errorMessage").ifBlank { null }?.userFacingDisplayText("处理失败，请稍后重试"),
                            progressPercent = item.optDouble("progressPercent", 0.0).toFloat(),
                            progressLabel = item.optString("progressLabel").ifBlank { null },
                            progressStage = item.optString("progressStage").ifBlank { null },
                            confirmed = item.optBoolean("confirmed", false),
                            syncStatus = item.optString("syncStatus").toCloudSyncStatus(),
                            knowledgeScope = item.optString("knowledgeScope").toKnowledgeIndexScope(),
                            isPrivate = item.optBoolean("isPrivate", false),
                            deviceId = item.optString("deviceId").ifBlank { null },
                            scheduleId = item.optString("scheduleId").ifBlank { null },
                            scheduleNote = item.optString("scheduleNote").ifBlank { null },
                            recognitionLanguage = RecognitionLanguage.fromRemote(item.optString("recognitionLanguage").ifBlank { null })
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    override fun saveTasks(tasks: List<MeetingTask>) {
        val array = JSONArray()
        tasks.forEach { task ->
            array.put(
                JSONObject()
                    .put("id", task.id)
                    .put("remoteTaskId", task.remoteTaskId ?: "")
                    .put("title", task.title)
                    .put("source", task.source.name)
                    .put("status", task.status.name)
                    .put("localFilePath", task.localFilePath)
                    .put("createdAtLabel", task.createdAtLabel)
                    .put("createdAtMillis", task.createdAtMillis)
                    .put("sizeLabel", task.sizeLabel ?: "")
                    .put("errorMessage", task.errorMessage?.userFacingDisplayText("处理失败，请稍后重试") ?: "")
                    .put("progressPercent", task.progressPercent)
                    .put("progressLabel", task.progressLabel ?: "")
                    .put("progressStage", task.progressStage ?: "")
                    .put("confirmed", task.confirmed)
                    .put("syncStatus", task.syncStatus.name)
                    .put("knowledgeScope", task.knowledgeScope.name)
                    .put("isPrivate", task.isPrivate)
                    .put("deviceId", task.deviceId ?: "")
                    .put("scheduleId", task.scheduleId ?: "")
                    .put("scheduleNote", task.scheduleNote ?: "")
                    .put("recognitionLanguage", task.recognitionLanguage.remoteValue)
            )
        }
        preferences.edit().putString(KEY_TASKS, array.toString()).commit()
    }

    private companion object {
        const val KEY_TASKS = "tasks"
    }
}

private fun String.toCloudSyncStatus(): CloudSyncStatus {
    return runCatching { CloudSyncStatus.valueOf(this) }.getOrDefault(CloudSyncStatus.LocalOnly)
}

private fun String.toKnowledgeIndexScope(): KnowledgeIndexScope {
    return runCatching { KnowledgeIndexScope.valueOf(this) }.getOrDefault(KnowledgeIndexScope.Local)
}
