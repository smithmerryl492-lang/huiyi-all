package com.huiyi.app.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val GeneratedDefaultTitlePattern = Regex(
    pattern = """^(HXAI_\d{8}_\d{6}|(meeting|import|recording)[_-]\d{10,})(\.[A-Za-z0-9]{1,8})?$""",
    option = RegexOption.IGNORE_CASE
)

fun defaultMeetingDisplayTitle(timestampMillis: Long = System.currentTimeMillis()): String {
    val formatted = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(timestampMillis))
    return "$formatted 会议"
}

fun meetingDisplayTitleOrDefault(
    candidate: String?,
    timestampMillis: Long = System.currentTimeMillis()
): String {
    val clean = candidate?.trim().orEmpty()
    return if (clean.needsGeneratedMeetingDisplayTitle()) {
        defaultMeetingDisplayTitle(timestampMillis)
    } else {
        clean
    }
}

private fun String.needsGeneratedMeetingDisplayTitle(): Boolean {
    if (isBlank()) return true
    if (this == "未完成录音") return true
    return GeneratedDefaultTitlePattern.matches(this)
}
