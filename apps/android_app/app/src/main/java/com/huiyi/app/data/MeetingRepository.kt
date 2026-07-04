package com.huiyi.app.data

interface MeetingRepository {
    fun getHomeDashboard(): HomeDashboard
    fun getRecentMeetings(): List<Meeting>
    fun getMeeting(id: String): Meeting
    fun getTodos(): List<TodoItem>
    fun getKnowledgeTopics(): List<KnowledgeTopic>
    fun getSearchSuggestions(): List<SearchSuggestion>
    fun getProcessingSteps(): List<ProcessingStep>
    fun getActiveRecordingSegments(): List<TranscriptSegment>
}

object EmptyMeetingRepository : MeetingRepository {
    override fun getHomeDashboard(): HomeDashboard {
        return HomeDashboard(
            pendingText = "还没有待处理会议",
            todayMeeting = ScheduledMeeting(
                id = "empty",
                time = "",
                title = "",
                participants = "",
                durationLabel = "",
                reminderLabel = ""
            ),
            recentMeetings = emptyList()
        )
    }

    override fun getRecentMeetings(): List<Meeting> = emptyList()

    override fun getMeeting(id: String): Meeting {
        error("会议不存在或真实处理结果未生成")
    }

    override fun getTodos(): List<TodoItem> = emptyList()

    override fun getKnowledgeTopics(): List<KnowledgeTopic> = emptyList()

    override fun getSearchSuggestions(): List<SearchSuggestion> = emptyList()

    override fun getProcessingSteps(): List<ProcessingStep> = emptyList()

    override fun getActiveRecordingSegments(): List<TranscriptSegment> = emptyList()
}
