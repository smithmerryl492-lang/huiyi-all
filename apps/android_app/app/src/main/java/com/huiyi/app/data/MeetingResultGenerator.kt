package com.huiyi.app.data

interface MeetingResultGenerator {
    fun buildMeeting(task: MeetingTask): Meeting
}

class StoredMeetingResultGenerator(
    private val resultStore: MeetingProcessingResultStore
) : MeetingResultGenerator {
    override fun buildMeeting(task: MeetingTask): Meeting {
        return resultStore.loadResultForTask(task)?.toMeeting(task)
            ?: error("真实处理结果不存在，不能生成会议详情")
    }
}

object MissingMeetingResultGenerator : MeetingResultGenerator {
    override fun buildMeeting(task: MeetingTask): Meeting {
        error("未配置真实结果读取器，不能生成会议详情")
    }
}

private fun MeetingProcessingResult.toMeeting(task: MeetingTask): Meeting {
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
        status = if (task.confirmed) MeetingStatus.Generated else MeetingStatus.PendingConfirm,
        progress = 1f,
        summary = summary,
        topics = topics,
        decisions = decisions,
        todos = todos.filter { it.effectiveStatus != TodoStatus.Canceled },
        risks = risks,
        transcripts = transcripts,
        sourceFilePath = sourceFilePath,
        generatedAtLabel = generatedAtLabel
    )
}

private fun List<String>.filterUsefulMeetingTags(): List<String> {
    val hidden = setOf("真实转写", "AI纪要", "导入", "录音")
    return map { it.trim() }
        .filter { it.isNotBlank() && it !in hidden }
        .distinct()
}
