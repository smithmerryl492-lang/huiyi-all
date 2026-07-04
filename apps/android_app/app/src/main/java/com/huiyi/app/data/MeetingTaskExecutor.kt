package com.huiyi.app.data

enum class MeetingTaskExecutionPhase(val label: String) {
    Preparing("准备文件"),
    Transcribing("音频转写"),
    GeneratingMinutes("生成纪要"),
    SavingResult("保存结果"),
    Finished("处理完成")
}

data class MeetingTaskExecutionSnapshot(
    val taskId: String,
    val phase: MeetingTaskExecutionPhase,
    val steps: List<ProcessingStep>
) {
    val finished: Boolean
        get() = phase == MeetingTaskExecutionPhase.Finished
}

interface MeetingTaskExecutor {
    fun start(task: MeetingTask): MeetingTaskExecutionSnapshot
    fun advance(task: MeetingTask, snapshot: MeetingTaskExecutionSnapshot): MeetingTaskExecutionSnapshot
}

object PlaceholderMeetingTaskExecutor : MeetingTaskExecutor {
    override fun start(task: MeetingTask): MeetingTaskExecutionSnapshot {
        return snapshot(task, MeetingTaskExecutionPhase.Preparing)
    }

    override fun advance(task: MeetingTask, snapshot: MeetingTaskExecutionSnapshot): MeetingTaskExecutionSnapshot {
        val next = when (snapshot.phase) {
            MeetingTaskExecutionPhase.Preparing -> MeetingTaskExecutionPhase.Transcribing
            MeetingTaskExecutionPhase.Transcribing -> MeetingTaskExecutionPhase.GeneratingMinutes
            MeetingTaskExecutionPhase.GeneratingMinutes -> MeetingTaskExecutionPhase.SavingResult
            MeetingTaskExecutionPhase.SavingResult -> MeetingTaskExecutionPhase.Finished
            MeetingTaskExecutionPhase.Finished -> MeetingTaskExecutionPhase.Finished
        }
        return snapshot(task, next)
    }

    private fun snapshot(task: MeetingTask, phase: MeetingTaskExecutionPhase): MeetingTaskExecutionSnapshot {
        return MeetingTaskExecutionSnapshot(
            taskId = task.id,
            phase = phase,
            steps = buildSteps(task, phase)
        )
    }

    private fun buildSteps(task: MeetingTask, phase: MeetingTaskExecutionPhase): List<ProcessingStep> {
        val sourceText = task.source.label
        return listOf(
            ProcessingStep(
                "本地文件准备",
                when {
                    phase.ordinal > MeetingTaskExecutionPhase.Preparing.ordinal -> "已保存：${task.title}"
                    phase == MeetingTaskExecutionPhase.Preparing -> "正在检查本地文件"
                    else -> "等待文件"
                }
            ),
            ProcessingStep(
                "音频转写",
                when {
                    phase.ordinal > MeetingTaskExecutionPhase.Transcribing.ordinal -> "Dolphin-CN-Dialect 转写已完成"
                    phase == MeetingTaskExecutionPhase.Transcribing -> "正在调用转写任务"
                    else -> "待处理"
                }
            ),
            ProcessingStep(
                "纪要生成",
                when {
                    phase.ordinal > MeetingTaskExecutionPhase.GeneratingMinutes.ordinal -> "摘要、决策与待办已生成"
                    phase == MeetingTaskExecutionPhase.GeneratingMinutes -> "正在生成摘要、决策与待办"
                    else -> "等待转写"
                }
            ),
            ProcessingStep(
                "任务状态",
                if (phase == MeetingTaskExecutionPhase.Finished) "已完成 · $sourceText" else "${phase.label} · $sourceText"
            )
        )
    }
}
