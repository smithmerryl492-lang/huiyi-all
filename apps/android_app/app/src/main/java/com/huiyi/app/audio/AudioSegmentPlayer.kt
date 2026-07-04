package com.huiyi.app.audio

import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

data class AudioSegmentPlaybackState(
    val activeKey: String? = null,
    val filePath: String? = null,
    val isPlaying: Boolean = false,
    val currentMs: Long = 0L,
    val startMs: Long = 0L,
    val endMs: Long? = null,
    val durationMs: Long = 0L
) {
    val segmentDurationMs: Long
        get() = ((endMs ?: durationMs) - startMs).coerceAtLeast(0L)

    val segmentCurrentMs: Long
        get() = (currentMs - startMs).coerceIn(0L, segmentDurationMs.coerceAtLeast(1L))

    val progress: Float
        get() = if (segmentDurationMs <= 0L) 0f else (segmentCurrentMs.toFloat() / segmentDurationMs).coerceIn(0f, 1f)
}

class AudioSegmentPlayer {
    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var stopRunnable: Runnable? = null
    private var progressRunnable: Runnable? = null
    private var onCompleteCallback: (() -> Unit)? = null
    private var onErrorCallback: ((Throwable) -> Unit)? = null
    private var pendingSeekKey: String? = null
    var state by mutableStateOf(AudioSegmentPlaybackState())
        private set

    fun play(
        filePath: String,
        startMs: Long?,
        endMs: Long?,
        onComplete: () -> Unit = {},
        onError: (Throwable) -> Unit = {}
    ) {
        val key = segmentKey(filePath, startMs, endMs)
        if (state.activeKey == key && player != null) {
            if (pendingSeekKey == key) return
            if (state.isPlaying) {
                pause()
            } else {
                resume(onComplete)
            }
            return
        }

        stop()
        runCatching {
            val file = File(filePath)
            require(file.exists()) { "音频文件不存在：$filePath" }
            val mediaPlayer = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener {
                    finishPlayback()
                    onComplete()
                }
                prepare()
            }
            val durationMs = mediaPlayer.duration.toLong().coerceAtLeast(0L)
            val safeStart = (startMs?.coerceAtLeast(0L) ?: 0L).coerceAtMost(durationMs)
            val safeEnd = endMs?.coerceAtLeast(safeStart)
            player = mediaPlayer
            onCompleteCallback = onComplete
            onErrorCallback = onError
            state = AudioSegmentPlaybackState(
                activeKey = key,
                filePath = file.absolutePath,
                isPlaying = false,
                currentMs = safeStart,
                startMs = safeStart,
                endMs = safeEnd,
                durationMs = durationMs
            )
            if (safeStart > 0L) {
                pendingSeekKey = key
                mediaPlayer.setOnSeekCompleteListener { completedPlayer ->
                    if (player !== completedPlayer || state.activeKey != key) return@setOnSeekCompleteListener
                    pendingSeekKey = null
                    startPreparedPlayer(completedPlayer)
                }
                mediaPlayer.seekTo(safeStart, MediaPlayer.SEEK_CLOSEST)
            } else {
                startPreparedPlayer(mediaPlayer)
            }
        }.onFailure { error ->
            stop()
            onError(error)
        }
    }

    fun pause() {
        stopRunnable?.let { handler.removeCallbacks(it) }
        stopRunnable = null
        progressRunnable?.let { handler.removeCallbacks(it) }
        progressRunnable = null
        player?.let { mediaPlayer ->
            runCatching {
                if (mediaPlayer.isPlaying) mediaPlayer.pause()
                state = state.copy(isPlaying = false, currentMs = mediaPlayer.currentPosition.toLong())
            }
        }
    }

    private fun resume(onComplete: () -> Unit) {
        player?.let { mediaPlayer ->
            runCatching {
                if (pendingSeekKey == state.activeKey) return
                onCompleteCallback = onComplete
                mediaPlayer.start()
                state = state.copy(isPlaying = true, currentMs = mediaPlayer.currentPosition.toLong())
                scheduleStop()
                startProgressUpdates()
            }.onFailure {
                stop()
            }
        }
    }

    private fun startPreparedPlayer(mediaPlayer: MediaPlayer) {
        runCatching {
            mediaPlayer.start()
            state = state.copy(isPlaying = true, currentMs = mediaPlayer.currentPosition.toLong())
            scheduleStop()
            startProgressUpdates()
        }.onFailure { error ->
            val callback = onErrorCallback
            stop()
            callback?.invoke(error)
        }
    }

    fun stop() {
        stopRunnable?.let { handler.removeCallbacks(it) }
        stopRunnable = null
        progressRunnable?.let { handler.removeCallbacks(it) }
        progressRunnable = null
        pendingSeekKey = null
        player?.let { mediaPlayer ->
            runCatching {
                if (mediaPlayer.isPlaying) mediaPlayer.stop()
            }
            mediaPlayer.release()
        }
        player = null
        onCompleteCallback = null
        onErrorCallback = null
        state = AudioSegmentPlaybackState()
    }

    private fun scheduleStop() {
        stopRunnable?.let { handler.removeCallbacks(it) }
        stopRunnable = null
        val mediaPlayer = player ?: return
        val endMs = state.endMs ?: return
        val remaining = endMs - mediaPlayer.currentPosition.toLong()
        if (remaining <= 0L) {
            finishPlayback()
            onCompleteCallback?.invoke()
            return
        }
        val runnable = Runnable {
            finishPlayback()
            onCompleteCallback?.invoke()
        }
        stopRunnable = runnable
        handler.postDelayed(runnable, remaining)
    }

    private fun startProgressUpdates() {
        progressRunnable?.let { handler.removeCallbacks(it) }
        val runnable = object : Runnable {
            override fun run() {
                val mediaPlayer = player ?: return
                val current = mediaPlayer.currentPosition.toLong()
                state = state.copy(
                    isPlaying = mediaPlayer.isPlaying,
                    currentMs = current,
                    durationMs = mediaPlayer.duration.toLong().coerceAtLeast(0L)
                )
                if (mediaPlayer.isPlaying) {
                    handler.postDelayed(this, 200L)
                }
            }
        }
        progressRunnable = runnable
        handler.post(runnable)
    }

    private fun finishPlayback() {
        stopRunnable?.let { handler.removeCallbacks(it) }
        stopRunnable = null
        progressRunnable?.let { handler.removeCallbacks(it) }
        progressRunnable = null
        val finalMs = state.endMs ?: state.durationMs
        player?.let { mediaPlayer ->
            runCatching {
                if (mediaPlayer.isPlaying) mediaPlayer.stop()
            }
            mediaPlayer.release()
        }
        player = null
        state = state.copy(isPlaying = false, currentMs = finalMs)
    }

    companion object {
        fun segmentKey(filePath: String, startMs: Long?, endMs: Long?): String {
            return "${File(filePath).absolutePath}:${startMs ?: 0L}:${endMs ?: -1L}"
        }
    }
}
