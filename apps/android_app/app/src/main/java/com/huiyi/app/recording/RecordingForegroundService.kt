package com.huiyi.app.recording

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.huiyi.app.MainActivity
import com.huiyi.app.data.HuixiaoApiClient
import com.huiyi.app.data.LiveTranscriptionClient
import com.huiyi.app.data.RecognitionLanguage
import com.huiyi.app.data.SharedPrefsCloudUserStore
import com.huiyi.app.data.userFacingDisplayText
import com.huiyi.app.data.userFacingMessage

class RecordingForegroundService : Service() {
    private val recorder by lazy { AndroidAudioRecorder(applicationContext) }
    private var liveClient: LiveTranscriptionClient? = null
    private var startedAtMillis: Long = 0L
    private var elapsedSeconds: Int = 0
    private var localFilePath: String? = null
    @Volatile private var actualRecordingStarted = false
    @Volatile private var finishingRecording = false
    private val ticker = Handler(Looper.getMainLooper())
    private val tickRunnable = object : Runnable {
        override fun run() {
            val current = RecordingSessionBus.state.value
            if (current.status == RecordingStatus.Recording) {
                elapsedSeconds += 1
                val state = current.copy(elapsedSeconds = elapsedSeconds)
                RecordingSessionBus.update(state)
                updateNotification(state)
                ticker.postDelayed(this, 1000L)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording(intent)
            ACTION_PAUSE -> pauseRecording()
            ACTION_RESUME -> resumeRecording()
            ACTION_STOP -> finishRecording()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        ticker.removeCallbacks(tickRunnable)
        liveClient?.release()
        recorder.release()
        super.onDestroy()
    }

    private fun startRecording(intent: Intent?) {
        val currentStatus = RecordingSessionBus.state.value.status
        if (currentStatus == RecordingStatus.Preparing || currentStatus == RecordingStatus.Recording) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            fail("未获得麦克风权限，无法开始录音")
            stopSelf()
            return
        }
        runCatching {
            val user = SharedPrefsCloudUserStore(applicationContext).loadUser()
            if (user == null || user.accessToken.isBlank()) {
                error("请先登录后再开始实时记录")
            }
            startedAtMillis = 0L
            elapsedSeconds = 0
            actualRecordingStarted = false
            finishingRecording = false
            localFilePath = null
            ticker.removeCallbacks(tickRunnable)
            val initialState = RecordingServiceState(
                status = RecordingStatus.Preparing,
                startedAtMillis = 0L,
                elapsedSeconds = 0,
                transcriptionStatus = "正在连接实时转写"
            )
            RecordingSessionBus.update(initialState)
            startForeground(NOTIFICATION_ID, buildNotification(initialState))
            val sessionId = "live-${System.currentTimeMillis()}"
            val client = LiveTranscriptionClient(
                accessToken = user.accessToken,
                apiClient = HuixiaoApiClient().also { it.setAccessToken(user.accessToken) },
                user = user,
                onSegments = { segments, isFinal, replaceAll ->
                    RecordingSessionBus.emit(RecordingServiceEvent.Segments(segments, isFinal, replaceAll))
                },
                onError = { message ->
                    RecordingSessionBus.emit(RecordingServiceEvent.Error(message))
                },
                onStatus = { message ->
                    updateTranscriptionStatus(message)
                },
                onReady = {
                    ticker.post { beginActualRecording() }
                },
                onStartFailed = { message ->
                    ticker.post {
                        fail(message)
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                },
                onStopRequested = { message ->
                    RecordingSessionBus.emit(RecordingServiceEvent.Error(message))
                    finishRecording()
                },
                recognitionLanguage = RecognitionLanguage.fromRemote(intent?.getStringExtra(EXTRA_RECOGNITION_LANGUAGE))
            )
            client.start(sessionId)
            liveClient = client
        }.onFailure { error ->
            fail("录音启动失败：${error.userFacingMessage("请检查麦克风权限")}")
            stopSelf()
        }
    }

    private fun beginActualRecording() {
        val current = RecordingSessionBus.state.value
        if (
            actualRecordingStarted ||
            finishingRecording ||
            current.status != RecordingStatus.Preparing ||
            liveClient == null
        ) {
            return
        }
        val client = liveClient ?: return
        runCatching {
            startedAtMillis = System.currentTimeMillis()
            elapsedSeconds = 0
            val file = recorder.start(
                onPcmFrame = { frame, endBytes -> client.sendPcm(frame, endBytes) },
                onAudioLevel = { level -> updateAudioLevel(level) },
                onAudioIssue = { message -> reportAudioIssue(message) }
            )
            actualRecordingStarted = true
            localFilePath = file.absolutePath
            client.attachAudioFile(file)
            val state = RecordingServiceState(
                status = RecordingStatus.Recording,
                startedAtMillis = startedAtMillis,
                elapsedSeconds = elapsedSeconds,
                localFilePath = file.absolutePath,
                transcriptionStatus = null
            )
            RecordingSessionBus.update(state)
            updateNotification(state)
            ticker.removeCallbacks(tickRunnable)
            ticker.postDelayed(tickRunnable, 1000L)
        }.onFailure { error ->
            fail("录音启动失败：${error.userFacingMessage("请检查麦克风权限")}")
            stopSelf()
        }
    }

    private fun pauseRecording() {
        if (!actualRecordingStarted) return
        recorder.pause()
        ticker.removeCallbacks(tickRunnable)
        elapsedSeconds = RecordingSessionBus.state.value.elapsedSeconds
        val state = RecordingSessionBus.state.value.copy(
            status = RecordingStatus.Paused,
            elapsedSeconds = elapsedSeconds
        )
        RecordingSessionBus.update(state)
        updateNotification(state)
    }

    private fun resumeRecording() {
        if (!actualRecordingStarted) return
        recorder.resume()
        elapsedSeconds = RecordingSessionBus.state.value.elapsedSeconds
        val state = RecordingSessionBus.state.value.copy(
            status = RecordingStatus.Recording,
            elapsedSeconds = elapsedSeconds
        )
        RecordingSessionBus.update(state)
        updateNotification(state)
        ticker.removeCallbacks(tickRunnable)
        ticker.postDelayed(tickRunnable, 1000L)
    }

    private fun finishRecording() {
        if (finishingRecording) return
        finishingRecording = true
        ticker.removeCallbacks(tickRunnable)
        elapsedSeconds = RecordingSessionBus.state.value.elapsedSeconds
        val file = if (actualRecordingStarted) recorder.stop() else null
        actualRecordingStarted = false
        val path = file?.absolutePath ?: localFilePath
        val stoppedState = RecordingSessionBus.state.value.copy(
            status = RecordingStatus.Finished,
            elapsedSeconds = elapsedSeconds,
            localFilePath = path,
            audioWarning = null,
            transcriptionStatus = null
        )
        RecordingSessionBus.update(stoppedState)
        RecordingSessionBus.emit(RecordingServiceEvent.Stopped(path))
        updateNotification(stoppedState)
        val client = liveClient
        val complete: () -> Unit = {
            ticker.post {
                if (!finishingRecording) return@post
                liveClient = null
                finishingRecording = false
                val state = RecordingSessionBus.state.value.copy(
                    status = RecordingStatus.Finished,
                    elapsedSeconds = elapsedSeconds,
                    localFilePath = path
                )
                RecordingSessionBus.update(state)
                RecordingSessionBus.emit(RecordingServiceEvent.Finished(path))
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        if (client != null) {
            client.finishAfterRecording(file, complete)
        } else {
            complete()
        }
    }

    private fun fail(message: String) {
        ticker.removeCallbacks(tickRunnable)
        elapsedSeconds = 0
        actualRecordingStarted = false
        finishingRecording = false
        val displayMessage = message.userFacingDisplayText("录音启动失败，请检查麦克风权限")
        RecordingSessionBus.update(RecordingServiceState(status = RecordingStatus.Idle, errorMessage = displayMessage))
        RecordingSessionBus.emit(RecordingServiceEvent.Error(displayMessage))
    }

    private fun updateAudioLevel(level: RecordingAudioLevel) {
        val current = RecordingSessionBus.state.value
        if (current.status != RecordingStatus.Preparing && current.status != RecordingStatus.Recording && current.status != RecordingStatus.Paused) return
        RecordingSessionBus.update(
            current.copy(
                audioLevel = level,
                audioWarning = if (level.isDigitalSilence) current.audioWarning else null,
                errorMessage = if (level.isDigitalSilence) current.errorMessage else null
            )
        )
    }

    private fun reportAudioIssue(message: String) {
        Log.w(TAG, message)
        val current = RecordingSessionBus.state.value
        RecordingSessionBus.update(current.copy(audioWarning = message, errorMessage = message))
        RecordingSessionBus.emit(RecordingServiceEvent.Error(message))
    }

    private fun updateTranscriptionStatus(message: String?) {
        if (finishingRecording) return
        val current = RecordingSessionBus.state.value
        if (current.status != RecordingStatus.Preparing && current.status != RecordingStatus.Recording && current.status != RecordingStatus.Paused) return
        Log.i(TAG, "live transcription status=${message ?: "connected"}")
        val state = current.copy(transcriptionStatus = message)
        RecordingSessionBus.update(state)
        updateNotification(state)
    }

    private fun updateNotification(state: RecordingServiceState) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(state))
    }

    private fun buildNotification(state: RecordingServiceState): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, RecordingForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_audio_online)
            .setContentTitle(if (state.status == RecordingStatus.Preparing) "鲲穹会纪正在准备" else "鲲穹会纪正在录音")
            .setContentText(
                when {
                    state.status == RecordingStatus.Preparing -> "正在连接实时转写"
                    state.status == RecordingStatus.Paused -> "录音已暂停"
                    !state.transcriptionStatus.isNullOrBlank() -> state.transcriptionStatus
                    else -> "后台录音与实时转写运行中"
                }
            )
            .setContentIntent(openIntent)
            .setOngoing(state.status != RecordingStatus.Finished)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (state.status != RecordingStatus.Preparing) {
            val pauseOrResumeAction = if (state.status == RecordingStatus.Paused) ACTION_RESUME else ACTION_PAUSE
            val pauseOrResumeIntent = PendingIntent.getService(
                this,
                3,
                Intent(this, RecordingForegroundService::class.java).setAction(pauseOrResumeAction),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val pauseOrResumeText = if (state.status == RecordingStatus.Paused) "继续" else "暂停"
            builder.addAction(0, pauseOrResumeText, pauseOrResumeIntent)
        }
        return builder
            .addAction(0, "结束", stopIntent)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(CHANNEL_ID, "录音状态", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_START = "com.huiyi.app.recording.START"
        const val ACTION_PAUSE = "com.huiyi.app.recording.PAUSE"
        const val ACTION_RESUME = "com.huiyi.app.recording.RESUME"
        const val ACTION_STOP = "com.huiyi.app.recording.STOP"
        private const val EXTRA_RECOGNITION_LANGUAGE = "recognition_language"
        private const val CHANNEL_ID = "huixiao_recording"
        private const val NOTIFICATION_ID = 9101
        private const val TAG = "HuixiaoRecorderService"

        fun start(context: Context, recognitionLanguage: RecognitionLanguage = RecognitionLanguage.Chinese) {
            val intent = Intent(context, RecordingForegroundService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RECOGNITION_LANGUAGE, recognitionLanguage.remoteValue)
            ContextCompat.startForegroundService(context, intent)
        }

        fun pause(context: Context) {
            context.startService(Intent(context, RecordingForegroundService::class.java).setAction(ACTION_PAUSE))
        }

        fun resume(context: Context) {
            context.startService(Intent(context, RecordingForegroundService::class.java).setAction(ACTION_RESUME))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, RecordingForegroundService::class.java).setAction(ACTION_STOP))
        }
    }
}
