package com.huiyi.app.recording

enum class RecordingStatus {
    Idle,
    Preparing,
    Recording,
    Paused,
    Finished
}

data class RecordingUiState(
    val status: RecordingStatus = RecordingStatus.Idle,
    val elapsedSeconds: Int = 0,
    val localFilePath: String? = null,
    val audioLevel: RecordingAudioLevel? = null,
    val audioWarning: String? = null,
    val transcriptionStatus: String? = null
) {
    val title: String
        get() = when (status) {
            RecordingStatus.Idle -> "准备记录"
            RecordingStatus.Preparing -> "正在准备"
            RecordingStatus.Recording -> "实时记录中"
            RecordingStatus.Paused -> "已暂停"
            RecordingStatus.Finished -> "录音已结束"
        }

    val statusLine: String
        get() = "${formatDuration(elapsedSeconds)} · 普通话 / 中英混合"
}

data class RecordingAudioLevel(
    val source: String,
    val peak: Int,
    val rms: Double,
    val nonZeroRatio: Double
) {
    val levelPercent: Int
        get() = ((rms / 1800.0) * 100.0).toInt().coerceIn(0, 100)

    val isDigitalSilence: Boolean
        get() = peak == 0 && nonZeroRatio == 0.0
}

fun formatDuration(totalSeconds: Int): String {
    val hours = totalSeconds / 3600
    val minutes = totalSeconds % 3600 / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%02d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}
