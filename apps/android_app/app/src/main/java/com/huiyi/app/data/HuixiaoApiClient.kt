package com.huiyi.app.data

import com.huiyi.app.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.Locale
import java.util.UUID

private const val DEFAULT_JSON_READ_TIMEOUT_MS = 600_000
private const val PAYMENT_SYNC_READ_TIMEOUT_MS = 2_000
private const val LONG_MINUTES_READ_TIMEOUT_MS = 7 * 60 * 60 * 1000
private const val UPLOAD_MIN_READ_TIMEOUT_MS = 10 * 60 * 1000
private const val UPLOAD_MAX_READ_TIMEOUT_MS = 60 * 60 * 1000
private const val UPLOAD_READ_TIMEOUT_PER_MB_MS = 30_000L
private const val LIVE_DIRECT_SESSION_CACHE_MAX_AGE_MS = 20 * 60 * 1000L
private const val LIVE_DIRECT_SESSION_MIN_VALID_MS = 2 * 60 * 1000L

data class RemoteKnowledgeSource(
    val chunkId: String,
    val taskId: String,
    val title: String,
    val text: String,
    val chunkType: String?,
    val meetingDate: String?,
    val speaker: String?,
    val timestamp: String?,
    val startMs: Long?,
    val endMs: Long?,
    val score: Double,
    val scope: String = "cloud"
)

data class RemoteKnowledgeAnswer(
    val answer: String,
    val sources: List<RemoteKnowledgeSource>
)

data class KnowledgeChatContextItem(
    val role: String,
    val text: String,
    val sources: List<RemoteKnowledgeSource> = emptyList()
)

data class RemoteTaskSnapshot(
    val id: String,
    val status: MeetingTaskStatus,
    val errorMessage: String?,
    val progressPercent: Float,
    val progressLabel: String?,
    val progressStage: String?
)

data class RemoteTaskDetail(
    val task: RemoteTaskSnapshot,
    val result: MeetingProcessingResult?
)

data class LiveDirectSession(
    val provider: String,
    val apiKey: String,
    val expiresAt: Long,
    val websocketUrl: String,
    val workspaceId: String,
    val model: String,
    val sampleRate: Int,
    val vadThreshold: Double,
    val vadSilenceDurationMs: Int,
    val filterFiller: Boolean,
    val fillerMaxDurationMs: Int,
    val remainingMinutes: Int,
    val graceMinutes: Int,
    val lowRemainingWarningMinutes: Int,
    val fetchedAtMillis: Long = System.currentTimeMillis()
)

class HuixiaoApiException(
    val statusCode: Int,
    val rawMessage: String,
    message: String = rawMessage.userFacingApiDetail(statusCode)
) : IllegalStateException(message)

class HuixiaoApiClient(
    private val baseUrl: String = BuildConfig.HUIXIAO_API_BASE_URL
) {
    @Volatile
    private var accessToken: String? = null

    fun setAccessToken(token: String?) {
        accessToken = token?.takeIf { it.isNotBlank() }
    }

    fun sendLoginSmsCode(phone: String) {
        postJson(
            "/auth/sms/send-code",
            JSONObject()
                .put("phone", phone)
                .put("scene", "login")
        )
    }

    fun sendRegisterSmsCode(phone: String) {
        postJson(
            "/auth/sms/send-code",
            JSONObject()
                .put("phone", phone)
                .put("scene", "register")
        )
    }

    fun sendPasswordResetSmsCode(phone: String) {
        postJson(
            "/auth/sms/send-code",
            JSONObject()
                .put("phone", phone)
                .put("scene", "change_password")
        )
    }

    fun sendPhoneChangeSmsCode(phone: String) {
        postJson(
            "/auth/sms/send-code",
            JSONObject()
                .put("phone", phone)
                .put("scene", "change_phone")
        )
    }

    fun loginWithSmsCode(phone: String, code: String): CloudUser {
        val result = postJson(
            "/auth/sms/login",
            JSONObject()
                .put("phone", phone)
                .put("code", code)
        )
        val token = result.getString("access_token")
        return CloudUser(
            userId = result.getString("user_id"),
            username = result.getString("username"),
            displayName = result.optString("display_name", result.getString("username")),
            phone = result.optString("phone"),
            accessToken = token,
            expiresAtMillis = System.currentTimeMillis() + result.optLong("expires_in", 0L).coerceAtLeast(0L) * 1000L
        ).also { setAccessToken(token) }
    }

    fun registerWithPassword(phone: String, code: String, password: String): CloudUser {
        val result = postJson(
            "/auth/password/register",
            JSONObject()
                .put("phone", phone)
                .put("code", code)
                .put("password", password)
        )
        return result.toCloudUser()
    }

    fun loginWithPassword(phone: String, password: String): CloudUser {
        val result = postJson(
            "/auth/password/login",
            JSONObject()
                .put("phone", phone)
                .put("password", password)
        )
        return result.toCloudUser()
    }

    fun resetPassword(phone: String, code: String, password: String): CloudUser {
        val result = postJson(
            "/auth/password/reset",
            JSONObject()
                .put("phone", phone)
                .put("code", code)
                .put("password", password)
        )
        return result.toCloudUser()
    }

    fun verifyCurrentPhoneForChange(user: CloudUser, oldPhone: String, oldCode: String): String {
        useUserToken(user)
        val result = postJson(
            "/auth/phone/change/verify-current",
            JSONObject()
                .put("old_phone", oldPhone)
                .put("old_code", oldCode)
        )
        return result.getString("verification_token")
    }

    fun changePhone(user: CloudUser, oldPhone: String, oldVerificationToken: String, newPhone: String, newCode: String): CloudUser {
        useUserToken(user)
        val result = postJson(
            "/auth/phone/change",
            JSONObject()
                .put("old_phone", oldPhone)
                .put("old_verification_token", oldVerificationToken)
                .put("new_phone", newPhone)
                .put("new_code", newCode)
        )
        return result.toCloudUser()
    }

    fun setPassword(user: CloudUser, password: String) {
        useUserToken(user)
        postJson(
            "/auth/password/set",
            JSONObject().put("password", password)
        )
    }

    fun changePassword(user: CloudUser, oldPassword: String, newPassword: String) {
        useUserToken(user)
        postJson(
            "/auth/password/change",
            JSONObject()
                .put("old_password", oldPassword)
                .put("new_password", newPassword)
        )
    }

    fun getCloudBootstrap(user: CloudUser): CloudBootstrap {
        useUserToken(user)
        val result = getJson("/sync/bootstrap")
        return result.toCloudBootstrap()
    }

    fun getMembershipProfile(user: CloudUser): MembershipProfile {
        useUserToken(user)
        return getJson("/membership/me").toMembershipProfile()
    }

    fun createLiveDirectSession(user: CloudUser): LiveDirectSession {
        useUserToken(user)
        cachedLiveDirectSession(user)?.let { return it }
        val session = postJson("/live/session", JSONObject(), 15_000).toLiveDirectSession()
        synchronized(LIVE_SESSION_CACHE_LOCK) {
            cachedLiveSessionBaseUrl = baseUrl
            cachedLiveSessionToken = user.accessToken
            cachedLiveSession = session
        }
        return session
    }

    fun prewarmLiveDirectSession(user: CloudUser) {
        useUserToken(user)
        if (cachedLiveDirectSession(user) != null) return
        runCatching { createLiveDirectSession(user) }
    }

    fun createAlipayPaymentOrder(user: CloudUser, planId: String): AlipayPaymentOrder {
        useUserToken(user)
        return postJson(
            "/payments/alipay/orders",
            JSONObject().put("plan_id", planId)
        ).toAlipayPaymentOrder()
    }

    fun createAlipayAddonPaymentOrder(user: CloudUser, addonId: String, quantity: Int = 1): AlipayPaymentOrder {
        useUserToken(user)
        return postJson(
            "/payments/alipay/addon-orders",
            JSONObject()
                .put("addon_id", addonId)
                .put("quantity", quantity.coerceAtLeast(1))
        ).toAlipayPaymentOrder()
    }

    fun listPaymentOrders(user: CloudUser): List<PaymentOrder> {
        useUserToken(user)
        return getJson("/payments/orders").optJSONArray("items").orEmpty().mapObjects { item ->
            item.toPaymentOrder()
        }
    }

    fun getPaymentOrder(user: CloudUser, orderId: String): PaymentOrder? {
        useUserToken(user)
        val item = getJson("/payments/orders/${URLEncoder.encode(orderId, "UTF-8")}").optJSONObject("order")
        return item?.toPaymentOrder()
    }

    fun syncAlipayPaymentOrder(user: CloudUser, orderId: String): PaymentOrder? {
        useUserToken(user)
        val item = postJson(
            "/payments/alipay/orders/${URLEncoder.encode(orderId, "UTF-8")}/sync",
            JSONObject(),
            PAYMENT_SYNC_READ_TIMEOUT_MS
        ).optJSONObject("order")
        return item?.toPaymentOrder()
    }

    fun syncSchedule(user: CloudUser, meeting: ScheduledMeeting): ScheduledMeeting {
        useUserToken(user)
        val result = putJson(
            "/sync/schedules/${URLEncoder.encode(meeting.id, "UTF-8")}",
            meeting.toRemoteScheduleJson()
        )
        return result.toLocalScheduledMeeting()
    }

    fun deleteSchedule(user: CloudUser, scheduleId: String) {
        useUserToken(user)
        delete("/sync/schedules/${URLEncoder.encode(scheduleId, "UTF-8")}")
    }

    fun clearUserCloudData(user: CloudUser) {
        useUserToken(user)
        delete("/sync/all")
    }

    fun uploadAndProcess(
        task: MeetingTask,
        userId: String,
        onProgress: (RemoteTaskSnapshot) -> Unit = {}
    ): MeetingProcessingResult {
        val remoteTask = uploadTask(task, userId)
        onProgress(remoteTask)
        onProgress(startTaskProcessing(remoteTask.id, task, userId).task)
        while (true) {
            Thread.sleep(1_000)
            val detail = getTaskDetail(remoteTask.id, task, userId)
            onProgress(detail.task)
            when (detail.task.status) {
                MeetingTaskStatus.Finished -> return detail.result ?: getTaskResult(remoteTask.id, task, userId)
                MeetingTaskStatus.Failed -> error(detail.task.errorMessage ?: "处理失败")
                MeetingTaskStatus.Canceled -> error(detail.task.errorMessage ?: "任务已终止")
                else -> Unit
            }
        }
    }

    fun uploadTask(task: MeetingTask, userId: String? = null, persistToCloud: Boolean = true, deviceId: String? = null): RemoteTaskSnapshot {
        val file = File(task.localFilePath)
        require(file.exists()) { "本地文件不存在：${task.localFilePath}" }
        val upload = uploadFile(file, task, userId, persistToCloud, deviceId)
        return upload.getJSONObject("task").toRemoteTaskSnapshot()
    }

    fun startTaskProcessing(
        remoteTaskId: String,
        task: MeetingTask,
        userId: String,
        transcripts: List<TranscriptSegment>? = null
    ): RemoteTaskDetail {
        return postJson("/tasks/$remoteTaskId/process", task.processingContextJson(transcripts)).toRemoteTaskDetail(task, remoteTaskId)
    }

    fun retryTaskProcessing(
        remoteTaskId: String,
        task: MeetingTask,
        userId: String,
        transcripts: List<TranscriptSegment>? = null
    ): RemoteTaskDetail {
        return postJson("/tasks/$remoteTaskId/retry", task.processingContextJson(transcripts)).toRemoteTaskDetail(task, remoteTaskId)
    }

    fun getTaskDetail(remoteTaskId: String, task: MeetingTask, userId: String): RemoteTaskDetail {
        return getJson("/tasks/$remoteTaskId").toRemoteTaskDetail(task, remoteTaskId)
    }

    fun getTaskResult(remoteTaskId: String, task: MeetingTask, userId: String): MeetingProcessingResult {
        return getJson("/tasks/$remoteTaskId/result").toLocalResult(task, remoteTaskId)
    }

    fun exportTaskText(remoteTaskId: String, userId: String, format: String = "markdown", includeTranscript: Boolean = false): String {
        return getText("/tasks/$remoteTaskId/export?format=${URLEncoder.encode(format, "UTF-8")}&include_transcript=$includeTranscript")
    }

    fun downloadTaskAudio(remoteTaskId: String, targetFile: File): Long {
        val connection = (URL("$baseUrl/tasks/$remoteTaskId/audio").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 30_000
            readTimeout = 600_000
            applyAuthHeader()
        }
        try {
            if (connection.responseCode !in 200..299) {
                val text = connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                val body = runCatching { JSONObject(text) }.getOrNull()
                val detail = body?.optString("detail").orEmpty()
                val userMessage = body?.optString("user_message").orEmpty()
                val rawMessage = detail.ifBlank { "音频下载失败：HTTP ${connection.responseCode}" }
                throw HuixiaoApiException(
                    statusCode = connection.responseCode,
                    rawMessage = rawMessage,
                    message = userMessage.ifBlank { rawMessage.userFacingApiDetail(connection.responseCode) }
                )
            }
            targetFile.parentFile?.mkdirs()
            connection.inputStream.use { input ->
                targetFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            return targetFile.length()
        } finally {
            connection.disconnect()
        }
    }

    fun updateTaskResult(remoteTaskId: String, result: MeetingProcessingResult, userId: String): MeetingProcessingResult {
        val updated = putJson("/tasks/$remoteTaskId/result", result.toRemoteJson())
        return updated.toLocalResult(
            taskId = result.taskId,
            remoteTaskId = remoteTaskId,
            sourceFilePath = result.sourceFilePath,
            source = result.source
        )
    }

    fun listSpeakerProfiles(user: CloudUser): List<SpeakerProfile> {
        useUserToken(user)
        val array = JSONArray(getText("/voiceprints/profiles"))
        return array.mapObjects { item -> item.toSpeakerProfile() }
    }

    fun rememberSpeakerProfile(
        user: CloudUser,
        remoteTaskId: String,
        speaker: SpeakerIdentity,
        displayName: String
    ): SpeakerProfile {
        useUserToken(user)
        val body = JSONObject()
            .put("task_id", remoteTaskId)
            .put("speaker_id", speaker.id)
            .put("speaker_name", speaker.displayName)
            .put("display_name", displayName)
        return postJson("/voiceprints/profiles/from-task", body).toSpeakerProfile()
    }

    fun enrollSpeakerProfileFromAudio(
        user: CloudUser,
        displayName: String,
        localFilePath: String,
        profileId: String? = null
    ): SpeakerProfile {
        useUserToken(user)
        val file = File(localFilePath)
        require(file.exists()) { "采样音频不存在：$localFilePath" }
        val boundary = "HuixiaoBoundary${UUID.randomUUID().toString().replace("-", "")}"
        val connection = (URL("$baseUrl/voiceprints/profiles/from-audio").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 30_000
            readTimeout = 240_000
            doOutput = true
            setChunkedStreamingMode(0)
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            applyAuthHeader()
        }
        DataOutputStream(BufferedOutputStream(connection.outputStream)).use { output ->
            output.writeFormField(boundary, "display_name", displayName)
            profileId?.takeIf { it.isNotBlank() }?.let {
                output.writeFormField(boundary, "profile_id", it)
            }
            output.writeBytes("--$boundary\r\n")
            output.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"${file.asciiUploadName()}\"; filename*=UTF-8''${file.encodedUploadName()}\r\n")
            output.writeBytes("Content-Type: ${file.contentType()}\r\n\r\n")
            file.inputStream().use { input -> input.copyTo(output) }
            output.writeBytes("\r\n--$boundary--\r\n")
            output.flush()
        }
        return connection.readJsonObject().toSpeakerProfile()
    }

    fun deleteSpeakerProfile(user: CloudUser, profileId: String) {
        useUserToken(user)
        delete("/voiceprints/profiles/${URLEncoder.encode(profileId, "UTF-8")}")
    }

    fun updateSpeakerProfile(
        user: CloudUser,
        profileId: String,
        displayName: String? = null,
        active: Boolean? = null
    ): SpeakerProfile {
        useUserToken(user)
        val body = JSONObject()
        displayName?.let { body.put("display_name", it) }
        active?.let { body.put("active", it) }
        return patchJson("/voiceprints/profiles/${URLEncoder.encode(profileId, "UTF-8")}", body).toSpeakerProfile()
    }

    fun updateTaskTitle(remoteTaskId: String, title: String, userId: String): RemoteTaskSnapshot {
        return updateTaskMetadata(remoteTaskId, userId, title = title)
    }

    fun updateTaskMetadata(
        remoteTaskId: String,
        userId: String,
        title: String? = null,
        confirmed: Boolean? = null,
        createdAtMillis: Long? = null,
        isPrivate: Boolean? = null,
        knowledgeScope: KnowledgeIndexScope? = null
    ): RemoteTaskSnapshot {
        val body = JSONObject()
        title?.let { body.put("title", it) }
        confirmed?.let { body.put("confirmed", it) }
        createdAtMillis?.let { body.put("created_at_millis", it) }
        isPrivate?.let { body.put("is_private", it) }
        knowledgeScope?.let { body.put("knowledge_scope", it.toRemoteKnowledgeScope()) }
        return patchJson(
            "/tasks/$remoteTaskId",
            body
        ).toRemoteTaskSnapshot()
    }

    fun regenerateMinutes(remoteTaskId: String, result: MeetingProcessingResult, userId: String): MeetingProcessingResult {
        val body = JSONObject()
            .put("transcripts", result.transcripts.toRemoteTranscriptArray())
        val updated = postJson("/tasks/$remoteTaskId/regenerate-minutes", body, LONG_MINUTES_READ_TIMEOUT_MS)
        return updated.toLocalResult(
            taskId = result.taskId,
            remoteTaskId = remoteTaskId,
            sourceFilePath = result.sourceFilePath,
            source = result.source
        )
    }

    fun regenerateLocalMinutes(task: MeetingTask, result: MeetingProcessingResult): MeetingProcessingResult {
        val body = JSONObject()
            .put("task_id", task.id)
            .put("title", task.title.ifBlank { result.taskId })
            .put("source_file_path", result.sourceFilePath)
            .put("participants", result.participants ?: JSONObject.NULL)
            .put("meeting_note", task.scheduleNote ?: JSONObject.NULL)
            .put("tags", JSONArray(result.tags))
            .put("transcripts", result.transcripts.toRemoteTranscriptArray())
        val updated = postJson("/tasks/regenerate-local-minutes", body, LONG_MINUTES_READ_TIMEOUT_MS)
        return updated.toLocalResult(
            taskId = task.id,
            remoteTaskId = result.remoteTaskId ?: task.remoteTaskId ?: task.id,
            sourceFilePath = result.sourceFilePath,
            source = result.source
        )
    }

    fun deleteTask(remoteTaskId: String, userId: String) {
        delete("/tasks/$remoteTaskId")
    }

    fun cancelTask(remoteTaskId: String, userId: String): RemoteTaskSnapshot {
        return postJson("/tasks/$remoteTaskId/cancel", JSONObject()).toRemoteTaskSnapshot()
    }

    fun clearServerData() {
        delete("/data/local")
    }

    fun askKnowledge(
        question: String,
        userId: String,
        userName: String?,
        scope: KnowledgeQueryScope,
        localSources: List<LocalKnowledgeChunk> = emptyList(),
        taskIds: List<String> = emptyList(),
        contextTaskIds: List<String> = emptyList(),
        contextMessages: List<KnowledgeChatContextItem> = emptyList()
    ): RemoteKnowledgeAnswer {
        val body = JSONObject()
            .put("question", question)
            .put("user_id", userId)
            .put("user_name", userName?.trim().orEmpty())
            .put("limit", 6)
            .put("scope", scope.toRemoteKnowledgeScope())
            .put("local_sources", localSources.toRemoteLocalSources())
        if (taskIds.isNotEmpty()) {
            body.put("task_ids", JSONArray(taskIds))
        }
        if (contextTaskIds.isNotEmpty()) {
            body.put("context_task_ids", JSONArray(contextTaskIds))
        }
        if (contextMessages.isNotEmpty()) {
            body.put("context_messages", contextMessages.toRemoteKnowledgeContext())
        }
        val result = postJson("/knowledge/ask", body)
        val sources = result.optJSONArray("sources").orEmpty().mapObjects { item ->
            RemoteKnowledgeSource(
                chunkId = item.cleanJsonText("chunk_id").orEmpty(),
                taskId = item.cleanJsonText("task_id").orEmpty(),
                title = item.cleanJsonText("title") ?: "会议记录",
                text = item.cleanJsonText("text").orEmpty(),
                chunkType = item.cleanJsonText("chunk_type"),
                meetingDate = item.cleanJsonText("meeting_date"),
                speaker = item.cleanJsonText("speaker"),
                timestamp = item.cleanJsonText("timestamp"),
                startMs = item.optNullableLong("start_ms"),
                endMs = item.optNullableLong("end_ms"),
                score = item.optDouble("score", 0.0),
                scope = item.optString("scope", "cloud")
            )
        }
        return RemoteKnowledgeAnswer(
            answer = result.cleanJsonText("answer")?.toUserFacingKnowledgeAnswer(question) ?: question.defaultKnowledgeFallback(),
            sources = sources
        )
    }

    private fun uploadFile(
        file: File,
        task: MeetingTask,
        userId: String? = null,
        persistToCloud: Boolean = true,
        deviceId: String? = null
    ): JSONObject {
        val sourceValue = when (task.source) {
            MeetingTaskSource.Recording -> "recording"
            MeetingTaskSource.Import -> "import"
        }
        val boundary = "HuixiaoBoundary${UUID.randomUUID().toString().replace("-", "")}"
        val deviceQuery = deviceId?.let { "&device_id=${URLEncoder.encode(it, "UTF-8")}" }.orEmpty()
        val clientTaskQuery = if (persistToCloud) "&client_task_id=${URLEncoder.encode(task.id, "UTF-8")}" else ""
        val url = URL("$baseUrl/files/upload?source=${URLEncoder.encode(sourceValue, "UTF-8")}&persist_to_cloud=$persistToCloud&is_private=${task.isPrivate}$deviceQuery$clientTaskQuery&confirmed=${task.confirmed}&created_at_millis=${task.createdAtMillis}")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 30_000
            readTimeout = uploadReadTimeoutMs(file.length())
            doOutput = true
            setChunkedStreamingMode(0)
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            applyAuthHeader()
        }
        DataOutputStream(BufferedOutputStream(connection.outputStream)).use { output ->
            output.writeBytes("--$boundary\r\n")
            output.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"${file.asciiUploadName()}\"; filename*=UTF-8''${file.encodedUploadName()}\r\n")
            output.writeBytes("Content-Type: ${file.contentType()}\r\n\r\n")
            file.inputStream().use { input -> input.copyTo(output) }
            output.writeBytes("\r\n--$boundary--\r\n")
            output.flush()
        }
        return connection.readJsonObject()
    }

    private fun postJson(path: String, body: JSONObject, readTimeoutMs: Int = DEFAULT_JSON_READ_TIMEOUT_MS): JSONObject {
        val connection = (URL("$baseUrl$path").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 30_000
            readTimeout = readTimeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            applyAuthHeader()
        }
        connection.outputStream.use { output ->
            output.write(body.toString().toByteArray(Charsets.UTF_8))
        }
        return connection.readJsonObject()
    }

    private fun putJson(path: String, body: JSONObject): JSONObject {
        val connection = (URL("$baseUrl$path").openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            connectTimeout = 30_000
            readTimeout = 600_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            applyAuthHeader()
        }
        connection.outputStream.use { output ->
            output.write(body.toString().toByteArray(Charsets.UTF_8))
        }
        return connection.readJsonObject()
    }

    private fun patchJson(path: String, body: JSONObject): JSONObject {
        val connection = (URL("$baseUrl$path").openConnection() as HttpURLConnection).apply {
            requestMethod = "PATCH"
            connectTimeout = 30_000
            readTimeout = 120_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            applyAuthHeader()
        }
        connection.outputStream.use { output ->
            output.write(body.toString().toByteArray(Charsets.UTF_8))
        }
        return connection.readJsonObject()
    }

    private fun getText(path: String): String {
        val connection = (URL("$baseUrl$path").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 30_000
            readTimeout = 120_000
            applyAuthHeader()
        }
        return connection.readText()
    }

    private fun getJson(path: String): JSONObject {
        return JSONObject(getText(path))
    }

    private fun delete(path: String): JSONObject {
        val connection = (URL("$baseUrl$path").openConnection() as HttpURLConnection).apply {
            requestMethod = "DELETE"
            connectTimeout = 30_000
            readTimeout = 120_000
            applyAuthHeader()
        }
        return connection.readJsonObject()
    }

    private fun HttpURLConnection.applyAuthHeader() {
        accessToken?.takeIf { it.isNotBlank() }?.let { token ->
            setRequestProperty("Authorization", "Bearer $token")
        }
    }

    private fun DataOutputStream.writeFormField(boundary: String, name: String, value: String) {
        write("--$boundary\r\n".toByteArray(Charsets.UTF_8))
        write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n".toByteArray(Charsets.UTF_8))
        write(value.toByteArray(Charsets.UTF_8))
        write("\r\n".toByteArray(Charsets.UTF_8))
    }

    private fun useUserToken(user: CloudUser) {
        user.accessToken.takeIf { it.isNotBlank() }?.let { setAccessToken(it) }
    }

    private fun cachedLiveDirectSession(user: CloudUser): LiveDirectSession? {
        val now = System.currentTimeMillis()
        synchronized(LIVE_SESSION_CACHE_LOCK) {
            val session = cachedLiveSession ?: return null
            if (cachedLiveSessionBaseUrl != baseUrl || cachedLiveSessionToken != user.accessToken) return null
            if (session.expiresAt * 1000L - now <= LIVE_DIRECT_SESSION_MIN_VALID_MS) return null
            if (now - session.fetchedAtMillis > LIVE_DIRECT_SESSION_CACHE_MAX_AGE_MS) return null
            return session
        }
    }

    private companion object {
        private val LIVE_SESSION_CACHE_LOCK = Any()

        @Volatile private var cachedLiveSessionBaseUrl: String? = null
        @Volatile private var cachedLiveSessionToken: String? = null
        @Volatile private var cachedLiveSession: LiveDirectSession? = null
    }

    private fun uploadReadTimeoutMs(sizeBytes: Long): Int {
        val sizeMb = (sizeBytes.coerceAtLeast(0L) + 1024L * 1024L - 1L) / (1024L * 1024L)
        val calculated = UPLOAD_MIN_READ_TIMEOUT_MS + sizeMb * UPLOAD_READ_TIMEOUT_PER_MB_MS
        return calculated.coerceIn(UPLOAD_MIN_READ_TIMEOUT_MS.toLong(), UPLOAD_MAX_READ_TIMEOUT_MS.toLong()).toInt()
    }

    private fun HttpURLConnection.readJsonObject(): JSONObject {
        return JSONObject(readText())
    }

    private fun HttpURLConnection.readText(): String {
        val stream = if (responseCode in 200..299) inputStream else errorStream
        val text = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        if (responseCode !in 200..299) {
            val body = runCatching { JSONObject(text) }.getOrNull()
            val detail = body?.optString("detail").orEmpty()
            val userMessage = body?.optString("user_message").orEmpty()
            val rawMessage = detail.ifBlank { "服务端请求失败：HTTP $responseCode" }
            throw HuixiaoApiException(
                statusCode = responseCode,
                rawMessage = rawMessage,
                message = userMessage.ifBlank { rawMessage.userFacingApiDetail(responseCode) }
            )
        }
        return text
    }

    private fun JSONObject.toLocalResult(task: MeetingTask, remoteTaskId: String): MeetingProcessingResult {
        return toLocalResult(
            taskId = task.id,
            remoteTaskId = remoteTaskId,
            sourceFilePath = task.localFilePath,
            source = task.source
        )
    }

    private fun JSONObject.toRemoteTaskDetail(task: MeetingTask, remoteTaskId: String): RemoteTaskDetail {
        val remoteTask = getJSONObject("task").toRemoteTaskSnapshot()
        val result = if (has("result") && !isNull("result")) {
            getJSONObject("result").toLocalResult(task, remoteTaskId)
        } else {
            null
        }
        return RemoteTaskDetail(task = remoteTask, result = result)
    }

    private fun JSONObject.toRemoteTaskSnapshot(): RemoteTaskSnapshot {
        return RemoteTaskSnapshot(
            id = getString("id"),
            status = optString("status").toLocalTaskStatus(),
            errorMessage = cleanJsonText("error_message")?.userFacingDisplayText("处理失败，请稍后重试"),
            progressPercent = optDouble("progress_percent", 0.0).toFloat().coerceIn(0f, 100f),
            progressLabel = cleanJsonText("progress_label"),
            progressStage = cleanJsonText("progress_stage")
        )
    }

    private fun JSONObject.toSpeakerProfile(): SpeakerProfile {
        return SpeakerProfile(
            id = getString("id"),
            displayName = optString("display_name").ifBlank { "未命名声纹" },
            sampleCount = optInt("sample_count", 0),
            active = optBoolean("active", true),
            updatedAt = optString("updated_at")
        )
    }

    private fun JSONObject.toCloudUser(): CloudUser {
        val token = getString("access_token")
        return CloudUser(
            userId = getString("user_id"),
            username = getString("username"),
            displayName = optString("display_name", getString("username")),
            phone = optString("phone"),
            accessToken = token,
            expiresAtMillis = System.currentTimeMillis() + optLong("expires_in", 0L).coerceAtLeast(0L) * 1000L
        ).also { setAccessToken(token) }
    }

    private fun JSONObject.toCloudBootstrap(): CloudBootstrap {
        val userId = getString("user_id")
        val taskArray = optJSONArray("tasks").orEmpty()
        val items = buildList {
            for (index in 0 until taskArray.length()) {
                val item = taskArray.getJSONObject(index)
                val taskObject = item.getJSONObject("task")
                if (taskObject.optString("sync_scope", "cloud") != "cloud") continue
                if (!item.has("result") || item.isNull("result")) continue
                val fileObject = item.getJSONObject("file")
                val remoteTaskId = taskObject.getString("id")
                val clientTaskId = taskObject.cleanJsonText("client_task_id") ?: remoteTaskId
                val source = taskObject.optString("source").toLocalTaskSource()
                val resultObject = item.getJSONObject("result")
                val result = resultObject.toLocalResult(
                    taskId = clientTaskId,
                    remoteTaskId = remoteTaskId,
                    sourceFilePath = resultObject.optString("source_file_path", fileObject.optString("stored_path")),
                    source = source
                )
                val remoteStatus = taskObject.optString("status").toLocalTaskStatus()
                val createdAtMillis = taskObject.optNullableLong("created_at_millis") ?: taskObject.cleanJsonText("created_at").toEpochMillisOrNow()
                val rawTitle = taskObject.cleanJsonText("title") ?: fileObject.cleanJsonText("original_name")
                val localTask = MeetingTask(
                    id = clientTaskId,
                    remoteTaskId = remoteTaskId,
                    title = meetingDisplayTitleOrDefault(rawTitle, createdAtMillis),
                    source = source,
                    status = if (remoteStatus == MeetingTaskStatus.WaitingProcess) MeetingTaskStatus.Finished else remoteStatus,
                    localFilePath = "",
                    createdAtLabel = createdAtMillis.toRelativeDateLabel(),
                    createdAtMillis = createdAtMillis,
                    sizeLabel = fileObject.optLong("size_bytes", 0L).takeIf { it > 0 }?.toReadableSize(),
                    errorMessage = taskObject.cleanJsonText("error_message"),
                    progressPercent = taskObject.optDouble("progress_percent", 0.0).toFloat().coerceIn(0f, 100f),
                    progressLabel = taskObject.cleanJsonText("progress_label"),
                    progressStage = taskObject.cleanJsonText("progress_stage"),
                    confirmed = taskObject.optBoolean("confirmed", false),
                    syncStatus = CloudSyncStatus.Synced,
                    knowledgeScope = taskObject.optString("knowledge_scope").toLocalKnowledgeIndexScope(),
                    isPrivate = taskObject.optBoolean("is_private", false),
                    deviceId = taskObject.cleanJsonText("device_id"),
                    recognitionLanguage = RecognitionLanguage.fromRemote(taskObject.cleanJsonText("recognition_language"))
                )
                add(CloudTaskItem(localTask, result))
            }
        }
        val schedules = optJSONArray("schedules").orEmpty().mapObjects { it.toLocalScheduledMeeting() }
        return CloudBootstrap(userId = userId, tasks = items, schedules = schedules)
    }

    private fun JSONObject.toMembershipProfile(): MembershipProfile {
        val plans = optJSONArray("plans").orEmpty().mapObjects { item ->
            MembershipPlan(
                id = item.optString("id"),
                name = item.optString("name"),
                priceCents = item.optInt("price_cents", 0),
                price = item.optDouble("price", 0.0),
                transcriptionMinutes = item.optInt("transcription_minutes", 0),
                hours = item.optDouble("hours", item.optInt("transcription_minutes", 0) / 60.0),
                knowledgeQa = item.optInt("knowledge_qa", 0),
                enabled = item.optBoolean("enabled", true)
            )
        }
        val addons = optJSONArray("addons").orEmpty().mapObjects { item ->
            MembershipAddon(
                id = item.optString("id"),
                name = item.optString("name"),
                unit = item.optString("unit", "hour"),
                priceCents = item.optInt("price_cents", 0),
                price = item.optDouble("price", 0.0),
                enabled = item.optBoolean("enabled", true)
            )
        }
        return MembershipProfile(
            active = optBoolean("active", false),
            accountStatus = optString("account_status", "normal").ifBlank { "normal" },
            planId = optString("plan_id", "none").ifBlank { "none" },
            planName = optString("plan_name", "无套餐").ifBlank { "无套餐" },
            expiresAt = cleanJsonText("expires_at"),
            periodMonth = optString("period_month"),
            transcriptionMinutesTotal = optInt("transcription_minutes_total", 0),
            transcriptionMinutesUsed = optInt("transcription_minutes_used", 0),
            knowledgeQaTotal = optInt("knowledge_qa_total", 0),
            knowledgeQaUsed = optInt("knowledge_qa_used", 0),
            paymentEnabled = optBoolean("payment_enabled", false),
            plans = plans,
            addons = addons
        )
    }

    private fun JSONObject.toAlipayPaymentOrder(): AlipayPaymentOrder {
        return AlipayPaymentOrder(
            order = getJSONObject("order").toPaymentOrder(),
            orderString = optString("order_string"),
            payUrl = optString("pay_url"),
            paymentMode = optString("payment_mode", if (optString("pay_url").isNotBlank()) "wap" else "app")
        )
    }

    private fun JSONObject.toPaymentOrder(): PaymentOrder {
        return PaymentOrder(
            id = optString("id"),
            productType = optString("productType"),
            planId = optString("planId"),
            planName = optString("planName"),
            addonId = optString("addonId"),
            addonName = optString("addonName"),
            productName = optString("productName"),
            transcriptionMinutes = optInt("transcriptionMinutes", 0),
            amount = optDouble("amount", 0.0),
            status = optString("status"),
            channel = optString("channel"),
            createdAt = cleanJsonText("createdAt") ?: cleanJsonText("date").orEmpty(),
            paidAt = cleanJsonText("paidAt").orEmpty(),
            updatedAt = cleanJsonText("updatedAt").orEmpty(),
            channelNo = cleanJsonText("channelNo").orEmpty()
        )
    }

    private fun JSONObject.toLocalResult(
        taskId: String,
        remoteTaskId: String,
        sourceFilePath: String,
        source: MeetingTaskSource
    ): MeetingProcessingResult {
        val summary = optString("summary").trim()
        val transcriptItems = optJSONArray("transcripts").orEmpty().mapObjects { item ->
            TranscriptSegment(
                speaker = item.optString("speaker", "说话人"),
                text = item.optString("text"),
                timestamp = item.optString("timestamp", "00:00"),
                startMs = item.optNullableLong("start_ms"),
                endMs = item.optNullableLong("end_ms"),
                speakerId = item.cleanJsonText("speaker_id")
            )
        }.filter { it.text.isNotBlank() }
        require(summary.isNotBlank()) { "LLM 未返回真实会议摘要" }
        require(transcriptItems.isNotEmpty()) { "ASR 未返回真实转写结果" }
        return MeetingProcessingResult(
            taskId = taskId,
            remoteTaskId = remoteTaskId,
            sourceFilePath = sourceFilePath,
            source = source,
            participants = cleanJsonText("participants"),
            tags = optJSONArray("tags").orEmpty().mapStrings(),
            summary = summary,
            topics = optJSONArray("topics").orEmpty().mapObjects { item ->
                TopicItem(
                    id = item.optString("id").ifBlank { "topic-${UUID.randomUUID()}" },
                    title = item.optString("title"),
                    summary = item.optString("summary"),
                    source = item.optString("source"),
                    sourceTimestamp = item.optString("source_timestamp").ifBlank { null }
                )
            }.filter { it.title.isNotBlank() },
            decisions = optJSONArray("decisions").orEmpty().mapStrings(),
            todos = optJSONArray("todos").orEmpty().mapObjects { item ->
                TodoItem(
                    id = item.optString("id").ifBlank { "todo-${UUID.randomUUID()}" },
                    title = item.optString("title"),
                    source = item.optString("source"),
                    done = item.optBoolean("done", false),
                    sourceTimestamp = item.cleanJsonText("source_timestamp"),
                    meetingId = item.cleanJsonText("meeting_id"),
                    meetingTitle = item.cleanJsonText("meeting_title"),
                    description = item.cleanJsonText("description").orEmpty(),
                    assigneeName = item.cleanJsonText("assignee"),
                    assigneeId = item.cleanJsonText("assignee_id"),
                    dueAtLabel = item.cleanJsonText("due_at"),
                    dueAtMillis = item.optNullableLong("due_at_millis") ?: item.optString("due_at").parseTodoDueMillis(),
                    priority = item.cleanJsonText("priority").normalizedTodoPriority(),
                    status = item.optString("status").toLocalTodoStatus(item.optBoolean("done", false)),
                    completedAtMillis = item.optNullableLong("completed_at_millis"),
                    completedAtLabel = item.cleanJsonText("completed_at"),
                    sourceSegmentIndex = item.optNullableInt("source_segment_index"),
                    lockedFields = item.optJSONArray("locked_fields").orEmpty().mapStrings().toSet()
                )
            },
            risks = optJSONArray("risks").orEmpty().mapObjects { item ->
                RiskItem(
                    id = item.optString("id").ifBlank { "risk-${UUID.randomUUID()}" },
                    title = item.optString("title"),
                    level = item.optString("level"),
                    description = item.optString("description"),
                    recommendation = item.optString("recommendation"),
                    source = item.optString("source"),
                    sourceTimestamp = item.optString("source_timestamp").ifBlank { null }
                )
            }.filter { it.title.isNotBlank() },
            transcripts = transcriptItems,
            generatedAtLabel = "刚刚"
        )
    }

    private fun File.contentType(): String {
        return when (extension.lowercase()) {
            "m4a" -> "audio/mp4"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "aac" -> "audio/aac"
            "mp4" -> "video/mp4"
            "mov" -> "video/quicktime"
            else -> "application/octet-stream"
        }
    }

    private fun File.asciiUploadName(): String {
        val safeName = name
            .map { char ->
                when {
                    char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' || char == '.' || char == '-' || char == '_' -> char
                    else -> '_'
                }
            }
            .joinToString("")
            .trim('.', '_', '-')
        if (safeName.isNotBlank()) return safeName
        val suffix = extension
            .lowercase()
            .filter { it in 'a'..'z' || it in '0'..'9' }
            .ifBlank { "bin" }
        return "upload.$suffix"
    }

    private fun File.encodedUploadName(): String {
        return URLEncoder.encode(name.ifBlank { asciiUploadName() }, "UTF-8").replace("+", "%20")
    }
}

private fun MeetingProcessingResult.toRemoteJson(): JSONObject {
    return JSONObject()
        .put("participants", participants ?: JSONObject.NULL)
        .put("tags", JSONArray(tags))
        .put("summary", summary)
        .put("topics", topics.toRemoteTopicArray())
        .put("decisions", JSONArray(decisions))
        .put("todos", todos.toRemoteTodoArray())
        .put("risks", risks.toRemoteRiskArray())
        .put("transcripts", transcripts.toRemoteTranscriptArray())
}

private fun MeetingTask.processingContextJson(transcripts: List<TranscriptSegment>? = null): JSONObject {
    return JSONObject().apply {
        scheduleNote?.takeIf { it.isNotBlank() }?.let { put("meeting_note", it) }
        scheduleId?.takeIf { it.isNotBlank() }?.let { put("schedule_id", it) }
        put("recognition_language", recognitionLanguage.remoteValue)
        transcripts?.filter { it.text.isNotBlank() }?.takeIf { it.isNotEmpty() }?.let {
            put("transcripts", it.toRemoteTranscriptArray())
        }
    }
}

private fun List<TopicItem>.toRemoteTopicArray(): JSONArray {
    val array = JSONArray()
    forEach { item ->
        array.put(
            JSONObject()
                .put("id", item.id)
                .put("title", item.title)
                .put("summary", item.summary)
                .put("source", item.source)
                .put("source_timestamp", item.sourceTimestamp ?: JSONObject.NULL)
        )
    }
    return array
}

private fun List<TodoItem>.toRemoteTodoArray(): JSONArray {
    val array = JSONArray()
    forEach { item ->
        array.put(
            JSONObject()
                .put("id", item.id)
                .put("title", item.title)
                .put("source", item.source)
                .put("done", item.done)
                .put("source_timestamp", item.sourceTimestamp ?: JSONObject.NULL)
                .put("meeting_id", item.meetingId ?: JSONObject.NULL)
                .put("meeting_title", item.meetingTitle ?: JSONObject.NULL)
                .put("description", item.description)
                .put("assignee", item.assigneeName ?: JSONObject.NULL)
                .put("assignee_id", item.assigneeId ?: JSONObject.NULL)
                .put("due_at", item.dueAtLabel ?: JSONObject.NULL)
                .put("due_at_millis", item.dueAtMillis ?: JSONObject.NULL)
                .put("priority", item.priority.normalizedTodoPriority())
                .put("status", item.effectiveStatus.toRemoteTodoStatus())
                .put("completed_at", item.completedAtLabel ?: JSONObject.NULL)
                .put("completed_at_millis", item.completedAtMillis ?: JSONObject.NULL)
                .put("source_segment_index", item.sourceSegmentIndex ?: JSONObject.NULL)
                .put("locked_fields", JSONArray(item.lockedFields.toList()))
        )
    }
    return array
}

private fun List<RiskItem>.toRemoteRiskArray(): JSONArray {
    val array = JSONArray()
    forEach { item ->
        array.put(
            JSONObject()
                .put("id", item.id)
                .put("title", item.title)
                .put("level", item.level)
                .put("description", item.description)
                .put("recommendation", item.recommendation)
                .put("source", item.source)
                .put("source_timestamp", item.sourceTimestamp ?: JSONObject.NULL)
        )
    }
    return array
}

private fun List<TranscriptSegment>.toRemoteTranscriptArray(): JSONArray {
    val array = JSONArray()
    forEach { item ->
        array.put(
            JSONObject()
                .put("speaker_id", item.stableSpeakerId)
                .put("speaker", item.speaker)
                .put("text", item.text)
                .put("timestamp", item.timestamp)
                .put("start_ms", item.startMs ?: JSONObject.NULL)
                .put("end_ms", item.endMs ?: JSONObject.NULL)
        )
    }
    return array
}

private fun JSONArray?.orEmpty(): JSONArray = this ?: JSONArray()

private fun JSONArray.mapStrings(): List<String> {
    return buildList {
        for (index in 0 until length()) add(optString(index))
    }
}

private fun <T> JSONArray.mapObjects(block: (JSONObject) -> T): List<T> {
    return buildList {
        for (index in 0 until length()) {
            add(block(getJSONObject(index)))
        }
    }
}

private fun JSONObject.optNullableLong(name: String): Long? {
    return if (has(name) && !isNull(name)) optLong(name) else null
}

private fun JSONObject.optNullableInt(name: String): Int? {
    return if (has(name) && !isNull(name)) optInt(name) else null
}

private fun JSONObject.cleanJsonText(name: String): String? {
    if (!has(name) || isNull(name)) return null
    val clean = optString(name).trim()
    return clean.takeUnless { it.isBlank() || it.equals("null", ignoreCase = true) }
}

private fun String.toLocalTaskStatus(): MeetingTaskStatus {
    return when (this) {
        "waiting_process" -> MeetingTaskStatus.WaitingProcess
        "processing" -> MeetingTaskStatus.Processing
        "finished" -> MeetingTaskStatus.Finished
        "failed" -> MeetingTaskStatus.Failed
        "canceled" -> MeetingTaskStatus.Canceled
        else -> MeetingTaskStatus.WaitingProcess
    }
}

private fun String.toLocalTaskSource(): MeetingTaskSource {
    return when (this) {
        "recording" -> MeetingTaskSource.Recording
        else -> MeetingTaskSource.Import
    }
}

private fun Long.toReadableSize(): String {
    val mb = this / 1024.0 / 1024.0
    return if (mb >= 1) {
        "%.1f MB".format(mb)
    } else {
        "${(this / 1024).coerceAtLeast(1)} KB"
    }
}

private fun ScheduledMeeting.toRemoteScheduleJson(): JSONObject {
    return JSONObject()
        .put("id", id)
        .put("time", time)
        .put("title", title)
        .put("participants", participants)
        .put("note", note)
        .put("duration_label", durationLabel)
        .put("reminder_label", reminderLabel)
        .put("start_at_millis", startAtMillis ?: JSONObject.NULL)
        .put("end_at_millis", endAtMillis ?: JSONObject.NULL)
        .put("created_at_millis", createdAtMillis)
        .put("status", status.toRemoteScheduleStatus())
        .put("calendar_event_id", calendarEventId ?: JSONObject.NULL)
}

private fun JSONObject.toLocalScheduledMeeting(): ScheduledMeeting {
    return ScheduledMeeting(
        id = getString("id"),
        time = getString("time"),
        title = getString("title"),
        participants = getString("participants"),
        note = optString("note"),
        durationLabel = optString("duration_label"),
        reminderLabel = optString("reminder_label", "提前 5 分钟提醒"),
        startAtMillis = optNullableLong("start_at_millis"),
        endAtMillis = optNullableLong("end_at_millis"),
        createdAtMillis = optLong("created_at_millis", System.currentTimeMillis()),
        status = optString("status").toLocalScheduleStatus(),
        calendarEventId = optNullableLong("calendar_event_id")
    )
}

private fun JSONObject.toLiveDirectSession(): LiveDirectSession {
    return LiveDirectSession(
        provider = optString("provider", "aliyun"),
        apiKey = getString("api_key"),
        expiresAt = optLong("expires_at"),
        websocketUrl = getString("websocket_url"),
        workspaceId = optString("workspace_id"),
        model = optString("model", "qwen3-asr-flash-realtime"),
        sampleRate = optInt("sample_rate", 16_000),
        vadThreshold = optDouble("vad_threshold", 0.35),
        vadSilenceDurationMs = optInt("vad_silence_duration_ms", 450),
        filterFiller = optBoolean("filter_filler", true),
        fillerMaxDurationMs = optInt("filler_max_duration_ms", 1200),
        remainingMinutes = optInt("remaining_minutes", 0),
        graceMinutes = optInt("grace_minutes", 30),
        lowRemainingWarningMinutes = optInt("low_remaining_warning_minutes", 30)
    )
}

private fun ScheduledMeetingStatus.toRemoteScheduleStatus(): String {
    return when (this) {
        ScheduledMeetingStatus.Pending -> "pending"
        ScheduledMeetingStatus.Overdue -> "overdue"
        ScheduledMeetingStatus.Finished -> "finished"
    }
}

private fun String.toLocalScheduleStatus(): ScheduledMeetingStatus {
    return when (this) {
        "overdue" -> ScheduledMeetingStatus.Overdue
        "finished" -> ScheduledMeetingStatus.Finished
        else -> ScheduledMeetingStatus.Pending
    }
}

private fun String.toLocalTodoStatus(done: Boolean): TodoStatus {
    return when (this) {
        "todo" -> TodoStatus.Todo
        "in_progress" -> TodoStatus.InProgress
        "done" -> TodoStatus.Done
        "canceled" -> TodoStatus.Canceled
        "pending_confirm" -> TodoStatus.PendingConfirm
        else -> if (done) TodoStatus.Done else TodoStatus.PendingConfirm
    }
}

private fun TodoStatus.toRemoteTodoStatus(): String {
    return when (this) {
        TodoStatus.PendingConfirm -> "pending_confirm"
        TodoStatus.Todo -> "todo"
        TodoStatus.InProgress -> "in_progress"
        TodoStatus.Done -> "done"
        TodoStatus.Canceled -> "canceled"
    }
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

private fun String?.toEpochMillisOrNow(): Long {
    if (this.isNullOrBlank()) return System.currentTimeMillis()
    return try {
        Instant.parse(replace("+00:00", "Z")).toEpochMilli()
    } catch (_: DateTimeParseException) {
        System.currentTimeMillis()
    }
}

private fun Long.toRelativeDateLabel(): String {
    val today = java.util.Calendar.getInstance()
    val target = java.util.Calendar.getInstance().apply { timeInMillis = this@toRelativeDateLabel }
    fun sameDay(left: java.util.Calendar, right: java.util.Calendar): Boolean {
        return left.get(java.util.Calendar.YEAR) == right.get(java.util.Calendar.YEAR) &&
            left.get(java.util.Calendar.DAY_OF_YEAR) == right.get(java.util.Calendar.DAY_OF_YEAR)
    }
    if (sameDay(today, target)) return "今天"
    val yesterday = java.util.Calendar.getInstance().apply { add(java.util.Calendar.DAY_OF_YEAR, -1) }
    if (sameDay(yesterday, target)) return "昨天"
    return SimpleDateFormat("MM-dd", Locale.CHINA).format(java.util.Date(this))
}

private fun String.toLocalKnowledgeIndexScope(): KnowledgeIndexScope {
    return when (this) {
        "cloud" -> KnowledgeIndexScope.Cloud
        "excluded" -> KnowledgeIndexScope.Excluded
        else -> KnowledgeIndexScope.Cloud
    }
}

private fun KnowledgeQueryScope.toRemoteKnowledgeScope(): String {
    return when (this) {
        KnowledgeQueryScope.Local -> "local"
        KnowledgeQueryScope.Cloud -> "cloud"
        KnowledgeQueryScope.All -> "all"
    }
}

private fun KnowledgeIndexScope.toRemoteKnowledgeScope(): String {
    return when (this) {
        KnowledgeIndexScope.Local -> "local"
        KnowledgeIndexScope.Cloud -> "cloud"
        KnowledgeIndexScope.Excluded -> "excluded"
    }
}

private fun List<LocalKnowledgeChunk>.toRemoteLocalSources(): JSONArray {
    val array = JSONArray()
    forEach { chunk ->
        array.put(
            JSONObject()
                .put("chunk_id", chunk.chunkId)
                .put("task_id", chunk.taskId)
                .put("title", chunk.title)
                .put("text", chunk.text)
                .put("chunk_type", chunk.chunkType)
                .put("meeting_date", chunk.meetingDate)
                .put("created_at", chunk.createdAt ?: JSONObject.NULL)
                .put("speaker", chunk.speaker ?: JSONObject.NULL)
                .put("timestamp", chunk.timestamp ?: JSONObject.NULL)
                .put("start_ms", chunk.startMs ?: JSONObject.NULL)
                .put("end_ms", chunk.endMs ?: JSONObject.NULL)
                .put("score", 1.0)
        )
    }
    return array
}

private fun List<KnowledgeChatContextItem>.toRemoteKnowledgeContext(): JSONArray {
    val array = JSONArray()
    takeLast(6).forEach { message ->
        array.put(
            JSONObject()
                .put("role", message.role)
                .put("text", message.text.take(500))
                .put("sources", message.sources.take(8).toRemoteKnowledgeSources())
        )
    }
    return array
}

private fun List<RemoteKnowledgeSource>.toRemoteKnowledgeSources(): JSONArray {
    val array = JSONArray()
    forEach { source ->
        array.put(
            JSONObject()
                .put("chunk_id", source.chunkId)
                .put("task_id", source.taskId)
                .put("title", source.title)
                .put("text", source.text.take(240))
                .put("chunk_type", source.chunkType ?: JSONObject.NULL)
                .put("meeting_date", source.meetingDate ?: JSONObject.NULL)
                .put("speaker", source.speaker ?: JSONObject.NULL)
                .put("timestamp", source.timestamp ?: JSONObject.NULL)
                .put("start_ms", source.startMs ?: JSONObject.NULL)
                .put("end_ms", source.endMs ?: JSONObject.NULL)
                .put("score", source.score)
                .put("scope", source.scope)
        )
    }
    return array
}

private fun String.toUserFacingKnowledgeAnswer(question: String): String {
    val clean = trim().trimEnd('。')
    val normalized = when {
        clean in setOf("当前会议记录中没有找到明确依据", "未检索到相关内容", "未在当前范围找到依据") -> "没有找到相关会议内容。"
        clean.contains("当前范围内没有会议记录") -> clean.replace("当前范围内", "").withChinesePeriod()
        clean.contains("当前范围内没有会议内容") -> clean.replace("当前范围内", "").withChinesePeriod()
        clean.contains("当前范围") || clean.contains("项目/客户筛选") -> clean
            .replace("未在当前范围找到依据", "没有找到相关会议内容")
            .replace("。可以扩大时间范围或调整项目/客户筛选后重试", "")
            .withChinesePeriod()
        else -> this.withChinesePeriod()
    }
    return question.contextualizeKnowledgeFallback(normalized)
}

private fun String.defaultKnowledgeFallback(): String {
    return contextualizeKnowledgeFallback("没有找到相关会议内容。")
}

private fun String.contextualizeKnowledgeFallback(answer: String): String {
    val clean = answer.trim().trimEnd('。')
    val period = knowledgeQuestionPeriod()
    val genericNoContent = clean in setOf(
        "没有找到相关会议内容",
        "没有找到相关会议记录",
        "没有会议内容",
        "没有会议记录",
        "未检索到相关内容"
    ) || clean.endsWith("没有找到相关会议内容") || clean.endsWith("没有找到相关会议记录")
    return when {
        genericNoContent && asksSelfMeeting() -> "${period}没有找到你参加过的会议记录。"
        genericNoContent && asksMeetingLookup() -> "${period}没有找到相关会议记录。"
        genericNoContent && asksSelfTodo() -> "${period}没有找到分配给你的待办。"
        genericNoContent && asksTodo() -> "${period}没有找到相关待办。"
        genericNoContent && asksRisk() -> "${period}没有找到相关风险。"
        genericNoContent && asksDecision() -> "${period}没有找到相关决策。"
        else -> answer.withChinesePeriod()
    }
}

private fun String.withChinesePeriod(): String {
    val clean = trim()
    if (clean.isBlank()) return clean
    return if (clean.last() in setOf('。', '？', '?', '！', '!', '.', '；', ';')) clean else "$clean。"
}

private fun String.knowledgeQuestionPeriod(): String {
    val clean = replace("\\s+".toRegex(), "")
    return when {
        "昨天" in clean -> "昨天"
        "今天" in clean -> "今天"
        "本周" in clean -> "本周"
        "上周" in clean -> "上周"
        "本月" in clean -> "本月"
        "上月" in clean -> "上月"
        else -> ""
    }
}

private fun String.asksSelfMeeting(): Boolean {
    val clean = replace("\\s+".toRegex(), "")
    return clean.contains(Regex("我|本人|自己")) && (asksParticipant() || asksMeetingLookup())
}

private fun String.asksParticipant(): Boolean {
    val clean = replace("\\s+".toRegex(), "")
    return listOf("参会", "参加", "参与", "出席", "到会").any { it in clean }
}

private fun String.asksMeetingLookup(): Boolean {
    val clean = replace("\\s+".toRegex(), "")
    return (("会议" in clean || "开会" in clean || "会吗" in clean) &&
        listOf("有没有", "有开", "开会吗", "有会", "会议记录", "哪些会议", "有哪些会议", "几场", "多少场").any { it in clean }) ||
        asksParticipant()
}

private fun String.asksTodo(): Boolean {
    val clean = replace("\\s+".toRegex(), "")
    return listOf("待办", "任务", "要做", "负责", "跟进").any { it in clean }
}

private fun String.asksSelfTodo(): Boolean {
    val clean = replace("\\s+".toRegex(), "")
    return clean.contains(Regex("我|本人|自己")) && asksTodo()
}

private fun String.asksRisk(): Boolean {
    val clean = replace("\\s+".toRegex(), "")
    return listOf("风险", "问题", "阻塞", "延期").any { it in clean }
}

private fun String.asksDecision(): Boolean {
    val clean = replace("\\s+".toRegex(), "")
    return listOf("决策", "决定", "结论", "定了").any { it in clean }
}
