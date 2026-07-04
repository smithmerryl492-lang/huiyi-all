package com.huiyi.app.sheets

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.GppGood
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.huiyi.app.audio.AudioSegmentPlaybackState
import com.huiyi.app.audio.AudioSegmentPlayer
import com.huiyi.app.components.RowItem
import com.huiyi.app.components.SheetContent
import com.huiyi.app.components.TranscriptLine
import com.huiyi.app.data.Meeting
import com.huiyi.app.data.MeetingTask
import com.huiyi.app.data.MeetingTaskStatus
import com.huiyi.app.data.RecognitionLanguage
import com.huiyi.app.data.ScheduledMeeting
import com.huiyi.app.data.SpeakerIdentity
import com.huiyi.app.data.TodoItem
import com.huiyi.app.data.TodoStatus
import com.huiyi.app.data.normalizedTodoPriority
import com.huiyi.app.ui.Brand
import com.huiyi.app.ui.BrandCyan
import com.huiyi.app.ui.BrandSoftCyan
import com.huiyi.app.ui.BrandSoft
import com.huiyi.app.ui.Danger
import com.huiyi.app.ui.Muted
import com.huiyi.app.ui.Ink
import com.huiyi.app.ui.Line
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
private fun SheetTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    label: String? = null,
    placeholder: String? = null,
    singleLine: Boolean = false,
    minLines: Int = 1
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        label = label?.let { { Text(it) } },
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = singleLine,
        minLines = minLines,
        shape = RoundedCornerShape(18.dp),
        textStyle = TextStyle(color = Ink, fontSize = 15.sp, lineHeight = 22.sp),
        colors = TextFieldDefaults.colors(
            focusedTextColor = Ink,
            unfocusedTextColor = Ink,
            disabledTextColor = Muted,
            focusedContainerColor = Color(0xFFFAFCFF),
            unfocusedContainerColor = Color.White,
            disabledContainerColor = Color(0xFFF5F8FC),
            cursorColor = Brand,
            focusedIndicatorColor = Brand.copy(alpha = 0.72f),
            unfocusedIndicatorColor = Line,
            disabledIndicatorColor = Line.copy(alpha = 0.75f),
            focusedLabelColor = Brand,
            unfocusedLabelColor = Muted,
            disabledLabelColor = Muted,
            focusedPlaceholderColor = Muted,
            unfocusedPlaceholderColor = Muted,
            disabledPlaceholderColor = Muted
        ),
        modifier = modifier
    )
}

@Composable
private fun SheetPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    height: Dp = 50.dp
) {
    val shape = RoundedCornerShape(999.dp)
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = shape,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            contentColor = Color.White,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = Muted
        ),
        contentPadding = PaddingValues(horizontal = 18.dp),
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(shape)
            .background(
                if (enabled) {
                    Brush.horizontalGradient(listOf(Brand, Color(0xFF4B7BFF), BrandCyan))
                } else {
                    Brush.horizontalGradient(listOf(Color(0xFFE8EEF8), Color(0xFFE8EEF8)))
                }
            )
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, fontWeight = FontWeight.Bold, fontSize = 15.sp)
    }
}

@Composable
private fun SheetSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(999.dp),
        border = BorderStroke(1.dp, Brand.copy(alpha = 0.24f)),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = BrandSoft.copy(alpha = 0.62f),
            contentColor = Brand,
            disabledContainerColor = Color(0xFFF2F5FA),
            disabledContentColor = Muted
        ),
        contentPadding = PaddingValues(horizontal = 18.dp),
        modifier = modifier.fillMaxWidth().height(46.dp)
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
}

@Composable
private fun SheetDangerButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(999.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Danger, contentColor = Color.White),
        modifier = modifier.fillMaxWidth().height(48.dp)
    ) {
        Text(text, fontWeight = FontWeight.Bold, fontSize = 15.sp)
    }
}

@Composable
fun RecordConsentSheet(
    recognitionLanguage: RecognitionLanguage,
    onRecognitionLanguageChange: (RecognitionLanguage) -> Unit,
    onCancel: () -> Unit,
    onStart: () -> Unit
) {
    SheetContent("开始录音前确认") {
        Text("请确认本次录音已获得会议参与方许可，并遵守当地法律法规。录音中会持续展示状态提醒。", color = Muted, fontSize = 14.sp, lineHeight = 22.sp)
        RowItem(Icons.Filled.Mic, "麦克风权限")
        RowItem(Icons.Filled.Article, "本地录音文件")
        RecognitionLanguageSelector(
            selected = recognitionLanguage,
            onSelect = onRecognitionLanguageChange,
            modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
        )
        SheetPrimaryButton("开始录音", onClick = onStart)
        TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("取消") }
    }
}

@Composable
fun ImportFileSheet(
    tasks: List<MeetingTask>,
    recognitionLanguage: RecognitionLanguage,
    onRecognitionLanguageChange: (RecognitionLanguage) -> Unit,
    onClose: () -> Unit,
    onPickFile: () -> Unit,
    onSubmit: () -> Unit,
    onDeleteTask: (MeetingTask) -> Unit,
    onProcessingTask: (MeetingTask) -> Unit
) {
    val compact = LocalConfiguration.current.screenWidthDp < 400
    val minSheetHeight = (LocalConfiguration.current.screenHeightDp * 0.60f).dp
    val queuedTasks = tasks
        .filter { it.status == MeetingTaskStatus.WaitingProcess }
        .sortedBy { it.createdAtMillis }
    val processingTask = tasks.firstOrNull { it.status == MeetingTaskStatus.Processing }
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = minSheetHeight)
            .background(Color(0xFFF0F5FF))
            .padding(horizontal = if (compact) 16.dp else 20.dp, vertical = 10.dp)
    ) {
        SmartSheetHandle()
        Spacer(Modifier.height(14.dp))
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onClose,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "关闭导入文件", tint = Ink, modifier = Modifier.size(22.dp))
            }
            Text(
                "导入文件",
                color = Ink,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
        Surface(
            color = Color.White,
            shape = RoundedCornerShape(16.dp),
            shadowElevation = 2.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(20.dp)) {
                Text("本地文件", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                RecognitionLanguageSelector(
                    selected = recognitionLanguage,
                    onSelect = onRecognitionLanguageChange,
                    modifier = Modifier.padding(top = 14.dp)
                )
                SheetPrimaryButton(
                    text = "选择文件",
                    onClick = onPickFile,
                    modifier = Modifier.padding(top = 16.dp),
                    height = 48.dp
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Surface(
            color = Color.White,
            shape = RoundedCornerShape(16.dp),
            shadowElevation = 2.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    if (queuedTasks.isEmpty()) "待处理文件" else "待处理文件（${queuedTasks.size}）",
                    color = Ink,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium
                )
                if (processingTask != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        RowItem(
                            Icons.Filled.UploadFile,
                            processingTask.title,
                            listOfNotNull(processingTask.status.label, processingTask.sizeLabel).joinToString(" · "),
                            onClick = { onProcessingTask(processingTask) }
                        )
                        SheetProgressLine(processingTask.progressPercent / 100f)
                        Text(processingTask.progressLabel ?: "处理中", color = Muted, fontSize = 13.sp)
                    }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(Line.copy(alpha = 0.7f))
                    )
                }
                when {
                    queuedTasks.isEmpty() -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(vertical = 2.dp)
                        ) {
                            Box(
                                Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(BrandSoft),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Filled.UploadFile, contentDescription = null, tint = Brand, modifier = Modifier.size(19.dp))
                            }
                            Text("还没有待处理文件", color = Muted, fontSize = 14.sp, modifier = Modifier.padding(start = 10.dp))
                        }
                    }
                    else -> {
                        queuedTasks.forEach { task ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    RowItem(
                                        Icons.Filled.UploadFile,
                                        task.title,
                                        listOfNotNull(task.status.label, task.sizeLabel, task.errorMessage).joinToString(" · "),
                                        onClick = { onProcessingTask(task) }
                                    )
                                }
                                IconButton(onClick = { onDeleteTask(task) }) {
                                    Icon(Icons.Filled.DeleteOutline, contentDescription = "删除任务", tint = Danger)
                                }
                            }
                        }
                    }
                }
                SheetPrimaryButton(
                    text = when {
                        processingTask != null -> "加入待处理"
                        queuedTasks.size > 1 -> "开始处理"
                        else -> "开始处理"
                    },
                    onClick = onSubmit,
                    enabled = queuedTasks.isNotEmpty(),
                    height = 48.dp
                )
            }
        }
        Spacer(Modifier.height(18.dp))
    }
}

@Composable
private fun SmartSheetHandle() {
    Box(
        Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .width(36.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(Brand.copy(alpha = 0.22f))
        )
    }
}

@Composable
private fun SheetProgressLine(progress: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(Color(0xFFE8EEF8))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .height(6.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(Brush.horizontalGradient(listOf(Brand, BrandCyan)))
        )
    }
}

@Composable
private fun RecognitionLanguageSelector(
    selected: RecognitionLanguage,
    onSelect: (RecognitionLanguage) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxWidth()) {
        Text("识别语言", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Row(
            modifier = Modifier
                .padding(top = 10.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFFF3F7FF))
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            RecognitionLanguage.entries.forEach { item ->
                val active = item == selected
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(38.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(if (active) Color.White else Color.Transparent)
                        .clickable { onSelect(item) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        item.displayName,
                        color = if (active) Brand else Muted,
                        fontSize = 13.sp,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
fun NotificationsSheet(onClose: () -> Unit) {
    val compact = LocalConfiguration.current.screenWidthDp < 400
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFFF0F5FF))
            .padding(horizontal = if (compact) 16.dp else 20.dp, vertical = 16.dp)
    ) {
        SmartSheetHandle()
        Spacer(Modifier.height(14.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("消息通知", color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("会议提醒、同步结果会显示在这里", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
            }
            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.86f))
            ) {
                Icon(Icons.Filled.Close, contentDescription = "关闭通知", tint = Ink, modifier = Modifier.size(20.dp))
            }
        }
        Spacer(Modifier.height(16.dp))
        Surface(
            color = Color.White,
            shape = RoundedCornerShape(20.dp),
            shadowElevation = 2.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(BrandSoft),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.Notifications, contentDescription = null, tint = Brand, modifier = Modifier.size(22.dp))
                }
                Column(Modifier.padding(start = 14.dp).weight(1f)) {
                    Text("暂无通知", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text("有新的会议提醒或同步状态时会在这里展示", color = Muted, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 5.dp))
                }
            }
        }
        Spacer(Modifier.height(18.dp))
    }
}

@Composable
fun CreateMeetingSheet(
    initialMeeting: ScheduledMeeting?,
    saving: Boolean,
    onClose: () -> Unit,
    onCreate: (String, String, String, String) -> String?,
    onDelete: (String) -> Unit
) {
    var title by remember(initialMeeting?.id) { mutableStateOf(initialMeeting?.title.orEmpty()) }
    var participants by remember(initialMeeting?.id) { mutableStateOf(initialMeeting?.participants.orEmpty()) }
    var note by remember(initialMeeting?.id) { mutableStateOf(initialMeeting?.note.orEmpty()) }
    var localError by remember(initialMeeting?.id) { mutableStateOf("") }
    val finished = initialMeeting?.isFinished() == true
    val overdue = initialMeeting?.isOverdue() == true
    val initialRange = remember(initialMeeting?.id) { initialMeeting.toSchedulePickerState() }
    var meetingDate by remember(initialMeeting?.id) { mutableStateOf(initialRange.date) }
    var startTime by remember(initialMeeting?.id) { mutableStateOf(initialRange.start) }
    var pickerMode by remember { mutableStateOf<SchedulePickerMode?>(null) }
    val time = remember(meetingDate, startTime) {
        "${meetingDate.format(ScheduleDateFormatter)} ${startTime.format(ScheduleTimeFormatter)}"
    }
    val compact = LocalConfiguration.current.screenWidthDp < 400
    val fieldGap = if (compact) 12.dp else 14.dp
    val actionTopGap = if (compact) 16.dp else 20.dp
    SheetContent(
        if (initialMeeting == null) "预约会议" else "修改今日会议",
        centerTitle = true,
        showHandle = true
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(fieldGap)) {
            if (finished) {
                Text("该会议已结束，仅可查看；修改或删除会被拒绝。", color = Danger, fontSize = 13.sp)
            } else if (overdue) {
                Text("该会议已逾期，可补会、修改时间或删除。", color = Color(0xFFB33D21), fontSize = 13.sp)
            }
            SheetTextField(value = title, onValueChange = { title = it; localError = "" }, enabled = !finished && !saving, label = "会议主题", modifier = Modifier.fillMaxWidth())
            ScheduleTimeSelector(
                date = meetingDate,
                start = startTime,
                enabled = !finished && !saving,
                onPickDate = { pickerMode = SchedulePickerMode.StartDateTime }
            )
            SheetTextField(value = participants, onValueChange = { participants = it; localError = "" }, enabled = !finished && !saving, label = "参会人", modifier = Modifier.fillMaxWidth())
            SheetTextField(value = note, onValueChange = { note = it; localError = "" }, enabled = !finished && !saving, label = "会议备注", minLines = 3, modifier = Modifier.fillMaxWidth())
            if (localError.isNotBlank()) {
                Text(localError, color = Danger, fontSize = 13.sp, lineHeight = 18.sp)
            }
            if (saving) {
                InlineBusyStatus("正在保存预约...")
            }
        }
        Spacer(Modifier.height(actionTopGap))
        SheetPrimaryButton(
            text = if (saving) "保存中..." else if (initialMeeting == null) "创建预约" else "保存修改",
            onClick = {
            localError = when {
                title.trim().isBlank() -> "会议主题不能为空"
                else -> ""
            }
            if (localError.isBlank()) {
                localError = onCreate(title, time, participants, note).orEmpty()
            }
        },
            enabled = !finished && !saving
        )
        if (initialMeeting != null) {
            TextButton(onClick = { onDelete(initialMeeting.id) }, enabled = !saving, modifier = Modifier.fillMaxWidth()) {
                Text("删除会议", color = Danger)
            }
        }
        TextButton(onClick = onClose, enabled = !saving, modifier = Modifier.fillMaxWidth()) { Text("取消") }
    }
    SchedulePickerDialogHost(
        mode = pickerMode,
        currentDate = meetingDate,
        currentStart = startTime,
        onDismiss = { pickerMode = null },
        onConfirm = { date, start ->
            meetingDate = date
            startTime = start
            localError = ""
            pickerMode = null
        }
    )
}

@Composable
fun ScheduleReminderSheet(
    meeting: ScheduledMeeting?,
    onStart: () -> Unit,
    onLater: () -> Unit,
    onDismiss: () -> Unit
) {
    SheetContent("会议即将开始") {
        if (meeting == null) {
            Text("当前预约会议不存在。", color = Muted, fontSize = 14.sp)
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("关闭") }
            return@SheetContent
        }
        Text(meeting.title, color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(meeting.time, color = Muted, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
        if (meeting.participants.isNotBlank()) {
            RowItem(Icons.Filled.Person, "参会人", meeting.participants)
        }
        SheetPrimaryButton("开始记录", onClick = onStart)
        SheetSecondaryButton("稍后提醒", onClick = onLater)
        TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("忽略") }
    }
}

@Composable
private fun ScheduleTimeSelector(
    date: LocalDate,
    start: LocalTime,
    enabled: Boolean,
    onPickDate: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("会议时间", color = Muted, fontSize = 13.sp)
        Surface(
            color = Color(0xFFFAFCFF),
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, Line),
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled) { onPickDate() }
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier.size(40.dp).clip(CircleShape).background(BrandSoftCyan),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.CalendarMonth, contentDescription = null, tint = Brand, modifier = Modifier.size(18.dp))
                }
                Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(date.format(ScheduleDateFormatter), color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text(
                        start.format(ScheduleTimeFormatter),
                        color = Muted,
                        fontSize = 13.sp
                    )
                }
                Text("选择", color = if (enabled) Brand else Muted, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun SchedulePickerDialogHost(
    mode: SchedulePickerMode?,
    currentDate: LocalDate,
    currentStart: LocalTime,
    onDismiss: () -> Unit,
    onConfirm: (LocalDate, LocalTime) -> Unit
) {
    when (mode) {
        SchedulePickerMode.StartDateTime -> ScheduleDateTimePickerSheet(
            currentDate = currentDate,
            currentStart = currentStart,
            onDismiss = onDismiss,
            onConfirm = onConfirm
        )
        null -> Unit
    }
}

@Composable
private fun TimeWheelPicker(value: LocalTime, onValueChange: (LocalTime) -> Unit, modifier: Modifier = Modifier) {
    val hours = (0..23).toList()
    val minutes = (0..59).toList()
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(46.dp), verticalAlignment = Alignment.CenterVertically) {
        SnapWheelPicker(
            values = hours,
            selected = value.hour,
            unit = "时",
            onSelect = { onValueChange(LocalTime.of(it, value.minute)) },
            modifier = Modifier.weight(1f)
        )
        SnapWheelPicker(
            values = minutes,
            selected = value.minute,
            unit = "分",
            onSelect = { onValueChange(LocalTime.of(value.hour, it)) },
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun ScheduleDateTimePickerSheet(
    currentDate: LocalDate,
    currentStart: LocalTime,
    onDismiss: () -> Unit,
    onConfirm: (LocalDate, LocalTime) -> Unit
) {
    val today = LocalDate.now()
    val selectedInitialDate = if (currentDate.isBefore(today)) today else currentDate
    val firstDate = today
    val lastDate = maxOf(today.plusDays(180), selectedInitialDate.plusDays(30))
    val dates = remember(currentDate) {
        val count = java.time.temporal.ChronoUnit.DAYS.between(firstDate, lastDate).toInt().coerceAtLeast(0) + 1
        List(count) { firstDate.plusDays(it.toLong()) }
    }
    var selectedDate by remember(currentDate) { mutableStateOf(selectedInitialDate) }
    var selectedHour by remember(currentStart) { mutableStateOf(currentStart.hour) }
    var selectedMinute by remember(currentStart) { mutableStateOf(currentStart.minute) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Surface(
                color = Color.White,
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(bottom = 34.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(80.dp)
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onDismiss, modifier = Modifier.size(56.dp)) {
                            Icon(Icons.Filled.Close, contentDescription = "取消", tint = Muted, modifier = Modifier.size(34.dp))
                        }
                        Text(
                            "开始时间",
                            color = Ink,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = {
                                val start = LocalTime.of(selectedHour, selectedMinute)
                                onConfirm(selectedDate, start)
                            },
                            modifier = Modifier.size(56.dp)
                        ) {
                            Icon(Icons.Filled.Check, contentDescription = "确定", tint = Brand, modifier = Modifier.size(36.dp))
                        }
                    }
                    Surface(color = Line, modifier = Modifier.fillMaxWidth().height(1.dp)) {}
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(280.dp)
                            .padding(horizontal = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            color = BrandSoftCyan,
                            shape = RoundedCornerShape(18.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                        ) {}
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            SnapWheelTextPicker(
                                values = dates,
                                selected = selectedDate,
                                label = { value, _ -> value.scheduleWheelDateLabel(today) },
                                onSelect = { selectedDate = it },
                                loop = false,
                                modifier = Modifier.weight(1.45f)
                            )
                            SnapWheelTextPicker(
                                values = (0..23).toList(),
                                selected = selectedHour,
                                unit = "时",
                                onSelect = { selectedHour = it },
                                modifier = Modifier.weight(0.82f)
                            )
                            SnapWheelTextPicker(
                                values = (0..59).toList(),
                                selected = selectedMinute,
                                unit = "分",
                                onSelect = { selectedMinute = it },
                                modifier = Modifier.weight(0.82f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun <T> SnapWheelTextPicker(
    values: List<T>,
    selected: T,
    label: (T, Boolean) -> String,
    onSelect: (T) -> Unit,
    loop: Boolean = true,
    modifier: Modifier = Modifier
) {
    val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()
    val selectedIndex = values.indexOf(selected).coerceAtLeast(0)
    val loopCount = if (loop) values.size * 1000 else values.size
    val loopMiddle = if (loop) (loopCount / 2 / values.size) * values.size else 0
    val initialLoopIndex = loopMiddle + selectedIndex
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialLoopIndex)
    val flingBehavior = rememberSnapFlingBehavior(lazyListState = listState, snapPosition = SnapPosition.Center)
    var initialized by remember { mutableStateOf(false) }
    LaunchedEffect(selectedIndex) {
        if (!initialized) {
            listState.scrollToItem(initialLoopIndex)
            initialized = true
        }
    }
    val centerLoopIndex by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val viewportCenter = (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2
            layoutInfo.visibleItemsInfo
                .minByOrNull { kotlin.math.abs((it.offset + it.size / 2) - viewportCenter) }
                ?.index ?: initialLoopIndex
        }
    }
    val centerValueIndex = if (loop) centerLoopIndex.floorMod(values.size) else centerLoopIndex.coerceIn(values.indices)
    val selectedValue = values.getOrNull(centerValueIndex)
    LaunchedEffect(listState.isScrollInProgress, selectedValue) {
        if (!listState.isScrollInProgress && selectedValue != null && selectedValue != selected) {
            onSelect(selectedValue)
        }
    }
    LazyColumn(
        state = listState,
        flingBehavior = flingBehavior,
        horizontalAlignment = Alignment.CenterHorizontally,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 112.dp),
        modifier = modifier.height(280.dp)
    ) {
        items(loopCount) { index ->
            val item = values[if (loop) index.floorMod(values.size) else index]
            val active = index == centerLoopIndex
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .clickable {
                        coroutineScope.launch {
                            val targetIndex = if (loop) index.nearestLoopIndex(centerLoopIndex, values.size) else index
                            listState.animateScrollToItem(targetIndex)
                            onSelect(item)
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label(item, active),
                    color = if (active) Ink else Color(0xFFD6D8DE),
                    fontSize = if (active) 26.sp else 25.sp,
                    fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                    lineHeight = 30.sp,
                    maxLines = 1,
                    style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun SnapWheelTextPicker(
    values: List<Int>,
    selected: Int,
    unit: String,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()
    val selectedIndex = values.indexOf(selected).coerceAtLeast(0)
    val loopCount = values.size * 1000
    val loopMiddle = (loopCount / 2 / values.size) * values.size
    val initialLoopIndex = loopMiddle + selectedIndex
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialLoopIndex)
    val flingBehavior = rememberSnapFlingBehavior(lazyListState = listState, snapPosition = SnapPosition.Center)
    var initialized by remember { mutableStateOf(false) }
    LaunchedEffect(selectedIndex) {
        if (!initialized) {
            listState.scrollToItem(initialLoopIndex)
            initialized = true
        }
    }
    val centerLoopIndex by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val viewportCenter = (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2
            layoutInfo.visibleItemsInfo
                .minByOrNull { kotlin.math.abs((it.offset + it.size / 2) - viewportCenter) }
                ?.index ?: initialLoopIndex
        }
    }
    val centerValueIndex = centerLoopIndex.floorMod(values.size)
    val selectedValue = values.getOrNull(centerValueIndex)
    LaunchedEffect(listState.isScrollInProgress, selectedValue) {
        if (!listState.isScrollInProgress && selectedValue != null && selectedValue != selected) {
            onSelect(selectedValue)
        }
    }
    Box(
        modifier = modifier.height(280.dp),
        contentAlignment = Alignment.Center
    ) {
        LazyColumn(
            state = listState,
            flingBehavior = flingBehavior,
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 112.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(loopCount) { index ->
                val item = values[index.floorMod(values.size)]
                val active = index == centerLoopIndex
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .clickable {
                            coroutineScope.launch {
                                listState.animateScrollToItem(index.nearestLoopIndex(centerLoopIndex, values.size))
                                onSelect(item)
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        item.toString(),
                        color = if (active) Ink else Color(0xFFD6D8DE),
                        fontSize = if (active) 26.sp else 25.sp,
                        fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                        lineHeight = 30.sp,
                        maxLines = 1,
                        style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
        Text(
            unit,
            color = Ink,
            fontSize = 26.sp,
            fontWeight = FontWeight.Medium,
            lineHeight = 30.sp,
            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)),
            modifier = Modifier.offset(x = 34.dp, y = (-1).dp)
        )
    }
}

@Composable
private fun SnapWheelPicker(
    values: List<Int>,
    selected: Int,
    unit: String,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()
    val selectedIndex = values.indexOf(selected).coerceAtLeast(0)
    val loopCount = values.size * 1000
    val loopMiddle = (loopCount / 2 / values.size) * values.size
    val initialLoopIndex = loopMiddle + selectedIndex
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialLoopIndex)
    val flingBehavior = rememberSnapFlingBehavior(lazyListState = listState, snapPosition = SnapPosition.Center)
    var initialized by remember { mutableStateOf(false) }
    LaunchedEffect(selectedIndex) {
        if (!initialized) {
            listState.scrollToItem(initialLoopIndex)
            initialized = true
        }
    }
    val centerLoopIndex by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val viewportCenter = (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2
            layoutInfo.visibleItemsInfo
                .minByOrNull { kotlin.math.abs((it.offset + it.size / 2) - viewportCenter) }
                ?.index ?: initialLoopIndex
        }
    }
    val centerValueIndex = centerLoopIndex.floorMod(values.size)
    val selectedValue = values.getOrNull(centerValueIndex)
    LaunchedEffect(listState.isScrollInProgress, selectedValue) {
        if (!listState.isScrollInProgress && selectedValue != null && selectedValue != selected) {
            onSelect(selectedValue)
        }
    }
    Box(
        modifier = modifier.height(168.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.width(170.dp)
        ) {
            LazyColumn(
                state = listState,
                flingBehavior = flingBehavior,
                horizontalAlignment = Alignment.End,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 56.dp),
                modifier = Modifier.width(82.dp).height(168.dp)
            ) {
                items(loopCount) { index ->
                    val item = values[index.floorMod(values.size)]
                    val active = index == centerLoopIndex
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .clickable {
                                coroutineScope.launch {
                                    listState.animateScrollToItem(index.nearestLoopIndex(centerLoopIndex, values.size))
                                    onSelect(item)
                                }
                            },
                        contentAlignment = Alignment.CenterEnd
                    ) {
                        Text(
                            item.toString().padStart(2, '0'),
                            color = if (active) Ink else Color(0xFFD8DADF),
                            fontSize = 30.sp,
                            fontWeight = FontWeight.Bold,
                            lineHeight = 30.sp,
                            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)),
                            textAlign = TextAlign.End
                        )
                    }
                }
            }
            Spacer(Modifier.width(20.dp))
            Box(modifier = Modifier.width(48.dp).height(56.dp), contentAlignment = Alignment.CenterStart) {
                Text(
                    unit,
                    color = Ink,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 30.sp,
                    style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)),
                    modifier = Modifier.offset(y = (-2).dp),
                    textAlign = TextAlign.Start
                )
            }
        }
    }
}

private fun Int.floorMod(size: Int): Int = ((this % size) + size) % size

private fun Int.nearestLoopIndex(current: Int, size: Int): Int {
    val target = this.floorMod(size)
    val currentBase = current - current.floorMod(size)
    val candidates = listOf(currentBase + target, currentBase + target - size, currentBase + target + size)
    return candidates.minBy { kotlin.math.abs(it - current) }
}

private enum class SchedulePickerMode {
    StartDateTime
}

private data class SchedulePickerState(val date: LocalDate, val start: LocalTime)

private val ScheduleDateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
private val ScheduleTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

private fun ScheduledMeeting?.toSchedulePickerState(): SchedulePickerState {
    val zone = ZoneId.systemDefault()
    if (this?.startAtMillis != null) {
        val startDateTime = Instant.ofEpochMilli(startAtMillis).atZone(zone).toLocalDateTime()
        return SchedulePickerState(startDateTime.toLocalDate(), startDateTime.toLocalTime())
    }
    val fallback = this?.time?.toPickerStateFromText()
    if (fallback != null) return fallback
    val now = java.util.Calendar.getInstance().apply {
        add(java.util.Calendar.MINUTE, 30)
        set(java.util.Calendar.MINUTE, ((get(java.util.Calendar.MINUTE) + 14) / 15) * 15)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }
    val start = now.toInstant().atZone(zone).toLocalDateTime()
    return SchedulePickerState(start.toLocalDate(), start.toLocalTime())
}

private fun String.toPickerStateFromText(): SchedulePickerState? {
    val dateMatch = Regex("""\d{4}-\d{1,2}-\d{1,2}""").find(this)?.value ?: return null
    val times = Regex("""(\d{1,2}):(\d{2})""").findAll(this).toList()
    if (times.isEmpty()) return null
    val date = runCatching { LocalDate.parse(dateMatch, DateTimeFormatter.ofPattern("yyyy-M-d")) }.getOrNull() ?: return null
    val start = times[0].toLocalTime()
    return SchedulePickerState(date, start)
}

private fun MatchResult.toLocalTime(): LocalTime {
    return LocalTime.of(groupValues[1].toInt().coerceIn(0, 23), groupValues[2].toInt().coerceIn(0, 59))
}

private fun LocalDate.scheduleWheelDateLabel(today: LocalDate): String {
    if (this == today) return "今天"
    val week = dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, Locale.CHINA)
    return "${monthValue}月${dayOfMonth.toString().padStart(2, '0')}日 $week"
}

@Composable
fun SpeakerSheet(
    speakers: List<SpeakerIdentity>,
    segmentSpeaker: SpeakerIdentity?,
    canSaveVoiceprint: Boolean,
    saving: Boolean,
    statusText: String?,
    onRename: (SpeakerIdentity, String, Boolean) -> Unit,
    onAssignSegment: (String, String?) -> Unit,
    onClose: () -> Unit
) {
    if (segmentSpeaker != null) {
        val assignmentOptions = remember(speakers, segmentSpeaker.id) {
            if (speakers.none { it.id == segmentSpeaker.id }) speakers + segmentSpeaker else speakers
        }
        var selectedId by remember(assignmentOptions, segmentSpeaker.id) { mutableStateOf(segmentSpeaker.id) }
        var creatingNew by remember(segmentSpeaker.id) { mutableStateOf(false) }
        var newSpeakerName by remember(segmentSpeaker.id) { mutableStateOf("") }
        val newSpeakerFocusRequester = remember { FocusRequester() }
        val selected = assignmentOptions.firstOrNull { it.id == selectedId }
        LaunchedEffect(creatingNew) {
            if (creatingNew) newSpeakerFocusRequester.requestFocus()
        }

        SheetContent("修改本段说话人") {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                assignmentOptions.forEach { speaker ->
                    SpeakerChoiceChip(
                        text = speaker.displayName,
                        selected = !creatingNew && speaker.id == selectedId,
                        enabled = !saving
                    ) {
                        creatingNew = false
                        selectedId = speaker.id
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            NewSpeakerEntry(
                creating = creatingNew,
                name = newSpeakerName,
                enabled = !saving,
                focusRequester = newSpeakerFocusRequester,
                onNameChange = { newSpeakerName = it.take(24) }
            ) {
                creatingNew = true
                selectedId = ""
            }
            Spacer(Modifier.height(10.dp))
            if (!statusText.isNullOrBlank()) {
                InlineBusyStatus(statusText)
                Spacer(Modifier.height(10.dp))
            }
            SheetPrimaryButton(
                text = if (saving) "保存中..." else "应用到本段",
                onClick = {
                    if (creatingNew) {
                        onAssignSegment(newSpeakerName, null)
                    } else {
                        selected?.let { onAssignSegment(it.displayName, it.id) }
                    }
                },
                enabled = !saving && if (creatingNew) newSpeakerName.trim().isNotBlank() else selected != null,
                icon = Icons.Filled.Check
            )
            TextButton(onClick = onClose, enabled = !saving, modifier = Modifier.fillMaxWidth()) { Text("取消") }
        }
        return
    }

    val initialSpeaker = speakers.firstOrNull()
    var selectedId by remember(speakers) { mutableStateOf(initialSpeaker?.id.orEmpty()) }
    val selected = speakers.firstOrNull { it.id == selectedId } ?: initialSpeaker
    var target by remember(selected?.id) { mutableStateOf(selected?.displayName.orEmpty()) }
    var saveVoiceprint by remember(selected?.id, canSaveVoiceprint) { mutableStateOf(false) }
    SheetContent("编辑说话人") {
        if (speakers.isEmpty()) {
            RowItem(Icons.Filled.Person, "还没有说话人")
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                speakers.forEach { speaker ->
                    SpeakerChoiceChip(
                        text = speaker.displayName,
                        selected = speaker.id == selected?.id,
                        enabled = !saving
                    ) {
                        selectedId = speaker.id
                        target = speaker.displayName
                    }
                }
            }
            SheetTextField(
                value = target,
                onValueChange = { target = it },
                label = "名称",
                singleLine = true,
                enabled = !saving,
                modifier = Modifier.fillMaxWidth()
            )
            if (canSaveVoiceprint) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(BrandSoftCyan)
                        .clickable(enabled = !saving) { saveVoiceprint = !saveVoiceprint }
                        .padding(12.dp)
                ) {
                    Checkbox(checked = saveVoiceprint, enabled = !saving, onCheckedChange = { saveVoiceprint = it })
                    Column(Modifier.weight(1f)) {
                        Text("我同意保存为声纹档案", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Text("使用当前会议中这个说话人的清晰语音样本，用于后续说话人识别；可在声纹库停用或删除。", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
                    }
                }
            }
            if (!statusText.isNullOrBlank()) {
                InlineBusyStatus(statusText)
            }
            SheetPrimaryButton(
                text = if (saving) "保存中..." else "保存",
                onClick = {
                    selected?.let { onRename(it, target, saveVoiceprint) }
                },
                enabled = target.trim().isNotBlank() && !saving,
                icon = Icons.Filled.Check
            )
        }
        TextButton(onClick = onClose, enabled = !saving, modifier = Modifier.fillMaxWidth()) { Text("取消") }
    }
}

@Composable
private fun NewSpeakerEntry(
    creating: Boolean,
    name: String,
    enabled: Boolean,
    focusRequester: FocusRequester,
    onNameChange: (String) -> Unit,
    onCreate: () -> Unit
) {
    if (creating) {
        OutlinedTextField(
            value = name,
            onValueChange = onNameChange,
            placeholder = { Text("新说话人名称") },
            leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null, tint = Brand) },
            singleLine = true,
            enabled = enabled,
            shape = RoundedCornerShape(18.dp),
            colors = TextFieldDefaults.colors(
                focusedTextColor = Ink,
                unfocusedTextColor = Ink,
                disabledTextColor = Muted,
                focusedContainerColor = Color(0xFFFAFCFF),
                unfocusedContainerColor = Color.White,
                disabledContainerColor = Color(0xFFF5F8FC),
                cursorColor = Brand,
                focusedIndicatorColor = Brand.copy(alpha = 0.72f),
                unfocusedIndicatorColor = Line,
                disabledIndicatorColor = Line.copy(alpha = 0.75f),
                focusedPlaceholderColor = Muted,
                unfocusedPlaceholderColor = Muted
            ),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
        )
        return
    }
    SheetSecondaryButton("新建说话人", onClick = onCreate, enabled = enabled, icon = Icons.Filled.Add)
}

@Composable
private fun SpeakerChoiceChip(text: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Surface(
        color = if (selected) Brand else Color.White,
        contentColor = if (selected) Color.White else Ink,
        shape = RoundedCornerShape(999.dp),
        border = BorderStroke(1.dp, if (selected) Brand else Line),
        modifier = Modifier
            .height(44.dp)
            .clip(RoundedCornerShape(999.dp))
            .clickable(enabled = enabled) { onClick() }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp)
        ) {
            Text(text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun TodoDetailSheet(
    todo: TodoItem?,
    saving: Boolean,
    onSave: (String, String, String, String, String, TodoStatus) -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit
) {
    if (todo == null) {
        SheetContent("待办详情") {
            RowItem(Icons.Filled.Article, "待办不存在")
            TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("关闭") }
        }
        return
    }
    var title by remember(todo.id) { mutableStateOf(todo.title) }
    var assignee by remember(todo.id) { mutableStateOf(todo.assigneeLabel.orEmpty()) }
    val initialDue = remember(todo.id) { todo.toTodoDuePickerState() }
    val existingDueLabel = remember(todo.id) { todo.dueLabel?.takeIf { it.isNotBlank() } }
    var dueDate by remember(todo.id) { mutableStateOf(initialDue.date) }
    var dueTime by remember(todo.id) { mutableStateOf(initialDue.time) }
    var dueCleared by remember(todo.id) { mutableStateOf(false) }
    var duePickerMode by remember { mutableStateOf<TodoDuePickerMode?>(null) }
    var priority by remember(todo.id) { mutableStateOf(todo.priority.toTodoPriorityValue()) }
    var description by remember(todo.id) { mutableStateOf(todo.description.cleanDisplayText().orEmpty()) }
    var status by remember(todo.id) { mutableStateOf(todo.effectiveStatus) }
    var localError by remember(todo.id) { mutableStateOf("") }
    var showDeleteConfirm by remember(todo.id) { mutableStateOf(false) }
    val dueAt = remember(dueDate, dueTime, dueCleared, existingDueLabel) {
        if (dueDate != null) {
            val dateLabel = dueDate!!.format(ScheduleDateFormatter)
            dueTime?.let { "$dateLabel ${it.format(ScheduleTimeFormatter)}" } ?: dateLabel
        } else if (!dueCleared) {
            existingDueLabel.orEmpty()
        } else {
            ""
        }
    }
    SheetContent("待办详情") {
        val meetingTitle = todo.meetingTitleLabel ?: "来源会议"
        val sourceText = listOfNotNull(todo.sourceTimestampLabel, todo.sourceLabel)
            .joinToString(" · ")
            .ifBlank { "来源待核验" }
        RowItem(Icons.Filled.Article, meetingTitle, sourceText)
        SheetTextField(value = title, onValueChange = { title = it }, enabled = !saving, label = "任务标题", singleLine = false, modifier = Modifier.fillMaxWidth())
        SheetTextField(value = assignee, onValueChange = { assignee = it }, enabled = !saving, label = "负责人", singleLine = true, modifier = Modifier.fillMaxWidth())
        TodoDueSelector(
            date = dueDate,
            time = dueTime,
            fallbackLabel = existingDueLabel.takeUnless { dueCleared },
            enabled = !saving,
            onPickDate = { duePickerMode = TodoDuePickerMode.DateTime },
            onClear = {
                dueDate = null
                dueTime = null
                dueCleared = true
            }
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("优先级", color = Muted, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                TodoPriorityChoice("普通", "medium", priority, enabled = !saving) { priority = it }
                TodoPriorityChoice("高", "high", priority, enabled = !saving) { priority = it }
                TodoPriorityChoice("低", "low", priority, enabled = !saving) { priority = it }
            }
        }
        SheetTextField(value = description, onValueChange = { description = it }, enabled = !saving, label = "描述", minLines = 2, modifier = Modifier.fillMaxWidth())
        if (localError.isNotBlank()) {
            Text(localError, color = Danger, fontSize = 13.sp)
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            TodoStatusChoice("待确认", TodoStatus.PendingConfirm, status, enabled = !saving) { status = it }
            TodoStatusChoice("待处理", TodoStatus.Todo, status, enabled = !saving) { status = it }
            TodoStatusChoice("进行中", TodoStatus.InProgress, status, enabled = !saving) { status = it }
            TodoStatusChoice("已完成", TodoStatus.Done, status, enabled = !saving) { status = it }
        }
        Spacer(Modifier.height(16.dp))
        SheetPrimaryButton(
            text = if (saving) "保存中..." else "保存",
            onClick = {
                localError = ""
                if (localError.isBlank()) onSave(title, assignee, dueAt, priority, description, status)
            },
            enabled = !saving,
            icon = Icons.Filled.Save,
            height = 46.dp,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
        Spacer(Modifier.height(8.dp))
        SheetDangerButton(
            text = if (saving) "处理中..." else "删除待办",
            onClick = { showDeleteConfirm = true },
            enabled = !saving,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
    }
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { if (!saving) showDeleteConfirm = false },
            title = { Text("删除待办") },
            text = {
                Text(
                    "删除后这条待办会从当前会议和待办列表中隐藏，并同步到云端。",
                    color = Ink,
                    fontSize = 14.sp,
                    lineHeight = 22.sp
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        onDelete()
                    },
                    enabled = !saving
                ) {
                    Text("确认删除", color = Danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }, enabled = !saving) {
                    Text("取消")
                }
            }
        )
    }
    TodoDuePickerDialogHost(
        mode = duePickerMode,
        currentDate = dueDate,
        currentTime = dueTime,
        onDismiss = { duePickerMode = null },
        onConfirm = { date, time ->
            dueDate = date
            dueTime = time
            dueCleared = false
            duePickerMode = null
        }
    )
}

@Composable
fun TodoCreateSheet(
    meetingTitle: String,
    saving: Boolean,
    onSave: (String, String, String, String, String, TodoStatus) -> Unit,
    onClose: () -> Unit
) {
    var title by remember { mutableStateOf("") }
    var assignee by remember { mutableStateOf("") }
    var dueDate by remember { mutableStateOf<LocalDate?>(null) }
    var dueTime by remember { mutableStateOf<LocalTime?>(null) }
    var duePickerMode by remember { mutableStateOf<TodoDuePickerMode?>(null) }
    var priority by remember { mutableStateOf("medium") }
    var description by remember { mutableStateOf("") }
    var status by remember { mutableStateOf(TodoStatus.Todo) }
    var localError by remember { mutableStateOf("") }
    val dueAt = remember(dueDate, dueTime) {
        dueDate?.let { date ->
            val dateLabel = date.format(ScheduleDateFormatter)
            dueTime?.let { "$dateLabel ${it.format(ScheduleTimeFormatter)}" } ?: dateLabel
        }.orEmpty()
    }
    SheetContent("补充待办") {
        RowItem(Icons.Filled.Article, meetingTitle.ifBlank { "当前会议" }, "手动补充到会议待办")
        SheetTextField(value = title, onValueChange = { title = it }, enabled = !saving, label = "任务标题", singleLine = false, modifier = Modifier.fillMaxWidth())
        SheetTextField(value = assignee, onValueChange = { assignee = it }, enabled = !saving, label = "负责人", singleLine = true, modifier = Modifier.fillMaxWidth())
        TodoDueSelector(
            date = dueDate,
            time = dueTime,
            fallbackLabel = null,
            enabled = !saving,
            onPickDate = { duePickerMode = TodoDuePickerMode.DateTime },
            onClear = {
                dueDate = null
                dueTime = null
            }
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("优先级", color = Muted, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                TodoPriorityChoice("普通", "medium", priority, enabled = !saving) { priority = it }
                TodoPriorityChoice("高", "high", priority, enabled = !saving) { priority = it }
                TodoPriorityChoice("低", "low", priority, enabled = !saving) { priority = it }
            }
        }
        SheetTextField(value = description, onValueChange = { description = it }, enabled = !saving, label = "描述", minLines = 2, modifier = Modifier.fillMaxWidth())
        if (localError.isNotBlank()) {
            Text(localError, color = Danger, fontSize = 13.sp)
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            TodoStatusChoice("待确认", TodoStatus.PendingConfirm, status, enabled = !saving) { status = it }
            TodoStatusChoice("待处理", TodoStatus.Todo, status, enabled = !saving) { status = it }
            TodoStatusChoice("进行中", TodoStatus.InProgress, status, enabled = !saving) { status = it }
            TodoStatusChoice("已完成", TodoStatus.Done, status, enabled = !saving) { status = it }
        }
        Spacer(Modifier.height(16.dp))
        SheetPrimaryButton(
            text = if (saving) "保存中..." else "保存",
            onClick = {
                localError = ""
                val cleanTitle = title.trim()
                if (cleanTitle.isBlank()) {
                    localError = "任务标题不能为空"
                } else if (cleanTitle.length > 100) {
                    localError = "任务标题不能超过 100 个字符"
                }
                if (localError.isBlank()) onSave(title, assignee, dueAt, priority, description, status)
            },
            enabled = !saving,
            icon = Icons.Filled.Save,
            height = 46.dp,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
    }
    TodoDuePickerDialogHost(
        mode = duePickerMode,
        currentDate = dueDate,
        currentTime = dueTime,
        onDismiss = { duePickerMode = null },
        onConfirm = { date, time ->
            dueDate = date
            dueTime = time
            duePickerMode = null
        }
    )
}

@Composable
private fun TodoStatusChoice(text: String, value: TodoStatus, selected: TodoStatus, enabled: Boolean = true, onSelect: (TodoStatus) -> Unit) {
    val active = value == selected
    Button(
        onClick = { onSelect(value) },
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = if (active) Brand else BrandSoft.copy(alpha = 0.72f), contentColor = if (active) Color.White else Ink),
        modifier = Modifier.height(40.dp),
        shape = RoundedCornerShape(999.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 0.dp)
    ) {
        Text(text, fontSize = 13.sp)
    }
}

@Composable
private fun TodoDueSelector(
    date: LocalDate?,
    time: LocalTime?,
    fallbackLabel: String?,
    enabled: Boolean,
    onPickDate: () -> Unit,
    onClear: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("截止时间", color = Muted, fontSize = 13.sp)
        Surface(
            color = Color(0xFFFAFCFF),
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, Line),
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled) { onPickDate() }
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier.size(40.dp).clip(CircleShape).background(BrandSoftCyan),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.CalendarMonth, contentDescription = null, tint = Brand, modifier = Modifier.size(18.dp))
                }
                Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(date?.format(ScheduleDateFormatter) ?: fallbackLabel ?: "未设置截止日期", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text(time?.format(ScheduleTimeFormatter) ?: if (date != null || fallbackLabel != null) "点击可补充具体时间" else "点击选择截止日期", color = Muted, fontSize = 13.sp)
                }
                if (date != null || time != null || fallbackLabel != null) {
                    IconButton(onClick = onClear, enabled = enabled, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Filled.DeleteOutline, contentDescription = "清除截止时间", tint = Muted)
                    }
                } else {
                    Text("选择", color = if (enabled) Brand else Muted, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun TodoDuePickerDialogHost(
    mode: TodoDuePickerMode?,
    currentDate: LocalDate?,
    currentTime: LocalTime?,
    onDismiss: () -> Unit,
    onConfirm: (LocalDate, LocalTime) -> Unit
) {
    when (mode) {
        TodoDuePickerMode.DateTime -> TodoDueDateTimePickerSheet(
            currentDate = currentDate,
            currentTime = currentTime,
            onDismiss = onDismiss,
            onConfirm = onConfirm
        )
        null -> Unit
    }
}

@Composable
private fun TodoDueDateTimePickerSheet(
    currentDate: LocalDate?,
    currentTime: LocalTime?,
    onDismiss: () -> Unit,
    onConfirm: (LocalDate, LocalTime) -> Unit
) {
    val today = LocalDate.now()
    val selectedInitialDate = currentDate?.takeUnless { it.isBefore(today) } ?: today
    val initialTime = currentTime ?: LocalTime.of(18, 0)
    val firstDate = today
    val lastDate = maxOf(today.plusDays(180), selectedInitialDate.plusDays(30))
    val dates = remember(currentDate) {
        val count = java.time.temporal.ChronoUnit.DAYS.between(firstDate, lastDate).toInt().coerceAtLeast(0) + 1
        List(count) { firstDate.plusDays(it.toLong()) }
    }
    var selectedDate by remember(currentDate) { mutableStateOf(selectedInitialDate) }
    var selectedHour by remember(currentTime) { mutableStateOf(initialTime.hour) }
    var selectedMinute by remember(currentTime) { mutableStateOf(initialTime.minute) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Surface(
                color = Color.White,
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(bottom = 34.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(80.dp)
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onDismiss, modifier = Modifier.size(56.dp)) {
                            Icon(Icons.Filled.Close, contentDescription = "取消", tint = Muted, modifier = Modifier.size(34.dp))
                        }
                        Text(
                            "截止时间",
                            color = Ink,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = {
                                onConfirm(selectedDate, LocalTime.of(selectedHour, selectedMinute))
                            },
                            modifier = Modifier.size(56.dp)
                        ) {
                            Icon(Icons.Filled.Check, contentDescription = "确定", tint = Brand, modifier = Modifier.size(36.dp))
                        }
                    }
                    Surface(color = Line, modifier = Modifier.fillMaxWidth().height(1.dp)) {}
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(280.dp)
                            .padding(horizontal = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            color = BrandSoftCyan,
                            shape = RoundedCornerShape(18.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                        ) {}
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            SnapWheelTextPicker(
                                values = dates,
                                selected = selectedDate,
                                label = { value, _ -> value.scheduleWheelDateLabel(today) },
                                onSelect = { selectedDate = it },
                                loop = false,
                                modifier = Modifier.weight(1.45f)
                            )
                            SnapWheelTextPicker(
                                values = (0..23).toList(),
                                selected = selectedHour,
                                unit = "时",
                                onSelect = { selectedHour = it },
                                modifier = Modifier.weight(0.82f)
                            )
                            SnapWheelTextPicker(
                                values = (0..59).toList(),
                                selected = selectedMinute,
                                unit = "分",
                                onSelect = { selectedMinute = it },
                                modifier = Modifier.weight(0.82f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TodoPriorityChoice(text: String, value: String, selected: String, enabled: Boolean = true, onSelect: (String) -> Unit) {
    val active = value == selected
    Button(
        onClick = { onSelect(value) },
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = if (active) Brand else BrandSoft.copy(alpha = 0.72f), contentColor = if (active) Color.White else Ink),
        modifier = Modifier.height(40.dp),
        shape = RoundedCornerShape(999.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 0.dp)
    ) {
        Text(text, fontSize = 13.sp)
    }
}

private enum class TodoDuePickerMode {
    DateTime
}

private data class TodoDuePickerState(val date: LocalDate?, val time: LocalTime?)

private fun TodoItem.toTodoDuePickerState(): TodoDuePickerState {
    val zone = ZoneId.systemDefault()
    dueAtMillis?.let {
        val dateTime = Instant.ofEpochMilli(it).atZone(zone).toLocalDateTime()
        return TodoDuePickerState(dateTime.toLocalDate(), dateTime.toLocalTime())
    }
    val label = dueAtLabel.cleanDisplayText() ?: return TodoDuePickerState(null, null)
    val date = runCatching {
        LocalDate.parse(label.take(10), ScheduleDateFormatter)
    }.getOrNull()
    val time = Regex("""(\d{1,2}):(\d{2})""").find(label)?.toLocalTime()
    return TodoDuePickerState(date, time)
}

private fun String.toTodoPriorityValue(): String {
    return normalizedTodoPriority()
}

private fun String?.cleanDisplayText(): String? {
    val clean = this?.trim().orEmpty()
    return clean.takeUnless { it.isBlank() || it.equals("null", ignoreCase = true) }
}

@Composable
fun SourceSheet(
    meeting: Meeting,
    segmentIndex: Int,
    playbackState: AudioSegmentPlaybackState,
    onPlaySegment: () -> Unit,
    onCorrection: () -> Unit,
    onClose: () -> Unit
) {
    SheetContent("来源核验") {
        val segment = meeting.transcripts.getOrNull(segmentIndex.coerceIn(0, (meeting.transcripts.size - 1).coerceAtLeast(0)))
        val sourcePath = meeting.sourceFilePath
        val activeKey = if (segment != null && sourcePath != null) {
            AudioSegmentPlayer.segmentKey(sourcePath, segment.startMs, segment.endMs)
        } else {
            null
        }
        val isActiveSegment = activeKey != null && playbackState.activeKey == activeKey
        val isPlaying = isActiveSegment && playbackState.isPlaying
        val progress = if (isActiveSegment) playbackState.progress else 0f
        val currentLabel = if (isActiveSegment) playbackState.segmentCurrentMs.toClockLabel() else "00:00"
        val durationLabel = if (isActiveSegment && playbackState.segmentDurationMs > 0L) {
            playbackState.segmentDurationMs.toClockLabel()
        } else {
            ((segment?.endMs ?: 0L) - (segment?.startMs ?: 0L)).coerceAtLeast(0L).toClockLabel()
        }
        Surface(color = Color.White, shape = RoundedCornerShape(22.dp), border = BorderStroke(1.dp, Line), shadowElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = Color.Transparent,
                    contentColor = Color.White,
                    shape = CircleShape,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Brush.horizontalGradient(listOf(Brand, BrandCyan)))
                        .clickable(enabled = segment != null && meeting.sourceFilePath != null) { onPlaySegment() }
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(
                            if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (isPlaying) "暂停" else "播放"
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(6.dp)).background(BrandSoftCyan)) {
                        Box(
                            Modifier
                                .fillMaxWidth(progress)
                                .height(6.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(Brush.horizontalGradient(listOf(Brand, BrandCyan)))
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("$currentLabel / $durationLabel", color = Muted, fontSize = 12.sp)
                }
                Text(segment?.timestamp ?: "00:00", color = Muted, fontSize = 12.sp)
            }
        }
        if (sourcePath == null) {
            RowItem(Icons.Filled.Article, "暂无可播放音频")
        }
        if (segment != null) {
            TranscriptLine(segment.speaker, segment.text, segment.timestamp, segment.timeRangeLabel)
        } else {
            RowItem(Icons.Filled.Article, "没有来源文本")
        }
        TextButton(onClick = onCorrection, enabled = segment != null, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Edit, contentDescription = null)
            Text("修正这段原文")
        }
        SheetPrimaryButton("确认", onClick = onClose)
    }
}

private fun Long.toClockLabel(): String {
    val totalSeconds = (this / 1000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "%02d:%02d".format(minutes, seconds)
}

@Composable
fun ExportSheet(
    busy: Boolean,
    statusText: String?,
    onClose: () -> Unit,
    onExportText: (Boolean) -> Unit
) {
    var includeTranscript by remember { mutableStateOf(false) }
    SheetContent("导出会议纪要") {
        Text(
            "保存为 TXT 文件，可选择手机里的保存位置",
            color = Muted,
            fontSize = 13.sp,
            lineHeight = 19.sp
        )
        Spacer(Modifier.height(8.dp))
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp)),
            shape = RoundedCornerShape(22.dp),
            color = Color.White,
            border = BorderStroke(1.dp, Line)
        ) {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    "导出内容",
                    color = Muted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 14.dp, top = 12.dp, end = 14.dp, bottom = 2.dp)
                )
                ExportScopeOption(
                    title = "仅会议纪要",
                    subtitle = "摘要、议题、决策、待办和风险",
                    selected = !includeTranscript,
                    enabled = !busy
                ) { includeTranscript = false }
                Box(Modifier.fillMaxWidth().height(1.dp).background(Line.copy(alpha = 0.65f)))
                ExportScopeOption(
                    title = "纪要和原文",
                    subtitle = "在纪要后附上完整转写文本",
                    selected = includeTranscript,
                    enabled = !busy
                ) { includeTranscript = true }
            }
        }
        if (!statusText.isNullOrBlank()) {
            InlineBusyStatus(statusText)
        }
        Spacer(Modifier.height(4.dp))
        SheetPrimaryButton(
            text = if (busy) "准备中..." else "选择位置并导出",
            onClick = { onExportText(includeTranscript) },
            enabled = !busy,
            icon = Icons.Filled.FileDownload
        )
        TextButton(onClick = onClose, enabled = !busy, modifier = Modifier.fillMaxWidth().height(40.dp)) { Text("取消", color = Muted, fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun ExportScopeOption(
    title: String,
    subtitle: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        color = if (selected) BrandSoftCyan else Color.White,
        contentColor = Ink
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text(subtitle, color = Muted, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 2.dp))
            }
            Surface(
                modifier = Modifier
                    .size(22.dp),
                shape = CircleShape,
                color = if (selected) Brand else Color.White,
                border = if (selected) null else BorderStroke(1.dp, Line)
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    if (selected) {
                        Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun EditMinutesSheet(initialSummary: String, saving: Boolean, onSave: (String) -> Unit, onClose: () -> Unit) {
    var content by remember(initialSummary) { mutableStateOf(initialSummary) }
    SheetContent("编辑会议纪要") {
        SheetTextField(value = content, onValueChange = { content = it }, enabled = !saving, minLines = 5, modifier = Modifier.fillMaxWidth())
        if (saving) {
            InlineBusyStatus("正在保存会议纪要...")
        }
        SheetPrimaryButton(if (saving) "保存中..." else "保存修改", onClick = { onSave(content) }, enabled = !saving, icon = Icons.Filled.Save)
        TextButton(onClick = onClose, enabled = !saving, modifier = Modifier.fillMaxWidth()) { Text("取消") }
    }
}

@Composable
fun EditMeetingInfoSheet(
    initialTitle: String,
    saving: Boolean,
    onSave: (String) -> Unit,
    onClose: () -> Unit
) {
    var title by remember(initialTitle) { mutableStateOf(initialTitle) }
    SheetContent("编辑会议名称") {
        SheetTextField(value = title, onValueChange = { title = it }, enabled = !saving, label = "会议名称", singleLine = true, modifier = Modifier.fillMaxWidth())
        if (saving) {
            InlineBusyStatus("正在保存会议名称...")
        }
        SheetPrimaryButton(if (saving) "保存中..." else "保存", onClick = { onSave(title) }, enabled = title.trim().isNotBlank() && !saving, icon = Icons.Filled.Save)
        TextButton(onClick = onClose, enabled = !saving, modifier = Modifier.fillMaxWidth()) { Text("取消") }
    }
}

@Composable
fun CorrectionSheet(originalText: String, timeRange: String, saving: Boolean, onSave: (String) -> Unit, onClose: () -> Unit) {
    var content by remember(originalText) { mutableStateOf(originalText) }
    SheetContent("修正转写原文") {
        SheetTextField(value = content, onValueChange = { content = it }, enabled = !saving, minLines = 4, modifier = Modifier.fillMaxWidth())
        RowItem(Icons.Filled.Article, "来源片段", timeRange)
        if (saving) {
            InlineBusyStatus("正在保存修正...")
        }
        SheetPrimaryButton(if (saving) "保存中..." else "保存修正", onClick = { onSave(content) }, enabled = !saving, icon = Icons.Filled.Check)
        TextButton(onClick = onClose, enabled = !saving, modifier = Modifier.fillMaxWidth()) { Text("取消") }
    }
}

@Composable
fun DeleteDataSheet(saving: Boolean, onClose: () -> Unit, onConfirm: () -> Unit) {
    SheetContent("删除会议数据") {
        Text("将删除本地录音、转写、纪要、待办和知识库索引；已同步的数据会同时清理云端记录。", color = Muted, fontSize = 14.sp, lineHeight = 22.sp)
        if (saving) {
            InlineBusyStatus("正在删除会议数据...")
        }
        SheetDangerButton(if (saving) "删除中..." else "确认删除", onClick = onConfirm, enabled = !saving)
        TextButton(onClick = onClose, enabled = !saving, modifier = Modifier.fillMaxWidth()) { Text("取消") }
    }
}

@Composable
fun DeleteMeetingSheet(meetingTitle: String, saving: Boolean, onClose: () -> Unit, onConfirm: () -> Unit) {
    SheetContent("删除会议") {
        Text("删除后该会议的录音、转写、纪要、待办和知识库索引将不可查看；若已同步，将一并删除云端记录。", color = Muted, fontSize = 14.sp, lineHeight = 22.sp)
        RowItem(Icons.Filled.Article, meetingTitle)
        if (saving) {
            InlineBusyStatus("正在删除会议...")
        }
        SheetDangerButton(if (saving) "删除中..." else "确认删除", onClick = onConfirm, enabled = !saving)
        TextButton(onClick = onClose, enabled = !saving, modifier = Modifier.fillMaxWidth()) { Text("取消") }
    }
}

@Composable
fun DeleteScheduleSheet(meetingTitle: String, saving: Boolean, onClose: () -> Unit, onConfirm: () -> Unit) {
    SheetContent("删除预约会议") {
        Text("删除后该预约会从 App 内列表移除；若已同步云端，也会同步删除云端预约。已结束会议不支持删除。", color = Muted, fontSize = 14.sp, lineHeight = 22.sp)
        RowItem(Icons.Filled.CalendarMonth, meetingTitle)
        if (saving) {
            InlineBusyStatus("正在删除预约...")
        }
        SheetDangerButton(if (saving) "删除中..." else "确认删除", onClick = onConfirm, enabled = !saving)
        TextButton(onClick = onClose, enabled = !saving, modifier = Modifier.fillMaxWidth()) { Text("取消") }
    }
}

@Composable
fun DeleteTaskSheet(taskTitle: String, saving: Boolean, onClose: () -> Unit, onConfirm: () -> Unit) {
    SheetContent("删除任务") {
        Text("删除后该任务的文件、转写结果和知识库索引将不可查看；若已上传，将一并删除云端记录。", color = Muted, fontSize = 14.sp, lineHeight = 22.sp)
        RowItem(Icons.Filled.UploadFile, taskTitle)
        if (saving) {
            InlineBusyStatus("正在删除任务...")
        }
        SheetDangerButton(if (saving) "删除中..." else "确认删除", onClick = onConfirm, enabled = !saving)
        TextButton(onClick = onClose, enabled = !saving, modifier = Modifier.fillMaxWidth()) { Text("取消") }
    }
}

@Composable
fun LogoutConfirmSheet(localUnsyncedTaskCount: Int, pendingDeleteCount: Int, saving: Boolean, onClose: () -> Unit, onConfirm: () -> Unit) {
    SheetContent("退出账号") {
        Text("退出登录会清除本机会议、待办、预约和知识库缓存，只影响当前设备，不会主动删除云端数据。", color = Muted, fontSize = 14.sp, lineHeight = 22.sp)
        if (localUnsyncedTaskCount > 0) {
            Text("有 $localUnsyncedTaskCount 个本机会议或文件任务尚未上传到云端，确认退出后这些本机数据会被删除。", color = Danger, fontSize = 14.sp, lineHeight = 22.sp)
        }
        if (pendingDeleteCount > 0) {
            Text("还有 $pendingDeleteCount 条本机删除操作尚未同步，退出后这些同步任务会停止。", color = Muted, fontSize = 14.sp, lineHeight = 22.sp)
        }
        RowItem(Icons.Filled.UploadFile, "未上传本机会议/文件任务", "${localUnsyncedTaskCount} 个")
        RowItem(Icons.Filled.DeleteOutline, "待同步删除", "${pendingDeleteCount} 条")
        if (saving) {
            InlineBusyStatus("正在退出账号...")
        }
        SheetDangerButton(if (saving) "退出中..." else "退出并删除本机数据", onClick = onConfirm, enabled = !saving)
        TextButton(onClick = onClose, enabled = !saving, modifier = Modifier.fillMaxWidth()) { Text("先不退出") }
    }
}

@Composable
private fun InlineBusyStatus(text: String) {
    Surface(
        color = Color(0xFFFFF5DB),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text,
            color = Color(0xFFC58B00),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(12.dp)
        )
    }
}

@Composable
fun UserAgreementSheet(onClose: () -> Unit) {
    LegalDocumentSheet(
        title = "用户协议",
        updatedAt = "更新/生效日期：2026年6月8日",
        sections = listOf(
            LegalSection(
                "一、服务说明",
                "鲲穹会纪是面向会议场景的记录与整理工具，提供会议录音、实时转写、文件导入、AI 纪要、待办整理、来源核验、知识库问答、本机保存和云端同步等能力。具体功能以当前版本实际提供为准。"
            ),
            LegalSection(
                "二、账号与登录",
                "你可以使用手机号和验证码登录。请确保提交的手机号为本人合法使用，并妥善保管验证码。因你主动泄露验证码、设备被他人使用等原因造成的损失，由你自行承担。"
            ),
            LegalSection(
                "三、会议录音与内容责任",
                "使用录音、转写、导入音频或保存声纹前，你应确认已取得会议参与方或相关权利人的必要同意，并遵守适用法律法规。你不得上传、录制、传播违法违规、侵权或未经授权的内容。"
            ),
            LegalSection(
                "四、AI 生成内容",
                "鲲穹会纪会基于你提供的音频、转写文本和会议资料生成摘要、议题、待办、风险和问答结果。AI 生成内容可能存在遗漏或理解偏差，仅作为辅助信息，重要事项请结合来源核验后再使用。"
            ),
            LegalSection(
                "五、本机数据与云端同步",
                "未开启或未完成云端同步时，会议文件、纪要和待办主要保存在当前设备。你开启云端同步或拉取云端数据后，相关会议资料会在本机与服务器之间传输和保存，用于跨设备查看、同步和恢复。"
            ),
            LegalSection(
                "六、用户行为规范",
                "你不得利用本应用从事危害网络安全、侵犯他人隐私、侵犯知识产权、骚扰他人、虚构事实或其他违法违规行为；不得绕过、破坏或干扰应用、服务器、模型服务和安全机制。"
            ),
            LegalSection(
                "七、服务变更与中止",
                "为保障服务稳定、安全和合规，我们可能对功能、模型、接口、存储策略或服务规则进行调整。因维护、网络、第三方服务、不可抗力等原因导致服务暂时不可用时，我们会尽力恢复。"
            ),
            LegalSection(
                "八、隐私保护",
                "我们会按照《隐私政策》处理你的个人信息和会议资料。请在使用本应用前仔细阅读并确认同意《隐私政策》。"
            ),
            LegalSection(
                "九、协议更新与联系",
                "本协议可能根据产品功能、法律法规或运营需要更新。运营主体、客服联系方式、投诉反馈渠道和争议处理方式以应用内、官网或应用发布渠道公示的信息为准。"
            )
        ),
        onClose = onClose
    )
}

@Composable
fun PrivacyPolicySheet(onClose: () -> Unit) {
    LegalDocumentSheet(
        title = "隐私政策",
        updatedAt = "更新/生效日期：2026年6月8日",
        sections = listOf(
            LegalSection(
                "一、我们如何收集信息",
                "为提供服务，我们会根据你的使用行为处理必要信息，包括手机号、验证码校验状态、登录状态、设备基础信息、录音或导入的音视频文件、实时转写文本、会议纪要、待办、风险、知识库索引、编辑记录、同步状态和必要运行日志。"
            ),
            LegalSection(
                "二、敏感信息说明",
                "会议录音、转写内容、声纹样本、会议参与人、客户或项目信息可能包含个人敏感信息或商业敏感内容。处理这些信息是实现录音转写、说话人识别、纪要生成、来源核验和知识库问答所必需；声纹档案仅在你主动勾选同意并保存说话人声纹或在声纹库录入时处理。如你不同意，请不要使用对应功能。"
            ),
            LegalSection(
                "三、信息使用目的",
                "我们使用相关信息用于账号登录、会议记录、语音转写、说话人识别、AI 纪要生成、待办整理、来源播放、知识库检索、云端同步、故障排查和服务安全保障，不会用于与本应用功能无关的用途。"
            ),
            LegalSection(
                "四、权限使用",
                "麦克风权限用于实时录音和转写；文件选择权限用于导入音视频文件；通知权限用于预约会议提醒和后台录音状态展示。你可以在系统设置中关闭相关权限，但部分功能可能无法使用。"
            ),
            LegalSection(
                "五、本机保存与云端处理",
                "鲲穹会纪采用本机优先的保存方式。登录、上传、云端同步、拉取云端数据、音频转写或声纹保存时，相关数据会传输至鲲穹会纪服务端；实时转写会在登录状态下向服务端申请临时语音识别凭证，并按必要范围将录音音频流传输至第三方语音识别服务。需要 AI 纪要、问答或向量检索时，会由服务端按必要范围转交第三方模型服务处理。未同步的数据仅保存在当前设备。"
            ),
            LegalSection(
                "六、第三方服务",
                "为实现功能，我们会按必要范围接入第三方服务：腾讯云短信服务用于发送验证码，处理手机号、验证码场景和发送状态；阿里云 DashScope 模型服务用于实时语音识别、AI 纪要、问答和向量检索，处理录音音频流、转写文本、会议摘要请求和检索文本。第三方服务只接收完成对应功能所需的数据，具体服务提供方、处理规则和隐私政策链接以应用内、官网或应用发布渠道公示的信息为准。"
            ),
            LegalSection(
                "七、保存、删除与导出",
                "本机会议数据保存至你删除应用数据、删除会议或卸载应用；云端会议资料、知识库索引和声纹档案保存至你删除云端数据、清理同步数据或服务依法停止；手机号和账号状态在账号存续期间保存；验证码仅用于本次登录校验并短期有效；设备基础信息、发送记录和运行日志仅在安全风控、排障和审计所需期限内保存，原则上不超过 6 个月，法律法规另有要求的除外；备份数据会随备份周期覆盖或清理。你可以在应用内删除会议数据、退出登录、关闭云端同步或导出 TXT 文件。"
            ),
            LegalSection(
                "八、你的权利",
                "你可以在应用内修改昵称、删除会议数据、导出会议 TXT、关闭云端同步、退出登录，并在声纹库停用或删除声纹档案。查询、更正、删除、导出个人信息副本、撤回同意或注销账号等其他依法享有的权利，需要通过公示联系方式提交可验证请求；我们会尽快处理，并原则上在 15 个工作日内反馈。撤回同意或关闭权限后，不影响此前基于同意已完成的处理，但可能导致部分功能不可用。"
            ),
            LegalSection(
                "九、安全措施",
                "我们会采取访问控制、权限隔离、传输保护、日志审计和最小必要处理等措施保护数据安全。由于网络环境和第三方服务存在不确定性，请避免在未授权会议中录制或上传高度敏感内容。"
            ),
            LegalSection(
                "十、未成年人保护",
                "本应用主要面向办公会议场景。未成年人使用本应用前，应取得监护人同意，并避免录制、上传或处理与学习生活无关的敏感信息。"
            ),
            LegalSection(
                "十一、政策更新与联系",
                "本政策会根据产品功能、法律法规和服务配置变化更新。运营主体、客服联系方式、投诉反馈和个人信息权利行使渠道以应用内、官网或应用发布渠道公示的信息为准。"
            )
        ),
        onClose = onClose
    )
}

private data class LegalSection(
    val title: String,
    val body: String
)

@Composable
private fun LegalDocumentSheet(
    title: String,
    updatedAt: String,
    sections: List<LegalSection>,
    onClose: () -> Unit
) {
    val maxContentHeight = (LocalConfiguration.current.screenHeightDp * 0.68f).dp
    SheetContent(title) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = maxContentHeight),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Text(updatedAt, color = Muted, fontSize = 13.sp, lineHeight = 20.sp)
            }
            sections.forEach { section ->
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(section.title, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Text(section.body, color = Muted, fontSize = 13.sp, lineHeight = 21.sp)
                    }
                }
            }
            item {
                SheetPrimaryButton("知道了", onClick = onClose)
            }
        }
    }
}
