package com.huiyi.app.data

import com.huiyi.app.calendar.isStartOnlySchedule

enum class MeetingStatus(val label: String) {
    Generated("已确认"),
    PendingConfirm("待确认"),
    Scheduled("待开始")
}

enum class MeetingTaskSource(val label: String) {
    Recording("录音"),
    Import("导入")
}

enum class MeetingTaskStatus(val label: String) {
    LocalSaved("已保存本地"),
    WaitingProcess("待处理"),
    Processing("处理中"),
    Finished("已完成"),
    Failed("处理失败"),
    Canceled("已终止")
}

enum class CloudSyncStatus(val label: String) {
    LocalOnly("仅本机"),
    PendingUpload("待上传"),
    Synced("已同步"),
    SyncFailed("同步失败"),
    LocalProcessing("本机处理中")
}

enum class KnowledgeIndexScope(val label: String) {
    Local("本机知识库"),
    Cloud("云端知识库"),
    Excluded("不进入知识库")
}

enum class KnowledgeQueryScope(val label: String) {
    Local("本机"),
    Cloud("云端"),
    All("全部")
}

enum class ScheduledMeetingStatus(val label: String) {
    Pending("待开始"),
    Overdue("已逾期"),
    Finished("已结束")
}

enum class TodoStatus(val label: String) {
    PendingConfirm("待确认"),
    Todo("待处理"),
    InProgress("进行中"),
    Done("已完成"),
    Canceled("已取消");

    val active: Boolean
        get() = this != Done && this != Canceled
}

enum class RecognitionLanguage(
    val remoteValue: String,
    val displayName: String,
    val shortName: String
) {
    Chinese("zh-CN", "中文", "中文"),
    English("en-US", "英文", "EN"),
    Auto("auto", "中英自由说", "中英");

    companion object {
        fun fromRemote(value: String?): RecognitionLanguage {
            return when (value?.trim()?.lowercase()) {
                "en", "en-us", "english" -> English
                "auto", "mixed", "zh-en" -> Auto
                else -> Chinese
            }
        }
    }
}

data class MeetingTask(
    val id: String,
    val title: String,
    val source: MeetingTaskSource,
    val status: MeetingTaskStatus,
    val localFilePath: String,
    val createdAtLabel: String,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val sizeLabel: String? = null,
    val errorMessage: String? = null,
    val remoteTaskId: String? = null,
    val progressPercent: Float = 0f,
    val progressLabel: String? = null,
    val progressStage: String? = null,
    val confirmed: Boolean = false,
    val syncStatus: CloudSyncStatus = CloudSyncStatus.LocalOnly,
    val knowledgeScope: KnowledgeIndexScope = KnowledgeIndexScope.Local,
    val isPrivate: Boolean = false,
    val deviceId: String? = null,
    val scheduleId: String? = null,
    val scheduleNote: String? = null,
    val recognitionLanguage: RecognitionLanguage = RecognitionLanguage.Chinese
)

data class Meeting(
    val id: String,
    val title: String,
    val timeLabel: String,
    val createdAtMillis: Long? = null,
    val participants: String,
    val durationLabel: String,
    val tags: List<String>,
    val status: MeetingStatus,
    val progress: Float,
    val summary: String,
    val topics: List<TopicItem>,
    val decisions: List<String>,
    val todos: List<TodoItem>,
    val risks: List<RiskItem>,
    val transcripts: List<TranscriptSegment>,
    val sourceFilePath: String? = null,
    val generatedAtLabel: String? = null,
    val remoteTaskId: String? = null
) {
    val subtitle: String
        get() = "$timeLabel，$durationLabel"

    val participantLine: String
        get() = "$participants，$durationLabel"

    val speakerIdentities: List<SpeakerIdentity>
        get() = transcripts.speakerIdentities()
}

data class ScheduledMeeting(
    val id: String,
    val time: String,
    val title: String,
    val participants: String,
    val note: String = "",
    val durationLabel: String,
    val reminderLabel: String = "提前 5 分钟提醒",
    val startAtMillis: Long? = null,
    val endAtMillis: Long? = null,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val status: ScheduledMeetingStatus = ScheduledMeetingStatus.Pending,
    val calendarEventId: Long? = null
) {
    val participantLine: String
        get() = if (durationLabel.isBlank()) participants else "$participants，$durationLabel"

    fun isFinished(nowMillis: Long = System.currentTimeMillis()): Boolean {
        return status == ScheduledMeetingStatus.Finished
    }

    fun isOverdue(nowMillis: Long = System.currentTimeMillis()): Boolean {
        val overdueAt = when {
            startAtMillis != null && isStartOnlySchedule() -> startAtMillis
            else -> endAtMillis
        }
        return status == ScheduledMeetingStatus.Overdue ||
            (status == ScheduledMeetingStatus.Pending && overdueAt != null && overdueAt < nowMillis)
    }
}

data class TodoItem(
    val id: String,
    val title: String,
    val source: String,
    val done: Boolean,
    val sourceTimestamp: String? = null,
    val meetingId: String? = null,
    val meetingTitle: String? = null,
    val description: String = "",
    val assigneeName: String? = null,
    val assigneeId: String? = null,
    val dueAtMillis: Long? = null,
    val dueAtLabel: String? = null,
    val priority: String = "medium",
    val status: TodoStatus = if (done) TodoStatus.Done else TodoStatus.PendingConfirm,
    val completedAtMillis: Long? = null,
    val completedAtLabel: String? = null,
    val sourceSegmentIndex: Int? = null,
    val lockedFields: Set<String> = emptySet()
) {
    val effectiveStatus: TodoStatus
        get() = if (done && status != TodoStatus.Canceled) TodoStatus.Done else status

    val needsConfirm: Boolean
        get() = effectiveStatus == TodoStatus.PendingConfirm

    val assigneeLabel: String?
        get() = assigneeName.cleanTodoRequiredText()

    val dueLabel: String?
        get() = dueAtLabel.cleanTodoRequiredText()?.takeUnless { label -> "%" in label }
            ?: dueAtMillis?.toTodoDueLabel()

    val meetingIdLabel: String?
        get() = meetingId.cleanTodoText()

    val meetingTitleLabel: String?
        get() = meetingTitle.cleanTodoText()

    val sourceTimestampLabel: String?
        get() = sourceTimestamp.cleanTodoText()

    val sourceLabel: String?
        get() = source.cleanTodoText()

    val missingAssigneeInfo: Boolean
        get() = assigneeLabel == null

    val missingDueInfo: Boolean
        get() = dueLabel == null

    val missingRequiredInfo: Boolean
        get() = missingAssigneeInfo || missingDueInfo

    fun isOverdue(nowMillis: Long = System.currentTimeMillis()): Boolean {
        return effectiveStatus.active && dueAtMillis != null && dueAtMillis < nowMillis
    }

    fun isDueToday(nowMillis: Long = System.currentTimeMillis()): Boolean {
        val due = dueAtMillis ?: return false
        return due.toLocalDayKey() == nowMillis.toLocalDayKey()
    }

    fun withMeetingContext(meeting: Meeting): TodoItem {
        return copy(
            meetingId = meetingIdLabel ?: meeting.id,
            meetingTitle = meetingTitleLabel ?: meeting.title
        )
    }
}

private fun String?.cleanTodoText(): String? {
    val clean = this?.trim().orEmpty()
    return clean.takeUnless { it.isBlank() || it.equals("null", ignoreCase = true) }
}

private fun String?.cleanTodoRequiredText(): String? {
    val clean = cleanTodoText() ?: return null
    val normalized = clean.lowercase(java.util.Locale.ROOT).replace(Regex("\\s+"), "")
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

private fun Long.toTodoDueLabel(): String {
    return java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA).format(java.util.Date(this))
}

private fun Long.toLocalDayKey(): String {
    val calendar = java.util.Calendar.getInstance()
    calendar.timeInMillis = this
    return "${calendar.get(java.util.Calendar.YEAR)}-${calendar.get(java.util.Calendar.DAY_OF_YEAR)}"
}

data class TopicItem(
    val id: String,
    val title: String,
    val summary: String,
    val source: String,
    val sourceTimestamp: String? = null
)

data class RiskItem(
    val id: String,
    val title: String,
    val level: String,
    val description: String,
    val recommendation: String,
    val source: String,
    val sourceTimestamp: String? = null
)

data class TranscriptSegment(
    val speaker: String,
    val text: String,
    val timestamp: String,
    val startMs: Long? = null,
    val endMs: Long? = null,
    val speakerId: String? = null
) {
    val timeRangeLabel: String
        get() = when {
            startMs != null && endMs != null -> "${startMs.toClock()} - ${endMs.toClock()}"
            startMs != null -> startMs.toClock()
            else -> timestamp
        }

    val stableSpeakerId: String
        get() = speakerId.cleanSpeakerText() ?: speakerIdentityIdForName(speaker)
}

data class SpeakerIdentity(
    val id: String,
    val displayName: String
)

data class SpeakerProfile(
    val id: String,
    val displayName: String,
    val sampleCount: Int = 0,
    val active: Boolean = true,
    val updatedAt: String = ""
)

fun speakerIdentityIdForName(name: String): String {
    val clean = name.cleanSpeakerText() ?: "说话人"
    return "spk-${Integer.toHexString(clean.hashCode())}"
}

fun TranscriptSegment.normalizedSpeakerIdentity(): TranscriptSegment {
    val cleanSpeaker = speaker.cleanSpeakerText() ?: "说话人"
    val cleanSpeakerId = speakerId.cleanSpeakerText() ?: speakerIdentityIdForName(cleanSpeaker)
    return copy(speaker = cleanSpeaker, speakerId = cleanSpeakerId)
}

fun List<TranscriptSegment>.speakerIdentities(): List<SpeakerIdentity> {
    val identities = linkedMapOf<String, String>()
    forEach { segment ->
        val normalized = segment.normalizedSpeakerIdentity()
        identities.putIfAbsent(normalized.stableSpeakerId, normalized.speaker)
    }
    return identities.map { item -> SpeakerIdentity(item.key, item.value) }
}

private fun String?.cleanSpeakerText(): String? {
    val clean = this?.trim().orEmpty()
    return clean.takeUnless { it.isBlank() || it.equals("null", ignoreCase = true) || it == "未分离" }
}

data class KnowledgeTopic(
    val meetingId: String,
    val title: String,
    val subtitle: String
)

data class ProcessingStep(
    val title: String,
    val subtitle: String
)

data class SearchSuggestion(
    val keyword: String,
    val resultHint: String
)

data class HomeDashboard(
    val pendingText: String,
    val todayMeeting: ScheduledMeeting,
    val recentMeetings: List<Meeting>
)

private fun Long.toClock(): String {
    val totalSeconds = (this / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}
