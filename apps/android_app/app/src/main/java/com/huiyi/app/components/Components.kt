package com.huiyi.app.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.huiyi.app.ui.AppBg
import com.huiyi.app.ui.Brand
import com.huiyi.app.ui.BrandCyan
import com.huiyi.app.ui.BrandDark
import com.huiyi.app.ui.BrandSoft
import com.huiyi.app.ui.BrandSoftCyan
import com.huiyi.app.ui.Ink
import com.huiyi.app.ui.Line
import com.huiyi.app.ui.Muted
import kotlin.math.sin

@Composable
private fun adaptiveHorizontalPadding(): Dp {
    val width = LocalConfiguration.current.screenWidthDp
    return when {
        width < 360 -> 14.dp
        width < 400 -> 16.dp
        else -> 20.dp
    }
}

@Composable
fun ScreenScaffold(
    title: String,
    onBack: () -> Unit,
    closeIcon: ImageVector = Icons.Filled.ArrowBack,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val horizontalPadding = adaptiveHorizontalPadding()
    Box(Modifier.fillMaxSize().background(SmartPageBrush)) {
        Box(
            modifier = Modifier
                .offset(x = (-96).dp, y = (-108).dp)
                .size(280.dp)
                .background(
                    Brush.radialGradient(
                        listOf(Color(0x40A78BFA), Color.Transparent)
                    ),
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
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = horizontalPadding,
                    end = horizontalPadding,
                    top = 4.dp,
                    bottom = 28.dp
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = {
                    item {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            content = content
                        )
                    }
                }
            )
        }
    }
}

@Composable
fun InfoBlock(title: String, action: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val compact = LocalConfiguration.current.screenWidthDp < 400
    Surface(
        color = Color.White.copy(alpha = 0.96f),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.72f)),
        shadowElevation = 3.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(if (compact) 16.dp else 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    color = Ink,
                    fontSize = if (compact) 17.sp else 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                if (action != null) {
                    IconButton(onClick = action, modifier = Modifier.size(34.dp)) {
                        Icon(Icons.Filled.Edit, contentDescription = "编辑", tint = Brand)
                    }
                }
            }
            Spacer(Modifier.height(if (compact) 8.dp else 10.dp))
            content()
        }
    }
}

@Composable
fun RowItem(icon: ImageVector, title: String, subtitle: String? = null, onClick: (() -> Unit)? = null) {
    val compact = LocalConfiguration.current.screenWidthDp < 400
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = onClick != null) { onClick?.invoke() }
            .padding(vertical = if (compact) 8.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(BrandSoftCyan),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = BrandDark, modifier = Modifier.size(21.dp))
        }
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(title, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    color = Muted,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 4.dp),
                    lineHeight = 18.sp
                )
            }
        }
        if (onClick != null) {
            Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = Ink)
        }
    }
}

@Composable
fun SectionTitle(title: String, action: String = "", onAction: () -> Unit = {}) {
    val compact = LocalConfiguration.current.screenWidthDp < 400
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Ink, fontSize = if (compact) 21.sp else 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        if (action.isNotBlank()) {
            TextButton(onClick = onAction) { Text(action, color = Brand, fontSize = 14.sp) }
        }
    }
}

@Composable
fun IconBox(icon: ImageVector, bg: Color, tint: Color) {
    Box(
        Modifier.size(42.dp).clip(CircleShape).background(bg),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
    }
}

@Composable
fun Pill(text: String, bg: Color, color: Color, modifier: Modifier = Modifier) {
    Surface(color = bg, shape = RoundedCornerShape(999.dp), modifier = modifier) {
        Text(
            text,
            color = color,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
        )
    }
}

@Composable
fun WaveBars(
    modifier: Modifier = Modifier,
    active: Boolean = true,
    levelPercent: Int? = null,
    barCount: Int = 6,
    maxHeight: Dp = 30.dp,
    barWidth: Dp = 5.dp,
    gap: Dp = 6.dp
) {
    val transition = rememberInfiniteTransition(label = "wave-bars")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 6.28f,
        animationSpec = infiniteRepeatable(animation = tween(durationMillis = 920), repeatMode = RepeatMode.Restart),
        label = "wave-phase"
    )
    val levelScale = levelPercent?.let { (0.45f + it.coerceIn(0, 100) / 100f * 0.75f).coerceIn(0.45f, 1.2f) } ?: 1f
    val baseHeights = listOf(14f, 23f, 18f, 30f, 22f, 27f, 16f, 25f, 20f, 28f, 17f, 24f)
    Row(modifier.height(maxHeight), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(gap)) {
        repeat(barCount) { index ->
            val pulse = if (active) {
                ((sin((phase + index * 0.62f).toDouble()).toFloat() + 1f) / 2f)
            } else {
                0.36f
            }
            val height = (baseHeights[index % baseHeights.size] * (0.72f + pulse * 0.42f) * levelScale)
                .coerceIn(6f, maxHeight.value)
            Box(
                Modifier
                    .width(barWidth)
                    .height(height.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (index == 3) BrandCyan else Color(0xFF7E91FF))
            )
        }
    }
}

@Composable
fun TranscriptLine(
    name: String,
    text: String,
    time: String,
    timeRange: String = time,
    showSpeakerLabel: Boolean = true,
    onSpeakerClick: (() -> Unit)? = null,
    onCopyClick: (() -> Unit)? = null
) {
    val compact = LocalConfiguration.current.screenWidthDp < 400
    Surface(
        color = Color.White.copy(alpha = 0.96f),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.72f)),
        shadowElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(if (compact) 14.dp else 16.dp), verticalAlignment = Alignment.Top) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (!showSpeakerLabel) {
                    Box(
                        modifier = Modifier
                            .padding(top = 6.dp)
                            .size(width = 6.dp, height = 18.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(Brand.copy(alpha = 0.85f))
                    )
                } else if (onSpeakerClick == null) {
                    Pill(name, BrandSoft, Brand)
                } else {
                    Box(
                        modifier = Modifier
                            .heightIn(min = 44.dp)
                            .clickable { onSpeakerClick() },
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Pill(name, BrandSoft, Brand)
                    }
                }
                if (onCopyClick != null) {
                    Spacer(Modifier.height(6.dp))
                    Surface(
                        color = BrandSoft.copy(alpha = 0.72f),
                        shape = CircleShape,
                        modifier = Modifier
                            .size(32.dp)
                            .clickable { onCopyClick() }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Filled.ContentCopy,
                                contentDescription = "复制片段",
                                tint = Brand,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    }
                }
            }
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text(text, color = Ink, fontSize = 15.sp, lineHeight = 23.sp)
                Text("定位 $timeRange", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

@Composable
fun SourceButton(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.padding(top = 8.dp)) {
        Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Brand, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(4.dp))
        Text(text, color = Brand)
    }
}

@Composable
fun Bullet(text: String) {
    Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 8.dp).size(6.dp).clip(CircleShape).background(Brand))
        Text(text, color = Ink, fontSize = 15.sp, lineHeight = 23.sp, modifier = Modifier.padding(start = 10.dp))
    }
}

@Composable
fun TaskRow(
    title: String,
    subtitle: String,
    done: Boolean,
    onClick: () -> Unit,
    onDelete: (() -> Unit)? = null,
    onReminder: (() -> Unit)? = null
) {
    Surface(
        color = Color.White,
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, Line),
        modifier = Modifier.fillMaxWidth().clickable { onClick() }
    ) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.Top) {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = if (done) Brand else Muted,
                modifier = Modifier.size(22.dp).padding(top = 2.dp)
            )
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text(title, color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text(subtitle, color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (onReminder != null) {
                    IconButton(onClick = onReminder, modifier = Modifier.size(38.dp)) {
                        Icon(Icons.Filled.Event, contentDescription = "提醒", tint = Brand)
                    }
                }
                if (onDelete != null) {
                    IconButton(onClick = onDelete, modifier = Modifier.size(38.dp)) {
                        Icon(Icons.Filled.DeleteOutline, contentDescription = "删除", tint = com.huiyi.app.ui.Danger)
                    }
                }
            }
        }
    }
}

@Composable
fun ActionButton(
    icon: ImageVector,
    text: String,
    modifier: Modifier,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    Box(
        modifier = modifier
            .height(48.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(
                Brush.horizontalGradient(
                    if (enabled) listOf(Brand, BrandCyan) else listOf(Color(0xFFE8EEF8), Color(0xFFE8EEF8))
                )
            )
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val contentColor = if (enabled) Color.White else Muted
            Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp))
            Text(text, color = contentColor, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun SheetContent(
    title: String,
    centerTitle: Boolean = false,
    showHandle: Boolean = true,
    backgroundColor: Color = Color(0xFFF0F5FF),
    content: @Composable ColumnScope.() -> Unit
) {
    val compact = LocalConfiguration.current.screenWidthDp < 400
    Column(
        Modifier
            .fillMaxWidth()
            .background(backgroundColor)
            .padding(horizontal = if (compact) 16.dp else 20.dp, vertical = 8.dp)
    ) {
        if (showHandle) {
            Box(
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .width(36.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(Brand.copy(alpha = 0.22f))
            )
            Spacer(Modifier.height(14.dp))
        }
        if (title.isNotBlank()) {
            Text(
                title,
                color = Ink,
                fontSize = if (compact) 20.sp else 22.sp,
                fontWeight = FontWeight.Bold,
                textAlign = if (centerTitle) TextAlign.Center else TextAlign.Start,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(if (compact) 10.dp else 12.dp))
        }
        content()
        Spacer(Modifier.height(if (compact) 14.dp else 20.dp))
    }
}
