package com.huiyi.app.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.huiyi.app.MainActivity
import com.huiyi.app.data.ScheduledMeeting

class ScheduleStatusNotifier(private val context: Context) {
    private val appContext = context.applicationContext

    fun notifyMeetingDue(meeting: ScheduledMeeting): Boolean {
        if (!canPostNotifications(appContext)) return false
        ensureChannel()
        val openIntent = PendingIntent.getActivity(
            appContext,
            meeting.id.hashCode() and 0x7FFFFFFF,
            Intent(appContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val content = listOf(meeting.title, meeting.time).filter { it.isNotBlank() }.joinToString(" · ")
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("会议即将开始")
            .setContentText(content.ifBlank { "点击回到鲲穹会纪" })
            .setStyle(NotificationCompat.BigTextStyle().bigText("点击回到鲲穹会纪开始记录：${content.ifBlank { meeting.title.ifBlank { "预约会议" } }}"))
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()
        NotificationManagerCompat.from(appContext).notify(NOTIFICATION_ID_BASE + (meeting.id.hashCode() and 0x0FFF), notification)
        return true
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(CHANNEL_ID, "会议提醒", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "预约会议状态栏提醒"
        }
        appContext.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        fun canPostNotifications(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED &&
                    NotificationManagerCompat.from(context).areNotificationsEnabled()
            } else {
                NotificationManagerCompat.from(context).areNotificationsEnabled()
            }
        }

        private const val CHANNEL_ID = "schedule_status"
        private const val NOTIFICATION_ID_BASE = 62000
    }
}
