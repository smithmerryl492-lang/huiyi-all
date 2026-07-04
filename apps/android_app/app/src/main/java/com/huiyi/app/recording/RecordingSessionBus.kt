package com.huiyi.app.recording

import com.huiyi.app.data.TranscriptSegment
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

data class RecordingServiceState(
    val status: RecordingStatus = RecordingStatus.Idle,
    val startedAtMillis: Long = 0L,
    val elapsedSeconds: Int = 0,
    val localFilePath: String? = null,
    val errorMessage: String? = null,
    val audioLevel: RecordingAudioLevel? = null,
    val audioWarning: String? = null,
    val transcriptionStatus: String? = null
)

sealed class RecordingServiceEvent {
    data class Segments(
        val segments: List<TranscriptSegment>,
        val isFinal: Boolean,
        val replaceAll: Boolean
    ) : RecordingServiceEvent()

    data class Error(val message: String) : RecordingServiceEvent()
    data class Stopped(val localFilePath: String?) : RecordingServiceEvent()
    data class Finished(val localFilePath: String?) : RecordingServiceEvent()
}

object RecordingSessionBus {
    private val _state = MutableStateFlow(RecordingServiceState())
    val state = _state.asStateFlow()

    private val _events = MutableSharedFlow<RecordingServiceEvent>(extraBufferCapacity = 32)
    val events = _events.asSharedFlow()

    fun update(state: RecordingServiceState) {
        _state.value = state
    }

    fun emit(event: RecordingServiceEvent) {
        _events.tryEmit(event)
    }
}
