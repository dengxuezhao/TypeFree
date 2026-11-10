package com.typefree.ime.ime

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.typefree.ime.data.AppSettings
import com.typefree.ime.data.InputScheme
import com.typefree.ime.data.ToneStyle
import com.typefree.ime.ui.components.TypeFreeIcons
import com.typefree.ime.ui.theme.LocalTypeFreeKeyboardPalette
import com.typefree.ime.ui.theme.TypeFreeEmerald

/**
 * 4x2 Quick Tool Panel rendered inside the key area while keeping the 34.dp Top Bar stationary.
 *
 * Row 1: 剪贴板 · 键盘高度 · 输入方案 · 文风
 * Row 2: AI 补全 · 实时写入 · 词库 · 设置
 */
@Composable
fun KeyboardToolPanel(
    uiState: ImeUiState,
    settings: AppSettings,
    activeStyle: ToneStyle,
    clipboardCount: Int,
    onOpenClipboard: () -> Unit,
    onToggleHeightExpanded: () -> Unit,
    onSelectHeightLevel: (Int) -> Unit,
    onCycleInputScheme: () -> Unit,
    onOpenStylePicker: () -> Unit,
    onToggleAiCompletion: () -> Unit,
    onToggleLiveWrite: () -> Unit,
    onOpenDictionary: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val metrics = LocalKeyboardMetrics.current
    val s = metrics.elementScale

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy((6f * s).dp)
    ) {
        // Inline 5-Level Height Adjustment Strip when "键盘高度" is expanded
        AnimatedVisibility(
            visible = uiState.isToolPanelHeightExpanded,
            enter = fadeIn(tween(120)) + expandVertically(tween(140)),
            exit = fadeOut(tween(100)) + shrinkVertically(tween(120))
        ) {
            ToolPanelHeightAdjustStrip(
                activeLevel = uiState.activeHeightLevel,
                onSelectLevel = onSelectHeightLevel
            )
        }

        // Row 1: 剪贴板 · 键盘高度 · 输入方案 · 文风
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            ToolPanelTile(
                icon = TypeFreeIcons.Clipboard,
                title = "剪贴板",
                subtitle = if (clipboardCount > 0) "$clipboardCount 条记录" else "常用语",
                badgeDot = uiState.freshClipboardBannerItem != null,
                onClick = onOpenClipboard,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            )

            ToolPanelTile(
                icon = TypeFreeIcons.Tune,
                title = "键盘高度",
                subtitle = KeyboardMetrics.labelForLevel(uiState.activeHeightLevel),
                isExpandedActive = uiState.isToolPanelHeightExpanded,
                onClick = onToggleHeightExpanded,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            )

            ToolPanelTile(
                icon = TypeFreeIcons.KeyboardGrid,
                title = "输入方案",
                subtitle = shortInputSchemeLabel(uiState.activeInputScheme),
                onClick = onCycleInputScheme,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            )

            ToolPanelTile(
                icon = TypeFreeIcons.Sparkles,
                title = "文风",
                subtitle = activeStyle.name,
                onClick = onOpenStylePicker,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            )
        }

        // Row 2: AI 补全 · 实时写入 · 词库 · 设置
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            ToolPanelTile(
                icon = TypeFreeIcons.VoicePen,
                title = "AI 补全",
                subtitle = if (settings.aiCompletionEnabled) "已开启" else "已关闭",
                isToggleEnabled = settings.aiCompletionEnabled,
                onClick = onToggleAiCompletion,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            )

            ToolPanelTile(
                icon = TypeFreeIcons.VoiceWaveform,
                title = "实时写入",
                subtitle = if (settings.liveWriteToEditor) "已开启" else "已关闭",
                isToggleEnabled = settings.liveWriteToEditor,
                onClick = onToggleLiveWrite,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            )

            ToolPanelTile(
                icon = TypeFreeIcons.Book,
                title = "词库",
                subtitle = "词典与方案",
                onClick = onOpenDictionary,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            )

            ToolPanelTile(
                icon = TypeFreeIcons.Tune,
                title = "设置",
                subtitle = "偏好与模型",
                onClick = onOpenSettings,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            )
        }
    }
}

@Composable
private fun ToolPanelHeightAdjustStrip(
    activeLevel: Int,
    onSelectLevel: (Int) -> Unit
) {
    val palette = LocalTypeFreeKeyboardPalette.current
    val isDefault = activeLevel == KeyboardMetrics.DEFAULT_LEVEL

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(34.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(palette.capsuleTrackBg)
            .border(1.dp, palette.keyBorder, RoundedCornerShape(10.dp))
            .padding(horizontal = 4.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        KeyboardMetrics.ALL_LEVELS.forEach { level ->
            val selected = activeLevel == level
            val label = KeyboardMetrics.labelForLevel(level)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(7.dp))
                    .background(
                        if (selected) palette.primaryMicSolid else Color.Transparent
                    )
                    .clickable { onSelectLevel(level) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    color = if (selected) palette.onPrimaryMic else palette.keyTextPrimary,
                    fontSize = 11.5.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxHeight()
                .clip(RoundedCornerShape(7.dp))
                .background(palette.keyCapBg)
                .border(1.dp, palette.keyBorder, RoundedCornerShape(7.dp))
                .clickable(enabled = !isDefault) {
                    onSelectLevel(KeyboardMetrics.DEFAULT_LEVEL)
                }
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "恢复默认",
                color = if (isDefault) palette.keyTextMuted.copy(alpha = 0.5f) else palette.keyTextPrimary,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun ToolPanelTile(
    icon: ImageVector,
    title: String,
    subtitle: String,
    isToggleEnabled: Boolean = false,
    isExpandedActive: Boolean = false,
    badgeDot: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalTypeFreeKeyboardPalette.current
    val metrics = LocalKeyboardMetrics.current
    val s = metrics.elementScale

    val bgColor = when {
        isToggleEnabled -> TypeFreeEmerald.copy(alpha = if (palette.isDark) 0.16f else 0.12f)
        isExpandedActive -> palette.capsuleSelectedBg
        else -> palette.keyCapBg
    }
    val borderColor = when {
        isToggleEnabled -> TypeFreeEmerald.copy(alpha = 0.78f)
        isExpandedActive -> palette.keyTextPrimary.copy(alpha = 0.42f)
        else -> palette.keyBorder
    }
    val borderWidth = if (isToggleEnabled || isExpandedActive) 1.25.dp else 1.dp
    val iconTint = when {
        isToggleEnabled -> if (palette.isDark) Color(0xFF6EE7B7) else Color(0xFF047857)
        else -> palette.keyTextPrimary
    }
    val subtitleColor = when {
        isToggleEnabled -> if (palette.isDark) Color(0xFF6EE7B7) else Color(0xFF047857)
        else -> palette.keyTextMuted
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bgColor)
            .border(borderWidth, borderColor, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = (5f * s).dp),
        contentAlignment = Alignment.Center
    ) {
        if (badgeDot) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(TypeFreeEmerald)
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = iconTint,
                modifier = Modifier.size((18f * s).dp)
            )
            Spacer(modifier = Modifier.height((3f * s).dp))
            Text(
                text = title,
                color = palette.keyTextPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                color = subtitleColor,
                fontSize = 10.sp,
                fontWeight = if (isToggleEnabled) FontWeight.SemiBold else FontWeight.Normal,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

fun shortInputSchemeLabel(scheme: InputScheme): String = when (scheme) {
    InputScheme.RIME_PINYIN -> "雾凇全拼"
    InputScheme.RIME_DOUBLE_PINYIN_FLYPY -> "小鹤双拼"
    InputScheme.FLYPY_TABLE -> "小鹤音形"
    InputScheme.RIME_CUSTOM -> "自定义方案"
}

fun nextCycledInputScheme(current: InputScheme): InputScheme = when (current) {
    InputScheme.RIME_PINYIN -> InputScheme.RIME_DOUBLE_PINYIN_FLYPY
    InputScheme.RIME_DOUBLE_PINYIN_FLYPY -> InputScheme.FLYPY_TABLE
    InputScheme.FLYPY_TABLE -> InputScheme.RIME_PINYIN
    InputScheme.RIME_CUSTOM -> InputScheme.RIME_PINYIN
}
