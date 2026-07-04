package com.huiyi.app.calendar

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class ParsedScheduleTime(
    val startAtMillis: Long,
    val endAtMillis: Long,
    val displayTime: String,
    val durationLabel: String
)

fun parseScheduleTime(input: String, now: LocalDateTime = LocalDateTime.now()): ParsedScheduleTime? {
    val clean = input.trim()
    if (clean.isBlank()) return null

    val date = when {
        clean.startsWith("今天") -> now.toLocalDate()
        clean.startsWith("明天") -> now.toLocalDate().plusDays(1)
        else -> parseLeadingDate(clean) ?: now.toLocalDate()
    }
    val times = Regex("""(\d{1,2}):(\d{2})""").findAll(clean).toList()
    if (times.isEmpty()) return null

    val start = times[0].toLocalTime()
    val startDateTime = LocalDateTime.of(date, start)
    val zone = ZoneId.systemDefault()
    return ParsedScheduleTime(
        startAtMillis = startDateTime.atZone(zone).toInstant().toEpochMilli(),
        endAtMillis = startDateTime.atZone(zone).toInstant().toEpochMilli(),
        displayTime = clean,
        durationLabel = ""
    )
}

fun formatScheduleDurationLabel(durationMinutes: Long): String {
    val minutes = durationMinutes.coerceAtLeast(1)
    if (minutes < 60) return "$minutes 分钟"

    val hours = minutes / 60
    val restMinutes = minutes % 60
    return if (restMinutes == 0L) {
        "$hours 小时"
    } else {
        "$hours 小时 $restMinutes 分钟"
    }
}

fun hasScheduleConflict(
    meetings: List<com.huiyi.app.data.ScheduledMeeting>,
    startAtMillis: Long,
    ignoreId: String? = null
): Boolean {
    return meetings.any { meeting ->
        val meetingStart = meeting.startAtMillis ?: parseScheduleTime(meeting.time)?.startAtMillis
        if (
            meeting.id == ignoreId ||
            meetingStart == null ||
            meeting.isFinished() ||
            meeting.isOverdue()
        ) {
            false
        } else {
            startAtMillis == meetingStart
        }
    }
}

fun com.huiyi.app.data.ScheduledMeeting.isStartOnlySchedule(): Boolean {
    return startAtMillis != null
}

private fun parseLeadingDate(value: String): LocalDate? {
    val match = Regex("""(\d{4})-(\d{1,2})-(\d{1,2})""").find(value) ?: return null
    return runCatching {
        LocalDate.parse(match.value, DateTimeFormatter.ofPattern("yyyy-M-d"))
    }.getOrNull()
}

private fun MatchResult.toLocalTime(): LocalTime {
    return LocalTime.of(groupValues[1].toInt().coerceIn(0, 23), groupValues[2].toInt().coerceIn(0, 59))
}
