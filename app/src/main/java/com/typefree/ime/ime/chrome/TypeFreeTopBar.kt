package com.typefree.ime.ime.chrome

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.view.HapticFeedbackConstants
import android.view.inputmethod.EditorInfo
import com.typefree.ime.data.AppSettings
import com.typefree.ime.data.ToneStyle
import com.typefree.ime.i18n.AppLang
import com.typefree.ime.i18n.AppStrings
import com.typefree.ime.i18n.LocalAppLanguage
import com.typefree.ime.i18n.tr
import com.typefree.ime.ime.ImeLogic
import com.typefree.ime.ime.ImeUiState
import com.typefree.ime.ime.KeyboardTabMode
import com.typefree.ime.ime.LocalKeyboardMetrics
import com.typefree.ime.ime.VoicePhase
import com.typefree.ime.ime.clipboard.ClipboardSmartExtractor
import com.typefree.ime.ime.clipboard.SmartClipKind
import com.typefree.ime.ui.components.TypeFreeIcons
import com.typefree.ime.ui.theme.LocalTypeFreeKeyboardPalette
import com.typefree.ime.ui.theme.TypeFreeEmerald
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class TopBarLeftSlotState(
    val showUndoButton: Boolean,
    val showClipboardQuickPaste: Boolean,
    val showVoicePenPill: Boolean,
    val voicePenLabel: String,
    val showJevCopilotPill: Boolean = false,
    val showClipboardButton: Boolean = true,
    val showStyleBadge: Boolean = false,
    val showQwertyHoldVoiceCapsule: Boolean = false
)

data class QwertyTopBarLayoutPlan(
    val bannerTakesOverMiddle: Boolean,
    val showClipboardIconButton: Boolean,
    val showRightAskAi: Boolean,
    val showRightJevCopilot: Boolean,
    val showRightVoicePen: Boolean,
    val showLeftInlineBanner: Boolean
)

/**
 * Pure policy for TypeFree v3.4.0 Top Bar slot priority & intent convergence:
 * 1. When [hasUndoSnapshot] is true, [TopBarUndoIconButton] is ALWAYS kept visible so a fresh
 *    clipboard banner never swallows the 1-tap Undo safety net.
 * 2. A permanent 26.dp [TopBarClipboardIconButton] (`[📋]`) is always exposed when not composing
 *    for 1-tap clipboard access, with a green notification dot when [hasFreshClipboardBanner] is true.
 * 3. The AI Tone Style badge (`💼 职场▾`) is ONLY shown in [KeyboardTabMode.VOICE] (hidden in QWERTY).
 * 4. In [KeyboardTabMode.QWERTY] idle state, the right cluster converges to `[✦ 军师]` + `[✍️ 帮写/改写]`
 *    + dark primary `[🎤 语音]` capsule (tap `< 220ms` switches to Voice panel; hold `>= 220ms` dictates in-place).
 */
object TopBarSlotPolicy {
    fun resolveLeftSlots(
        activeTab: KeyboardTabMode,
        isComposing: Boolean,
        hasUndoSnapshot: Boolean,
        hasFreshClipboardBanner: Boolean,
        hasActiveSelection: Boolean,
        jevCopilotEnabled: Boolean = true,
        lang: AppLang = AppLang.ZH_CN
    ): TopBarLeftSlotState {
        val penLabel = if (hasActiveSelection) {
            AppStrings.pick(lang, "改写", "Rewrite", "改寫")
        } else {
            AppStrings.pick(lang, "帮写", "Write", "幫寫")
        }
        if (isComposing) {
            return TopBarLeftSlotState(
                showUndoButton = false,
                showClipboardQuickPaste = false,
                showVoicePenPill = false,
                voicePenLabel = penLabel,
                showJevCopilotPill = false,
                showClipboardButton = false,
                showStyleBadge = false,
                showQwertyHoldVoiceCapsule = false
            )
        }
        val showVoicePen = activeTab == KeyboardTabMode.QWERTY ||
            (activeTab != KeyboardTabMode.VOICE && hasActiveSelection)
        val showJevPill = jevCopilotEnabled &&
            (activeTab == KeyboardTabMode.QWERTY || activeTab == KeyboardTabMode.VOICE)
        return TopBarLeftSlotState(
            showUndoButton = hasUndoSnapshot,
            showClipboardQuickPaste = hasFreshClipboardBanner,
            showVoicePenPill = showVoicePen,
            voicePenLabel = penLabel,
            showJevCopilotPill = showJevPill,
            showClipboardButton = true,
            showStyleBadge = activeTab == KeyboardTabMode.VOICE,
            showQwertyHoldVoiceCapsule = activeTab == KeyboardTabMode.QWERTY
        )
    }

    const val MIN_INLINE_BANNER_WIDTH_DP = 72f
    private const val WIDTH_TOOL_BUTTON_DP = 28f
    private const val WIDTH_SMALL_ICON_SLOT_DP = 31f // 26dp icon + 5dp gap
    private const val WIDTH_INCOGNITO_BADGE_DP = 52f
    private const val WIDTH_GAP_BETWEEN_CLUSTERS_DP = 6f
    private const val WIDTH_VOICE_CAPSULE_DP = 64f
    private const val WIDTH_RIGHT_PILL_SLOT_DP = 60f // ~55dp pill + 5dp gap

    fun shouldShowTopBarAskAiPill(
        settings: AppSettings,
        activeTab: KeyboardTabMode,
        isComposing: Boolean,
        isToolPanelOpen: Boolean,
        isClipboardOpen: Boolean,
        currentInputType: Int,
        currentEditorText: String,
        currentImeOptions: Int = 0
    ): Boolean {
        if (!settings.equalEqualAskAiEnabled) return false
        if (activeTab != KeyboardTabMode.QWERTY || isComposing || isToolPanelOpen || isClipboardOpen) return false
        if (ImeLogic.isPasswordEditorInputType(currentInputType)) return false
        val imeAction = currentImeOptions and EditorInfo.IME_MASK_ACTION
        if (imeAction == EditorInfo.IME_ACTION_SEARCH || imeAction == EditorInfo.IME_ACTION_GO) {
            return false
        }
        val inputClass = currentInputType and android.text.InputType.TYPE_MASK_CLASS
        if (inputClass == android.text.InputType.TYPE_CLASS_NUMBER ||
            inputClass == android.text.InputType.TYPE_CLASS_PHONE ||
            inputClass == android.text.InputType.TYPE_CLASS_DATETIME
        ) {
            return false
        }
        return currentEditorText.trim().length >= 2
    }

    /**
     * Resolves QWERTY idle top-bar width budget so transient banners (clipboard quick paste,
     * in-place voice status, auto-learn / quick toasts) temporarily take over the middle lane
     * instead of getting squished into an unreadable `[·]` sliver alongside 4 right-side pills.
     */
    fun resolveQwertyBarLayout(
        availableWidthDp: Float,
        showUndoButton: Boolean,
        showClipboardButton: Boolean,
        isIncognito: Boolean,
        hasPriorityBanner: Boolean,
        isClipboardBannerActive: Boolean,
        wantAskAi: Boolean,
        wantJevCopilot: Boolean,
        wantVoicePen: Boolean,
        lang: AppLang = AppLang.ZH_CN
    ): QwertyTopBarLayoutPlan {
        // When a fresh clipboard paste banner takes over the middle, hide the redundant
        // clipboard icon on the left to maximize readable preview space (user can dismiss via ×).
        val effectiveClipboardIcon = showClipboardButton && !isClipboardBannerActive
        val isEn = lang == AppLang.EN
        val voiceWidth = if (isEn) 76f else WIDTH_VOICE_CAPSULE_DP
        val penWidth = if (isEn) 68f else WIDTH_RIGHT_PILL_SLOT_DP
        val jevWidth = if (isEn) 76f else WIDTH_RIGHT_PILL_SLOT_DP
        val askAiWidth = if (isEn) 70f else WIDTH_RIGHT_PILL_SLOT_DP

        val leftFixedWidth = WIDTH_TOOL_BUTTON_DP +
            (if (showUndoButton) WIDTH_SMALL_ICON_SLOT_DP else 0f) +
            (if (effectiveClipboardIcon) WIDTH_SMALL_ICON_SLOT_DP else 0f) +
            (if (isIncognito) WIDTH_INCOGNITO_BADGE_DP else 0f) +
            WIDTH_GAP_BETWEEN_CLUSTERS_DP + 8f

        if (hasPriorityBanner) {
            val remainingForBanner = availableWidthDp - leftFixedWidth - voiceWidth
            val canShowBanner = remainingForBanner >= MIN_INLINE_BANNER_WIDTH_DP
            return QwertyTopBarLayoutPlan(
                bannerTakesOverMiddle = canShowBanner,
                showClipboardIconButton = if (canShowBanner) effectiveClipboardIcon else showClipboardButton,
                showRightAskAi = false,
                showRightJevCopilot = false,
                showRightVoicePen = false,
                showLeftInlineBanner = canShowBanner
            )
        }

        var showAskAi = wantAskAi
        var showJev = wantJevCopilot
        var showPen = wantVoicePen

        fun rightClusterWidth(): Float {
            var w = voiceWidth
            if (showPen) w += penWidth
            if (showJev) w += jevWidth
            if (showAskAi) w += askAiWidth
            return w
        }

        // Narrow-screen / English-label overflow guard: drop lowest-priority secondary pills first
        if (leftFixedWidth + rightClusterWidth() > availableWidthDp && showAskAi) {
            showAskAi = false
        }
        if (leftFixedWidth + rightClusterWidth() > availableWidthDp && showJev) {
            showJev = false
        }
        if (leftFixedWidth + rightClusterWidth() > availableWidthDp && showPen) {
            showPen = false
        }

        return QwertyTopBarLayoutPlan(
            bannerTakesOverMiddle = false,
            showClipboardIconButton = showClipboardButton,
            showRightAskAi = showAskAi,
            showRightJevCopilot = showJev,
            showRightVoicePen = showPen,
            showLeftInlineBanner = false
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
            .size(28.dp)
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
    onDismissFreshClipboardBanner: () -> Unit = {},
    onToggleManualIncognito: () -> Unit = {},
    onOpenJevCopilot: () -> Unit = {},
    onOpenAskAiCard: () -> Unit = {},
    onToggleClipboard: () -> Unit = {},
    onStartVoiceDictation: () -> Unit = {},
    onStopVoiceDictation: () -> Unit = {}
) {
    val palette = LocalTypeFreeKeyboardPalette.current
    val appLang = LocalAppLanguage.current
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
        uiState.rawSelectedText,
        settings.jevCopilotEnabled,
        appLang
    ) {
        TopBarSlotPolicy.resolveLeftSlots(
            activeTab = uiState.tabMode,
            isComposing = uiState.isFlypyComposing,
            hasUndoSnapshot = uiState.undoSnapshot != null,
            hasFreshClipboardBanner = freshClip != null,
            hasActiveSelection = uiState.rawSelectedText.isNotBlank(),
            jevCopilotEnabled = settings.jevCopilotEnabled,
            lang = appLang
        )
    }

    val isQwertyRightCluster = slotState.showQwertyHoldVoiceCapsule && !uiState.isToolPanelOpen
    val isInPlaceQwertyVoiceActive = uiState.tabMode == KeyboardTabMode.QWERTY &&
        (uiState.voicePhase is VoicePhase.Recording || uiState.voicePhase is VoicePhase.Processing)
    val learnedTerm = uiState.autoLearnedToastTerm
    val quickToast = uiState.quickActionToast
    val isClipboardBannerActive = !isInPlaceQwertyVoiceActive &&
        learnedTerm.isNullOrBlank() &&
        quickToast.isNullOrBlank() &&
        slotState.showClipboardQuickPaste &&
        freshClip != null
    val hasPriorityBanner = isInPlaceQwertyVoiceActive ||
        !learnedTerm.isNullOrBlank() ||
        !quickToast.isNullOrBlank() ||
        isClipboardBannerActive

    val wantAskAi = if (isQwertyRightCluster) {
        TopBarSlotPolicy.shouldShowTopBarAskAiPill(
            settings = settings,
            activeTab = uiState.tabMode,
            isComposing = uiState.isFlypyComposing,
            isToolPanelOpen = uiState.isToolPanelOpen,
            isClipboardOpen = uiState.isClipboardOpen,
            currentInputType = uiState.currentInputType,
            currentEditorText = uiState.currentEditorText,
            currentImeOptions = uiState.currentImeOptions
        )
    } else {
        false
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(metrics.topBarHeight)
    ) {
        val qwertyPlan = if (isQwertyRightCluster) {
            TopBarSlotPolicy.resolveQwertyBarLayout(
                availableWidthDp = maxWidth.value,
                showUndoButton = slotState.showUndoButton,
                showClipboardButton = slotState.showClipboardButton,
                isIncognito = uiState.isIncognitoMode,
                hasPriorityBanner = hasPriorityBanner,
                isClipboardBannerActive = isClipboardBannerActive,
                wantAskAi = wantAskAi,
                wantJevCopilot = slotState.showJevCopilotPill,
                wantVoicePen = slotState.showVoicePenPill,
                lang = appLang
            )
        } else {
            null
        }
        val showClipboardBtn = qwertyPlan?.showClipboardIconButton ?: slotState.showClipboardButton
        val allowInlineBanner = qwertyPlan?.showLeftInlineBanner ?: true

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
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
        // Left Cluster: [⊞] + [↶] + [📋] + Smart Paste / Status / Style Badge (VOICE only)
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

            // 2. Permanent 1-Tap 26.dp Clipboard Button ([📋]) with green badge dot on fresh clip
            if (showClipboardBtn) {
                TopBarClipboardIconButton(
                    isOpen = uiState.isClipboardOpen,
                    hasFreshClip = freshClip != null,
                    onClick = onToggleClipboard
                )
            }

            // In VOICE / TRANSLATE / ToolPanel mode, render [✦ 军师] and [✍️ 帮写/改写] on the left
            if (!isQwertyRightCluster && slotState.showJevCopilotPill) {
                TopBarJevCopilotPill(onClick = onOpenJevCopilot)
            }

            if (!isQwertyRightCluster && slotState.showVoicePenPill) {
                TopBarVoicePenPill(
                    label = slotState.voicePenLabel,
                    isHighlighted = uiState.shouldShowTopBarSpeakToEditPill,
                    onClick = onOpenHelpMeWrite
                )
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
                        contentDescription = tr("无痕模式", "Incognito Mode", "無痕模式"),
                        tint = incTint,
                        modifier = Modifier.size(11.dp)
                    )
                    Text(
                        text = tr("无痕", "Incog", "無痕"),
                        color = incTint,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }
            }

            if (allowInlineBanner && isInPlaceQwertyVoiceActive) {
                val statusLabel = if (uiState.voicePhase is VoicePhase.Processing) {
                    tr("🎙 正在转写上屏…", "🎙 Transcribing…", "🎙 正在轉寫上屏…")
                } else {
                    tr("🎙 聆听中 · 松手上屏", "🎙 Listening · Release", "🎙 聆聽中 · 放開上屏")
                }
                Box(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .clip(RoundedCornerShape(12.dp))
                        .background(TypeFreeEmerald.copy(alpha = 0.16f))
                        .border(1.dp, TypeFreeEmerald.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = statusLabel,
                        color = if (palette.isDark) Color(0xFF6EE7B7) else Color(0xFF047857),
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            } else if (allowInlineBanner && !learnedTerm.isNullOrBlank()) {
                Box(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .clip(RoundedCornerShape(12.dp))
                        .background(TypeFreeEmerald.copy(alpha = 0.15f))
                        .border(1.dp, TypeFreeEmerald.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = tr(
                            "✓ 已自动收入词典「$learnedTerm」",
                            "✓ Added \"$learnedTerm\" to dictionary",
                            "✓ 已自動收入詞典「$learnedTerm」"
                        ),
                        color = if (palette.isDark) Color(0xFF6EE7B7) else Color(0xFF047857),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            } else if (allowInlineBanner && !quickToast.isNullOrBlank()) {
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
            } else if (allowInlineBanner && isClipboardBannerActive && freshClip != null) {
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
                                .padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
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
                                text = tr("全文", "Full", "全文"),
                                color = palette.keyTextPrimary,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1
                            )
                        }
                        Box(
                            modifier = Modifier
                                .requiredSize(22.dp)
                                .clip(CircleShape)
                                .background(palette.keyCapBg)
                                .border(1.dp, palette.keyBorder, CircleShape)
                                .clickable(onClick = onDismissFreshClipboardBanner),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = TypeFreeIcons.Close,
                                contentDescription = "关闭粘贴提示",
                                tint = palette.keyTextMuted,
                                modifier = Modifier.size(10.dp)
                            )
                        }
                    }
                } else {
                    val previewText = ClipboardSmartExtractor.formatSingleLinePreview(freshClip.text, 10)
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
                                .clickable { onPasteClipboardText(freshClip.text) }
                                .padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = TypeFreeIcons.Clipboard,
                                contentDescription = null,
                                tint = clipTint,
                                modifier = Modifier.size(11.dp)
                            )
                            Text(
                                text = tr("粘贴「$previewText」", "Paste \"$previewText\"", "貼上「$previewText」"),
                                color = clipTint,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Box(
                            modifier = Modifier
                                .requiredSize(22.dp)
                                .clip(CircleShape)
                                .background(palette.keyCapBg)
                                .border(1.dp, palette.keyBorder, CircleShape)
                                .clickable(onClick = onDismissFreshClipboardBanner),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = TypeFreeIcons.Close,
                                contentDescription = "关闭粘贴提示",
                                tint = palette.keyTextMuted,
                                modifier = Modifier.size(10.dp)
                            )
                        }
                    }
                }
            } else if (slotState.showStyleBadge) {
                // Compact Clickable Scene Style Badge (ONLY in VOICE mode, hidden in QWERTY!)
                val localizedStyle = activeStyle.copy(name = AppStrings.toneStyleName(activeStyle, appLang))
                val badgeLabel = ImeLogic.buildCompactStyleBadgeText(localizedStyle, isAutoMatched)
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

        // Right Cluster:
        // - In QWERTY mode: [✦ 军师] + [✍️ 帮写/改写] + Dark Primary [🎤 语音] (tap <220ms switches to VOICE; hold >=220ms dictates in-place)
        //   When a transient banner (clipboard paste / toast / voice listening) takes over the middle, secondary AI pills yield cleanly.
        // - In VOICE / TRANSLATE mode: Compact 2-Segment Track [鹤 打字 | 文A]
        if (isQwertyRightCluster && qwertyPlan != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                if (qwertyPlan.showRightAskAi) {
                    TopBarAskAiPill(onClick = onOpenAskAiCard)
                }
                if (qwertyPlan.showRightJevCopilot) {
                    TopBarJevCopilotPill(onClick = onOpenJevCopilot)
                }
                if (qwertyPlan.showRightVoicePen) {
                    TopBarVoicePenPill(
                        label = slotState.voicePenLabel,
                        isHighlighted = uiState.shouldShowTopBarSpeakToEditPill,
                        onClick = onOpenHelpMeWrite
                    )
                }
                QwertyTopBarHoldVoiceCapsule(
                    isRecordingInPlace = uiState.voicePhase is VoicePhase.Recording,
                    onTapSwitchToVoice = { onSelectTabMode(KeyboardTabMode.VOICE) },
                    onStartVoiceDictation = onStartVoiceDictation,
                    onStopVoiceDictation = onStopVoiceDictation
                )
            }
        } else {
            Row(
                modifier = Modifier
                    .height(30.dp)
                    .clip(RoundedCornerShape(15.dp))
                    .background(palette.capsuleTrackBg)
                    .border(1.dp, palette.keyBorder, RoundedCornerShape(15.dp))
                    .padding(2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                if (uiState.tabMode != KeyboardTabMode.VOICE) {
                    ModeSegmentItem(
                        selected = false,
                        width = 30.dp,
                        onClick = { onSelectTabMode(KeyboardTabMode.VOICE) }
                    ) { contentColor ->
                        Icon(
                            imageVector = TypeFreeIcons.VoiceWaveform,
                            contentDescription = "语音模式",
                            tint = contentColor,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }

                val schemeBadge = if (uiState.isFlypyChineseMode) {
                    AppStrings.inputSchemeShortBadge(uiState.activeInputScheme, appLang)
                } else {
                    "EN"
                }
                ModeSegmentItem(
                    selected = uiState.tabMode == KeyboardTabMode.QWERTY && !uiState.isToolPanelOpen,
                    width = 52.dp,
                    onClick = {
                        if (uiState.tabMode == KeyboardTabMode.QWERTY && !uiState.isToolPanelOpen) {
                            onToggleFlypyLanguageMode()
                        } else {
                            onSelectTabMode(KeyboardTabMode.QWERTY)
                        }
                    }
                ) { contentColor ->
                    Text(
                        text = if (appLang == AppLang.EN) {
                            if (uiState.isFlypyChineseMode) "$schemeBadge Type" else "ABC"
                        } else {
                            "$schemeBadge 打字"
                        },
                        color = contentColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }

                ModeSegmentItem(
                    selected = uiState.tabMode == KeyboardTabMode.TRANSLATE && !uiState.isToolPanelOpen,
                    width = 34.dp,
                    onClick = { onSelectTabMode(KeyboardTabMode.TRANSLATE) }
                ) { contentColor ->
                    Text(
                        text = "文A",
                        color = contentColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                }
            }
        }
        }
    }
}

@Composable
private fun TopBarVoicePenPill(
    label: String,
    isHighlighted: Boolean,
    onClick: () -> Unit
) {
    val palette = LocalTypeFreeKeyboardPalette.current
    Row(
        modifier = Modifier
            .height(28.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (isHighlighted) palette.primaryMicSolid else palette.keyCapBg)
            .border(
                width = 1.dp,
                color = if (isHighlighted) Color.Transparent else palette.keyBorder,
                shape = RoundedCornerShape(14.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Icon(
            imageVector = TypeFreeIcons.VoicePen,
            contentDescription = label,
            tint = if (isHighlighted) palette.onPrimaryMic else palette.keyTextPrimary,
            modifier = Modifier.size(11.5.dp)
        )
        Text(
            text = label,
            color = if (isHighlighted) palette.onPrimaryMic else palette.keyTextPrimary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

private val AskAiPillIndigo = Color(0xFF6366F1)

@Composable
fun TopBarAskAiPill(
    onClick: () -> Unit
) {
    val palette = LocalTypeFreeKeyboardPalette.current
    val fgColor = if (palette.isDark) Color(0xFFA5B4FC) else Color(0xFF4F46E5)
    Row(
        modifier = Modifier
            .height(28.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(AskAiPillIndigo.copy(alpha = if (palette.isDark) 0.14f else 0.10f))
            .border(
                width = 1.dp,
                color = AskAiPillIndigo.copy(alpha = if (palette.isDark) 0.38f else 0.35f),
                shape = RoundedCornerShape(14.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Icon(
            imageVector = TypeFreeIcons.Search,
            contentDescription = tr("问AI", "Ask AI", "問AI"),
            tint = fgColor,
            modifier = Modifier.size(11.dp)
        )
        Text(
            text = tr("问AI", "Ask AI", "問AI"),
            color = fgColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

@Composable
private fun TopBarJevCopilotPill(
    onClick: () -> Unit
) {
    val palette = LocalTypeFreeKeyboardPalette.current
    Row(
        modifier = Modifier
            .height(28.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(palette.keyCapBg)
            .border(
                width = 1.dp,
                color = palette.keyBorder,
                shape = RoundedCornerShape(14.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Icon(
            imageVector = TypeFreeIcons.CopilotStar,
            contentDescription = tr("军师", "Copilot", "軍師"),
            tint = palette.keyTextPrimary,
            modifier = Modifier.size(11.dp)
        )
        Text(
            text = tr("军师", "Copilot", "軍師"),
            color = palette.keyTextPrimary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

@Composable
private fun QwertyTopBarHoldVoiceCapsule(
    isRecordingInPlace: Boolean,
    onTapSwitchToVoice: () -> Unit,
    onStartVoiceDictation: () -> Unit,
    onStopVoiceDictation: () -> Unit
) {
    val palette = LocalTypeFreeKeyboardPalette.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val currentOnTap by rememberUpdatedState(onTapSwitchToVoice)
    val currentOnStart by rememberUpdatedState(onStartVoiceDictation)
    val currentOnStop by rememberUpdatedState(onStopVoiceDictation)
    var isHoldingMic by remember { mutableStateOf(false) }

    val activeHighlight = isHoldingMic || isRecordingInPlace
    val bgColor = if (activeHighlight) TypeFreeEmerald else palette.primaryMicSolid
    val fgColor = if (activeHighlight) Color.White else palette.onPrimaryMic

    Row(
        modifier = Modifier
            .height(28.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(bgColor)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    var holdTriggered = false
                    val holdJob: Job = scope.launch {
                        delay(ImeLogic.TOP_BAR_VOICE_HOLD_MS)
                        holdTriggered = true
                        isHoldingMic = true
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        currentOnStart()
                    }
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                                ?: event.changes.firstOrNull()
                                ?: break
                            if (!change.pressed) {
                                change.consume()
                                break
                            }
                        }
                    } finally {
                        holdJob.cancel()
                        isHoldingMic = false
                    }
                    if (holdTriggered) {
                        currentOnStop()
                    } else {
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        currentOnTap()
                    }
                }
            }
            .padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            imageVector = TypeFreeIcons.Mic,
            contentDescription = tr("轻点切语音 / 按住说上屏", "Tap for Voice / Hold to dictate", "輕點切語音 / 按住說上屏"),
            tint = fgColor,
            modifier = Modifier.size(12.dp)
        )
        Text(
            text = if (activeHighlight) tr("松手上屏", "Release", "放開上屏") else tr("语音", "Voice", "語音"),
            color = fgColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

@Composable
fun TopBarClipboardIconButton(
    isOpen: Boolean,
    hasFreshClip: Boolean,
    onClick: () -> Unit
) {
    val palette = LocalTypeFreeKeyboardPalette.current
    val bgColor = if (isOpen) palette.primaryMicSolid else palette.keyCapBg
    val borderColor = if (isOpen) Color.Transparent else palette.keyBorder
    val iconTint = if (isOpen) palette.onPrimaryMic else palette.keyTextPrimary

    Box(
        modifier = Modifier.size(26.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(bgColor)
                .border(1.dp, borderColor, CircleShape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = TypeFreeIcons.Clipboard,
                contentDescription = "剪贴板",
                tint = iconTint,
                modifier = Modifier.size(13.dp)
            )
        }
        if (hasFreshClip && !isOpen) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 1.dp, y = (-1).dp)
                    .requiredSize(6.dp)
                    .clip(CircleShape)
                    .background(TypeFreeEmerald)
            )
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
            .size(26.dp)
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
    width: androidx.compose.ui.unit.Dp = 33.dp,
    onClick: () -> Unit,
    content: @Composable (Color) -> Unit
) {
    val palette = LocalTypeFreeKeyboardPalette.current
    val contentColor = if (selected) palette.keyTextPrimary else palette.keyTextMuted

    Box(
        modifier = Modifier
            .width(width)
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
    val appLang = LocalAppLanguage.current
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
            text = tr("自动场景感知", "Auto App-Aware", "自動場景感知"),
            leadingIcon = TypeFreeIcons.Sparkles,
            selected = isAutoSelected,
            onClick = onSelectAutoStyle
        )

        settings.styles.forEach { style ->
            val selected = !isAutoSelected && settings.selectedManualStyleId == style.id
            StyleChoicePill(
                text = "${style.emoji} ${AppStrings.toneStyleName(style, appLang)}",
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
                text = tr("管理风格 ›", "Manage Styles ›", "管理風格 ›"),
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
