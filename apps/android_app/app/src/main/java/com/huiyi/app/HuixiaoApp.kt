package com.huiyi.app

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.huiyi.app.components.SmartBottomNavigationBar
import com.huiyi.app.data.AndroidNetworkStatus
import com.huiyi.app.data.AlipayPaymentOrder
import com.huiyi.app.data.CloudAudioCache
import com.huiyi.app.audio.AudioSegmentPlayer
import com.huiyi.app.calendar.hasScheduleConflict
import com.huiyi.app.calendar.parseScheduleTime
import com.huiyi.app.data.HuixiaoApiClient
import com.huiyi.app.data.HuixiaoApiException
import com.huiyi.app.data.LocalMeetingDataCleaner
import com.huiyi.app.data.MeetingTask
import com.huiyi.app.data.MeetingTaskSource
import com.huiyi.app.data.MeetingTaskStatus
import com.huiyi.app.data.RecognitionLanguage
import com.huiyi.app.data.EmptyMeetingRepository
import com.huiyi.app.data.SharedPrefsMeetingProcessingResultStore
import com.huiyi.app.data.SharedPrefsLocalScheduleStore
import com.huiyi.app.data.SharedPrefsLocalTaskStore
import com.huiyi.app.data.SharedPrefsLocalKnowledgeStore
import com.huiyi.app.data.SharedPrefsLocalSearchHistoryStore
import com.huiyi.app.data.SharedPrefsCloudSyncOperationStore
import com.huiyi.app.data.SharedPrefsCloudUserStore
import com.huiyi.app.data.SharedPrefsMembershipProfileStore
import com.huiyi.app.data.SharedPrefsSpeakerProfileStore
import com.huiyi.app.data.StoredMeetingResultGenerator
import com.huiyi.app.data.userFacingMessage
import com.huiyi.app.fileimport.LocalFileImporter
import com.huiyi.app.model.AppScreen
import com.huiyi.app.model.SheetType
import com.huiyi.app.notifications.ScheduleStatusNotifier
import com.huiyi.app.permissions.AppPermissionStatus
import com.huiyi.app.recording.RecordingForegroundService
import com.huiyi.app.recording.RecordingServiceEvent
import com.huiyi.app.recording.RecordingSessionBus
import com.huiyi.app.recording.RecordingStatus
import com.huiyi.app.screens.DetailScreen
import com.huiyi.app.screens.GeneratingScreen
import com.huiyi.app.screens.HomeScreen
import com.huiyi.app.screens.ImportScreen
import com.huiyi.app.screens.KnowledgeScreen
import com.huiyi.app.screens.LoginScreen
import com.huiyi.app.screens.MeetingListScreen
import com.huiyi.app.screens.MembershipScreen
import com.huiyi.app.screens.PaymentOrdersScreen
import com.huiyi.app.screens.ProfileScreen
import com.huiyi.app.screens.RecordScreen
import com.huiyi.app.screens.ScheduleListScreen
import com.huiyi.app.screens.SearchScreen
import com.huiyi.app.screens.TasksScreen
import com.huiyi.app.screens.VoiceprintScreen
import com.huiyi.app.sheets.CreateMeetingSheet
import com.huiyi.app.sheets.CorrectionSheet
import com.huiyi.app.sheets.DeleteDataSheet
import com.huiyi.app.sheets.DeleteMeetingSheet
import com.huiyi.app.sheets.DeleteScheduleSheet
import com.huiyi.app.sheets.DeleteTaskSheet
import com.huiyi.app.sheets.EditMeetingInfoSheet
import com.huiyi.app.sheets.EditMinutesSheet
import com.huiyi.app.sheets.ExportSheet
import com.huiyi.app.sheets.ImportFileSheet
import com.huiyi.app.sheets.LogoutConfirmSheet
import com.huiyi.app.sheets.NotificationsSheet
import com.huiyi.app.sheets.PrivacyPolicySheet
import com.huiyi.app.sheets.RecordConsentSheet
import com.huiyi.app.sheets.ScheduleReminderSheet
import com.huiyi.app.sheets.SpeakerSheet
import com.huiyi.app.sheets.SourceSheet
import com.huiyi.app.sheets.TodoDetailSheet
import com.huiyi.app.sheets.TodoCreateSheet
import com.huiyi.app.sheets.UserAgreementSheet
import com.huiyi.app.state.rememberHuixiaoAppState
import com.huiyi.app.ui.AppBg
import com.huiyi.app.ui.Brand
import com.huiyi.app.ui.BrandCyan
import com.huiyi.app.ui.BrandDark
import com.huiyi.app.ui.BrandSoft
import com.huiyi.app.ui.Ink
import com.huiyi.app.ui.Line
import com.huiyi.app.ui.Muted
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.alipay.sdk.app.PayTask
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val LEGAL_CONSENT_PREFS = "huixiao_legal_consents"
private const val LOGIN_AGREEMENT_VERSION_KEY = "login_agreement_version"
private const val LOGIN_AGREEMENT_ACCEPTED_AT_KEY = "login_agreement_accepted_at"
private const val LOGIN_AGREEMENT_VERSION = "2026-06-08"

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HuixiaoApp() {
    val context = LocalContext.current
    val taskStore = remember { SharedPrefsLocalTaskStore(context.applicationContext) }
    val scheduleStore = remember { SharedPrefsLocalScheduleStore(context.applicationContext) }
    val resultStore = remember { SharedPrefsMeetingProcessingResultStore(context.applicationContext) }
    val localKnowledgeStore = remember { SharedPrefsLocalKnowledgeStore(context.applicationContext) }
    val searchHistoryStore = remember { SharedPrefsLocalSearchHistoryStore(context.applicationContext) }
    val cloudUserStore = remember { SharedPrefsCloudUserStore(context.applicationContext) }
    val membershipProfileStore = remember { SharedPrefsMembershipProfileStore(context.applicationContext) }
    val cloudSyncOperationStore = remember { SharedPrefsCloudSyncOperationStore(context.applicationContext) }
    val speakerProfileStore = remember { SharedPrefsSpeakerProfileStore(context.applicationContext) }
    val cloudAudioCache = remember { CloudAudioCache(context.applicationContext) }
    val networkStatus = remember { AndroidNetworkStatus(context.applicationContext) }
    val networkAvailable = remember(networkStatus) { { networkStatus.isNetworkAvailable() } }
    val dataCleaner = remember { LocalMeetingDataCleaner(context.applicationContext) }
    val apiClient = remember { HuixiaoApiClient() }
    val resultGenerator = remember { StoredMeetingResultGenerator(resultStore) }
    val appState = rememberHuixiaoAppState(
        repository = EmptyMeetingRepository,
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
    val audioSegmentPlayer = remember { AudioSegmentPlayer() }
    val fileImporter = remember { LocalFileImporter(context.applicationContext) }
    val scheduleStatusNotifier = remember { ScheduleStatusNotifier(context.applicationContext) }
    val legalConsentPrefs = remember { context.applicationContext.getSharedPreferences(LEGAL_CONSENT_PREFS, Context.MODE_PRIVATE) }
    var loginAgreementAccepted by remember {
        mutableStateOf(legalConsentPrefs.getString(LOGIN_AGREEMENT_VERSION_KEY, null) == LOGIN_AGREEMENT_VERSION)
    }
    val screen by remember { derivedStateOf { appState.screen } }
    val loggedIn by remember { derivedStateOf { appState.cloudUser != null && loginAgreementAccepted } }
    val sheet by remember { derivedStateOf { appState.sheet } }
    val toast by remember { derivedStateOf { appState.toast } }
    val roots = listOf(AppScreen.Home, AppScreen.Tasks, AppScreen.Knowledge, AppScreen.Profile)
    val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    var permissionStatus by remember { mutableStateOf(readPermissionStatus(context)) }
    var notifiedScheduleIds by remember { mutableStateOf(setOf<String>()) }
    var scheduleSaving by remember { mutableStateOf(false) }
    var todoSaving by remember { mutableStateOf(false) }
    var speakerSaving by remember { mutableStateOf(false) }
    var speakerStatusText by remember { mutableStateOf<String?>(null) }
    var exportBusy by remember { mutableStateOf(false) }
    var exportStatusText by remember { mutableStateOf<String?>(null) }
    var pendingTextExport by remember { mutableStateOf<PendingTextExport?>(null) }
    var correctionSaving by remember { mutableStateOf(false) }
    var minutesSaving by remember { mutableStateOf(false) }
    var meetingInfoSaving by remember { mutableStateOf(false) }
    var deleteDataSaving by remember { mutableStateOf(false) }
    var deleteMeetingSaving by remember { mutableStateOf(false) }
    var bulkMeetingDeleting by remember { mutableStateOf(false) }
    var deleteScheduleSaving by remember { mutableStateOf(false) }
    var deleteTaskSaving by remember { mutableStateOf(false) }
    var logoutSaving by remember { mutableStateOf(false) }
    var loginBusy by remember { mutableStateOf(false) }
    var smsSending by remember { mutableStateOf(false) }
    var loginStatusText by remember { mutableStateOf<String?>(null) }
    var phoneChangeStatusText by remember { mutableStateOf<String?>(null) }
    var pendingRegistrationPhone by remember { mutableStateOf<String?>(null) }
    var pendingRegistrationCode by remember { mutableStateOf("") }
    var pendingRegistrationSignal by remember { mutableStateOf(0) }
    var pendingLoginPhone by remember { mutableStateOf<String?>(null) }
    var pendingLoginSignal by remember { mutableStateOf(0) }
    var voiceprintBusy by remember { mutableStateOf(false) }
    var voiceprintStatusText by remember { mutableStateOf<String?>(null) }
    var pendingVoiceprintImportName by remember { mutableStateOf<String?>(null) }
    var pendingWebPaymentOrderId by remember { mutableStateOf<String?>(null) }
    var pendingStoppedRecordingTaskId by remember { mutableStateOf<String?>(null) }
    var selectedRecognitionLanguage by remember { mutableStateOf(RecognitionLanguage.Chinese) }
    var frozenAccountDialogVisible by remember { mutableStateOf(false) }
    var paymentBusy by remember { mutableStateOf(false) }
    var paymentBusyText by remember { mutableStateOf("") }
    var queuedRetryTaskIds by remember { mutableStateOf<List<String>>(emptyList()) }
    lateinit var startRecording: () -> Unit
    lateinit var importFile: (Uri) -> Unit
    lateinit var guardedProcessingLaunch: (() -> com.huiyi.app.state.ProcessingLaunch?) -> Unit
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            startRecording()
        } else {
            appState.closeSheet()
            appState.showToast("未获得麦克风权限，无法开始录音")
        }
    }
    val filePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importFile(uri)
    }
    val textExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val payload = pendingTextExport
        pendingTextExport = null
        if (uri == null) {
            if (payload != null) appState.showToast("已取消导出")
            return@rememberLauncherForActivityResult
        }
        if (payload == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openOutputStream(uri)?.use { stream ->
                stream.write(payload.text.toByteArray(Charsets.UTF_8))
                stream.flush()
            } ?: error("无法打开保存位置")
        }.onSuccess {
            appState.showToast("TXT 文件已导出")
        }.onFailure { error ->
            appState.showToast("导出失败：${error.userFacingMessage(networkAvailable = networkStatus.isNetworkAvailable())}")
        }
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionStatus = readPermissionStatus(context)
        appState.showToast(if (granted) "通知权限已开启" else "未获得通知权限")
    }

    fun showPriorityToast(message: String) {
        Toast.makeText(context.applicationContext, message, Toast.LENGTH_SHORT).show()
    }

    fun friendlyError(error: Throwable, fallback: String = "操作失败，请稍后重试"): String {
        return error.userFacingMessage(fallback, networkAvailable = networkStatus.isNetworkAvailable())
    }

    fun showFrozenAccountDialog() {
        appState.closeSheet()
        frozenAccountDialogVisible = true
    }

    fun showQuotaExhaustedAndOpenMembership(message: String = "额度已耗尽，请充值后继续享受权益") {
        appState.closeSheet()
        appState.go(AppScreen.Membership)
        showPriorityToast(message)
    }

    suspend fun confirmAlipayPaymentResult(orderId: String): String {
        fun cachedStatus(): String {
            return appState.paymentOrders.firstOrNull { it.id == orderId }?.status.orEmpty()
        }
        var status = cachedStatus()
        if (status == "支付成功") {
            paymentBusyText = "正在刷新会员权益"
            appState.refreshPaymentOrdersAndMembershipAfterPayment()
            return status
        }
        paymentBusyText = "正在确认支付结果"
        runCatching { appState.refreshPaymentOrder(orderId) }
            .onSuccess { localStatus -> status = localStatus.ifBlank { cachedStatus() } }
        if (status == "支付成功") {
            paymentBusyText = "正在刷新会员权益"
            appState.refreshPaymentOrdersAndMembershipAfterPayment()
            return status
        }
        val retryDelays = listOf(0L, 700L)
        var lastError: Throwable? = null
        for ((index, delayMs) in retryDelays.withIndex()) {
            if (delayMs > 0) delay(delayMs)
            paymentBusyText = if (index == 0) {
                "正在确认支付结果"
            } else {
                "正在确认支付结果 ${index + 1}/${retryDelays.size}"
            }
            runCatching { appState.syncAlipayPaymentOrder(orderId) }
                .onSuccess { nextStatus ->
                    status = nextStatus
                    lastError = null
                }
                .onFailure { error ->
                    lastError = error
                }
            if (status == "支付成功" || status == "支付失败") break
        }
        if (status == "支付成功") {
            paymentBusyText = "正在刷新会员权益"
            appState.refreshPaymentOrdersAndMembershipAfterPayment()
        } else {
            appState.refreshPaymentOrders(force = true)
            lastError?.takeIf { status.isBlank() }?.let { throw it }
        }
        return status
    }

    fun ensureTranscriptionQuotaForUi(): Boolean {
        if (appState.cloudUser == null) {
            showPriorityToast("请先登录后再使用转写")
            return false
        }
        if (appState.membershipProfile.frozen) {
            showFrozenAccountDialog()
            return false
        }
        if (appState.membershipProfile.loading) {
            showPriorityToast("会员信息同步中，请稍后再试")
            return false
        }
        if (appState.membershipProfile.transcriptionMinutesRemaining <= 0) {
            showQuotaExhaustedAndOpenMembership()
            return false
        }
        return true
    }

    fun ensureKnowledgeQuotaForUi(): Boolean {
        if (appState.cloudUser == null) {
            showPriorityToast("请先登录后再使用知识库问答")
            return false
        }
        if (appState.membershipProfile.frozen) {
            showFrozenAccountDialog()
            return false
        }
        if (appState.membershipProfile.loading) {
            showPriorityToast("会员信息同步中，请稍后再试")
            return false
        }
        if (appState.membershipProfile.knowledgeQaRemaining <= 0) {
            showQuotaExhaustedAndOpenMembership()
            return false
        }
        return true
    }

    LaunchedEffect(loggedIn, appState.cloudUser?.accessToken) {
        val user = appState.cloudUser
        if (loggedIn && user != null && user.tokenValid) {
            while (true) {
                withContext(Dispatchers.IO) {
                    apiClient.prewarmLiveDirectSession(user)
                }
                delay(10 * 60 * 1000L)
            }
        }
    }

    startRecording = {
        runCatching {
            val remaining = appState.membershipProfile.transcriptionMinutesRemaining
            if (!ensureTranscriptionQuotaForUi()) {
                return@runCatching
            }
            if (remaining <= 30) {
                showPriorityToast("当前剩余转写时长 ${remaining} 分钟，本次实时记录已进入缓冲提醒")
            }
            RecordingForegroundService.start(context.applicationContext, selectedRecognitionLanguage)
            appState.closeSheet()
            appState.go(AppScreen.Record)
        }.onFailure {
            appState.closeSheet()
            appState.showToast("录音启动失败：${friendlyError(it, "请检查麦克风权限")}")
        }
    }

    importFile = { uri ->
        val voiceprintName = pendingVoiceprintImportName
        pendingVoiceprintImportName = null
        runCatching {
            val imported = fileImporter.import(uri)
            if (voiceprintName != null) {
                appState.go(AppScreen.Voiceprints)
                voiceprintBusy = true
                voiceprintStatusText = "正在从导入音频录入声纹..."
                coroutineScope.launch {
                    runCatching { appState.enrollSpeakerProfileFromAudio(voiceprintName, imported.localFilePath) }
                        .onSuccess { voiceprintStatusText = "声纹录入成功" }
                        .onFailure { error ->
                            voiceprintStatusText = "声纹录入失败"
                            appState.showToast("声纹录入失败：${friendlyError(error)}")
                        }
                    voiceprintBusy = false
                }
            } else {
                appState.addImportedTask(
                    imported.displayName,
                    imported.localFilePath,
                    imported.sizeLabel,
                    selectedRecognitionLanguage
                )
                appState.showToast("文件已保存到本地")
            }
        }.onFailure {
            appState.showToast("导入失败：${friendlyError(it, "无法读取文件")}")
        }
    }

    fun openFilePicker() {
        filePickerLauncher.launch(arrayOf("audio/*", "video/*"))
    }

    fun requestOrStartRecording() {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            startRecording()
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    fun openAppPermissionSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun startAlipayPayment(productName: String, createOrder: suspend () -> AlipayPaymentOrder) {
        val activity = context as? Activity
        if (paymentBusy) return
        if (!appState.membershipProfile.paymentEnabled) {
            appState.showToast("支付宝支付配置未完成，${productName}暂不能购买")
            return
        }
        if (appState.membershipProfile.frozen) {
            showFrozenAccountDialog()
            return
        }
        coroutineScope.launch {
            paymentBusy = true
            paymentBusyText = "正在创建支付宝订单"
            runCatching {
                val payment = createOrder()
                if (payment.paymentMode == "wap" || payment.payUrl.isNotBlank()) {
                    if (payment.payUrl.isBlank()) error("支付链接生成失败，请稍后重试")
                    paymentBusyText = "正在打开支付宝支付页面"
                    pendingWebPaymentOrderId = payment.order.id
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(payment.payUrl)).apply {
                        if (activity == null) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    paymentBusyText = "请在支付宝页面完成支付，返回后自动确认"
                } else {
                    if (activity == null) error("当前页面无法调起支付宝")
                    paymentBusyText = "正在调起支付宝"
                    val result = withContext(Dispatchers.IO) {
                        PayTask(activity).payV2(payment.orderString, true)
                    }
                    when (result["resultStatus"]) {
                        "9000" -> {
                            appState.showToast("支付结果确认中")
                            val status = confirmAlipayPaymentResult(payment.order.id)
                            if (status == "支付成功") {
                                appState.showToast("支付成功，权益已到账")
                            } else {
                                appState.showToast("支付已提交，权益到账可能稍有延迟")
                            }
                        }
                        "8000" -> appState.showToast("支付处理中，请稍后刷新会员状态")
                        "6001" -> appState.showToast("已取消支付")
                        else -> appState.showToast("支付未完成")
                    }
                    paymentBusy = false
                    paymentBusyText = ""
                }
            }.onFailure { error ->
                paymentBusy = false
                paymentBusyText = ""
                appState.showToast(error.userFacingMessage(networkAvailable = networkStatus.isNetworkAvailable()))
            }
        }
    }

    fun syncScheduleToCloudLater(meeting: com.huiyi.app.data.ScheduledMeeting) {
        coroutineScope.launch {
            runCatching { appState.syncScheduledMeetingToCloud(meeting) }
                .onFailure { appState.showToast("云端预约同步失败：${friendlyError(it)}") }
        }
    }

    fun saveScheduleFromSheet(title: String, time: String, participants: String, note: String): String? {
        if (scheduleSaving) return null
        val cleanTitle = title.trim()
        if (cleanTitle.isBlank() || cleanTitle == "无" || cleanTitle.equals("null", ignoreCase = true)) {
            return "会议主题不能为空"
        }
        val parsedTime = parseScheduleTime(time.trim()) ?: return "会议时间格式不正确"
        val editing = appState.editingSchedule
        if (editing?.isFinished() == true) {
            return "已结束会议不支持修改"
        }
        if (hasScheduleConflict(appState.scheduledMeetings, parsedTime.startAtMillis, ignoreId = editing?.id)) {
            return "时间冲突，请调整会议时间"
        }
        scheduleSaving = true
        val result = runCatching {
            val meeting = if (editing == null) {
                appState.createScheduledMeeting(title, time, participants, note)
            } else {
                appState.updateScheduledMeeting(editing.id, title, time, participants, note)
            }
            if (meeting != null) {
                appState.closeSheet()
                syncScheduleToCloudLater(meeting)
                appState.showToast("预约已保存，到点会通过状态栏通知提醒")
                notifiedScheduleIds = notifiedScheduleIds - meeting.id
                null
            } else {
                "预约保存失败，请检查会议信息"
            }
        }.getOrElse {
            "预约保存失败：${it.userFacingMessage(networkAvailable = networkStatus.isNetworkAvailable())}"
        }
        scheduleSaving = false
        return result
    }

    fun persistLoginAgreementConsent() {
        loginAgreementAccepted = true
        legalConsentPrefs.edit()
            .putString(LOGIN_AGREEMENT_VERSION_KEY, LOGIN_AGREEMENT_VERSION)
            .putLong(LOGIN_AGREEMENT_ACCEPTED_AT_KEY, System.currentTimeMillis())
            .apply()
    }

    fun setLoginAgreementAccepted(accepted: Boolean) {
        if (accepted) {
            persistLoginAgreementConsent()
        } else if (!accepted) {
            loginAgreementAccepted = false
            legalConsentPrefs.edit()
                .remove(LOGIN_AGREEMENT_VERSION_KEY)
                .remove(LOGIN_AGREEMENT_ACCEPTED_AT_KEY)
                .apply()
        }
    }

    fun requireLoginAgreement(): Boolean {
        if (!loginAgreementAccepted) {
            appState.showToast("请先阅读并同意用户协议和隐私政策")
            return false
        }
        return true
    }

    fun sendLoginSmsCodeFromUi(phone: String, onSent: () -> Unit = {}) {
        if (!requireLoginAgreement()) return
        if (smsSending) return
        smsSending = true
        loginStatusText = "正在发送验证码..."
        coroutineScope.launch {
            runCatching { appState.sendLoginSmsCode(phone) }
                .onSuccess {
                    loginStatusText = "验证码已发送"
                    onSent()
                }
                .onFailure {
                    val apiError = it as? HuixiaoApiException
                    if (apiError?.statusCode == 404 && apiError.rawMessage == "ACCOUNT_NOT_REGISTERED") {
                        pendingRegistrationPhone = phone
                        pendingRegistrationCode = ""
                        pendingRegistrationSignal += 1
                        pendingLoginPhone = null
                        loginStatusText = "该手机号未注册，请先注册"
                        appState.showToast("该手机号未注册，请先注册")
                    } else {
                        loginStatusText = "验证码发送失败"
                        appState.showToast("验证码发送失败：${it.message ?: "未知错误"}")
                    }
                }
            smsSending = false
        }
    }

    fun sendRegisterSmsCodeFromUi(phone: String, onSent: () -> Unit = {}) {
        if (!requireLoginAgreement()) return
        if (smsSending) return
        smsSending = true
        loginStatusText = "正在发送验证码..."
        coroutineScope.launch {
            runCatching { appState.sendRegisterSmsCode(phone) }
                .onSuccess {
                    loginStatusText = "验证码已发送"
                    onSent()
                }
                .onFailure {
                    val apiError = it as? HuixiaoApiException
                    if (apiError?.statusCode == 409 && apiError.rawMessage == "ACCOUNT_ALREADY_REGISTERED") {
                        pendingRegistrationPhone = null
                        pendingRegistrationCode = ""
                        pendingLoginPhone = phone
                        pendingLoginSignal += 1
                        loginStatusText = "该手机号已注册，请直接登录"
                        appState.showToast("该手机号已注册，请直接登录")
                    } else {
                        loginStatusText = "验证码发送失败"
                        appState.showToast("验证码发送失败：${it.message ?: "未知错误"}")
                    }
                }
            smsSending = false
        }
    }

    fun loginCloudFromUi(phone: String, code: String) {
        if (!requireLoginAgreement()) return
        if (loginBusy) return
        loginBusy = true
        loginStatusText = "正在登录..."
        coroutineScope.launch {
            runCatching { appState.loginCloud(phone, code) }
                .onSuccess {
                    persistLoginAgreementConsent()
                    pendingRegistrationPhone = null
                    pendingRegistrationCode = ""
                    pendingLoginPhone = null
                    loginStatusText = null
                    appState.go(AppScreen.Home)
                }
                .onFailure {
                    val apiError = it as? HuixiaoApiException
                    if (apiError?.statusCode == 404 && apiError.rawMessage == "ACCOUNT_NOT_REGISTERED") {
                        pendingRegistrationPhone = phone
                        pendingRegistrationCode = code
                        pendingRegistrationSignal += 1
                        pendingLoginPhone = null
                        loginStatusText = "请设置密码完成注册"
                        appState.showToast("该手机号未注册，请设置密码完成注册")
                    } else {
                        loginStatusText = "登录失败"
                        appState.showToast("登录失败：${it.message ?: "未知错误"}")
                    }
                }
            loginBusy = false
        }
    }

    fun sendPasswordResetSmsCodeFromUi(phone: String, onSent: () -> Unit = {}) {
        if (!requireLoginAgreement()) return
        if (smsSending) return
        smsSending = true
        loginStatusText = "正在发送验证码..."
        coroutineScope.launch {
            runCatching { appState.sendPasswordResetSmsCode(phone) }
                .onSuccess {
                    loginStatusText = "验证码已发送"
                    onSent()
                }
                .onFailure {
                    val apiError = it as? HuixiaoApiException
                    if (apiError?.statusCode == 404 && apiError.rawMessage == "ACCOUNT_NOT_REGISTERED") {
                        pendingRegistrationPhone = phone
                        pendingRegistrationCode = ""
                        pendingRegistrationSignal += 1
                        pendingLoginPhone = null
                        loginStatusText = "该手机号未注册，请先注册"
                        appState.showToast("该手机号未注册，请先注册")
                    } else {
                        loginStatusText = "验证码发送失败"
                        appState.showToast("验证码发送失败：${it.message ?: "未知错误"}")
                    }
                }
            smsSending = false
        }
    }

    fun registerCloudFromUi(phone: String, code: String, password: String) {
        if (!requireLoginAgreement()) return
        if (loginBusy) return
        loginBusy = true
        loginStatusText = "正在注册..."
        coroutineScope.launch {
            runCatching { appState.registerCloudWithPassword(phone, code, password) }
                .onSuccess {
                    persistLoginAgreementConsent()
                    pendingRegistrationPhone = null
                    pendingRegistrationCode = ""
                    pendingLoginPhone = null
                    loginStatusText = null
                    appState.go(AppScreen.Home)
                }
                .onFailure {
                    loginStatusText = "注册失败"
                    appState.showToast("注册失败：${it.message ?: "未知错误"}")
                }
            loginBusy = false
        }
    }

    fun loginCloudWithPasswordFromUi(phone: String, password: String) {
        if (!requireLoginAgreement()) return
        if (loginBusy) return
        loginBusy = true
        loginStatusText = "正在登录..."
        coroutineScope.launch {
            runCatching { appState.loginCloudWithPassword(phone, password) }
                .onSuccess {
                    persistLoginAgreementConsent()
                    loginStatusText = null
                    appState.go(AppScreen.Home)
                }
                .onFailure {
                    loginStatusText = "登录失败"
                    appState.showToast("登录失败：${it.message ?: "未知错误"}")
                }
            loginBusy = false
        }
    }

    fun resetCloudPasswordFromUi(phone: String, code: String, password: String) {
        if (!requireLoginAgreement()) return
        if (loginBusy) return
        loginBusy = true
        loginStatusText = "正在重置密码..."
        coroutineScope.launch {
            runCatching { appState.resetCloudPassword(phone, code, password) }
                .onSuccess {
                    persistLoginAgreementConsent()
                    loginStatusText = null
                    appState.go(AppScreen.Home)
                }
                .onFailure {
                    loginStatusText = "重置失败"
                    appState.showToast("重置失败：${it.message ?: "未知错误"}")
                }
            loginBusy = false
        }
    }

    fun sendPhoneChangeSmsCodeFromUi(phone: String) {
        if (loginBusy) return
        loginBusy = true
        phoneChangeStatusText = "正在发送验证码..."
        coroutineScope.launch {
            runCatching { appState.sendPhoneChangeSmsCode(phone) }
                .onSuccess { phoneChangeStatusText = "验证码已发送" }
                .onFailure {
                    val message = it.userFacingMessage(
                        fallback = "验证码发送失败，请稍后重试",
                        networkAvailable = networkStatus.isNetworkAvailable()
                    )
                    phoneChangeStatusText = "验证码发送失败：$message"
                    appState.showToast("验证码发送失败：$message")
                }
            loginBusy = false
        }
    }

    fun verifyCurrentPhoneForChangeFromUi(oldPhone: String, oldCode: String, onVerified: (String) -> Unit) {
        if (loginBusy) return
        loginBusy = true
        phoneChangeStatusText = "正在验证当前手机号..."
        coroutineScope.launch {
            runCatching { appState.verifyCurrentPhoneForChange(oldPhone, oldCode) }
                .onSuccess { token ->
                    if (token.isNotBlank()) {
                        phoneChangeStatusText = "当前手机号已验证"
                        onVerified(token)
                    }
                }
                .onFailure {
                    val message = it.userFacingMessage(
                        fallback = "验证失败，请稍后重试",
                        networkAvailable = networkStatus.isNetworkAvailable()
                    )
                    phoneChangeStatusText = "验证失败：$message"
                    appState.showToast("验证失败：$message")
                }
            loginBusy = false
        }
    }

    fun changeCloudPhoneFromUi(oldPhone: String, oldVerificationToken: String, newPhone: String, newCode: String) {
        if (loginBusy) return
        loginBusy = true
        phoneChangeStatusText = "正在修改手机号..."
        coroutineScope.launch {
            runCatching { appState.changeCloudPhone(oldPhone, oldVerificationToken, newPhone, newCode) }
                .onSuccess { phoneChangeStatusText = "手机号已修改" }
                .onFailure {
                    val message = it.userFacingMessage(
                        fallback = "修改手机号失败，请稍后重试",
                        networkAvailable = networkStatus.isNetworkAvailable()
                    )
                    phoneChangeStatusText = "修改手机号失败：$message"
                    appState.showToast("修改手机号失败：$message")
                }
            loginBusy = false
        }
    }

    fun setCloudPasswordFromUi(password: String) {
        if (loginBusy) return
        loginBusy = true
        loginStatusText = "正在设置密码..."
        coroutineScope.launch {
            runCatching { appState.setCloudPassword(password) }
                .onSuccess { loginStatusText = "密码已设置" }
                .onFailure {
                    loginStatusText = "设置失败"
                    appState.showToast("设置失败：${it.message ?: "未知错误"}")
                }
            loginBusy = false
        }
    }

    fun changeCloudPasswordFromUi(oldPassword: String, newPassword: String) {
        if (loginBusy) return
        loginBusy = true
        loginStatusText = "正在修改密码..."
        coroutineScope.launch {
            runCatching { appState.changeCloudPassword(oldPassword, newPassword) }
                .onSuccess { loginStatusText = "密码已修改" }
                .onFailure {
                    loginStatusText = "修改失败"
                    appState.showToast("修改失败：${it.message ?: "未知错误"}")
                }
            loginBusy = false
        }
    }

    fun manageNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    fun nextAutoProcessingTaskId(): String? {
        val existingTaskIds = appState.localTasks.map { it.id }.toSet()
        if (queuedRetryTaskIds.any { it !in existingTaskIds }) {
            queuedRetryTaskIds = queuedRetryTaskIds.filter { it in existingTaskIds }
        }
        val retryTaskId = queuedRetryTaskIds.firstOrNull { id ->
            appState.localTasks.any { task ->
                task.id == id &&
                    (task.status == MeetingTaskStatus.Failed ||
                        (task.status == MeetingTaskStatus.WaitingProcess && task.progressStage == "waiting_retry"))
            }
        }
        return retryTaskId ?: appState.queuedImportTasks.firstOrNull()?.id
    }

    fun queuedProcessingTasksForGenerating(): List<MeetingTask> {
        val activeTaskId = appState.activeProcessingTask?.id
        val retryTasks = queuedRetryTaskIds.mapNotNull { id ->
            appState.localTasks.firstOrNull { task ->
                task.id == id &&
                    (task.status == MeetingTaskStatus.Failed ||
                        (task.status == MeetingTaskStatus.WaitingProcess && task.progressStage == "waiting_retry"))
            }
        }
        return (retryTasks + appState.queuedImportTasks)
            .distinctBy { it.id }
            .filter { it.id != activeTaskId }
    }

    fun queueRetryTask(taskId: String) {
        if (queuedRetryTaskIds.none { it == taskId }) {
            queuedRetryTaskIds = queuedRetryTaskIds + taskId
        }
    }

    var startNextAutoProcessingIfIdle: () -> Unit = {}

    fun startProcessingMonitor(launch: com.huiyi.app.state.ProcessingLaunch?) {
        if (launch == null) return
        val job = coroutineScope.launch {
            val shouldUpload = appState.processTask(launch.taskId, launch.runId, launch.retryRemote)
            val finishedSuccessfully = appState.localTasks.any { task ->
                task.id == launch.taskId && task.status == MeetingTaskStatus.Finished
            }
            if (shouldUpload) {
                launch {
                    runCatching { appState.syncPendingLocalResultsToCloud() }
                        .onFailure { appState.showToast("已保存在本机，云端稍后重试") }
                }
            }
            queuedRetryTaskIds = queuedRetryTaskIds.filterNot { it == launch.taskId }
            if (finishedSuccessfully) {
                startNextAutoProcessingIfIdle()
            }
        }
        appState.attachProcessingJob(launch.taskId, job)
    }

    startNextAutoProcessingIfIdle = autoStart@{
        if (appState.activeProcessingTask != null) return@autoStart
        val nextTaskId = nextAutoProcessingTaskId() ?: return@autoStart
        val launch = appState.beginProcessingTask(nextTaskId) ?: return@autoStart
        startProcessingMonitor(launch)
    }

    fun launchProcessing(launch: com.huiyi.app.state.ProcessingLaunch?) {
        if (launch == null) {
            appState.showToast(if (appState.activeProcessingTask != null) "已有文件正在处理" else "请先选择待处理文件")
            return
        }
        appState.go(AppScreen.Generating)
        startProcessingMonitor(launch)
    }

    fun queueTasksForProcessing(taskIds: Collection<String>, closeSheet: Boolean = true, showQueuedToast: Boolean = true) {
        val cleanIds = taskIds.distinct().filter { it.isNotBlank() }
        if (cleanIds.isEmpty()) {
            appState.showToast("请先选择待处理文件")
            return
        }
        if (!ensureTranscriptionQuotaForUi()) {
            if (closeSheet) appState.closeSheet()
            return
        }
        if (closeSheet) appState.closeSheet()
        val activeTask = appState.activeProcessingTask
        if (activeTask != null) {
            if (showQueuedToast) appState.showToast("已加入待处理，当前任务完成后继续")
            appState.openProcessingTask(activeTask.id)
            appState.go(AppScreen.Generating)
            return
        }
        val nextTaskId = cleanIds
            .firstOrNull { id -> appState.localTasks.any { task -> task.id == id && task.status == MeetingTaskStatus.WaitingProcess } }
            ?: nextAutoProcessingTaskId()
        val launch = nextTaskId?.let { appState.beginProcessingTask(it) }
        if (launch != null) {
            appState.go(AppScreen.Generating)
            startProcessingMonitor(launch)
        }
        if (showQueuedToast && cleanIds.size > 1) {
            appState.showToast("已开始处理 ${cleanIds.size} 个文件")
        }
    }

    fun queueWaitingTasksForProcessing() {
        val waitingIds = appState.queuedImportTasks.map { it.id }
        queueTasksForProcessing(waitingIds)
    }

    guardedProcessingLaunch = { createLaunch ->
        if (!ensureTranscriptionQuotaForUi()) {
            appState.closeSheet()
        } else {
            val launch = createLaunch()
            if (launch != null) {
                appState.closeSheet()
            }
            launchProcessing(launch)
        }
    }

    fun openProcessingTask(taskId: String) {
        if (appState.openProcessingTask(taskId)) {
            appState.go(AppScreen.Generating)
            startProcessingMonitor(appState.resumeProcessingTaskIfNeeded(taskId))
        }
    }

    fun openOrQueueProcessingTask(taskId: String) {
        val task = appState.localTasks.firstOrNull { it.id == taskId } ?: return
        val activeTask = appState.activeProcessingTask
        if (activeTask != null && activeTask.id != task.id) {
            if (task.status == MeetingTaskStatus.Failed ||
                (task.status == MeetingTaskStatus.WaitingProcess && task.progressStage == "waiting_retry")
            ) {
                queueRetryTask(task.id)
                appState.showToast("已加入待处理，当前任务完成后继续")
            }
            appState.openProcessingTask(activeTask.id)
            appState.go(AppScreen.Generating)
            return
        }
        when (task.status) {
            MeetingTaskStatus.Processing -> openProcessingTask(task.id)
            MeetingTaskStatus.WaitingProcess -> {
                if (task.progressStage == "waiting_retry") {
                    if (appState.openLocalProcessingTask(task.id)) appState.go(AppScreen.Generating)
                } else {
                    queueTasksForProcessing(listOf(task.id), showQueuedToast = false)
                }
            }
            MeetingTaskStatus.Failed -> {
                if (appState.openLocalProcessingTask(task.id)) appState.go(AppScreen.Generating)
            }
            else -> Unit
        }
    }

    fun retryProcessingTask(taskId: String) {
        val activeTask = appState.activeProcessingTask
        if (activeTask != null && activeTask.id != taskId) {
            queueRetryTask(taskId)
            appState.showToast("已加入待处理，当前任务完成后继续")
            appState.openProcessingTask(activeTask.id)
            appState.go(AppScreen.Generating)
            return
        }
        guardedProcessingLaunch { appState.beginProcessingTask(taskId) }
    }

    fun runExportOperation(statusText: String, action: suspend () -> Unit) {
        if (exportBusy) return
        exportBusy = true
        exportStatusText = statusText
        coroutineScope.launch {
            try {
                action()
            } catch (error: Throwable) {
                appState.closeSheet()
                appState.showToast("导出失败：${friendlyError(error)}")
            } finally {
                exportBusy = false
                exportStatusText = null
            }
        }
    }

    fun stopRecordingAndGo(target: AppScreen) {
        RecordingForegroundService.stop(context.applicationContext)
        appState.pendingRecordingFinishTarget = if (appState.recording.status == RecordingStatus.Preparing && target == AppScreen.Generating) {
            AppScreen.Home
        } else {
            target
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            audioSegmentPlayer.stop()
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permissionStatus = readPermissionStatus(context)
                val orderId = pendingWebPaymentOrderId
                if (orderId != null) {
                    coroutineScope.launch {
                        paymentBusy = true
                        paymentBusyText = "正在确认支付结果"
                        runCatching {
                            val status = confirmAlipayPaymentResult(orderId)
                            if (status == "支付成功") {
                                appState.showToast("支付成功，权益已到账")
                            } else if (status == "未支付") {
                                appState.showToast("订单未支付")
                            } else {
                                appState.showToast("暂未确认支付完成，请稍后在订单记录刷新")
                            }
                        }.onFailure { error ->
                            appState.showToast(error.userFacingMessage(networkAvailable = networkStatus.isNetworkAvailable()))
                        }.also {
                            pendingWebPaymentOrderId = null
                            paymentBusy = false
                            paymentBusyText = ""
                        }
                    }
                } else if (loggedIn) {
                    coroutineScope.launch {
                        runCatching { appState.refreshMembershipProfile() }
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        launch {
            RecordingSessionBus.state.collect { state ->
                appState.applyRecordingServiceState(state)
                if (state.status == RecordingStatus.Preparing || state.status == RecordingStatus.Recording || state.status == RecordingStatus.Paused) {
                    appState.closeSheet()
                    appState.go(AppScreen.Record)
                }
            }
        }
        launch {
            RecordingSessionBus.events.collect { event ->
                when (event) {
                    is RecordingServiceEvent.Segments -> appState.appendLiveTranscriptSegments(event.segments, event.isFinal, event.replaceAll)
                    is RecordingServiceEvent.Error -> appState.showToast(event.message)
                    is RecordingServiceEvent.Stopped -> {
                        if (appState.isVoiceprintEnrollmentRecording) {
                            return@collect
                        }
                        val target = appState.pendingRecordingFinishTarget ?: AppScreen.Home
                        val taskId = appState.finishRecording(event.localFilePath)
                        pendingStoppedRecordingTaskId = taskId
                        if (target == AppScreen.Generating && taskId != null) {
                            appState.closeSheet()
                            appState.go(AppScreen.Generating)
                        } else {
                            appState.pendingRecordingFinishTarget = null
                            if (appState.screen == AppScreen.Record) {
                                appState.go(target)
                            }
                        }
                    }
                    is RecordingServiceEvent.Finished -> {
                        val voiceprintName = appState.consumeVoiceprintEnrollmentName()
                        if (voiceprintName != null) {
                            pendingStoppedRecordingTaskId = null
                            val localFilePath = appState.finishVoiceprintEnrollmentRecording(event.localFilePath)
                            appState.pendingRecordingFinishTarget = null
                            appState.go(AppScreen.Voiceprints)
                            if (localFilePath == null) {
                                appState.showToast("声纹录音文件不存在")
                            } else {
                                voiceprintBusy = true
                                voiceprintStatusText = "正在从录音采样录入声纹..."
                                launch {
                                    runCatching { appState.enrollSpeakerProfileFromAudio(voiceprintName, localFilePath) }
                                        .onSuccess { voiceprintStatusText = "声纹录入成功" }
                                        .onFailure { error ->
                                            voiceprintStatusText = "声纹录入失败"
                                            appState.showToast("声纹录入失败：${friendlyError(error)}")
                                        }
                                    voiceprintBusy = false
                                }
                            }
                        } else {
                            val target = appState.pendingRecordingFinishTarget ?: AppScreen.Home
                            val taskId = pendingStoppedRecordingTaskId ?: appState.finishRecording(event.localFilePath)
                            pendingStoppedRecordingTaskId = null
                            if (taskId != null) {
                                appState.finalizeRecordingTranscript(taskId)
                            }
                            appState.pendingRecordingFinishTarget = null
                            if (target == AppScreen.Generating && taskId != null) {
                                queueTasksForProcessing(listOf(taskId), closeSheet = false, showQueuedToast = appState.activeProcessingTask != null)
                            } else {
                                if (appState.screen == AppScreen.Record || appState.screen == AppScreen.Generating) {
                                    appState.go(target)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        val dir = File(context.filesDir, "recordings")
        val recovered = dir.listFiles()
            ?.filter { it.isFile && it.length() > 4096L }
            ?.count { file ->
                appState.recoverLocalRecording(file.absolutePath, file.length().toReadableSizeLabel())
            }
            ?: 0
        if (recovered > 0) {
            appState.showToast("发现 $recovered 条未完成录音，已加入待处理")
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            val previousReminderId = appState.reminderScheduleId
            appState.checkScheduleReminders()
            val meeting = appState.reminderSchedule
            if (meeting != null && meeting.id !in notifiedScheduleIds) {
                val posted = scheduleStatusNotifier.notifyMeetingDue(meeting)
                notifiedScheduleIds = notifiedScheduleIds + meeting.id
                if (!posted && meeting.id != previousReminderId) {
                    appState.showToast("会议即将开始；通知权限未开启，无法显示状态栏提醒")
                }
            }
            delay(30_000)
        }
    }

    LaunchedEffect(screen) {
        if (screen == AppScreen.Home) {
            appState.refreshScheduledMeetings()
        }
    }

    LaunchedEffect(loggedIn, appState.cloudUser?.userId) {
        if (loggedIn) {
            launch { appState.refreshSpeakerProfiles() }
            launch {
                runCatching { appState.refreshMembershipProfile() }
                    .onFailure {
                        if (!appState.membershipProfile.loading && appState.membershipProfile.plans.isEmpty()) {
                            appState.showToast("会员信息刷新失败：${it.userFacingMessage(networkAvailable = networkStatus.isNetworkAvailable())}")
                        }
                    }
            }
        }
    }

    LaunchedEffect(toast) {
        if (toast.isNotBlank()) {
            delay(2400)
            appState.clearToast()
        }
    }

    BackHandler(loggedIn && screen != AppScreen.Home && screen.root.not()) {
        when (screen) {
            AppScreen.Record -> appState.showToast(if (appState.isVoiceprintEnrollmentRecording) "声纹录入中，请点击结束后录入声纹" else "录音中，请点击结束后生成会议纪要")
            AppScreen.Detail -> appState.backFromDetail()
            AppScreen.Membership -> appState.go(AppScreen.Profile)
            AppScreen.PaymentOrders -> appState.go(AppScreen.Membership)
            AppScreen.Voiceprints -> appState.go(AppScreen.Profile)
            AppScreen.Generating -> {
                coroutineScope.launch { appState.closeGeneratingTask() }
            }
            else -> appState.backHome()
        }
    }

    BackHandler(paymentBusy) {
        showPriorityToast("支付处理中，请稍候")
    }

    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Brand,
            secondary = BrandCyan,
            background = AppBg,
            surface = Color.White,
            onSurface = Ink
        )
    ) {
        Box(Modifier.fillMaxSize().background(AppBg)) {
            if (!loggedIn) {
                LoginScreen(
                    loginBusy = loginBusy,
                    smsSending = smsSending,
                    loginStatusText = loginStatusText,
                    agreementAccepted = loginAgreementAccepted,
                    pendingRegistrationPhone = pendingRegistrationPhone,
                    pendingRegistrationCode = pendingRegistrationCode,
                    pendingRegistrationSignal = pendingRegistrationSignal,
                    pendingLoginPhone = pendingLoginPhone,
                    pendingLoginSignal = pendingLoginSignal,
                    onAgreementAcceptedChange = ::setLoginAgreementAccepted,
                    onSendLoginSmsCode = ::sendLoginSmsCodeFromUi,
                    onSendRegisterSmsCode = ::sendRegisterSmsCodeFromUi,
                    onSendPasswordResetSmsCode = ::sendPasswordResetSmsCodeFromUi,
                    onLogin = ::loginCloudFromUi,
                    onPasswordLogin = ::loginCloudWithPasswordFromUi,
                    onRegister = ::registerCloudFromUi,
                    onResetPassword = ::resetCloudPasswordFromUi,
                    onUserAgreement = { appState.showSheet(SheetType.UserAgreement) },
                    onPrivacyPolicy = { appState.showSheet(SheetType.PrivacyPolicy) },
                    onAgreementRequired = { requireLoginAgreement() }
                )
            } else {
                val imeVisible = WindowInsets.isImeVisible
                Scaffold(
                    containerColor = AppBg,
                    bottomBar = {
                        if (screen.root && !imeVisible) {
                            SmartBottomNavigationBar(
                                roots = roots,
                                current = screen,
                                onSelect = { appState.go(it) }
                            )
                        }
                    }
                ) { padding ->
                    Box(
                        Modifier
                            .padding(padding)
                            .consumeWindowInsets(padding)
                            .fillMaxSize()
                    ) {
                        when (screen) {
                        AppScreen.Home -> HomeScreen(
                            dashboard = appState.repository.getHomeDashboard(),
                            recentMeetings = appState.recentMeetings,
                            scheduledMeetings = appState.activeScheduledMeetings,
                            localTasks = appState.localTasks,
                            onStart = {
                                appState.prepareRecordingForSchedule(recognitionLanguage = selectedRecognitionLanguage)
                                appState.showSheet(SheetType.RecordConsent)
                            },
                            onImport = { appState.showSheet(SheetType.ImportFile) },
                            onCreateMeeting = { appState.beginCreatingSchedule() },
                            onAllSchedules = { appState.go(AppScreen.Schedules) },
                            onAllMeetings = { appState.go(AppScreen.Meetings) },
                            onSearch = { appState.go(AppScreen.Search) },
                            onProfile = { appState.go(AppScreen.Profile) },
                            onCloudSync = { appState.openProfileCloudSync() },
                            onDetail = { meetingId -> appState.openMeeting(meetingId) },
                            onDeleteMeeting = { meetingId ->
                                appState.requestDeleteMeeting(meetingId)
                            },
                            onDeleteSchedule = { scheduleId ->
                                appState.requestDeleteSchedule(scheduleId)
                            },
                            onEditSchedule = { scheduleId -> appState.beginEditingSchedule(scheduleId) },
                            onStartSchedule = { meeting ->
                                appState.prepareRecordingForSchedule(meeting, selectedRecognitionLanguage)
                                appState.showSheet(SheetType.RecordConsent)
                            },
                            onProcessingTask = { task ->
                                openProcessingTask(task.id)
                            }
                        )
                        AppScreen.Schedules -> ScheduleListScreen(
                            meetings = appState.activeScheduledMeetings,
                            onBack = { appState.backHome() },
                            onCreateMeeting = { appState.beginCreatingSchedule() },
                            onStart = { meeting ->
                                appState.prepareRecordingForSchedule(meeting, selectedRecognitionLanguage)
                                appState.showSheet(SheetType.RecordConsent)
                            },
                            onDeleteSchedule = { scheduleId -> appState.requestDeleteSchedule(scheduleId) },
                            onEditSchedule = { scheduleId -> appState.beginEditingSchedule(scheduleId) }
                        )
                        AppScreen.Meetings -> MeetingListScreen(
                            meetings = appState.recentMeetings,
                            processingTasks = appState.localTasks,
                            bulkDeleting = bulkMeetingDeleting,
                            onBack = { appState.backHome() },
                            onDetail = { meetingId -> appState.openMeeting(meetingId) },
                            onProcessingTask = { task -> openProcessingTask(task.id) },
                            onDeleteMeeting = { meetingId ->
                                appState.requestDeleteMeeting(meetingId)
                            },
                            onDeleteMeetings = { meetingIds ->
                                if (!bulkMeetingDeleting) {
                                    bulkMeetingDeleting = true
                                    coroutineScope.launch {
                                        runCatching { appState.deleteMeetings(meetingIds) }
                                            .onSuccess { count ->
                                                if (count > 0) {
                                                    appState.showToast("已删除 ${count} 场会议")
                                                    launch {
                                                        runCatching { appState.syncPendingLocalResultsToCloud() }
                                                            .onFailure { appState.showToast("已保存在本机，云端稍后重试") }
                                                    }
                                                }
                                            }
                                            .onFailure { appState.showToast("删除失败：${friendlyError(it)}") }
                                        bulkMeetingDeleting = false
                                    }
                                }
                            }
                        )
                        AppScreen.Tasks -> TasksScreen(
                            todos = appState.allTodos,
                            localTasks = appState.localTasks,
                            currentUserName = appState.profileName,
                            mineOnly = appState.todoMineOnly,
                            filter = appState.todoFilter,
                            todayCount = appState.todoTodayCount,
                            overdueCount = appState.todoOverdueCount,
                            onBackHome = { appState.go(AppScreen.Home) },
                            onTask = { task ->
                                if (task.status == MeetingTaskStatus.Finished) {
                                    appState.openMeeting(task.id)
                                } else if (task.status == MeetingTaskStatus.Processing) {
                                    openProcessingTask(task.id)
                                } else {
                                    openOrQueueProcessingTask(task.id)
                                }
                            },
                            onDetail = { meetingId -> appState.openMeeting(meetingId) },
                            onDeleteTask = { task ->
                                appState.requestDeleteTask(task)
                            },
                            onMineOnly = appState::toggleTodoMineOnly,
                            onFilter = appState::toggleTodoFilter,
                            onRefresh = {
                                coroutineScope.launch {
                                    runCatching { appState.pullCloudData() }
                                        .onFailure { appState.showToast("刷新失败：${friendlyError(it)}") }
                                }
                            },
                            onTodoDetail = { todo -> appState.showTodoDetail(todo) },
                            onTodoSource = appState::openTodoSource
                        )
                        AppScreen.Knowledge -> KnowledgeScreen(
                            topics = emptyList(),
                            question = appState.knowledgeQuestion,
                            messages = appState.knowledgeMessages,
                            loading = appState.knowledgeLoading,
                            onBackHome = { appState.go(AppScreen.Home) },
                            onQuestionChange = appState::updateKnowledgeQuestion,
                            onAsk = { question ->
                                if (ensureKnowledgeQuotaForUi()) {
                                    coroutineScope.launch { appState.askKnowledge(question) }
                                }
                            },
                            onCancel = { appState.cancelKnowledgeAnswer() },
                            onRetryQuestion = { question -> appState.updateKnowledgeQuestion(question) },
                            onSourceClick = appState::openKnowledgeSource,
                            onTopicClick = appState::openMeeting
                        )
                        AppScreen.Profile -> ProfileScreen(
                            cloudUser = appState.cloudUser,
                            membershipProfile = appState.membershipProfile,
                            profileName = appState.profileName,
                            cloudSyncEnabled = appState.cloudSyncEnabled,
                            cloudSyncInProgress = appState.cloudSyncInProgress,
                            cloudSyncStatusText = appState.cloudSyncStatusText,
                            cloudSyncFocusRequest = appState.profileCloudSyncFocusRequest,
                            onCloudSyncFocusConsumed = appState::consumeProfileCloudSyncFocusRequest,
                            unsyncedMeetingCount = appState.unsyncedFinishedMeetingCount,
                            speakerProfileCount = appState.speakerProfiles.size,
                            meetingCount = appState.recentMeetings.size,
                            todoCount = appState.allTodos.size,
                            loginBusy = loginBusy,
                            smsSending = smsSending,
                            loginStatusText = if (appState.cloudUser == null) loginStatusText else phoneChangeStatusText,
                            agreementAccepted = loginAgreementAccepted,
                            pendingRegistrationPhone = pendingRegistrationPhone,
                            pendingRegistrationCode = pendingRegistrationCode,
                            pendingRegistrationSignal = pendingRegistrationSignal,
                            pendingLoginPhone = pendingLoginPhone,
                            pendingLoginSignal = pendingLoginSignal,
                            onAgreementAcceptedChange = ::setLoginAgreementAccepted,
                            permissionStatus = permissionStatus,
                            onBackHome = { appState.go(AppScreen.Home) },
                            onSendLoginSmsCode = ::sendLoginSmsCodeFromUi,
                            onSendRegisterSmsCode = ::sendRegisterSmsCodeFromUi,
                            onSendPasswordResetSmsCode = ::sendPasswordResetSmsCodeFromUi,
                            onLogin = ::loginCloudFromUi,
                            onPasswordLogin = ::loginCloudWithPasswordFromUi,
                            onRegister = ::registerCloudFromUi,
                            onResetPassword = ::resetCloudPasswordFromUi,
                            onSendPhoneChangeSmsCode = ::sendPhoneChangeSmsCodeFromUi,
                            onVerifyCurrentPhoneForChange = ::verifyCurrentPhoneForChangeFromUi,
                            onChangePhone = ::changeCloudPhoneFromUi,
                            onClearPhoneChangeStatus = { phoneChangeStatusText = null },
                            onLogout = {
                                loginStatusText = null
                                phoneChangeStatusText = null
                                appState.requestLogoutCloud()
                            },
                            onUserAgreement = { appState.showSheet(SheetType.UserAgreement) },
                            onAgreementRequired = { requireLoginAgreement() },
                            onSaveProfileName = appState::saveProfileName,
                            onCloudSyncChange = { enabled ->
                                coroutineScope.launch { appState.setCloudSyncEnabled(enabled) }
                            },
                            onUploadUnsynced = {
                                coroutineScope.launch {
                                    runCatching { appState.uploadAllUnsyncedMeetings() }
                                        .onFailure { appState.showToast("上传失败：${friendlyError(it)}") }
                                }
                            },
                            onPullCloud = {
                                coroutineScope.launch {
                                    runCatching { appState.pullCloudData() }
                                        .onFailure { appState.showToast("同步失败：${friendlyError(it)}") }
                                }
                            },
                            onMembership = { appState.go(AppScreen.Membership) },
                            onOrders = { appState.go(AppScreen.PaymentOrders) },
                            onVoiceprints = { appState.go(AppScreen.Voiceprints) },
                            onMicrophonePermission = { openAppPermissionSettings() },
                            onNotificationPermission = { manageNotificationPermission() },
                            onFilePermission = { appState.showToast("导入时会打开系统文件选择器，按次授权") },
                            onPrivacyPolicy = { appState.showSheet(SheetType.PrivacyPolicy) },
                            onDelete = { appState.showSheet(SheetType.DeleteData) }
                        )
                        AppScreen.Membership -> {
                            LaunchedEffect(appState.cloudUser?.userId) {
                                coroutineScope.launch {
                                    runCatching { appState.refreshMembershipProfile() }
                                        .onFailure {
                                            if (appState.membershipProfile.plans.isEmpty()) {
                                                appState.showToast("会员信息刷新失败：${it.userFacingMessage(networkAvailable = networkStatus.isNetworkAvailable())}")
                                            }
                                        }
                                }
                            }
                            MembershipScreen(
                                membership = appState.membershipProfile,
                                onBack = { appState.go(AppScreen.Profile) },
                                onPurchase = { plan ->
                                    startAlipayPayment(plan.name) { appState.createAlipayPaymentOrder(plan.id) }
                                },
                                onAddonPurchase = { addon ->
                                    startAlipayPayment(addon.name) { appState.createAlipayAddonPaymentOrder(addon.id) }
                                },
                                onOrders = { appState.go(AppScreen.PaymentOrders) }
                            )
                        }
                        AppScreen.PaymentOrders -> {
                            LaunchedEffect(appState.cloudUser?.userId) {
                                coroutineScope.launch {
                                    runCatching { appState.refreshPaymentOrders() }
                                        .onFailure { appState.showToast("订单刷新失败：${it.userFacingMessage(networkAvailable = networkStatus.isNetworkAvailable())}") }
                                }
                            }
                            PaymentOrdersScreen(
                                orders = appState.paymentOrders,
                                loading = appState.paymentOrdersLoading,
                                onBack = { appState.go(AppScreen.Membership) },
                                onRefresh = {
                                    coroutineScope.launch {
                                        runCatching { appState.refreshPaymentOrders(force = true) }
                                            .onSuccess { appState.showToast("订单已刷新") }
                                            .onFailure { appState.showToast("订单刷新失败：${it.userFacingMessage(networkAvailable = networkStatus.isNetworkAvailable())}") }
                                    }
                                },
                                onSync = { orderId ->
                                    coroutineScope.launch {
                                        runCatching { appState.syncAlipayPaymentOrderAndRefresh(orderId) }
                                            .onSuccess { status ->
                                                appState.showToast(if (status.isBlank()) "订单状态已刷新" else "订单状态：$status")
                                            }
                                            .onFailure { appState.showToast("订单状态刷新失败：${it.userFacingMessage(networkAvailable = networkStatus.isNetworkAvailable())}") }
                                    }
                                },
                                onCopyOrderId = { orderId ->
                                    copyText(context, "订单号", orderId)
                                    appState.showToast("订单号已复制")
                                }
                            )
                        }
                        AppScreen.Voiceprints -> {
                            LaunchedEffect(appState.cloudUser?.userId) {
                                appState.refreshSpeakerProfiles()
                            }
                            VoiceprintScreen(
                                cloudUser = appState.cloudUser,
                                profiles = appState.speakerProfiles,
                                busy = voiceprintBusy,
                                statusText = voiceprintStatusText,
                                onBack = { appState.go(AppScreen.Profile) },
                                onRefresh = {
                                    if (!voiceprintBusy) {
                                        voiceprintBusy = true
                                        voiceprintStatusText = "正在刷新声纹库..."
                                        coroutineScope.launch {
                                            runCatching { appState.refreshSpeakerProfiles() }
                                                .onSuccess { voiceprintStatusText = "声纹库已刷新" }
                                                .onFailure {
                                                    voiceprintStatusText = "声纹库刷新失败"
                                                    appState.showToast("声纹库刷新失败：${friendlyError(it)}")
                                                }
                                            voiceprintBusy = false
                                        }
                                    }
                                },
                                onRecordSample = { name ->
                                    if (appState.cloudUser == null) {
                                        appState.showToast("请先登录后再录入声纹")
                                    } else if (name.trim().isBlank()) {
                                        appState.showToast("请先填写声纹姓名")
                                    } else {
                                        appState.prepareVoiceprintEnrollment(name)
                                        voiceprintStatusText = "请录制 15-30 秒清晰人声，结束后会自动录入声纹"
                                        appState.showToast("请录制 15-30 秒清晰人声")
                                        appState.showSheet(SheetType.RecordConsent)
                                    }
                                },
                                onImportSample = { name ->
                                    if (appState.cloudUser == null) {
                                        appState.showToast("请先登录后再录入声纹")
                                    } else if (name.trim().isBlank()) {
                                        appState.showToast("请先填写声纹姓名")
                                    } else {
                                        pendingVoiceprintImportName = name.trim().takeIf { it.isNotBlank() }
                                        voiceprintStatusText = "请选择一段清晰人声采样音频"
                                        openFilePicker()
                                    }
                                },
                                onRenameProfile = { profile, name ->
                                    if (!voiceprintBusy) {
                                        voiceprintBusy = true
                                        voiceprintStatusText = "正在更新声纹名称..."
                                        coroutineScope.launch {
                                            runCatching { appState.updateSpeakerProfile(profile.id, displayName = name) }
                                                .onSuccess { voiceprintStatusText = "声纹名称已更新" }
                                                .onFailure {
                                                    voiceprintStatusText = "声纹改名失败"
                                                    appState.showToast("声纹改名失败：${friendlyError(it)}")
                                                }
                                            voiceprintBusy = false
                                        }
                                    }
                                },
                                onToggleProfile = { profile, active ->
                                    if (!voiceprintBusy) {
                                        voiceprintBusy = true
                                        voiceprintStatusText = if (active) "正在启用声纹..." else "正在停用声纹..."
                                        coroutineScope.launch {
                                            runCatching { appState.updateSpeakerProfile(profile.id, active = active) }
                                                .onSuccess { voiceprintStatusText = if (active) "声纹已启用" else "声纹已停用" }
                                                .onFailure {
                                                    voiceprintStatusText = "声纹状态更新失败"
                                                    appState.showToast("声纹状态更新失败：${friendlyError(it)}")
                                                }
                                            voiceprintBusy = false
                                        }
                                    }
                                },
                                onDeleteProfile = { profile ->
                                    if (!voiceprintBusy) {
                                        voiceprintBusy = true
                                        voiceprintStatusText = "正在删除声纹..."
                                        coroutineScope.launch {
                                            runCatching { appState.deleteSpeakerProfile(profile.id) }
                                                .onSuccess { voiceprintStatusText = "声纹档案已删除" }
                                                .onFailure {
                                                    voiceprintStatusText = "声纹删除失败"
                                                    appState.showToast("声纹删除失败：${friendlyError(it)}")
                                                }
                                            voiceprintBusy = false
                                        }
                                    }
                                }
                            )
                        }
                        AppScreen.Import -> ImportScreen(
                            tasks = appState.localTasks,
                            onBack = { appState.backHome() },
                            onPickFile = { openFilePicker() },
                            onSubmit = {
                                queueWaitingTasksForProcessing()
                            },
                            onDeleteTask = { task ->
                                appState.requestDeleteTask(task)
                            },
                            onProcessingTask = { task ->
                                openOrQueueProcessingTask(task.id)
                            }
                        )
                        AppScreen.Search -> SearchScreen(
                            recentSearches = appState.recentSearchKeywords,
                            recentMeetings = appState.recentMeetings,
                            onBack = { appState.backHome() },
                            onSearchCommit = appState::recordRecentSearch,
                            onDetail = { meetingId -> appState.openMeeting(meetingId) }
                        )
                        AppScreen.Record -> RecordScreen(
                            recording = appState.recording,
                            segments = appState.liveTranscriptSegments,
                            onBack = { appState.showToast(if (appState.isVoiceprintEnrollmentRecording) "声纹录入中，请点击结束后录入声纹" else "录音中，请点击结束后生成会议纪要") },
                            onPauseToggle = {
                                if (appState.recording.status == RecordingStatus.Paused) {
                                    runCatching { RecordingForegroundService.resume(context.applicationContext) }
                                        .onSuccess { appState.resumeRecording() }
                                        .onFailure { appState.showToast("继续录音失败：${friendlyError(it)}") }
                                } else {
                                    runCatching { RecordingForegroundService.pause(context.applicationContext) }
                                        .onSuccess { appState.pauseRecording() }
                                        .onFailure { appState.showToast("暂停录音失败：${friendlyError(it)}") }
                                }
                            },
                            onFinish = { stopRecordingAndGo(if (appState.isVoiceprintEnrollmentRecording) AppScreen.Voiceprints else AppScreen.Generating) }
                        )
                        AppScreen.Generating -> GeneratingScreen(
                            task = appState.selectedTask,
                            queuedTasks = queuedProcessingTasksForGenerating(),
                            onClose = {
                                coroutineScope.launch {
                                    appState.closeGeneratingTask()
                                }
                            },
                            onCancel = { taskId ->
                                coroutineScope.launch {
                                    val remoteTaskId = appState.cancelProcessingTask(taskId)
                                    remoteTaskId?.let {
                                        launch { appState.cancelRemoteProcessingTask(it) }
                                    }
                                }
                            },
                            onRetry = { taskId ->
                                retryProcessingTask(taskId)
                            },
                            onDetail = { meetingId -> appState.openMeeting(meetingId) }
                        )
                        AppScreen.Detail -> {
                            val detailMeeting = appState.selectedMeeting
                            DetailScreen(
                                meeting = detailMeeting,
                                onBack = { appState.backFromDetail() },
                                onSource = { index -> appState.showSourceSheet(index) },
                                onExport = { appState.showSheet(SheetType.Export) },
                                onEdit = { appState.showSheet(SheetType.EditMinutes) },
                                onEditInfo = { appState.showSheet(SheetType.EditMeetingInfo) },
                                onSpeakers = { appState.showSpeakerSheet() },
                                showRegenerateMinutes = detailMeeting.id in appState.transcriptEditedTaskIds,
                                minutesRefreshing = detailMeeting.id in appState.regeneratingMinutesTaskIds,
                                onRegenerateMinutes = {
                                    coroutineScope.launch {
                                        appState.regenerateSelectedMeetingMinutes()
                                    }
                                },
                                onAddTodo = { appState.showCreateTodoForSelectedMeeting() },
                                onEditSegmentSpeaker = { index -> appState.showSpeakerSheet(index) },
                                onCopySegment = { segment ->
                                    copyText(context, segment.speaker, "${segment.speaker}：${segment.text}")
                                    appState.showToast("已复制")
                                },
                                onTodoDetail = { todo -> appState.showTodoDetail(todo) },
                                onConfirm = { appState.confirmSelectedMeeting() },
                                onDelete = { appState.requestDeleteMeeting(detailMeeting.id) }
                            )
                        }
                    }
                }
            }
            }

            if (paymentBusy && paymentBusyText.isNotBlank()) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.34f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {}
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        color = Color.White,
                        shape = RoundedCornerShape(24.dp),
                        shadowElevation = 8.dp
                    ) {
                        Column(
                            Modifier.padding(horizontal = 28.dp, vertical = 24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            CircularProgressIndicator(color = Brand, strokeWidth = 3.dp, modifier = Modifier.size(34.dp))
                            Text(paymentBusyText, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            Text("请勿重复点击或切换页面", color = Muted, fontSize = 12.sp)
                        }
                    }
                }
            }

            if (frozenAccountDialogVisible) {
                AlertDialog(
                    onDismissRequest = { frozenAccountDialogVisible = false },
                    title = { Text("账号状态异常", color = Ink, fontWeight = FontWeight.Bold) },
                    text = { Text("该账户状态异常已经被冻结", color = Muted, lineHeight = 22.sp) },
                    confirmButton = {
                        TextButton(onClick = { frozenAccountDialogVisible = false }) {
                            Text("确认", color = Brand, fontWeight = FontWeight.Bold)
                        }
                    },
                    containerColor = Color.White,
                    shape = RoundedCornerShape(24.dp)
                )
            }

            if (loggedIn || sheet == SheetType.UserAgreement || sheet == SheetType.PrivacyPolicy) {
                sheet?.let { active ->
                    val todoDetailSheetContent: @Composable () -> Unit = {
                        TodoDetailSheet(
                            todo = appState.selectedTodo,
                            saving = todoSaving,
                            onSave = { title, assignee, dueAt, priority, description, status ->
                                val todo = appState.selectedTodo ?: return@TodoDetailSheet
                                if (todoSaving) return@TodoDetailSheet
                                todoSaving = true
                                coroutineScope.launch {
                                    runCatching {
                                        appState.updateTodo(todo, title, assignee, dueAt, priority, description, status)
                                    }.onSuccess { saved ->
                                        if (saved) {
                                            appState.closeSheet()
                                            coroutineScope.launch {
                                                runCatching { appState.syncPendingLocalResultsToCloud() }
                                                    .onFailure { appState.showToast("已保存在本机，云端稍后重试") }
                                            }
                                        }
                                    }.onFailure { appState.showToast("待办保存失败：${friendlyError(it, "网络连接失败")}") }
                                    todoSaving = false
                                }
                            },
                            onDelete = {
                                val todo = appState.selectedTodo ?: return@TodoDetailSheet
                                if (todoSaving) return@TodoDetailSheet
                                todoSaving = true
                                coroutineScope.launch {
                                    runCatching {
                                        appState.deleteTodo(todo)
                                    }.onSuccess { deleted ->
                                        if (deleted) {
                                            appState.closeSheet()
                                            coroutineScope.launch {
                                                runCatching { appState.syncPendingLocalResultsToCloud() }
                                                    .onFailure { appState.showToast("已保存在本机，云端稍后重试") }
                                            }
                                        }
                                    }.onFailure { appState.showToast("待办删除失败：${friendlyError(it, "网络连接失败")}") }
                                    todoSaving = false
                                }
                            },
                            onClose = { appState.closeSheet() }
                        )
                    }
                    val todoCreateSheetContent: @Composable () -> Unit = {
                        TodoCreateSheet(
                            meetingTitle = appState.selectedMeeting.title,
                            saving = todoSaving,
                            onSave = { title, assignee, dueAt, priority, description, status ->
                                if (todoSaving) return@TodoCreateSheet
                                todoSaving = true
                                coroutineScope.launch {
                                    runCatching {
                                        appState.createTodoForSelectedMeeting(title, assignee, dueAt, priority, description, status)
                                    }.onSuccess { saved ->
                                        if (saved) {
                                            appState.closeSheet()
                                            coroutineScope.launch {
                                                runCatching { appState.syncPendingLocalResultsToCloud() }
                                                    .onFailure { appState.showToast("已保存在本机，云端稍后重试") }
                                            }
                                        }
                                    }.onFailure { appState.showToast("待办保存失败：${friendlyError(it, "网络连接失败")}") }
                                    todoSaving = false
                                }
                            },
                            onClose = { appState.closeSheet() }
                        )
                    }
                    if (active == SheetType.TodoDetail || active == SheetType.CreateTodo) {
                        StableTodoDetailBottomSheet(onDismiss = { appState.closeSheet() }) {
                            if (active == SheetType.CreateTodo) {
                                todoCreateSheetContent()
                            } else {
                                todoDetailSheetContent()
                            }
                        }
                    } else {
                    val bottomSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                    ModalBottomSheet(
                        modifier = Modifier.imePadding(),
                        onDismissRequest = {
                            if (appState.sheet == SheetType.ScheduleReminder) {
                                appState.snoozeScheduleReminder()
                            } else {
                                appState.closeSheet()
                            }
                        },
                        sheetState = bottomSheetState,
                        containerColor = AppBg,
                        shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
                        tonalElevation = 0.dp,
                        dragHandle = null
                    ) {
                        when (active) {
                        SheetType.ImportFile -> ImportFileSheet(
                            tasks = appState.localTasks,
                            recognitionLanguage = selectedRecognitionLanguage,
                            onRecognitionLanguageChange = { selectedRecognitionLanguage = it },
                            onClose = { appState.closeSheet() },
                            onPickFile = { openFilePicker() },
                            onSubmit = {
                                queueWaitingTasksForProcessing()
                            },
                            onDeleteTask = { task ->
                                appState.requestDeleteTask(task)
                            },
                            onProcessingTask = { task ->
                                openOrQueueProcessingTask(task.id)
                            }
                        )
                        SheetType.Notifications -> NotificationsSheet(onClose = { appState.closeSheet() })
                        SheetType.RecordConsent -> RecordConsentSheet(
                            recognitionLanguage = selectedRecognitionLanguage,
                            onRecognitionLanguageChange = {
                                selectedRecognitionLanguage = it
                                appState.updateRecordingRecognitionLanguage(selectedRecognitionLanguage)
                            },
                            onCancel = { appState.closeSheet() },
                            onStart = {
                                requestOrStartRecording()
                            }
                        )
                        SheetType.CreateMeeting -> CreateMeetingSheet(
                            initialMeeting = appState.editingSchedule,
                            saving = scheduleSaving,
                            onClose = {
                                appState.stopEditingSchedule()
                                appState.closeSheet()
                            },
                            onCreate = { title, time, participants, note ->
                                saveScheduleFromSheet(title, time, participants, note)
                            },
                            onDelete = { scheduleId ->
                                appState.requestDeleteSchedule(scheduleId)
                            }
                        )
                        SheetType.Speakers -> {
                            SpeakerSheet(
                                speakers = appState.selectedMeetingSpeakers,
                                segmentSpeaker = appState.selectedSegmentSpeaker,
                                canSaveVoiceprint = appState.cloudUser != null,
                                saving = speakerSaving,
                                statusText = speakerStatusText,
                                onRename = { speaker, target, saveVoiceprint ->
                                    if (speakerSaving) return@SpeakerSheet
                                    speakerSaving = true
                                    speakerStatusText = if (saveVoiceprint) "正在保存说话人和声纹..." else "正在保存说话人..."
                                    coroutineScope.launch {
                                        runCatching { appState.renameSpeaker(speaker, target) }
                                            .onSuccess {
                                                if (saveVoiceprint) {
                                                    speakerStatusText = "正在保存声纹样本..."
                                                    runCatching { appState.rememberSelectedSpeakerVoiceprint(speaker, target) }
                                                        .onFailure { appState.showToast("说话人已更新，声纹保存失败：${friendlyError(it)}") }
                                                }
                                                appState.closeSheet()
                                                coroutineScope.launch {
                                                    runCatching { appState.syncPendingLocalResultsToCloud() }
                                                        .onFailure { appState.showToast("已保存在本机，云端稍后重试") }
                                                }
                                            }
                                            .onFailure { appState.showToast("说话人保存失败：${friendlyError(it)}") }
                                        speakerSaving = false
                                        speakerStatusText = null
                                    }
                                },
                                onAssignSegment = { target, selectedSpeakerId ->
                                    if (speakerSaving) return@SpeakerSheet
                                    speakerSaving = true
                                    speakerStatusText = "正在保存说话人..."
                                    coroutineScope.launch {
                                        runCatching { appState.updateSelectedSegmentSpeaker(target, selectedSpeakerId) }
                                            .onSuccess {
                                                appState.closeSheet()
                                                coroutineScope.launch {
                                                    runCatching { appState.syncPendingLocalResultsToCloud() }
                                                        .onFailure { appState.showToast("已保存在本机，云端稍后重试") }
                                                }
                                            }
                                            .onFailure { appState.showToast("说话人保存失败：${friendlyError(it)}") }
                                        speakerSaving = false
                                        speakerStatusText = null
                                    }
                                },
                                onClose = { appState.closeSheet() }
                            )
                        }
                        SheetType.Source -> SourceSheet(
                            meeting = appState.selectedMeeting,
                            segmentIndex = appState.selectedSourceSegmentIndex,
                            playbackState = audioSegmentPlayer.state,
                            onPlaySegment = {
                                val meeting = appState.selectedMeeting
                                val segment = meeting.transcripts.getOrNull(
                                    appState.selectedSourceSegmentIndex.coerceIn(0, (meeting.transcripts.size - 1).coerceAtLeast(0))
                                )
                                val path = meeting.sourceFilePath
                                if (segment == null || path == null) {
                                    appState.showToast("没有可播放的来源音频")
                                } else {
                                    audioSegmentPlayer.play(
                                        filePath = path,
                                        startMs = segment.startMs,
                                        endMs = segment.endMs,
                                        onComplete = { appState.showToast("播放完成") },
                                        onError = { error -> appState.showToast("播放失败：${friendlyError(error, "音频暂时无法播放，请重新同步后再试")}") }
                                    )
                                }
                            },
                            onCorrection = { appState.showSheet(SheetType.Correction) },
                            onClose = {
                                audioSegmentPlayer.stop()
                                appState.closeSheet()
                            }
                        )
                        SheetType.TodoDetail -> todoDetailSheetContent()
                        SheetType.CreateTodo -> todoCreateSheetContent()
                        SheetType.Export -> ExportSheet(
                            busy = exportBusy,
                            statusText = exportStatusText,
                            onClose = { appState.closeSheet() },
                            onExportText = { includeTranscript ->
                                runExportOperation("正在准备 TXT 文件...") {
                                    val title = appState.selectedMeeting.title
                                    val text = appState.exportSelectedMeetingText("txt", includeTranscript)
                                    val fileName = "${title.safeExportFileName()}.txt"
                                    pendingTextExport = PendingTextExport(fileName, text)
                                    appState.closeSheet()
                                    textExportLauncher.launch(fileName)
                                }
                            }
                        )
                        SheetType.Correction -> {
                            val meeting = appState.selectedMeeting
                            val segment = meeting.transcripts.getOrNull(
                                appState.selectedSourceSegmentIndex.coerceIn(0, (meeting.transcripts.size - 1).coerceAtLeast(0))
                            )
                            CorrectionSheet(
                                originalText = segment?.text.orEmpty(),
                                timeRange = segment?.timeRangeLabel ?: "无时间点",
                                saving = correctionSaving,
                                onSave = { text ->
                                    if (correctionSaving) return@CorrectionSheet
                                    correctionSaving = true
                                    coroutineScope.launch {
                                        runCatching { appState.correctSelectedTranscript(text) }
                                            .onSuccess { appState.closeSheet() }
                                            .onFailure { appState.showToast("修正失败：${friendlyError(it)}") }
                                        correctionSaving = false
                                    }
                                },
                                onClose = { appState.closeSheet() }
                            )
                        }
                        SheetType.EditMinutes -> EditMinutesSheet(
                            initialSummary = appState.selectedMeeting.summary,
                            saving = minutesSaving,
                            onSave = { summary ->
                                if (minutesSaving) return@EditMinutesSheet
                                minutesSaving = true
                                coroutineScope.launch {
                                    runCatching { appState.updateSelectedSummary(summary) }
                                        .onSuccess {
                                            appState.closeSheet()
                                            coroutineScope.launch {
                                                runCatching { appState.syncPendingLocalResultsToCloud() }
                                                    .onFailure { appState.showToast("已保存在本机，云端稍后重试") }
                                            }
                                        }
                                        .onFailure { appState.showToast("保存失败：${friendlyError(it)}") }
                                    minutesSaving = false
                                }
                            },
                            onClose = { appState.closeSheet() }
                        )
                        SheetType.EditMeetingInfo -> EditMeetingInfoSheet(
                            initialTitle = appState.selectedMeeting.title,
                            saving = meetingInfoSaving,
                            onSave = { title ->
                                if (meetingInfoSaving) return@EditMeetingInfoSheet
                                meetingInfoSaving = true
                                coroutineScope.launch {
                                    runCatching { appState.updateSelectedMeetingTitle(title) }
                                        .onSuccess {
                                            appState.closeSheet()
                                            coroutineScope.launch {
                                                runCatching { appState.syncPendingLocalResultsToCloud() }
                                                    .onFailure { appState.showToast("已保存在本机，云端稍后重试") }
                                            }
                                        }
                                        .onFailure { appState.showToast("保存失败：${friendlyError(it)}") }
                                    meetingInfoSaving = false
                                }
                            },
                            onClose = { appState.closeSheet() }
                        )
                        SheetType.DeleteData -> DeleteDataSheet(
                            saving = deleteDataSaving,
                            onClose = { appState.closeSheet() },
                            onConfirm = {
                                if (deleteDataSaving) return@DeleteDataSheet
                                deleteDataSaving = true
                                coroutineScope.launch {
                                    var cloudCleared = false
                                    runCatching {
                                        cloudCleared = appState.clearCloudDataIfLoggedIn()
                                        appState.clearLocalTasks()
                                        resultStore.clearAll()
                                        dataCleaner.clearLocalFiles()
                                    }.onSuccess {
                                        appState.closeSheet()
                                        appState.showToast(if (cloudCleared) "本地与云端会议数据已删除" else "本地会议数据已删除")
                                    }.onFailure {
                                        appState.closeSheet()
                                        appState.showToast("删除失败：${friendlyError(it)}")
                                    }
                                    deleteDataSaving = false
                                }
                            }
                        )
                        SheetType.DeleteMeeting -> DeleteMeetingSheet(
                            meetingTitle = appState.pendingDeleteMeeting?.title ?: "会议",
                            saving = deleteMeetingSaving,
                            onClose = { appState.closeSheet() },
                            onConfirm = {
                                if (deleteMeetingSaving) return@DeleteMeetingSheet
                                deleteMeetingSaving = true
                                coroutineScope.launch {
                                    runCatching { appState.deletePendingMeeting() }
                                        .onSuccess { deleted ->
                                            appState.closeSheet()
                                            if (deleted) appState.showToast("会议已删除")
                                            if (deleted) {
                                                coroutineScope.launch {
                                                    runCatching { appState.syncPendingLocalResultsToCloud() }
                                                        .onFailure { appState.showToast("已保存在本机，云端稍后重试") }
                                                }
                                            }
                                        }
                                        .onFailure {
                                            appState.closeSheet()
                                            appState.showToast("删除失败：${friendlyError(it)}")
                                        }
                                    deleteMeetingSaving = false
                                }
                            }
                        )
                        SheetType.DeleteSchedule -> DeleteScheduleSheet(
                            meetingTitle = appState.pendingDeleteSchedule?.title ?: "预约会议",
                            saving = deleteScheduleSaving,
                            onClose = { appState.closeSheet() },
                            onConfirm = {
                                if (deleteScheduleSaving) return@DeleteScheduleSheet
                                deleteScheduleSaving = true
                                coroutineScope.launch {
                                    val deleted = runCatching { appState.deletePendingSchedule() }
                                        .onFailure { appState.showToast("预约删除失败：${friendlyError(it)}") }
                                        .getOrNull()
                                    if (deleted != null) {
                                        appState.showToast("预约已删除")
                                        appState.stopEditingSchedule()
                                        appState.closeSheet()
                                    }
                                    deleteScheduleSaving = false
                                }
                            }
                        )
                        SheetType.DeleteTask -> DeleteTaskSheet(
                            taskTitle = appState.pendingDeleteTask?.title ?: "待处理任务",
                            saving = deleteTaskSaving,
                            onClose = { appState.closeSheet() },
                            onConfirm = {
                                if (deleteTaskSaving) return@DeleteTaskSheet
                                deleteTaskSaving = true
                                coroutineScope.launch {
                                    runCatching { appState.deletePendingTask() }
                                        .onSuccess { deleted ->
                                            appState.closeSheet()
                                            if (deleted) appState.showToast("任务已删除")
                                        }
                                        .onFailure {
                                            appState.closeSheet()
                                            appState.showToast("删除失败：${friendlyError(it)}")
                                    }
                                    deleteTaskSaving = false
                                }
                            }
                        )
                        SheetType.ScheduleReminder -> ScheduleReminderSheet(
                            meeting = appState.reminderSchedule,
                            onStart = {
                                val meeting = appState.consumeScheduleReminderForRecording()
                                appState.closeSheet()
                                appState.prepareRecordingForSchedule(meeting, selectedRecognitionLanguage)
                                appState.showSheet(SheetType.RecordConsent)
                            },
                            onLater = { appState.snoozeScheduleReminder() },
                            onDismiss = { appState.dismissScheduleReminder() }
                        )
                        SheetType.LogoutConfirm -> LogoutConfirmSheet(
                            localUnsyncedTaskCount = appState.logoutLocalUnsyncedTaskCount,
                            pendingDeleteCount = appState.pendingCloudDeleteCount,
                            saving = logoutSaving,
                            onClose = { appState.closeSheet() },
                            onConfirm = {
                                if (logoutSaving) return@LogoutConfirmSheet
                                logoutSaving = true
                                coroutineScope.launch {
                                    try {
                                        appState.closeSheet()
                                        appState.cleanupTemporaryRemoteProcessingTasks()
                                        appState.logoutCloud()
                                        setLoginAgreementAccepted(false)
                                    } finally {
                                        logoutSaving = false
                                    }
                                }
                            }
                        )
                        SheetType.UserAgreement -> UserAgreementSheet(onClose = { appState.closeSheet() })
                        SheetType.PrivacyPolicy -> PrivacyPolicySheet(onClose = { appState.closeSheet() })
                    }
                    }
                    }
                }
            }
            if (toast.isNotBlank()) {
                val toastModifier = if (sheet != null) {
                    Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 16.dp)
                } else {
                    val bottomPadding = if (screen.root) 104.dp else 44.dp
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 16.dp)
                        .padding(bottom = bottomPadding)
                }
                Surface(
                    color = Color(0xE6172026),
                    shape = RoundedCornerShape(18.dp),
                    modifier = toastModifier.zIndex(20f)
                ) {
                    Text(
                        text = toast,
                        color = Color.White,
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StableTodoDetailBottomSheet(
    onDismiss: () -> Unit,
    content: @Composable () -> Unit
) {
    BackHandler(onBack = onDismiss)

    var dragOffsetY by remember { mutableStateOf(0f) }
    val dismissDistance = with(LocalDensity.current) { 96.dp.toPx() }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.32f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                )
        )

        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .graphicsLayer { translationY = dragOffsetY }
                .pointerInput(onDismiss, dismissDistance) {
                    detectVerticalDragGestures(
                        onVerticalDrag = { change, dragAmount ->
                            val nextOffset = (dragOffsetY + dragAmount).coerceAtLeast(0f)
                            if (dragAmount > 0f || nextOffset != dragOffsetY) {
                                change.consume()
                            }
                            dragOffsetY = nextOffset
                        },
                        onDragEnd = {
                            if (dragOffsetY > dismissDistance) {
                                onDismiss()
                            } else {
                                dragOffsetY = 0f
                            }
                        },
                        onDragCancel = { dragOffsetY = 0f }
                    )
                },
            shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
            color = AppBg,
            tonalElevation = 0.dp
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding()
            ) {
                content()
            }
        }
    }
}

private fun copyText(context: Context, title: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(title, text))
}

private fun String.safeExportFileName(): String {
    val clean = trim()
        .replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_")
        .trim('_', '.', '-')
        .take(48)
    return clean.ifBlank { "会议纪要" }
}

private data class PendingTextExport(
    val fileName: String,
    val text: String
)

private fun readPermissionStatus(context: Context): AppPermissionStatus {
    val microphone = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()
    } else {
        NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
    return AppPermissionStatus(
        microphoneEnabled = microphone,
        notificationEnabled = notification,
        fileAccessEnabled = true
    )
}

private fun Long.toReadableSizeLabel(): String {
    return when {
        this >= 1024L * 1024L -> "%.1f MB".format(this / 1024f / 1024f)
        this >= 1024L -> "%.1f KB".format(this / 1024f)
        else -> "$this B"
    }
}
