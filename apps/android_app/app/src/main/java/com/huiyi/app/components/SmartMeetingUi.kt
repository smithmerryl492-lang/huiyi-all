package com.huiyi.app.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.huiyi.app.R
import com.huiyi.app.model.AppScreen
import com.huiyi.app.ui.AppBg
import com.huiyi.app.ui.Brand
import com.huiyi.app.ui.BrandCyan
import com.huiyi.app.ui.BrandDark
import com.huiyi.app.ui.BrandPurple
import com.huiyi.app.ui.BrandSoft
import com.huiyi.app.ui.HeaderBlue
import com.huiyi.app.ui.HeaderCyan
import com.huiyi.app.ui.Ink
import com.huiyi.app.ui.Line
import com.huiyi.app.ui.Muted
import com.huiyi.app.ui.PageBlueMid
import com.huiyi.app.ui.PageBlueTop

val SmartPageBrush: Brush
    get() = Brush.verticalGradient(
        listOf(
            PageBlueTop,
            PageBlueMid,
            Color(0xFFF5F8FF),
            AppBg
        )
    )

val SmartHeaderBrush: Brush
    get() = Brush.verticalGradient(
        listOf(
            HeaderBlue,
            HeaderCyan,
            Color(0x005A9EFF)
        )
    )

val SmartPrimaryBrush: Brush
    get() = Brush.horizontalGradient(listOf(Brand, Color(0xFF7CA8FF), BrandCyan))

val SmartRecordBrush: Brush
    get() = Brush.horizontalGradient(listOf(BrandCyan.copy(alpha = 0.82f), BrandPurple.copy(alpha = 0.84f)))

@Composable
fun SmartPageBackground(content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SmartPageBrush),
        content = content
    )
}

@Composable
fun SmartWhiteCard(
    modifier: Modifier = Modifier,
    radius: Dp = 16.dp,
    color: Color = Color.White.copy(alpha = 0.96f),
    borderColor: Color = Color.White.copy(alpha = 0.72f),
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        color = color,
        shape = RoundedCornerShape(radius),
        border = BorderStroke(1.dp, borderColor),
        shadowElevation = 3.dp,
        modifier = modifier
    ) {
        androidx.compose.foundation.layout.Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content
        )
    }
}

@Composable
fun SmartRoundIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = BrandDark,
    container: Color = Color.White.copy(alpha = 0.88f)
) {
    Surface(
        color = container,
        shape = CircleShape,
        shadowElevation = 2.dp,
        modifier = modifier
            .size(40.dp)
            .clickable(onClick = onClick)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(21.dp))
        }
    }
}

@Composable
fun SmartSearchPill(
    text: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null
) {
    Surface(
        color = Color.White.copy(alpha = 0.30f),
        shape = RoundedCornerShape(999.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.18f)),
        shadowElevation = 0.dp,
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .clickable(enabled = onClick != null) { onClick?.invoke() }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
            Text(
                text,
                color = Color.White,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .weight(1f)
            )
            trailing?.invoke(this)
        }
    }
}

@Composable
fun SmartSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    primaryAction: String? = null,
    onPrimaryAction: (() -> Unit)? = null,
    secondaryAction: String? = null,
    onSecondaryAction: (() -> Unit)? = null
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        if (primaryAction != null && onPrimaryAction != null) {
            Text(
                primaryAction,
                color = Muted,
                fontSize = 14.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onPrimaryAction)
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            )
        }
        if (secondaryAction != null && onSecondaryAction != null) {
            Text(
                secondaryAction,
                color = Brand,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onSecondaryAction)
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            )
        }
    }
}

@Composable
fun SmartHeaderTexture(modifier: Modifier = Modifier, alpha: Float = 1f) {
    Image(
        painter = painterResource(id = R.drawable.smart_meeting_header_bg),
        contentDescription = null,
        contentScale = ContentScale.FillWidth,
        alpha = alpha,
        modifier = modifier
    )
}

@Composable
fun SmartStatusDot(text: String, active: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (active) Color.White.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.10f))
            .padding(horizontal = 9.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(if (active) Color(0xFF4ADE80) else Color.White.copy(alpha = 0.72f))
        )
        Text(
            text,
            color = Color.White.copy(alpha = 0.90f),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            modifier = Modifier.padding(start = 6.dp)
        )
    }
}

@Composable
fun SmartBottomNavigationBar(
    roots: List<AppScreen>,
    current: AppScreen,
    onSelect: (AppScreen) -> Unit
) {
    Surface(
        color = Color.White.copy(alpha = 0.96f),
        shadowElevation = 8.dp,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.82f)),
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            roots.forEach { item ->
                val selected = current == item
                val contentColor = if (selected) Brand else Color(0xFF8C93A0)
                ColumnNavItem(
                    item = item,
                    selected = selected,
                    contentColor = contentColor,
                    onClick = { onSelect(item) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun ColumnNavItem(
    item: AppScreen,
    selected: Boolean,
    contentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    androidx.compose.foundation.layout.Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) BrandSoft else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Icon(item.icon, contentDescription = item.title, tint = contentColor, modifier = Modifier.size(23.dp))
        Text(item.title, color = contentColor, fontSize = 12.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
    }
}

@Composable
fun SmartGradientIcon(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    tint: Color = Brand
) {
    Surface(
        color = Color.White.copy(alpha = 0.72f),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.72f)),
        shadowElevation = 2.dp,
        modifier = modifier.size(48.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
        }
    }
}

@Composable
fun SmartGradientPanel(
    modifier: Modifier = Modifier,
    radius: Dp = 24.dp,
    brush: Brush = Brush.horizontalGradient(listOf(BrandCyan, Color(0xFF7B9FFF), BrandPurple)),
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(radius))
            .background(brush),
        content = content
    )
}
