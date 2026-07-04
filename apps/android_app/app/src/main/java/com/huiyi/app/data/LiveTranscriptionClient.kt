package com.huiyi.app.data

import com.huiyi.app.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.net.URLEncoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

class LiveTranscriptionClient(
    private val wsUrl: String = BuildConfig.HUIXIAO_LIVE_WS_URL,
    private val accessToken: String = "",
    private val apiClient: HuixiaoApiClient = HuixiaoApiClient(),
    private val user: CloudUser? = null,
    private val onSegments: (List<TranscriptSegment>, Boolean, Boolean) -> Unit,
    private val onError: (String) -> Unit,
    private val onStatus: (String?) -> Unit = {},
    private val onReady: () -> Unit = {},
    private val onStartFailed: (String) -> Unit = { onError(it) },
    private val onStopRequested: (String) -> Unit = {},
    private val recognitionLanguage: RecognitionLanguage = RecognitionLanguage.Chinese
) {
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private val stateLock = Any()

    @Volatile private var webSocket: WebSocket? = null
    @Volatile private var opened = false
    @Volatile private var stopped = false
    @Volatile private var released = false
    @Volatile private var sessionId: String = ""
    @Volatile private var audioFile: File? = null
    @Volatile private var initialConnectionEstablished = false
    @Volatile private var latestFrameEndBytes: Long = 0L
    @Volatile private var sentThroughBytes: Long = 0L
    @Volatile private var lastFinalEndBytes: Long = 0L
    @Volatile private var connectionGeneration = 0
    @Volatile private var streamBaseBytes: Long = 0L
    @Volatile private var statusMessage: String? = null
    @Volatile private var directSession: LiveDirectSession? = null
    @Volatile private var useProxyFallback = false

    private var reconnectAttempts = 0
    private var reconnectFuture: ScheduledFuture<*>? = null
    private var closeFuture: ScheduledFuture<*>? = null
    private val pendingBackfillRanges = mutableListOf<ByteRange>()

    fun start(sessionId: String) {
        this.sessionId = sessionId
        stopped = false
        released = false
        initialConnectionEstablished = false
        latestFrameEndBytes = 0L
        sentThroughBytes = 0L
        lastFinalEndBytes = 0L
        streamBaseBytes = 0L
        directSession = null
        useProxyFallback = false
        audioFile = null
        synchronized(stateLock) {
            pendingBackfillRanges.clear()
        }
        connect(ResumePlan(startBytes = 0L), reconnect = false)
    }

    fun attachAudioFile(file: File) {
        audioFile = file
    }

    fun sendPcm(frame: ByteArray, endBytes: Long) {
        if (frame.isEmpty()) return
        latestFrameEndBytes = max(latestFrameEndBytes, endBytes)
        if (stopped || released) return
        val frameStartBytes = (endBytes - frame.size).coerceAtLeast(0L).alignPcmBytes()
        val socket = webSocket
        if (socket == null || !opened) {
            rememberMissingAudio(frameStartBytes, endBytes)
            return
        }
        val sent = if (useProxyFallback) {
            socket.send(frame.toByteString())
        } else {
            sendDirectAudioAppend(socket, frame, endBytes)
        }
        if (sent) {
            sentThroughBytes = max(sentThroughBytes, endBytes)
        } else {
            rememberMissingAudio(frameStartBytes, endBytes)
            handleDisconnected(connectionGeneration, "实时转写发送失败，录音继续本地保存中")
        }
    }

    fun stop() {
        finishAfterRecording(null) {}
    }

    fun finishAfterRecording(file: File?, onComplete: () -> Unit) {
        file?.let { audioFile = it }
        stopped = true
        reconnectFuture?.cancel(false)
        reconnectFuture = null
        val socket = webSocket
        if (socket != null) {
            if (useProxyFallback) {
                socket.send(JSONObject().put("type", "audio.end").toString())
            } else {
                runCatching {
                    socket.send(JSONObject().put("event_id", "audio_commit").put("type", "input_audio_buffer.commit").toString())
                    socket.send(JSONObject().put("event_id", "session_finish").put("type", "session.finish").toString())
                }
            }
        }
        closeFuture?.cancel(false)
        closeFuture = scheduler.schedule({
            rememberUnfinalizedTailAudio()
            runCatching { backfillMissingAudioBeforeFinish() }
            socket?.close(1000, "recording finished")
            if (socket == null || webSocket === socket) {
                if (webSocket === socket) webSocket = null
                opened = false
            }
            onComplete()
        }, FINISH_SETTLE_MS, TimeUnit.MILLISECONDS)
    }

    fun release() {
        released = true
        stopped = true
        reconnectFuture?.cancel(false)
        closeFuture?.cancel(false)
        webSocket?.cancel()
        webSocket = null
        opened = false
        scheduler.shutdownNow()
    }

    private fun connect(plan: ResumePlan, reconnect: Boolean) {
        if (released || stopped || sessionId.isBlank()) return
        val generation = synchronized(stateLock) {
            connectionGeneration += 1
            opened = false
            streamBaseBytes = plan.startBytes
            connectionGeneration
        }
        if (reconnect) {
            updateStatus("正在恢复实时转写连接")
        } else {
            updateStatus("正在连接实时转写")
        }
        scheduler.execute {
            if (released || stopped || !isActiveGeneration(generation)) return@execute
            val request = runCatching {
                if (useProxyFallback) buildProxyRequest() else buildDirectRequest()
            }.getOrElse { error ->
                if (!initialConnectionEstablished) {
                    failBeforeRecording(generation, error.userFacingMessage("服务器维护中，请稍后重试"))
                    return@execute
                }
                handleDisconnected(generation, "实时转写连接恢复中，录音已继续保存")
                return@execute
            }
            val socket = client.newWebSocket(request, listenerFor(generation, plan, reconnect))
            webSocket = socket
        }
    }

    private fun buildDirectRequest(): Request {
        val currentUser = user ?: error("请先登录后再开始实时记录")
        val session = directSession?.takeIf { it.expiresAt * 1000L - System.currentTimeMillis() > 60_000L }
            ?: apiClient.createLiveDirectSession(currentUser).also { directSession = it }
        val builder = Request.Builder()
            .url(session.websocketUrl)
            .header("Authorization", "Bearer ${session.apiKey}")
            .header("User-Agent", "huiyi-android/0.1")
        if (session.workspaceId.isNotBlank()) {
            builder.header("X-DashScope-WorkSpace", session.workspaceId)
        }
        return builder.build()
    }

    private fun buildProxyRequest(): Request {
        val encodedSessionId = URLEncoder.encode(sessionId, "UTF-8")
        val builder = Request.Builder()
            .url("$wsUrl?session_id=$encodedSessionId")
        if (accessToken.isNotBlank()) {
            builder.header("Authorization", "Bearer $accessToken")
        }
        return builder.build()
    }

    private fun listenerFor(generation: Int, plan: ResumePlan, reconnect: Boolean): WebSocketListener {
        return object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (!isActiveGeneration(generation)) {
                    webSocket.cancel()
                    return
                }
                if (!reconnect) reconnectAttempts = 0
                if (reconnect && initialConnectionEstablished) {
                    streamBaseBytes = latestFrameEndBytes.alignPcmBytes()
                } else {
                    streamBaseBytes = plan.startBytes
                }
                if (useProxyFallback) {
                    webSocket.send(
                        JSONObject()
                            .put("type", "audio.start")
                            .put("format", "pcm_s16le")
                            .put("sample_rate", SAMPLE_RATE)
                            .put("channels", 1)
                            .put("speaker_separation", false)
                            .toString()
                    )
                } else {
                    webSocket.send(directSessionUpdatePayload())
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (isActiveGeneration(generation)) {
                    handleMessage(text, generation, streamBaseBytes, reconnect)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (isActiveGeneration(generation)) {
                    if (!initialConnectionEstablished) {
                        failBeforeRecording(generation, startupConnectionFailureMessage(t, response))
                    } else {
                        handleDisconnected(generation, "网络异常，正在恢复实时转写")
                    }
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!initialConnectionEstablished && !stopped && !released && isActiveGeneration(generation)) {
                    failBeforeRecording(generation, "实时转写服务暂时不可用，请稍后重试")
                    return
                }
                if (!stopped && !released && isActiveGeneration(generation)) {
                    handleDisconnected(generation, "实时转写连接已断开，正在恢复")
                }
            }
        }
    }

    private fun handleDisconnected(generation: Int, message: String) {
        if (!isActiveGeneration(generation) || stopped || released) return
        opened = false
        updateStatus(message)
        scheduleReconnect()
    }

    private fun failBeforeRecording(generation: Int, message: String) {
        if (!isActiveGeneration(generation) || released) return
        stopped = true
        opened = false
        reconnectFuture?.cancel(false)
        reconnectFuture = null
        closeFuture?.cancel(false)
        closeFuture = null
        webSocket?.cancel()
        webSocket = null
        updateStatus(null)
        onStartFailed(message.userFacingDisplayText("实时转写服务暂时不可用，请稍后重试"))
    }

    private fun scheduleReconnect() {
        if (stopped || released) return
        reconnectFuture?.cancel(false)
        reconnectAttempts += 1
        val delaySeconds = min(30L, 1L shl min(reconnectAttempts - 1, 5))
        reconnectFuture = scheduler.schedule({
            if (!stopped && !released) {
                connect(buildResumePlan(), reconnect = true)
            }
        }, delaySeconds, TimeUnit.SECONDS)
    }

    private fun buildResumePlan(): ResumePlan {
        val start = if (initialConnectionEstablished) latestFrameEndBytes.alignPcmBytes() else 0L
        return ResumePlan(startBytes = start)
    }

    private fun rememberMissingAudio(startBytes: Long, endBytes: Long) {
        if (endBytes <= startBytes) return
        synchronized(stateLock) {
            val range = ByteRange(startBytes.alignPcmBytes(), endBytes.alignPcmBytes())
            val last = pendingBackfillRanges.lastOrNull()
            if (last != null && range.startBytes <= last.endBytes + LIVE_FRAME_BYTES) {
                pendingBackfillRanges[pendingBackfillRanges.lastIndex] = last.copy(endBytes = max(last.endBytes, range.endBytes))
            } else {
                pendingBackfillRanges += range
            }
        }
    }

    private fun rememberUnfinalizedTailAudio() {
        val tailEnd = latestFrameEndBytes
        val tailStart = lastFinalEndBytes
        if (tailEnd - tailStart >= MIN_BACKFILL_BYTES) {
            rememberMissingAudio(tailStart, tailEnd)
        }
    }

    private fun backfillMissingAudioBeforeFinish() {
        val file = audioFile?.takeIf { it.exists() } ?: return
        if (user == null || released) return
        val ranges = synchronized(stateLock) {
            pendingBackfillRanges.toList().also { pendingBackfillRanges.clear() }
        }
            .mapNotNull { range ->
                val start = (range.startBytes - BACKFILL_OVERLAP_BYTES).coerceAtLeast(0L).alignPcmBytes()
                val end = (range.endBytes + BACKFILL_OVERLAP_BYTES).coerceAtMost(latestFrameEndBytes).alignPcmBytes()
                ByteRange(start, end).takeIf { it.endBytes - it.startBytes >= MIN_BACKFILL_BYTES }
            }
            .mergeOverlappingRanges()
        ranges.forEach { range ->
            runCatching { backfillRangeWithDirectAsr(file, range) }
        }
    }

    private fun backfillRangeWithDirectAsr(file: File, range: ByteRange) {
        val request = runCatching { buildDirectRequest() }.getOrNull() ?: return
        val done = CountDownLatch(1)
        val itemState = LiveItemState()
        val socketRef = arrayOfNulls<WebSocket>(1)
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                socketRef[0] = webSocket
                webSocket.send(directSessionUpdatePayload())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val json = runCatching { JSONObject(text) }.getOrNull() ?: return
                when (json.optString("type")) {
                    "session.updated" -> {
                        streamLocalRangeToDirectAsr(webSocket, file, range)
                        runCatching {
                            webSocket.send(JSONObject().put("event_id", "backfill_commit").put("type", "input_audio_buffer.commit").toString())
                            webSocket.send(JSONObject().put("event_id", "backfill_finish").put("type", "session.finish").toString())
                        }
                    }
                    "session.finished" -> done.countDown()
                    "error" -> done.countDown()
                    else -> {
                        directTranscriptEvent(json, range.startBytes, range.endBytes, itemState)?.let { event ->
                            if (event.isFinal && event.segments.isNotEmpty()) {
                                onSegments(event.segments, true, false)
                            }
                        }
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                done.countDown()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                done.countDown()
            }
        }
        val socket = client.newWebSocket(request, listener)
        socketRef[0] = socket
        done.await(backfillTimeoutMillis(range), TimeUnit.MILLISECONDS)
        socket.close(1000, "backfill finished")
    }

    private fun backfillTimeoutMillis(range: ByteRange): Long {
        val durationMs = bytesToMs(range.endBytes - range.startBytes)
        return max(MIN_BACKFILL_TIMEOUT_MS, durationMs * BACKFILL_TIMEOUT_MULTIPLIER)
    }

    private fun streamLocalRangeToDirectAsr(socket: WebSocket, file: File, range: ByteRange) {
        RandomAccessFile(file, "r").use { input ->
            val buffer = ByteArray(LIVE_FRAME_BYTES)
            var cursor = range.startBytes.alignPcmBytes()
            val end = range.endBytes.alignPcmBytes()
            while (cursor < end && !released) {
                val remaining = min(LIVE_FRAME_BYTES.toLong(), end - cursor).toInt()
                if (remaining <= 0) break
                input.seek(WAV_HEADER_BYTES + cursor)
                val read = input.read(buffer, 0, remaining)
                if (read <= 0) break
                val frame = if (read == buffer.size) buffer.copyOf() else buffer.copyOf(read)
                if (frame.hasNonZeroAudio()) {
                    if (!sendDirectAudioAppend(socket, frame, cursor + read)) break
                }
                cursor += read
            }
        }
    }

    private fun handleMessage(text: String, generation: Int, baseBytes: Long, reconnect: Boolean) {
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return
        if (!useProxyFallback && json.optString("type") == "session.updated") {
            opened = true
            initialConnectionEstablished = true
            updateStatus(null)
            onReady()
            return
        }
        if (!useProxyFallback && json.optString("type") == "session.created") {
            return
        }
        if (useProxyFallback && json.optString("type") == "session.started") {
            opened = true
            initialConnectionEstablished = true
            updateStatus(null)
            onReady()
            return
        }
        if (json.optString("type") == "quota.warning") {
            updateStatus(json.optString("message").userFacingDisplayText("转写时长即将不足"))
            return
        }
        if (json.optString("type") == "error" || json.has("error")) {
            handleErrorEvent(json)
            return
        }
        if (!useProxyFallback && json.optString("type") == "session.finished") {
            return
        }
        val normalized = if (useProxyFallback) {
            proxyTranscriptEvent(json, baseBytes, reconnect, generation)
        } else {
            directTranscriptEvent(json, baseBytes, latestFrameEndBytes, liveItems)
        } ?: return
        if (normalized.segments.isNotEmpty()) {
            onSegments(normalized.segments, normalized.isFinal, normalized.replaceAll)
        }
    }

    private fun proxyTranscriptEvent(json: JSONObject, baseBytes: Long, reconnect: Boolean, generation: Int): LiveTranscriptEvent? {
        val eventType = json.optString("type")
        val isFinal = eventType == "transcript.final"
        if (!isFinal && eventType != "transcript.partial") return null
        val baseMs = bytesToMs(baseBytes)
        val segments = json.optJSONArray("segments").orEmpty().mapObjects { item ->
            val startMs = item.optNullableLong("start_ms")?.plus(baseMs)
            val endMs = item.optNullableLong("end_ms")?.plus(baseMs)
            TranscriptSegment(
                speaker = item.optString("speaker", "发言"),
                text = item.optString("text"),
                timestamp = startMs?.toLiveTimestamp() ?: item.optString("timestamp", "00:00"),
                startMs = startMs,
                endMs = endMs,
                speakerId = item.cleanJsonText("speaker_id")
            )
        }.filter { it.text.isNotBlank() }
        rememberFinalEnd(isFinal, segments)
        return LiveTranscriptEvent(
            segments = segments,
            isFinal = isFinal,
            replaceAll = isFinal && json.optBoolean("replace_all", false) && !reconnect && generation == 1 && baseBytes == 0L
        )
    }

    private fun directTranscriptEvent(
        json: JSONObject,
        baseBytes: Long,
        fallbackEndBytes: Long,
        itemState: LiveItemState
    ): LiveTranscriptEvent? {
        val eventType = json.optString("type")
        val itemId = json.optString("item_id").ifBlank { null }
        when (eventType) {
            "input_audio_buffer.speech_started" -> {
                itemState.touch(itemId, json.optNullableLong("audio_start_ms") ?: bytesToMs(fallbackEndBytes - baseBytes))
                return null
            }
            "input_audio_buffer.speech_stopped" -> {
                itemState.touch(itemId)?.endMs = json.optNullableLong("audio_end_ms") ?: bytesToMs(fallbackEndBytes - baseBytes)
                return null
            }
            "conversation.item.input_audio_transcription.text",
            "conversation.item.input_audio_transcription.completed" -> Unit
            else -> return null
        }
        val item = itemState.touch(itemId) ?: return null
        val isFinal = eventType == "conversation.item.input_audio_transcription.completed"
        val text = if (isFinal) {
            json.optString("transcript").ifBlank { item.partialText }.trim()
        } else {
            partialTextFromEvent(json).trim()
        }
        if (text.isBlank()) return null
        val endMs = if (isFinal) item.endMs ?: bytesToMs(fallbackEndBytes - baseBytes) else item.endMs
        if (directSession?.filterFiller != false && shouldFilterLiveText(text, item.startMs, endMs, directSession?.fillerMaxDurationMs ?: 1200)) {
            if (isFinal) item.partialText = ""
            return null
        }
        if (!isFinal) item.partialText = text
        val absoluteStartMs = item.startMs + bytesToMs(baseBytes)
        val absoluteEndMs = endMs?.plus(bytesToMs(baseBytes))
        val segment = TranscriptSegment(
            speaker = "发言",
            text = text,
            timestamp = absoluteStartMs.toLiveTimestamp(),
            startMs = absoluteStartMs,
            endMs = absoluteEndMs,
            speakerId = "live"
        )
        rememberFinalEnd(isFinal, listOf(segment))
        return LiveTranscriptEvent(listOf(segment), isFinal = isFinal, replaceAll = false)
    }

    private fun handleErrorEvent(json: JSONObject) {
        if (stopped || released) return
        val code = json.optString("code").ifBlank {
            json.optionalObject("error")?.optString("code").orEmpty()
        }
        val rawMessage = json.optString("message").ifBlank {
            json.optionalObject("error")?.optString("message").orEmpty()
        }
        val message = rawMessage.ifBlank { "实时转写失败" }.userFacingDisplayText("实时转写服务暂时不可用")
        if (code == "quota_exhausted") {
            onError(message)
            stopped = true
            webSocket?.cancel()
            onStopRequested(message)
            return
        }
        if (code.isRecoverableLiveErrorCode() || message.isRecoverableLiveErrorMessage()) {
            if (!initialConnectionEstablished) {
                failBeforeRecording(connectionGeneration, message)
                return
            }
            updateStatus(if (initialConnectionEstablished) "实时转写连接恢复中，录音已继续保存" else "正在连接实时转写")
            if (!stopped && !released) {
                webSocket?.cancel()
                scheduleReconnect()
            }
            return
        }
        if (code.isTransientLiveErrorCode() || message.isTransientLiveErrorMessage()) {
            if (!initialConnectionEstablished) {
                failBeforeRecording(connectionGeneration, "实时转写服务暂时不可用，请稍后重试")
                return
            }
            updateStatus(if (initialConnectionEstablished) "实时转写服务异常，正在自动恢复" else "正在连接实时转写")
            if (!stopped && !released) {
                webSocket?.cancel()
                scheduleReconnect()
            }
            return
        }
        onError(message)
        if (!stopped && !released) {
            updateStatus("实时转写服务异常，正在重试")
            webSocket?.cancel()
            scheduleReconnect()
        }
    }

    private fun directSessionUpdatePayload(): String {
        val session = directSession
        return JSONObject()
            .put("event_id", "huiyi_session_update")
            .put("type", "session.update")
            .put(
                "session",
                JSONObject()
                    .put("input_audio_format", "pcm")
                    .put("sample_rate", session?.sampleRate ?: SAMPLE_RATE)
                    .put("input_audio_transcription", directTranscriptionPayload())
                    .put(
                        "turn_detection",
                        JSONObject()
                            .put("type", "server_vad")
                            .put("threshold", session?.vadThreshold ?: 0.35)
                            .put("silence_duration_ms", session?.vadSilenceDurationMs ?: 450)
                    )
            )
            .toString()
    }

    private fun directTranscriptionPayload(): JSONObject {
        return JSONObject().apply {
            when (recognitionLanguage) {
                RecognitionLanguage.Chinese -> put("language", "zh")
                RecognitionLanguage.English -> put("language", "en")
                RecognitionLanguage.Auto -> Unit
            }
        }
    }

    private fun sendDirectAudioAppend(socket: WebSocket, frame: ByteArray, endBytes: Long): Boolean {
        return socket.send(
            JSONObject()
                .put("event_id", "audio_append_$endBytes")
                .put("type", "input_audio_buffer.append")
                .put("audio", Base64.encodeToString(frame, Base64.NO_WRAP))
                .toString()
        )
    }

    private fun rememberFinalEnd(isFinal: Boolean, segments: List<TranscriptSegment>) {
        val maxEndMs = segments.mapNotNull { it.endMs }.maxOrNull()
        if (isFinal && maxEndMs != null) {
            lastFinalEndBytes = max(lastFinalEndBytes, msToBytes(maxEndMs))
        }
    }

    private fun isActiveGeneration(generation: Int): Boolean {
        return generation == connectionGeneration && !released
    }

    private fun updateStatus(message: String?) {
        if (statusMessage == message) return
        statusMessage = message
        onStatus(message)
    }

    private val liveItems = LiveItemState()

    private fun List<ByteRange>.mergeOverlappingRanges(): List<ByteRange> {
        if (isEmpty()) return emptyList()
        val sorted = sortedBy { it.startBytes }
        val merged = mutableListOf<ByteRange>()
        for (range in sorted) {
            val last = merged.lastOrNull()
            if (last != null && range.startBytes <= last.endBytes + LIVE_FRAME_BYTES) {
                merged[merged.lastIndex] = last.copy(endBytes = max(last.endBytes, range.endBytes))
            } else {
                merged += range
            }
        }
        return merged
    }

    private data class ResumePlan(val startBytes: Long)
    private data class LiveTranscriptEvent(val segments: List<TranscriptSegment>, val isFinal: Boolean, val replaceAll: Boolean)
    private data class LiveItem(var startMs: Long, var endMs: Long? = null, var partialText: String = "")
    private data class ByteRange(val startBytes: Long, val endBytes: Long)

    private class LiveItemState {
        private val items = mutableMapOf<String, LiveItem>()
        private var fallbackIndex = 0
        private var activeItemId: String? = null

        fun touch(itemId: String?, startMs: Long? = null): LiveItem? {
            val id = itemId ?: activeItemId ?: "item-${fallbackIndex++}"
            val item = items.getOrPut(id) { LiveItem(startMs ?: 0L) }
            if (startMs != null) item.startMs = startMs
            activeItemId = id
            return item
        }
    }

    private companion object {
        private const val SAMPLE_RATE = 16_000
        private const val BYTES_PER_MS = 32L
        private const val LIVE_FRAME_BYTES = 1_920
        private const val WAV_HEADER_BYTES = 44L
        private const val FINISH_SETTLE_MS = 1_500L
        private const val MIN_BACKFILL_TIMEOUT_MS = 60_000L
        private const val BACKFILL_TIMEOUT_MULTIPLIER = 3L
        private val BACKFILL_OVERLAP_BYTES = msToBytes(500)
        private val MIN_BACKFILL_BYTES = msToBytes(300)
    }
}

private fun JSONArray?.orEmpty(): JSONArray = this ?: JSONArray()

private fun <T> JSONArray.mapObjects(block: (JSONObject) -> T): List<T> {
    val items = mutableListOf<T>()
    for (index in 0 until length()) {
        optJSONObject(index)?.let { items += block(it) }
    }
    return items
}

private fun JSONObject.optNullableLong(name: String): Long? {
    if (!has(name) || isNull(name)) return null
    return optLong(name)
}

private fun JSONObject.cleanJsonText(name: String): String? {
    if (!has(name) || isNull(name)) return null
    val clean = optString(name).trim()
    return clean.takeUnless { it.isBlank() || it.equals("null", ignoreCase = true) }
}

private fun JSONObject.optionalObject(name: String): JSONObject? {
    return if (has(name) && !isNull(name)) getJSONObject(name) else null
}

private fun Long.alignPcmBytes(): Long = this - (this % 2L)

private fun bytesToMs(bytes: Long): Long = bytes.coerceAtLeast(0L) / 32L

private fun msToBytes(ms: Long): Long = (ms * 32L).alignPcmBytes()

private fun partialTextFromEvent(event: JSONObject): String {
    val committed = event.optString("text").trim()
    val stash = event.optString("stash").trim()
    return when {
        committed.isNotBlank() && stash.isNotBlank() -> "$committed$stash"
        committed.isNotBlank() -> committed
        else -> stash
    }
}

private fun shouldFilterLiveText(text: String, startMs: Long?, endMs: Long?, maxDurationMs: Int): Boolean {
    val normalized = text.replace(Regex("[\\s，。！？、,.!?…~～]+"), "")
    if (normalized.isBlank() || !Regex("^[嗯呃额啊唔]+$").matches(normalized)) return false
    val durationMs = if (startMs != null && endMs != null && endMs >= startMs) endMs - startMs else null
    return if (durationMs == null) normalized.length <= 3 else durationMs <= max(300, maxDurationMs).toLong()
}

private fun ByteArray.hasNonZeroAudio(): Boolean {
    var index = 0
    while (index < size) {
        if (this[index].toInt() != 0) return true
        index += 1
    }
    return false
}

private fun String.isRecoverableLiveErrorCode(): Boolean {
    return this in setOf("live_unavailable", "upstream_unavailable")
}

private fun String.isTransientLiveErrorCode(): Boolean {
    return isBlank() ||
        contains("unavailable", ignoreCase = true) ||
        contains("timeout", ignoreCase = true) ||
        contains("gateway", ignoreCase = true) ||
        contains("server", ignoreCase = true) ||
        contains("network", ignoreCase = true)
}

private fun String.isRecoverableLiveErrorMessage(): Boolean {
    return contains("实时转写服务暂时不可用") ||
        contains("连接已断开") ||
        contains("正在恢复") ||
        contains("暂时不可用")
}

private fun String.isTransientLiveErrorMessage(): Boolean {
    return contains("实时转写") ||
        contains("语音识别") ||
        contains("服务暂时") ||
        contains("稍后重试") ||
        contains("请求超时") ||
        contains("服务器维护") ||
        contains("网络连接")
}

private fun startupConnectionFailureMessage(error: Throwable, response: Response?): String {
    val code = response?.code
    if (code != null) {
        return if (code >= 500) {
            "实时转写服务暂时不可用，请稍后重试"
        } else {
            "实时转写连接失败，请稍后重试"
        }
    }
    val display = error.userFacingMessage("实时转写服务暂时不可用，请稍后重试")
    return when {
        display.contains("网络连接失败") -> display
        display.contains("请求超时") -> display
        else -> "实时转写服务暂时不可用，请稍后重试"
    }
}

private fun Long.toLiveTimestamp(): String {
    val totalSeconds = (this / 1000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "%02d:%02d".format(minutes, seconds)
}
