package com.huiyi.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

interface LocalScheduleStore {
    fun loadSchedules(): List<ScheduledMeeting>
    fun saveSchedules(meetings: List<ScheduledMeeting>)
}

class SharedPrefsLocalScheduleStore(context: Context) : LocalScheduleStore {
    private val preferences = context.getSharedPreferences("huixiao_scheduled_meetings", Context.MODE_PRIVATE)

    override fun loadSchedules(): List<ScheduledMeeting> {
        val raw = preferences.getString(KEY_SCHEDULES, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(
                        ScheduledMeeting(
                            id = item.getString("id"),
                            time = item.getString("time"),
                            title = item.getString("title"),
                            participants = item.getString("participants"),
                            note = item.optString("note"),
                            durationLabel = item.optString("durationLabel"),
                            reminderLabel = item.optString("reminderLabel", "提前 5 分钟提醒"),
                            startAtMillis = item.optNullableLong("startAtMillis"),
                            endAtMillis = item.optNullableLong("endAtMillis"),
                            createdAtMillis = item.optLong("createdAtMillis", 0L),
                            status = item.optString("status").ifBlank { null }?.let {
                                runCatching { ScheduledMeetingStatus.valueOf(it) }.getOrNull()
                            } ?: ScheduledMeetingStatus.Pending,
                            calendarEventId = item.optNullableLong("calendarEventId")
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    override fun saveSchedules(meetings: List<ScheduledMeeting>) {
        val array = JSONArray()
        meetings.forEach { meeting ->
            array.put(
                JSONObject()
                    .put("id", meeting.id)
                    .put("time", meeting.time)
                    .put("title", meeting.title)
                    .put("participants", meeting.participants)
                    .put("note", meeting.note)
                    .put("durationLabel", meeting.durationLabel)
                    .put("reminderLabel", meeting.reminderLabel)
                    .put("startAtMillis", meeting.startAtMillis ?: JSONObject.NULL)
                    .put("endAtMillis", meeting.endAtMillis ?: JSONObject.NULL)
                    .put("createdAtMillis", meeting.createdAtMillis)
                    .put("status", meeting.status.name)
                    .put("calendarEventId", meeting.calendarEventId ?: JSONObject.NULL)
            )
        }
        preferences.edit().putString(KEY_SCHEDULES, array.toString()).apply()
    }

    private companion object {
        const val KEY_SCHEDULES = "schedules"
    }
}

private fun JSONObject.optNullableLong(name: String): Long? {
    return if (has(name) && !isNull(name)) optLong(name) else null
}
