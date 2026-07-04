package com.huiyi.app.model

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.huiyi.app.data.Meeting
import com.huiyi.app.data.MeetingStatus
import com.huiyi.app.ui.Brand

typealias MeetingUi = Meeting

enum class AppScreen(val title: String, val icon: ImageVector, val root: Boolean = false) {
    Home("会议", Icons.Filled.Home, true),
    Tasks("待办", Icons.Filled.TaskAlt, true),
    Knowledge("知识库", Icons.Filled.ChatBubbleOutline, true),
    Profile("我的", Icons.Filled.Person, true),
    Import("导入文件", Icons.Filled.UploadFile),
    Search("搜索", Icons.Filled.Search),
    Schedules("预约会议", Icons.Filled.Article),
    Meetings("全部会议", Icons.Filled.Article),
    Record("实时记录", Icons.Filled.Mic),
    Voiceprints("声纹库", Icons.Filled.Mic),
    Membership("会员中心", Icons.Filled.Article),
    PaymentOrders("订单记录", Icons.Filled.Article),
    Generating("生成中", Icons.Filled.Article),
    Detail("会议详情", Icons.Filled.Article)
}

enum class SheetType {
    RecordConsent,
    ImportFile,
    Notifications,
    CreateMeeting,
    Speakers,
    Source,
    Export,
    Correction,
    EditMinutes,
    EditMeetingInfo,
    TodoDetail,
    CreateTodo,
    DeleteMeeting,
    DeleteSchedule,
    DeleteTask,
    ScheduleReminder,
    DeleteData,
    LogoutConfirm,
    UserAgreement,
    PrivacyPolicy
}

val Meeting.statusColor: Color
    get() = when (status) {
        MeetingStatus.Generated -> Brand
        MeetingStatus.PendingConfirm -> Color(0xFFC58B00)
        MeetingStatus.Scheduled -> Color(0xFF4F6F7A)
    }
