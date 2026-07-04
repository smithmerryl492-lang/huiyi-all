package com.huiyi.app.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.huiyi.app.calendar.hasScheduleConflict
import com.huiyi.app.calendar.parseScheduleTime
import com.huiyi.app.data.CloudSyncStatus
import com.huiyi.app.data.AlipayPaymentOrder
import com.huiyi.app.data.CloudAudioCache
import com.huiyi.app.data.CloudSyncOperation
import com.huiyi.app.data.CloudSyncOperationStore
import com.huiyi.app.data.CloudSyncOperationType
import com.huiyi.app.data.KnowledgeIndexScope
import com.huiyi.app.data.KnowledgeChatContextItem
import com.huiyi.app.data.KnowledgeQueryScope
import com.huiyi.app.data.LocalKnowledgeStore
import com.huiyi.app.data.LocalSearchHistoryStore
import com.huiyi.app.data.LocalTaskStore
import com.huiyi.app.data.LocalScheduleStore
import com.huiyi.app.data.Meeting
import com.huiyi.app.data.MeetingProcessingResult
import com.huiyi.app.data.MeetingProcessingResultStore
import com.huiyi.app.data.MeetingResultGenerator
import com.huiyi.app.data.MeetingTask
import com.huiyi.app.data.MeetingTaskSource
import com.huiyi.app.data.MeetingTaskStatus
import com.huiyi.app.data.MembershipProfile
import com.huiyi.app.data.MeetingRepository
import com.huiyi.app.data.MissingMeetingResultGenerator
import com.huiyi.app.data.PaymentOrder
import com.huiyi.app.data.RecognitionLanguage
import com.huiyi.app.data.HuixiaoApiClient
import com.huiyi.app.data.HuixiaoApiException
import com.huiyi.app.data.SharedPrefsMembershipProfileStore
import com.huiyi.app.data.loadResultForTask
import com.huiyi.app.data.meetingDisplayTitleOrDefault
import com.huiyi.app.data.normalizedTodoPriority
import com.huiyi.app.data.normalizedForTask
import com.huiyi.app.data.CloudUser
import com.huiyi.app.data.SharedPrefsCloudUserStore
import com.huiyi.app.data.RemoteKnowledgeSource
import com.huiyi.app.data.RemoteTaskDetail
import com.huiyi.app.data.ScheduledMeeting
import com.huiyi.app.data.ScheduledMeetingStatus
import com.huiyi.app.data.SpeakerIdentity
import com.huiyi.app.data.SpeakerProfile
import com.huiyi.app.data.SpeakerProfileStore
import com.huiyi.app.data.TodoItem
import com.huiyi.app.data.TodoStatus
import com.huiyi.app.data.TranscriptSegment
import com.huiyi.app.data.speakerIdentityIdForName
import com.huiyi.app.data.speakerIdentities
import com.huiyi.app.data.userFacingDisplayText
import com.huiyi.app.data.userFacingMessage
import com.huiyi.app.model.AppScreen
import com.huiyi.app.model.SheetType
import com.huiyi.app.recording.RecordingStatus
import com.huiyi.app.recording.RecordingServiceState
import com.huiyi.app.recording.RecordingUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

enum class KnowledgeChatRole {
    User,
    Assistant
}

enum class TodoFilter(val label: String) {
    Today("今日到期"),
    Overdue("逾期"),
    PendingConfirm("待确认")
}

private const val TodoLockedFieldTitle = "title"
private const val TodoLockedFieldDescription = "description"
private const val TodoLockedFieldAssignee = "assignee"
private const val TodoLockedFieldDue = "due"
private const val TodoLockedFieldPriority = "priority"
private const val TodoLockedFieldStatus = "status"
private const val TodoLockedFieldManual = "manual"
private const val ManualTodoSource = "手动补充"
private const val PROCESSING_TRANSIENT_RETRY_LIMIT = 2

private fun String.isTransientProcessingFailure(): Boolean {
    val clean = trim()
    if (clean.isBlank()) return true
    val lower = clean.lowercase(Locale.ROOT)
    if (
        clean.contains("额度") ||
        clean.contains("登录") ||
        clean.contains("冻结") ||
        clean.contains("文件不存在") ||
        clean.contains("文件暂不可用") ||
        clean.contains("本地文件") ||
        clean.contains("任务已终止")
    ) {
        return false
    }
    return clean.contains("语音识别暂时失败") ||
        clean.contains("智能处理暂时失败") ||
        clean.contains("服务器维护") ||
        clean.contains("请求超时") ||
        clean.contains("稍后重试") ||
        lower.contains("timeout") ||
        lower.contains("timed out") ||
        lower.contains("connection") ||
        lower.contains("unavailable") ||
        lower.contains("bad gateway") ||
        lower.contains("gateway timeout") ||
        lower.contains("internal server error")
}

private fun MeetingTask.withGeneratedDisplayTitleIfNeeded(): MeetingTask {
    val timestamp = createdAtMillis.takeIf { it > 0L } ?: System.currentTimeMillis()
    val generatedTitle = meetingDisplayTitleOrDefault(title, timestamp)
    return if (generatedTitle == title) this else copy(title = generatedTitle)
}

data class KnowledgeChatMessage(
    val id: String,
    val role: KnowledgeChatRole,
    val text: String,
    val sources: List<RemoteKnowledgeSource> = emptyList(),
    val failed: Boolean = false,
    val retryQuestion: String? = null
)

data class ProcessingLaunch(
    val taskId: String,
    val runId: Long,
    val retryRemote: Boolean = false
)

class HuixiaoAppState(
    val repository: MeetingRepository,
    private val taskStore: LocalTaskStore? = null,
    private val scheduleStore: LocalScheduleStore? = null,
    private val resultGenerator: MeetingResultGenerator = MissingMeetingResultGenerator,
    private val resultStore: MeetingProcessingResultStore? = null,
    private val localKnowledgeStore: LocalKnowledgeStore? = null,
    private val searchHistoryStore: LocalSearchHistoryStore? = null,
    private val apiClient: HuixiaoApiClient? = null,
    private val cloudAudioCache: CloudAudioCache? = null,
    private val cloudUserStore: SharedPrefsCloudUserStore? = null,
    private val cloudSyncOperationStore: CloudSyncOperationStore? = null,
    private val speakerProfileStore: SpeakerProfileStore? = null,
    private val membershipProfileStore: SharedPrefsMembershipProfileStore? = null,
    private val networkAvailable: () -> Boolean? = { null }
) {
    var screen by mutableStateOf(AppScreen.Home)
        private set
    private var detailReturnScreen by mutableStateOf(AppScreen.Home)
    private var generatingReturnScreen by mutableStateOf(AppScreen.Home)

    var sheet by mutableStateOf<SheetType?>(null)
        private set

    var toast by mutableStateOf("")
        private set

    var selectedMeetingId by mutableStateOf(repository.getRecentMeetings().firstOrNull()?.id.orEmpty())
        private set

    var recording by mutableStateOf(RecordingUiState())
        private set
    var pendingRecordingFinishTarget by mutableStateOf<AppScreen?>(null)
    var pendingVoiceprintEnrollmentName by mutableStateOf<String?>(null)
        private set

    val isVoiceprintEnrollmentRecording: Boolean
        get() = pendingVoiceprintEnrollmentName != null

    var liveTranscriptSegments by mutableStateOf<List<TranscriptSegment>>(emptyList())
        private set
    private var liveFinalTranscriptSegments: List<TranscriptSegment> = emptyList()
    private var liveCompleteTranscriptSegments: List<TranscriptSegment> = emptyList()
    private var livePartialTranscriptSegments: List<TranscriptSegment> = emptyList()
    private var liveTranscriptsByTaskId: Map<String, List<TranscriptSegment>> = emptyMap()

    var localTasks by mutableStateOf(
        taskStore?.loadTasks()?.filterNot { task ->
            task.status == MeetingTaskStatus.Failed || task.status == MeetingTaskStatus.Canceled
        }?.map { task ->
            val restoredTask = if (task.status == MeetingTaskStatus.Processing) {
                task.copy(status = MeetingTaskStatus.WaitingProcess)
            } else {
                task
            }
            restoredTask.withGeneratedDisplayTitleIfNeeded()
        } ?: emptyList()
    )
        private set

    var selectedTaskId by mutableStateOf<String?>(null)
        private set

    var selectedSourceSegmentIndex by mutableStateOf(0)
        private set

    var speakerEditSegmentIndex by mutableStateOf<Int?>(null)
        private set

    var scheduledMeetings by mutableStateOf(
        scheduleStore?.loadSchedules() ?: emptyList()
    )
        private set

    var profileName by mutableStateOf(cloudUserStore?.loadProfileName().orEmpty())
        private set

    var todoFilter by mutableStateOf<TodoFilter?>(null)
        private set

    var todoMineOnly by mutableStateOf(false)
        private set

    var selectedTodoId by mutableStateOf<String?>(null)
        private set

    var selectedTodoMeetingId by mutableStateOf<String?>(null)
        private set

    var pendingDeleteTaskId by mutableStateOf<String?>(null)
        private set

    var pendingDeleteMeetingId by mutableStateOf<String?>(null)
        private set
    private var pendingDeleteMeetingSnapshot by mutableStateOf<Meeting?>(null)

    var pendingDeleteScheduleId by mutableStateOf<String?>(null)
        private set

    var reminderScheduleId by mutableStateOf<String?>(null)
        private set

    val pendingDeleteTask: MeetingTask?
        get() = pendingDeleteTaskId?.let { id -> localTasks.firstOrNull { it.id == id } }

    val pendingDeleteMeeting: Meeting?
        get() = pendingDeleteMeetingSnapshot
            ?: pendingDeleteMeetingId?.let { id -> recentMeetings.firstOrNull { it.id == id } }
            ?: selectedMeetingOrNull

    val pendingDeleteSchedule: ScheduledMeeting?
        get() = pendingDeleteScheduleId?.let { id -> scheduledMeetings.firstOrNull { it.id == id } }

    val reminderSchedule: ScheduledMeeting?
        get() = reminderScheduleId?.let { id -> scheduledMeetings.firstOrNull { it.id == id } }

    var knowledgeQuestion by mutableStateOf("")
        private set

    var knowledgeMessages by mutableStateOf<List<KnowledgeChatMessage>>(emptyList())
        private set

    var knowledgeLoading by mutableStateOf(false)
        private set

    private var knowledgeRequestId = 0L
    private var activeKnowledgeQuestion: String? = null

    var regeneratingMinutesTaskIds by mutableStateOf<Set<String>>(emptySet())
        private set

    var transcriptEditedTaskIds by mutableStateOf<Set<String>>(emptySet())
        private set

    var recentSearchKeywords by mutableStateOf(searchHistoryStore?.loadKeywords() ?: emptyList())
        private set

    var cloudUser by mutableStateOf(cloudUserStore?.loadUser())
        private set

    var cloudSyncEnabled by mutableStateOf(cloudUserStore?.loadCloudSyncEnabled() ?: false)
        private set

    private var cloudSyncBusyCount by mutableStateOf(0)

    val cloudSyncInProgress: Boolean
        get() = cloudSyncBusyCount > 0

    var cloudSyncStatusText by mutableStateOf(if (cloudSyncEnabled) "云端同步已开启" else "云端同步未开启")
        private set

    var profileCloudSyncFocusRequest by mutableStateOf(0)
        private set

    var speakerProfiles by mutableStateOf(speakerProfileStore?.loadProfiles(cloudUser?.userId) ?: emptyList())
        private set

    var membershipProfile by mutableStateOf(
        membershipProfileStore?.load(cloudUser?.userId)
            ?: MembershipProfile(loading = cloudUser != null)
    )
        private set
    private var membershipRefreshInProgress = false
    private var membershipLastLoadedAtMillis = 0L
    private var membershipLastFailedAtMillis = 0L

    var paymentOrders by mutableStateOf<List<PaymentOrder>>(emptyList())
        private set

    var paymentOrdersLoading by mutableStateOf(false)
        private set

    private var paymentOrdersLastLoadedAtMillis = 0L

    var deletedMeetingIds by mutableStateOf<Set<String>>(emptySet())
        private set

    private var cloudSyncOperations by mutableStateOf(cloudSyncOperationStore?.loadOperations(cloudUser?.userId) ?: emptyList())

    private val deviceId: String = cloudUserStore?.loadDeviceId() ?: "android-local"

    private var cancelledProcessingTaskIds by mutableStateOf<Set<String>>(emptySet())
    private var processingRunIds by mutableStateOf<Map<String, Long>>(emptyMap())
    private val processingJobs = mutableMapOf<String, Job>()

    var editingScheduleId by mutableStateOf<String?>(null)
        private set

    private var recordingTitle by mutableStateOf<String?>(null)
    private var recordingScheduleId by mutableStateOf<String?>(null)
    private var recordingScheduleNote by mutableStateOf<String?>(null)
    private var recordingRecognitionLanguage by mutableStateOf(RecognitionLanguage.Chinese)
    private var dismissedScheduleReminderIds by mutableStateOf<Set<String>>(emptySet())
    private var snoozedScheduleReminderUntil by mutableStateOf<Map<String, Long>>(emptyMap())
    private var lastSelectedMeetingSnapshot by mutableStateOf<Meeting?>(null)

    init {
        apiClient?.setAccessToken(cloudUser?.accessToken)
    }

    private fun currentCloudUserOrNull(): CloudUser? {
        val current = cloudUser?.takeIf { it.tokenValid } ?: cloudUserStore?.loadUser()
        if (current != null && current != cloudUser) {
            cloudUser = current
            apiClient?.setAccessToken(current.accessToken)
            cloudSyncOperations = cloudSyncOperationStore?.loadOperations(current.userId) ?: emptyList()
            profileName = cloudUserStore?.loadProfileName(current.userId).orEmpty().ifBlank { current.displayName }
            speakerProfiles = speakerProfileStore?.loadProfiles(current.userId) ?: emptyList()
            membershipProfile = membershipProfileStore?.load(current.userId) ?: MembershipProfile(loading = true)
            membershipLastLoadedAtMillis = 0L
            membershipLastFailedAtMillis = 0L
            paymentOrders = emptyList()
            paymentOrdersLastLoadedAtMillis = 0L
        }
        return current
    }

    private fun currentUserId(): String {
        return currentCloudUserOrNull()?.userId ?: error("请先登录账号")
    }

    private fun replaceSpeakerProfiles(profiles: List<SpeakerProfile>, userId: String? = cloudUser?.userId) {
        speakerProfiles = profiles
        userId?.let { speakerProfileStore?.saveProfiles(it, profiles) }
    }

    private fun clearCachedSpeakerProfiles(userId: String?) {
        speakerProfiles = emptyList()
        speakerProfileStore?.clearProfiles(userId)
    }

    private fun clearExpiredCloudSession(message: String = "登录已失效，请重新登录") {
        val displayMessage = message.userFacingStateText("登录已过期，请重新登录")
        cloudUserStore?.clear()
        cloudUserStore?.saveCloudSyncEnabled(false)
        apiClient?.setAccessToken(null)
        cloudUser = null
        cloudSyncEnabled = false
        cloudSyncOperations = emptyList()
        cloudSyncStatusText = displayMessage
        profileName = ""
        speakerProfiles = emptyList()
        membershipProfile = MembershipProfile()
        membershipLastLoadedAtMillis = 0L
        membershipLastFailedAtMillis = 0L
        paymentOrders = emptyList()
        paymentOrdersLoading = false
        paymentOrdersLastLoadedAtMillis = 0L
    }

    private fun handleAuthFailure(error: Throwable): Boolean {
        val message = error.message.orEmpty()
        val authFailure = (error as? HuixiaoApiException)?.statusCode == 401 ||
            listOf("请先登录", "登录已失效", "登录已过期", "登录凭证").any { it in message }
        if (!authFailure) return false
        val displayMessage = error.userFacingStateMessage("登录已过期，请重新登录")
        clearExpiredCloudSession(displayMessage)
        showToast(displayMessage)
        return true
    }

    private fun handleQuotaExhausted(error: Throwable? = null): Boolean {
        val quotaExhausted = (error as? HuixiaoApiException)?.statusCode == 402 ||
            listOf("额度已耗尽", "转写时长不足", "知识库问答次数不足").any { it in error?.message.orEmpty() }
        if (!quotaExhausted) return false
        closeSheet()
        screen = AppScreen.Membership
        showToast("额度已耗尽，请充值后继续享受权益")
        return true
    }

    val selectedTask: MeetingTask?
        get() = selectedTaskId?.let { id -> localTasks.firstOrNull { it.id == id } }

    val activeProcessingTask: MeetingTask?
        get() = localTasks.firstOrNull { it.status == MeetingTaskStatus.Processing }

    val queuedImportTasks: List<MeetingTask>
        get() = localTasks
            .filter { it.status == MeetingTaskStatus.WaitingProcess && it.progressStage != "waiting_retry" }
            .sortedWith(
                compareBy(
                    { task -> if (task.source == MeetingTaskSource.Recording) 0 else 1 },
                    { task -> task.createdAtMillis }
                )
            )

    val recentMeetings: List<Meeting>
        get() = (localTasks
            .filter { it.status == MeetingTaskStatus.Finished }
            .sortedByDescending { it.createdAtMillis }
            .mapNotNull { task -> runCatching { resultGenerator.buildMeeting(task) }.getOrNull() } + repository.getRecentMeetings())
            .filterNot { it.id in deletedMeetingIds }

    val allTodos: List<TodoItem>
        get() = recentMeetings.flatMap { meeting -> meeting.todos.map { it.withMeetingContext(meeting) } }

    val selectedTodo: TodoItem?
        get() {
            val todoId = selectedTodoId ?: return null
            val meetingId = selectedTodoMeetingId
            return allTodos.firstOrNull { it.id == todoId && (meetingId == null || it.meetingId == meetingId) }
        }

    val todoTodayCount: Int
        get() = allTodos.count { it.effectiveStatus.active && it.isDueToday() }

    val todoOverdueCount: Int
        get() = allTodos.count { it.isOverdue() }

    val selectedMeetingOrNull: Meeting?
        get() = localTasks
            .firstOrNull { it.id == selectedMeetingId && it.status == MeetingTaskStatus.Finished }
            ?.let { resultGenerator.buildMeeting(it) }
            ?: repository.getRecentMeetings().firstOrNull { it.id == selectedMeetingId }
            ?: recentMeetings.firstOrNull()

    val selectedMeeting: Meeting
        get() {
            val meeting = selectedMeetingOrNull ?: lastSelectedMeetingSnapshot ?: repository.getMeeting(selectedMeetingId)
            return meeting
        }

    val selectedMeetingSpeakers: List<SpeakerIdentity>
        get() = selectedMeeting.speakerIdentities

    val selectedSegmentSpeaker: SpeakerIdentity?
        get() {
            val index = speakerEditSegmentIndex ?: return null
            val segment = selectedMeeting.transcripts.getOrNull(index) ?: return null
            return selectedMeetingSpeakers.firstOrNull { it.id == segment.stableSpeakerId }
                ?: SpeakerIdentity(segment.stableSpeakerId, segment.speaker)
        }

    val editingSchedule: ScheduledMeeting?
        get() = editingScheduleId?.let { id -> scheduledMeetings.firstOrNull { it.id == id } }

    fun go(target: AppScreen) {
        if (target == AppScreen.Generating && screen != AppScreen.Generating) {
            generatingReturnScreen = screen.returnTargetForTransientScreen()
        }
        screen = target
    }

    fun openProfileCloudSync() {
        profileCloudSyncFocusRequest += 1
        go(AppScreen.Profile)
    }

    fun consumeProfileCloudSyncFocusRequest() {
        profileCloudSyncFocusRequest = 0
    }

    fun backHome() {
        screen = AppScreen.Home
    }

    fun backFromDetail() {
        screen = detailReturnScreen.returnTargetForTransientScreen()
    }

    fun openMeeting(id: String) {
        val task = finishedTaskForMeetingId(id)
        if (task != null && resultStore?.loadResultForTask(task) == null) {
            showToast("会议结果正在保存，请稍后重试")
            return
        }
        if (screen != AppScreen.Detail) {
            detailReturnScreen = screen.returnTargetForTransientScreen()
        }
        selectedMeetingId = task?.id ?: id
        selectedTaskId = task?.id
        lastSelectedMeetingSnapshot = null
        screen = AppScreen.Detail
    }

    fun openKnowledgeSource(source: RemoteKnowledgeSource) {
        val meeting = recentMeetings.firstOrNull { it.remoteTaskId == source.taskId || it.id == source.taskId } ?: run {
            showToast("来源会议不在当前范围")
            return
        }
        selectedMeetingId = meeting.id
        selectedTaskId = finishedTaskForMeetingId(meeting.id)?.id
        selectedSourceSegmentIndex = meeting.sourceIndexFor(source)
        if (screen != AppScreen.Detail) {
            detailReturnScreen = screen.returnTargetForTransientScreen()
        }
        screen = AppScreen.Detail
        sheet = SheetType.Source
    }

    fun showSheet(target: SheetType) {
        sheet = target
    }

    fun showSpeakerSheet(segmentIndex: Int? = null) {
        speakerEditSegmentIndex = segmentIndex
        sheet = SheetType.Speakers
    }

    fun showSourceSheet(segmentIndex: Int = 0) {
        selectedSourceSegmentIndex = segmentIndex.coerceAtLeast(0)
        sheet = SheetType.Source
    }

    fun showTodoDetail(todo: TodoItem) {
        selectedTodoId = todo.id
        selectedTodoMeetingId = todo.meetingIdLabel
        sheet = SheetType.TodoDetail
    }

    fun showCreateTodoForSelectedMeeting() {
        val task = selectedLocalFinishedTask()
        if (task == null) {
            showToast("当前会议无法补充待办")
            return
        }
        selectedTodoId = null
        selectedTodoMeetingId = task.id
        sheet = SheetType.CreateTodo
    }

    fun openTodoSource(todo: TodoItem) {
        val contextualTodo = allTodos.firstOrNull { item ->
            item.id == todo.id && (todo.meetingIdLabel == null || item.meetingIdLabel == todo.meetingIdLabel)
        } ?: allTodos.firstOrNull { it.id == todo.id } ?: todo
        val meetingId = contextualTodo.meetingIdLabel
        val meeting = recentMeetings.firstOrNull { it.id == meetingId || it.remoteTaskId == meetingId } ?: run {
            showToast("来源会议不在当前设备")
            return
        }
        selectedMeetingId = meeting.id
        selectedTaskId = finishedTaskForMeetingId(meeting.id)?.id
        val sourceIndex = meeting.todoSourceIndexOrNull(contextualTodo)
        selectedSourceSegmentIndex = sourceIndex ?: 0
        if (sourceIndex == null) {
            showToast("来源片段待核验")
        }
        if (screen != AppScreen.Detail) {
            detailReturnScreen = screen.returnTargetForTransientScreen()
        }
        screen = AppScreen.Detail
        if (contextualTodo.sourceLabel == ManualTodoSource || TodoLockedFieldManual in contextualTodo.lockedFields) {
            sheet = null
            showToast("已打开来源会议")
            return
        }
        sheet = SheetType.Source
    }

    fun closeSheet() {
        if (sheet == SheetType.DeleteMeeting) {
            pendingDeleteMeetingId = null
            pendingDeleteMeetingSnapshot = null
        }
        sheet = null
        speakerEditSegmentIndex = null
    }

    fun showToast(message: String) {
        toast = message.userFacingDisplayText(networkAvailable = currentNetworkAvailable())
    }

    private fun currentNetworkAvailable(): Boolean? {
        return runCatching { networkAvailable() }.getOrNull()
    }

    private fun Throwable.userFacingStateMessage(fallback: String): String {
        return userFacingMessage(fallback, currentNetworkAvailable())
    }

    private fun String.userFacingStateText(fallback: String): String {
        return userFacingDisplayText(fallback, currentNetworkAvailable())
    }

    suspend fun sendLoginSmsCode(phone: String) {
        val client = apiClient ?: run {
            showToast("云同步服务未配置")
            return
        }
        val cleanPhone = phone.trim()
        if (cleanPhone.isBlank()) {
            showToast("请输入手机号")
            return
        }
        withContext(Dispatchers.IO) { client.sendLoginSmsCode(cleanPhone) }
        showToast("验证码已发送")
    }

    suspend fun sendRegisterSmsCode(phone: String) {
        val client = apiClient ?: run {
            showToast("云同步服务未配置")
            return
        }
        val cleanPhone = phone.trim()
        if (cleanPhone.isBlank()) {
            showToast("请输入手机号")
            return
        }
        withContext(Dispatchers.IO) { client.sendRegisterSmsCode(cleanPhone) }
        showToast("验证码已发送")
    }

    suspend fun sendPasswordResetSmsCode(phone: String) {
        val client = apiClient ?: run {
            showToast("云同步服务未配置")
            return
        }
        val cleanPhone = phone.trim()
        if (cleanPhone.isBlank()) {
            showToast("请输入手机号")
            return
        }
        withContext(Dispatchers.IO) { client.sendPasswordResetSmsCode(cleanPhone) }
        showToast("验证码已发送")
    }

    suspend fun sendPhoneChangeSmsCode(phone: String) {
        val client = apiClient ?: run {
            showToast("云同步服务未配置")
            return
        }
        val cleanPhone = phone.trim()
        if (cleanPhone.isBlank()) {
            showToast("请输入手机号")
            return
        }
        withContext(Dispatchers.IO) { client.sendPhoneChangeSmsCode(cleanPhone) }
        showToast("验证码已发送")
    }

    suspend fun loginCloud(phone: String, code: String) {
        val client = apiClient ?: run {
            showToast("云同步服务未配置")
            return
        }
        val cleanPhone = phone.trim()
        val cleanCode = code.trim()
        if (cleanPhone.isBlank() || cleanCode.isBlank()) {
            showToast("请输入手机号和验证码")
            return
        }
        val user = withContext(Dispatchers.IO) { client.loginWithSmsCode(cleanPhone, cleanCode) }
        applyCloudLogin(client, user)
    }

    suspend fun registerCloudWithPassword(phone: String, code: String, password: String) {
        val client = apiClient ?: run {
            showToast("云同步服务未配置")
            return
        }
        val cleanPhone = phone.trim()
        val cleanCode = code.trim()
        val cleanPassword = password.trim()
        if (cleanPhone.isBlank() || cleanCode.isBlank() || cleanPassword.isBlank()) {
            showToast("请输入手机号、验证码和密码")
            return
        }
        val user = withContext(Dispatchers.IO) { client.registerWithPassword(cleanPhone, cleanCode, cleanPassword) }
        applyCloudLogin(client, user)
    }

    suspend fun loginCloudWithPassword(phone: String, password: String) {
        val client = apiClient ?: run {
            showToast("云同步服务未配置")
            return
        }
        val cleanPhone = phone.trim()
        val cleanPassword = password.trim()
        if (cleanPhone.isBlank() || cleanPassword.isBlank()) {
            showToast("请输入手机号和密码")
            return
        }
        val user = withContext(Dispatchers.IO) { client.loginWithPassword(cleanPhone, cleanPassword) }
        applyCloudLogin(client, user)
    }

    suspend fun resetCloudPassword(phone: String, code: String, password: String) {
        val client = apiClient ?: run {
            showToast("云同步服务未配置")
            return
        }
        val cleanPhone = phone.trim()
        val cleanCode = code.trim()
        val cleanPassword = password.trim()
        if (cleanPhone.isBlank() || cleanCode.isBlank() || cleanPassword.isBlank()) {
            showToast("请输入手机号、验证码和新密码")
            return
        }
        val user = withContext(Dispatchers.IO) { client.resetPassword(cleanPhone, cleanCode, cleanPassword) }
        applyCloudLogin(client, user)
    }

    suspend fun verifyCurrentPhoneForChange(oldPhone: String, oldCode: String): String {
        val client = apiClient ?: run {
            showToast("云同步服务未配置")
            return ""
        }
        val user = currentCloudUserOrNull()
        if (user == null) {
            showToast("请先登录账号")
            return ""
        }
        val cleanOldPhone = oldPhone.trim()
        val cleanOldCode = oldCode.trim()
        if (cleanOldPhone.isBlank() || cleanOldCode.isBlank()) {
            showToast("请输入当前手机号验证码")
            return ""
        }
        val token = withContext(Dispatchers.IO) { client.verifyCurrentPhoneForChange(user, cleanOldPhone, cleanOldCode) }
        showToast("当前手机号已验证")
        return token
    }

    suspend fun changeCloudPhone(oldPhone: String, oldVerificationToken: String, newPhone: String, newCode: String) {
        val client = apiClient ?: run {
            showToast("云同步服务未配置")
            return
        }
        val user = currentCloudUserOrNull()
        if (user == null) {
            showToast("请先登录账号")
            return
        }
        val cleanOldPhone = oldPhone.trim()
        val cleanOldVerificationToken = oldVerificationToken.trim()
        val cleanNewPhone = newPhone.trim()
        val cleanNewCode = newCode.trim()
        if (cleanOldPhone.isBlank() || cleanOldVerificationToken.isBlank() || cleanNewPhone.isBlank() || cleanNewCode.isBlank()) {
            showToast("请输入旧手机号、新手机号和验证码")
            return
        }
        val updated = withContext(Dispatchers.IO) { client.changePhone(user, cleanOldPhone, cleanOldVerificationToken, cleanNewPhone, cleanNewCode) }
        client.setAccessToken(updated.accessToken)
        cloudUserStore?.saveUser(updated)
        cloudUser = updated
        profileName = cloudUserStore?.loadProfileName(updated.userId).orEmpty().ifBlank { updated.displayName }
        showToast("手机号已修改")
    }

    suspend fun setCloudPassword(password: String) {
        val client = apiClient ?: run {
            showToast("云同步服务未配置")
            return
        }
        val user = currentCloudUserOrNull()
        if (user == null) {
            showToast("请先登录账号")
            return
        }
        val cleanPassword = password.trim()
        if (cleanPassword.isBlank()) {
            showToast("请输入密码")
            return
        }
        withContext(Dispatchers.IO) { client.setPassword(user, cleanPassword) }
        showToast("密码已设置")
    }

    suspend fun changeCloudPassword(oldPassword: String, newPassword: String) {
        val client = apiClient ?: run {
            showToast("云同步服务未配置")
            return
        }
        val user = currentCloudUserOrNull()
        if (user == null) {
            showToast("请先登录账号")
            return
        }
        val cleanOldPassword = oldPassword.trim()
        val cleanNewPassword = newPassword.trim()
        if (cleanOldPassword.isBlank() || cleanNewPassword.isBlank()) {
            showToast("请输入原密码和新密码")
            return
        }
        withContext(Dispatchers.IO) { client.changePassword(user, cleanOldPassword, cleanNewPassword) }
        showToast("密码已修改")
    }

    private suspend fun applyCloudLogin(client: HuixiaoApiClient, user: CloudUser) {
        client.setAccessToken(user.accessToken)
        cloudUserStore?.saveUser(user)
        cloudUser = user
        cloudSyncOperations = cloudSyncOperationStore?.loadOperations(user.userId) ?: emptyList()
        profileName = cloudUserStore?.loadProfileName(user.userId).orEmpty().ifBlank { user.displayName }
        membershipProfile = membershipProfileStore?.load(user.userId) ?: MembershipProfile(loading = true)
        membershipLastLoadedAtMillis = 0L
        membershipLastFailedAtMillis = 0L
        if (cloudUserStore?.loadProfileName(user.userId).isNullOrBlank()) {
            cloudUserStore?.saveProfileName(profileName, user.userId)
        }
        cloudSyncStatusText = if (cloudSyncEnabled) "云端同步已开启" else "云端同步未开启"
        runCatching {
            replaceSpeakerProfiles(
                withContext(Dispatchers.IO) { client.listSpeakerProfiles(user) },
                user.userId
            )
        }
        runCatching {
            refreshMembershipProfile()
        }
        showToast("已登录：${user.displayName}")
    }

    fun saveProfileName(name: String) {
        val clean = name.trim()
        if (clean.isBlank()) {
            showToast("姓名不能为空")
            return
        }
        profileName = clean
        cloudUserStore?.saveProfileName(clean, cloudUser?.userId)
        showToast("姓名已保存")
    }

    fun toggleTodoFilter(filter: TodoFilter) {
        todoFilter = if (todoFilter == filter) null else filter
    }

    fun toggleTodoMineOnly() {
        todoMineOnly = !todoMineOnly
    }

    suspend fun setCloudSyncEnabled(enabled: Boolean) {
        if (enabled && currentCloudUserOrNull() == null) {
            showToast("请先登录账号")
            return
        }
        cloudSyncEnabled = enabled
        cloudUserStore?.saveCloudSyncEnabled(enabled)
        if (enabled) {
            cloudSyncStatusText = "云端同步已开启"
            showToast("云端同步已开启，新会议会后台上传；历史会议可手动上传")
        } else {
            cloudSyncStatusText = "云端同步已关闭"
            showToast("云端同步已关闭，新的会议会先保存在本机")
        }
    }

    val unsyncedFinishedMeetingCount: Int
        get() = localTasks.count {
            it.status == MeetingTaskStatus.Finished &&
                it.syncStatus != CloudSyncStatus.Synced
        }

    val logoutLocalUnsyncedTaskCount: Int
        get() = localTasks.count { it.willBeDeletedBeforeCloudSafe() }

    val activeScheduledMeetings: List<ScheduledMeeting>
        get() = scheduledMeetings.filterNot { it.isFinished() }

    val pendingCloudDeleteCount: Int
        get() = cloudSyncOperations.count { it.type in setOf(CloudSyncOperationType.Delete, CloudSyncOperationType.DeleteSchedule) }

    val hasPendingCloudWork: Boolean
        get() = logoutLocalUnsyncedTaskCount > 0 || pendingCloudDeleteCount > 0

    fun recordRecentSearch(keyword: String) {
        val clean = keyword.trim()
        if (clean.isBlank()) return
        recentSearchKeywords = (listOf(clean) + recentSearchKeywords.filterNot { it.equals(clean, ignoreCase = true) })
            .take(10)
        searchHistoryStore?.saveKeywords(recentSearchKeywords)
    }

    suspend fun uploadAllUnsyncedMeetings() {
        if (currentCloudUserOrNull() == null) {
            showToast("请先登录账号")
            return
        }
        val pendingCount = unsyncedFinishedMeetingCount
        if (pendingCount == 0) {
            showToast("没有未上传会议纪要")
            return
        }
        localTasks = localTasks.map { task ->
            if (
                task.status == MeetingTaskStatus.Finished &&
                task.syncStatus != CloudSyncStatus.Synced
            ) {
                task.copy(syncStatus = CloudSyncStatus.PendingUpload)
            } else {
                task
            }
        }
        persistTasks()
        enqueueSyncForPendingLocalTasks()
        syncPendingLocalResultsToCloud(forceUpload = true)
        showToast(if (cloudSyncOperations.any { !it.lastError.isNullOrBlank() }) "部分会议同步失败，请稍后重试" else "未上传会议纪要已同步")
    }

    suspend fun syncPendingLocalResultsToCloud(forceUpload: Boolean = false) {
        val client = apiClient ?: return
        val userId = currentCloudUserOrNull()?.userId ?: return
        val store = resultStore
        beginCloudSync(if (forceUpload) "正在上传本机会议" else "正在同步云端")
        try {
            enqueueSyncForPendingLocalTasks()
            val operations = cloudSyncOperations.sortedBy { it.createdAtMillis }
            if (operations.isEmpty()) {
                finishCloudSync("没有待同步内容")
                return
            }
            for (operation in operations) {
                if (currentCloudUserOrNull() == null) break
                when (operation.type) {
                    CloudSyncOperationType.Upload -> if ((cloudSyncEnabled || forceUpload) && store != null) syncUploadOperation(operation, client, userId, store)
                    CloudSyncOperationType.UpdateResult -> if ((cloudSyncEnabled || forceUpload) && store != null) syncUpdateOperation(operation, client, userId, store)
                    CloudSyncOperationType.Delete -> syncDeleteOperation(operation, client, userId)
                    CloudSyncOperationType.UpsertSchedule -> if (cloudSyncEnabled) syncUpsertScheduleOperation(operation, client)
                    CloudSyncOperationType.DeleteSchedule -> syncDeleteScheduleOperation(operation, client, userId)
                }
            }
            val failedCount = cloudSyncOperations.count { !it.lastError.isNullOrBlank() }
            finishCloudSync(
                when {
                    failedCount > 0 -> "部分同步失败，${cloudSyncOperations.size} 项待重试"
                    cloudSyncOperations.isNotEmpty() -> "${cloudSyncOperations.size} 项等待同步"
                    else -> "云端同步完成"
                }
            )
        } catch (error: Throwable) {
            if (!handleAuthFailure(error)) {
                failCloudSync(error)
            }
            throw error
        } finally {
            endCloudSync()
        }
    }

    suspend fun pullCloudData() {
        val user = currentCloudUserOrNull() ?: run {
            showToast("请先登录")
            return
        }
        val client = apiClient ?: run {
            showToast("云同步服务未配置")
            return
        }
        beginCloudSync("正在拉取云端数据")
        try {
            val bootstrap = withContext(Dispatchers.IO) { client.getCloudBootstrap(user) }
            replaceSpeakerProfiles(
                withContext(Dispatchers.IO) { client.listSpeakerProfiles(user) },
                user.userId
            )
            membershipProfile = withContext(Dispatchers.IO) { client.getMembershipProfile(user) }
            membershipProfileStore?.save(user.userId, membershipProfile)
            membershipLastLoadedAtMillis = System.currentTimeMillis()
            val cloudTasks = bootstrap.tasks.map { it.task }
            val pendingDeletedRemoteIds = cloudSyncOperations
                .filter { it.type == CloudSyncOperationType.Delete }
                .mapNotNull { it.remoteTaskId }
                .toSet()
            val activeCloudTasks = cloudTasks.filterNot { (it.remoteTaskId ?: it.id) in pendingDeletedRemoteIds }
            val cloudTaskIds = activeCloudTasks.flatMap { listOfNotNull(it.id, it.remoteTaskId) }.toSet()
            val pendingDeletedScheduleIds = cloudSyncOperations
                .filter { it.type == CloudSyncOperationType.DeleteSchedule }
                .map { it.localTaskId }
                .toSet()
            val pendingUpsertScheduleIds = cloudSyncOperations
                .filter { it.type == CloudSyncOperationType.UpsertSchedule }
                .map { it.localTaskId }
                .toSet()
            val localByCloudKey = localTasks
                .flatMap { task -> listOfNotNull(task.id, task.remoteTaskId).map { key -> key to task } }
                .toMap()
            var preservedLocalChangeCount = 0
            localTasks
                .filter { it.syncStatus == CloudSyncStatus.Synced && it.id !in cloudTaskIds && (it.remoteTaskId ?: it.id) !in cloudTaskIds }
                .forEach { stale ->
                    resultStore?.deleteResult(stale.id)
                    stale.remoteTaskId?.let { resultStore?.deleteResult(it) }
                    localKnowledgeStore?.deleteMeetingIndex(stale.id)
                    stale.remoteTaskId?.let { localKnowledgeStore?.deleteMeetingIndex(it) }
                }
            bootstrap.tasks
                .filterNot { (it.task.remoteTaskId ?: it.task.id) in pendingDeletedRemoteIds }
                .forEach { item ->
                    val result = item.result ?: return@forEach
                    val remoteKey = item.task.remoteTaskId ?: item.task.id
                    val existing = localByCloudKey[item.task.id] ?: localByCloudKey[remoteKey]
                    if (existing != null && existing.hasLocalPendingCloudChanges()) {
                        preservedLocalChangeCount += 1
                        return@forEach
                    }
                    val targetTask = existing?.mergedWithCloud(item.task)
                        ?: item.task.copy(syncStatus = CloudSyncStatus.Synced, knowledgeScope = KnowledgeIndexScope.Cloud)
                    val normalized = result.normalizedForTask(targetTask)
                    resultStore?.saveResult(normalized)
                    localKnowledgeStore?.replaceMeetingIndex(targetTask.copy(knowledgeScope = KnowledgeIndexScope.Cloud), normalized)
                }
            val previousSelectedMeetingId = selectedMeetingId
            localTasks = mergeCloudTasks(
                localTasks,
                activeCloudTasks,
                cloudTaskIds
            ).sortedByDescending { it.createdAtMillis }
            val cachedAudioCount = cacheFinishedCloudAudioForSyncedTasks(client, resultStore)
            scheduledMeetings = mergeSchedules(
                scheduledMeetings.filterNot { it.id in pendingDeletedScheduleIds },
                bootstrap.schedules.filterNot { it.id in pendingDeletedScheduleIds },
                pendingUpsertScheduleIds
            ).sortedBy { it.startAtMillis ?: Long.MAX_VALUE }
            selectedMeetingId = when {
                previousSelectedMeetingId.isNotBlank() && localTasks.any { it.id == previousSelectedMeetingId && it.status == MeetingTaskStatus.Finished } -> previousSelectedMeetingId
                else -> localTasks.firstOrNull { it.status == MeetingTaskStatus.Finished }?.id.orEmpty()
            }
            persistTasks()
            persistSchedules()
            val message = if (preservedLocalChangeCount > 0) {
                "云端拉取完成，已保留 ${preservedLocalChangeCount} 个本机未上传改动"
            } else if (cachedAudioCount > 0) {
                "云端数据和音频已同步"
            } else {
                "云端数据已同步"
            }
            finishCloudSync(message)
            showToast(message)
        } catch (error: Throwable) {
            if (!handleAuthFailure(error)) {
                failCloudSync(error)
            }
            throw error
        } finally {
            endCloudSync()
        }
    }

    private suspend fun cacheFinishedCloudAudioForSyncedTasks(
        client: HuixiaoApiClient,
        store: MeetingProcessingResultStore?
    ): Int {
        val cache = cloudAudioCache ?: return 0
        if (store == null) return 0
        val candidates = localTasks.filter { task ->
            task.syncStatus == CloudSyncStatus.Synced &&
                task.status == MeetingTaskStatus.Finished &&
                task.remoteTaskId != null &&
                !task.localFilePath.isReadableLocalAudioFile() &&
                store.loadResultForTask(task) != null
        }
        if (candidates.isEmpty()) return 0
        var cachedCount = 0
        candidates.forEachIndexed { index, task ->
            cloudSyncStatusText = "正在同步云端音频 ${index + 1}/${candidates.size}"
            runCatching {
                val result = store.loadResultForTask(task)
                val cachedPath = withContext(Dispatchers.IO) {
                    cache.cachedAudioPath(task, result?.sourceFilePath) ?: cache.cacheTaskAudio(client, task, result?.sourceFilePath)
                } ?: error("云端音频缓存失败")
                val current = localTasks.firstOrNull { it.id == task.id } ?: task
                val updatedTask = current.copy(localFilePath = cachedPath)
                localTasks = localTasks.map { if (it.id == updatedTask.id) updatedTask else it }
                val normalized = (result ?: store.loadResultForTask(updatedTask))?.normalizedForTask(updatedTask)
                if (normalized != null) {
                    store.saveResult(normalized)
                    localKnowledgeStore?.replaceMeetingIndex(
                        updatedTask.copy(knowledgeScope = KnowledgeIndexScope.Cloud),
                        normalized
                    )
                }
                cachedCount += 1
            }.onFailure {
                updateTask(task.copy(localFilePath = ""))
            }
        }
        if (cachedCount > 0) persistTasks()
        return cachedCount
    }

    suspend fun refreshSpeakerProfiles() {
        val user = currentCloudUserOrNull() ?: return
        val client = apiClient ?: return
        runCatching {
            withContext(Dispatchers.IO) { client.listSpeakerProfiles(user) }
        }.onSuccess { profiles ->
            replaceSpeakerProfiles(profiles, user.userId)
        }.onFailure { error ->
            handleAuthFailure(error)
        }
    }

    suspend fun refreshMembershipProfile() {
        refreshMembershipProfile(force = false)
    }

    suspend fun refreshMembershipProfile(force: Boolean = false) {
        val user = currentCloudUserOrNull() ?: return
        val client = apiClient ?: return
        val now = System.currentTimeMillis()
        if (membershipRefreshInProgress) {
            return
        }
        if (!force && membershipLastLoadedAtMillis > 0 && now - membershipLastLoadedAtMillis < 15_000L) {
            return
        }
        if (!force && membershipLastFailedAtMillis > 0 && now - membershipLastFailedAtMillis < 8_000L) {
            return
        }
        val cached = membershipProfileStore?.load(user.userId)
        if (cached != null && !hasVisibleMembershipProfile()) {
            membershipProfile = cached.copy(loading = false)
        } else if (cached == null && !hasVisibleMembershipProfile()) {
            membershipProfile = membershipProfile.copy(loading = true)
        }
        membershipRefreshInProgress = true
        try {
            membershipProfile = withContext(Dispatchers.IO) { client.getMembershipProfile(user) }.copy(loading = false)
            membershipProfileStore?.save(user.userId, membershipProfile)
            membershipLastLoadedAtMillis = System.currentTimeMillis()
            membershipLastFailedAtMillis = 0L
        } catch (error: CancellationException) {
            membershipProfile = membershipProfile.copy(loading = false)
        } catch (error: Throwable) {
            membershipLastFailedAtMillis = System.currentTimeMillis()
            if (!handleAuthFailure(error)) {
                membershipProfile = membershipProfile.copy(loading = false)
                throw error
            }
        } finally {
            membershipRefreshInProgress = false
        }
    }

    private fun hasVisibleMembershipProfile(): Boolean {
        return membershipProfile.active ||
            membershipProfile.frozen ||
            membershipProfile.plans.isNotEmpty() ||
            membershipProfile.addons.isNotEmpty() ||
            membershipProfile.transcriptionMinutesTotal > 0 ||
            membershipProfile.transcriptionMinutesUsed > 0 ||
            membershipProfile.knowledgeQaTotal > 0 ||
            membershipProfile.knowledgeQaUsed > 0
    }

    suspend fun createAlipayPaymentOrder(planId: String): AlipayPaymentOrder {
        val user = currentCloudUserOrNull() ?: error("请先登录账号")
        val client = apiClient ?: error("云服务未配置")
        return withContext(Dispatchers.IO) { client.createAlipayPaymentOrder(user, planId) }
            .also { upsertPaymentOrder(it.order) }
    }

    suspend fun createAlipayAddonPaymentOrder(addonId: String): AlipayPaymentOrder {
        val user = currentCloudUserOrNull() ?: error("请先登录账号")
        val client = apiClient ?: error("云服务未配置")
        return withContext(Dispatchers.IO) { client.createAlipayAddonPaymentOrder(user, addonId, 1) }
            .also { upsertPaymentOrder(it.order) }
    }

    suspend fun refreshPaymentOrder(orderId: String): String {
        val user = currentCloudUserOrNull() ?: return ""
        val client = apiClient ?: return ""
        val order = withContext(Dispatchers.IO) { client.getPaymentOrder(user, orderId) }
        if (order != null) upsertPaymentOrder(order)
        return order?.status.orEmpty()
    }

    suspend fun syncAlipayPaymentOrder(orderId: String): String {
        val user = currentCloudUserOrNull() ?: return ""
        val client = apiClient ?: return ""
        val order = withContext(Dispatchers.IO) { client.syncAlipayPaymentOrder(user, orderId) }
        if (order != null) upsertPaymentOrder(order)
        return order?.status.orEmpty()
    }

    suspend fun refreshPaymentOrders(force: Boolean = false) {
        val user = currentCloudUserOrNull() ?: return
        val client = apiClient ?: return
        val now = System.currentTimeMillis()
        if (!force && paymentOrdersLastLoadedAtMillis > 0 && now - paymentOrdersLastLoadedAtMillis < 10_000L) return
        paymentOrdersLoading = true
        try {
            paymentOrders = withContext(Dispatchers.IO) { client.listPaymentOrders(user) }
            paymentOrdersLastLoadedAtMillis = System.currentTimeMillis()
        } catch (error: Throwable) {
            if (!handleAuthFailure(error)) throw error
        } finally {
            paymentOrdersLoading = false
        }
    }

    suspend fun syncAlipayPaymentOrderAndRefresh(orderId: String): String {
        val status = syncAlipayPaymentOrder(orderId)
        if (status == "支付成功") {
            coroutineScope {
                launch { refreshPaymentOrders(force = true) }
                launch { refreshMembershipProfile(force = true) }
            }
        } else {
            refreshPaymentOrders(force = true)
        }
        return status
    }

    suspend fun refreshPaymentOrdersAndMembershipAfterPayment() {
        coroutineScope {
            launch { refreshPaymentOrders(force = true) }
            launch { refreshMembershipProfile(force = true) }
        }
    }

    private fun upsertPaymentOrder(order: PaymentOrder) {
        paymentOrders = listOf(order) + paymentOrders.filterNot { it.id == order.id }
    }

    suspend fun rememberSelectedSpeakerVoiceprint(speaker: SpeakerIdentity, displayName: String) {
        val user = currentCloudUserOrNull() ?: run {
            showToast("请先登录后再保存声纹")
            return
        }
        val client = apiClient ?: run {
            showToast("云同步服务未配置")
            return
        }
        var task = selectedLocalFinishedTask() ?: run {
            showToast("当前会议不是本机已完成会议，无法从会议内容保存声纹")
            return
        }
        var remoteTaskId = task.remoteTaskId ?: resultStore?.loadResultForTask(task)?.remoteTaskId ?: selectedMeeting.remoteTaskId
        if (remoteTaskId.isNullOrBlank()) {
            showToast("正在同步会议后保存声纹...")
            updateTask(task.copy(syncStatus = CloudSyncStatus.PendingUpload, errorMessage = null))
            enqueueCloudOperation(CloudSyncOperationType.Upload, task.id, null)
            syncPendingLocalResultsToCloud(forceUpload = true)
            task = selectedLocalFinishedTask() ?: task
            remoteTaskId = task.remoteTaskId ?: resultStore?.loadResultForTask(task)?.remoteTaskId ?: selectedMeeting.remoteTaskId
        }
        val targetRemoteTaskId = remoteTaskId?.takeIf { it.isNotBlank() } ?: run {
            showToast("会议同步到云端后才能保存声纹")
            return
        }
        val cleanDisplayName = displayName.trim()
        val targetSpeaker = speaker.copy(displayName = cleanDisplayName.ifBlank { speaker.displayName })
        val profile = withContext(Dispatchers.IO) {
            client.rememberSpeakerProfile(user, targetRemoteTaskId, targetSpeaker, cleanDisplayName)
        }
        replaceSpeakerProfiles(
            (speakerProfiles.filterNot { it.id == profile.id } + profile)
                .sortedBy { it.displayName },
            user.userId
        )
        showToast("已保存 ${profile.displayName} 的声纹")
    }

    suspend fun deleteSpeakerProfile(profileId: String) {
        val user = currentCloudUserOrNull() ?: run {
            showToast("请先登录")
            return
        }
        val client = apiClient ?: return
        withContext(Dispatchers.IO) { client.deleteSpeakerProfile(user, profileId) }
        replaceSpeakerProfiles(speakerProfiles.filterNot { it.id == profileId }, user.userId)
        showToast("声纹档案已删除")
    }

    suspend fun updateSpeakerProfile(profileId: String, displayName: String? = null, active: Boolean? = null) {
        val user = currentCloudUserOrNull() ?: run {
            showToast("请先登录")
            return
        }
        val client = apiClient ?: return
        val profile = withContext(Dispatchers.IO) {
            client.updateSpeakerProfile(
                user = user,
                profileId = profileId,
                displayName = displayName?.trim()?.takeIf { it.isNotBlank() },
                active = active
            )
        }
        replaceSpeakerProfiles(
            (speakerProfiles.filterNot { it.id == profile.id } + profile)
                .sortedBy { it.displayName },
            user.userId
        )
        showToast("声纹档案已更新")
    }

    suspend fun enrollSpeakerProfileFromAudio(displayName: String, localFilePath: String) {
        val user = currentCloudUserOrNull() ?: run {
            showToast("请先登录后再录入声纹")
            return
        }
        val client = apiClient ?: run {
            showToast("云同步服务未配置")
            return
        }
        val cleanName = displayName.trim()
        if (cleanName.isBlank()) {
            showToast("请先填写声纹姓名")
            return
        }
        val profile = withContext(Dispatchers.IO) {
            client.enrollSpeakerProfileFromAudio(user, cleanName, localFilePath)
        }
        replaceSpeakerProfiles(
            (speakerProfiles.filterNot { it.id == profile.id } + profile)
                .sortedBy { it.displayName },
            user.userId
        )
        runCatching { File(localFilePath).delete() }
        showToast("已录入 ${profile.displayName} 的声纹")
    }

    fun logoutCloud() {
        val userId = cloudUser?.userId
        if (userId != null) {
            cloudSyncOperationStore?.saveOperations(userId, emptyList())
        }
        cloudUserStore?.clear()
        apiClient?.setAccessToken(null)
        cloudUser = null
        membershipProfile = MembershipProfile()
        clearCachedSpeakerProfiles(userId)
        cloudSyncOperations = emptyList()
        cloudSyncStatusText = "云端同步未开启"
        selectedTaskId = null
        selectedMeetingId = ""
        localTasks = emptyList()
        scheduledMeetings = emptyList()
        resultStore?.clearAll()
        localKnowledgeStore?.clearAll()
        persistTasks()
        persistSchedules()
        showToast("已退出账号")
    }

    fun requestLogoutCloud() {
        showSheet(SheetType.LogoutConfirm)
    }

    suspend fun syncScheduledMeetingToCloud(meeting: ScheduledMeeting) {
        if (!cloudSyncEnabled || currentCloudUserOrNull() == null) return
        enqueueCloudOperation(CloudSyncOperationType.UpsertSchedule, meeting.id, meeting.id)
        syncPendingLocalResultsToCloud()
    }

    suspend fun deleteScheduledMeetingFromCloud(scheduleId: String) {
        val user = currentCloudUserOrNull() ?: return
        val client = apiClient ?: return
        withContext(Dispatchers.IO) { client.deleteSchedule(user, scheduleId) }
    }

    suspend fun deleteScheduledMeetingEverywhere(scheduleId: String): Boolean {
        val deleted = deleteScheduledMeeting(scheduleId)
        if (deleted && currentCloudUserOrNull() != null) {
            enqueueCloudOperation(CloudSyncOperationType.DeleteSchedule, scheduleId, scheduleId)
            syncPendingLocalResultsToCloud()
        }
        return deleted
    }

    fun requestDeleteSchedule(scheduleId: String) {
        val meeting = scheduledMeetings.firstOrNull { it.id == scheduleId } ?: return
        if (meeting.isFinished()) {
            showToast("已完成会议不支持删除")
            return
        }
        pendingDeleteScheduleId = scheduleId
        showSheet(SheetType.DeleteSchedule)
    }

    suspend fun deletePendingSchedule(): ScheduledMeeting? {
        val meeting = pendingDeleteSchedule ?: return null
        val deleted = deleteScheduledMeetingEverywhere(meeting.id)
        if (!deleted) return null
        pendingDeleteScheduleId = null
        return meeting
    }

    fun checkScheduleReminders(nowMillis: Long = System.currentTimeMillis()) {
        markOverdueSchedules(nowMillis)
        if (sheet != null || recording.status == RecordingStatus.Preparing || recording.status == RecordingStatus.Recording || recording.status == RecordingStatus.Paused) return
        val meeting = scheduledMeetings
            .filterNot { it.id in dismissedScheduleReminderIds }
            .filterNot { schedule -> (snoozedScheduleReminderUntil[schedule.id] ?: 0L) > nowMillis }
            .firstOrNull { schedule ->
                val start = schedule.startAtMillis ?: return@firstOrNull false
                val end = schedule.endAtMillis ?: (start + 60 * 60 * 1000L)
                !schedule.isFinished(nowMillis) && !schedule.isOverdue(nowMillis) && nowMillis in (start - REMINDER_LEAD_MILLIS)..end
            }
        if (meeting != null) {
            reminderScheduleId = meeting.id
            sheet = SheetType.ScheduleReminder
        }
    }

    fun refreshScheduledMeetings() {
        markOverdueSchedules()
    }

    fun dismissScheduleReminder() {
        reminderScheduleId?.let { id ->
            dismissedScheduleReminderIds = dismissedScheduleReminderIds + id
            snoozedScheduleReminderUntil = snoozedScheduleReminderUntil - id
        }
        reminderScheduleId = null
        closeSheet()
    }

    fun snoozeScheduleReminder(minutes: Long = 5) {
        reminderScheduleId?.let { id ->
            dismissedScheduleReminderIds = dismissedScheduleReminderIds - id
            snoozedScheduleReminderUntil = snoozedScheduleReminderUntil + (id to (System.currentTimeMillis() + minutes * 60 * 1000L))
        }
        reminderScheduleId = null
        closeSheet()
    }

    fun consumeScheduleReminderForRecording(): ScheduledMeeting? {
        val meeting = reminderSchedule
        reminderScheduleId = null
        if (meeting != null) {
            dismissedScheduleReminderIds = dismissedScheduleReminderIds + meeting.id
            snoozedScheduleReminderUntil = snoozedScheduleReminderUntil - meeting.id
        }
        return meeting
    }

    fun clearToast() {
        toast = ""
    }

    fun startRecording(localFilePath: String?) {
        liveFinalTranscriptSegments = emptyList()
        liveCompleteTranscriptSegments = emptyList()
        livePartialTranscriptSegments = emptyList()
        liveTranscriptSegments = emptyList()
        recording = RecordingUiState(
            status = RecordingStatus.Recording,
            elapsedSeconds = 0,
            localFilePath = localFilePath,
            audioLevel = null,
            audioWarning = null,
            transcriptionStatus = null
        )
    }

    fun applyRecordingServiceState(state: RecordingServiceState) {
        if (state.status == RecordingStatus.Idle && state.errorMessage == null) return
        if (state.status == RecordingStatus.Preparing && recording.status != RecordingStatus.Preparing) {
            liveFinalTranscriptSegments = emptyList()
            liveCompleteTranscriptSegments = emptyList()
            livePartialTranscriptSegments = emptyList()
            liveTranscriptSegments = emptyList()
        }
        recording = recording.copy(
            status = state.status,
            elapsedSeconds = state.elapsedSeconds,
            localFilePath = state.localFilePath ?: recording.localFilePath,
            audioLevel = state.audioLevel,
            audioWarning = state.audioWarning,
            transcriptionStatus = state.transcriptionStatus
        )
    }

    fun pauseRecording() {
        recording = recording.copy(status = RecordingStatus.Paused)
    }

    fun resumeRecording() {
        recording = recording.copy(status = RecordingStatus.Recording)
    }

    fun finishRecording(localFilePath: String? = recording.localFilePath): String? {
        recording = recording.copy(status = RecordingStatus.Finished, localFilePath = localFilePath)
        return if (localFilePath != null) {
            val scheduleId = recordingScheduleId
            val createdAtMillis = System.currentTimeMillis()
            val rawTitle = recordingTitle ?: localFilePath.substringAfterLast('/')
            val task = MeetingTask(
                id = "recording-${localFilePath.hashCode()}",
                title = meetingDisplayTitleOrDefault(rawTitle, createdAtMillis),
                source = MeetingTaskSource.Recording,
                status = MeetingTaskStatus.WaitingProcess,
                localFilePath = localFilePath,
                createdAtLabel = "刚刚",
                createdAtMillis = createdAtMillis,
                sizeLabel = localFilePath.readableLocalFileSizeLabel(),
                progressPercent = 0f,
                progressLabel = "正在准备音频处理",
                progressStage = "preparing_audio",
                syncStatus = CloudSyncStatus.LocalOnly,
                knowledgeScope = KnowledgeIndexScope.Local,
                deviceId = deviceId,
                scheduleId = scheduleId,
                scheduleNote = recordingScheduleNote,
                recognitionLanguage = recordingRecognitionLanguage
            )
            if (scheduleId != null) {
                removeCompletedSchedule(scheduleId)
            }
            val transcriptSnapshot = liveCompleteTranscriptSegments
                .filter { it.text.isNotBlank() }
                .sortedBy { it.startMs ?: Long.MAX_VALUE }
            recordingTitle = null
            recordingScheduleId = null
            recordingScheduleNote = null
            recordingRecognitionLanguage = RecognitionLanguage.Chinese
            upsertTask(task)
            if (transcriptSnapshot.isNotEmpty()) {
                liveTranscriptsByTaskId = liveTranscriptsByTaskId + (task.id to transcriptSnapshot)
            }
            selectedTaskId = task.id
            task.id
        } else {
            null
        }
    }

    fun tickRecording() {
        if (recording.status == RecordingStatus.Recording) {
            recording = recording.copy(elapsedSeconds = recording.elapsedSeconds + 1)
        }
    }

    fun finalizeRecordingTranscript(taskId: String) {
        val task = localTasks.firstOrNull { it.id == taskId } ?: return
        val transcriptSnapshot = liveCompleteTranscriptSegments
            .filter { it.text.isNotBlank() }
            .sortedBy { it.startMs ?: Long.MAX_VALUE }
        if (transcriptSnapshot.isNotEmpty()) {
            liveTranscriptsByTaskId = liveTranscriptsByTaskId + (task.id to transcriptSnapshot)
        }
    }

    fun appendLiveTranscriptSegments(
        segments: List<TranscriptSegment>,
        isFinal: Boolean = false,
        replaceAll: Boolean = false
    ) {
        if (segments.isEmpty()) return
        val incomingSegments = segments.mapNotNull { it.normalizedLiveSegmentOrNull() }
        if (isFinal) {
            livePartialTranscriptSegments = emptyList()
            val current = if (replaceAll) mutableListOf() else liveCompleteTranscriptSegments.toMutableList()
            if (!replaceAll) {
                current.removeAll { existing ->
                    incomingSegments.any { incoming -> existing.isCoveredByFinalLiveSegment(incoming) }
                }
            }
            incomingSegments.forEach { incoming ->
                val duplicateIndex = current.indexOfFirst { it.sameLiveContent(incoming) }
                if (duplicateIndex >= 0) {
                    current[duplicateIndex] = current[duplicateIndex].mergeLiveUpdate(incoming)
                    return@forEach
                }
                val updateIndex = current.indexOfLast { it.canBeUpdatedBy(incoming) }
                if (updateIndex >= 0) {
                    current[updateIndex] = current[updateIndex].mergeLiveUpdate(incoming)
                } else {
                    current += incoming
                }
            }
            liveCompleteTranscriptSegments = current
                .sortedBy { it.startMs ?: Long.MAX_VALUE }
            liveFinalTranscriptSegments = liveCompleteTranscriptSegments
        } else {
            livePartialTranscriptSegments = incomingSegments
        }
        liveTranscriptSegments = liveFinalTranscriptSegments + livePartialTranscriptSegments
    }

    fun prepareRecordingForSchedule(
        meeting: ScheduledMeeting? = null,
        recognitionLanguage: RecognitionLanguage = RecognitionLanguage.Chinese
    ) {
        recordingTitle = meeting?.title
        recordingScheduleId = meeting?.id
        recordingScheduleNote = meeting?.note?.takeIf { it.isNotBlank() }
        recordingRecognitionLanguage = recognitionLanguage
        pendingVoiceprintEnrollmentName = null
    }

    fun updateRecordingRecognitionLanguage(recognitionLanguage: RecognitionLanguage) {
        recordingRecognitionLanguage = recognitionLanguage
    }

    fun prepareVoiceprintEnrollment(displayName: String) {
        val cleanName = displayName.trim()
        pendingVoiceprintEnrollmentName = cleanName
        recordingTitle = if (cleanName.isBlank()) "声纹录入" else "声纹录入：$cleanName"
        recordingScheduleId = null
        recordingScheduleNote = "声纹录入采样"
        recordingRecognitionLanguage = RecognitionLanguage.Chinese
    }

    fun finishVoiceprintEnrollmentRecording(localFilePath: String? = recording.localFilePath): String? {
        recording = recording.copy(status = RecordingStatus.Finished, localFilePath = localFilePath)
        recordingTitle = null
        recordingScheduleId = null
        recordingScheduleNote = null
        recordingRecognitionLanguage = RecognitionLanguage.Chinese
        liveFinalTranscriptSegments = emptyList()
        liveCompleteTranscriptSegments = emptyList()
        livePartialTranscriptSegments = emptyList()
        liveTranscriptSegments = emptyList()
        return localFilePath
    }

    fun consumeVoiceprintEnrollmentName(): String? {
        val name = pendingVoiceprintEnrollmentName
        pendingVoiceprintEnrollmentName = null
        return name
    }

    fun addImportedTask(
        title: String,
        localFilePath: String,
        sizeLabel: String,
        recognitionLanguage: RecognitionLanguage = RecognitionLanguage.Chinese
    ): String {
        val createdAtMillis = System.currentTimeMillis()
        val task = MeetingTask(
            id = "import-${localFilePath.hashCode()}",
            title = meetingDisplayTitleOrDefault(title, createdAtMillis),
            source = MeetingTaskSource.Import,
            status = MeetingTaskStatus.WaitingProcess,
            localFilePath = localFilePath,
            createdAtLabel = "刚刚",
            createdAtMillis = createdAtMillis,
            sizeLabel = sizeLabel,
            syncStatus = CloudSyncStatus.LocalOnly,
            knowledgeScope = KnowledgeIndexScope.Local,
            deviceId = deviceId,
            recognitionLanguage = recognitionLanguage
        )
        upsertTask(task)
        selectedTaskId = task.id
        return task.id
    }

    fun recoverLocalRecording(localFilePath: String, sizeLabel: String): Boolean {
        if (localTasks.any { it.localFilePath == localFilePath }) return false
        val file = File(localFilePath)
        val createdAtMillis = file.lastModified().takeIf { it > 0L } ?: System.currentTimeMillis()
        val fileName = file.name.ifBlank { "未完成录音" }
        val task = MeetingTask(
            id = "recording-${localFilePath.hashCode()}",
            title = meetingDisplayTitleOrDefault(fileName, createdAtMillis),
            source = MeetingTaskSource.Recording,
            status = MeetingTaskStatus.WaitingProcess,
            localFilePath = localFilePath,
            createdAtLabel = "待恢复",
            createdAtMillis = createdAtMillis,
            sizeLabel = sizeLabel,
            syncStatus = CloudSyncStatus.LocalOnly,
            knowledgeScope = KnowledgeIndexScope.Local,
            deviceId = deviceId,
            recognitionLanguage = RecognitionLanguage.Chinese
        )
        upsertTask(task)
        return true
    }

    fun beginEditingSchedule(id: String) {
        scheduledMeetings.firstOrNull { it.id == id } ?: return
        editingScheduleId = id
        sheet = SheetType.CreateMeeting
    }

    fun beginCreatingSchedule() {
        editingScheduleId = null
        sheet = SheetType.CreateMeeting
    }

    fun stopEditingSchedule() {
        editingScheduleId = null
    }

    fun createScheduledMeeting(title: String, time: String, participants: String, note: String): ScheduledMeeting? {
        val cleanTitle = title.trim()
        val cleanTime = time.trim()
        if (cleanTitle.isBlank() || cleanTitle == "无" || cleanTitle.equals("null", ignoreCase = true)) {
            showToast("会议主题不能为空")
            return null
        }
        if (cleanTime.isBlank()) {
            showToast("会议时间不能为空")
            return null
        }
        val parsed = parseScheduleTime(cleanTime)
        if (parsed == null) {
            showToast("会议时间格式不正确")
            return null
        }
        if (hasScheduleConflict(scheduledMeetings, parsed.startAtMillis)) {
            showToast("时间冲突，请调整会议时间")
            return null
        }
        val cleanParticipants = participants.cleanParticipants()
        val meeting = ScheduledMeeting(
            id = "schedule-${System.currentTimeMillis()}",
            time = parsed.displayTime,
            title = cleanTitle,
            participants = cleanParticipants,
            note = note.trim(),
            durationLabel = parsed.durationLabel,
            reminderLabel = "提前 5 分钟提醒",
            startAtMillis = parsed.startAtMillis,
            endAtMillis = parsed.endAtMillis,
            createdAtMillis = System.currentTimeMillis(),
            status = ScheduledMeetingStatus.Pending
        )
        scheduledMeetings = (scheduledMeetings + meeting).sortedBy { it.startAtMillis ?: Long.MAX_VALUE }
        persistSchedules()
        showToast("已预约会议")
        checkScheduleReminders()
        return meeting
    }

    fun updateScheduledMeeting(id: String, title: String, time: String, participants: String, note: String): ScheduledMeeting? {
        val existing = scheduledMeetings.firstOrNull { it.id == id } ?: return null
        if (existing.isFinished()) {
            showToast("已完成会议不支持修改")
            return null
        }
        val cleanTitle = title.trim()
        val cleanTime = time.trim()
        if (cleanTitle.isBlank() || cleanTitle == "无" || cleanTitle.equals("null", ignoreCase = true)) {
            showToast("会议主题不能为空")
            return null
        }
        if (cleanTime.isBlank()) {
            showToast("会议时间不能为空")
            return null
        }
        val parsed = parseScheduleTime(cleanTime)
        if (parsed == null) {
            showToast("会议时间格式不正确")
            return null
        }
        if (hasScheduleConflict(scheduledMeetings, parsed.startAtMillis, ignoreId = id)) {
            showToast("时间冲突，请调整会议时间")
            return null
        }
        val updated = existing.copy(
            title = cleanTitle,
            time = parsed.displayTime,
            participants = participants.cleanParticipants(),
            note = note.trim(),
            durationLabel = parsed.durationLabel,
            startAtMillis = parsed.startAtMillis,
            endAtMillis = parsed.endAtMillis,
            status = ScheduledMeetingStatus.Pending
        )
        scheduledMeetings = scheduledMeetings.map { if (it.id == id) updated else it }.sortedBy { it.startAtMillis ?: Long.MAX_VALUE }
        editingScheduleId = null
        persistSchedules()
        showToast("会议已更新")
        dismissedScheduleReminderIds = dismissedScheduleReminderIds - id
        snoozedScheduleReminderUntil = snoozedScheduleReminderUntil - id
        checkScheduleReminders()
        return updated
    }

    fun deleteScheduledMeeting(id: String): Boolean {
        val meeting = scheduledMeetings.firstOrNull { it.id == id } ?: return false
        if (meeting.isFinished()) {
            showToast("删除失败，已完成会议不支持删除")
            return false
        }
        scheduledMeetings = scheduledMeetings.filterNot { it.id == id }
        if (reminderScheduleId == id) reminderScheduleId = null
        dismissedScheduleReminderIds = dismissedScheduleReminderIds - id
        snoozedScheduleReminderUntil = snoozedScheduleReminderUntil - id
        persistSchedules()
        showToast("预约会议已删除")
        return true
    }

    private fun markOverdueSchedules(nowMillis: Long = System.currentTimeMillis()) {
        val overdueSchedules = scheduledMeetings
            .filter { it.status == ScheduledMeetingStatus.Pending && it.isOverdue(nowMillis) }
        val overdueIds = overdueSchedules
            .map { it.id }
            .toSet()
        if (overdueIds.isEmpty()) return
        scheduledMeetings = scheduledMeetings.map { meeting ->
            if (meeting.id in overdueIds) meeting.copy(status = ScheduledMeetingStatus.Overdue) else meeting
        }
        dismissedScheduleReminderIds = dismissedScheduleReminderIds - overdueIds
        snoozedScheduleReminderUntil = snoozedScheduleReminderUntil - overdueIds
        if (reminderScheduleId in overdueIds) reminderScheduleId = null
        persistSchedules()
        if (cloudUser != null) {
            overdueSchedules.forEach { schedule ->
                enqueueCloudOperation(CloudSyncOperationType.UpsertSchedule, schedule.id, schedule.id)
            }
        }
    }

    private fun removeCompletedSchedule(scheduleId: String) {
        if (scheduledMeetings.none { it.id == scheduleId }) return
        scheduledMeetings = scheduledMeetings.filterNot { it.id == scheduleId }
        dismissedScheduleReminderIds = dismissedScheduleReminderIds - scheduleId
        snoozedScheduleReminderUntil = snoozedScheduleReminderUntil - scheduleId
        if (reminderScheduleId == scheduleId) reminderScheduleId = null
        persistSchedules()
        if (cloudUser != null) {
            enqueueCloudOperation(CloudSyncOperationType.DeleteSchedule, scheduleId, scheduleId)
        }
    }

    suspend fun renameSpeaker(speaker: SpeakerIdentity, target: String) {
        val cleanTarget = target.trim()
        if (speaker.id.isBlank() || cleanTarget.isBlank()) return
        if (speaker.displayName == cleanTarget) {
            showToast("说话人名称未变化")
            return
        }
        var changed = false
        updateSelectedResult(syncServer = true) { result ->
            changed = true
            result.renamedSpeaker(speaker.id, speaker.displayName, cleanTarget)
        }
        if (!changed) {
            val snapshot = selectedMeetingOrNull ?: lastSelectedMeetingSnapshot
            if (snapshot != null && snapshot.id == selectedMeetingId) {
                lastSelectedMeetingSnapshot = snapshot.renamedSpeaker(speaker.id, speaker.displayName, cleanTarget)
                changed = true
            }
        }
        showToast(if (changed) "已更新说话人名称" else "当前会议无法保存说话人名称")
    }

    suspend fun updateSelectedSegmentSpeaker(target: String, selectedSpeakerId: String? = null) {
        val cleanTarget = target.trim()
        if (cleanTarget.isBlank()) {
            showToast("说话人不能为空")
            return
        }
        val index = speakerEditSegmentIndex ?: run {
            showToast("请选择要修改的片段")
            return
        }
        val task = selectedLocalFinishedTask() ?: run {
            showToast("当前会议无法修正")
            return
        }
        val store = resultStore ?: run {
            showToast("会议结果存储未配置")
            return
        }
        val current = store.loadResultForTask(task) ?: run {
            showToast("会议结果不存在")
            return
        }
        if (index !in current.transcripts.indices) {
            showToast("来源片段不存在")
            return
        }
        val identities = current.transcripts.speakerIdentities()
        val targetId = selectedSpeakerId
            ?.takeIf { selectedId -> identities.any { it.id == selectedId && it.displayName == cleanTarget } }
            ?: identities.firstOrNull { it.displayName == cleanTarget }?.id
            ?: speakerIdentityIdForName(cleanTarget)
        val currentSegment = current.transcripts[index]
        if (currentSegment.stableSpeakerId == targetId && currentSegment.speaker == cleanTarget) {
            showToast("说话人未变化")
            return
        }
        val edited = current.copy(
            transcripts = current.transcripts.mapIndexed { itemIndex, segment ->
                if (itemIndex == index) {
                    segment.copy(speaker = cleanTarget, speakerId = targetId)
                } else {
                    segment
                }
            }
        ).normalizedForTask(task)
        saveTranscriptEdit(task, edited, "说话人已更新")
    }

    suspend fun updateSelectedSummary(summary: String) {
        val clean = summary.trim()
        if (clean.isBlank()) {
            showToast("纪要不能为空")
            return
        }
        updateSelectedResult(syncServer = true) { it.copy(summary = clean) }
        showToast("纪要已保存")
    }

    suspend fun updateSelectedMeetingTitle(title: String) {
        val clean = title.trim()
        if (clean.length !in 2..80) {
            showToast("会议标题需为 2-80 个字符")
            return
        }
        val task = selectedLocalFinishedTask() ?: run {
            showToast("当前会议无法保存名称")
            return
        }
        val currentTitle = task.title.substringBeforeLast('.', task.title)
        updateSelectedResult(
            syncServer = true,
            taskTransform = { it.copy(title = clean) }
        ) { result ->
            result.copy(
                todos = result.todos.map { todo -> todo.copy(meetingTitle = clean) }
            )
        }
        val changed = clean != currentTitle || clean != task.title
        showToast(if (changed) "会议名称已保存" else "会议名称未变化")
    }

    suspend fun correctSelectedTranscript(text: String) {
        val clean = text.trim()
        if (clean.isBlank()) {
            showToast("转写原文不能为空")
            return
        }
        val index = selectedSourceSegmentIndex
        val task = selectedLocalFinishedTask() ?: run {
            showToast("当前会议无法修正")
            return
        }
        val store = resultStore ?: run {
            showToast("会议结果存储未配置")
            return
        }
        val current = store.loadResultForTask(task) ?: run {
            showToast("会议结果不存在")
            return
        }
        if (index !in current.transcripts.indices) {
            showToast("来源片段不存在")
            return
        }
        val edited = current.copy(
            transcripts = current.transcripts.mapIndexed { itemIndex, segment ->
                if (itemIndex == index) segment.copy(text = clean) else segment
            }
        ).normalizedForTask(task)
        saveTranscriptEdit(task, edited, "转写已修正")
    }

    private suspend fun saveTranscriptEdit(
        task: MeetingTask,
        edited: MeetingProcessingResult,
        successMessage: String
    ) {
        val store = resultStore ?: run {
            showToast("会议结果存储未配置")
            return
        }
        val merged = edited.copy(taskId = task.id, remoteTaskId = edited.remoteTaskId ?: task.remoteTaskId)
            .normalizedForTask(task)
        store.saveResult(merged)
        localKnowledgeStore?.replaceMeetingIndex(task, merged)
        lastSelectedMeetingSnapshot = merged.toMeetingSnapshot(task)
        transcriptEditedTaskIds = transcriptEditedTaskIds + task.id
        val nextTask = if (cloudUser != null && apiClient != null) task.copy(syncStatus = CloudSyncStatus.PendingUpload, errorMessage = null) else task
        updateTask(nextTask)
        if (cloudUser != null && apiClient != null) {
            if (task.remoteTaskId != null) {
                enqueueCloudOperation(CloudSyncOperationType.UpdateResult, task.id, task.remoteTaskId)
            } else if (cloudSyncEnabled) {
                enqueueCloudOperation(CloudSyncOperationType.Upload, task.id, null)
            }
        }
        showToast(successMessage)
    }

    suspend fun regenerateSelectedMeetingMinutes() {
        val task = selectedLocalFinishedTask() ?: run {
            showToast("当前会议无法更新纪要")
            return
        }
        if (task.id in regeneratingMinutesTaskIds) {
            showToast("纪要正在更新")
            return
        }
        val store = resultStore ?: run {
            showToast("会议结果存储未配置")
            return
        }
        val client = apiClient ?: run {
            showToast("AI 服务未配置")
            return
        }
        val current = store.loadResultForTask(task) ?: run {
            showToast("会议结果不存在")
            return
        }
        if (current.transcripts.none { it.text.trim().isNotBlank() }) {
            showToast("会议内容为空，无法更新纪要")
            return
        }
        regeneratingMinutesTaskIds = regeneratingMinutesTaskIds + task.id
        showToast("纪要开始更新")
        try {
            val regenerated = withContext(Dispatchers.IO) {
                client.regenerateLocalMinutes(task, current)
            }
            val latestTask = localTasks.firstOrNull { it.id == task.id } ?: task
            val latestResult = store.loadResultForTask(latestTask) ?: current
            val merged = latestResult.copy(
                remoteTaskId = regenerated.remoteTaskId ?: latestResult.remoteTaskId ?: latestTask.remoteTaskId,
                summary = regenerated.summary,
                topics = regenerated.topics,
                decisions = regenerated.decisions,
                todos = mergeRegeneratedTodos(
                    previousTodos = latestResult.todos,
                    regeneratedTodos = regenerated.todos
                ),
                risks = regenerated.risks,
                generatedAtLabel = regenerated.generatedAtLabel
            ).normalizedForTask(latestTask)
            store.saveResult(merged)
            localKnowledgeStore?.replaceMeetingIndex(latestTask, merged)
            lastSelectedMeetingSnapshot = merged.toMeetingSnapshot(latestTask)

            val shouldSync = currentCloudUserOrNull() != null
            val nextTask = latestTask.copy(
                remoteTaskId = merged.remoteTaskId ?: latestTask.remoteTaskId,
                syncStatus = if (shouldSync) CloudSyncStatus.PendingUpload else latestTask.syncStatus,
                errorMessage = null
            )
            updateTask(nextTask)
            if (shouldSync) {
                val remoteTaskId = nextTask.remoteTaskId
                if (remoteTaskId != null) {
                    enqueueCloudOperation(CloudSyncOperationType.UpdateResult, nextTask.id, remoteTaskId)
                } else if (cloudSyncEnabled) {
                    enqueueCloudOperation(CloudSyncOperationType.Upload, nextTask.id, null)
                }
            }
            showToast("纪要已更新")
            transcriptEditedTaskIds = transcriptEditedTaskIds - task.id
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            handleAuthFailure(error)
            showToast("纪要更新失败：${error.userFacingStateMessage("操作失败，请稍后重试")}")
        } finally {
            regeneratingMinutesTaskIds = regeneratingMinutesTaskIds - task.id
        }
    }

    suspend fun toggleTodo(todo: TodoItem): Boolean {
        if (todo.effectiveStatus != TodoStatus.Done && todo.missingAssigneeInfo) {
            showToast("请先补全负责人")
            return false
        }
        val updated = updateResultContainingTodo(todo, syncServer = true) { result ->
            val now = System.currentTimeMillis()
            result.copy(todos = result.todos.map { item ->
                if (item.id != todo.id) {
                    item
                } else if (item.effectiveStatus == TodoStatus.Done) {
                    item.copy(
                        done = false,
                        status = if (item.missingAssigneeInfo) TodoStatus.PendingConfirm else TodoStatus.Todo,
                        completedAtMillis = null,
                        completedAtLabel = null,
                        lockedFields = item.lockedFields + TodoLockedFieldStatus
                    )
                } else {
                    item.copy(
                        done = true,
                        status = TodoStatus.Done,
                        completedAtMillis = now,
                        completedAtLabel = now.formatDateTimeLabel(),
                        lockedFields = item.lockedFields + TodoLockedFieldStatus
                    )
                }
            })
        }
        if (!updated) return false
        return true
    }

    suspend fun updateTodo(todo: TodoItem, title: String, assigneeName: String, dueAtLabel: String, priority: String, description: String, status: TodoStatus): Boolean {
        val cleanTitle = title.trim()
        if (cleanTitle.isBlank()) {
            showToast("任务标题不能为空")
            return false
        }
        if (cleanTitle.length > 100) {
            showToast("任务标题不能超过 100 个字符")
            return false
        }
        val cleanAssignee = assigneeName.cleanTodoRequiredText()
        val cleanDueAtLabel = dueAtLabel.cleanTodoRequiredText()
        val dueMillis = cleanDueAtLabel?.parseTodoDueMillis()
        val nextStatus = status
        val updated = updateResultContainingTodo(todo, syncServer = true) { result ->
            val now = System.currentTimeMillis()
            result.copy(todos = result.todos.map { item ->
                if (item.id == todo.id) {
                    val completedAt = if (nextStatus == TodoStatus.Done) item.completedAtMillis ?: now else null
                    val cleanDescription = description.cleanNullableText().orEmpty()
                    val cleanPriority = priority.normalizedTodoPriority()
                    val nextLockedFields = item.lockedFields + buildSet {
                        if (item.title.trim() != cleanTitle) add(TodoLockedFieldTitle)
                        if (item.description.cleanNullableText().orEmpty() != cleanDescription) add(TodoLockedFieldDescription)
                        if (item.assigneeName.cleanTodoRequiredText() != cleanAssignee) add(TodoLockedFieldAssignee)
                        if (item.dueAtLabel.cleanTodoRequiredText() != cleanDueAtLabel || item.dueAtMillis != dueMillis) add(TodoLockedFieldDue)
                        if ((item.priority.cleanNullableText() ?: "medium") != cleanPriority) add(TodoLockedFieldPriority)
                        if (item.effectiveStatus != nextStatus || item.done != (nextStatus == TodoStatus.Done)) add(TodoLockedFieldStatus)
                    }
                    item.copy(
                        title = cleanTitle,
                        description = cleanDescription,
                        assigneeName = cleanAssignee,
                        dueAtLabel = cleanDueAtLabel,
                        dueAtMillis = dueMillis,
                        priority = cleanPriority,
                        status = nextStatus,
                        done = nextStatus == TodoStatus.Done,
                        completedAtMillis = completedAt,
                        completedAtLabel = completedAt?.formatDateTimeLabel(),
                        lockedFields = nextLockedFields
                    )
                } else {
                    item
                }
            })
        }
        if (!updated) return false
        showToast("待办已保存")
        return true
    }

    suspend fun createTodoForSelectedMeeting(title: String, assigneeName: String, dueAtLabel: String, priority: String, description: String, status: TodoStatus): Boolean {
        val task = selectedLocalFinishedTask() ?: run {
            showToast("当前会议无法补充待办")
            return false
        }
        val cleanTitle = title.trim()
        if (cleanTitle.isBlank()) {
            showToast("任务标题不能为空")
            return false
        }
        if (cleanTitle.length > 100) {
            showToast("任务标题不能超过 100 个字符")
            return false
        }
        val cleanAssignee = assigneeName.cleanTodoRequiredText()
        val cleanDueAtLabel = dueAtLabel.cleanTodoRequiredText()
        val dueMillis = cleanDueAtLabel?.parseTodoDueMillis()
        val cleanDescription = description.cleanNullableText().orEmpty()
        val cleanPriority = priority.normalizedTodoPriority()
        val now = System.currentTimeMillis()
        val nextStatus = if (cleanAssignee == null && status != TodoStatus.Done && status != TodoStatus.Canceled) {
            TodoStatus.PendingConfirm
        } else {
            status
        }
        val manualTodo = TodoItem(
            id = "todo-manual-${now}-${UUID.randomUUID().toString().take(8)}",
            title = cleanTitle,
            source = ManualTodoSource,
            done = nextStatus == TodoStatus.Done,
            meetingId = task.id,
            meetingTitle = task.title,
            description = cleanDescription,
            assigneeName = cleanAssignee,
            dueAtMillis = dueMillis,
            dueAtLabel = cleanDueAtLabel,
            priority = cleanPriority,
            status = nextStatus,
            completedAtMillis = if (nextStatus == TodoStatus.Done) now else null,
            completedAtLabel = if (nextStatus == TodoStatus.Done) now.formatDateTimeLabel() else null,
            sourceSegmentIndex = null,
            lockedFields = setOf(
                TodoLockedFieldManual,
                TodoLockedFieldTitle,
                TodoLockedFieldDescription,
                TodoLockedFieldAssignee,
                TodoLockedFieldDue,
                TodoLockedFieldPriority,
                TodoLockedFieldStatus
            )
        )
        updateSelectedResult(syncServer = true) { result ->
            result.copy(todos = result.todos + manualTodo)
        }
        showToast("待办已补充")
        return true
    }

    suspend fun startTodo(todo: TodoItem): Boolean {
        if (todo.missingAssigneeInfo) {
            showToast("请先补全负责人")
            return false
        }
        val updated = updateResultContainingTodo(todo, syncServer = true) { result ->
            result.copy(todos = result.todos.map {
                if (it.id == todo.id) it.copy(status = TodoStatus.InProgress, done = false, lockedFields = it.lockedFields + TodoLockedFieldStatus) else it
            })
        }
        if (!updated) return false
        showToast("待办已设为进行中")
        return true
    }

    suspend fun deleteTodo(todo: TodoItem): Boolean {
        val updated = updateResultContainingTodo(todo, syncServer = true) { result ->
            result.copy(todos = result.todos.map {
                if (it.id == todo.id) it.copy(status = TodoStatus.Canceled, done = false, lockedFields = it.lockedFields + TodoLockedFieldStatus) else it
            })
        }
        if (!updated) return false
        showToast("待办已取消")
        return true
    }

    fun confirmSelectedMeeting() {
        val task = selectedLocalFinishedTask() ?: return
        val updated = task.copy(
            confirmed = true,
            syncStatus = if (cloudSyncEnabled) CloudSyncStatus.PendingUpload else task.syncStatus,
            errorMessage = null
        )
        updateTask(updated)
        if (cloudSyncEnabled) {
            if (updated.remoteTaskId == null) {
                enqueueCloudOperation(CloudSyncOperationType.Upload, updated.id, null)
            } else {
                enqueueCloudOperation(CloudSyncOperationType.UpdateResult, updated.id, updated.remoteTaskId)
            }
        }
        showToast("会议纪要已确认")
    }

    fun beginProcessingLatestTask(): ProcessingLaunch? {
        if (activeProcessingTask != null) return null
        val task = queuedImportTasks.firstOrNull() ?: return null
        return beginProcessingTask(task.id)
    }

    fun beginProcessingTask(taskId: String): ProcessingLaunch? {
        if (activeProcessingTask != null) return null
        if (currentCloudUserOrNull() == null) {
            showToast("请先登录账号")
            return null
        }
        if (membershipProfile.loading) {
            showToast("会员信息同步中，请稍后再试")
            return null
        }
        if (membershipProfile.transcriptionMinutesRemaining <= 0) {
            handleQuotaExhausted(HuixiaoApiException(402, "额度已耗尽，请充值后继续享受权益"))
            return null
        }
        val task = localTasks.firstOrNull { it.id == taskId } ?: return null
        if (
            task.status != MeetingTaskStatus.WaitingProcess &&
            task.status != MeetingTaskStatus.Failed &&
            task.status != MeetingTaskStatus.Canceled
        ) return null
        val retryRemote = task.status == MeetingTaskStatus.Failed ||
            (task.status == MeetingTaskStatus.WaitingProcess && task.progressStage == "waiting_retry" && task.remoteTaskId != null)
        val runId = System.currentTimeMillis()
        selectedTaskId = task.id
        cancelledProcessingTaskIds = cancelledProcessingTaskIds - task.id
        processingRunIds = processingRunIds + (task.id to runId)
        updateTask(
            task.copy(
                status = MeetingTaskStatus.Processing,
                errorMessage = null,
                remoteTaskId = if (task.status == MeetingTaskStatus.Canceled) null else task.remoteTaskId,
                progressPercent = if (task.remoteTaskId != null && !retryRemote && task.status != MeetingTaskStatus.Canceled) task.progressPercent else 0f,
                progressLabel = when {
                    retryRemote -> if (task.status == MeetingTaskStatus.WaitingProcess) "准备继续处理" else "准备重新处理"
                    task.status == MeetingTaskStatus.Canceled -> "等待上传"
                    task.remoteTaskId != null -> task.progressLabel ?: "恢复处理状态"
                    else -> "等待上传"
                },
                progressStage = when {
                    retryRemote -> "retrying"
                    task.status == MeetingTaskStatus.Canceled -> "uploading"
                    task.remoteTaskId != null -> task.progressStage ?: "resuming"
                    else -> "uploading"
                }
            )
        )
        return ProcessingLaunch(task.id, runId, retryRemote)
    }

    fun openProcessingTask(taskId: String): Boolean {
        val task = localTasks.firstOrNull { it.id == taskId && it.status == MeetingTaskStatus.Processing } ?: return false
        selectedTaskId = task.id
        return true
    }

    fun openLocalProcessingTask(taskId: String): Boolean {
        val task = localTasks.firstOrNull {
            it.id == taskId &&
                (it.status == MeetingTaskStatus.WaitingProcess || it.status == MeetingTaskStatus.Failed)
        } ?: return false
        selectedTaskId = task.id
        return true
    }

    fun resumeProcessingTaskIfNeeded(taskId: String): ProcessingLaunch? {
        val task = localTasks.firstOrNull { it.id == taskId && it.status == MeetingTaskStatus.Processing } ?: return null
        val existing = processingJobs[taskId]
        if (existing?.isActive == true) return null
        if (existing != null) processingJobs.remove(taskId)
        val runId = System.currentTimeMillis()
        selectedTaskId = task.id
        cancelledProcessingTaskIds = cancelledProcessingTaskIds - task.id
        processingRunIds = processingRunIds + (task.id to runId)
        return ProcessingLaunch(task.id, runId, retryRemote = false)
    }

    fun cancelProcessingTask(taskId: String): String? {
        cancelledProcessingTaskIds = cancelledProcessingTaskIds + taskId
        processingRunIds = processingRunIds - taskId
        processingJobs.remove(taskId)?.cancel()
        val task = localTasks.firstOrNull { it.id == taskId } ?: return null
        if (task.status == MeetingTaskStatus.Processing || task.status == MeetingTaskStatus.WaitingProcess) {
            updateTask(
                task.copy(
                    status = MeetingTaskStatus.Canceled,
                    errorMessage = "任务已终止",
                    remoteTaskId = null,
                    progressLabel = "已终止",
                    progressStage = "canceled"
                )
            )
        }
        return task.remoteTaskId
    }

    suspend fun cancelRemoteProcessingTask(remoteTaskId: String) {
        deleteTemporaryRemoteProcessingTask(remoteTaskId)
    }

    suspend fun cleanupTemporaryRemoteProcessingTasks() {
        val remoteTaskIds = localTasks
            .asSequence()
            .filter { it.status != MeetingTaskStatus.Finished }
            .mapNotNull { it.remoteTaskId?.takeIf { id -> id.isNotBlank() } }
            .distinct()
            .toList()
        remoteTaskIds.forEach { deleteTemporaryRemoteProcessingTask(it) }
    }

    fun attachProcessingJob(taskId: String, job: Job) {
        val existing = processingJobs[taskId]
        if (existing?.isActive == true) {
            job.cancel()
            return
        }
        processingJobs[taskId] = job
    }

    suspend fun processTask(taskId: String, runId: Long, retryRemote: Boolean = false): Boolean {
        val task = localTasks.firstOrNull { it.id == taskId } ?: return false
        if (task.status != MeetingTaskStatus.Processing || processingRunIds[taskId] != runId) return false
        var shouldUploadInBackground = false
        runCatching {
            val client = apiClient ?: error("处理服务未配置")
            var remoteTaskId = task.remoteTaskId
            var firstDetail: RemoteTaskDetail? = null
            if (remoteTaskId == null) {
                updateProcessingProgress(taskId, null, 0f, "正在上传文件", "uploading")
                val remoteTask = withContext(Dispatchers.IO) {
                    client.uploadTask(task, currentUserId(), persistToCloud = false, deviceId = deviceId)
                }
                remoteTaskId = remoteTask.id
                applyRemoteProgress(taskId, remoteTaskId, remoteTask, keepProcessingForWaiting = true)
            } else {
                val existing = withContext(Dispatchers.IO) { client.getTaskDetail(remoteTaskId, task, currentUserId()) }
                applyRemoteProgress(taskId, remoteTaskId, existing.task, keepProcessingForWaiting = true)
                firstDetail = existing
            }
            ensureProcessingStillCurrent(taskId, runId)
            val activeRemoteTaskId = remoteTaskId
            val currentBeforeStart = localTasks.firstOrNull { it.id == taskId } ?: error("本地任务不存在")
            val liveTranscriptsForProcessing = liveTranscriptsByTaskId[taskId]
                ?.takeIf { currentBeforeStart.source == MeetingTaskSource.Recording && it.isNotEmpty() }
            val started = when (firstDetail?.task?.status) {
                MeetingTaskStatus.Processing -> firstDetail
                MeetingTaskStatus.Finished -> firstDetail
                MeetingTaskStatus.Failed -> {
                    if (!retryRemote) error(firstDetail.task.errorMessage ?: "处理失败")
                    withContext(Dispatchers.IO) {
                        client.retryTaskProcessing(activeRemoteTaskId, currentBeforeStart, currentUserId(), liveTranscriptsForProcessing)
                    }
                }
                MeetingTaskStatus.WaitingProcess -> {
                    if (firstDetail.task.progressStage == "waiting_retry") {
                        if (!retryRemote) error(firstDetail.task.errorMessage ?: "处理暂未完成，稍后可继续")
                        withContext(Dispatchers.IO) {
                            client.retryTaskProcessing(activeRemoteTaskId, currentBeforeStart, currentUserId(), liveTranscriptsForProcessing)
                        }
                    } else {
                        withContext(Dispatchers.IO) {
                            client.startTaskProcessing(activeRemoteTaskId, currentBeforeStart, currentUserId(), liveTranscriptsForProcessing)
                        }
                    }
                }
                else -> withContext(Dispatchers.IO) {
                    client.startTaskProcessing(activeRemoteTaskId, currentBeforeStart, currentUserId(), liveTranscriptsForProcessing)
                }
            }
            applyRemoteProgress(taskId, activeRemoteTaskId, started.task, keepProcessingForWaiting = true)

            var finalResult: MeetingProcessingResult? = started.result
            var transientFailureRetries = 0
            while (finalResult == null) {
                ensureProcessingStillCurrent(taskId, runId)
                delay(1_000)
                val currentTask = localTasks.firstOrNull { it.id == taskId } ?: error("本地任务不存在")
                val detail = withContext(Dispatchers.IO) { client.getTaskDetail(activeRemoteTaskId, currentTask, currentUserId()) }
                applyRemoteProgress(taskId, activeRemoteTaskId, detail.task, keepProcessingForWaiting = true)
                when (detail.task.status) {
                    MeetingTaskStatus.Finished -> finalResult = detail.result ?: withContext(Dispatchers.IO) {
                        client.getTaskResult(activeRemoteTaskId, currentTask, currentUserId())
                    }
                    MeetingTaskStatus.Failed -> {
                        val errorMessage = detail.task.errorMessage ?: "处理失败"
                        if (transientFailureRetries < PROCESSING_TRANSIENT_RETRY_LIMIT && errorMessage.isTransientProcessingFailure()) {
                            transientFailureRetries += 1
                            updateProcessingProgress(
                                taskId,
                                activeRemoteTaskId,
                                detail.task.progressPercent.coerceAtLeast(8f),
                                "处理暂未完成，正在继续",
                                "auto_retrying"
                            )
                            val retryDetail = runCatching {
                                withContext(Dispatchers.IO) {
                                    client.retryTaskProcessing(activeRemoteTaskId, currentTask, currentUserId(), liveTranscriptsForProcessing)
                                }
                            }.getOrElse { retryError ->
                                if (retryError.userFacingMessage("处理失败").isTransientProcessingFailure()) {
                                    updateTask(
                                        currentTask.copy(
                                            status = MeetingTaskStatus.WaitingProcess,
                                            errorMessage = "处理暂未完成，稍后可继续",
                                            progressLabel = "可继续处理",
                                            progressStage = "waiting_retry"
                                        )
                                    )
                                }
                                throw retryError
                            }
                            applyRemoteProgress(taskId, activeRemoteTaskId, retryDetail.task, keepProcessingForWaiting = true)
                            finalResult = retryDetail.result
                        } else {
                            error(errorMessage)
                        }
                    }
                    MeetingTaskStatus.WaitingProcess -> {
                        if (detail.task.progressStage == "waiting_retry") {
                            error(detail.task.errorMessage ?: detail.task.progressLabel ?: "处理暂未完成，稍后可继续")
                        }
                    }
                    MeetingTaskStatus.Canceled -> throw CancellationException(detail.task.errorMessage ?: "任务已终止")
                    else -> Unit
                }
            }
            finalResult
        }.onSuccess { result ->
            val current = localTasks.firstOrNull { it.id == taskId } ?: return@onSuccess
            if (processingRunIds[taskId] != runId || taskId in cancelledProcessingTaskIds) return@onSuccess
            val finalTask = current.copy(
                status = MeetingTaskStatus.Finished,
                errorMessage = null,
                remoteTaskId = null,
                progressPercent = 100f,
                progressLabel = "处理完成",
                progressStage = "finished",
                syncStatus = if (cloudSyncEnabled) CloudSyncStatus.PendingUpload else CloudSyncStatus.LocalOnly,
                knowledgeScope = KnowledgeIndexScope.Local
            )
            val finalResult = result.copy(remoteTaskId = null).normalizedForTask(finalTask)
            resultStore?.saveResult(finalResult)
            localKnowledgeStore?.replaceMeetingIndex(finalTask, finalResult)
            val remoteToDelete = result.remoteTaskId ?: current.remoteTaskId
            if (remoteToDelete != null) {
                deleteTemporaryRemoteProcessingTask(remoteToDelete)
            }
            processingRunIds = processingRunIds - taskId
            processingJobs.remove(taskId)
            liveTranscriptsByTaskId = liveTranscriptsByTaskId - taskId
            updateTask(finalTask)
            selectedMeetingId = finalTask.id
            if (cloudSyncEnabled) {
                enqueueCloudOperation(CloudSyncOperationType.Upload, finalTask.id, null)
                shouldUploadInBackground = true
            }
            runCatching { refreshMembershipProfile() }
        }.onFailure { error ->
            if (error is CancellationException) {
                if (processingRunIds[taskId] != runId && taskId !in cancelledProcessingTaskIds) return@onFailure
                processingRunIds = processingRunIds - taskId
                processingJobs.remove(taskId)
                val current = localTasks.firstOrNull { it.id == taskId } ?: return@onFailure
                if (current.status == MeetingTaskStatus.Processing || current.status == MeetingTaskStatus.WaitingProcess) {
                    updateTask(
                        current.copy(
                            status = MeetingTaskStatus.Canceled,
                            errorMessage = error.userFacingStateMessage("任务已终止"),
                            progressLabel = "已终止",
                            progressStage = "canceled"
                        )
                    )
                }
                return@onFailure
            }
            val current = localTasks.firstOrNull { it.id == taskId } ?: return@onFailure
            if (processingRunIds[taskId] != runId || taskId in cancelledProcessingTaskIds) return@onFailure
            processingRunIds = processingRunIds - taskId
            processingJobs.remove(taskId)
            val isAuthFailure = handleAuthFailure(error)
            val isQuotaExhausted = handleQuotaExhausted(error)
            val cleanError = error.userFacingStateMessage("处理失败")
            val isTransientFailure = !isAuthFailure && !isQuotaExhausted && cleanError.isTransientProcessingFailure()
            updateTask(
                current.copy(
                    status = if (isAuthFailure || isQuotaExhausted || isTransientFailure) MeetingTaskStatus.WaitingProcess else MeetingTaskStatus.Failed,
                    errorMessage = if (isTransientFailure) {
                        "处理暂未完成，稍后可继续"
                    } else {
                        cleanError
                    },
                    progressLabel = when {
                        isAuthFailure -> "等待登录"
                        isQuotaExhausted -> "等待充值"
                        isTransientFailure -> "可继续处理"
                        else -> "处理失败"
                    },
                    progressStage = when {
                        isAuthFailure -> "waiting_login"
                        isQuotaExhausted -> "waiting_payment"
                        isTransientFailure -> "waiting_retry"
                        else -> "failed"
                    }
                )
            )
        }
        return shouldUploadInBackground
    }

    private fun ensureProcessingStillCurrent(taskId: String, runId: Long) {
        if (processingRunIds[taskId] != runId || taskId in cancelledProcessingTaskIds) {
            throw CancellationException("任务已终止")
        }
    }

    private fun updateProcessingProgress(
        taskId: String,
        remoteTaskId: String?,
        progressPercent: Float,
        progressLabel: String,
        progressStage: String
    ) {
        val current = localTasks.firstOrNull { it.id == taskId } ?: return
        updateTask(
            current.copy(
                status = MeetingTaskStatus.Processing,
                remoteTaskId = remoteTaskId ?: current.remoteTaskId,
                progressPercent = progressPercent.coerceIn(0f, 100f),
                progressLabel = progressLabel,
                progressStage = progressStage
            )
        )
    }

    private fun applyRemoteProgress(
        taskId: String,
        remoteTaskId: String,
        remote: com.huiyi.app.data.RemoteTaskSnapshot,
        keepProcessingForWaiting: Boolean
    ) {
        val current = localTasks.firstOrNull { it.id == taskId } ?: return
        val waitingForRetry = remote.status == MeetingTaskStatus.WaitingProcess && remote.progressStage == "waiting_retry"
        val nextStatus = if (keepProcessingForWaiting && remote.status == MeetingTaskStatus.WaitingProcess && !waitingForRetry) {
            MeetingTaskStatus.Processing
        } else if (remote.status == MeetingTaskStatus.Finished && resultStore?.loadResultForTask(current) == null) {
            MeetingTaskStatus.Processing
        } else {
            remote.status
        }
        val nextProgress = if (nextStatus == MeetingTaskStatus.Processing && remote.status == MeetingTaskStatus.Finished) {
            96f
        } else {
            remote.progressPercent
        }
        val nextLabel = if (nextStatus == MeetingTaskStatus.Processing && remote.status == MeetingTaskStatus.Finished) {
            "正在保存本机结果"
        } else {
            remote.progressLabel
        }
        val nextStage = if (nextStatus == MeetingTaskStatus.Processing && remote.status == MeetingTaskStatus.Finished) {
            "saving_local"
        } else {
            remote.progressStage
        }
        updateTask(
            current.copy(
                status = nextStatus,
                remoteTaskId = remoteTaskId,
                errorMessage = if (nextStatus == MeetingTaskStatus.Processing && remote.status == MeetingTaskStatus.WaitingProcess) null else remote.errorMessage,
                progressPercent = nextProgress,
                progressLabel = nextLabel,
                progressStage = nextStage
            )
        )
    }

    private suspend fun deleteTemporaryRemoteProcessingTask(remoteTaskId: String) {
        val client = apiClient ?: return
        runCatching {
            withContext(Dispatchers.IO) {
                client.deleteTask(remoteTaskId, currentUserId())
            }
        }
    }

    fun updateKnowledgeQuestion(question: String) {
        knowledgeQuestion = question
    }

    suspend fun askKnowledge(question: String = knowledgeQuestion) {
        val cleanQuestion = question.trim()
        if (cleanQuestion.isBlank()) {
            showToast("请输入要查询的问题")
            return
        }
        if (knowledgeLoading) {
            showToast("知识库正在回答")
            return
        }
        val client = apiClient ?: run {
            showToast("知识库服务未配置")
            return
        }
        if (currentCloudUserOrNull() == null) {
            showToast("请先登录账号")
            return
        }
        if (membershipProfile.loading) {
            showToast("会员信息同步中，请稍后再试")
            return
        }
        if (membershipProfile.knowledgeQaRemaining <= 0) {
            handleQuotaExhausted(HuixiaoApiException(402, "额度已耗尽，请充值后继续享受权益"))
            return
        }
        val messageSeed = System.currentTimeMillis()
        knowledgeMessages = knowledgeMessages + KnowledgeChatMessage(
            id = "user-$messageSeed",
            role = KnowledgeChatRole.User,
            text = cleanQuestion
        )
        knowledgeQuestion = ""
        knowledgeLoading = true
        activeKnowledgeQuestion = cleanQuestion
        val requestId = nextKnowledgeRequestId()
        val localSources = localKnowledgeStore?.list(KnowledgeQueryScope.Local).orEmpty()
        val contextTaskIds = lastKnowledgeContextTaskIds()
        val contextMessages = knowledgeContextMessages()
        try {
            val result = withContext(Dispatchers.IO) {
                client.askKnowledge(
                    cleanQuestion,
                    currentUserId(),
                    profileName.trim().ifBlank { null },
                    KnowledgeQueryScope.Local,
                    localSources,
                    emptyList(),
                    contextTaskIds,
                    contextMessages
                )
            }
            if (!isCurrentKnowledgeRequest(requestId)) return
            knowledgeMessages = knowledgeMessages + KnowledgeChatMessage(
                id = "assistant-${System.currentTimeMillis()}",
                role = KnowledgeChatRole.Assistant,
                text = result.answer,
                sources = result.sources.sanitizedSources()
            )
            activeKnowledgeQuestion = null
            showToast("知识库回答已生成")
            runCatching { refreshMembershipProfile() }
        } catch (error: CancellationException) {
            if (isCurrentKnowledgeRequest(requestId)) {
                knowledgeMessages = knowledgeMessages + KnowledgeChatMessage(
                    id = "assistant-stopped-${System.currentTimeMillis()}",
                    role = KnowledgeChatRole.Assistant,
                    text = "已停止回答",
                    retryQuestion = cleanQuestion
                )
                activeKnowledgeQuestion = null
            }
        } catch (error: Throwable) {
            if (!isCurrentKnowledgeRequest(requestId)) return
            handleAuthFailure(error)
            val quotaExhausted = handleQuotaExhausted(error)
            knowledgeMessages = knowledgeMessages + KnowledgeChatMessage(
                id = "assistant-error-${System.currentTimeMillis()}",
                role = KnowledgeChatRole.Assistant,
                text = error.userFacingStateMessage(if (quotaExhausted) "额度已耗尽，请充值后继续享受权益" else "知识库问答失败"),
                failed = true
            )
            activeKnowledgeQuestion = null
            if (!quotaExhausted) {
                showToast("知识库问答失败")
            }
        } finally {
            if (isCurrentKnowledgeRequest(requestId)) {
                knowledgeLoading = false
            }
        }
    }

    fun cancelKnowledgeAnswer() {
        if (!knowledgeLoading) return
        val retryQuestion = activeKnowledgeQuestion
        knowledgeRequestId += 1
        knowledgeLoading = false
        activeKnowledgeQuestion = null
        knowledgeMessages = knowledgeMessages + KnowledgeChatMessage(
            id = "assistant-stopped-${System.currentTimeMillis()}",
            role = KnowledgeChatRole.Assistant,
            text = "已停止回答",
            retryQuestion = retryQuestion
        )
        showToast("已停止回答")
    }

    private fun nextKnowledgeRequestId(): Long {
        knowledgeRequestId += 1
        return knowledgeRequestId
    }

    private fun isCurrentKnowledgeRequest(requestId: Long): Boolean {
        return knowledgeRequestId == requestId
    }

    private fun lastKnowledgeContextTaskIds(): List<String> {
        return knowledgeMessages
            .asReversed()
            .firstOrNull { message -> message.role == KnowledgeChatRole.Assistant && message.sources.isNotEmpty() }
            ?.sources
            ?.mapNotNull { source -> source.taskId.cleanNullableText() }
            ?.distinct()
            ?: emptyList()
    }

    private fun knowledgeContextMessages(): List<KnowledgeChatContextItem> {
        return knowledgeMessages
            .takeLast(6)
            .map { message ->
                KnowledgeChatContextItem(
                    role = when (message.role) {
                        KnowledgeChatRole.User -> "user"
                        KnowledgeChatRole.Assistant -> "assistant"
                    },
                    text = message.text,
                    sources = message.sources
                )
            }
    }

    private fun List<RemoteKnowledgeSource>.sanitizedSources(): List<RemoteKnowledgeSource> {
        return mapNotNull { source ->
            val cleanTitle = source.title.cleanNullableText()
            val cleanText = source.text.cleanNullableText()
            val cleanSpeaker = source.speaker.cleanNullableText()
            val cleanTimestamp = source.timestamp.cleanNullableText()
            if (cleanTitle == null && cleanText == null && cleanSpeaker == null && cleanTimestamp == null) {
                null
            } else {
                source.copy(
                    chunkId = source.chunkId,
                    taskId = source.taskId,
                    title = cleanTitle ?: "会议记录",
                    text = cleanText.orEmpty(),
                    chunkType = source.chunkType.cleanNullableText(),
                    meetingDate = source.meetingDate.cleanNullableText(),
                    speaker = cleanSpeaker,
                    timestamp = cleanTimestamp
                )
            }
        }.take(8)
    }

    private fun String?.cleanNullableText(): String? {
        val clean = this?.trim().orEmpty()
        return clean.takeUnless { it.isBlank() || it.equals("null", ignoreCase = true) }
    }

    private fun String?.cleanTodoRequiredText(): String? {
        val clean = cleanNullableText() ?: return null
        val normalized = clean.lowercase(Locale.ROOT).replace(Regex("\\s+"), "")
        val placeholders = setOf(
            "未指定",
            "待确认",
            "待补充",
            "暂无",
            "无",
            "none",
            "unknown",
            "n/a",
            "na",
            "-"
        )
        val placeholderPrefixes = listOf("未指定", "待确认", "待补充", "暂无", "无明确", "未明确", "未知", "待定")
        return clean.takeUnless { normalized in placeholders || placeholderPrefixes.any { prefix -> normalized.startsWith(prefix) } }
    }

    private fun MeetingProcessingResult.renamedSpeaker(speakerId: String, original: String, target: String): MeetingProcessingResult {
        val targetSpeakerId = transcripts.speakerIdentities()
            .firstOrNull { it.id != speakerId && it.displayName == target }
            ?.id
            ?: speakerId
        return copy(
            transcripts = transcripts.map { segment ->
                if (segment.stableSpeakerId == speakerId) segment.copy(speaker = target, speakerId = targetSpeakerId) else segment
            },
            todos = todos.map { item -> item.renamedSpeakerAssignee(speakerId, original, target, targetSpeakerId) }
        )
    }

    private fun Meeting.renamedSpeaker(speakerId: String, original: String, target: String): Meeting {
        val targetSpeakerId = transcripts.speakerIdentities()
            .firstOrNull { it.id != speakerId && it.displayName == target }
            ?.id
            ?: speakerId
        return copy(
            todos = todos.map { item -> item.renamedSpeakerAssignee(speakerId, original, target, targetSpeakerId) },
            transcripts = transcripts.map { segment ->
                if (segment.stableSpeakerId == speakerId) segment.copy(speaker = target, speakerId = targetSpeakerId) else segment
            }
        )
    }

    private fun TodoItem.renamedSpeakerAssignee(speakerId: String, original: String, target: String, targetSpeakerId: String): TodoItem {
        val matchesAssignee = assigneeId.cleanNullableText() == speakerId || assigneeName.cleanNullableText() == original
        if (!matchesAssignee) return this
        return copy(assigneeName = target, assigneeId = targetSpeakerId)
    }

    private fun MeetingProcessingResult.toMeetingSnapshot(task: MeetingTask): Meeting {
        val speakerCount = transcripts.speakerIdentities().size
        val defaultParticipants = if (speakerCount > 0) "说话人 $speakerCount 位" else "未识别说话人"
        return Meeting(
            id = task.id,
            remoteTaskId = task.remoteTaskId ?: remoteTaskId,
            title = task.title.substringBeforeLast('.', task.title),
            timeLabel = task.createdAtLabel,
            createdAtMillis = task.createdAtMillis,
            participants = participants?.takeIf { it.isNotBlank() } ?: defaultParticipants,
            durationLabel = task.sizeLabel ?: "本地文件",
            tags = tags.filterUsefulMeetingTags(),
            status = if (task.confirmed) com.huiyi.app.data.MeetingStatus.Generated else com.huiyi.app.data.MeetingStatus.PendingConfirm,
            progress = 1f,
            summary = summary,
            topics = topics,
            decisions = decisions,
            todos = todos,
            risks = risks,
            transcripts = transcripts,
            sourceFilePath = sourceFilePath,
            generatedAtLabel = generatedAtLabel
        )
    }

    private fun Long.formatDateTimeLabel(): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(this))
    }

    private fun List<String>.filterUsefulMeetingTags(): List<String> {
        val hidden = setOf("真实转写", "AI纪要", "导入", "录音")
        return map { it.trim() }
            .filter { it.isNotBlank() && it !in hidden }
            .distinct()
    }

    private fun String.parseTodoDueMillis(): Long? {
        val clean = trim()
        if (clean.isBlank() || clean == "待确认" || clean.equals("null", ignoreCase = true)) return null
        val relative = clean.parseRelativeDueMillis()
        if (relative != null) return relative
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

    private fun String.parseRelativeDueMillis(): Long? {
        val calendar = java.util.Calendar.getInstance()
        fun endOfDay(): Long {
            calendar.set(java.util.Calendar.HOUR_OF_DAY, 23)
            calendar.set(java.util.Calendar.MINUTE, 59)
            calendar.set(java.util.Calendar.SECOND, 0)
            calendar.set(java.util.Calendar.MILLISECOND, 0)
            return calendar.timeInMillis
        }
        when {
            contains("今天") -> return endOfDay()
            contains("明天") -> {
                calendar.add(java.util.Calendar.DAY_OF_YEAR, 1)
                return endOfDay()
            }
            contains("后天") -> {
                calendar.add(java.util.Calendar.DAY_OF_YEAR, 2)
                return endOfDay()
            }
            contains("月底") || contains("本月底") -> {
                calendar.set(java.util.Calendar.DAY_OF_MONTH, calendar.getActualMaximum(java.util.Calendar.DAY_OF_MONTH))
                return endOfDay()
            }
        }
        val weekMap = mapOf("一" to java.util.Calendar.MONDAY, "二" to java.util.Calendar.TUESDAY, "三" to java.util.Calendar.WEDNESDAY, "四" to java.util.Calendar.THURSDAY, "五" to java.util.Calendar.FRIDAY, "六" to java.util.Calendar.SATURDAY, "日" to java.util.Calendar.SUNDAY, "天" to java.util.Calendar.SUNDAY)
        val key = Regex("下周([一二三四五六日天])").find(this)?.groupValues?.getOrNull(1)
        val target = key?.let { weekMap[it] } ?: return null
        val current = calendar.get(java.util.Calendar.DAY_OF_WEEK)
        val daysToNextMonday = ((java.util.Calendar.MONDAY - current + 7) % 7).let { if (it == 0) 7 else it }
        val targetOffset = if (target == java.util.Calendar.SUNDAY) 6 else target - java.util.Calendar.MONDAY
        calendar.add(java.util.Calendar.DAY_OF_YEAR, daysToNextMonday + targetOffset)
        return endOfDay()
    }

    private fun mergeRegeneratedTodos(
        previousTodos: List<TodoItem>,
        regeneratedTodos: List<TodoItem>
    ): List<TodoItem> {
        val completedTodos = previousTodos.filter { it.effectiveStatus == TodoStatus.Done }
        val canceledTodos = previousTodos.filter { it.effectiveStatus == TodoStatus.Canceled }
        val manualTodos = previousTodos.filter { TodoLockedFieldManual in it.lockedFields }
        val preservedTodos = (manualTodos + completedTodos + canceledTodos).distinctBy { it.id }
        if (preservedTodos.isEmpty()) return regeneratedTodos
        val activeGenerated = regeneratedTodos.filterNot { generated ->
            completedTodos.any { completed -> completed.matchesCompletedRegeneratedTodo(generated) } ||
                canceledTodos.any { canceled -> canceled.matchesCompletedRegeneratedTodo(generated) }
        }
        return activeGenerated + preservedTodos
    }

    private fun TodoItem.matchesCompletedRegeneratedTodo(regenerated: TodoItem): Boolean {
        val titleScore = todoTextSimilarity(title, regenerated.title)
        if (titleScore >= 0.96f && todoAssigneeCompatible(regenerated)) return true
        val sameSourceIndex = sourceSegmentIndex != null &&
            regenerated.sourceSegmentIndex != null &&
            sourceSegmentIndex == regenerated.sourceSegmentIndex
        if (sameSourceIndex && titleScore >= 0.58f) return true
        val sameSourceTimestamp = sourceTimestampLabel != null &&
            regenerated.sourceTimestampLabel != null &&
            sourceTimestampLabel == regenerated.sourceTimestampLabel
        if (sameSourceTimestamp && titleScore >= 0.58f) return true
        val sourceScore = todoTextSimilarity(
            listOf(sourceLabel.orEmpty(), description).joinToString(" "),
            listOf(regenerated.sourceLabel.orEmpty(), regenerated.description).joinToString(" ")
        )
        return titleScore >= 0.84f && sourceScore >= 0.36f && todoAssigneeCompatible(regenerated)
    }

    private fun TodoItem.todoAssigneeCompatible(other: TodoItem): Boolean {
        val left = assigneeLabel.normalizedTodoMatchText()
        val right = other.assigneeLabel.normalizedTodoMatchText()
        if (left.isBlank() || right.isBlank()) return true
        return left == right || left in right || right in left
    }

    private fun todoTextSimilarity(left: String, right: String): Float {
        val a = left.normalizedTodoMatchText()
        val b = right.normalizedTodoMatchText()
        if (a.isBlank() || b.isBlank()) return 0f
        if (a == b) return 1f
        val shorter = if (a.length <= b.length) a else b
        val longer = if (a.length > b.length) a else b
        if (shorter.length >= 6 && shorter in longer) {
            return shorter.length.toFloat() / longer.length.toFloat()
        }
        val leftBigrams = a.todoBigrams()
        val rightBigrams = b.todoBigrams()
        if (leftBigrams.isEmpty() || rightBigrams.isEmpty()) return 0f
        val overlap = leftBigrams.entries.sumOf { entry -> minOf(entry.value, rightBigrams[entry.key] ?: 0) }
        return (2f * overlap) / (leftBigrams.values.sum() + rightBigrams.values.sum())
    }

    private fun String?.normalizedTodoMatchText(): String {
        return this
            ?.lowercase(Locale.ROOT)
            ?.replace(Regex("[\\p{P}\\p{S}\\s]+"), "")
            ?.replace("需要", "")
            ?.replace("负责", "")
            ?.replace("处理", "")
            ?.replace("完成", "")
            ?.trim()
            .orEmpty()
    }

    private fun String.todoBigrams(): Map<String, Int> {
        if (length <= 1) return if (isBlank()) emptyMap() else mapOf(this to 1)
        val counts = mutableMapOf<String, Int>()
        for (index in 0 until length - 1) {
            val token = substring(index, index + 2)
            counts[token] = (counts[token] ?: 0) + 1
        }
        return counts
    }

    suspend fun exportSelectedMeetingText(format: String = "markdown", includeTranscript: Boolean = false): String {
        val meeting = selectedMeeting
        return if (format.equals("txt", ignoreCase = true)) {
            meeting.toPlainText(includeTranscript)
        } else {
            meeting.toMarkdownText(includeTranscript)
        }
    }

    private suspend fun updateSelectedResult(
        syncServer: Boolean,
        taskTransform: (MeetingTask) -> MeetingTask = { it },
        transform: (MeetingProcessingResult) -> MeetingProcessingResult
    ) {
        val store = resultStore ?: return
        val task = selectedLocalFinishedTask() ?: return
        val current = store.loadResultForTask(task) ?: return
        var updated = transform(current)
        val remoteTaskId = updated.remoteTaskId ?: task.remoteTaskId
        updated = updated.normalizedForTask(task)
        store.saveResult(updated)
        val transformedTask = taskTransform(task).copy(remoteTaskId = updated.remoteTaskId ?: task.remoteTaskId)
        localKnowledgeStore?.replaceMeetingIndex(transformedTask, updated)
        lastSelectedMeetingSnapshot = updated.toMeetingSnapshot(transformedTask)
        val baseTask = transformedTask
        val shouldSync = syncServer && apiClient != null && currentCloudUserOrNull() != null
        val nextTask = if (shouldSync) {
            baseTask.copy(syncStatus = CloudSyncStatus.PendingUpload, errorMessage = null)
        } else {
            baseTask
        }
        updateTask(nextTask)
        if (shouldSync && remoteTaskId != null) {
            enqueueCloudOperation(CloudSyncOperationType.UpdateResult, nextTask.id, remoteTaskId)
        } else if (shouldSync && cloudSyncEnabled) {
            enqueueCloudOperation(CloudSyncOperationType.Upload, nextTask.id, null)
        }
    }

    private suspend fun updateResultContainingTodo(
        todo: TodoItem,
        syncServer: Boolean,
        transform: (MeetingProcessingResult) -> MeetingProcessingResult
    ): Boolean {
        val store = resultStore ?: return false
        val todoMeetingId = todo.meetingIdLabel
        val task = localTasks.firstOrNull { task ->
            task.id == todoMeetingId && store.loadResultForTask(task)?.todos?.any { it.id == todo.id } == true
        } ?: localTasks.firstOrNull { task ->
            store.loadResultForTask(task)?.todos?.any { it.id == todo.id } == true
        } ?: run {
            showToast("待办来源会议不存在")
            return false
        }
        val current = store.loadResultForTask(task) ?: return false
        var updated = transform(current)
        val remoteTaskId = updated.remoteTaskId ?: task.remoteTaskId
        updated = updated.normalizedForTask(task)
        store.saveResult(updated)
        localKnowledgeStore?.replaceMeetingIndex(task, updated)
        val baseTask = task.copy(remoteTaskId = updated.remoteTaskId ?: task.remoteTaskId)
        val shouldSync = syncServer && apiClient != null && cloudUser != null
        val nextTask = if (shouldSync) {
            baseTask.copy(syncStatus = CloudSyncStatus.PendingUpload, errorMessage = null)
        } else {
            baseTask
        }
        updateTask(nextTask)
        if (shouldSync && remoteTaskId != null) {
            enqueueCloudOperation(CloudSyncOperationType.UpdateResult, nextTask.id, remoteTaskId)
        } else if (shouldSync && cloudSyncEnabled) {
            enqueueCloudOperation(CloudSyncOperationType.Upload, nextTask.id, null)
        }
        return true
    }

    suspend fun clearCloudDataIfLoggedIn(): Boolean {
        val client = apiClient ?: return false
        val user = currentCloudUserOrNull() ?: return false
        withContext(Dispatchers.IO) { client.clearUserCloudData(user) }
        clearCachedSpeakerProfiles(user.userId)
        return true
    }

    suspend fun deleteMeeting(id: String): Boolean {
        val wasSelectedDetail = selectedMeetingId == id && screen == AppScreen.Detail
        val task = localTasks.firstOrNull { it.id == id }
        if (task?.status == MeetingTaskStatus.Processing) {
            showToast("正在处理的文件不能删除，请先终止任务")
            return false
        }
        if (selectedMeetingId == id) {
            selectedMeetingId = recentMeetings.firstOrNull { it.id != id }?.id.orEmpty()
            if (lastSelectedMeetingSnapshot?.id == id) {
                lastSelectedMeetingSnapshot = null
            }
            if (wasSelectedDetail) {
                screen = detailReturnScreen.returnTargetForTransientScreen()
            }
        }
        if (task != null) {
            if (!deleteLocalTask(task)) return false
        }
        deletedMeetingIds = deletedMeetingIds + id
        return true
    }

    fun requestDeleteMeeting(meetingId: String) {
        val meeting = recentMeetings.firstOrNull { it.id == meetingId }
            ?: selectedMeetingOrNull?.takeIf { it.id == meetingId }
            ?: lastSelectedMeetingSnapshot?.takeIf { it.id == meetingId }
            ?: return
        pendingDeleteMeetingId = meeting.id
        pendingDeleteMeetingSnapshot = meeting
        showSheet(SheetType.DeleteMeeting)
    }

    suspend fun deletePendingMeeting(): Boolean {
        val id = pendingDeleteMeetingId ?: pendingDeleteMeetingSnapshot?.id ?: return false
        val deleted = deleteMeeting(id)
        if (deleted) {
            pendingDeleteMeetingId = null
            pendingDeleteMeetingSnapshot = null
        }
        return deleted
    }

    suspend fun deleteMeetings(ids: Collection<String>): Int {
        var deletedCount = 0
        ids.distinct().forEach { id ->
            if (deleteMeeting(id)) deletedCount += 1
        }
        return deletedCount
    }

    suspend fun deleteLocalTask(task: MeetingTask): Boolean {
        if (task.status == MeetingTaskStatus.Processing) {
            showToast("正在处理的文件不能删除，请先终止任务")
            return false
        }
        task.remoteTaskId?.let { remoteTaskId ->
            enqueueCloudOperation(CloudSyncOperationType.Delete, task.id, remoteTaskId)
        }
        resultStore?.deleteResult(task.id)
        task.remoteTaskId?.let { resultStore?.deleteResult(it) }
        localKnowledgeStore?.deleteMeetingIndex(task.id)
        task.remoteTaskId?.let { localKnowledgeStore?.deleteMeetingIndex(it) }
        withContext(Dispatchers.IO) { deleteOwnedLocalFile(task.localFilePath) }
        localTasks = localTasks.filterNot { it.id == task.id }
        if (selectedTaskId == task.id) selectedTaskId = null
        processingRunIds = processingRunIds - task.id
        liveTranscriptsByTaskId = liveTranscriptsByTaskId - task.id
        processingJobs.remove(task.id)?.cancel()
        persistTasks()
        return true
    }

    suspend fun closeGeneratingTask() {
        val task = selectedTask
        if (task != null && (task.status == MeetingTaskStatus.Failed || task.status == MeetingTaskStatus.Canceled)) {
            deleteLocalTask(task)
        }
        screen = generatingReturnScreen.returnTargetForTransientScreen()
    }

    fun requestDeleteTask(task: MeetingTask) {
        pendingDeleteTaskId = task.id
        showSheet(SheetType.DeleteTask)
    }

    suspend fun deletePendingTask(): Boolean {
        val task = pendingDeleteTask ?: return false
        val deleted = deleteLocalTask(task)
        if (deleted) pendingDeleteTaskId = null
        return deleted
    }

    fun clearLocalTasks() {
        processingJobs.values.forEach { it.cancel() }
        processingJobs.clear()
        processingRunIds = emptyMap()
        localTasks = emptyList()
        deletedMeetingIds = emptySet()
        cloudSyncOperations = emptyList()
        localKnowledgeStore?.clearAll()
        scheduledMeetings = listOf(repository.getHomeDashboard().todayMeeting)
            .filter { it.title.isNotBlank() }
        selectedTaskId = null
        persistTasks()
        persistSchedules()
        persistCloudSyncOperations()
    }

    private fun updateTask(task: MeetingTask) {
        localTasks = localTasks.map { if (it.id == task.id) task else it }
        persistTasks()
    }

    private fun upsertTask(task: MeetingTask) {
        localTasks = listOf(task) + localTasks.filterNot { it.id == task.id }
        persistTasks()
    }

    private fun finishedTaskForMeetingId(id: String): MeetingTask? {
        return localTasks.firstOrNull {
            it.status == MeetingTaskStatus.Finished && (it.id == id || it.remoteTaskId == id)
        }
    }

    private fun selectedLocalFinishedTask(): MeetingTask? {
        finishedTaskForMeetingId(selectedMeetingId)?.let { return it }
        val remoteTaskId = selectedMeetingOrNull?.remoteTaskId ?: lastSelectedMeetingSnapshot?.remoteTaskId
        return remoteTaskId?.let { finishedTaskForMeetingId(it) }
    }

    private fun persistTasks() {
        taskStore?.saveTasks(localTasks)
    }

    private fun persistSchedules() {
        scheduleStore?.saveSchedules(scheduledMeetings)
    }

    private fun persistCloudSyncOperations() {
        val userId = cloudUser?.userId ?: return
        cloudSyncOperationStore?.saveOperations(userId, cloudSyncOperations)
    }

    private fun beginCloudSync(message: String) {
        cloudSyncBusyCount += 1
        cloudSyncStatusText = message
    }

    private fun finishCloudSync(message: String) {
        cloudSyncStatusText = message
    }

    private fun failCloudSync(error: Throwable) {
        cloudSyncStatusText = "同步失败：${error.userFacingStateMessage("网络连接失败，请检查网络后重试")}"
    }

    private fun endCloudSync() {
        cloudSyncBusyCount = (cloudSyncBusyCount - 1).coerceAtLeast(0)
    }

    private fun enqueueCloudOperation(type: CloudSyncOperationType, localTaskId: String, remoteTaskId: String?) {
        val userId = cloudUser?.userId ?: return
        val normalizedRemoteTaskId = remoteTaskId?.takeIf { it.isNotBlank() }
        val next = when (type) {
            CloudSyncOperationType.Upload -> {
                if (normalizedRemoteTaskId != null) return
                cloudSyncOperations
                    .filterNot { it.localTaskId == localTaskId && it.type in setOf(CloudSyncOperationType.Upload, CloudSyncOperationType.UpdateResult) }
                    .plus(CloudSyncOperation(id = "upload-$userId-$localTaskId", type = type, localTaskId = localTaskId, userId = userId))
            }
            CloudSyncOperationType.UpdateResult -> {
                val remoteId = normalizedRemoteTaskId ?: return
                if (cloudSyncOperations.any { it.localTaskId == localTaskId && it.type == CloudSyncOperationType.Upload }) return
                cloudSyncOperations
                    .filterNot { it.localTaskId == localTaskId && it.type == CloudSyncOperationType.UpdateResult }
                    .plus(CloudSyncOperation(id = "update-$userId-$localTaskId-$remoteId", type = type, localTaskId = localTaskId, remoteTaskId = remoteId, userId = userId))
            }
            CloudSyncOperationType.Delete -> {
                val remoteId = normalizedRemoteTaskId ?: return
                cloudSyncOperations
                    .filterNot { it.localTaskId == localTaskId || it.remoteTaskId == remoteId }
                    .plus(CloudSyncOperation(id = "delete-$userId-$remoteId", type = type, localTaskId = localTaskId, remoteTaskId = remoteId, userId = userId))
            }
            CloudSyncOperationType.UpsertSchedule -> {
                cloudSyncOperations
                    .filterNot { it.localTaskId == localTaskId && it.type in setOf(CloudSyncOperationType.UpsertSchedule, CloudSyncOperationType.DeleteSchedule) }
                    .plus(CloudSyncOperation(id = "upsert-schedule-$userId-$localTaskId", type = type, localTaskId = localTaskId, remoteTaskId = normalizedRemoteTaskId, userId = userId))
            }
            CloudSyncOperationType.DeleteSchedule -> {
                cloudSyncOperations
                    .filterNot { it.localTaskId == localTaskId && it.type in setOf(CloudSyncOperationType.UpsertSchedule, CloudSyncOperationType.DeleteSchedule) }
                    .plus(CloudSyncOperation(id = "delete-schedule-$userId-$localTaskId", type = type, localTaskId = localTaskId, remoteTaskId = normalizedRemoteTaskId, userId = userId))
            }
        }
        cloudSyncOperations = next.sortedBy { it.createdAtMillis }
        persistCloudSyncOperations()
    }

    private fun completeCloudOperation(operation: CloudSyncOperation) {
        cloudSyncOperations = cloudSyncOperations.filterNot { it.id == operation.id }
        persistCloudSyncOperations()
    }

    private fun failCloudOperation(operation: CloudSyncOperation, error: Throwable) {
        cloudSyncOperations = cloudSyncOperations.map {
            if (it.id == operation.id) it.copy(lastError = error.userFacingStateMessage("同步失败")) else it
        }
        persistCloudSyncOperations()
    }

    private fun enqueueSyncForPendingLocalTasks() {
        localTasks
            .filter { it.status == MeetingTaskStatus.Finished && it.syncStatus == CloudSyncStatus.PendingUpload }
            .forEach { task ->
                if (task.remoteTaskId == null) {
                    enqueueCloudOperation(CloudSyncOperationType.Upload, task.id, null)
                } else {
                    enqueueCloudOperation(CloudSyncOperationType.UpdateResult, task.id, task.remoteTaskId)
                }
            }
    }

    private suspend fun syncUploadOperation(operation: CloudSyncOperation, client: HuixiaoApiClient, userId: String, store: MeetingProcessingResultStore) {
        val task = localTasks.firstOrNull { it.id == operation.localTaskId } ?: run {
            completeCloudOperation(operation)
            return
        }
        if (task.remoteTaskId != null) {
            enqueueCloudOperation(CloudSyncOperationType.UpdateResult, task.id, task.remoteTaskId)
            completeCloudOperation(operation)
            return
        }
        val result = store.loadResultForTask(task) ?: run {
            completeCloudOperation(operation)
            return
        }
        runCatching {
            updateTask(task.copy(syncStatus = CloudSyncStatus.PendingUpload, errorMessage = null))
            val remoteTask = withContext(Dispatchers.IO) {
                client.uploadTask(task.copy(knowledgeScope = KnowledgeIndexScope.Cloud), userId, persistToCloud = true, deviceId = deviceId)
            }
            val current = localTasks.firstOrNull { it.id == task.id }
            if (current == null) {
                withContext(Dispatchers.IO) { runCatching { client.deleteTask(remoteTask.id, userId) } }
                completeCloudOperation(operation)
                return@runCatching
            }
            val resultToUpload = store.loadResultForTask(current) ?: result
            val updated = withContext(Dispatchers.IO) { client.updateTaskResult(remoteTask.id, resultToUpload, userId) }
            withContext(Dispatchers.IO) {
                client.updateTaskMetadata(
                    remoteTask.id,
                    userId,
                    title = current.title,
                    confirmed = current.confirmed,
                    createdAtMillis = current.createdAtMillis,
                    isPrivate = current.isPrivate,
                    knowledgeScope = KnowledgeIndexScope.Cloud
                )
            }
            finishResultSyncWithoutLosingLocalEdits(
                operation = operation,
                taskAtSend = current,
                remoteTaskId = remoteTask.id,
                sentResult = resultToUpload,
                serverResult = updated,
                store = store
            )
        }.onFailure { error ->
            handleAuthFailure(error)
            localTasks.firstOrNull { it.id == task.id }?.let {
                updateTask(it.copy(syncStatus = CloudSyncStatus.SyncFailed, errorMessage = error.userFacingStateMessage("同步失败")))
            }
            failCloudOperation(operation, error)
        }
    }

    private suspend fun syncUpdateOperation(operation: CloudSyncOperation, client: HuixiaoApiClient, userId: String, store: MeetingProcessingResultStore) {
        val task = localTasks.firstOrNull { it.id == operation.localTaskId } ?: run {
            completeCloudOperation(operation)
            return
        }
        val remoteTaskId = operation.remoteTaskId ?: task.remoteTaskId ?: run {
            completeCloudOperation(operation)
            return
        }
        val result = store.loadResultForTask(task) ?: run {
            completeCloudOperation(operation)
            return
        }
        runCatching {
            val updated = withContext(Dispatchers.IO) { client.updateTaskResult(remoteTaskId, result, userId) }
            withContext(Dispatchers.IO) {
                client.updateTaskMetadata(
                    remoteTaskId,
                    userId,
                    title = task.title,
                    confirmed = task.confirmed,
                    createdAtMillis = task.createdAtMillis,
                    isPrivate = task.isPrivate,
                    knowledgeScope = KnowledgeIndexScope.Cloud
                )
            }
            finishResultSyncWithoutLosingLocalEdits(
                operation = operation,
                taskAtSend = task,
                remoteTaskId = remoteTaskId,
                sentResult = result,
                serverResult = updated,
                store = store
            )
        }.onFailure { error ->
            handleAuthFailure(error)
            updateTask(task.copy(syncStatus = CloudSyncStatus.SyncFailed, errorMessage = error.userFacingStateMessage("同步失败")))
            failCloudOperation(operation, error)
        }
    }

    private fun finishResultSyncWithoutLosingLocalEdits(
        operation: CloudSyncOperation,
        taskAtSend: MeetingTask,
        remoteTaskId: String,
        sentResult: MeetingProcessingResult,
        serverResult: MeetingProcessingResult,
        store: MeetingProcessingResultStore
    ) {
        val latestTask = localTasks.firstOrNull { it.id == taskAtSend.id } ?: run {
            completeCloudOperation(operation)
            return
        }
        val latestResult = store.loadResultForTask(latestTask)
        val changedAfterSend = latestTask.hasSyncContentChangedSince(taskAtSend) || (latestResult != null && latestResult != sentResult)
        if (changedAfterSend && latestResult != null) {
            val pendingTask = latestTask.copy(
                remoteTaskId = remoteTaskId,
                syncStatus = CloudSyncStatus.PendingUpload,
                knowledgeScope = KnowledgeIndexScope.Cloud,
                errorMessage = null
            )
            val preservedResult = latestResult.copy(remoteTaskId = remoteTaskId).normalizedForTask(pendingTask)
            store.saveResult(preservedResult)
            localKnowledgeStore?.replaceMeetingIndex(pendingTask, preservedResult)
            updateTask(pendingTask)
            completeCloudOperation(operation)
            enqueueCloudOperation(CloudSyncOperationType.UpdateResult, pendingTask.id, remoteTaskId)
            finishCloudSync("本机有新修改，已排队继续同步")
            return
        }
        val syncedTask = latestTask.copy(
            remoteTaskId = remoteTaskId,
            syncStatus = CloudSyncStatus.Synced,
            knowledgeScope = KnowledgeIndexScope.Cloud,
            progressPercent = if (latestTask.status == MeetingTaskStatus.Finished) 100f else latestTask.progressPercent,
            progressLabel = if (latestTask.status == MeetingTaskStatus.Finished) "处理完成" else latestTask.progressLabel,
            progressStage = if (latestTask.status == MeetingTaskStatus.Finished) "finished" else latestTask.progressStage,
            errorMessage = null
        )
        val syncedResult = serverResult.normalizedForTask(syncedTask)
        store.saveResult(syncedResult)
        localKnowledgeStore?.replaceMeetingIndex(syncedTask, syncedResult)
        updateTask(syncedTask)
        completeCloudOperation(operation)
    }

    private suspend fun syncDeleteOperation(operation: CloudSyncOperation, client: HuixiaoApiClient, userId: String) {
        val remoteTaskId = operation.remoteTaskId ?: run {
            completeCloudOperation(operation)
            return
        }
        runCatching {
            withContext(Dispatchers.IO) { client.deleteTask(remoteTaskId, userId) }
            completeCloudOperation(operation)
        }.onFailure { error ->
            if (error.message.orEmpty().contains("不存在")) {
                completeCloudOperation(operation)
            } else {
                handleAuthFailure(error)
                failCloudOperation(operation, error)
            }
        }
    }

    private suspend fun syncUpsertScheduleOperation(operation: CloudSyncOperation, client: HuixiaoApiClient) {
        val user = cloudUser ?: return
        val meeting = scheduledMeetings.firstOrNull { it.id == operation.localTaskId } ?: run {
            completeCloudOperation(operation)
            return
        }
        runCatching {
            withContext(Dispatchers.IO) { client.syncSchedule(user, meeting) }
            completeCloudOperation(operation)
        }.onFailure { error ->
            handleAuthFailure(error)
            failCloudOperation(operation, error)
        }
    }

    private suspend fun syncDeleteScheduleOperation(operation: CloudSyncOperation, client: HuixiaoApiClient, userId: String) {
        runCatching {
            withContext(Dispatchers.IO) { client.deleteSchedule(CloudUser(userId = userId, username = "", displayName = ""), operation.localTaskId) }
            completeCloudOperation(operation)
        }.onFailure { error ->
            if (error.message.orEmpty().contains("不存在")) {
                completeCloudOperation(operation)
            } else {
                handleAuthFailure(error)
                failCloudOperation(operation, error)
            }
        }
    }

    private fun mergeCloudTasks(local: List<MeetingTask>, cloud: List<MeetingTask>, cloudTaskIds: Set<String>): List<MeetingTask> {
        val retainedLocal = local.filter { task ->
            task.syncStatus != CloudSyncStatus.Synced || task.id in cloudTaskIds || (task.remoteTaskId ?: task.id) in cloudTaskIds
        }
        val byId = retainedLocal.associateBy { it.id }.toMutableMap()
        val remoteToLocalId = retainedLocal.mapNotNull { task -> task.remoteTaskId?.let { it to task.id } }.toMap().toMutableMap()
        cloud.forEach { remote ->
            val localId = byId[remote.id]?.id ?: remote.remoteTaskId?.let { remoteToLocalId[it] } ?: remote.id
            val existing = byId[localId]
            val merged = if (existing == null) {
                remote.copy(syncStatus = CloudSyncStatus.Synced, knowledgeScope = KnowledgeIndexScope.Cloud)
            } else if (existing.syncStatus == CloudSyncStatus.PendingUpload || existing.syncStatus == CloudSyncStatus.SyncFailed) {
                existing
            } else {
                existing.mergedWithCloud(remote)
            }
            byId[localId] = merged
            merged.remoteTaskId?.let { remoteToLocalId[it] = localId }
        }
        return byId.values.toList()
    }

    private fun MeetingTask.mergedWithCloud(remote: MeetingTask): MeetingTask {
        return copy(
            remoteTaskId = remote.remoteTaskId ?: remote.id,
            title = remote.title,
            source = remote.source,
            status = remote.status,
            localFilePath = localFilePath.ifBlank { remote.localFilePath },
            createdAtLabel = remote.createdAtLabel,
            createdAtMillis = remote.createdAtMillis,
            sizeLabel = remote.sizeLabel ?: sizeLabel,
            errorMessage = remote.errorMessage,
            progressPercent = remote.progressPercent,
            progressLabel = remote.progressLabel,
            progressStage = remote.progressStage,
            confirmed = remote.confirmed,
            syncStatus = CloudSyncStatus.Synced,
            knowledgeScope = KnowledgeIndexScope.Cloud,
            isPrivate = remote.isPrivate,
            deviceId = remote.deviceId ?: this.deviceId
        )
    }

    private fun MeetingTask.hasLocalPendingCloudChanges(): Boolean {
        val currentRemoteTaskId = remoteTaskId?.takeIf { it.isNotBlank() }
        return syncStatus == CloudSyncStatus.PendingUpload ||
            syncStatus == CloudSyncStatus.SyncFailed ||
            cloudSyncOperations.any { operation ->
                operation.type in setOf(CloudSyncOperationType.Upload, CloudSyncOperationType.UpdateResult) &&
                    (operation.localTaskId == id || (currentRemoteTaskId != null && operation.remoteTaskId == currentRemoteTaskId))
            }
    }

    private fun MeetingTask.hasSyncContentChangedSince(previous: MeetingTask): Boolean {
        return title != previous.title ||
            source != previous.source ||
            status != previous.status ||
            localFilePath != previous.localFilePath ||
            confirmed != previous.confirmed ||
            isPrivate != previous.isPrivate ||
            scheduleId != previous.scheduleId ||
            scheduleNote != previous.scheduleNote
    }

    private fun mergeSchedules(local: List<ScheduledMeeting>, cloud: List<ScheduledMeeting>, pendingLocalScheduleIds: Set<String>): List<ScheduledMeeting> {
        val byId = local.associateBy { it.id }.toMutableMap()
        cloud.forEach { remote ->
            if (remote.id !in pendingLocalScheduleIds) {
                byId[remote.id] = remote
            }
        }
        return byId.values.filterNot { it.isFinished() }
    }

    private fun String.cleanParticipants(): String {
        val names = split("，", ",", "、", ";", "；")
            .map { it.trim() }
            .filter { it.isNotBlank() && it != "无" && !it.equals("null", ignoreCase = true) }
            .distinct()
        return names.takeIf { it.isNotEmpty() }?.joinToString("，") ?: "待补充参会人"
    }

    private fun deleteOwnedLocalFile(path: String) {
        runCatching {
            val target = File(path).canonicalFile
            val parent = target.parentFile
            val grandParent = parent?.parentFile
            if ((parent?.name == "recordings" || parent?.name == "imports" || parent?.name == CloudAudioCache.DirectoryName) && grandParent?.name == "files") {
                target.delete()
            }
        }
    }

    private fun String.readableLocalFileSizeLabel(): String? {
        val size = runCatching { File(this).length() }.getOrDefault(0L)
        if (size <= 0L) return null
        val mb = size / 1024.0 / 1024.0
        return if (mb >= 1) {
            "%.1f MB".format(Locale.CHINA, mb)
        } else {
            "${(size / 1024).coerceAtLeast(1)} KB"
        }
    }

    private fun Meeting.withSpeakerAliases(aliases: Map<String, String>): Meeting {
        if (aliases.isEmpty()) return this
        return copy(
            todos = todos.map { it.applySpeakerAssigneeAliases(aliases) },
            transcripts = transcripts.map { segment -> segment.applySpeakerAliases(aliases) }
        )
    }

    private fun TodoItem.applySpeakerAssigneeAliases(aliases: Map<String, String>): TodoItem {
        val target = aliases[assigneeName.cleanNullableText()] ?: return this
        return copy(assigneeName = target)
    }

    private fun TranscriptSegment.applySpeakerAliases(aliases: Map<String, String>): TranscriptSegment {
        return copy(speaker = aliases[speaker] ?: speaker)
    }

    private fun Meeting.sourceIndexFor(source: RemoteKnowledgeSource): Int {
        if (transcripts.isEmpty()) return 0
        val byStart = source.startMs?.let { startMs ->
            transcripts
                .mapIndexedNotNull { index, segment -> segment.startMs?.let { index to kotlin.math.abs(it - startMs) } }
                .minByOrNull { it.second }
                ?.first
        }
        if (byStart != null) return byStart.coerceIn(0, transcripts.lastIndex)

        val cleanTimestamp = source.timestamp.cleanNullableText()
        val byTimestamp = cleanTimestamp?.let { timestamp ->
            transcripts.indexOfFirst { segment -> segment.timestamp == timestamp || segment.timeRangeLabel.contains(timestamp) }
        } ?: -1
        if (byTimestamp >= 0) return byTimestamp.coerceIn(0, transcripts.lastIndex)

        val sourceText = source.text.take(24)
        val byText = transcripts.indexOfFirst { segment -> sourceText.isNotBlank() && (sourceText in segment.text || segment.text.take(24) in source.text) }
        return (if (byText >= 0) byText else 0).coerceIn(0, transcripts.lastIndex)
    }

    private fun Meeting.sourceIndexFor(timestamp: String? = null, text: String = "", fallback: Int = 0): Int {
        if (transcripts.isEmpty()) return 0
        val clean = timestamp.cleanNullableText()
        val byTime = clean?.let { timestampText ->
            transcripts.indexOfFirst { segment ->
                segment.timestamp == timestampText || segment.timeRangeLabel.contains(timestampText)
            }
        } ?: -1
        if (byTime >= 0) return byTime.coerceIn(0, transcripts.lastIndex)
        val cleanText = text.trim()
        val byText = if (cleanText.isBlank()) {
            -1
        } else {
            transcripts.indexOfFirst { segment ->
                val left = segment.text.take(32)
                val right = cleanText.take(32)
                left.isNotBlank() && (left in cleanText || right in segment.text)
            }
        }
        return (if (byText >= 0) byText else fallback).coerceIn(0, transcripts.lastIndex)
    }

    private fun Meeting.todoSourceIndexOrNull(todo: TodoItem): Int? {
        if (transcripts.isEmpty()) return null
        todo.sourceSegmentIndex?.takeIf { it in transcripts.indices }?.let { return it }
        val byTime = todo.sourceTimestampLabel?.let { timestampText ->
            transcripts.indexOfFirst { segment ->
                segment.timestamp == timestampText || segment.timeRangeLabel.contains(timestampText)
            }
        } ?: -1
        if (byTime >= 0) return byTime.coerceIn(0, transcripts.lastIndex)
        val cleanText = listOf(todo.title, todo.description, todo.sourceLabel.orEmpty()).joinToString(" ").trim()
        if (cleanText.isBlank()) return null
        val byText = transcripts.indexOfFirst { segment ->
            val left = segment.text.take(32)
            val right = cleanText.take(32)
            left.isNotBlank() && (left in cleanText || right in segment.text)
        }
        return byText.takeIf { it >= 0 }?.coerceIn(0, transcripts.lastIndex)
    }

    private fun Meeting.toMarkdownText(includeTranscript: Boolean): String {
        return buildString {
            appendLine("# $title")
            appendLine()
            appendLine("- 参会人：$participants")
            if (tags.isNotEmpty()) appendLine("- 标签：${tags.joinToString("、")}")
            appendLine()
            appendLine("## 摘要")
            appendLine(summary)
            appendLine()
            appendLine("## 议题")
            topics.forEach { appendLine("- ${it.title}${if (it.summary.isNotBlank()) "：${it.summary}" else ""}（${it.sourceTimestamp ?: "无时间"} ${it.source.ifBlank { "无来源" }}）") }
            appendLine()
            appendLine("## 决策")
            decisions.forEach { appendLine("- $it") }
            appendLine()
            appendLine("## 待办")
            todos.forEach { appendLine("- ${it.title}（负责人：${it.assigneeLabel ?: "待补充"}；截止：${it.dueLabel ?: "待补充"}；来源：${it.sourceTimestampLabel ?: "无时间"} ${it.sourceLabel.orEmpty()}）") }
            appendLine()
            appendLine("## 风险")
            risks.forEach { appendLine("- ${it.title}${if (it.level.isNotBlank()) "（${it.level}）" else ""}：${it.description.ifBlank { it.recommendation }}（${it.sourceTimestamp ?: "无时间"} ${it.source.ifBlank { "无来源" }}）") }
            appendLine()
            if (includeTranscript) {
                appendLine()
                appendLine("## 转写原文")
                transcripts.forEach { appendLine("- ${it.timeRangeLabel} ${it.speaker}：${it.text}") }
            }
        }
    }

    private fun Meeting.toPlainText(includeTranscript: Boolean): String {
        return buildString {
            appendLine(title)
            appendLine("参会人：$participants")
            if (tags.isNotEmpty()) appendLine("标签：${tags.joinToString("、")}")
            appendLine()
            appendLine("摘要")
            appendLine(summary)
            appendLine()
            appendLine("议题")
            topics.forEach { appendLine("- ${it.title}${if (it.summary.isNotBlank()) "：${it.summary}" else ""}") }
            appendLine()
            appendLine("决策")
            decisions.forEach { appendLine("- $it") }
            appendLine()
            appendLine("待办")
            todos.forEach { appendLine("- ${it.title}；负责人：${it.assigneeLabel ?: "待补充"}；截止：${it.dueLabel ?: "待补充"}") }
            appendLine()
            appendLine("风险")
            risks.forEach { appendLine("- ${it.title}${if (it.level.isNotBlank()) "（${it.level}）" else ""}：${it.description.ifBlank { it.recommendation }}") }
            if (includeTranscript) {
                appendLine()
                appendLine("转写原文")
                transcripts.forEach { appendLine("- ${it.timeRangeLabel} ${it.speaker}：${it.text}") }
            }
        }
    }
}

private fun AppScreen.returnTargetForTransientScreen(): AppScreen {
    return when (this) {
        AppScreen.Detail,
        AppScreen.Record,
        AppScreen.Generating -> AppScreen.Home
        else -> this
    }
}

private fun MeetingTask.willBeDeletedBeforeCloudSafe(): Boolean {
    return when {
        status in setOf(MeetingTaskStatus.WaitingProcess, MeetingTaskStatus.Processing, MeetingTaskStatus.Failed) -> true
        syncStatus in setOf(
            CloudSyncStatus.LocalOnly,
            CloudSyncStatus.PendingUpload,
            CloudSyncStatus.SyncFailed,
            CloudSyncStatus.LocalProcessing
        ) -> true
        status == MeetingTaskStatus.Finished && syncStatus != CloudSyncStatus.Synced -> true
        else -> false
    }
}

private fun String.isReadableLocalAudioFile(): Boolean {
    if (isBlank()) return false
    return runCatching {
        val file = File(this)
        file.exists() && file.isFile && file.length() > 0L
    }.getOrDefault(false)
}

private const val REMINDER_LEAD_MILLIS = 5 * 60 * 1000L

private fun TranscriptSegment.normalizedLiveSegmentOrNull(): TranscriptSegment? {
    val cleanText = text.trim()
    if (cleanText.isBlank()) return null
    val cleanSpeaker = speaker.takeUnless { it.isBlank() || it == "未分离" } ?: "发言"
    val cleanSpeakerId = speakerId?.takeIf { it.isNotBlank() } ?: speakerIdentityIdForName(cleanSpeaker)
    return copy(speaker = cleanSpeaker, text = cleanText, speakerId = cleanSpeakerId)
}

private fun TranscriptSegment.sameLiveContent(other: TranscriptSegment): Boolean {
    return speaker == other.speaker &&
        text == other.text &&
        startMs == other.startMs &&
        endMs == other.endMs
}

private fun TranscriptSegment.canBeUpdatedBy(incoming: TranscriptSegment): Boolean {
    if (speaker != incoming.speaker && speaker != "未分离" && incoming.speaker != "未分离") return false
    val leftStart = startMs
    val rightStart = incoming.startMs
    if (leftStart == null || rightStart == null) return false
    if (kotlin.math.abs(leftStart - rightStart) > 300L) return false
    return text.isLiveRevisionPair(incoming.text)
}

private fun TranscriptSegment.mergeLiveUpdate(incoming: TranscriptSegment): TranscriptSegment {
    val mergedText = if (incoming.text.length + 3 >= text.length) incoming.text else text
    val mergedStart = listOfNotNull(startMs, incoming.startMs).minOrNull()
    val mergedEnd = listOfNotNull(endMs, incoming.endMs).maxOrNull()
    return copy(
        text = mergedText,
        timestamp = mergedStart?.toLiveClock() ?: incoming.timestamp.ifBlank { timestamp },
        startMs = mergedStart,
        endMs = mergedEnd
    )
}

private fun TranscriptSegment.isCoveredByFinalLiveSegment(finalSegment: TranscriptSegment): Boolean {
    if (sameLiveContent(finalSegment)) return false
    val sameRangeText = text == finalSegment.text &&
        startMs == finalSegment.startMs &&
        endMs == finalSegment.endMs
    if (sameRangeText) return true
    if (speaker != "未分离" && speaker != finalSegment.speaker) return false
    val coveredByText = text.isNotBlank() && text in finalSegment.text
    val coveredByTime = startMs != null &&
        finalSegment.startMs != null &&
        finalSegment.endMs != null &&
        startMs >= finalSegment.startMs - 500L &&
        startMs <= finalSegment.endMs + 500L
    return coveredByText || coveredByTime
}

private fun String.isLiveRevisionPair(other: String): Boolean {
    val left = trim()
    val right = other.trim()
    if (left.isBlank() || right.isBlank()) return false
    if (left == right || left in right || right in left) return true
    val shorter = minOf(left.length, right.length)
    val commonPrefix = left.zip(right).takeWhile { it.first == it.second }.size
    return commonPrefix >= minOf(8, shorter)
}

private fun Long.toLiveClock(): String {
    val totalSeconds = (this / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}

@Composable
fun rememberHuixiaoAppState(
    repository: MeetingRepository,
    taskStore: LocalTaskStore? = null,
    scheduleStore: LocalScheduleStore? = null,
    resultGenerator: MeetingResultGenerator = MissingMeetingResultGenerator,
    resultStore: MeetingProcessingResultStore? = null,
    localKnowledgeStore: LocalKnowledgeStore? = null,
    searchHistoryStore: LocalSearchHistoryStore? = null,
    apiClient: HuixiaoApiClient? = null,
    cloudAudioCache: CloudAudioCache? = null,
    cloudUserStore: SharedPrefsCloudUserStore? = null,
    cloudSyncOperationStore: CloudSyncOperationStore? = null,
    speakerProfileStore: SpeakerProfileStore? = null,
    membershipProfileStore: SharedPrefsMembershipProfileStore? = null,
    networkAvailable: () -> Boolean? = { null }
): HuixiaoAppState {
    return remember(repository, taskStore, scheduleStore, resultGenerator, resultStore, localKnowledgeStore, searchHistoryStore, apiClient, cloudAudioCache, cloudUserStore, cloudSyncOperationStore, speakerProfileStore, membershipProfileStore, networkAvailable) {
        HuixiaoAppState(
            repository = repository,
            taskStore = taskStore,
            scheduleStore = scheduleStore,
            resultGenerator = resultGenerator,
            resultStore = resultStore,
            localKnowledgeStore = localKnowledgeStore,
            searchHistoryStore = searchHistoryStore,
            apiClient = apiClient,
            cloudAudioCache = cloudAudioCache,
            cloudUserStore = cloudUserStore,
            cloudSyncOperationStore = cloudSyncOperationStore,
            speakerProfileStore = speakerProfileStore,
            membershipProfileStore = membershipProfileStore,
            networkAvailable = networkAvailable
        )
    }
}
