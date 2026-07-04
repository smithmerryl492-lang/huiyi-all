package com.huiyi.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

enum class CloudSyncOperationType {
    Upload,
    UpdateResult,
    Delete,
    UpsertSchedule,
    DeleteSchedule
}

data class CloudSyncOperation(
    val id: String,
    val type: CloudSyncOperationType,
    val localTaskId: String,
    val remoteTaskId: String? = null,
    val userId: String? = null,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val lastError: String? = null
)

interface CloudSyncOperationStore {
    fun loadOperations(): List<CloudSyncOperation>
    fun saveOperations(operations: List<CloudSyncOperation>)
    fun loadOperations(userId: String?): List<CloudSyncOperation> {
        val cleanUserId = userId?.takeIf { it.isNotBlank() } ?: return emptyList()
        return loadOperations().filter { it.userId == cleanUserId }
    }

    fun saveOperations(userId: String?, operations: List<CloudSyncOperation>) {
        val cleanUserId = userId?.takeIf { it.isNotBlank() }
        if (cleanUserId == null) {
            saveOperations(operations)
            return
        }
        val retained = loadOperations().filterNot { it.userId == cleanUserId }
        saveOperations(retained + operations.map { it.copy(userId = cleanUserId) })
    }
}

class SharedPrefsCloudSyncOperationStore(context: Context) : CloudSyncOperationStore {
    private val preferences = context.getSharedPreferences("huixiao_cloud_sync_ops", Context.MODE_PRIVATE)

    override fun loadOperations(): List<CloudSyncOperation> {
        val raw = preferences.getString(KEY_OPERATIONS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(
                        CloudSyncOperation(
                            id = item.getString("id"),
                            type = item.optString("type").toCloudSyncOperationType(),
                            localTaskId = item.getString("localTaskId"),
                            remoteTaskId = item.optString("remoteTaskId").ifBlank { null },
                            userId = item.optString("userId").ifBlank { null },
                            createdAtMillis = item.optLong("createdAtMillis", System.currentTimeMillis()),
                            lastError = item.optString("lastError").ifBlank { null }?.userFacingDisplayText("同步失败，请稍后重试")
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    override fun saveOperations(operations: List<CloudSyncOperation>) {
        val array = JSONArray()
        operations.forEach { operation ->
            array.put(
                JSONObject()
                    .put("id", operation.id)
                    .put("type", operation.type.name)
                    .put("localTaskId", operation.localTaskId)
                    .put("remoteTaskId", operation.remoteTaskId ?: "")
                    .put("userId", operation.userId ?: "")
                    .put("createdAtMillis", operation.createdAtMillis)
                    .put("lastError", operation.lastError?.userFacingDisplayText("同步失败，请稍后重试") ?: "")
            )
        }
        preferences.edit().putString(KEY_OPERATIONS, array.toString()).commit()
    }

    private companion object {
        const val KEY_OPERATIONS = "operations"
    }
}

private fun String.toCloudSyncOperationType(): CloudSyncOperationType {
    return runCatching { CloudSyncOperationType.valueOf(this) }.getOrDefault(CloudSyncOperationType.Upload)
}
