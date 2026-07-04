package com.huiyi.app.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.GppGood
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.huiyi.app.R
import com.huiyi.app.components.ActionButton
import com.huiyi.app.components.Bullet
import com.huiyi.app.components.InfoBlock
import com.huiyi.app.components.Pill
import com.huiyi.app.components.RowItem
import com.huiyi.app.components.ScreenScaffold
import com.huiyi.app.components.SectionTitle
import com.huiyi.app.components.SourceButton
import com.huiyi.app.components.SmartGradientIcon
import com.huiyi.app.components.SmartGradientPanel
import com.huiyi.app.components.SmartHeaderTexture
import com.huiyi.app.components.SmartPageBackground
import com.huiyi.app.components.SmartPrimaryBrush
import com.huiyi.app.components.SmartRecordBrush
import com.huiyi.app.components.SmartRoundIconButton
import com.huiyi.app.components.SmartSearchPill
import com.huiyi.app.components.SmartSectionHeader
import com.huiyi.app.components.SmartStatusDot
import com.huiyi.app.components.SmartWhiteCard
import com.huiyi.app.components.TaskRow
import com.huiyi.app.components.TranscriptLine
import com.huiyi.app.components.WaveBars
import com.huiyi.app.data.HomeDashboard
import com.huiyi.app.data.KnowledgeTopic
import com.huiyi.app.data.Meeting
import com.huiyi.app.data.MeetingTask
import com.huiyi.app.data.MeetingTaskStatus
import com.huiyi.app.data.MembershipAddon
import com.huiyi.app.data.MembershipPlan
import com.huiyi.app.data.MembershipProfile
import com.huiyi.app.data.PaymentOrder
import com.huiyi.app.data.CloudUser
import com.huiyi.app.data.RemoteKnowledgeSource
import com.huiyi.app.data.ScheduledMeeting
import com.huiyi.app.data.SpeakerProfile
import com.huiyi.app.permissions.AppPermissionStatus
import com.huiyi.app.data.TodoItem
import com.huiyi.app.data.TodoPriorityHigh
import com.huiyi.app.data.TodoPriorityLow
import com.huiyi.app.data.TodoStatus
import com.huiyi.app.data.TranscriptSegment
import com.huiyi.app.data.normalizedTodoPriority
import com.huiyi.app.data.todoPriorityLabel
import com.huiyi.app.data.todoPriorityWeight
import com.huiyi.app.model.statusColor
import com.huiyi.app.recording.RecordingStatus
import com.huiyi.app.recording.RecordingUiState
import com.huiyi.app.state.KnowledgeChatMessage
import com.huiyi.app.state.KnowledgeChatRole
import com.huiyi.app.state.TodoFilter
import com.huiyi.app.ui.AppBg
import com.huiyi.app.ui.Brand
import com.huiyi.app.ui.BrandCyan
import com.huiyi.app.ui.BrandDark
import com.huiyi.app.ui.BrandPurple
import com.huiyi.app.ui.BrandSoft
import com.huiyi.app.ui.BrandSoftCyan
import com.huiyi.app.ui.Danger
import com.huiyi.app.ui.HeaderBlue
import com.huiyi.app.ui.HeaderCyan
import com.huiyi.app.ui.Ink
import com.huiyi.app.ui.Line
import com.huiyi.app.ui.Muted
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private data class PhoneLayoutSpec(
    val horizontalPadding: Dp,
    val listSpacing: Dp,
    val cardPadding: Dp,
    val compactWidth: Boolean,
    val extraCompactWidth: Boolean,
    val shortHeight: Boolean,
    val recordHeroHeight: Dp,
    val actionTileHeight: Dp,
    val scheduleActionWidth: Dp
)

@Composable
private fun rememberPhoneLayoutSpec(): PhoneLayoutSpec {
    val configuration = LocalConfiguration.current
    val compactWidth = configuration.screenWidthDp < 400
    val extraCompactWidth = configuration.screenWidthDp < 360
    val shortHeight = configuration.screenHeightDp < 720
    return PhoneLayoutSpec(
        horizontalPadding = when {
            extraCompactWidth -> 14.dp
            compactWidth -> 16.dp
            else -> 20.dp
        },
        listSpacing = if (compactWidth || shortHeight) 12.dp else 14.dp,
        cardPadding = if (extraCompactWidth) 14.dp else 16.dp,
        compactWidth = compactWidth,
        extraCompactWidth = extraCompactWidth,
        shortHeight = shortHeight,
        recordHeroHeight = when {
            extraCompactWidth || shortHeight -> 104.dp
            compactWidth -> 110.dp
            else -> 118.dp
        },
        actionTileHeight = if (extraCompactWidth || shortHeight) 66.dp else 72.dp,
        scheduleActionWidth = if (extraCompactWidth) 64.dp else 72.dp
    )
}

@Composable
private fun stableSp(base: Float, min: Float): TextUnit {
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    return (base / fontScale).coerceAtLeast(min).sp
}

@Composable
private fun AppOutlinedField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    label: String? = null,
    singleLine: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    supportingText: String? = null,
    onFocusLost: (() -> Unit)? = null
) {
    var hadFocus by remember { mutableStateOf(false) }
    Column(modifier = modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            label = label?.let { { Text(it) } },
            singleLine = singleLine,
            keyboardOptions = keyboardOptions,
            visualTransformation = visualTransformation,
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
                focusedLabelColor = Brand,
                unfocusedLabelColor = Muted,
                disabledLabelColor = Muted
            ),
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { state ->
                    if (state.isFocused) {
                        hadFocus = true
                    } else if (hadFocus) {
                        hadFocus = false
                        onFocusLost?.invoke()
                    }
                }
        )
        if (!supportingText.isNullOrBlank()) {
            Text(supportingText, color = Danger, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun VerificationCodeField(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(18.dp)
    Surface(
        color = if (enabled) Color.White else Color(0xFFF5F8FC),
        shape = shape,
        border = BorderStroke(1.dp, if (enabled) Line else Line.copy(alpha = 0.75f)),
        modifier = modifier.height(56.dp)
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            textStyle = TextStyle(color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Medium),
            cursorBrush = SolidColor(Brand),
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            decorationBox = { innerTextField ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isBlank()) {
                        Text("验证码", color = Muted, fontSize = 15.sp)
                    }
                    innerTextField()
                }
            }
        )
    }
}

@Composable
private fun VerificationSendButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean,
    fontSize: androidx.compose.ui.unit.TextUnit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(999.dp)
    Surface(
        color = Color.White,
        shape = shape,
        border = BorderStroke(1.dp, Brand.copy(alpha = if (enabled) 0.34f else 0.16f)),
        modifier = modifier
            .height(56.dp)
            .clickable(enabled = enabled, onClick = onClick)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize().padding(horizontal = 6.dp)) {
            Text(
                text,
                color = if (enabled) Brand else Muted,
                fontSize = fontSize,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun GradientActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null
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
        modifier = modifier
            .height(48.dp)
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
        Text(text, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun KunqiongLogo(modifier: Modifier = Modifier, corner: Dp = 18.dp) {
    Image(
        painter = painterResource(id = R.drawable.kunqiong_logo),
        contentDescription = "鲲穹会纪",
        modifier = modifier.clip(RoundedCornerShape(corner))
    )
}

@Composable
private fun PinnedListScaffold(
    title: String,
    onBack: () -> Unit,
    closeIcon: ImageVector = Icons.Filled.ArrowBack,
    trailing: (@Composable () -> Unit)? = null,
    header: (@Composable ColumnScope.() -> Unit)? = null,
    listState: LazyListState = rememberLazyListState(),
    content: LazyListScope.() -> Unit
) {
    val layout = rememberPhoneLayoutSpec()
    SmartPageBackground {
        Box(
            modifier = Modifier
                .offset(x = (-96).dp, y = (-108).dp)
                .size(300.dp)
                .background(
                    Brush.radialGradient(listOf(Color(0x40A78BFA), Color.Transparent)),
                    CircleShape
                )
        )
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = Color.White.copy(alpha = 0.86f),
                    shape = CircleShape,
                    shadowElevation = 2.dp,
                    modifier = Modifier
                        .size(40.dp)
                        .clickable(onClick = onBack)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(closeIcon, contentDescription = "返回", tint = Ink, modifier = Modifier.size(22.dp))
                    }
                }
                Text(
                    title,
                    color = Ink,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 14.dp)
                )
                trailing?.invoke()
            }
            if (header != null) {
                Column(
                    Modifier.fillMaxWidth().padding(start = layout.horizontalPadding, end = layout.horizontalPadding, top = 4.dp, bottom = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    content = header
                )
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = layout.horizontalPadding,
                    end = layout.horizontalPadding,
                    top = 4.dp,
                    bottom = 24.dp
                ),
                verticalArrangement = Arrangement.spacedBy(layout.listSpacing),
                content = content
            )
        }
    }
}

@Composable
fun HomeScreen(
    dashboard: HomeDashboard,
    recentMeetings: List<Meeting>,
    scheduledMeetings: List<ScheduledMeeting>,
    localTasks: List<MeetingTask>,
    onStart: () -> Unit,
    onImport: () -> Unit,
    onCreateMeeting: () -> Unit,
    onAllSchedules: () -> Unit,
    onAllMeetings: () -> Unit,
    onSearch: () -> Unit,
    onProfile: () -> Unit,
    onCloudSync: () -> Unit,
    onDetail: (String) -> Unit,
    onDeleteMeeting: (String) -> Unit,
    onDeleteSchedule: (String) -> Unit,
    onEditSchedule: (String) -> Unit,
    onStartSchedule: (ScheduledMeeting) -> Unit,
    onProcessingTask: (MeetingTask) -> Unit
) {
    val layout = rememberPhoneLayoutSpec()
    val allTodayMeetings = scheduledMeetings
        .filter { it.isTodaySchedule() }
        .filterNot { it.isFinished() }
        .sortedBy { it.startAtMillis ?: Long.MAX_VALUE }
    val todayMeetings = allTodayMeetings
        .take(2)
    val recentPreview = recentMeetings.take(3)
    SmartPageBackground {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(layout.listSpacing)
        ) {
            item {
                HomeHeader(
                    onSearch = onSearch,
                    onAllMeetings = onAllMeetings,
                    onImport = onImport,
                    onCreateMeeting = onCreateMeeting,
                    onProfile = onProfile,
                    onCloudSync = onCloudSync,
                    layout = layout,
                    todayCount = allTodayMeetings.size
                )
            }
            item {
                RecordHero(
                    onStart = onStart,
                    layout = layout,
                    modifier = Modifier.padding(horizontal = layout.horizontalPadding)
                )
            }
            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.padding(horizontal = layout.horizontalPadding)
                ) {
                    ActionTile("导入文件", "音频/视频转写", Icons.Filled.UploadFile, Modifier.weight(1f), layout, onImport)
                    ActionTile("预约会议", "设置会议时间", Icons.Filled.CalendarMonth, Modifier.weight(1f), layout, onCreateMeeting)
                }
            }
            item {
                SmartSectionHeader(
                    title = "今日会议",
                    primaryAction = "全部",
                    onPrimaryAction = onAllSchedules,
                    secondaryAction = "新建",
                    onSecondaryAction = onCreateMeeting,
                    modifier = Modifier.padding(horizontal = layout.horizontalPadding)
                )
            }
            item {
                TodayMeetingList(
                    meetings = todayMeetings,
                    onStart = onStartSchedule,
                    onDeleteSchedule = onDeleteSchedule,
                    onEditSchedule = onEditSchedule,
                    layout = layout,
                    modifier = Modifier.padding(horizontal = layout.horizontalPadding)
                )
            }
            item {
                SmartSectionHeader(
                    title = "最近会议",
                    primaryAction = "全部",
                    onPrimaryAction = onAllMeetings,
                    modifier = Modifier.padding(horizontal = layout.horizontalPadding)
                )
            }
        val generatingTask = localTasks.firstOrNull { it.status == MeetingTaskStatus.Processing }
        if (generatingTask != null) {
            item {
                Box(Modifier.padding(horizontal = layout.horizontalPadding)) {
                    BackgroundGeneratingCard(generatingTask, onProcessingTask)
                }
            }
        }
            if (recentPreview.isEmpty() && generatingTask == null) {
                item {
                    SmartWhiteCard(
                        modifier = Modifier
                            .padding(horizontal = layout.horizontalPadding)
                            .fillMaxWidth(),
                        radius = 16.dp
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SmartGradientIcon(Icons.Filled.Article, tint = Color(0xFFB7C8EF))
                            Text("暂无会议", color = Muted, fontSize = 14.sp, modifier = Modifier.padding(start = 12.dp))
                        }
                    }
                }
            }
            items(recentPreview.size) { index ->
                Box(Modifier.padding(horizontal = layout.horizontalPadding)) {
                    MeetingCard(recentPreview[index], onDetail, onDeleteMeeting)
                }
            }
        }
    }
}

@Composable
private fun HomeHeader(
    onSearch: () -> Unit,
    onAllMeetings: () -> Unit,
    onImport: () -> Unit,
    onCreateMeeting: () -> Unit,
    onProfile: () -> Unit,
    onCloudSync: () -> Unit,
    layout: PhoneLayoutSpec,
    todayCount: Int
) {
    val headerHeight = if (layout.compactWidth || layout.shortHeight) 312.dp else 334.dp
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(headerHeight)
            .background(
                Brush.verticalGradient(
                    colorStops = arrayOf(
                        0.00f to Color(0xFF5E85FF),
                        0.50f to Color(0xFF27A4FF),
                        1.00f to Color.Transparent
                    )
                )
            )
    ) {
        SmartHeaderTexture(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(if (layout.compactWidth) 198.dp else 216.dp),
            alpha = 1f
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = layout.horizontalPadding, vertical = 22.dp),
            verticalArrangement = Arrangement.spacedBy(if (layout.compactWidth) 16.dp else 18.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("鲲穹会纪", color = Color.White, fontSize = if (layout.compactWidth) 24.sp else 25.sp, fontWeight = FontWeight.Bold)
                    Text(
                        if (todayCount > 0) "今天 $todayCount 场会议待处理" else "今天没有预约会议",
                        color = Color.White.copy(alpha = 0.88f),
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                HomeProfileButton(onClick = onProfile)
            }
            SmartSearchPill("查询会议记录", Icons.Filled.Search, onClick = onSearch)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                HomeQuickAction("会议记录", Icons.Filled.AccessTime, Color(0xFF5B8AFF), onAllMeetings)
                HomeQuickAction("会议纪要", Icons.Filled.Description, Color(0xFF5AC8FA), onImport)
                HomeQuickAction("预约会议", Icons.Filled.CalendarMonth, BrandPurple, onCreateMeeting)
                HomeQuickAction("云端同步", Icons.Filled.CloudQueue, Color(0xFFFF9F43), onCloudSync)
            }
        }
    }
}

@Composable
private fun HomeProfileButton(onClick: () -> Unit) {
    Surface(
        color = Color.White.copy(alpha = 0.18f),
        shape = CircleShape,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.22f)),
        shadowElevation = 0.dp,
        modifier = Modifier
            .size(40.dp)
            .clickable(onClick = onClick)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Person, contentDescription = "个人中心", tint = Color.White, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun HomeQuickAction(title: String, icon: ImageVector, color: Color, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(72.dp)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Surface(
            color = Color.White.copy(alpha = 0.78f),
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.68f)),
            shadowElevation = 2.dp,
            modifier = Modifier.size(50.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(25.dp))
            }
        }
        Text(title, color = BrandDark, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun RecordHero(onStart: () -> Unit, layout: PhoneLayoutSpec, modifier: Modifier = Modifier) {
    SmartGradientPanel(
        modifier = modifier
            .fillMaxWidth()
            .height(if (layout.compactWidth) 96.dp else 104.dp)
            .clickable { onStart() },
        radius = 18.dp,
        brush = SmartRecordBrush
    ) {
        Row(
            Modifier
                .fillMaxSize()
                .padding(horizontal = if (layout.compactWidth) 18.dp else 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                color = Color.White.copy(alpha = 0.16f),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.size(52.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Mic, contentDescription = null, tint = Color.White, modifier = Modifier.size(27.dp))
                }
            }
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text("开始记录", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                Text("实时转写，结束后生成纪要", color = Color.White.copy(alpha = 0.78f), fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
            }
            WaveBars(active = true, barCount = 5, maxHeight = 30.dp, barWidth = 4.dp, gap = 5.dp)
        }
    }
}

@Composable
private fun ActionTile(title: String, subtitle: String, icon: ImageVector, modifier: Modifier, layout: PhoneLayoutSpec, onClick: () -> Unit) {
    val import = title == "导入文件"
    Box(
        modifier = modifier
            .height(if (layout.compactWidth) 150.dp else 156.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (import) {
                    Brush.horizontalGradient(listOf(Color(0xEEF4FBFF), Color(0xEAF0F8FF)))
                } else {
                    Brush.horizontalGradient(listOf(Color(0xF8F7F4FF), Color(0xF4F1EFFF)))
                }
            )
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier.padding(horizontal = 14.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                Modifier
                    .size(if (layout.compactWidth) 52.dp else 56.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (import) Color(0xFFEAF5FF) else Color(0xFFF0E9FF)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = if (import) Brand else BrandPurple, modifier = Modifier.size(26.dp))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = 14.dp)) {
                Text(title, color = Brand, fontWeight = FontWeight.Bold, fontSize = if (layout.compactWidth) 18.sp else 19.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun TodayMeetingList(
    meetings: List<ScheduledMeeting>,
    onStart: (ScheduledMeeting) -> Unit,
    onDeleteSchedule: (String) -> Unit,
    onEditSchedule: (String) -> Unit,
    layout: PhoneLayoutSpec,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (meetings.isEmpty()) {
            Surface(
                color = Color.White.copy(alpha = 0.96f),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.78f)),
                shadowElevation = 2.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .height(if (layout.compactWidth) 110.dp else 118.dp)
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(Icons.Filled.CalendarMonth, contentDescription = null, tint = Color(0xFFB7C8EF), modifier = Modifier.size(42.dp))
                    Text("今天还没有预约会议", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
                }
            }
        }
        meetings.forEach { meeting ->
            val overdue = meeting.isOverdue()
            val startTimeLabel = if (overdue) "逾期" else meeting.compactStartTimeLabel()
            val participantLine = meeting.participants
                .takeUnless { it.isPlaceholderParticipantLabel() }
                ?: "未填写参会人"
            Surface(
                color = Color.White.copy(alpha = 0.96f),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.78f)),
                shadowElevation = 2.dp,
                modifier = Modifier.fillMaxWidth().clickable { onEditSchedule(meeting.id) }
            ) {
                Row(
                    Modifier.padding(horizontal = if (layout.extraCompactWidth) 12.dp else 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(if (layout.compactWidth) 8.dp else 10.dp)
                ) {
                    Surface(
                        color = if (overdue) Color(0xFFFFF0EA) else BrandSoft,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.size(width = if (layout.compactWidth) 50.dp else 54.dp, height = 36.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                startTimeLabel,
                                color = if (overdue) Color(0xFFB33D21) else Brand,
                                fontSize = if (startTimeLabel.length > 4) 13.sp else 14.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(meeting.title, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(participantLine, color = Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        IconButton(onClick = { onDeleteSchedule(meeting.id) }, modifier = Modifier.size(30.dp)) {
                            Icon(Icons.Filled.DeleteOutline, contentDescription = "删除预约", tint = Muted.copy(alpha = 0.62f), modifier = Modifier.size(18.dp))
                        }
                        Surface(
                            color = Color.Transparent,
                            shape = RoundedCornerShape(999.dp),
                            modifier = Modifier
                                .width(if (layout.extraCompactWidth) 50.dp else 54.dp)
                                .height(32.dp)
                                .clip(RoundedCornerShape(999.dp))
                                .background(Brush.horizontalGradient(listOf(Brand, BrandCyan)))
                                .clickable { onStart(meeting) }
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    if (overdue) "补会" else "记录",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun ScheduledMeeting.compactStartTimeLabel(): String {
    startAtMillis?.let { return SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(it)) }
    return Regex("""\d{1,2}:\d{2}""").find(time)?.value ?: time.take(5)
}

private fun String.isPlaceholderParticipantLabel(): Boolean {
    val clean = trim()
    return clean.isBlank() || clean == "待补充参会人" || clean == "未填写参会人"
}

@Composable
fun ScheduleListScreen(
    meetings: List<ScheduledMeeting>,
    onBack: () -> Unit,
    onCreateMeeting: () -> Unit,
    onStart: (ScheduledMeeting) -> Unit,
    onDeleteSchedule: (String) -> Unit,
    onEditSchedule: (String) -> Unit
) {
    var dateFilter by remember { mutableStateOf(DateListFilter.All) }
    var customRange by remember { mutableStateOf(DateRangeFilter()) }
    var showRangePicker by remember { mutableStateOf(false) }
    val activeRange = dateFilter.toRange(customRange)
    val filteredMeetings = meetings
        .filter { activeRange.matches(it.startAtMillis ?: it.createdAtMillis) }
        .sortedBy { it.startAtMillis ?: Long.MAX_VALUE }
    val todayMeetings = filteredMeetings.filter { it.isTodaySchedule() }
    val otherMeetings = filteredMeetings.filterNot { it.isTodaySchedule() }
    PinnedListScaffold(
        title = "预约会议",
        onBack = onBack,
        trailing = {
            TextButton(onClick = onCreateMeeting) {
                Text("新建", color = Brand, fontWeight = FontWeight.Bold)
            }
        },
        header = {
            DateRangeFilterBar(
                selected = dateFilter,
                customRange = customRange,
                onSelect = { filter ->
                    dateFilter = filter
                    if (filter == DateListFilter.All) customRange = DateRangeFilter()
                    if (filter == DateListFilter.Custom) showRangePicker = true
                },
            )
        }
    ) {
        item {
            InfoBlock("今日会议") {
                ScheduleListContent(todayMeetings, onStart, onDeleteSchedule, onEditSchedule)
            }
        }
        if (otherMeetings.isNotEmpty()) {
            item {
                InfoBlock("其他预约") {
                    ScheduleListContent(otherMeetings, onStart, onDeleteSchedule, onEditSchedule)
                }
            }
        } else if (todayMeetings.isEmpty()) {
            item {
                InfoBlock("筛选结果") {
                    Text("没有符合筛选的预约", color = Muted, fontSize = 14.sp)
                }
            }
        }
    }
    DateRangePickerDialog(
        visible = showRangePicker,
        range = customRange,
        onDismiss = {
            showRangePicker = false
            if (!customRange.isActive()) dateFilter = DateListFilter.All
        },
        onSelected = { range ->
            customRange = range
            dateFilter = DateListFilter.Custom
            showRangePicker = false
        }
    )
}

@Composable
private fun ScheduleListContent(
    meetings: List<ScheduledMeeting>,
    onStart: (ScheduledMeeting) -> Unit,
    onDeleteSchedule: (String) -> Unit,
    onEditSchedule: (String) -> Unit
) {
    if (meetings.isEmpty()) {
        Text("这里还没有预约会议", color = Muted, fontSize = 14.sp)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        meetings.forEach { meeting ->
            ScheduleListRow(meeting, onStart, onDeleteSchedule, onEditSchedule)
        }
    }
}

@Composable
private fun ScheduleListRow(
    meeting: ScheduledMeeting,
    onStart: (ScheduledMeeting) -> Unit,
    onDeleteSchedule: (String) -> Unit,
    onEditSchedule: (String) -> Unit
) {
    val overdue = meeting.isOverdue()
    Surface(
        color = if (overdue) Color(0xFFFFFBF8) else Color.White,
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, if (overdue) Color(0xFFFFD5C2) else Line),
        shadowElevation = 1.dp,
        modifier = Modifier.fillMaxWidth().clickable { onEditSchedule(meeting.id) }
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(meeting.title, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(meeting.time, color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 5.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = { onDeleteSchedule(meeting.id) }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.DeleteOutline, contentDescription = "删除预约", tint = Danger)
                }
            }
            if (meeting.participants.isNotBlank()) {
                Text(meeting.participants, color = Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Pill(if (overdue) "逾期" else "待开始", if (overdue) Color(0xFFFFE8DD) else BrandSoft, if (overdue) Color(0xFFB33D21) else Brand)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { onStart(meeting) }, modifier = Modifier.height(40.dp)) {
                    Text(if (overdue) "补会" else "记录", color = Brand, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun MeetingListScreen(
    meetings: List<Meeting>,
    processingTasks: List<MeetingTask> = emptyList(),
    bulkDeleting: Boolean,
    onBack: () -> Unit,
    onDetail: (String) -> Unit,
    onProcessingTask: (MeetingTask) -> Unit = {},
    onDeleteMeeting: (String) -> Unit,
    onDeleteMeetings: (List<String>) -> Unit
) {
    var dateFilter by remember { mutableStateOf(DateListFilter.All) }
    var customRange by remember { mutableStateOf(DateRangeFilter()) }
    var showRangePicker by remember { mutableStateOf(false) }
    var selecting by remember { mutableStateOf(false) }
    var selectedMeetingIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showBulkDeleteConfirm by remember { mutableStateOf(false) }
    var pendingBulkDelete by remember { mutableStateOf(false) }
    val activeRange = dateFilter.toRange(customRange)
    val filteredMeetings = meetings.filter { activeRange.matches(it.createdAtMillis) }
    val visibleProcessingTasks = processingTasks
        .filter { it.status == MeetingTaskStatus.Processing }
        .filter { activeRange.matches(it.createdAtMillis) }
    val filteredMeetingIds = filteredMeetings.map { it.id }.toSet()
    val selectedVisibleCount = selectedMeetingIds.count { it in filteredMeetingIds }
    LaunchedEffect(bulkDeleting, pendingBulkDelete) {
        if (pendingBulkDelete && !bulkDeleting) {
            showBulkDeleteConfirm = false
            selecting = false
            selectedMeetingIds = emptySet()
            pendingBulkDelete = false
        }
    }
    PinnedListScaffold(
        title = "全部会议",
        onBack = {
            if (selecting) {
                selecting = false
                selectedMeetingIds = emptySet()
            } else {
                onBack()
            }
        },
        trailing = {
            if (filteredMeetings.isNotEmpty() && !selecting) {
                TextButton(onClick = { selecting = true }) {
                    Text("多选", color = Brand, fontSize = 14.sp)
                }
            }
        },
        header = {
            DateRangeFilterBar(
                selected = dateFilter,
                customRange = customRange,
                onSelect = { filter ->
                    dateFilter = filter
                    if (filter == DateListFilter.All) customRange = DateRangeFilter()
                    if (filter == DateListFilter.Custom) showRangePicker = true
                },
            )
        }
    ) {
        if (filteredMeetings.isEmpty() && visibleProcessingTasks.isEmpty()) {
            item {
                InfoBlock("会议记录") {
                    Text("这里还没有会议记录", color = Muted, fontSize = 14.sp)
                }
            }
        } else {
            if (visibleProcessingTasks.isNotEmpty()) {
                item {
                    InfoBlock("正在处理") {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            visibleProcessingTasks.forEach { task ->
                                BackgroundGeneratingCard(task = task, onClick = onProcessingTask)
                            }
                        }
                    }
                }
            }
            if (selecting) {
                item {
                    Surface(
                        color = Color.White,
                        shape = RoundedCornerShape(22.dp),
                        border = BorderStroke(1.dp, Line),
                        shadowElevation = 1.dp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("已选 $selectedVisibleCount 项", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                TextButton(onClick = {
                                    selecting = false
                                    selectedMeetingIds = emptySet()
                                }, enabled = !bulkDeleting) {
                                    Text("取消", color = Muted, fontSize = 14.sp)
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                                OutlinedButton(
                                    onClick = {
                                        selectedMeetingIds = if (selectedVisibleCount == filteredMeetings.size) {
                                            selectedMeetingIds - filteredMeetingIds
                                        } else {
                                            selectedMeetingIds + filteredMeetingIds
                                        }
                                    },
                                    enabled = !bulkDeleting,
                                    shape = RoundedCornerShape(999.dp),
                                    modifier = Modifier.weight(1f).height(44.dp)
                                ) {
                                    Text(if (selectedVisibleCount == filteredMeetings.size) "清空" else "全选", maxLines = 1)
                                }
                                Button(
                                    onClick = { showBulkDeleteConfirm = true },
                                    enabled = selectedVisibleCount > 0 && !bulkDeleting,
                                    colors = ButtonDefaults.buttonColors(containerColor = Danger),
                                    shape = RoundedCornerShape(999.dp),
                                    modifier = Modifier.weight(1f).height(44.dp)
                                ) {
                                    Text(if (bulkDeleting) "删除中" else "删除所选", fontWeight = FontWeight.Bold, maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }
            items(filteredMeetings.size) { index ->
                val meeting = filteredMeetings[index]
                MeetingCard(
                    meeting = meeting,
                    onClick = onDetail,
                    onDelete = onDeleteMeeting,
                    selectable = selecting,
                    selected = meeting.id in selectedMeetingIds,
                    onSelectionChange = { id ->
                        selectedMeetingIds = if (id in selectedMeetingIds) {
                            selectedMeetingIds - id
                        } else {
                            selectedMeetingIds + id
                        }
                    }
                )
            }
        }
    }
    if (showBulkDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { if (!bulkDeleting) showBulkDeleteConfirm = false },
            title = { Text("删除选中会议") },
            text = { Text("将删除选中的 $selectedVisibleCount 场会议，本机结果和知识库索引也会同步清理。") },
            confirmButton = {
                TextButton(
                    enabled = !bulkDeleting,
                    onClick = {
                    val ids = selectedMeetingIds.filter { it in filteredMeetingIds }
                    if (ids.isNotEmpty()) {
                        pendingBulkDelete = true
                        onDeleteMeetings(ids)
                    }
                }) {
                    Text(if (bulkDeleting) "删除中..." else "删除", color = Danger, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showBulkDeleteConfirm = false }, enabled = !bulkDeleting) {
                    Text("取消", color = Muted)
                }
            }
        )
    }
    DateRangePickerDialog(
        visible = showRangePicker,
        range = customRange,
        onDismiss = {
            showRangePicker = false
            if (!customRange.isActive()) dateFilter = DateListFilter.All
        },
        onSelected = { range ->
            customRange = range
            dateFilter = DateListFilter.Custom
            showRangePicker = false
        }
    )
}

private enum class DateListFilter(val label: String) {
    All("全部"),
    Today("今天"),
    Yesterday("昨天"),
    Last7Days("近7天"),
    Custom("自定义");

    fun toRange(customRange: DateRangeFilter): DateRangeFilter {
        val today = Calendar.getInstance().startOfDayMillis()
        val dayMillis = 24L * 60 * 60 * 1000
        return when (this) {
            All -> DateRangeFilter()
            Today -> DateRangeFilter(today, today)
            Yesterday -> DateRangeFilter(today - dayMillis, today - dayMillis)
            Last7Days -> DateRangeFilter(today - 6L * dayMillis, null)
            Custom -> customRange
        }
    }
}

private data class DateRangeFilter(
    val startDayMillis: Long? = null,
    val endDayMillis: Long? = null
) {
    fun isActive(): Boolean = startDayMillis != null || endDayMillis != null

    fun matches(millis: Long?): Boolean {
        if (startDayMillis == null && endDayMillis == null) return true
        val value = millis ?: return false
        val dayStart = value.toLocalDayStartMillis()
        val start = startDayMillis ?: Long.MIN_VALUE
        val end = endDayMillis ?: Long.MAX_VALUE
        return dayStart in start..end
    }
}

@Composable
private fun DateRangeFilterBar(
    selected: DateListFilter,
    customRange: DateRangeFilter,
    onSelect: (DateListFilter) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        DateListFilter.entries.forEach { filter ->
            val active = selected == filter
            val label = if (filter == DateListFilter.Custom && customRange.isActive()) {
                customRange.toShortLabel()
            } else {
                filter.label
            }
            DateFilterChip(label = label, active = active, onClick = { onSelect(filter) })
        }
    }
}

@Composable
private fun DateFilterChip(
    label: String,
    active: Boolean,
    onClick: () -> Unit
) {
    Surface(
        color = if (active) BrandSoft else Color.White,
        shape = RoundedCornerShape(999.dp),
        border = BorderStroke(1.dp, if (active) BrandSoft else Line),
        modifier = Modifier.clickable { onClick() }
    ) {
        Text(
            text = label,
            color = if (active) Brand else Muted,
            fontSize = 13.sp,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateRangePickerDialog(
    visible: Boolean,
    range: DateRangeFilter,
    onDismiss: () -> Unit,
    onSelected: (DateRangeFilter) -> Unit
) {
    if (!visible) return
    val state = rememberDateRangePickerState(
        initialSelectedStartDateMillis = range.startDayMillis?.toDatePickerUtcMillis(),
        initialSelectedEndDateMillis = range.endDayMillis?.toDatePickerUtcMillis()
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val start = state.selectedStartDateMillis?.fromDatePickerUtcMillis()
                    val end = state.selectedEndDateMillis?.fromDatePickerUtcMillis()
                    onSelected(DateRangeFilter(start, end))
                }
            ) {
                Text("确定", color = Brand, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = Muted) }
        }
    ) {
        DateRangePicker(state = state, title = null, headline = null)
    }
}

private fun Calendar.sameDay(other: Calendar): Boolean {
    return get(Calendar.YEAR) == other.get(Calendar.YEAR) &&
        get(Calendar.DAY_OF_YEAR) == other.get(Calendar.DAY_OF_YEAR)
}

private fun Calendar.startOfDayMillis(): Long {
    val calendar = clone() as Calendar
    calendar.set(Calendar.HOUR_OF_DAY, 0)
    calendar.set(Calendar.MINUTE, 0)
    calendar.set(Calendar.SECOND, 0)
    calendar.set(Calendar.MILLISECOND, 0)
    return calendar.timeInMillis
}

private fun Long.toLocalDayStartMillis(): Long {
    return Calendar.getInstance().apply { timeInMillis = this@toLocalDayStartMillis }.startOfDayMillis()
}

private fun Long.toDatePickerUtcMillis(): Long {
    val local = Calendar.getInstance().apply { timeInMillis = this@toDatePickerUtcMillis }
    return Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        clear()
        set(Calendar.YEAR, local.get(Calendar.YEAR))
        set(Calendar.MONTH, local.get(Calendar.MONTH))
        set(Calendar.DAY_OF_MONTH, local.get(Calendar.DAY_OF_MONTH))
    }.timeInMillis
}

private fun Long.fromDatePickerUtcMillis(): Long {
    val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = this@fromDatePickerUtcMillis }
    return Calendar.getInstance().apply {
        clear()
        set(Calendar.YEAR, utc.get(Calendar.YEAR))
        set(Calendar.MONTH, utc.get(Calendar.MONTH))
        set(Calendar.DAY_OF_MONTH, utc.get(Calendar.DAY_OF_MONTH))
    }.timeInMillis
}

private fun Long.toDateText(): String {
    return SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date(this))
}

private fun DateRangeFilter.toShortLabel(): String {
    val start = startDayMillis?.toShortDateText()
    val end = endDayMillis?.toShortDateText()
    return when {
        start != null && end != null -> "$start~$end"
        start != null -> "$start 起"
        end != null -> "$end 前"
        else -> DateListFilter.Custom.label
    }
}

private fun Long.toShortDateText(): String {
    return SimpleDateFormat("MM-dd", Locale.CHINA).format(Date(this))
}

private fun ScheduledMeeting.isTodaySchedule(): Boolean {
    val millis = startAtMillis ?: return false
    val today = Calendar.getInstance()
    val target = Calendar.getInstance().apply { timeInMillis = millis }
    return today.get(Calendar.YEAR) == target.get(Calendar.YEAR) &&
        today.get(Calendar.DAY_OF_YEAR) == target.get(Calendar.DAY_OF_YEAR)
}

@Composable
private fun MeetingCard(
    meeting: Meeting,
    onClick: (String) -> Unit,
    onDelete: (String) -> Unit,
    selectable: Boolean = false,
    selected: Boolean = false,
    onSelectionChange: (String) -> Unit = {}
) {
    Surface(
        color = if (selected) BrandSoft.copy(alpha = 0.50f) else Color.White.copy(alpha = 0.96f),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, if (selected) Brand else Color.White.copy(alpha = 0.72f)),
        shadowElevation = 3.dp,
        modifier = Modifier.fillMaxWidth().clickable {
            if (selectable) {
                onSelectionChange(meeting.id)
            } else {
                onClick(meeting.id)
            }
        }
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                if (selectable) {
                    Checkbox(
                        checked = selected,
                        onCheckedChange = { onSelectionChange(meeting.id) },
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(meeting.title, color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(meeting.displaySubtitle(), color = Muted, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
                }
                Pill(meeting.status.label, meeting.statusColor.copy(alpha = 0.12f), meeting.statusColor)
                if (!selectable) {
                    IconButton(onClick = { onDelete(meeting.id) }, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Filled.DeleteOutline, contentDescription = "删除", tint = Danger)
                    }
                }
            }
        }
    }
}

private fun Meeting.displaySubtitle(): String {
    val dateLabel = createdAtMillis?.toMeetingDateLabel() ?: timeLabel
    return "$dateLabel，$durationLabel"
}

private fun Meeting.detailTimeLabel(): String {
    return createdAtMillis?.toMeetingDateLabel() ?: timeLabel
}

private fun Long.toMeetingDateLabel(): String {
    val now = Calendar.getInstance()
    val target = Calendar.getInstance().apply { timeInMillis = this@toMeetingDateLabel }
    val time = SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(this))
    return when {
        now.sameDay(target) -> "今天 $time"
        Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }.sameDay(target) -> "昨天 $time"
        now.get(Calendar.YEAR) == target.get(Calendar.YEAR) -> SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(this))
        else -> SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(this))
    }
}

@Composable
private fun BackgroundGeneratingCard(task: MeetingTask, onClick: (MeetingTask) -> Unit) {
    Surface(
        color = Color.White.copy(alpha = 0.94f),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, Color(0xFFD8DEFF)),
        shadowElevation = 3.dp,
        modifier = Modifier.fillMaxWidth().clickable { onClick(task) }
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text("会议处理中", color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "${task.title} · ${task.progressLabel ?: task.status.label}",
                        color = Muted,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    Text("点击查看进度或终止任务", color = Brand, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                }
                Pill(task.progressPercent.progressLabel(), BrandSoft, Brand)
            }
            ProgressLine(task.progressPercent / 100f, Modifier.padding(top = 12.dp))
        }
    }
}

@Composable
fun ImportScreen(
    tasks: List<MeetingTask>,
    onBack: () -> Unit,
    onPickFile: () -> Unit,
    onSubmit: () -> Unit,
    onDeleteTask: (MeetingTask) -> Unit,
    onProcessingTask: (MeetingTask) -> Unit
) {
    val queuedTasks = tasks
        .filter { it.status == MeetingTaskStatus.WaitingProcess || it.status == MeetingTaskStatus.Failed }
        .sortedBy { it.createdAtMillis }
    val waitingTasks = queuedTasks.filter { it.status == MeetingTaskStatus.WaitingProcess && it.progressStage != "waiting_retry" }
    val processingTask = tasks.firstOrNull { it.status == MeetingTaskStatus.Processing }
    ScreenScaffold("导入文件", onBack) {
        UploadPanel(onPickFile)
        if (processingTask != null) {
            InfoBlock("正在处理") {
                RowItem(
                    Icons.Filled.UploadFile,
                    processingTask.title,
                    listOfNotNull(processingTask.status.label, processingTask.sizeLabel).joinToString(" · "),
                    onClick = { onProcessingTask(processingTask) }
                )
                ProgressLine(processingTask.progressPercent / 100f, Modifier.padding(top = 12.dp))
                Text(processingTask.progressLabel ?: "处理中", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                Text(
                    "点击查看进度或终止任务",
                    color = Brand,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 6.dp).clickable { onProcessingTask(processingTask) }
                )
            }
        }
        InfoBlock(if (queuedTasks.isEmpty()) "待处理文件" else "待处理文件（${queuedTasks.size}）") {
            if (queuedTasks.isEmpty()) {
                RowItem(Icons.Filled.UploadFile, "还没有待处理文件")
            } else {
                queuedTasks.forEach {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            RowItem(
                                Icons.Filled.UploadFile,
                                it.title,
                                listOfNotNull(it.status.label, it.sizeLabel, it.errorMessage).joinToString(" · "),
                                onClick = { onProcessingTask(it) }
                            )
                        }
                        IconButton(onClick = { onDeleteTask(it) }) {
                            Icon(Icons.Filled.DeleteOutline, contentDescription = "删除任务", tint = Danger)
                        }
                    }
                }
            }
            GradientActionButton(
                text = when {
                    processingTask != null -> "加入待处理"
                    waitingTasks.size > 1 -> "开始处理"
                    else -> "开始处理"
                },
                onClick = onSubmit,
                enabled = waitingTasks.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().height(52.dp)
            )
        }
    }
}

@Composable
private fun UploadPanel(onPickFile: () -> Unit) {
    Surface(
        color = Color.White.copy(alpha = 0.96f),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.72f)),
        shadowElevation = 3.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(18.dp), horizontalAlignment = Alignment.Start) {
            Text("本地文件", color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            GradientActionButton(
                text = "选择文件",
                onClick = onPickFile,
                modifier = Modifier.padding(top = 14.dp).fillMaxWidth().height(50.dp)
            )
        }
    }
}

@Composable
fun SearchScreen(
    recentSearches: List<String>,
    recentMeetings: List<Meeting>,
    onBack: () -> Unit,
    onSearchCommit: (String) -> Unit,
    onDetail: (String) -> Unit
) {
    var query by remember { mutableStateOf("") }
    val cleanQuery = query.trim()
    fun commitSearch(keyword: String = cleanQuery) {
        val clean = keyword.trim()
        if (clean.isNotBlank()) onSearchCommit(clean)
    }
    val results = if (cleanQuery.isBlank()) {
        emptyList()
    } else {
        recentMeetings.filter { meeting ->
            meeting.title.contains(cleanQuery, ignoreCase = true) ||
                meeting.participants.contains(cleanQuery, ignoreCase = true) ||
                meeting.tags.any { it.contains(cleanQuery, ignoreCase = true) } ||
                meeting.summary.contains(cleanQuery, ignoreCase = true) ||
                meeting.decisions.any { it.contains(cleanQuery, ignoreCase = true) } ||
                meeting.topics.any { it.title.contains(cleanQuery, ignoreCase = true) || it.summary.contains(cleanQuery, ignoreCase = true) || it.source.contains(cleanQuery, ignoreCase = true) } ||
                meeting.risks.any { it.title.contains(cleanQuery, ignoreCase = true) || it.description.contains(cleanQuery, ignoreCase = true) || it.recommendation.contains(cleanQuery, ignoreCase = true) || it.source.contains(cleanQuery, ignoreCase = true) } ||
                meeting.todos.any {
                    it.title.contains(cleanQuery, ignoreCase = true) ||
                        it.description.contains(cleanQuery, ignoreCase = true) ||
                        it.source.contains(cleanQuery, ignoreCase = true) ||
                        it.assigneeName?.contains(cleanQuery, ignoreCase = true) == true
                } ||
                meeting.transcripts.any { it.text.contains(cleanQuery, ignoreCase = true) || it.speaker.contains(cleanQuery, ignoreCase = true) }
        }
    }
    PinnedListScaffold(
        title = "搜索",
        onBack = onBack,
        header = {
            Surface(color = Color.White, shape = RoundedCornerShape(22.dp), border = BorderStroke(1.dp, Line), shadowElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                    singleLine = true,
                    shape = RoundedCornerShape(18.dp),
                    colors = TextFieldDefaults.colors(
                        focusedTextColor = Ink,
                        unfocusedTextColor = Ink,
                        focusedContainerColor = Color(0xFFFAFCFF),
                        unfocusedContainerColor = Color.White,
                        focusedIndicatorColor = Brand.copy(alpha = 0.72f),
                        unfocusedIndicatorColor = Color.Transparent,
                        cursorColor = Brand
                    ),
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = Muted) },
                    trailingIcon = { Icon(Icons.Filled.Article, contentDescription = null, tint = Brand) },
                    placeholder = { Text("搜索会议、纪要、待办、转写原文") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { commitSearch() })
                )
            }
        }
    ) {
        item {
            InfoBlock("搜索结果") {
                when {
                    cleanQuery.isBlank() -> Text("输入关键词搜索本机会议内容", color = Muted, fontSize = 14.sp, lineHeight = 22.sp)
                    results.isNotEmpty() -> {
                        results.take(10).forEach { meeting ->
                            RowItem(
                                Icons.Filled.Search,
                                meeting.title,
                                meeting.searchHitLabel(cleanQuery),
                                onClick = {
                                    commitSearch()
                                    onDetail(meeting.id)
                                }
                            )
                        }
                    }
                    else -> Text("没有找到匹配内容，换个关键词试试", color = Muted, fontSize = 14.sp, lineHeight = 22.sp)
                }
            }
        }
        if (recentSearches.isNotEmpty()) {
            item {
                InfoBlock("最近搜索") {
                    recentSearches.forEach { keyword ->
                        RowItem(
                            Icons.Filled.Search,
                            keyword,
                            "再次搜索",
                            onClick = {
                                query = keyword
                                commitSearch(keyword)
                            }
                        )
                    }
                }
            }
        }
    }
}

private fun Meeting.searchHitLabel(query: String): String {
    return when {
        title.contains(query, ignoreCase = true) -> "命中会议标题"
        participants.contains(query, ignoreCase = true) || transcripts.any { it.speaker.contains(query, ignoreCase = true) } -> "命中说话人"
        tags.any { it.contains(query, ignoreCase = true) } -> "命中标签"
        summary.contains(query, ignoreCase = true) -> "命中会议摘要"
        topics.any { it.title.contains(query, ignoreCase = true) || it.summary.contains(query, ignoreCase = true) || it.source.contains(query, ignoreCase = true) } -> "命中议题"
        decisions.any { it.contains(query, ignoreCase = true) } -> "命中决策"
        risks.any { it.title.contains(query, ignoreCase = true) || it.description.contains(query, ignoreCase = true) || it.recommendation.contains(query, ignoreCase = true) || it.source.contains(query, ignoreCase = true) } -> "命中风险"
        todos.any { it.title.contains(query, ignoreCase = true) || it.description.contains(query, ignoreCase = true) || it.source.contains(query, ignoreCase = true) || it.assigneeName?.contains(query, ignoreCase = true) == true } -> "命中待办"
        transcripts.any { it.text.contains(query, ignoreCase = true) } -> "命中转写原文"
        else -> "命中会议内容"
    }
}

@Composable
fun RecordScreen(
    recording: RecordingUiState,
    segments: List<TranscriptSegment>,
    onBack: () -> Unit,
    onPauseToggle: () -> Unit,
    onFinish: () -> Unit
) {
    val inputLevel = recording.audioLevel?.levelPercent ?: 0
    val audioWarning = recording.audioWarning
    val transcriptionStatus = recording.transcriptionStatus
    val transcriptListState = rememberLazyListState()
    val latestTranscriptMarker = segments.lastOrNull()?.let { item ->
        "${item.speaker}|${item.timestamp}|${item.timeRangeLabel}|${item.text}"
    }
    LaunchedEffect(segments.size, latestTranscriptMarker) {
        if (segments.isNotEmpty()) {
            transcriptListState.animateScrollToItem(segments.lastIndex)
        }
    }
    SmartPageBackground {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            SmartRoundIconButton(
                icon = Icons.Filled.ArrowBack,
                contentDescription = "返回",
                onClick = onBack,
                tint = Ink
            )
            Column(Modifier.weight(1f).padding(horizontal = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(recording.title, color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    when (recording.status) {
                        RecordingStatus.Preparing -> "正在连接实时转写"
                        RecordingStatus.Paused -> "录音已暂停"
                        else -> "录音进行中"
                    },
                    color = Muted,
                    fontSize = 13.sp
                )
            }
            Spacer(Modifier.size(40.dp))
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 18.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(Brush.horizontalGradient(listOf(Brand, Color(0xFF4B7BFF), BrandCyan)))
        ) {
            Column(Modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    LiveDot(
                        when (recording.status) {
                            RecordingStatus.Preparing -> "连接中"
                            RecordingStatus.Paused -> "录音已暂停"
                            else -> "实时记录中"
                        },
                        active = recording.status == RecordingStatus.Recording
                    )
                    Spacer(Modifier.weight(1f))
                    Pill("输入 $inputLevel%", if (inputLevel > 0) Color(0xFFD4FFF8) else Color.White.copy(alpha = 0.12f), if (inputLevel > 0) BrandDark else Color.White)
                }
                Text(recording.statusLine.substringBefore(" ·"), color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 18.dp))
                WaveBars(
                    modifier = Modifier.padding(top = 18.dp),
                    active = recording.status == RecordingStatus.Recording,
                    levelPercent = inputLevel,
                    barCount = 24,
                    maxHeight = 44.dp,
                    barWidth = 5.dp,
                    gap = 5.dp
                )
                if (audioWarning != null) {
                    Text(audioWarning, color = Color(0xFFFFC9C9), fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.fillMaxWidth().padding(top = 14.dp))
                }
                if (transcriptionStatus != null) {
                    Text(transcriptionStatus, color = Color(0xFFBFF7EE), fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
                }
                if (recording.status == RecordingStatus.Paused) {
                    Surface(color = Color.White.copy(alpha = 0.14f), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Pause, contentDescription = null, tint = Color(0xFF84E4D8))
                            Text("点击继续后恢复录音与实时转写，当前已保留暂停前片段。", color = Color.White.copy(alpha = 0.82f), fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(start = 10.dp))
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("实时转写", color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            LiveDot(
                when {
                    recording.status == RecordingStatus.Preparing -> "准备中"
                    recording.status == RecordingStatus.Paused -> "已暂停"
                    transcriptionStatus != null -> "本地保存中"
                    else -> "转写中"
                },
                active = recording.status == RecordingStatus.Recording && transcriptionStatus == null,
                contentColor = Brand
            )
        }
        LazyColumn(
            state = transcriptListState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (segments.isEmpty()) {
                item {
                    Surface(color = Color.White, shape = RoundedCornerShape(22.dp), border = BorderStroke(1.dp, Line), modifier = Modifier.fillMaxWidth()) {
                        Text("开始说话后，转写内容会显示在这里。", color = Muted, fontSize = 14.sp, lineHeight = 21.sp, modifier = Modifier.padding(18.dp))
                    }
                }
            } else {
                items(segments.size) { index ->
                    val item = segments[index]
                    TranscriptLine(
                        item.speaker,
                        item.text,
                        item.timestamp,
                        item.timeRangeLabel,
                        showSpeakerLabel = false
                    )
                }
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 18.dp)
        ) {
            ActionButton(
                if (recording.status == RecordingStatus.Paused) Icons.Filled.Mic else Icons.Filled.Pause,
                if (recording.status == RecordingStatus.Preparing) "准备中" else if (recording.status == RecordingStatus.Paused) "继续" else "暂停",
                Modifier.weight(1f),
                onClick = { if (recording.status != RecordingStatus.Preparing) onPauseToggle() },
                enabled = recording.status != RecordingStatus.Preparing
            )
            Button(onClick = onFinish, modifier = Modifier.weight(1f).height(48.dp), colors = ButtonDefaults.buttonColors(containerColor = Danger), shape = RoundedCornerShape(999.dp)) {
                Icon(Icons.Filled.Stop, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("结束")
            }
        }
    }
    }
}

@Composable
fun GeneratingScreen(
    task: MeetingTask?,
    queuedTasks: List<MeetingTask>,
    onClose: () -> Unit,
    onCancel: (String) -> Unit,
    onRetry: (String) -> Unit,
    onDetail: (String) -> Unit
) {
    var showCancelConfirm by remember { mutableStateOf(false) }
    val status = task?.status
    val waitingRetry = status == MeetingTaskStatus.WaitingProcess && task.progressStage == "waiting_retry"
    val processing = status == MeetingTaskStatus.Processing
    val failed = status == MeetingTaskStatus.Failed
    val canceled = status == MeetingTaskStatus.Canceled
    val finished = status == MeetingTaskStatus.Finished
    val progress = task?.progressPercent?.coerceIn(0f, 100f) ?: if (finished) 100f else 0f

    SmartPageBackground {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            SmartRoundIconButton(
                icon = Icons.Filled.Close,
                contentDescription = "退出",
                onClick = onClose,
                tint = Ink
            )
            Text("生成会议纪要", color = Ink, fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).padding(start = 14.dp))
        }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Surface(
                    color = Color.White,
                    shape = RoundedCornerShape(22.dp),
                    border = BorderStroke(1.dp, Line),
                    shadowElevation = 2.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(20.dp)) {
                        Text(
                            when {
                                finished -> "AI 纪要已生成"
                                failed -> "处理失败"
                                waitingRetry -> "处理暂未完成"
                                canceled -> "任务已终止"
                                else -> task?.progressLabel ?: "正在处理文件"
                            },
                            color = Ink,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        if (task != null) {
                            Row(
                                modifier = Modifier.padding(top = 16.dp).fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    Modifier
                                        .size(40.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(BrandSoftCyan),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Filled.UploadFile, contentDescription = null, tint = BrandDark, modifier = Modifier.size(21.dp))
                                }
                                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                                    Text(task.title, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        listOf(task.status.label, task.createdAtLabel).joinToString(" · "),
                                        color = Muted,
                                        fontSize = 12.sp,
                                        lineHeight = 17.sp,
                                        modifier = Modifier.padding(top = 3.dp)
                                    )
                                }
                            }
                        }
                        ProgressLine(progress / 100f, Modifier.padding(top = 16.dp))
                        Text(
                            when {
                                finished -> "100%"
                                failed -> "失败"
                                waitingRetry -> "可继续"
                                canceled -> "终止"
                                else -> progress.progressLabel()
                            },
                            color = if (failed || canceled) Danger else Brand,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                        Text(
                            task?.errorMessage ?: when {
                                failed -> "处理失败，请重新尝试。"
                                waitingRetry -> "处理暂未完成，可以继续处理。"
                                canceled -> "任务已终止，可以重新开始处理。"
                                finished -> "转写、来源、纪要和待办已生成。"
                                queuedTasks.isNotEmpty() -> "请稍候，完成后会自动处理后续任务。"
                                else -> "请稍候，完成后可直接查看会议纪要。"
                            },
                            color = if (failed || canceled) Danger else Muted,
                            fontSize = 13.sp,
                            lineHeight = 20.sp,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                        if (finished) {
                            Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Pill("摘要", BrandSoft, Brand)
                                Pill("来源", Color(0xFFEEF1FF), Color(0xFF4856C8))
                                Pill("待办", Color(0xFFFFF5DB), Color(0xFFC58B00))
                            }
                        }
                    }
                }
            }
            if (queuedTasks.isNotEmpty()) {
                item {
                    Surface(
                        color = Color.White,
                        shape = RoundedCornerShape(18.dp),
                        border = BorderStroke(1.dp, Line),
                        shadowElevation = 2.dp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(
                                "后续待处理",
                                color = Ink,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                            queuedTasks.forEachIndexed { index, queuedTask ->
                                QueuedProcessingTaskRow(
                                    task = queuedTask,
                                    order = index + 1
                                )
                            }
                        }
                    }
                }
            }
        }
        Surface(color = Color.White, shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp), border = BorderStroke(1.dp, Line), shadowElevation = 6.dp, modifier = Modifier.fillMaxWidth().navigationBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (failed || waitingRetry || canceled) {
                    GradientActionButton(if (waitingRetry) "继续处理" else "重新尝试", onClick = { onRetry(task.id) }, enabled = true, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = onClose, modifier = Modifier.weight(1f), shape = RoundedCornerShape(999.dp)) {
                        Text("退出")
                    }
                } else if (processing) {
                    OutlinedButton(onClick = onClose, modifier = Modifier.weight(1f), shape = RoundedCornerShape(999.dp)) {
                        Text("后台处理")
                    }
                    OutlinedButton(
                        onClick = { showCancelConfirm = true },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(999.dp),
                        border = BorderStroke(1.dp, Danger.copy(alpha = 0.45f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Danger)
                    ) {
                        Text("终止任务")
                    }
                } else {
                    OutlinedButton(onClick = onClose, modifier = Modifier.weight(1f), shape = RoundedCornerShape(999.dp)) {
                        Text("返回首页")
                    }
                    GradientActionButton("查看详情", onClick = { task?.id?.let(onDetail) }, enabled = finished, modifier = Modifier.weight(1f))
                }
            }
        }
    }
    }

    if (showCancelConfirm && task != null) {
        AlertDialog(
            onDismissRequest = { showCancelConfirm = false },
            title = { Text("终止处理？") },
            text = { Text("当前文件正在生成会议纪要，终止后需要重新开始处理。") },
            confirmButton = {
                TextButton(onClick = {
                    showCancelConfirm = false
                    onCancel(task.id)
                }) { Text("终止任务", color = Danger) }
            },
            dismissButton = {
                TextButton(onClick = { showCancelConfirm = false }) { Text("继续处理") }
            }
        )
    }
}

@Composable
private fun QueuedProcessingTaskRow(task: MeetingTask, order: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(BrandSoftCyan),
            contentAlignment = Alignment.Center
        ) {
            Text(order.toString(), color = Brand, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(task.title, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                listOf(task.source.label, "当前任务完成后自动处理").joinToString(" · "),
                color = Muted,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                modifier = Modifier.padding(top = 3.dp)
            )
        }
    }
}

@Composable
fun DetailScreen(
    meeting: Meeting,
    onBack: () -> Unit,
    onSource: (Int) -> Unit,
    onExport: () -> Unit,
    onEdit: () -> Unit,
    onEditInfo: () -> Unit,
    onSpeakers: () -> Unit,
    showRegenerateMinutes: Boolean,
    minutesRefreshing: Boolean,
    onRegenerateMinutes: () -> Unit,
    onAddTodo: () -> Unit,
    onEditSegmentSpeaker: (Int) -> Unit,
    onCopySegment: (TranscriptSegment) -> Unit,
    onTodoDetail: (TodoItem) -> Unit,
    onConfirm: () -> Unit,
    onDelete: () -> Unit
) {
    var tab by remember(meeting.id) { mutableStateOf("摘要") }
    var showRegenerateConfirm by remember(meeting.id) { mutableStateOf(false) }
    val detailTabs = listOf("详情", "摘要", "内容", "议题", "决策", "待办", "风险")
    val detailListState = remember(meeting.id) { LazyListState() }
    val summaryListState = remember(meeting.id) { LazyListState() }
    val contentListState = remember(meeting.id) { LazyListState() }
    val topicListState = remember(meeting.id) { LazyListState() }
    val decisionListState = remember(meeting.id) { LazyListState() }
    val todoListState = remember(meeting.id) { LazyListState() }
    val riskListState = remember(meeting.id) { LazyListState() }
    val currentListState = when (tab) {
        "详情" -> detailListState
        "内容" -> contentListState
        "议题" -> topicListState
        "决策" -> decisionListState
        "待办" -> todoListState
        "风险" -> riskListState
        else -> summaryListState
    }
    val scope = rememberCoroutineScope()
    val showContentBackToTop = tab == "内容" &&
        (contentListState.firstVisibleItemIndex > 0 || contentListState.firstVisibleItemScrollOffset > 240)

    Box(Modifier.fillMaxSize()) {
        PinnedListScaffold(
            title = "会议详情",
            onBack = onBack,
            trailing = {
                if (showRegenerateMinutes || minutesRefreshing) {
                    IconButton(
                        onClick = { showRegenerateConfirm = true },
                        enabled = !minutesRefreshing && showRegenerateMinutes
                    ) {
                        if (minutesRefreshing) {
                            CircularProgressIndicator(
                                color = Brand,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(20.dp)
                            )
                        } else {
                            Icon(
                                Icons.Filled.Refresh,
                                contentDescription = "更新纪要",
                                tint = Brand
                            )
                        }
                    }
                }
                if (tab == "待办") {
                    IconButton(onClick = onAddTodo) {
                        Icon(Icons.Filled.Add, contentDescription = "补充待办", tint = Brand)
                    }
                }
                IconButton(onClick = onDelete) { Icon(Icons.Filled.DeleteOutline, contentDescription = "删除", tint = Danger) }
                IconButton(onClick = onExport) { Icon(Icons.Filled.FileDownload, contentDescription = "导出", tint = Ink) }
            },
            header = {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(meeting.title, color = Ink, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), lineHeight = 30.sp)
                    IconButton(onClick = onEditInfo) {
                        Icon(Icons.Filled.Edit, contentDescription = "编辑会议名称", tint = Brand)
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                ) {
                    detailTabs.forEach { item ->
                        DetailTabChip(item, selected = tab == item) { tab = item }
                    }
                }
            },
            listState = currentListState
        ) {
            item {
                when (tab) {
            "详情" -> {
                InfoBlock("会议信息") {
                    ConfirmationStatusCard(
                        pending = meeting.status == com.huiyi.app.data.MeetingStatus.PendingConfirm,
                        onConfirm = onConfirm
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                        InfoCell("会议时间", meeting.detailTimeLabel(), Modifier.weight(1f))
                        InfoCell("转写片段", "${meeting.transcripts.size} 条", Modifier.weight(1f))
                    }
                    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        InfoCell("说话人", meeting.participants.takeUnless { it.isPlaceholderParticipantLabel() } ?: "待补充", Modifier.weight(1f))
                        InfoCell("音频大小", meeting.durationLabel, Modifier.weight(1f))
                    }
                }
            }
            "摘要" -> {
                InfoBlock("会议摘要", action = onEdit) {
                    Text(meeting.summary, color = Ink, fontSize = 15.sp, lineHeight = 23.sp)
                    Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OverviewPill(meeting.topics.size.toString(), "议题", Modifier.weight(1f))
                        OverviewPill(meeting.decisions.size.toString(), "决策", Modifier.weight(1f))
                        OverviewPill(meeting.todos.size.toString(), "待办", Modifier.weight(1f))
                        OverviewPill(meeting.risks.size.toString(), "风险", Modifier.weight(1f))
                    }
                    val firstTimestamp = meeting.transcripts.firstOrNull()?.timestamp ?: "00:00"
                    SourceButton("查看来源 $firstTimestamp") { onSource(0) }
                }
            }
            "内容" -> {
                InfoBlock("会议内容", action = onSpeakers) {
                    if (meeting.transcripts.isEmpty()) {
                        Text("还没有转写结果", color = Muted, fontSize = 14.sp, lineHeight = 22.sp)
                    } else {
                        meeting.transcripts.forEachIndexed { index, item ->
                            TranscriptLine(
                                item.speaker,
                                item.text,
                                item.timestamp,
                                item.timeRangeLabel,
                                onSpeakerClick = { onEditSegmentSpeaker(index) },
                                onCopyClick = { onCopySegment(item) }
                            )
                            SourceButton("播放 ${item.timeRangeLabel}") { onSource(index) }
                        }
                    }
                }
            }
            "议题" -> InfoBlock("议题梳理") {
                if (meeting.topics.isEmpty()) {
                    Text("还没有议题记录", color = Muted, fontSize = 14.sp, lineHeight = 22.sp)
                } else {
                    meeting.topics.forEachIndexed { index, item ->
                        val sourceIndex = meeting.sourceIndexForOrNull(
                            timestamp = item.sourceTimestamp,
                            text = listOf(item.title, item.summary, item.source).joinToString(" ")
                        )
                        InsightItem(
                            title = item.title,
                            tag = "议题",
                            desc = listOf(item.summary, sourceIndex?.let { "来源：${meeting.transcripts.getOrNull(it)?.timeRangeLabel}" } ?: "来源待核验").filter { it.isNotBlank() }.joinToString(" · "),
                            onSource = sourceIndex?.let { { onSource(it) } }
                        )
                    }
                }
            }
            "决策" -> InfoBlock("决策确认") {
                if (meeting.decisions.isEmpty()) {
                    Text("还没有决策记录", color = Muted, fontSize = 14.sp, lineHeight = 22.sp)
                } else {
                    meeting.decisions.forEachIndexed { index, item ->
                        val sourceIndex = meeting.sourceIndexForOrNull(text = item)
                        InsightItem(item, "决策", sourceIndex?.let { "来源：${meeting.transcripts.getOrNull(it)?.timeRangeLabel ?: "无时间点"}" } ?: "来源待核验", sourceIndex?.let { { onSource(it) } })
                    }
                }
            }
            "待办" -> InfoBlock("待办事项") {
                if (meeting.todos.isEmpty()) {
                    Text("还没有待办事项，可点击右上角 + 补充", color = Muted, fontSize = 14.sp, lineHeight = 22.sp)
                } else {
                    meeting.todos.forEach { item ->
                        val isManualTodo = item.sourceLabel == "手动补充"
                        val sourceIndex = item.sourceSegmentIndex ?: meeting.sourceIndexForOrNull(
                            timestamp = item.sourceTimestamp,
                            text = listOf(item.title, item.description, item.source).joinToString(" ")
                        )
                        ActionItem(
                            title = item.title,
                            meta = listOfNotNull(
                                item.assigneeLabel?.let { "负责人 $it" },
                                item.dueLabel?.let { "截止 $it" },
                                item.sourceLabel
                            ).joinToString(" · ").ifBlank { "待补全" },
                            onDetail = { onTodoDetail(item) },
                            onSource = if (isManualTodo) null else sourceIndex?.let { { onSource(it) } },
                            missingSourceText = if (isManualTodo) "手动补充" else "来源待核验"
                        )
                    }
                }
            }
            else -> InfoBlock("风险识别") {
                if (meeting.risks.isEmpty()) {
                    Text("还没有风险记录", color = Muted, fontSize = 14.sp, lineHeight = 22.sp)
                } else {
                    meeting.risks.forEachIndexed { index, item ->
                        val sourceIndex = meeting.sourceIndexForOrNull(
                            timestamp = item.sourceTimestamp,
                            text = listOf(item.title, item.description, item.recommendation, item.source).joinToString(" ")
                        )
                        RiskInsightItem(
                            title = item.title,
                            level = item.level.ifBlank { "未标级" },
                            desc = listOf(item.description, item.recommendation, sourceIndex?.let { "来源：${meeting.transcripts.getOrNull(it)?.timeRangeLabel}" } ?: "来源待核验").filter { it.isNotBlank() }.joinToString(" · "),
                            onSource = sourceIndex?.let { { onSource(it) } }
                        )
                    }
                }
            }
            }
        }
        }

        if (showContentBackToTop) {
            Surface(
                color = Brand,
                contentColor = Color.White,
                shape = CircleShape,
                shadowElevation = 6.dp,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 22.dp, bottom = 22.dp)
                    .size(44.dp)
            ) {
                IconButton(onClick = { scope.launch { contentListState.animateScrollToItem(0) } }) {
                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "回到顶部")
                }
            }
        }
    }
    if (showRegenerateConfirm) {
        AlertDialog(
            onDismissRequest = { if (!minutesRefreshing) showRegenerateConfirm = false },
            title = { Text("更新纪要") },
            text = {
                Text(
                    "将根据当前会议内容重新生成摘要、议题、决策、风险和待办。已完成的待办会保留，重复的新待办不会新增。处理可能需要一些时间，期间可以继续使用应用。",
                    color = Ink,
                    fontSize = 14.sp,
                    lineHeight = 22.sp
                )
            },
            confirmButton = {
                GradientActionButton(
                    text = "开始更新",
                    onClick = {
                        showRegenerateConfirm = false
                        onRegenerateMinutes()
                    },
                    enabled = !minutesRefreshing,
                    modifier = Modifier.width(104.dp)
                )
            },
            dismissButton = {
                TextButton(onClick = { showRegenerateConfirm = false }, enabled = !minutesRefreshing) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
private fun DetailTabChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (selected) BrandSoft else Color.White,
        shape = RoundedCornerShape(999.dp),
        border = BorderStroke(1.dp, if (selected) Brand else Line),
        modifier = Modifier
            .height(44.dp)
            .clickable(onClick = onClick)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = 10.dp)
        ) {
            Text(
                label,
                color = if (selected) Brand else Ink,
                fontSize = 14.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ConfirmationStatusCard(pending: Boolean, onConfirm: () -> Unit) {
    val bg = if (pending) Color(0xFFFFF8E6) else BrandSoft
    val border = if (pending) Color(0xFFE8C66A) else Color(0xFFC8E8E4)
    val title = if (pending) "确认状态：待确认" else "确认状态：已确认"
    val desc = if (pending) "转写和纪要需要人工确认后再分享或归档。" else "会议内容已完成人工确认。"
    Surface(
        color = bg,
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, border),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(desc, color = Muted, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 4.dp))
            }
            if (pending) {
                GradientActionButton(
                    text = "确认",
                    onClick = onConfirm,
                    modifier = Modifier.padding(start = 12.dp).height(48.dp)
                )
            }
        }
    }
}

@Composable
private fun InfoCell(label: String, value: String, modifier: Modifier) {
    Surface(color = Color.White, shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, Line), modifier = modifier) {
        Column(Modifier.height(82.dp).padding(12.dp)) {
            Text(label, color = Muted, fontSize = 12.sp, maxLines = 1)
            Text(
                value,
                color = Ink,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 19.sp,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

@Composable
private fun OverviewPill(value: String, label: String, modifier: Modifier) {
    Surface(color = BrandSoft, shape = RoundedCornerShape(18.dp), modifier = modifier) {
        Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, color = Brand, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Text(label, color = Muted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun InsightItem(title: String, tag: String, desc: String, onSource: (() -> Unit)?) {
    Surface(color = Color.White, shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, Line), modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(title, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), lineHeight = 22.sp)
                Pill(tag, if (tag.contains("风险")) Color(0xFFFFECE8) else BrandSoft, if (tag.contains("风险")) Danger else Brand)
            }
            Text(desc, color = Muted, fontSize = 13.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 8.dp))
            if (onSource == null) {
                Text("来源待核验", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            } else {
                SourceButton("查看来源", onSource)
            }
        }
    }
}

@Composable
private fun RiskInsightItem(title: String, level: String, desc: String, onSource: (() -> Unit)?) {
    Surface(color = Color.White, shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, Line), modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(title, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), lineHeight = 22.sp)
                Pill(level, Color(0xFFFFECE8), Danger)
            }
            Text(desc.ifBlank { "还没有风险说明" }, color = Muted, fontSize = 13.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 8.dp))
            if (onSource == null) {
                Text("来源待核验", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            } else {
                SourceButton("查看来源", onSource)
            }
        }
    }
}

@Composable
private fun ActionItem(
    title: String,
    meta: String,
    onDetail: (() -> Unit)? = null,
    onSource: (() -> Unit)?,
    missingSourceText: String = "来源待核验"
) {
    Surface(
        color = Color.White,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, Line),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp, start = 4.dp, end = 4.dp)
            .clickable(enabled = onDetail != null) { onDetail?.invoke() }
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
            Text(title, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold, lineHeight = 21.sp)
            Text(meta, color = Muted, fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 6.dp))
            if (onSource == null) {
                Text(missingSourceText, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            } else {
                SourceButton("查看来源", onSource)
            }
        }
    }
}

private fun Meeting.sourceIndexForOrNull(timestamp: String? = null, text: String = ""): Int? {
    if (transcripts.isEmpty()) return null
    val clean = timestamp?.trim().orEmpty()
    val byTime = if (clean.isBlank()) {
        -1
    } else {
        transcripts.indexOfFirst { segment ->
            segment.timestamp == clean || segment.timeRangeLabel.contains(clean)
        }
    }
    if (byTime >= 0) return byTime.coerceIn(0, transcripts.lastIndex)
    val cleanText = text.trim()
    if (cleanText.isBlank()) return null
    val byText = transcripts.indexOfFirst { segment ->
        val left = segment.text.take(24)
        val right = cleanText.take(24)
        left.isNotBlank() && (left in cleanText || right in segment.text)
    }
    return byText.takeIf { it >= 0 }?.coerceIn(0, transcripts.lastIndex)
}

private fun Meeting.sourceIndexFor(timestamp: String? = null, text: String = "", fallback: Int = 0): Int {
    if (transcripts.isEmpty()) return 0
    val clean = timestamp?.trim().orEmpty()
    val byTime = if (clean.isBlank()) {
        -1
    } else {
        transcripts.indexOfFirst { segment ->
            segment.timestamp == clean || segment.timeRangeLabel.contains(clean)
        }
    }
    if (byTime >= 0) return byTime.coerceIn(0, transcripts.lastIndex)
    val cleanText = text.trim()
    val byText = if (cleanText.isBlank()) {
        -1
    } else {
        transcripts.indexOfFirst { segment ->
            val left = segment.text.take(24)
            val right = cleanText.take(24)
            left.isNotBlank() && (left in cleanText || right in segment.text)
        }
    }
    return (if (byText >= 0) byText else fallback).coerceIn(0, transcripts.lastIndex)
}

@Composable
private fun ProgressLine(progress: Float, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFFEDEFF1))) {
        Box(
            Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .height(7.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Brand)
        )
    }
}

private fun Float.progressLabel(): String {
    return "${coerceIn(0f, 100f).roundToInt()}%"
}

@Composable
private fun LiveDot(text: String, active: Boolean = true, contentColor: Color = Color.White) {
    val transition = rememberInfiniteTransition(label = "live-dot")
    val pulse by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(durationMillis = 780), repeatMode = RepeatMode.Reverse),
        label = "live-dot-alpha"
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(Danger.copy(alpha = if (active) pulse else 0.42f)))
        Text(text, color = contentColor, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
fun TasksScreen(
    todos: List<TodoItem>,
    localTasks: List<MeetingTask>,
    currentUserName: String,
    mineOnly: Boolean,
    filter: TodoFilter?,
    todayCount: Int,
    overdueCount: Int,
    onBackHome: () -> Unit,
    onTask: (MeetingTask) -> Unit,
    onDetail: (String) -> Unit,
    onDeleteTask: (MeetingTask) -> Unit,
    onMineOnly: () -> Unit,
    onFilter: (TodoFilter) -> Unit,
    onRefresh: () -> Unit,
    onTodoDetail: (TodoItem) -> Unit,
    onTodoSource: (TodoItem) -> Unit
) {
    val layout = rememberPhoneLayoutSpec()
    val focusManager = LocalFocusManager.current
    var query by remember { mutableStateOf("") }
    val cleanQuery = query.trim()
    fun submitTodoSearch() {
        query = cleanQuery
        focusManager.clearFocus()
    }
    val visibleLocalTasks = localTasks.filter {
        it.status == MeetingTaskStatus.WaitingProcess ||
            it.status == MeetingTaskStatus.Processing ||
            it.status == MeetingTaskStatus.Failed
    }.filter {
        cleanQuery.isBlank() ||
            it.title.contains(cleanQuery, ignoreCase = true)
    }
    val activeTodos = todos.filter { it.effectiveStatus != TodoStatus.Canceled }
    val queryMatchedTodos = activeTodos.filter { item -> item.matchesTodoQuery(cleanQuery) }
    val filteredTodos = activeTodos
        .let { queryMatchedTodos }
        .filter { item -> !mineOnly || item.assigneeLabel.matchesCurrentUserName(currentUserName) }
        .filter { item ->
            when (filter) {
                TodoFilter.Today -> item.isDueToday()
                TodoFilter.Overdue -> item.isOverdue()
                TodoFilter.PendingConfirm -> item.effectiveStatus == TodoStatus.PendingConfirm
                null -> true
            }
        }
    val pendingTodos = filteredTodos
        .filter { it.effectiveStatus.active }
        .sortedWith(
            compareByDescending<TodoItem> { it.priority.todoPriorityWeight() }
                .thenByDescending { it.isOverdue() }
                .thenBy { it.dueAtMillis ?: Long.MAX_VALUE }
                .thenBy { it.title }
        )
    val doneTodos = filteredTodos
        .filter { it.effectiveStatus == TodoStatus.Done }
        .sortedByDescending { it.completedAtMillis ?: 0L }
    val myTodoCount = activeTodos.count { it.effectiveStatus.active && it.assigneeLabel.matchesCurrentUserName(currentUserName) }
    val pendingConfirmCount = activeTodos.count { it.effectiveStatus == TodoStatus.PendingConfirm }
    fun activateMineTab() {
        if (mineOnly && filter == null) {
            onMineOnly()
            return
        }
        filter?.let { onFilter(it) }
        if (!mineOnly) {
            onMineOnly()
        }
    }
    fun activateFilterTab(target: TodoFilter) {
        if (mineOnly) {
            onMineOnly()
        }
        onFilter(target)
    }
    SmartPageBackground {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = layout.horizontalPadding,
                end = layout.horizontalPadding,
                top = if (layout.compactWidth) 14.dp else 18.dp,
                bottom = if (layout.compactWidth || layout.shortHeight) 100.dp else 112.dp
            ),
            verticalArrangement = Arrangement.spacedBy(if (layout.compactWidth) 12.dp else 14.dp)
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    SmartRoundIconButton(Icons.Filled.ArrowBack, "返回会议页", onClick = onBackHome, tint = Ink)
                    Text(
                        "待办任务",
                        color = Ink,
                        fontSize = if (layout.compactWidth) 22.sp else 23.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    SmartRoundIconButton(Icons.Filled.Refresh, "刷新", onClick = onRefresh, tint = Brand)
                }
            }
            item {
                Surface(
                    color = Color.White.copy(alpha = 0.88f),
                    shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.74f)),
                    shadowElevation = 2.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .padding(horizontal = 15.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.Search, contentDescription = null, tint = Muted, modifier = Modifier.size(19.dp))
                        BasicTextField(
                            value = query,
                            onValueChange = { query = it.take(40) },
                            singleLine = true,
                            textStyle = TextStyle(color = Ink, fontSize = 14.sp),
                            cursorBrush = SolidColor(Brand),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { submitTodoSearch() }),
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 9.dp),
                            decorationBox = { innerTextField ->
                                Box(contentAlignment = Alignment.CenterStart) {
                                    if (query.isBlank()) {
                                        Text("搜索标题、截止时间、负责人", color = Muted, fontSize = 14.sp)
                                    }
                                    innerTextField()
                                }
                            }
                        )
                        TextButton(
                            onClick = { submitTodoSearch() },
                            enabled = cleanQuery.isNotEmpty(),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
                            modifier = Modifier.height(40.dp)
                        ) {
                            Text(
                                "搜索",
                                color = if (cleanQuery.isNotEmpty()) Brand else Muted,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
            item {
                TodoStatsPanel(
                    overdueCount = overdueCount,
                    todayCount = todayCount,
                    myCount = myTodoCount,
                    pendingConfirmCount = pendingConfirmCount,
                    urgentTodo = activeTodos
                        .filter { it.effectiveStatus.active }
                        .sortedWith(compareByDescending<TodoItem> { it.isOverdue() }.thenBy { it.dueAtMillis ?: Long.MAX_VALUE })
                        .firstOrNull(),
                    onUrgent = { todo -> onTodoDetail(todo) }
                )
            }
        if (visibleLocalTasks.isNotEmpty()) {
            item {
                InfoBlock("本地处理任务") {
                    visibleLocalTasks.forEach { task ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                            RowItem(
                                Icons.Filled.UploadFile,
                                task.title,
                                listOfNotNull(task.status.label, task.sizeLabel).joinToString(" · "),
                                onClick = { onTask(task) }
                            )
                            if (task.status == MeetingTaskStatus.Processing) {
                                ProgressLine(task.progressPercent / 100f, Modifier.padding(start = 60.dp, end = 8.dp, top = 6.dp))
                                Text(task.progressLabel ?: task.progressPercent.progressLabel(), color = Muted, fontSize = 12.sp, modifier = Modifier.padding(start = 60.dp, top = 4.dp))
                            }
                            }
                            IconButton(
                                onClick = { onDeleteTask(task) },
                                enabled = task.status != MeetingTaskStatus.Processing
                            ) {
                                Icon(
                                    Icons.Filled.DeleteOutline,
                                    contentDescription = "删除任务",
                                    tint = if (task.status == MeetingTaskStatus.Processing) Muted.copy(alpha = 0.45f) else Danger
                                )
                            }
                        }
                    }
                }
            }
        }
            item {
                TodoListPanel(
                    pendingTodos = pendingTodos,
                    doneTodos = doneTodos,
                    emptyText = when {
                        cleanQuery.isNotBlank() -> "没有匹配的待办"
                        activeTodos.isEmpty() -> "还没有待办事项"
                        else -> "当前筛选下没有待办"
                    },
                    mineSelected = mineOnly && filter == null,
                    todaySelected = !mineOnly && filter == TodoFilter.Today,
                    overdueSelected = !mineOnly && filter == TodoFilter.Overdue,
                    pendingConfirmSelected = !mineOnly && filter == TodoFilter.PendingConfirm,
                    onMine = ::activateMineTab,
                    onToday = { activateFilterTab(TodoFilter.Today) },
                    onOverdue = { activateFilterTab(TodoFilter.Overdue) },
                    onPendingConfirm = { activateFilterTab(TodoFilter.PendingConfirm) },
                    onTodoDetail = onTodoDetail,
                    onTodoSource = onTodoSource
                )
            }
    }
    }
}

private fun TodoItem.matchesTodoQuery(keyword: String): Boolean {
    if (keyword.isBlank()) return true
    return listOfNotNull(
        title,
        assigneeLabel,
        dueLabel
    ).any { text -> text.contains(keyword, ignoreCase = true) }
}

@Composable
private fun TodoStatsPanel(
    overdueCount: Int,
    todayCount: Int,
    myCount: Int,
    pendingConfirmCount: Int,
    urgentTodo: TodoItem?,
    onUrgent: (TodoItem) -> Unit
) {
    SmartGradientPanel(
        modifier = Modifier.fillMaxWidth(),
        radius = 16.dp,
        brush = Brush.horizontalGradient(listOf(Color(0xFF5AC8FA), Color(0xFF6F9BFF), Color(0xFF8D7DFF)))
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth()) {
                TodoStatNumber(overdueCount.toString(), "已逾期", Modifier.weight(1f))
                TodoStatNumber(todayCount.toString(), "今日到期", Modifier.weight(1f))
                TodoStatNumber(myCount.toString(), "我负责", Modifier.weight(1f))
                TodoStatNumber(pendingConfirmCount.toString(), "待确认", Modifier.weight(1f))
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.24f)))
            if (urgentTodo != null) {
                Surface(
                    color = Color.White.copy(alpha = 0.92f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().clickable { onUrgent(urgentTodo) }
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TodoSmallTag(if (urgentTodo.isOverdue()) "超时" else "待办", Color(0xFFFFECE8), Danger)
                            Text(urgentTodo.dueLabel ?: "截止待补充", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(start = 10.dp).weight(1f))
                            Surface(color = Brand, shape = RoundedCornerShape(999.dp)) {
                                Text("去处理", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp))
                            }
                        }
                        Text(urgentTodo.title, color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(urgentTodo.description.ifBlank { urgentTodo.meetingTitle ?: "待补充说明" }, color = Muted, fontSize = 14.sp, lineHeight = 20.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            } else {
                Text("暂无紧急待办", color = Color.White.copy(alpha = 0.86f), fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun TodoStatNumber(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(value, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(label, color = Color.White.copy(alpha = 0.90f), fontSize = 13.sp, maxLines = 1)
    }
}

@Composable
private fun TodoPrototypeTab(label: String, icon: ImageVector, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val color = if (selected) Brand else Muted
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Icon(icon, contentDescription = label, tint = color, modifier = Modifier.size(22.dp))
        Text(label, color = color, fontSize = 13.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
        Box(
            Modifier
                .height(3.dp)
                .fillMaxWidth(0.46f)
                .clip(RoundedCornerShape(99.dp))
                .background(if (selected) Brand else Color.Transparent)
        )
    }
}

@Composable
private fun TodoListPanel(
    pendingTodos: List<TodoItem>,
    doneTodos: List<TodoItem>,
    emptyText: String,
    mineSelected: Boolean,
    todaySelected: Boolean,
    overdueSelected: Boolean,
    pendingConfirmSelected: Boolean,
    onMine: () -> Unit,
    onToday: () -> Unit,
    onOverdue: () -> Unit,
    onPendingConfirm: () -> Unit,
    onTodoDetail: (TodoItem) -> Unit,
    onTodoSource: (TodoItem) -> Unit
) {
    SmartWhiteCard(radius = 16.dp, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TodoPrototypeTab("我负责", Icons.Filled.Person, mineSelected, Modifier.weight(1f), onClick = onMine)
            TodoPrototypeTab("今日到期", Icons.Filled.CalendarMonth, todaySelected, Modifier.weight(1f), onClick = onToday)
            TodoPrototypeTab("已逾期", Icons.Filled.Refresh, overdueSelected, Modifier.weight(1f), onClick = onOverdue)
            TodoPrototypeTab("待确认", Icons.Filled.Article, pendingConfirmSelected, Modifier.weight(1f), onClick = onPendingConfirm)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Line.copy(alpha = 0.70f)))
        if (pendingTodos.isEmpty() && doneTodos.isEmpty()) {
            Text(emptyText, color = Muted, fontSize = 14.sp, lineHeight = 22.sp, modifier = Modifier.padding(vertical = 18.dp))
        }
        if (pendingTodos.isNotEmpty()) {
            pendingTodos.forEachIndexed { index, item ->
                TodoActionRow(
                    item = item,
                    onDetail = { onTodoDetail(item) },
                    onSource = { onTodoSource(item) }
                )
                if (index < pendingTodos.lastIndex) {
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Line.copy(alpha = 0.55f)))
                }
            }
        }
        if (doneTodos.isNotEmpty()) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(Line.copy(alpha = 0.70f)))
            Text("已完成", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            doneTodos.forEachIndexed { index, item ->
                TodoActionRow(
                    item = item,
                    onDetail = { onTodoDetail(item) },
                    onSource = { onTodoSource(item) }
                )
                if (index < doneTodos.lastIndex) {
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Line.copy(alpha = 0.55f)))
                }
            }
        }
    }
}

@Composable
private fun TodoActionRow(
    item: TodoItem,
    onDetail: () -> Unit,
    onSource: () -> Unit
) {
    val compact = LocalConfiguration.current.screenWidthDp < 400
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onDetail)
            .padding(horizontal = if (compact) 2.dp else 4.dp, vertical = if (compact) 11.dp else 13.dp),
        verticalAlignment = Alignment.Top
    ) {
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                Text(item.title, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val assigneeText = item.assigneeLabel?.let { "负责人 $it" } ?: "负责人待补充"
                val dueText = item.dueLabel?.let { "截止 $it" } ?: "截止待补充"
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    Text(
                        assigneeText,
                        color = Muted,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        dueText,
                        color = Muted,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        maxLines = 2
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                    TodoSmallTag(if (item.isOverdue()) "逾期" else item.effectiveStatus.label, if (item.isOverdue()) Color(0xFFFFECE8) else BrandSoft, if (item.isOverdue()) Danger else Brand)
                    TodoPriorityTag(item.priority)
                }
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(
                    color = Color.Transparent,
                    shape = RoundedCornerShape(999.dp),
                    border = BorderStroke(1.dp, Brand.copy(alpha = 0.28f)),
                    modifier = Modifier.clickable(onClick = onDetail)
                ) {
                    Text("查看", color = Brand, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 13.dp, vertical = 6.dp))
                }
                TextButton(onClick = onSource, modifier = Modifier.height(32.dp)) {
                    Text("来源", color = Brand, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
    }
}

@Composable
private fun TodoSmallTag(text: String, bg: Color, fg: Color) {
    Surface(color = bg, shape = RoundedCornerShape(999.dp)) {
        Text(
            text,
            color = fg,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
        )
    }
}

@Composable
private fun TodoPriorityTag(priority: String) {
    val normalized = priority.normalizedTodoPriority()
    val colors = when (normalized) {
        TodoPriorityHigh -> Color(0xFFFFECE8) to Danger
        TodoPriorityLow -> Color(0xFFF0F4F5) to Muted
        else -> BrandSoft to Brand
    }
    TodoSmallTag(priority.todoPriorityLabel(), colors.first, colors.second)
}

private fun String?.matchesCurrentUserName(currentUserName: String): Boolean {
    val user = currentUserName.normalizedPersonName()
    val assignee = this.normalizedPersonName()
    return user.isNotBlank() && assignee.isNotBlank() && assignee == user
}

private fun String?.normalizedPersonName(): String {
    return this.orEmpty()
        .trim()
        .replace(Regex("\\s+"), "")
        .removePrefix("负责人")
        .removePrefix("参会人")
        .removeSuffix("负责")
}

@Composable
fun KnowledgeScreen(
    topics: List<KnowledgeTopic>,
    question: String,
    messages: List<KnowledgeChatMessage>,
    loading: Boolean,
    onBackHome: () -> Unit,
    onQuestionChange: (String) -> Unit,
    onAsk: (String) -> Unit,
    onCancel: () -> Unit,
    onRetryQuestion: (String) -> Unit,
    onSourceClick: (RemoteKnowledgeSource) -> Unit,
    onTopicClick: (String) -> Unit
) {
    val layout = rememberPhoneLayoutSpec()
    var savedListIndex by rememberSaveable { mutableStateOf(0) }
    var savedListOffset by rememberSaveable { mutableStateOf(0) }
    val listState = rememberLazyListState(savedListIndex, savedListOffset)
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { (index, offset) ->
                savedListIndex = index
                savedListOffset = offset
            }
    }
    SmartPageBackground {
        Box(Modifier.fillMaxSize().statusBarsPadding()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = layout.horizontalPadding,
                    end = layout.horizontalPadding,
                    top = if (layout.compactWidth) 14.dp else 18.dp,
                    bottom = 118.dp
                ),
                verticalArrangement = Arrangement.spacedBy(if (layout.compactWidth) 14.dp else 16.dp)
            ) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        SmartRoundIconButton(Icons.Filled.ArrowBack, "返回会议页", onClick = onBackHome, tint = Ink)
                        Text(
                            "知识库",
                            color = Ink,
                            fontSize = if (layout.compactWidth) 22.sp else 23.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.size(40.dp))
                    }
                }
                item {
                    Text(
                        "会议、待办、风险一起查",
                        color = BrandDark,
                        fontSize = 15.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                item {
                    CompactRecommendedQuestions(onAsk)
                }
                if (messages.isEmpty()) {
                    item {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 18.dp, bottom = 8.dp)
                        ) {
                            Text("有什么我能帮你的吗？", color = Brand, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                            Text("—— 不妨先随意看看 ——", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                        }
                    }
                    if (topics.isEmpty()) {
                        item {
                            SmartWhiteCard(radius = 16.dp, modifier = Modifier.fillMaxWidth()) {
                                Text("输入问题后，会从已转写会议和云端知识内容里检索回答。", color = Muted, fontSize = 14.sp, lineHeight = 22.sp)
                            }
                        }
                    } else {
                        items(topics.take(5), key = { it.meetingId }) { topic ->
                            KnowledgeTopicCard(topic = topic, onClick = { onTopicClick(topic.meetingId) })
                        }
                    }
                }
                items(messages, key = { it.id }) { message ->
                    KnowledgeChatBubble(message, onSourceClick, onRetryQuestion)
                }
                if (loading) {
                    item {
                        KnowledgeTypingBubble()
                    }
                }
            }
            Surface(
                color = Color.White.copy(alpha = 0.96f),
                shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.82f)),
                shadowElevation = 8.dp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding()
            ) {
                Row(Modifier.padding(horizontal = layout.horizontalPadding, vertical = if (layout.compactWidth) 13.dp else 15.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(color = Color(0xFFFAFCFF), shape = RoundedCornerShape(999.dp), border = BorderStroke(1.dp, Line), modifier = Modifier.weight(1f).height(52.dp)) {
                        Box(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
                            BasicTextField(
                                value = question,
                                onValueChange = onQuestionChange,
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                enabled = !loading,
                                textStyle = TextStyle(color = Ink, fontSize = 14.sp),
                                cursorBrush = SolidColor(Brand)
                            )
                            if (question.isBlank()) {
                                Text("问会议、待办或风险", color = Color(0xFF7D91A8), fontSize = 14.sp)
                            }
                        }
                    }
                    Button(
                        onClick = { if (loading) onCancel() else onAsk(question) },
                        enabled = loading || question.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = if (loading) Danger else Brand),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                        shape = CircleShape,
                        modifier = Modifier.padding(start = 10.dp).size(52.dp)
                    ) {
                        if (loading) {
                            Icon(Icons.Filled.Stop, contentDescription = "停止回答", tint = Color.White, modifier = Modifier.size(20.dp))
                        } else {
                            Icon(Icons.Filled.Send, contentDescription = "发送", tint = Color.White, modifier = Modifier.size(21.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun KnowledgeTopicCard(topic: KnowledgeTopic, onClick: () -> Unit) {
    SmartWhiteCard(radius = 16.dp, modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SmartGradientIcon(Icons.Filled.Article, tint = Brand)
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text(topic.title, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(topic.subtitle, color = Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 5.dp))
            }
            Surface(color = Color.White, shape = RoundedCornerShape(999.dp), border = BorderStroke(1.dp, Line)) {
                Text("查看", color = Brand, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
            }
        }
    }
}

@Composable
private fun KnowledgeEmptyState(onAsk: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
            Surface(color = Color.White, shape = RoundedCornerShape(22.dp), border = BorderStroke(1.dp, Line), modifier = Modifier.fillMaxWidth(0.76f)) {
                Text(
                    "想查什么会议内容？",
                    color = Ink,
                    fontSize = 15.sp,
                    lineHeight = 22.sp,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
                )
            }
        }
        CompactRecommendedQuestions(onAsk)
    }
}

@Composable
private fun CompactRecommendedQuestions(onAsk: (String) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
    ) {
        RecommendedQuestionButton("本周待办") { onAsk("本周我有哪些待办？") }
        RecommendedQuestionButton("最近风险") { onAsk("最近有哪些风险？") }
        RecommendedQuestionButton("最近决策") { onAsk("最近会议决定了什么？") }
    }
}

@Composable
private fun KnowledgeChatBubble(
    message: KnowledgeChatMessage,
    onSourceClick: (RemoteKnowledgeSource) -> Unit,
    onRetryQuestion: (String) -> Unit
) {
    if (message.role == KnowledgeChatRole.User) {
        KnowledgeUserBubble(message.text)
    } else {
        KnowledgeAssistantBubble(message, onSourceClick, onRetryQuestion)
    }
}

@Composable
private fun KnowledgeUserBubble(question: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(color = Brand, shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth(0.68f)) {
            Text(question, color = Color.White, fontSize = 15.sp, lineHeight = 22.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp))
        }
    }
}

@Composable
private fun KnowledgeAssistantBubble(
    message: KnowledgeChatMessage,
    onSourceClick: (RemoteKnowledgeSource) -> Unit,
    onRetryQuestion: (String) -> Unit
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Surface(
            color = if (message.failed) Color(0xFFFFF8F7) else Color.White,
            shape = RoundedCornerShape(22.dp),
            border = BorderStroke(1.dp, if (message.failed) Color(0xFFFFC9C1) else Line),
            modifier = Modifier.fillMaxWidth(0.84f)
        ) {
            Column(Modifier.padding(14.dp)) {
                val showSources = !message.failed && message.text.trim().trimEnd('。') != "未检索到相关内容"
                var sourcesExpanded by remember(message.id, message.sources.size) { mutableStateOf(false) }
                val collapsedSourceCount = 2
                val expandedSourceLimit = 10
                val visibleSources = if (sourcesExpanded) {
                    message.sources.take(expandedSourceLimit)
                } else {
                    message.sources.take(collapsedSourceCount)
                }
                Text(
                    message.text,
                    color = if (message.failed) Danger else Ink,
                    fontSize = 14.sp,
                    lineHeight = 22.sp
                )
                val retryQuestion = message.retryQuestion?.trim().orEmpty()
                if (retryQuestion.isNotBlank()) {
                    Surface(
                        color = BrandSoft,
                        shape = RoundedCornerShape(999.dp),
                        modifier = Modifier.padding(top = 10.dp).clickable { onRetryQuestion(retryQuestion) }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
                        ) {
                            Icon(Icons.Filled.Edit, contentDescription = null, tint = Brand, modifier = Modifier.size(15.dp))
                            Text("重新编辑", color = Brand, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 5.dp))
                        }
                    }
                }
                if (showSources && message.sources.isNotEmpty()) {
                    visibleSources.forEach { source ->
                        KnowledgeSourcePill(source, onClick = { onSourceClick(source) })
                    }
                    if (message.sources.size > collapsedSourceCount) {
                        KnowledgeSourcesToggle(
                            expanded = sourcesExpanded,
                            total = message.sources.size,
                            expandedLimit = expandedSourceLimit,
                            onClick = { sourcesExpanded = !sourcesExpanded }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun KnowledgeTypingBubble() {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Surface(color = Color.White, shape = RoundedCornerShape(22.dp), border = BorderStroke(1.dp, Line)) {
            Text("正在回答…", color = Muted, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
        }
    }
}

@Composable
private fun KnowledgeSourcePill(source: RemoteKnowledgeSource, onClick: () -> Unit) {
    val sourceLabel = listOf(source.title, source.meetingDate, source.speaker, source.timestamp)
        .mapNotNull { it?.trim() }
        .filter { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
        .joinToString(" · ")
    if (sourceLabel.isBlank()) return
    Surface(color = BrandSoft, shape = RoundedCornerShape(999.dp), modifier = Modifier.padding(top = 10.dp).clickable(onClick = onClick)) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Article, contentDescription = null, tint = Brand, modifier = Modifier.size(15.dp))
            Text(
                text = sourceLabel,
                color = Brand,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 5.dp)
            )
        }
    }
}

@Composable
private fun KnowledgeSourcesToggle(expanded: Boolean, total: Int, expandedLimit: Int, onClick: () -> Unit) {
    val label = when {
        expanded -> "收起来源"
        total > expandedLimit -> "展开前 $expandedLimit 个来源"
        else -> "展开 $total 个来源"
    }
    Surface(
        color = Color.White,
        shape = RoundedCornerShape(999.dp),
        border = BorderStroke(1.dp, Line),
        modifier = Modifier.padding(top = 10.dp).fillMaxWidth().height(44.dp).clickable(onClick = onClick)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(label, color = Brand, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun RecommendedQuestionButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        color = Color.White,
        shape = RoundedCornerShape(999.dp),
        border = BorderStroke(1.dp, Line),
        modifier = modifier.clickable(onClick = onClick)
    ) {
        Text(text, color = Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp))
    }
}

private enum class AuthMode(val label: String) {
    Sms("验证码登录"),
    Password("密码登录")
}

private enum class AuthFlow {
    Login,
    Register,
    ResetPassword,
    SetupPassword
}

private const val LOGIN_PASSWORD_MIN_LENGTH = 8

@Composable
private fun AuthModeTabs(
    mode: AuthMode,
    onModeChange: (AuthMode) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(999.dp))
            .background(Color(0xFFF1F6FD))
            .padding(4.dp)
    ) {
        AuthMode.values().forEach { item ->
            val selected = item == mode
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (selected) Color.White else Color.Transparent)
                    .clickable(enabled = enabled && !selected) { onModeChange(item) }
            ) {
                Text(
                    item.label,
                    color = if (selected) Brand else Muted,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun LoginScreen(
    loginBusy: Boolean,
    smsSending: Boolean,
    loginStatusText: String?,
    agreementAccepted: Boolean,
    pendingRegistrationPhone: String?,
    pendingRegistrationCode: String,
    pendingRegistrationSignal: Int,
    pendingLoginPhone: String?,
    pendingLoginSignal: Int,
    onAgreementAcceptedChange: (Boolean) -> Unit,
    onSendLoginSmsCode: (String, () -> Unit) -> Unit,
    onSendRegisterSmsCode: (String, () -> Unit) -> Unit,
    onSendPasswordResetSmsCode: (String, () -> Unit) -> Unit,
    onLogin: (String, String) -> Unit,
    onPasswordLogin: (String, String) -> Unit,
    onRegister: (String, String, String) -> Unit,
    onResetPassword: (String, String, String) -> Unit,
    onUserAgreement: () -> Unit,
    onPrivacyPolicy: () -> Unit,
    onAgreementRequired: () -> Unit
) {
    val layout = rememberPhoneLayoutSpec()
    val imeVisible = WindowInsets.isImeVisible
    val density = LocalDensity.current
    var phone by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var authMode by remember { mutableStateOf(AuthMode.Sms) }
    var authFlow by remember { mutableStateOf(AuthFlow.Login) }
    var resendSeconds by remember { mutableStateOf(0) }
    var formStatusText by remember { mutableStateOf<String?>(null) }
    var passwordHintVisible by remember { mutableStateOf(false) }
    val headerHeight = headerHeightForLayout(layout)
    LaunchedEffect(resendSeconds) {
        if (resendSeconds > 0) {
            delay(1_000)
            resendSeconds -= 1
        }
    }
    LaunchedEffect(pendingRegistrationSignal) {
        if (pendingRegistrationSignal > 0 && !pendingRegistrationPhone.isNullOrBlank()) {
            phone = pendingRegistrationPhone
            code = pendingRegistrationCode
            password = ""
            confirmPassword = ""
            passwordHintVisible = false
            authMode = AuthMode.Sms
            authFlow = AuthFlow.SetupPassword
        }
    }
    LaunchedEffect(pendingLoginSignal) {
        if (pendingLoginSignal > 0 && !pendingLoginPhone.isNullOrBlank()) {
            phone = pendingLoginPhone
            code = ""
            password = ""
            confirmPassword = ""
            passwordHintVisible = false
            authMode = AuthMode.Sms
            authFlow = AuthFlow.Login
            resendSeconds = 0
        }
    }
    LaunchedEffect(authMode, authFlow) {
        passwordHintVisible = false
    }
    val cleanPassword = password.trim()
    val cleanConfirmPassword = confirmPassword.trim()
    val passwordReady = cleanPassword.length >= LOGIN_PASSWORD_MIN_LENGTH
    val confirmPasswordEntered = cleanConfirmPassword.isNotEmpty()
    val codeReady = code.trim().length == 6
    val phoneReady = phone.trim().length >= 11
    val canSubmit = agreementAccepted && !loginBusy && phoneReady && when {
        authFlow == AuthFlow.Login && authMode == AuthMode.Sms -> codeReady
        authFlow == AuthFlow.Login && authMode == AuthMode.Password -> passwordReady
        else -> codeReady && passwordReady && confirmPasswordEntered
    }
    val displayedStatusText = formStatusText ?: loginStatusText
    val passwordHintText = if (
        passwordHintVisible &&
        authFlow != AuthFlow.Login &&
        cleanPassword.isNotEmpty() &&
        !passwordReady
    ) {
        "密码至少 ${LOGIN_PASSWORD_MIN_LENGTH} 位"
    } else {
        null
    }
    val busy = loginBusy || smsSending
    val submitText = when {
        authFlow == AuthFlow.ResetPassword -> if (loginBusy) "重置中..." else "重置并登录"
        authFlow == AuthFlow.Register || authFlow == AuthFlow.SetupPassword -> if (loginBusy) "注册中..." else "注册并登录"
        authMode == AuthMode.Password -> if (loginBusy) "登录中..." else "密码登录"
        else -> if (loginBusy) "登录中..." else "登录"
    }
    val flowTitle = when (authFlow) {
        AuthFlow.Register -> "免费注册账号"
        AuthFlow.ResetPassword -> "忘记密码"
        AuthFlow.SetupPassword -> "设置登录密码"
        AuthFlow.Login -> ""
    }
    val flowSubtitle = when (authFlow) {
        AuthFlow.Register -> "验证手机号后设置登录密码"
        AuthFlow.ResetPassword -> "通过手机号验证码重置密码"
        AuthFlow.SetupPassword -> "该手机号未注册，请设置密码完成注册"
        AuthFlow.Login -> ""
    }
    fun sendCode() {
        if (agreementAccepted) {
            val onSent = { resendSeconds = 60 }
            when (authFlow) {
                AuthFlow.ResetPassword -> onSendPasswordResetSmsCode(phone, onSent)
                AuthFlow.Register,
                AuthFlow.SetupPassword -> onSendRegisterSmsCode(phone, onSent)
                AuthFlow.Login -> onSendLoginSmsCode(phone, onSent)
            }
        } else {
            onAgreementRequired()
        }
    }
    fun submit() {
        if (!agreementAccepted) {
            onAgreementRequired()
            return
        }
        if (authFlow != AuthFlow.Login && cleanPassword != cleanConfirmPassword) {
            formStatusText = "两次输入的密码不一致"
            return
        }
        formStatusText = null
        when {
            authFlow == AuthFlow.ResetPassword -> onResetPassword(phone, code, cleanPassword)
            authFlow == AuthFlow.Register || authFlow == AuthFlow.SetupPassword -> onRegister(phone, code, cleanPassword)
            authMode == AuthMode.Sms -> onLogin(phone, code)
            authMode == AuthMode.Password -> onPasswordLogin(phone, cleanPassword)
        }
    }
    fun backToLogin() {
        authFlow = AuthFlow.Login
        password = ""
        confirmPassword = ""
        code = ""
        passwordHintVisible = false
    }
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(AppBg)
            .navigationBarsPadding()
    ) {
        var heroHeightPx by remember { mutableStateOf(0) }
        var focusedInputBottomPx by remember { mutableStateOf<Float?>(null) }
        var shiftedContentTopPx by remember { mutableStateOf(0f) }
        fun updateFocusedInputBottom(bottomInRootPx: Float?) {
            focusedInputBottomPx = bottomInRootPx?.let { it - shiftedContentTopPx }
        }
        val cardOverlap = if (layout.shortHeight) 8.dp else 10.dp
        val rootHeightPx = with(density) { maxHeight.toPx() }
        val imeBottomPx = WindowInsets.ime.getBottom(density).toFloat()
        val navigationBottomPx = WindowInsets.navigationBars.getBottom(density).toFloat()
        val keyboardCoverPx = (imeBottomPx - navigationBottomPx).coerceAtLeast(0f)
        val keyboardTopPx = rootHeightPx - keyboardCoverPx
        val keepFocusedFieldAboveKeyboardPx = with(density) { (if (layout.shortHeight) 6.dp else 8.dp).toPx() }
        val focusedBottomPx = focusedInputBottomPx
        val pageShiftPx = if (imeVisible && keyboardCoverPx > 0f && focusedBottomPx != null) {
            (focusedBottomPx + keepFocusedFieldAboveKeyboardPx - keyboardTopPx).coerceAtLeast(0f)
        } else {
            0f
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { translationY = -pageShiftPx }
                .onGloballyPositioned { coordinates ->
                    shiftedContentTopPx = coordinates.boundsInRoot().top
                }
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(if (layout.shortHeight) 130.dp else 190.dp)
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Transparent,
                                BrandSoftCyan.copy(alpha = 0.72f)
                            )
                        )
                    )
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(headerHeight + 58.dp)
                    .background(
                        Brush.verticalGradient(listOf(BrandCyan, Color(0xFF4B7BFF), Brand)),
                        shape = RoundedCornerShape(bottomStart = 36.dp, bottomEnd = 36.dp)
                    )
            )
            Column(modifier = Modifier.fillMaxSize()) {
                Box(Modifier.onSizeChanged { heroHeightPx = it.height }) {
                    LoginHero(layout, headerHeight)
                }
                Surface(
                    color = Color.White,
                    shape = RoundedCornerShape(32.dp),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.82f)),
                    shadowElevation = 10.dp,
                    modifier = Modifier
                        .padding(horizontal = if (layout.extraCompactWidth) 18.dp else 22.dp)
                        .offset(y = -cardOverlap)
                        .fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                start = if (layout.extraCompactWidth) 20.dp else 24.dp,
                                top = if (layout.compactWidth || layout.shortHeight) 20.dp else 22.dp,
                                end = if (layout.extraCompactWidth) 20.dp else 24.dp,
                                bottom = if (layout.shortHeight) 20.dp else 24.dp
                            ),
                        verticalArrangement = Arrangement.spacedBy(if (layout.compactWidth || layout.shortHeight) 14.dp else 16.dp)
                    ) {
                        if (authFlow != AuthFlow.Login) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(flowTitle, color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                    Text(flowSubtitle, color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                                }
                                TextButton(onClick = { backToLogin() }, enabled = !loginBusy) {
                                    Text("返回登录", color = Brand, fontWeight = FontWeight.Bold)
                                }
                            }
                        } else {
                            AuthModeTabs(
                                mode = authMode,
                                onModeChange = {
                                    authMode = it
                                    code = ""
                                    password = ""
                                    confirmPassword = ""
                                },
                                enabled = !loginBusy
                            )
                        }
                        LoginInputField(
                            label = "手机号",
                            value = phone,
                            onValueChange = {
                                formStatusText = null
                                phone = it.filter { char -> char.isDigit() || char == '+' }.take(14)
                            },
                            enabled = !loginBusy,
                            placeholder = "请输入手机号",
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                            onFocusedInputBottomChange = { updateFocusedInputBottom(it) }
                        )
                        if (authFlow != AuthFlow.Login || authMode == AuthMode.Sms) {
                            LoginCodeField(
                                value = code,
                                onValueChange = {
                                    formStatusText = null
                                    code = it.filter { char -> char.isDigit() }.take(6)
                                },
                                enabled = !loginBusy,
                                placeholder = "请输入验证码",
                                sendLabel = if (smsSending) "发送中" else if (resendSeconds > 0) "${resendSeconds}s" else "获取验证码",
                                canSend = !smsSending && resendSeconds == 0 && phoneReady,
                                compact = layout.compactWidth,
                                onSend = { sendCode() },
                                onFocusedInputBottomChange = { updateFocusedInputBottom(it) }
                            )
                        }
                        if (authFlow == AuthFlow.Login && authMode == AuthMode.Password) {
                            LoginInputField(
                                label = "密码",
                                value = password,
                                onValueChange = {
                                    formStatusText = null
                                    password = it.take(32)
                                },
                                enabled = !loginBusy,
                                placeholder = "请输入密码",
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                visualTransformation = PasswordVisualTransformation(),
                                onFocusedInputBottomChange = { updateFocusedInputBottom(it) }
                            )
                        }
                        if (authFlow != AuthFlow.Login) {
                            LoginInputField(
                                label = if (authFlow == AuthFlow.ResetPassword) "新密码" else "密码",
                                value = password,
                                onValueChange = {
                                    formStatusText = null
                                    password = it.take(32)
                                    if (password.trim().length >= LOGIN_PASSWORD_MIN_LENGTH) {
                                        passwordHintVisible = false
                                    }
                                },
                                enabled = !loginBusy,
                                placeholder = "8-32 位字母和数字",
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                visualTransformation = PasswordVisualTransformation(),
                                supportingText = passwordHintText,
                                onFocusLost = {
                                    passwordHintVisible = password.trim().isNotEmpty() &&
                                        password.trim().length < LOGIN_PASSWORD_MIN_LENGTH
                                },
                                onFocusedInputBottomChange = { updateFocusedInputBottom(it) }
                            )
                            LoginInputField(
                                label = "确认密码",
                                value = confirmPassword,
                                onValueChange = {
                                    formStatusText = null
                                    confirmPassword = it.take(32)
                                },
                                enabled = !loginBusy,
                                placeholder = "再次输入密码",
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                visualTransformation = PasswordVisualTransformation(),
                                onFocusedInputBottomChange = { updateFocusedInputBottom(it) }
                            )
                        }
                        if (authFlow == AuthFlow.Login) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                TextButton(
                                    onClick = {
                                        authFlow = AuthFlow.ResetPassword
                                        code = ""
                                        password = ""
                                        confirmPassword = ""
                                    },
                                    enabled = !loginBusy
                                ) {
                                    Text("忘记密码", color = Muted, fontWeight = FontWeight.Medium)
                                }
                                Spacer(Modifier.weight(1f))
                                TextButton(
                                    onClick = {
                                        authFlow = AuthFlow.Register
                                        code = ""
                                        password = ""
                                        confirmPassword = ""
                                    },
                                    enabled = !loginBusy
                                ) {
                                    Text("免费注册账号", color = Brand, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                        if (!displayedStatusText.isNullOrBlank()) {
                            LoginStatusStrip(loginBusy = busy, text = displayedStatusText)
                        }
                        LoginAgreementRow(
                            checked = agreementAccepted,
                            onCheckedChange = onAgreementAcceptedChange,
                            onUserAgreement = onUserAgreement,
                            onPrivacyPolicy = onPrivacyPolicy
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(if (layout.compactWidth || layout.shortHeight) 52.dp else 56.dp)
                                .clip(RoundedCornerShape(999.dp))
                                .background(
                                    if (canSubmit) {
                                        Brush.horizontalGradient(listOf(Brand, Color(0xFF4B7BFF), BrandCyan))
                                    } else {
                                        Brush.horizontalGradient(listOf(Color(0xFFE8EEF8), Color(0xFFE8EEF8)))
                                    }
                                )
                                .clickable(enabled = !loginBusy && (!agreementAccepted || canSubmit)) { submit() },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                submitText,
                                color = if (canSubmit) Color.White else Color(0xFF8A96AA),
                                fontWeight = FontWeight.Bold,
                                fontSize = 17.sp
                            )
                        }
                    }
                }
                Spacer(Modifier.height(if (layout.shortHeight) 18.dp else 28.dp))
            }
        }
    }
}

private fun headerHeightForLayout(layout: PhoneLayoutSpec): Dp = when {
    layout.extraCompactWidth || layout.shortHeight -> 210.dp
    layout.compactWidth -> 236.dp
    else -> 252.dp
}

@Composable
private fun LoginHero(layout: PhoneLayoutSpec, height: Dp) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(height)
            .padding(horizontal = if (layout.extraCompactWidth) 20.dp else 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(
            color = Color.White.copy(alpha = 0.18f),
            shape = RoundedCornerShape(26.dp),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.28f)),
            modifier = Modifier.size(if (layout.compactWidth) 82.dp else 90.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                KunqiongLogo(
                    modifier = Modifier.size(if (layout.compactWidth) 64.dp else 72.dp),
                    corner = 20.dp
                )
            }
        }
        Spacer(Modifier.height(if (layout.compactWidth || layout.shortHeight) 10.dp else 12.dp))
        Text(
            "鲲穹会纪",
            color = Color.White,
            fontSize = if (layout.compactWidth) 28.sp else 30.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(8.dp))
        Surface(
            color = Color.White.copy(alpha = 0.16f),
            shape = RoundedCornerShape(999.dp),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.24f))
        ) {
            Text(
                "鲲穹AI旗下产品",
                color = Color.White.copy(alpha = 0.92f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    }
}

@Composable
private fun LoginInputField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    placeholder: String,
    keyboardOptions: KeyboardOptions,
    modifier: Modifier = Modifier,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    supportingText: String? = null,
    onFocusLost: (() -> Unit)? = null,
    onFocusedInputBottomChange: (Float?) -> Unit = {}
) {
    var hadFocus by remember { mutableStateOf(false) }
    var fieldFocused by remember { mutableStateOf(false) }
    var fieldBottomPx by remember { mutableStateOf(0f) }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Surface(
            color = Color(0xFFF8FBFF),
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, Color(0xFFDCE7F5)),
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp)
                .onGloballyPositioned { coordinates ->
                    fieldBottomPx = coordinates.boundsInRoot().bottom
                    if (fieldFocused) {
                        onFocusedInputBottomChange(fieldBottomPx)
                    }
                }
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                singleLine = true,
                textStyle = TextStyle(color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Medium),
                keyboardOptions = keyboardOptions,
                visualTransformation = visualTransformation,
                modifier = Modifier
                    .fillMaxSize()
                    .onFocusChanged { state ->
                        fieldFocused = state.isFocused
                        if (state.isFocused) {
                            hadFocus = true
                            if (fieldBottomPx > 0f) {
                                onFocusedInputBottomChange(fieldBottomPx)
                            }
                        } else if (hadFocus) {
                            hadFocus = false
                            onFocusedInputBottomChange(null)
                            onFocusLost?.invoke()
                        }
                    }
                    .padding(horizontal = 16.dp),
                decorationBox = { innerTextField ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (value.isBlank()) {
                            Text(placeholder, color = Muted, fontSize = 15.sp)
                        }
                        innerTextField()
                    }
                }
            )
        }
        if (!supportingText.isNullOrBlank()) {
            Text(supportingText, color = Danger, fontSize = 12.sp, lineHeight = 17.sp)
        }
    }
}

@Composable
private fun LoginCodeField(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    placeholder: String,
    sendLabel: String,
    canSend: Boolean,
    compact: Boolean,
    onSend: () -> Unit,
    onFocusedInputBottomChange: (Float?) -> Unit = {}
) {
    var fieldFocused by remember { mutableStateOf(false) }
    var fieldBottomPx by remember { mutableStateOf(0f) }
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("验证码", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Surface(
            color = Color(0xFFF8FBFF),
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, Color(0xFFDCE7F5)),
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp)
                .onGloballyPositioned { coordinates ->
                    fieldBottomPx = coordinates.boundsInRoot().bottom
                    if (fieldFocused) {
                        onFocusedInputBottomChange(fieldBottomPx)
                    }
                }
        ) {
            Row(
                modifier = Modifier.fillMaxSize().padding(start = 16.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    enabled = enabled,
                    singleLine = true,
                    textStyle = TextStyle(color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Medium),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier
                        .weight(1f)
                        .onFocusChanged { state ->
                            fieldFocused = state.isFocused
                            if (state.isFocused) {
                                if (fieldBottomPx > 0f) {
                                    onFocusedInputBottomChange(fieldBottomPx)
                                }
                            } else {
                                onFocusedInputBottomChange(null)
                            }
                        },
                    decorationBox = { innerTextField ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (value.isBlank()) {
                                Text(placeholder, color = Muted, fontSize = 15.sp)
                            }
                            innerTextField()
                        }
                    }
                )
                Box(Modifier.padding(horizontal = 8.dp).width(1.dp).height(22.dp).background(Line))
                TextButton(
                    onClick = onSend,
                    enabled = canSend,
                    shape = RoundedCornerShape(999.dp),
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = Brand,
                        disabledContentColor = Muted.copy(alpha = 0.64f)
                    ),
                    modifier = Modifier.width(if (compact) 88.dp else 96.dp).height(42.dp)
                ) {
                    Text(sendLabel, fontSize = if (compact) 12.sp else 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun LoginAgreementRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onUserAgreement: () -> Unit,
    onPrivacyPolicy: () -> Unit
) {
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val extraCompact = screenWidthDp < 360
    val agreementFontSize = when {
        screenWidthDp < 340 -> 10.sp
        screenWidthDp < 360 -> 10.5.sp
        screenWidthDp < 390 -> 11.sp
        else -> 12.sp
    }
    val plainLabel = if (extraCompact) "已阅读并同意" else "我已阅读并同意"
    val checkboxSize = if (extraCompact) 32.dp else 36.dp
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = CheckboxDefaults.colors(
                checkedColor = Brand,
                uncheckedColor = Muted.copy(alpha = 0.76f),
                checkmarkColor = Color.White
            ),
            modifier = Modifier.size(checkboxSize)
        )
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AgreementPlainText(
                text = plainLabel,
                fontSize = agreementFontSize,
                horizontalPadding = 1.dp,
                onClick = { onCheckedChange(!checked) }
            )
            AgreementLinkText("《用户协议》", agreementFontSize, onUserAgreement)
            AgreementPlainText(
                text = "和",
                fontSize = agreementFontSize,
                horizontalPadding = 0.dp,
                onClick = { onCheckedChange(!checked) }
            )
            AgreementLinkText("《隐私政策》", agreementFontSize, onPrivacyPolicy)
        }
    }
}

@Composable
private fun AgreementPlainText(
    text: String,
    fontSize: androidx.compose.ui.unit.TextUnit,
    horizontalPadding: Dp,
    onClick: () -> Unit
) {
    Text(
        text,
        color = Muted,
        fontSize = fontSize,
        lineHeight = 18.sp,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = horizontalPadding, vertical = 6.dp)
    )
}

@Composable
private fun AgreementLinkText(
    text: String,
    fontSize: androidx.compose.ui.unit.TextUnit,
    onClick: () -> Unit
) {
    Text(
        text,
        color = Brand,
        fontSize = fontSize,
        fontWeight = FontWeight.Bold,
        lineHeight = 18.sp,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 1.dp, vertical = 6.dp)
    )
}

private data class LoginStatusTone(
    val container: Color,
    val border: Color,
    val icon: Color,
    val text: Color
)

private fun loginStatusTone(loginBusy: Boolean, text: String): LoginStatusTone {
    val isError = listOf("失败", "错误", "异常", "不一致", "网络连接失败", "服务器维护").any { text.contains(it) }
    return when {
        isError -> LoginStatusTone(
            container = Color(0xFFFFF0ED),
            border = Color(0xFFFFC9BE),
            icon = Danger,
            text = Color(0xFFC23D2A)
        )
        loginBusy -> LoginStatusTone(
            container = Color(0xFFFFF7E6),
            border = Color(0xFFFFE1AD),
            icon = Color(0xFFB87600),
            text = Color(0xFFB87600)
        )
        else -> LoginStatusTone(
            container = Color(0xFFEAF8F5),
            border = Color(0xFFCBECE6),
            icon = Brand,
            text = BrandDark
        )
    }
}

@Composable
private fun LoginStatusStrip(loginBusy: Boolean, text: String) {
    val tone = loginStatusTone(loginBusy, text)
    Surface(
        color = tone.container,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, tone.border),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.GppGood,
                contentDescription = null,
                tint = tone.icon,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text,
                color = tone.text,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 18.sp,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }
}

@Composable
fun ProfileScreen(
    cloudUser: CloudUser?,
    membershipProfile: MembershipProfile,
    profileName: String,
    cloudSyncEnabled: Boolean,
    cloudSyncInProgress: Boolean,
    cloudSyncStatusText: String,
    cloudSyncFocusRequest: Int = 0,
    onCloudSyncFocusConsumed: () -> Unit = {},
    unsyncedMeetingCount: Int,
    speakerProfileCount: Int,
    meetingCount: Int,
    todoCount: Int,
    loginBusy: Boolean,
    smsSending: Boolean,
    loginStatusText: String?,
    agreementAccepted: Boolean,
    pendingRegistrationPhone: String?,
    pendingRegistrationCode: String,
    pendingRegistrationSignal: Int,
    pendingLoginPhone: String?,
    pendingLoginSignal: Int,
    onAgreementAcceptedChange: (Boolean) -> Unit,
    permissionStatus: AppPermissionStatus,
    onBackHome: () -> Unit,
    onSendLoginSmsCode: (String, () -> Unit) -> Unit,
    onSendRegisterSmsCode: (String, () -> Unit) -> Unit,
    onSendPasswordResetSmsCode: (String, () -> Unit) -> Unit,
    onLogin: (String, String) -> Unit,
    onPasswordLogin: (String, String) -> Unit,
    onRegister: (String, String, String) -> Unit,
    onResetPassword: (String, String, String) -> Unit,
    onSendPhoneChangeSmsCode: (String) -> Unit,
    onVerifyCurrentPhoneForChange: (String, String, (String) -> Unit) -> Unit,
    onChangePhone: (String, String, String, String) -> Unit,
    onClearPhoneChangeStatus: () -> Unit,
    onLogout: () -> Unit,
    onUserAgreement: () -> Unit,
    onAgreementRequired: () -> Unit,
    onSaveProfileName: (String) -> Unit,
    onCloudSyncChange: (Boolean) -> Unit,
    onUploadUnsynced: () -> Unit,
    onPullCloud: () -> Unit,
    onMembership: () -> Unit,
    onOrders: () -> Unit,
    onVoiceprints: () -> Unit,
    onMicrophonePermission: () -> Unit,
    onNotificationPermission: () -> Unit,
    onFilePermission: () -> Unit,
    onPrivacyPolicy: () -> Unit,
    onDelete: () -> Unit
) {
    val layout = rememberPhoneLayoutSpec()
    val listState = rememberLazyListState()
    LaunchedEffect(cloudSyncFocusRequest, cloudUser != null) {
        if (cloudSyncFocusRequest > 0 && cloudUser != null) {
            listState.animateScrollToItem(3)
            onCloudSyncFocusConsumed()
        }
    }
    SmartPageBackground {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = layout.horizontalPadding,
                top = if (layout.compactWidth) 14.dp else 18.dp,
                end = layout.horizontalPadding,
                bottom = if (layout.compactWidth || layout.shortHeight) 100.dp else 116.dp
            ),
            verticalArrangement = Arrangement.spacedBy(if (layout.compactWidth) 14.dp else 16.dp)
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    SmartRoundIconButton(Icons.Filled.ArrowBack, "返回会议页", onClick = onBackHome, tint = Ink)
                    Text(
                        "个人中心",
                        color = Ink,
                        fontSize = if (layout.compactWidth) 22.sp else 23.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.size(40.dp))
                }
            }
            if (cloudUser == null) {
                item {
                    ProfileHeaderCard(
                        title = "个人空间",
                        subtitle = "登录后开启云端同步",
                        loggedIn = false
                    )
                }
                item {
                    ProfileLoginCard(
                        busy = loginBusy,
                        smsSending = smsSending,
                        statusText = loginStatusText,
                        onSendLoginSmsCode = onSendLoginSmsCode,
                        onSendRegisterSmsCode = onSendRegisterSmsCode,
                        onSendPasswordResetSmsCode = onSendPasswordResetSmsCode,
                        pendingRegistrationPhone = pendingRegistrationPhone,
                        pendingRegistrationCode = pendingRegistrationCode,
                        pendingRegistrationSignal = pendingRegistrationSignal,
                        pendingLoginPhone = pendingLoginPhone,
                        pendingLoginSignal = pendingLoginSignal,
                        onLogin = onLogin,
                        onPasswordLogin = onPasswordLogin,
                        onRegister = onRegister,
                        onResetPassword = onResetPassword,
                        agreementAccepted = agreementAccepted,
                        onAgreementAcceptedChange = onAgreementAcceptedChange,
                        onUserAgreement = onUserAgreement,
                        onPrivacyPolicy = onPrivacyPolicy,
                        onAgreementRequired = onAgreementRequired
                    )
                }
            } else {
                item {
                    ProfileAccountCard(
                        user = cloudUser,
                        profileName = profileName,
                        busy = loginBusy,
                        statusText = loginStatusText,
                        onSaveProfileName = onSaveProfileName,
                        onSendPhoneChangeSmsCode = onSendPhoneChangeSmsCode,
                        onVerifyCurrentPhoneForChange = onVerifyCurrentPhoneForChange,
                        onChangePhone = onChangePhone,
                        onClearPhoneChangeStatus = onClearPhoneChangeStatus,
                        onLogout = onLogout,
                        meetingCount = meetingCount,
                        todoCount = todoCount
                    )
                }
                item {
                    ProfileMembershipCard(
                        membership = membershipProfile,
                        onClick = onMembership
                    )
                }
                item {
                    ProfileSyncCard(
                        cloudSyncEnabled = cloudSyncEnabled,
                        cloudSyncInProgress = cloudSyncInProgress,
                        cloudSyncStatusText = cloudSyncStatusText,
                        unsyncedMeetingCount = unsyncedMeetingCount,
                        onCloudSyncChange = onCloudSyncChange,
                        onUploadUnsynced = onUploadUnsynced,
                        onPullCloud = onPullCloud
                    )
                }
                item {
                    ProfilePanel("订单与支付") {
                        ProfileActionRow(
                            Icons.Filled.ReceiptLong,
                            "订单记录",
                            "查看会员套餐和加量包购买记录",
                            onClick = onOrders
                        )
                    }
                }
            }
            item {
                ProfilePanel("智能声纹") {
                    ProfileActionRow(
                        Icons.Filled.Mic,
                        "声纹库",
                        if (cloudUser == null) "登录后管理声纹档案和提前录入" else "$speakerProfileCount 个声纹档案，可提前录入或停用",
                        onClick = onVoiceprints
                    )
                }
            }
            item {
                ProfilePanel("数据与隐私") {
                    ProfileActionRow(Icons.Filled.Article, "用户协议", onClick = onUserAgreement)
                    ProfileActionRow(Icons.Filled.GppGood, "隐私政策", onClick = onPrivacyPolicy)
                    ProfileActionRow(Icons.Filled.DeleteOutline, "删除会议数据", tint = Danger, softBg = Color(0xFFFFECE8), onClick = onDelete)
                }
            }
            item {
                ProfilePanel("系统权限") {
                    PermissionRow(
                        "麦克风",
                        if (permissionStatus.microphoneEnabled) "已开启" else "未开启",
                        if (permissionStatus.microphoneEnabled) BrandSoft else Color(0xFFFFF5DB),
                        if (permissionStatus.microphoneEnabled) Brand else Color(0xFFC58B00),
                        onMicrophonePermission
                    )
                    PermissionRow(
                        "通知",
                        if (permissionStatus.notificationEnabled) "已开启" else "未开启",
                        if (permissionStatus.notificationEnabled) BrandSoft else Color(0xFFFFF5DB),
                        if (permissionStatus.notificationEnabled) Brand else Color(0xFFC58B00),
                        onNotificationPermission
                    )
                    PermissionRow(
                        "文件访问",
                        if (permissionStatus.fileAccessEnabled) "按次授权" else "未开启",
                        BrandSoft,
                        Brand,
                        onFilePermission
                    )
                }
            }
        }
    }
}

@Composable
private fun ProfileMembershipCard(
    membership: MembershipProfile,
    onClick: () -> Unit
) {
    val layout = rememberPhoneLayoutSpec()
    val active = membership.active
    val hasQuotaData = membership.transcriptionMinutesTotal > 0 ||
        membership.transcriptionMinutesUsed > 0 ||
        membership.knowledgeQaTotal > 0 ||
        membership.knowledgeQaUsed > 0
    val title = when {
        membership.loading -> "会员信息加载中"
        active -> membership.planName
        else -> "未开通会员"
    }
    val subtitle = when {
        membership.loading -> "正在同步会员状态"
        active -> membershipExpiryLabel(membership.expiresAt, layout.compactWidth)
        hasQuotaData -> "当前剩余额度可直接使用"
        else -> "开通后获得转写时长和知识库问答额度"
    }
    Surface(
        color = Color.White,
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, if (active) Brand.copy(alpha = 0.18f) else Line),
        modifier = Modifier.fillMaxWidth().clickable { onClick() }
    ) {
        Column(Modifier.padding(if (layout.compactWidth) 16.dp else 18.dp), verticalArrangement = Arrangement.spacedBy(if (layout.compactWidth) 12.dp else 14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(if (layout.compactWidth) 40.dp else 44.dp).clip(CircleShape).background(if (active) BrandSoft else Color(0xFFEAF4FF)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.GppGood, contentDescription = null, tint = Brand, modifier = Modifier.size(if (layout.compactWidth) 22.dp else 24.dp))
                }
                Column(Modifier.padding(start = if (layout.compactWidth) 10.dp else 12.dp).weight(1f)) {
                    Text(title, color = Ink, fontSize = stableSp(if (layout.compactWidth) 18f else 19f, 16.5f), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(subtitle, color = Muted, fontSize = stableSp(12.5f, 11f), modifier = Modifier.padding(top = 4.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = Brand, modifier = Modifier.size(if (layout.compactWidth) 16.dp else 18.dp))
            }
            if (membership.loading) {
                MembershipLoadingStrip()
            } else if (active || hasQuotaData) {
                Row(horizontalArrangement = Arrangement.spacedBy(if (layout.compactWidth) 10.dp else 12.dp), modifier = Modifier.fillMaxWidth()) {
                    MembershipQuotaMini(
                        title = "转写时长",
                        used = membership.transcriptionMinutesUsed,
                        total = membership.transcriptionMinutesTotal,
                        suffix = if (layout.compactWidth) "分" else "分钟",
                        modifier = Modifier.weight(1f)
                    )
                    MembershipQuotaMini(
                        title = "知识问答",
                        used = membership.knowledgeQaUsed,
                        total = membership.knowledgeQaTotal,
                        suffix = "次",
                        modifier = Modifier.weight(1f)
                    )
                }
            } else {
                Text("暂无可用额度", color = Muted, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun MembershipLoadingStrip() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.fillMaxWidth(0.72f).height(10.dp).clip(RoundedCornerShape(999.dp)).background(Color(0xFFEAF0F6)))
        Box(Modifier.fillMaxWidth(0.48f).height(10.dp).clip(RoundedCornerShape(999.dp)).background(Color(0xFFEAF0F6)))
    }
}

@Composable
private fun MembershipQuotaMini(
    title: String,
    used: Int,
    total: Int,
    suffix: String,
    modifier: Modifier = Modifier
) {
    val remaining = (total - used).coerceAtLeast(0)
    val progress = if (total > 0) (used.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f
    Column(
        modifier
            .clip(RoundedCornerShape(if (LocalConfiguration.current.screenWidthDp < 400) 16.dp else 18.dp))
            .background(Color(0xFFF6FAFC))
            .padding(if (LocalConfiguration.current.screenWidthDp < 400) 10.dp else 12.dp),
        verticalArrangement = Arrangement.spacedBy(if (LocalConfiguration.current.screenWidthDp < 400) 6.dp else 8.dp)
    ) {
        Text(title, color = Ink, fontSize = stableSp(14f, 12f), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(999.dp)).background(Color(0xFFE4ECF3))) {
            Box(Modifier.fillMaxWidth(progress).height(5.dp).clip(RoundedCornerShape(999.dp)).background(Brand))
        }
        Text("剩余 $remaining/$total$suffix", color = Muted, fontSize = stableSp(12f, 11f), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun MembershipScreen(
    membership: MembershipProfile,
    onBack: () -> Unit,
    onPurchase: (MembershipPlan) -> Unit,
    onAddonPurchase: (MembershipAddon) -> Unit = {},
    onOrders: () -> Unit = {}
) {
    val layout = rememberPhoneLayoutSpec()
    var selectedPlanId by remember(membership.planId, membership.plans) {
        mutableStateOf(
            membership.plans.firstOrNull { it.id == membership.planId }?.id
                ?: membership.plans.firstOrNull()?.id
                ?: ""
        )
    }
    var selectedAddonId by remember(membership.addons) { mutableStateOf<String?>(null) }
    var pendingPlanConfirmation by remember { mutableStateOf<MembershipPlan?>(null) }
    val addonPurchasable = membership.active && !membership.frozen
    val selectedPlan = if (selectedAddonId != null && addonPurchasable) {
        null
    } else {
        membership.plans.firstOrNull { it.id == selectedPlanId }
    }
    val selectedAddon = if (addonPurchasable) {
        selectedAddonId?.let { addonId -> membership.addons.firstOrNull { it.id == addonId } }
    } else {
        null
    }
    val purchasePlan = selectedPlan.takeUnless { membership.loading }
    val purchaseAddon = selectedAddon.takeUnless { membership.loading }
    Box(Modifier.fillMaxSize().background(Color(0xFFF2F7FF))) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = Color.White,
                    shape = CircleShape,
                    shadowElevation = 2.dp,
                    modifier = Modifier.size(40.dp).clickable(onClick = onBack)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回", tint = Ink, modifier = Modifier.size(22.dp))
                    }
                }
                Text(
                    "会员中心",
                    color = Ink,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 14.dp)
                )
            }

            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = layout.horizontalPadding,
                    end = layout.horizontalPadding,
                    top = 2.dp,
                    bottom = if (layout.compactWidth || layout.shortHeight) 212.dp else 196.dp
                ),
                verticalArrangement = Arrangement.spacedBy(if (layout.compactWidth) 14.dp else 18.dp)
            ) {
                item {
                    MembershipPurchaseHero(membership)
                }
                item {
                    MembershipOrderEntry(onClick = onOrders)
                }
                if (membership.loading) {
                    item {
                        Surface(
                            color = Color.White,
                            shape = RoundedCornerShape(22.dp),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.75f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                Text("正在读取套餐", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                MembershipLoadingStrip()
                            }
                        }
                    }
                } else if (membership.plans.isEmpty()) {
                    item {
                        Surface(
                            color = Color.White,
                            shape = RoundedCornerShape(22.dp),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.75f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("当前没有可售套餐", color = Muted, fontSize = 15.sp, modifier = Modifier.padding(20.dp))
                        }
                    }
                } else {
                    item {
                        Text("选择套餐", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }
                    if (membership.active) {
                        item {
                            MembershipPlanRepurchaseNotice()
                        }
                    }
                    items(membership.plans) { plan ->
                        MembershipPlanRow(
                            plan = plan,
                            selected = selectedAddonId == null && plan.id == selectedPlanId,
                            onClick = {
                                selectedAddonId = null
                                selectedPlanId = plan.id
                            }
                        )
                    }
                    if (membership.addons.isNotEmpty()) {
                        item {
                            Text("加量包", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        }
                        if (!addonPurchasable) {
                            item {
                                MembershipAddonLockedNotice()
                            }
                        }
                        items(membership.addons) { addon ->
                            MembershipAddonRow(
                                addon = addon,
                                selected = addonPurchasable && selectedAddonId == addon.id,
                                enabled = addonPurchasable,
                                onClick = {
                                    if (addonPurchasable) {
                                        selectedAddonId = addon.id
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }

        MembershipPurchaseBar(
            membership = membership,
            selectedPlan = purchasePlan,
            selectedAddon = purchaseAddon,
            onPurchase = { plan ->
                if (membership.active) {
                    pendingPlanConfirmation = plan
                } else {
                    onPurchase(plan)
                }
            },
            onAddonPurchase = onAddonPurchase,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
        pendingPlanConfirmation?.let { plan ->
            AlertDialog(
                onDismissRequest = { pendingPlanConfirmation = null },
                title = {
                    Text("确认购买套餐", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                },
                text = {
                    Text(
                        "你当前已有会员。本次购买会追加 ${plan.hours.cleanHourText()} 小时转写和 ${plan.knowledgeQa} 次问答额度；会员截止日期刷新为从今天起 1 个月，不会按原截止日期继续顺延。",
                        color = Muted,
                        fontSize = 14.sp,
                        lineHeight = 22.sp
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            pendingPlanConfirmation = null
                            onPurchase(plan)
                        }
                    ) {
                        Text("继续支付", color = Brand, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingPlanConfirmation = null }) {
                        Text("取消", color = Muted, fontWeight = FontWeight.Bold)
                    }
                },
                containerColor = Color.White,
                shape = RoundedCornerShape(24.dp)
            )
        }
    }
}

@Composable
private fun MembershipPurchaseHero(membership: MembershipProfile) {
    val layout = rememberPhoneLayoutSpec()
    val cardPadding = when {
        layout.extraCompactWidth -> 16.dp
        layout.compactWidth -> 18.dp
        else -> 22.dp
    }
    val iconSize = when {
        layout.extraCompactWidth -> 40.dp
        layout.compactWidth -> 44.dp
        else -> 54.dp
    }
    val iconCorner = if (layout.compactWidth) 14.dp else 18.dp
    val titleFontSize = when {
        layout.extraCompactWidth -> stableSp(18f, 16f)
        layout.compactWidth -> stableSp(19f, 16.5f)
        else -> stableSp(22f, 18f)
    }
    val hasTrialQuota = !membership.active &&
        (membership.transcriptionMinutesRemaining > 0 || membership.knowledgeQaRemaining > 0)
    Surface(
        color = Color.White,
        shape = RoundedCornerShape(28.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.75f)),
        shadowElevation = 4.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(cardPadding), verticalArrangement = Arrangement.spacedBy(if (layout.compactWidth) 14.dp else 18.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(iconSize).clip(RoundedCornerShape(iconCorner)).background(BrandSoft),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.GppGood, contentDescription = null, tint = Brand, modifier = Modifier.size(if (layout.compactWidth) 22.dp else 28.dp))
                }
                Column(Modifier.padding(start = if (layout.compactWidth) 10.dp else 14.dp).weight(1f)) {
                    Text(
                        when {
                            membership.loading -> "会员信息同步中"
                            membership.frozen -> "账号已冻结"
                            membership.active -> membership.planName
                            hasTrialQuota -> "试用额度"
                            else -> "开通鲲穹会纪会员"
                        },
                        color = Ink,
                        fontSize = titleFontSize,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        when {
                            membership.loading -> "正在读取账号权益"
                            membership.frozen -> "购买和支付已暂停"
                            membership.active -> membershipExpiryLabel(membership.expiresAt, layout.compactWidth)
                            hasTrialQuota -> "剩余额度用完后可购买套餐继续使用"
                            else -> "获得转写时长和知识库问答额度"
                        },
                        color = Muted,
                        fontSize = stableSp(if (layout.compactWidth) 12f else 13f, 11f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 5.dp)
                    )
                }
                when {
                    membership.frozen -> CompactStatusBadge("冻结", Color(0xFFFFECE8), Danger)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MembershipQuotaTile(
                    value = if (!membership.loading) "${membership.transcriptionMinutesRemaining}" else "-",
                    label = "转写分钟",
                    modifier = Modifier.weight(1f)
                )
                MembershipQuotaTile(
                    value = if (!membership.loading) "${membership.knowledgeQaRemaining}" else "-",
                    label = "问答次数",
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun MembershipQuotaTile(value: String, label: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFFF5F8FD))
            .padding(14.dp)
    ) {
        Text(value, color = Brand, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(label, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp))
    }
}

@Composable
private fun MembershipOrderEntry(onClick: () -> Unit) {
    Surface(
        color = Color.White,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, Line),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(38.dp).clip(CircleShape).background(BrandSoft), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.ReceiptLong, contentDescription = null, tint = Brand, modifier = Modifier.size(21.dp))
            }
            Column(Modifier.padding(start = 12.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("订单记录", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text("查看购买订单和支付状态", color = Muted, fontSize = 12.sp)
            }
            Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = Muted, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun MembershipPlanRow(
    plan: MembershipPlan,
    selected: Boolean,
    onClick: () -> Unit
) {
    val border = if (selected) Brand else Line
    Surface(
        color = if (selected) BrandSoft else Color.White,
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(if (selected) 2.dp else 1.dp, border),
        shadowElevation = 2.dp,
        modifier = Modifier.fillMaxWidth().clickable { onClick() }
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(plan.name, color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
                Text("${plan.hours.cleanHourText()}小时转写 / ${plan.knowledgeQa}次知识库问答", color = Muted, fontSize = 13.sp)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("¥${plan.price.cleanPriceText()}", color = if (selected) Brand else Ink, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text("每月", color = Muted, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun MembershipPlanRepurchaseNotice() {
    Surface(
        color = Color(0xFFFFF7E8),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, Color(0xFFFFD79B)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(30.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.AccessTime, contentDescription = null, tint = Color(0xFFE0782E), modifier = Modifier.size(17.dp))
            }
            Text(
                "已有会员再买套餐会追加本月额度，截止日期刷新为从今天起 1 个月。",
                color = Color(0xFF7A4E18),
                fontSize = 13.sp,
                lineHeight = 19.sp,
                modifier = Modifier.padding(start = 10.dp)
            )
        }
    }
}

@Composable
private fun CompactStatusBadge(text: String, bg: Color, color: Color) {
    Surface(color = bg, shape = RoundedCornerShape(999.dp)) {
        Text(
            text,
            color = color,
            fontSize = stableSp(11f, 9.5f),
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp)
        )
    }
}

private fun membershipExpiryLabel(expiresAt: String?, compact: Boolean): String {
    val date = expiresAt ?: "-"
    return "有效期至 $date"
}

@Composable
private fun MembershipAddonLockedNotice() {
    Surface(
        color = Color(0xFFF7F9FC),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, Line),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(30.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Lock, contentDescription = null, tint = Muted, modifier = Modifier.size(17.dp))
            }
            Text(
                "加量包仅限会员购买，请先开通会员套餐。",
                color = Muted,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                modifier = Modifier.padding(start = 10.dp)
            )
        }
    }
}

@Composable
private fun MembershipAddonRow(addon: MembershipAddon, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val border = if (selected) Brand else Line
    val contentAlpha = if (enabled) 1f else 0.48f
    Surface(
        color = if (selected) BrandSoft else Color.White,
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(if (selected) 2.dp else 1.dp, border),
        shadowElevation = if (enabled) 2.dp else 0.dp,
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled) { onClick() }
    ) {
        Row(Modifier.padding(16.dp).alpha(contentAlpha), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(Color(0xFFFFF3E8)), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.AccessTime, contentDescription = null, tint = Color(0xFFE0782E), modifier = Modifier.size(21.dp))
            }
            Column(Modifier.padding(start = 12.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(addon.name, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text("补充 1 ${addon.unit.unitLabel()}转写额度", color = Muted, fontSize = 13.sp)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("¥${addon.price.cleanPriceText()}", color = if (selected) Brand else Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("每${addon.unit.unitLabel()}", color = Muted, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun MembershipPurchaseBar(
    membership: MembershipProfile,
    selectedPlan: MembershipPlan?,
    selectedAddon: MembershipAddon?,
    onPurchase: (MembershipPlan) -> Unit,
    onAddonPurchase: (MembershipAddon) -> Unit,
    modifier: Modifier = Modifier
) {
    val selectedName = selectedAddon?.name ?: selectedPlan?.name
    val selectedPrice = selectedAddon?.price ?: selectedPlan?.price
    val selectedUnit = if (selectedAddon != null) selectedAddon.unit.unitLabel() else "月"
    Surface(
        color = Color.White,
        shadowElevation = 10.dp,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            Modifier.navigationBarsPadding().padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("支付宝支付", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text(
                        selectedName?.let { "已选 $it · ¥${selectedPrice?.cleanPriceText()}/$selectedUnit" } ?: "请选择套餐",
                        color = Muted,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 3.dp)
                    )
                }
                Text(
                    selectedPrice?.let { "¥${it.cleanPriceText()}" } ?: "-",
                    color = Brand,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            GradientActionButton(
                text = when {
                    membership.loading -> "加载中"
                    selectedPlan == null && selectedAddon == null -> "请选择套餐"
                    membership.frozen -> "账号已冻结"
                    !membership.paymentEnabled -> "支付配置待开通"
                    else -> "立即购买"
                },
                onClick = {
                    selectedAddon?.let(onAddonPurchase) ?: selectedPlan?.let(onPurchase)
                },
                enabled = (selectedPlan != null || selectedAddon != null) && membership.paymentEnabled && !membership.frozen && !membership.loading,
                modifier = Modifier.fillMaxWidth().height(52.dp)
            )
        }
    }
}

private fun Double.cleanPriceText(): String {
    return if (this % 1.0 == 0.0) this.toInt().toString() else "%.2f".format(this)
}

private fun Double.cleanHourText(): String {
    return if (this % 1.0 == 0.0) this.toInt().toString() else "%.1f".format(this)
}

private fun String.unitLabel(): String {
    return when (this) {
        "hour" -> "小时"
        else -> this
    }
}

@Composable
fun PaymentOrdersScreen(
    orders: List<PaymentOrder>,
    loading: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onSync: (String) -> Unit,
    onCopyOrderId: (String) -> Unit
) {
    val successfulOrders = orders.filter { it.status.isSuccessfulPaymentOrderStatus() }
    ScreenScaffold(
        title = "订单记录",
        onBack = onBack,
        trailing = {
            IconButton(onClick = onRefresh, enabled = !loading) {
                if (loading) {
                    CircularProgressIndicator(
                        color = Brand,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(20.dp)
                    )
                } else {
                    Icon(Icons.Filled.Refresh, contentDescription = "刷新订单", tint = Brand)
                }
            }
        }
    ) {
        when {
            loading && successfulOrders.isEmpty() -> {
                InfoBlock("正在读取订单") {
                    MembershipLoadingStrip()
                    Text("正在同步你的购买记录", color = Muted, fontSize = 13.sp)
                }
            }
            successfulOrders.isEmpty() -> {
                InfoBlock("暂无订单") {
                    Text("充值成功的会员套餐或加量包订单会显示在这里。", color = Muted, fontSize = 14.sp, lineHeight = 20.sp)
                }
            }
            else -> {
                successfulOrders.forEach { order ->
                    PaymentOrderCard(
                        order = order,
                        onSync = { onSync(order.id) },
                        onCopyOrderId = { onCopyOrderId(order.id) }
                    )
                }
            }
        }
    }
}

private fun String.isSuccessfulPaymentOrderStatus(): Boolean {
    val clean = trim()
    return clean == "支付成功" || clean == "已支付"
}

@Composable
private fun PaymentOrderCard(
    order: PaymentOrder,
    onSync: () -> Unit,
    onCopyOrderId: () -> Unit
) {
    val statusTone = paymentOrderStatusTone(order.status)
    Surface(
        color = Color.White,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, Line),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(
                        order.productName.ifBlank { order.planName.ifBlank { "会员订单" } },
                        color = Ink,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(order.createdAt.ifBlank { order.paidAt.ifBlank { "-" } }, color = Muted, fontSize = 12.sp)
                }
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("¥${order.amount.cleanPriceText()}", color = Brand, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Pill(order.status.ifBlank { "未知状态" }, statusTone.first, statusTone.second)
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PaymentOrderInfoLine("订单类型", if (order.productType == "addon") "加量包" else "会员套餐")
                if (order.transcriptionMinutes > 0) {
                    PaymentOrderInfoLine("转写额度", "${order.transcriptionMinutes} 分钟")
                }
                PaymentOrderInfoLine("支付渠道", order.channel.ifBlank { "支付宝" })
                if (order.paidAt.isNotBlank() && order.paidAt != "-") {
                    PaymentOrderInfoLine("支付时间", order.paidAt)
                }
                PaymentOrderInfoLine("订单号", order.id)
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = onCopyOrderId,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("复制订单号", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
                if (order.status == "未支付" || order.status == "待支付" || order.status == "处理中" || order.status == "支付中") {
                    Button(
                        onClick = onSync,
                        colors = ButtonDefaults.buttonColors(containerColor = Brand),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("刷新状态", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun PaymentOrderInfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(label, color = Muted, fontSize = 13.sp, modifier = Modifier.width(70.dp))
        Text(
            value,
            color = Ink,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            modifier = Modifier.weight(1f)
        )
    }
}

private fun paymentOrderStatusTone(status: String): Pair<Color, Color> {
    return when {
        "成功" in status || "已支付" in status -> BrandSoft to Brand
        "未支付" in status -> Color(0xFFF1F4F8) to Muted
        "待" in status || "中" in status -> Color(0xFFFFF5DB) to Color(0xFFC58B00)
        "失败" in status || "关闭" in status || "取消" in status -> Color(0xFFFFECE8) to Danger
        else -> Color(0xFFF1F4F8) to Muted
    }
}

@Composable
fun VoiceprintScreen(
    cloudUser: CloudUser?,
    profiles: List<SpeakerProfile>,
    busy: Boolean,
    statusText: String?,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onRecordSample: (String) -> Unit,
    onImportSample: (String) -> Unit,
    onRenameProfile: (SpeakerProfile, String) -> Unit,
    onToggleProfile: (SpeakerProfile, Boolean) -> Unit,
    onDeleteProfile: (SpeakerProfile) -> Unit
) {
    var enrollmentName by remember { mutableStateOf("") }
    var voiceprintConsentAccepted by remember { mutableStateOf(false) }
    ScreenScaffold(
        title = "声纹库",
        onBack = onBack,
        trailing = {
            IconButton(onClick = onRefresh, enabled = cloudUser != null && !busy) {
                Icon(Icons.Filled.Refresh, contentDescription = "刷新声纹库", tint = Brand)
            }
        }
    ) {
        if (cloudUser == null) {
            InfoBlock("登录后使用") {
                Text("声纹档案按账号保存。登录后可以提前录入、删除、停用，并在新会议中自动识别说话人。", color = Muted, fontSize = 14.sp, lineHeight = 20.sp)
            }
            return@ScreenScaffold
        }

        InfoBlock("提前录入") {
            Text("录制或导入 15-30 秒清晰人声，系统会直接提取声纹并保存到当前账号。", color = Muted, fontSize = 14.sp, lineHeight = 20.sp)
            VoiceprintConsentRow(
                checked = voiceprintConsentAccepted,
                onCheckedChange = { voiceprintConsentAccepted = it }
            )
            AppOutlinedField(
                value = enrollmentName,
                onValueChange = { enrollmentName = it.take(24) },
                label = "声纹姓名",
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            if (!statusText.isNullOrBlank()) {
                Surface(
                    color = if (busy) Color(0xFFFFF5DB) else BrandSoft,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        statusText,
                        color = if (busy) Color(0xFFC58B00) else Brand,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                GradientActionButton(
                    text = "录音录入",
                    onClick = { onRecordSample(enrollmentName) },
                    enabled = voiceprintConsentAccepted && !busy && enrollmentName.trim().isNotBlank(),
                    icon = Icons.Filled.Mic,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(
                    onClick = { onImportSample(enrollmentName) },
                    enabled = voiceprintConsentAccepted && !busy && enrollmentName.trim().isNotBlank(),
                    shape = RoundedCornerShape(999.dp),
                    modifier = Modifier.weight(1f).height(48.dp)
                ) {
                    Icon(Icons.Filled.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("导入音频", modifier = Modifier.padding(start = 6.dp), maxLines = 1)
                }
            }
        }

        InfoBlock("声纹档案") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${profiles.size} 个档案", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text("录入成功后会出现在这里；停用后不会参与自动匹配", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                }
                Pill(if (busy) "处理中" else "已同步", if (busy) Color(0xFFFFF5DB) else BrandSoft, if (busy) Color(0xFFC58B00) else Brand)
            }
            if (profiles.isEmpty()) {
                Text("暂无声纹档案。可以先在这里录制或导入一段清晰人声采样。", color = Muted, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 8.dp))
            } else {
                profiles.forEachIndexed { index, profile ->
                    if (index > 0) {
                        Spacer(Modifier.fillMaxWidth().height(1.dp).background(Line))
                    }
                    VoiceprintProfileRow(
                        profile = profile,
                        busy = busy,
                        onRename = { name -> onRenameProfile(profile, name) },
                        onToggle = { active -> onToggleProfile(profile, active) },
                        onDelete = { onDeleteProfile(profile) }
                    )
                }
            }
        }
    }
}

@Composable
private fun VoiceprintConsentRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(BrandSoftCyan)
            .clickable { onCheckedChange(!checked) }
            .padding(10.dp)
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.size(32.dp)
        )
        Text(
            "我同意采集并保存本人或已获授权的声纹样本，用于说话人识别；后续可在声纹库停用或删除。",
            color = Muted,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun VoiceprintProfileRow(
    profile: SpeakerProfile,
    busy: Boolean,
    onRename: (String) -> Unit,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit
) {
    var editing by remember(profile.id) { mutableStateOf(false) }
    var editName by remember(profile.id, profile.displayName) { mutableStateOf(profile.displayName) }
    var confirmDelete by remember(profile.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(if (profile.active) BrandSoft else Color(0xFFF3F6F6)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Mic, contentDescription = null, tint = if (profile.active) Brand else Muted, modifier = Modifier.size(21.dp))
            }
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text(profile.displayName, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${profile.sampleCount} 个样本 · ${profile.updatedAt.toVoiceprintDateLabel()}", color = Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            }
            Pill(if (profile.active) "启用" else "停用", if (profile.active) BrandSoft else Color(0xFFF3F6F6), if (profile.active) Brand else Muted)
        }
        if (editing) {
            AppOutlinedField(
                value = editName,
                onValueChange = { editName = it.take(24) },
                label = "声纹姓名",
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                GradientActionButton(
                    text = "保存",
                    onClick = {
                        onRename(editName)
                        editing = false
                    },
                    enabled = !busy && editName.trim().isNotBlank(),
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(
                    onClick = {
                        editName = profile.displayName
                        editing = false
                    },
                    enabled = !busy,
                    shape = RoundedCornerShape(999.dp),
                    modifier = Modifier.weight(1f).height(48.dp)
                ) {
                    Text("取消")
                }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = { editing = true }, enabled = !busy) {
                    Icon(Icons.Filled.Edit, contentDescription = null, tint = Brand, modifier = Modifier.size(17.dp))
                    Text("改名", color = Brand, modifier = Modifier.padding(start = 4.dp))
                }
                TextButton(onClick = { onToggle(!profile.active) }, enabled = !busy) {
                    Text(if (profile.active) "停用" else "启用", color = Brand)
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { confirmDelete = true }, enabled = !busy) {
                    Icon(Icons.Filled.DeleteOutline, contentDescription = null, tint = Danger, modifier = Modifier.size(17.dp))
                    Text("删除", color = Danger, modifier = Modifier.padding(start = 4.dp))
                }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除声纹档案") },
            text = { Text("删除后会移除 ${profile.displayName} 的声纹档案和样本，后续会议不会再用它自动识别。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        onDelete()
                    }
                ) {
                    Text("删除", color = Danger, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text("取消")
                }
            }
        )
    }
}

private fun String.toVoiceprintDateLabel(): String {
    val clean = trim()
    if (clean.length >= 10) return clean.take(10)
    return "刚刚更新"
}

@Composable
private fun ProfileHeaderCard(title: String, subtitle: String, loggedIn: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(Brush.horizontalGradient(listOf(BrandCyan, Color(0xFF4B7BFF), Brand)))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            KunqiongLogo(modifier = Modifier.size(58.dp), corner = 18.dp)
            Column(Modifier.padding(start = 14.dp).weight(1f)) {
                Text(title, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Text(subtitle, color = Color.White.copy(alpha = 0.76f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            }
            Pill(if (loggedIn) "已登录" else "未登录", Color.White.copy(alpha = 0.18f), Color.White)
        }
    }
}

@Composable
private fun ProfilePanel(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = Color.White.copy(alpha = 0.96f),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.72f)),
        shadowElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(title, color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun ProfileActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    tint: Color = Brand,
    softBg: Color = BrandSoftCyan,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(softBg),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(21.dp))
        }
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(title, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, color = Muted, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            }
        }
        Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = Ink.copy(alpha = 0.58f))
    }
}

@Composable
private fun ProfileLoginCard(
    busy: Boolean,
    smsSending: Boolean,
    statusText: String?,
    onSendLoginSmsCode: (String, () -> Unit) -> Unit,
    onSendRegisterSmsCode: (String, () -> Unit) -> Unit,
    onSendPasswordResetSmsCode: (String, () -> Unit) -> Unit,
    pendingRegistrationPhone: String?,
    pendingRegistrationCode: String,
    pendingRegistrationSignal: Int,
    pendingLoginPhone: String?,
    pendingLoginSignal: Int,
    onLogin: (String, String) -> Unit,
    onPasswordLogin: (String, String) -> Unit,
    onRegister: (String, String, String) -> Unit,
    onResetPassword: (String, String, String) -> Unit,
    agreementAccepted: Boolean,
    onAgreementAcceptedChange: (Boolean) -> Unit,
    onUserAgreement: () -> Unit,
    onPrivacyPolicy: () -> Unit,
    onAgreementRequired: () -> Unit
) {
    val layout = rememberPhoneLayoutSpec()
    var phone by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var authMode by remember { mutableStateOf(AuthMode.Sms) }
    var authFlow by remember { mutableStateOf(AuthFlow.Login) }
    var resendSeconds by remember { mutableStateOf(0) }
    var formStatusText by remember { mutableStateOf<String?>(null) }
    var passwordHintVisible by remember { mutableStateOf(false) }
    LaunchedEffect(resendSeconds) {
        if (resendSeconds > 0) {
            delay(1_000)
            resendSeconds -= 1
        }
    }
    LaunchedEffect(authMode, authFlow) {
        passwordHintVisible = false
    }
    LaunchedEffect(pendingRegistrationSignal) {
        if (pendingRegistrationSignal > 0 && !pendingRegistrationPhone.isNullOrBlank()) {
            phone = pendingRegistrationPhone
            code = pendingRegistrationCode
            password = ""
            confirmPassword = ""
            passwordHintVisible = false
            authMode = AuthMode.Sms
            authFlow = AuthFlow.SetupPassword
        }
    }
    LaunchedEffect(pendingLoginSignal) {
        if (pendingLoginSignal > 0 && !pendingLoginPhone.isNullOrBlank()) {
            phone = pendingLoginPhone
            code = ""
            password = ""
            confirmPassword = ""
            passwordHintVisible = false
            authMode = AuthMode.Sms
            authFlow = AuthFlow.Login
            resendSeconds = 0
        }
    }
    val phoneReady = phone.trim().length >= 11
    val codeReady = code.trim().length == 6
    val cleanPassword = password.trim()
    val passwordReady = cleanPassword.length >= LOGIN_PASSWORD_MIN_LENGTH
    val confirmPasswordEntered = confirmPassword.trim().isNotEmpty()
    val canSubmit = agreementAccepted && !busy && phoneReady && when {
        authFlow == AuthFlow.Login && authMode == AuthMode.Sms -> codeReady
        authFlow == AuthFlow.Login && authMode == AuthMode.Password -> passwordReady
        else -> codeReady && passwordReady && confirmPasswordEntered
    }
    val displayedStatusText = formStatusText ?: statusText
    val passwordHintText = if (
        passwordHintVisible &&
        authFlow != AuthFlow.Login &&
        cleanPassword.isNotEmpty() &&
        !passwordReady
    ) {
        "密码至少 ${LOGIN_PASSWORD_MIN_LENGTH} 位"
    } else {
        null
    }
    val title = when (authFlow) {
        AuthFlow.Register -> "免费注册账号"
        AuthFlow.ResetPassword -> "忘记密码"
        AuthFlow.SetupPassword -> "设置登录密码"
        AuthFlow.Login -> "账号登录"
    }
    val actionText = when {
        authFlow == AuthFlow.ResetPassword -> if (busy) "重置中..." else "重置并登录"
        authFlow == AuthFlow.Register || authFlow == AuthFlow.SetupPassword -> if (busy) "注册中..." else "注册并登录"
        authMode == AuthMode.Password -> if (busy) "登录中..." else "密码登录"
        else -> if (busy) "登录中..." else "登录"
    }
    val statusBusy = busy || smsSending
    fun sendCode() {
        if (agreementAccepted) {
            val onSent = { resendSeconds = 60 }
            when (authFlow) {
                AuthFlow.ResetPassword -> onSendPasswordResetSmsCode(phone, onSent)
                AuthFlow.Register,
                AuthFlow.SetupPassword -> onSendRegisterSmsCode(phone, onSent)
                AuthFlow.Login -> onSendLoginSmsCode(phone, onSent)
            }
        } else {
            onAgreementRequired()
        }
    }
    fun submit() {
        if (!agreementAccepted) {
            onAgreementRequired()
            return
        }
        if (authFlow != AuthFlow.Login && password.trim() != confirmPassword.trim()) {
            formStatusText = "两次输入的密码不一致"
            return
        }
        formStatusText = null
        when {
            authFlow == AuthFlow.ResetPassword -> onResetPassword(phone, code, password.trim())
            authFlow == AuthFlow.Register || authFlow == AuthFlow.SetupPassword -> onRegister(phone, code, password.trim())
            authMode == AuthMode.Sms -> onLogin(phone, code)
            authMode == AuthMode.Password -> onPasswordLogin(phone, password.trim())
        }
    }
    ProfilePanel(title) {
        if (authFlow != AuthFlow.Login) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when (authFlow) {
                        AuthFlow.ResetPassword -> "通过手机号验证码重置密码"
                        AuthFlow.Register -> "验证手机号后设置登录密码"
                        AuthFlow.SetupPassword -> "设置密码完成注册"
                        AuthFlow.Login -> ""
                    },
                    color = Muted,
                    fontSize = 13.sp,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = {
                    authFlow = AuthFlow.Login
                    code = ""
                    password = ""
                    confirmPassword = ""
                    passwordHintVisible = false
                }, enabled = !busy) {
                    Text("返回登录", color = Brand, fontWeight = FontWeight.Bold)
                }
            }
        } else {
            AuthModeTabs(
                mode = authMode,
                onModeChange = {
                    authMode = it
                    code = ""
                    password = ""
                    confirmPassword = ""
                },
                enabled = !busy
            )
        }
        AppOutlinedField(
            value = phone,
            onValueChange = {
                formStatusText = null
                phone = it.filter { char -> char.isDigit() || char == '+' }.take(14)
            },
            enabled = !busy,
            label = "手机号",
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            modifier = Modifier.fillMaxWidth()
        )
        if (authFlow != AuthFlow.Login || authMode == AuthMode.Sms) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                AppOutlinedField(
                    value = code,
                    onValueChange = {
                        formStatusText = null
                        code = it.filter { char -> char.isDigit() }.take(6)
                    },
                    enabled = !busy,
                    label = "验证码",
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(
                    onClick = { sendCode() },
                    enabled = !busy && !smsSending && resendSeconds == 0 && phoneReady,
                    modifier = Modifier.width(if (layout.compactWidth) 108.dp else 120.dp).height(56.dp),
                    shape = RoundedCornerShape(999.dp)
                ) {
                    Text(if (smsSending) "发送中" else if (resendSeconds > 0) "${resendSeconds}s" else "获取验证码", fontSize = if (layout.compactWidth) 12.sp else 13.sp, maxLines = 1)
                }
            }
        }
        if (authFlow == AuthFlow.Login && authMode == AuthMode.Password) {
            AppOutlinedField(
                value = password,
                onValueChange = {
                    formStatusText = null
                    password = it.take(32)
                },
                enabled = !busy,
                label = "密码",
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (authFlow != AuthFlow.Login) {
            AppOutlinedField(
                value = password,
                onValueChange = {
                    formStatusText = null
                    password = it.take(32)
                    if (password.trim().length >= LOGIN_PASSWORD_MIN_LENGTH) {
                        passwordHintVisible = false
                    }
                },
                enabled = !busy,
                label = if (authFlow == AuthFlow.ResetPassword) "新密码" else "密码",
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                visualTransformation = PasswordVisualTransformation(),
                supportingText = passwordHintText,
                onFocusLost = {
                    passwordHintVisible = password.trim().isNotEmpty() &&
                        password.trim().length < LOGIN_PASSWORD_MIN_LENGTH
                },
                modifier = Modifier.fillMaxWidth()
            )
            AppOutlinedField(
                value = confirmPassword,
                onValueChange = {
                    formStatusText = null
                    confirmPassword = it.take(32)
                },
                enabled = !busy,
                label = "确认密码",
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (authFlow == AuthFlow.Login) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = {
                    authFlow = AuthFlow.ResetPassword
                    code = ""
                    password = ""
                    confirmPassword = ""
                    passwordHintVisible = false
                }, enabled = !busy) {
                    Text("忘记密码", color = Muted, fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = {
                    authFlow = AuthFlow.Register
                    code = ""
                    password = ""
                    confirmPassword = ""
                    passwordHintVisible = false
                }, enabled = !busy) {
                    Text("免费注册账号", color = Brand, fontWeight = FontWeight.Bold)
                }
            }
        }
        if (!displayedStatusText.isNullOrBlank()) {
            val tone = loginStatusTone(statusBusy, displayedStatusText)
            Surface(
                color = tone.container,
                shape = RoundedCornerShape(999.dp),
                border = BorderStroke(1.dp, tone.border),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    displayedStatusText,
                    color = tone.text,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }
        LoginAgreementRow(
            checked = agreementAccepted,
            onCheckedChange = onAgreementAcceptedChange,
            onUserAgreement = onUserAgreement,
            onPrivacyPolicy = onPrivacyPolicy
        )
        GradientActionButton(
            text = actionText,
            onClick = { submit() },
            enabled = canSubmit,
            modifier = Modifier.fillMaxWidth().height(50.dp)
        )
    }
}

@Composable
private fun ProfileAccountCard(
    user: CloudUser,
    profileName: String,
    busy: Boolean,
    statusText: String?,
    onSaveProfileName: (String) -> Unit,
    onSendPhoneChangeSmsCode: (String) -> Unit,
    onVerifyCurrentPhoneForChange: (String, String, (String) -> Unit) -> Unit,
    onChangePhone: (String, String, String, String) -> Unit,
    onClearPhoneChangeStatus: () -> Unit,
    onLogout: () -> Unit,
    meetingCount: Int,
    todoCount: Int
) {
    val layout = rememberPhoneLayoutSpec()
    val rawName = profileName.ifBlank { user.displayName }.trim()
    val displayName = rawName.takeUnless { it.isGeneratedPhoneDisplayName() } ?: "未设置"
    val nameFontSize = when {
        layout.extraCompactWidth && displayName.length > 8 -> stableSp(16f, 14.5f)
        layout.compactWidth && displayName.length > 8 -> stableSp(17f, 15f)
        layout.compactWidth -> stableSp(18f, 16f)
        displayName.length > 12 -> stableSp(18f, 16f)
        displayName.length > 8 -> stableSp(19f, 17f)
        else -> stableSp(21f, 18f)
    }
    val nameLineHeight = when {
        layout.extraCompactWidth && displayName.length > 8 -> stableSp(20f, 17f)
        layout.compactWidth && displayName.length > 8 -> stableSp(22f, 18f)
        layout.compactWidth -> stableSp(24f, 20f)
        displayName.length > 12 -> stableSp(23f, 20f)
        displayName.length > 8 -> stableSp(25f, 21f)
        else -> stableSp(28f, 23f)
    }
    val cardPadding = if (layout.extraCompactWidth) 16.dp else if (layout.compactWidth) 18.dp else 22.dp
    val avatarSize = if (layout.extraCompactWidth) 38.dp else if (layout.compactWidth) 42.dp else 48.dp
    val avatarIconSize = if (layout.extraCompactWidth) 22.dp else if (layout.compactWidth) 24.dp else 27.dp
    val editButtonSize = if (layout.extraCompactWidth) 28.dp else if (layout.compactWidth) 30.dp else 34.dp
    val profileGap = if (layout.compactWidth) 8.dp else 12.dp
    val currentPhoneRaw = user.phone
    val currentPhoneLabel = currentPhoneRaw.toProfilePhoneLabel().ifBlank { user.userId }
    var editableName by remember(profileName, user.userId) { mutableStateOf(rawName.takeUnless { it.isGeneratedPhoneDisplayName() } ?: "") }
    var editingName by remember(user.userId) { mutableStateOf(false) }
    var phoneChangeVisible by remember(user.userId) { mutableStateOf(false) }
    var phoneChangeStep by remember(user.userId) { mutableStateOf(0) }
    var oldPhoneVerificationToken by remember(user.userId) { mutableStateOf("") }
    var oldPhoneCode by remember(user.userId) { mutableStateOf("") }
    var newPhone by remember(user.userId) { mutableStateOf("") }
    var newPhoneCode by remember(user.userId) { mutableStateOf("") }
    var oldPhoneResendSeconds by remember(user.userId) { mutableStateOf(0) }
    var newPhoneResendSeconds by remember(user.userId) { mutableStateOf(0) }
    LaunchedEffect(oldPhoneResendSeconds) {
        if (oldPhoneResendSeconds > 0) {
            delay(1_000)
            oldPhoneResendSeconds -= 1
        }
    }
    LaunchedEffect(newPhoneResendSeconds) {
        if (newPhoneResendSeconds > 0) {
            delay(1_000)
            newPhoneResendSeconds -= 1
        }
    }
    val oldPhoneReady = currentPhoneRaw.isNotBlank()
    val newPhoneReady = newPhone.trim().filter { it.isDigit() }.length == 11
    fun resetPhoneChangeForm() {
        phoneChangeStep = 0
        oldPhoneVerificationToken = ""
        oldPhoneCode = ""
        newPhone = ""
        newPhoneCode = ""
        oldPhoneResendSeconds = 0
        newPhoneResendSeconds = 0
    }
    LaunchedEffect(currentPhoneRaw) {
        phoneChangeVisible = false
        resetPhoneChangeForm()
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Brush.horizontalGradient(listOf(BrandCyan, Color(0xFF6EA1FF), BrandPurple)))
    ) {
        Column(Modifier.padding(cardPadding), verticalArrangement = Arrangement.spacedBy(if (layout.compactWidth) 14.dp else 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(avatarSize)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.22f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.Person, contentDescription = null, tint = Color.White, modifier = Modifier.size(avatarIconSize))
                }
                Column(Modifier.padding(start = profileGap).weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(
                        displayName,
                        color = Color.White,
                        fontSize = nameFontSize,
                        lineHeight = nameLineHeight,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            currentPhoneLabel,
                            color = Color.White.copy(alpha = 0.84f),
                            fontSize = stableSp(if (layout.compactWidth) 11.5f else 12.5f, 10.5f),
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        TextButton(
                            onClick = {
                                resetPhoneChangeForm()
                                onClearPhoneChangeStatus()
                                phoneChangeVisible = true
                            },
                            enabled = oldPhoneReady && !busy,
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 5.dp, vertical = 0.dp),
                            modifier = Modifier.height(26.dp).padding(start = 2.dp)
                        ) {
                            Text("修改", color = Color.White, fontWeight = FontWeight.Bold, fontSize = stableSp(11.5f, 10f))
                        }
                    }
                }
                IconButton(
                    onClick = {
                        editableName = rawName.takeUnless { it.isGeneratedPhoneDisplayName() } ?: ""
                        editingName = true
                    },
                    modifier = Modifier
                        .padding(start = if (layout.compactWidth) 2.dp else 6.dp)
                        .size(editButtonSize)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.18f))
                ) {
                    Icon(Icons.Filled.Edit, contentDescription = "修改姓名", tint = Color.White, modifier = Modifier.size(if (layout.compactWidth) 15.dp else 16.dp))
                }
            }
            if (editingName) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Text("我的姓名", color = Color.White.copy(alpha = 0.90f), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Surface(
                        color = Color.White,
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.72f)),
                        modifier = Modifier.fillMaxWidth().height(52.dp)
                    ) {
                        BasicTextField(
                            value = editableName,
                            onValueChange = { editableName = it },
                            singleLine = true,
                            textStyle = TextStyle(color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Medium),
                            cursorBrush = SolidColor(Brand),
                            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                            decorationBox = { innerTextField ->
                                Box(contentAlignment = Alignment.CenterStart) {
                                    if (editableName.isBlank()) {
                                        Text("请输入姓名", color = Muted, fontSize = 15.sp)
                                    }
                                    innerTextField()
                                }
                            }
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    GradientActionButton(
                        text = "保存",
                        onClick = {
                            onSaveProfileName(editableName)
                            editingName = false
                        },
                        modifier = Modifier.weight(1f).height(46.dp)
                    )
                    OutlinedButton(
                        onClick = {
                            editableName = rawName.takeUnless { it.isGeneratedPhoneDisplayName() } ?: ""
                            editingName = false
                        },
                        shape = RoundedCornerShape(999.dp),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.68f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                        modifier = Modifier.weight(1f).height(46.dp)
                    ) {
                        Text("取消", fontWeight = FontWeight.Bold)
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.18f)))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                ProfileStatCell(meetingCount.toString(), "会议", Icons.Filled.Article, Modifier.weight(1f))
                Box(Modifier.width(1.dp).height(32.dp).background(Color.White.copy(alpha = 0.22f)))
                ProfileStatCell(todoCount.toString(), "待办", Icons.Filled.GppGood, Modifier.weight(1f))
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.18f)))
            OutlinedButton(
                onClick = onLogout,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.42f)),
                colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.White.copy(alpha = 0.12f), contentColor = Color.White),
                modifier = Modifier.fillMaxWidth().height(50.dp)
            ) {
                Text("退出登录", fontWeight = FontWeight.Bold)
            }
        }
    }
    if (phoneChangeVisible) {
        ChangePhoneDialog(
            currentPhoneRaw = currentPhoneRaw,
            currentPhoneLabel = currentPhoneLabel,
            step = phoneChangeStep,
            busy = busy,
            oldPhoneCode = oldPhoneCode,
            newPhone = newPhone,
            newPhoneCode = newPhoneCode,
            statusText = statusText,
            oldPhoneResendSeconds = oldPhoneResendSeconds,
            newPhoneResendSeconds = newPhoneResendSeconds,
            onOldPhoneCodeChange = {
                oldPhoneVerificationToken = ""
                onClearPhoneChangeStatus()
                oldPhoneCode = it.filter { char -> char.isDigit() }.take(6)
            },
            onNewPhoneChange = {
                onClearPhoneChangeStatus()
                newPhone = it.filter { char -> char.isDigit() }.take(11)
            },
            onNewPhoneCodeChange = {
                onClearPhoneChangeStatus()
                newPhoneCode = it.filter { char -> char.isDigit() }.take(6)
            },
            onSendOldCode = {
                onClearPhoneChangeStatus()
                onSendPhoneChangeSmsCode(currentPhoneRaw)
                oldPhoneResendSeconds = 60
            },
            onSendNewCode = {
                onClearPhoneChangeStatus()
                onSendPhoneChangeSmsCode(newPhone)
                newPhoneResendSeconds = 60
            },
            onNextStep = {
                onVerifyCurrentPhoneForChange(currentPhoneRaw, oldPhoneCode.trim()) { token ->
                    oldPhoneVerificationToken = token
                    phoneChangeStep = 1
                }
            },
            onBackStep = {
                oldPhoneVerificationToken = ""
                oldPhoneCode = ""
                oldPhoneResendSeconds = 0
                onClearPhoneChangeStatus()
                phoneChangeStep = 0
            },
            onConfirm = {
                onChangePhone(currentPhoneRaw, oldPhoneVerificationToken, newPhone.trim(), newPhoneCode.trim())
            },
            onDismiss = {
                phoneChangeVisible = false
                resetPhoneChangeForm()
                onClearPhoneChangeStatus()
            }
        )
    }
}

@Composable
private fun ProfileStatCell(value: String, label: String, icon: ImageVector, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(value, color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = Color.White.copy(alpha = 0.82f), modifier = Modifier.size(16.dp))
            Text(label, color = Color.White.copy(alpha = 0.82f), fontSize = 13.sp, modifier = Modifier.padding(start = 5.dp))
        }
    }
}

@Composable
private fun ChangePhoneDialog(
    currentPhoneRaw: String,
    currentPhoneLabel: String,
    step: Int,
    busy: Boolean,
    oldPhoneCode: String,
    newPhone: String,
    newPhoneCode: String,
    statusText: String?,
    oldPhoneResendSeconds: Int,
    newPhoneResendSeconds: Int,
    onOldPhoneCodeChange: (String) -> Unit,
    onNewPhoneChange: (String) -> Unit,
    onNewPhoneCodeChange: (String) -> Unit,
    onSendOldCode: () -> Unit,
    onSendNewCode: () -> Unit,
    onNextStep: () -> Unit,
    onBackStep: () -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val oldCodeReady = oldPhoneCode.trim().length == 6
    val newPhoneReady = newPhone.trim().filter { it.isDigit() }.length == 11
    val newCodeReady = newPhoneCode.trim().length == 6
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = AppBg,
        titleContentColor = Ink,
        textContentColor = Ink,
        tonalElevation = 0.dp,
        shape = RoundedCornerShape(28.dp),
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("修改手机号", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (step == 0) "验证当前手机号" else "绑定新手机号",
                    color = Brand,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth()) {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val compactCodeRow = maxWidth < 260.dp
                    val codeButtonWidth = if (compactCodeRow) 112.dp else 118.dp
                    val codeButtonFontSize = if (compactCodeRow) 11.sp else 12.sp
                    val codeRowGap = if (compactCodeRow) 8.dp else 10.dp
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth()) {
                        if (step == 0) {
                            Text("验证码将发送至 $currentPhoneLabel", color = Muted, fontSize = 13.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(codeRowGap), verticalAlignment = Alignment.CenterVertically) {
                                VerificationCodeField(
                                    value = oldPhoneCode,
                                    onValueChange = onOldPhoneCodeChange,
                                    enabled = !busy,
                                    modifier = Modifier.weight(1f)
                                )
                                VerificationSendButton(
                                    text = if (oldPhoneResendSeconds > 0) "${oldPhoneResendSeconds}s" else "获取验证码",
                                    onClick = onSendOldCode,
                                    enabled = currentPhoneRaw.isNotBlank() && !busy && oldPhoneResendSeconds == 0,
                                    fontSize = codeButtonFontSize,
                                    modifier = Modifier.width(codeButtonWidth).height(56.dp)
                                )
                            }
                            if (!statusText.isNullOrBlank()) {
                                LoginStatusStrip(loginBusy = busy, text = statusText)
                            }
                            GradientActionButton(
                                text = "下一步",
                                onClick = onNextStep,
                                enabled = oldCodeReady && !busy,
                                modifier = Modifier.fillMaxWidth().height(48.dp)
                            )
                        } else {
                            AppOutlinedField(
                                value = newPhone,
                                onValueChange = onNewPhoneChange,
                                enabled = !busy,
                                label = "新手机号",
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                                modifier = Modifier.fillMaxWidth()
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(codeRowGap), verticalAlignment = Alignment.CenterVertically) {
                                VerificationCodeField(
                                    value = newPhoneCode,
                                    onValueChange = onNewPhoneCodeChange,
                                    enabled = !busy,
                                    modifier = Modifier.weight(1f)
                                )
                                VerificationSendButton(
                                    text = if (newPhoneResendSeconds > 0) "${newPhoneResendSeconds}s" else "获取验证码",
                                    onClick = onSendNewCode,
                                    enabled = newPhoneReady && !busy && newPhoneResendSeconds == 0,
                                    fontSize = codeButtonFontSize,
                                    modifier = Modifier.width(codeButtonWidth).height(56.dp)
                                )
                            }
                            if (!statusText.isNullOrBlank()) {
                                LoginStatusStrip(loginBusy = busy, text = statusText)
                            }
                            GradientActionButton(
                                text = if (busy) "处理中..." else "确认修改",
                                onClick = onConfirm,
                                enabled = oldCodeReady && newPhoneReady && newCodeReady && !busy,
                                modifier = Modifier.fillMaxWidth().height(48.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (step == 1) {
                    TextButton(onClick = onBackStep, enabled = !busy) {
                        Text("上一步", color = Muted)
                    }
                }
                TextButton(onClick = onDismiss, enabled = !busy) {
                    Text("取消", color = Muted)
                }
            }
        }
    )
}

@Composable
private fun ProfileStatusRow() {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("登录状态", color = Muted, fontSize = 13.sp)
            Text("账号已连接", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 5.dp))
        }
        Pill("已登录", BrandSoft, Brand)
    }
}

private fun String.toProfilePhoneLabel(): String {
    val digits = filter { it.isDigit() }
    val local = if (digits.startsWith("86") && digits.length >= 13) digits.takeLast(11) else digits
    if (local.length != 11) return trim()
    return "${local.take(3)}****${local.takeLast(4)}"
}

private fun String.isGeneratedPhoneDisplayName(): Boolean {
    val clean = trim()
    return clean.startsWith("用户 ") && clean.any { it == '*' } && clean.any { it.isDigit() }
}

@Composable
private fun ProfileValueRow(label: String, value: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, color = Muted, fontSize = 13.sp)
            Text(value, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 5.dp))
        }
        if (action != null && onAction != null) {
            TextButton(onClick = onAction) {
                Text(action, color = Brand, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun ProfileSyncCard(
    cloudSyncEnabled: Boolean,
    cloudSyncInProgress: Boolean,
    cloudSyncStatusText: String,
    unsyncedMeetingCount: Int,
    onCloudSyncChange: (Boolean) -> Unit,
    onUploadUnsynced: () -> Unit,
    onPullCloud: () -> Unit
) {
    ProfilePanel("云端同步") {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (cloudSyncEnabled) "账号内容会自动同步到云端" else "新会议会先保存在本机",
                    color = Ink,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    cloudSyncStatusText,
                    color = Muted,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            Switch(checked = cloudSyncEnabled, onCheckedChange = onCloudSyncChange, enabled = !cloudSyncInProgress)
        }

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(Modifier.size(34.dp).clip(CircleShape).background(BrandSoft), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.CloudQueue, contentDescription = null, tint = Brand, modifier = Modifier.size(19.dp))
            }
            Text("本机待上传", color = Muted, fontSize = 13.sp)
            Spacer(Modifier.weight(1f))
            Text("${unsyncedMeetingCount} 场", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            GradientActionButton(
                text = if (cloudSyncInProgress) "同步中" else "上传本机",
                onClick = onUploadUnsynced,
                enabled = !cloudSyncInProgress && unsyncedMeetingCount > 0,
                icon = Icons.Filled.UploadFile,
                modifier = Modifier.weight(1f).height(46.dp)
            )
            OutlinedButton(
                onClick = onPullCloud,
                enabled = !cloudSyncInProgress,
                shape = RoundedCornerShape(999.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = Brand,
                    disabledContentColor = Muted
                ),
                border = BorderStroke(1.dp, if (cloudSyncInProgress) Line else Brand.copy(alpha = 0.28f)),
                modifier = Modifier.weight(1f).height(46.dp)
            ) {
                Icon(Icons.Filled.CloudQueue, contentDescription = null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (cloudSyncInProgress) "同步中" else "拉取云端", maxLines = 1, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun PermissionRow(title: String, status: String, bg: Color, tint: Color, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
        Pill(status, bg, tint)
        Icon(Icons.Filled.ChevronRight, contentDescription = "管理权限", tint = Muted, modifier = Modifier.padding(start = 8.dp))
    }
}
