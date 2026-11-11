package com.typefree.ime.ime.chrome

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.typefree.ime.data.AppSettings
import com.typefree.ime.data.ToneStyle
import com.typefree.ime.ime.ImeLogic
import com.typefree.ime.ime.ImeUiState
import com.typefree.ime.ime.KeyboardTabMode
import com.typefree.ime.ime.LocalKeyboardMetrics
import com.typefree.ime.ime.clipboard.ClipboardSmartExtractor
import com.typefree.ime.ime.clipboard.SmartClipKind
import com.typefree.ime.ui.components.TypeFreeIcons
import com.typefree.ime.ui.theme.LocalTypeFreeKeyboardPalette
import com.typefree.ime.ui.theme.TypeFreeEmerald

data class TopBarLeftSlotState(
    val showUndoButton: Boolean,
    val showClipboardQuickPaste: Boolean,
    val showVoicePenPill: Boolean,
    val voicePenLabel: String
)

/**
 * Pure policy for TypeFree v3.1.0 Top Bar left-area slot priority:
 * 1. When [hasUndoSnapshot] is true, [TopBarUndoIconButton] is ALWAYS kept visible so a fresh
 *    clipboard banner never swallows the 1-tap Undo safety net.
 * 2. When in [KeyboardTabMode.QWERTY] idle state (not composing), a compact monochrome
 *    `[VoicePen 帮写/改写]` pill is exposed so typing users can jump to HelpMeWrite in 1 tap.
 */
object TopBarSlotPolicy {
    fun resolveLeftSlots(
        activeTab: KeyboardTabMode,
        isComposing: Boolean,
        hasUndoSnapshot: Boolean,
        hasFreshClipboardBanner: Boolean,
        hasActiveSelection: Boolean
    ): TopBarLeftSlotState {
        if (isComposing) {
            return TopBarLeftSlotState(
                showUndoButton = false,
                showClipboardQuickPaste = false,
                showVoicePenPill = false,
                voicePenLabel = if (hasActiveSelection) "改写" else "帮写"
            )
        }
        val showVoicePen = activeTab == KeyboardTabMode.QWERTY ||
            (activeTab != KeyboardTabMode.VOICE && hasActiveSelection)
        return TopBarLeftSlotState(
            showUndoButton = hasUndoSnapshot,
            showClipboardQuickPaste = hasFreshClipboardBanner,
            showVoicePenPill = showVoicePen,
            voicePenLabel = if (hasActiveSelection) "改写" else "帮写"
        )
    }
}

@Composable
fun TopBarToolPanelButton(
    isOpen: Boolean,
    onClick: () -> Unit
) {
    val palette = LocalTypeFreeKeyboardPalette.current
    val bgColor = if (isOpen) palette.primaryMicSolid else palette.keyCapBg
    val borderColor = if (isOpen) Color.Transparent else palette.keyBorder
    val iconTint = if (isOpen) palette.onPrimaryMic else palette.keyTextPrimary

    Box(
        modifier = Modifier
            .requiredSize(28.dp)
            .clip(CircleShape)
            .background(bgColor)
            .border(1.dp, borderColor, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = TypeFreeIcons.LogoMark,
            contentDescription = if (isOpen) "收起工具面板" else "展开工具面板",
            tint = iconTint,
            modifier = Modifier.size(15.dp)
        )
    }
}

@Composable
fun TypeFreeTopBar(
    uiState: ImeUiState,
    settings: AppSettings,
    activeStyle: ToneStyle,
    isAutoMatched: Boolean,
    onToggleToolPanel: () -> Unit,
    onSelectTabMode: (KeyboardTabMode) -> Unit,
    onOpenHelpMeWrite: () -> Unit,
    onToggleFlypyLanguageMode: () -> Unit,
    onToggleStylePicker: () -> Unit,
    onPerformUndo: () -> Unit,
    onPasteClipboardText: (String) -> Unit,
    onToggleManualIncognito: () -> Unit = {}
) {
    val palette = LocalTypeFreeKeyboardPalette.current
    val metrics = LocalKeyboardMetrics.current
    val density = LocalDensity.current
    val swipeThresholdPx = with(density) { 42.dp.toPx() }
    val currentTab by rememberUpdatedState(uiState.tabMode)
    val currentOnSelectTab by rememberUpdatedState(onSelectTabMode)
    val freshClip = if (settings.clipboardEnabled && settings.clipboardQuickPasteSuggestion) {
        uiState.freshClipboardBannerItem
    } else {
        null
    }

    val slotState = remember(
        uiState.tabMode,
        uiState.isFlypyComposing,
        uiState.undoSnapshot,
        freshClip,
        uiState.rawSelectedText
    ) {
        TopBarSlotPolicy.resolveLeftSlots(
            activeTab = uiState.tabMode,
            isComposing = uiState.isFlypyComposing,
            hasUndoSnapshot = uiState.undoSnapshot != null,
            hasFreshClipboardBanner = freshClip != null,
            hasActiveSelection = uiState.rawSelectedText.isNotBlank()
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(metrics.topBarHeight)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val start = down.position
                    var last = start
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        last = change.position
                        if (!change.pressed) break
                    }
                    val next = ImeLogic.resolveNextTabByHorizontalSwipe(
                        currentTab = currentTab,
                        dragOffsetX = last.x - start.x,
                        dragOffsetY = last.y - start.y,
                        thresholdPx = swipeThresholdPx
                    )
                    if (next != null) {
                        currentOnSelectTab(next)
                    }
                }
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Left: 28.dp Circular Brand Expand Button + Smart Paste / VoicePen / Scene Badge / Undo / Toast
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            TopBarToolPanelButton(
                isOpen = uiState.isToolPanelOpen,
                onClick = onToggleToolPanel
            )

            // 1. Always-visible Undo button when undoSnapshot != null (never swallowed by freshClip!)
            if (slotState.showUndoButton) {
                TopBarUndoIconButton(onClick = onPerformUndo)
            }

            // 2. Compact monochrome VoicePen pill in QWERTY idle state or when selection is active
            if (slotState.showVoicePenPill) {
                val isSelectionRewrite = uiState.shouldShowTopBarSpeakToEditPill
                Row(
                    modifier = Modifier
                        .height(26.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(if (isSelectionRewrite) palette.primaryMicSolid else palette.keyCapBg)
                        .border(
                            width = 1.dp,
                            color = if (isSelectionRewrite) Color.Transparent else palette.keyBorder,
                            shape = RoundedCornerShape(13.dp)
                        )
                        .clickable(onClick = onOpenHelpMeWrite)
                        .padding(horizontal = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Icon(
                        imageVector = TypeFreeIcons.VoicePen,
                        contentDescription = slotState.voicePenLabel,
                        tint = if (isSelectionRewrite) palette.onPrimaryMic else palette.keyTextPrimary,
                        modifier = Modifier.size(11.5.dp)
                    )
                    Text(
                        text = slotState.voicePenLabel,
                        color = if (isSelectionRewrite) palette.onPrimaryMic else palette.keyTextPrimary,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }
            }

            if (uiState.isIncognitoMode) {
                Row(
                    modifier = Modifier
                        .height(24.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(TypeFreeEmerald.copy(alpha = 0.16f))
                        .border(1.dp, TypeFreeEmerald.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
                        .clickable(onClick = onToggleManualIncognito)
                        .padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    val incTint = if (palette.isDark) Color(0xFF6EE7B7) else Color(0xFF047857)
                    Icon(
                        imageVector = TypeFreeIcons.IncognitoMask,
                        contentDescription = "无痕模式",
                        tint = incTint,
                        modifier = Modifier.size(11.dp)
                    )
                    Text(
                        text = "无痕",
                        color = incTint,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }
            }

            val learnedTerm = uiState.autoLearnedToastTerm
            val quickToast = uiState.quickActionToast
            if (!learnedTerm.isNullOrBlank()) {
                Box(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .clip(RoundedCornerShape(12.dp))
                        .background(TypeFreeEmerald.copy(alpha = 0.15f))
                        .border(1.dp, TypeFreeEmerald.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = "✓ 已自动收入词典「$learnedTerm」",
                        color = if (palette.isDark) Color(0xFF6EE7B7) else Color(0xFF047857),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            } else if (!quickToast.isNullOrBlank()) {
                Box(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .clip(RoundedCornerShape(12.dp))
                        .background(palette.capsuleSelectedBg)
                        .border(1.dp, palette.keyBorder, RoundedCornerShape(12.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = quickToast,
                        color = palette.keyTextPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            } else if (slotState.showClipboardQuickPaste && freshClip != null) {
                val smart = remember(freshClip.text) {
                    ClipboardSmartExtractor.extractPrimary(freshClip.text)
                }
                val hasDistinctSmartEntity = smart != null && smart.extractedValue != freshClip.text.trim()
                val clipTint = if (palette.isDark) Color(0xFF6EE7B7) else Color(0xFF047857)
                if (hasDistinctSmartEntity && smart != null) {
                    val smartIcon: ImageVector = when (smart.kind) {
                        SmartClipKind.OTP_CODE -> TypeFreeIcons.HashCode
                        SmartClipKind.PICKUP_CODE -> TypeFreeIcons.PackageBox
                        else -> TypeFreeIcons.Clipboard
                    }
                    Row(
                        modifier = Modifier.weight(1f, fill = false),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .height(26.dp)
                                .clip(RoundedCornerShape(13.dp))
                                .background(TypeFreeEmerald.copy(alpha = 0.15f))
                                .border(1.dp, TypeFreeEmerald.copy(alpha = 0.48f), RoundedCornerShape(13.dp))
                                .clickable { onPasteClipboardText(smart.extractedValue) }
                                .padding(horizontal = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Icon(
                                imageVector = smartIcon,
                                contentDescription = null,
                                tint = clipTint,
                                modifier = Modifier.size(11.dp)
                            )
                            Text(
                                text = smart.extractedValue,
                                color = clipTint,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Row(
                            modifier = Modifier
                                .height(26.dp)
                                .clip(RoundedCornerShape(13.dp))
                                .background(palette.keyCapBg)
                                .border(1.dp, palette.keyBorder, RoundedCornerShape(13.dp))
                                .clickable { onPasteClipboardText(freshClip.text) }
                                .padding(horizontal = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Icon(
                                imageVector = TypeFreeIcons.Clipboard,
                                contentDescription = null,
                                tint = palette.keyTextPrimary,
                                modifier = Modifier.size(10.5.dp)
                            )
                            Text(
                                text = "全文",
                                color = palette.keyTextPrimary,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1
                            )
                        }
                    }
                } else {
                    val previewText = ClipboardSmartExtractor.formatSingleLinePreview(freshClip.text, 7)
                    Row(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .height(26.dp)
                            .clip(RoundedCornerShape(13.dp))
                            .background(TypeFreeEmerald.copy(alpha = 0.15f))
                            .border(1.dp, TypeFreeEmerald.copy(alpha = 0.48f), RoundedCornerShape(13.dp))
                            .clickable { onPasteClipboardText(freshClip.text) }
                            .padding(horizontal = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Icon(
                            imageVector = TypeFreeIcons.Clipboard,
                            contentDescription = null,
                            tint = clipTint,
                            modifier = Modifier.size(11.dp)
                        )
                        Text(
                            text = "粘贴「$previewText」",
                            color = clipTint,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            } else {
                // Compact Clickable Scene Style Badge
                val badgeLabel = ImeLogic.buildCompactStyleBadgeText(activeStyle, isAutoMatched)
                val chevron = if (uiState.isStylePickerExpanded) "▴" else "▾"
                Row(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .height(26.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(
                            if (uiState.isStylePickerExpanded) palette.capsuleSelectedBg else palette.keyCapBg
                        )
                        .border(1.dp, palette.keyBorder, RoundedCornerShape(13.dp))
                        .clickable(onClick = onToggleStylePicker)
                        .padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = badgeLabel,
                        color = palette.keyTextMuted,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Text(
                        text = chevron,
                        color = palette.keyTextMuted,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(4.dp))

        // Right Cluster: Authentic 3-Segment Capsule Track (`VOICE` | `拼/鹤/EN` | `文A`)
        Row(
            modifier = Modifier
                .height(32.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(palette.capsuleTrackBg)
                .border(1.dp, palette.keyBorder, RoundedCornerShape(16.dp))
                .padding(3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            // Segment 1: Voice Dictation (`TypeFreeIcons.VoiceWaveform`)
            ModeSegmentItem(
                selected = uiState.tabMode == KeyboardTabMode.VOICE && !uiState.isToolPanelOpen,
                onClick = { onSelectTabMode(KeyboardTabMode.VOICE) }
            ) { contentColor ->
                Icon(
                    imageVector = TypeFreeIcons.VoiceWaveform,
                    contentDescription = "语音模式",
                    tint = contentColor,
                    modifier = Modifier.size(16.dp)
                )
            }

            // Segment 2: Dynamic Input Scheme (`拼` / `鹤`) / English (`EN`)
            val middleBadge = if (uiState.isFlypyChineseMode) {
                uiState.activeInputScheme.shortBadge
            } else {
                "EN"
            }
            ModeSegmentItem(
                selected = uiState.tabMode == KeyboardTabMode.QWERTY && !uiState.isToolPanelOpen,
                onClick = {
                    if (uiState.tabMode == KeyboardTabMode.QWERTY && !uiState.isToolPanelOpen) {
                        onToggleFlypyLanguageMode()
                    } else {
                        onSelectTabMode(KeyboardTabMode.QWERTY)
                    }
                }
            ) { contentColor ->
                Text(
                    text = middleBadge,
                    color = contentColor,
                    fontSize = if (uiState.isFlypyChineseMode) 12.sp else 11.5.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // Segment 3: Real-time Translate (`文A`)
            ModeSegmentItem(
                selected = uiState.tabMode == KeyboardTabMode.TRANSLATE && !uiState.isToolPanelOpen,
                onClick = { onSelectTabMode(KeyboardTabMode.TRANSLATE) }
            ) { contentColor ->
                Text(
                    text = "文A",
                    color = contentColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
fun TopBarUndoIconButton(
    onClick: () -> Unit
) {
    val palette = LocalTypeFreeKeyboardPalette.current
    Box(
        modifier = Modifier
            .requiredSize(26.dp)
            .clip(CircleShape)
            .background(palette.keyCapBg)
            .border(1.dp, palette.keyBorder, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = TypeFreeIcons.Undo,
            contentDescription = "撤销",
            tint = palette.keyTextPrimary,
            modifier = Modifier.size(13.dp)
        )
    }
}

@Composable
private fun ModeSegmentItem(
    selected: Boolean,
    onClick: () -> Unit,
    content: @Composable (Color) -> Unit
) {
    val palette = LocalTypeFreeKeyboardPalette.current
    val contentColor = if (selected) palette.keyTextPrimary else palette.keyTextMuted

    Box(
        modifier = Modifier
            .width(33.dp)
            .fillMaxHeight()
            .clip(RoundedCornerShape(13.dp))
            .background(if (selected) palette.capsuleSelectedBg else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        content(contentColor)
    }
}

@Composable
fun InlineStyleSelectorStrip(
    settings: AppSettings,
    onSelectAutoStyle: () -> Unit,
    onSelectManualStyle: (ToneStyle) -> Unit,
    onOpenStyleRules: () -> Unit
) {
    val palette = LocalTypeFreeKeyboardPalette.current
    val isAutoSelected = settings.selectedManualStyleId == "auto"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StyleChoicePill(
            text = "自动场景感知",
            leadingIcon = TypeFreeIcons.Sparkles,
            selected = isAutoSelected,
            onClick = onSelectAutoStyle
        )

        settings.styles.forEach { style ->
            val selected = !isAutoSelected && settings.selectedManualStyleId == style.id
            StyleChoicePill(
                text = "${style.emoji} ${style.name}",
                selected = selected,
                onClick = { onSelectManualStyle(style) }
            )
        }

        Box(
            modifier = Modifier
                .fillMaxHeight()
                .clip(RoundedCornerShape(14.dp))
                .background(palette.keyCapBg)
                .border(1.dp, palette.keyBorder, RoundedCornerShape(14.dp))
                .clickable(onClick = onOpenStyleRules)
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "管理风格 ›",
                color = palette.keyTextMuted,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun StyleChoicePill(
    text: String,
    leadingIcon: ImageVector? = null,
    selected: Boolean,
    onClick: () -> Unit
) {
    val palette = LocalTypeFreeKeyboardPalette.current
    val fgColor = if (selected) palette.onPrimaryMic else palette.keyTextPrimary
    Row(
        modifier = Modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) palette.primaryMicSolid else palette.keyCapBg)
            .border(
                width = 1.dp,
                color = if (selected) Color.Transparent else palette.keyBorder,
                shape = RoundedCornerShape(14.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                tint = fgColor,
                modifier = Modifier.size(12.dp)
            )
        }
        Text(
            text = text,
            color = fgColor,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
        )
    }
}
