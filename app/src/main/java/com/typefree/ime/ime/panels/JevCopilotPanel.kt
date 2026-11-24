package com.typefree.ime.ime.panels

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.typefree.ime.ime.jev.memory.GroupSceneTag
import com.typefree.ime.ime.jev.memory.PrivateRelationTag
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.typefree.ime.bubble.CapturedChatContext
import com.typefree.ime.bubble.ChatSpeaker
import com.typefree.ime.data.AppSettings
import com.typefree.ime.data.VoiceIntentMode
import com.typefree.ime.i18n.AppLang
import com.typefree.ime.i18n.LocalAppLanguage
import com.typefree.ime.i18n.tr
import com.typefree.ime.ime.ImeUiAction
import com.typefree.ime.ime.ImeUiState
import com.typefree.ime.ime.KeyboardTabMode
import com.typefree.ime.ime.VoicePhase
import com.typefree.ime.ime.chrome.ErrorContextBanner
import com.typefree.ime.ime.chrome.SubPanelHeaderBridgeBar
import com.typefree.ime.ime.chrome.SubPanelModeSwitchTrack
import com.typefree.ime.ime.jev.JevCopilotResult
import com.typefree.ime.ime.jev.JevStrategyOption
import com.typefree.ime.ime.jev.JevTensionTier
import com.typefree.ime.ui.AppDestinations
import com.typefree.ime.ui.components.TypeFreeIcons
import com.typefree.ime.ui.theme.LocalTypeFreeKeyboardPalette
import com.typefree.ime.ui.theme.TypeFreeAmber
import com.typefree.ime.ui.theme.TypeFreeEmerald
import com.typefree.ime.ui.theme.TypeFreeRose

/**
 * TypeFree v3.4.0 `✦ Jev 军师` Panel (Zero-Truncation Inline-Pill & 32.dp Slim Control Bar).
 *
 * Fits inside the compact `252.dp` 4-row keyboard frame height with zero layout jump:
 * 1. Header (`28.dp`): `[ 帮写 | ✦ 军师 ]` segmented switch + privacy/redaction badge + `[⛶ 全屏深读]`
 *    240ms non-disconnecting peek trigger + `[鹤 打字] [📋] [×]`.
 * 2. Subtext Radar Bar (`heightIn(min = 24.dp)`, `maxLines = 2`): single `6.dp` breathing semantic dot
 *    + `Lv.X 定性标签 · 潜台词拆解正文` flowing naturally across up to 2 lines (`<= 46` chars).
 * 3. 3-Strategy Keycap Cards (`Modifier.weight(1f).verticalScroll`): first-line inline strategy pill
 *    (`[★ 55% 策略名]`) via `InlineTextContent` + `buildAnnotatedString`, followed by full-width
 *    `option.replyText` (`maxLines = Int.MAX_VALUE`, zero ellipsis truncation) + inline `✓已置入`.
 * 4. Bottom Control Row (`32.dp`): full-width left hold-to-speak bottom-line capsule + right `[↻ 换一批]`.
 */
@Composable
fun JevCopilotPanel(
    uiState: ImeUiState,
    settings: AppSettings,
    onAction: (ImeUiAction) -> Unit
) {
    val palette = LocalTypeFreeKeyboardPalette.current
    val phase = uiState.voicePhase
    val isProcessing = phase is VoicePhase.Processing || uiState.isJevLoading
    val rawCaptured = uiState.capturedChatContext
    val captured = if (rawCaptured.packageName.isBlank() && uiState.targetPackage.isNotBlank()) {
        rawCaptured.copy(
            packageName = uiState.targetPackage,
            domainProfile = com.typefree.ime.ime.jev.JevDomainProfile.fromPackage(uiState.targetPackage)
        )
    } else {
        rawCaptured
    }
    val jevResult = uiState.jevCopilotResult
    var hasAutoBootstrapped by remember { mutableStateOf(false) }

    // Safety net: if JevCopilotPanel ever mounts with neither a result, an active request, nor an error,
    // immediately trigger initial inference once so it never sits idle on static placeholder cards.
    LaunchedEffect(
        uiState.showChatContextDisclosurePrompt,
        jevResult,
        isProcessing,
        uiState.jevErrorMessage
    ) {
        if (jevResult != null || !uiState.jevErrorMessage.isNullOrBlank()) {
            hasAutoBootstrapped = true
        } else if (!uiState.showChatContextDisclosurePrompt && !isProcessing && !hasAutoBootstrapped) {
            hasAutoBootstrapped = true
            onAction(ImeUiAction.RefreshJevStrategies)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // 1. Top Header Bar (`28.dp`): Mode Switcher + Privacy/Redaction Pill + `[⛶ 全屏深读]` + Bridge
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                SubPanelModeSwitchTrack(
                    isJevActive = true,
                    jevEnabled = settings.jevCopilotEnabled,
                    onSelectHelpMeWrite = { onAction(ImeUiAction.OpenHelpMeWrite) },
                    onSelectJevCopilot = {}
                )

                // Redaction / Domain / Context status pill (toggles read-only JevContextInspectorCard)
                val appLang = LocalAppLanguage.current
                val statusLabel = when {
                    captured.redactionCount > 0 ->
                        "🛡${captured.redactionCount} ${if (uiState.isJevContextInspectorOpen) "▴" else "▾"}"
                    captured.hasContext ->
                        "${tr("文", "Ctx", "文")}·${captured.messageCount} ${if (uiState.isJevContextInspectorOpen) "▴" else "▾"}"
                    else ->
                        "${if (appLang == AppLang.EN) "Chat" else captured.effectiveBadgeTitle.take(3)} ${if (uiState.isJevContextInspectorOpen) "▴" else "▾"}"
                }
                val statusTint = when {
                    uiState.isJevContextInspectorOpen -> palette.primaryMicSolid
                    captured.redactionCount > 0 || captured.hasContext ->
                        if (palette.isDark) Color(0xFF6EE7B7) else Color(0xFF047857)
                    else -> palette.keyTextMuted
                }
                val statusBg = if (uiState.isJevContextInspectorOpen) {
                    palette.capsuleSelectedBg
                } else {
                    palette.keyCapBg
                }
                val statusBorder = if (uiState.isJevContextInspectorOpen) {
                    palette.primaryMicSolid
                } else {
                    palette.keyBorder
                }
                Row(
                    modifier = Modifier
                        .height(22.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(statusBg)
                        .border(1.dp, statusBorder, RoundedCornerShape(11.dp))
                        .clickable(enabled = !uiState.isFullScreenPeekCapturing) {
                            onAction(ImeUiAction.ToggleJevContextInspector)
                        }
                        .padding(horizontal = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = statusLabel,
                        style = TextStyle(
                            color = statusTint,
                            fontSize = 9.5.sp,
                            lineHeight = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                            platformStyle = PlatformTextStyle(includeFontPadding = false)
                        ),
                        maxLines = 1,
                        softWrap = false
                    )
                }

                // Relationship / Scene / Note Overlay pill (`ToggleJevRelationOverlay`)
                val relationLabel = when {
                    uiState.jevMemoryBypassedByPrivacy -> tr("🔒无痕", "🔒Incog", "🔒無痕")
                    captured.topology.isGroupChat && uiState.jevGroupSceneTag != GroupSceneTag.UNSET ->
                        "${uiState.jevGroupSceneTag.badgeEmoji}${uiState.jevGroupSceneTag.shortLabel.take(2)}"
                    !captured.topology.isGroupChat && uiState.jevPrivateRelationTag != PrivateRelationTag.UNSET ->
                        "${uiState.jevPrivateRelationTag.badgeEmoji}${uiState.jevPrivateRelationTag.shortLabel.take(2)}"
                    else -> "${tr("⚡关系", "⚡Role", "⚡關係")}${if (uiState.isJevRelationOverlayOpen) "▴" else "▾"}"
                }
                val relationHasSelection = uiState.isJevRelationOverlayOpen ||
                    (!uiState.jevMemoryBypassedByPrivacy && (
                        uiState.jevPrivateRelationTag != PrivateRelationTag.UNSET ||
                            uiState.jevGroupSceneTag != GroupSceneTag.UNSET ||
                            uiState.jevCustomRelationNote.isNotBlank()
                        ))
                Row(
                    modifier = Modifier
                        .height(22.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(if (uiState.isJevRelationOverlayOpen) palette.capsuleSelectedBg else palette.keyCapBg)
                        .border(
                            1.dp,
                            if (relationHasSelection) palette.primaryMicSolid else palette.keyBorder,
                            RoundedCornerShape(11.dp)
                        )
                        .clickable(enabled = !uiState.isFullScreenPeekCapturing) {
                            onAction(ImeUiAction.ToggleJevRelationOverlay)
                        }
                        .padding(horizontal = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = relationLabel,
                        style = TextStyle(
                            color = if (relationHasSelection) palette.primaryMicSolid else palette.keyTextPrimary,
                            fontSize = 9.5.sp,
                            lineHeight = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                            platformStyle = PlatformTextStyle(includeFontPadding = false)
                        ),
                        maxLines = 1,
                        softWrap = false
                    )
                }

                // `[⛶ 深读]` 240ms non-disconnecting peek pill (compact so 360dp narrow screens never crush it)
                Row(
                    modifier = Modifier
                        .height(22.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(palette.keyCapBg)
                        .border(1.dp, palette.keyBorder, RoundedCornerShape(11.dp))
                        .clickable(enabled = !isProcessing && !uiState.isFullScreenPeekCapturing) {
                            onAction(ImeUiAction.TriggerFullScreenPeekCapture)
                        }
                        .padding(horizontal = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = if (uiState.isFullScreenPeekCapturing) {
                            tr("取景中", "Scanning", "取景中")
                        } else {
                            tr("⛶ 深读", "⛶ Scan", "⛶ 深讀")
                        },
                        style = TextStyle(
                            color = palette.keyTextPrimary,
                            fontSize = 9.5.sp,
                            lineHeight = 11.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            platformStyle = PlatformTextStyle(includeFontPadding = false)
                        ),
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }

            SubPanelHeaderBridgeBar(
                isFlypyChineseMode = uiState.isFlypyChineseMode,
                inputSchemeShortBadge = uiState.activeInputScheme.shortBadge,
                hasFreshClipboard = uiState.freshClipboardBannerItem != null,
                onSwitchToQwerty = { onAction(ImeUiAction.SelectTab(KeyboardTabMode.QWERTY)) },
                onOpenClipboard = { onAction(ImeUiAction.OpenClipboard) },
                closeDescription = "关闭 Jev 军师",
                onClose = { onAction(ImeUiAction.CloseSubPanel) }
            )
        }

        // 2. Subtext Intent Radar Bar (`heightIn(min = 24.dp)`, multi-line adaptive)
        JevSubtextRadarBar(
            result = jevResult,
            isLoading = isProcessing,
            isThinking = uiState.isJevThinking,
            errorMessage = uiState.jevErrorMessage,
            domainBadge = captured.effectiveBadgeTitle
        )

        // 3. Center Area: Disclosure Prompt / Read-Only Context Inspector / Error Banner / Scrollable 3 Strategy Cards
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            if (uiState.showChatContextDisclosurePrompt) {
                JevFirstTimeDisclosureCard(
                    onAccept = { onAction(ImeUiAction.AcceptChatContextDisclosure) },
                    onDecline = { onAction(ImeUiAction.DeclineChatContextDisclosure) }
                )
            } else if (uiState.isJevRelationOverlayOpen) {
                JevRelationQuickOverlay(
                    uiState = uiState,
                    settings = settings,
                    isBusy = isProcessing || uiState.isFullScreenPeekCapturing,
                    onAction = onAction
                )
            } else if (uiState.isJevContextInspectorOpen) {
                JevContextInspectorCard(
                    captured = captured,
                    usedVision = jevResult?.usedVision == true || captured.hasScreenshot,
                    isBusy = isProcessing || uiState.isFullScreenPeekCapturing,
                    onCollapse = { onAction(ImeUiAction.ToggleJevContextInspector) },
                    onTriggerFullScreenPeek = { onAction(ImeUiAction.TriggerFullScreenPeekCapture) }
                )
            } else if (phase is VoicePhase.Error) {
                ErrorContextBanner(
                    error = phase,
                    onRetry = { onAction(ImeUiAction.RetryLastAudio) },
                    onOpenApiSetup = { onAction(ImeUiAction.OpenHostApp(AppDestinations.TAB_API)) },
                    onOpenPermissionSetup = { onAction(ImeUiAction.OpenHostApp(AppDestinations.TAB_HOME)) },
                    onDismiss = { onAction(ImeUiAction.DismissError) }
                )
            } else if (!uiState.jevErrorMessage.isNullOrBlank() && jevResult == null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(palette.keyCapBg)
                        .border(1.dp, palette.keyBorder, RoundedCornerShape(10.dp))
                        .padding(horizontal = 10.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = uiState.jevErrorMessage,
                        style = TextStyle(
                            color = TypeFreeRose,
                            fontSize = 11.sp,
                            lineHeight = 14.5.sp,
                            fontWeight = FontWeight.Medium,
                            platformStyle = PlatformTextStyle(includeFontPadding = false)
                        ),
                        maxLines = 3,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .height(26.dp)
                            .clip(RoundedCornerShape(13.dp))
                            .background(palette.primaryMicSolid)
                            .clickable { onAction(ImeUiAction.RefreshJevStrategies) }
                            .padding(horizontal = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = tr("重试", "Retry", "重試"),
                            style = TextStyle(
                                color = palette.onPrimaryMic,
                                fontSize = 10.5.sp,
                                lineHeight = 12.5.sp,
                                fontWeight = FontWeight.Bold,
                                platformStyle = PlatformTextStyle(includeFontPadding = false)
                            )
                        )
                    }
                }
            } else {
                val options = jevResult?.options.orEmpty()
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (options.isEmpty()) {
                        repeat(3) { idx ->
                            JevPlaceholderCard(
                                index = idx,
                                isLoading = isProcessing
                            )
                        }
                    } else {
                        val shownOptions = options.take(3)
                        shownOptions.forEachIndexed { index, option ->
                            JevStrategyKeycapCard(
                                rankIndex = index,
                                option = option,
                                isSelected = uiState.selectedJevStrategyIndex == index,
                                enabled = option.replyText.isNotBlank(),
                                onClick = { onAction(ImeUiAction.SelectJevStrategyCard(index)) }
                            )
                        }
                        if (isProcessing && shownOptions.size < 3) {
                            repeat(3 - shownOptions.size) { remIdx ->
                                JevPlaceholderCard(
                                    index = shownOptions.size + remIdx,
                                    isLoading = true
                                )
                            }
                        }
                    }
                }
            }
        }

        // 4. Bottom 32.dp Ultra-Slim Dual Capsules
        JevBottomControlRow(
            phase = phase,
            isJevLoading = uiState.isJevLoading,
            userBottomLine = uiState.jevUserBottomLine,
            isRelationOverlayOpen = uiState.isJevRelationOverlayOpen,
            customRelationNote = uiState.jevCustomRelationNote,
            onStartRecording = { onAction(ImeUiAction.MicPressDown(VoiceIntentMode.JEV_COPILOT)) },
            onReleaseRecording = { onAction(ImeUiAction.MicPressUp) },
            onFinishHandsFree = { onAction(ImeUiAction.FinishHandsFree) },
            onCancelRecording = { onAction(ImeUiAction.CancelVoiceRecording) },
            onRefreshStrategies = { onAction(ImeUiAction.RefreshJevStrategies) }
        )
    }
}

@Composable
private fun JevSubtextRadarBar(
    result: JevCopilotResult?,
    isLoading: Boolean,
    isThinking: Boolean = false,
    errorMessage: String? = null,
    domainBadge: String
) {
    val palette = LocalTypeFreeKeyboardPalette.current
    val infiniteTransition = rememberInfiniteTransition(label = "jevRadarPulse")
    val dotAlpha by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 0.42f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "jevDotAlpha"
    )

    val hasError = !isLoading && result == null && !errorMessage.isNullOrBlank()
    val hasRadarText = result != null && result.subtext.isNotBlank()
    val dotColor = when {
        hasError -> TypeFreeRose
        result?.tensionTier == JevTensionTier.SAFE_GREEN -> TypeFreeEmerald
        result?.tensionTier == JevTensionTier.DANGER_ROSE -> TypeFreeRose
        else -> TypeFreeAmber
    }

    val appLang = LocalAppLanguage.current
    val badgeText = when {
        hasRadarText -> "Lv.${result!!.clampedLevel} ${result.tag}"
        isLoading -> if (isThinking) {
            tr("Lv.-- 深度思考中", "Lv.-- Deep Reasoning", "Lv.-- 深度思考中")
        } else {
            tr("Lv.-- 局势推演中", "Lv.-- Analyzing Subtext", "Lv.-- 局勢推演中")
        }
        hasError -> tr("Lv.-- 推演未完成", "Lv.-- Paused", "Lv.-- 推演未完成")
        else -> "Lv.-- ${if (appLang == AppLang.EN) "Subtext Radar" else domainBadge}"
    }

    val subtext = when {
        hasRadarText -> result!!.subtext
        isLoading -> if (isThinking) {
            tr("正在深度推演对方真实动机与三套高筹码博弈话术…", "Analyzing real subtext and crafting 3 tactical replies…", "正在深度推演對方真實動機與三套高籌碼博弈話術…")
        } else {
            tr("正在结合脱敏上文拆解对方潜台词与三套博弈话术…", "Reading redacted context and drafting 3 reply strategies…", "正在結合去識別化上文拆解對方潛台詞與三套博弈話術…")
        }
        hasError -> errorMessage.orEmpty()
        else -> tr("正在自动读取上文并推演三套博弈策略…", "Analyzing conversation context to craft 3 reply options…", "正在自動讀取上文並推演三套博弈策略…")
    }

    val combinedRadarText = remember(badgeText, subtext, palette.keyTextPrimary, palette.keyTextMuted) {
        buildAnnotatedString {
            withStyle(
                SpanStyle(
                    color = palette.keyTextPrimary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            ) {
                append(badgeText)
            }
            withStyle(
                SpanStyle(
                    color = palette.keyTextMuted,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            ) {
                append(" · ")
            }
            withStyle(
                SpanStyle(
                    color = palette.keyTextMuted,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Medium
                )
            ) {
                append(subtext)
            }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 24.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(palette.capsuleTrackBg)
            .border(1.dp, palette.keyBorder, RoundedCornerShape(7.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Box(
            modifier = Modifier
                .padding(top = 3.5.dp)
                .size(6.dp)
                .alpha(dotAlpha)
                .clip(CircleShape)
                .background(dotColor)
        )
        Text(
            text = combinedRadarText,
            style = TextStyle(
                lineHeight = 13.5.sp,
                platformStyle = PlatformTextStyle(includeFontPadding = false)
            ),
            maxLines = 4,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun JevStrategyKeycapCard(
    rankIndex: Int,
    option: JevStrategyOption,
    isSelected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val palette = LocalTypeFreeKeyboardPalette.current
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val isTopPick = rankIndex == 0
    val bgColor = if (isSelected) palette.capsuleSelectedBg else palette.keyCapBg
    val borderColor = if (isSelected) palette.primaryMicSolid else palette.keyBorder
    val borderWidth = if (isSelected) 1.5.dp else 1.dp
    val badgeText = when {
        option.probability <= 0 -> option.label
        isTopPick -> "★ ${option.probability}% ${option.label}"
        else -> "${option.probability}% ${option.label}"
    }
    val pillBorderWidth = when {
        isSelected -> 0.dp
        isTopPick -> 1.25.dp
        else -> 1.dp
    }
    val pillBorderColor = when {
        isSelected -> Color.Transparent
        isTopPick -> palette.keyTextPrimary.copy(alpha = 0.36f)
        else -> palette.keyBorder
    }
    val pillTextColor = when {
        isSelected -> palette.onPrimaryMic
        isTopPick -> palette.keyTextPrimary
        else -> palette.keyTextMuted
    }
    val checkColor = if (palette.isDark) Color(0xFF6EE7B7) else Color(0xFF047857)
    val inlinePillId = "jev_strategy_pill"

    val badgeFontWeight = if (isTopPick || isSelected) FontWeight.ExtraBold else FontWeight.Bold
    val badgeTextStyle = remember(pillTextColor, badgeFontWeight) {
        TextStyle(
            color = pillTextColor,
            fontSize = 9.5.sp,
            lineHeight = 11.5.sp,
            fontWeight = badgeFontWeight,
            platformStyle = PlatformTextStyle(includeFontPadding = false)
        )
    }

    val measuredBadgeWidthPx = remember(badgeText, badgeTextStyle, textMeasurer) {
        textMeasurer.measure(
            text = badgeText,
            style = badgeTextStyle,
            maxLines = 1,
            softWrap = false
        ).size.width
    }
    val pillWidthDp = with(density) { measuredBadgeWidthPx.toDp() + 11.dp }
    val pillHeightDp = 17.5.dp
    val placeholderWidthSp = with(density) { (pillWidthDp + 5.dp).toSp() }
    val placeholderHeightSp = with(density) { 18.5.dp.toSp() }

    val inlineContentMap = remember(
        badgeText,
        badgeTextStyle,
        pillWidthDp,
        placeholderWidthSp,
        placeholderHeightSp,
        isSelected,
        pillBorderWidth,
        pillBorderColor,
        palette
    ) {
        mapOf(
            inlinePillId to InlineTextContent(
                placeholder = Placeholder(
                    width = placeholderWidthSp,
                    height = placeholderHeightSp,
                    placeholderVerticalAlign = PlaceholderVerticalAlign.Center
                )
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Box(
                        modifier = Modifier
                            .width(pillWidthDp)
                            .height(pillHeightDp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(if (isSelected) palette.primaryMicSolid else palette.capsuleTrackBg)
                            .border(
                                width = pillBorderWidth,
                                color = pillBorderColor,
                                shape = RoundedCornerShape(5.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = badgeText,
                            style = badgeTextStyle,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }
        )
    }

    val insertedSuffix = tr(" ✓已置入", " ✓Inserted", " ✓已置入")
    val cardAnnotatedText = remember(option.replyText, isSelected, checkColor, badgeText, insertedSuffix) {
        buildAnnotatedString {
            appendInlineContent(inlinePillId, "[$badgeText] ")
            append(option.replyText)
            if (isSelected) {
                withStyle(
                    SpanStyle(
                        color = checkColor,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                ) {
                    append(insertedSuffix)
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .background(bgColor)
            .border(borderWidth, borderColor, RoundedCornerShape(9.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 5.5.dp)
    ) {
        Text(
            text = cardAnnotatedText,
            inlineContent = inlineContentMap,
            style = TextStyle(
                color = palette.keyTextPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 17.5.sp,
                platformStyle = PlatformTextStyle(includeFontPadding = false)
            ),
            maxLines = Int.MAX_VALUE,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun JevPlaceholderCard(
    index: Int,
    isLoading: Boolean
) {
    val palette = LocalTypeFreeKeyboardPalette.current
    val defaultTags = listOf(
        tr("稳妥顺接", "Graceful Pivot", "穩妥順接"),
        tr("温和设限", "Firm Boundary", "溫和設限"),
        tr("高维反客", "Dry Wit", "高維反客")
    )
    val defaultHints = listOf(
        tr("正在生成顺水推舟、不失礼貌的稳健回复方案…", "Drafting a smooth, diplomatic reply option…", "正在生成順水推舟、不失禮貌的穩健回覆方案…"),
        tr("正在生成明确边界、守住核心筹码的防御方案…", "Drafting a clear, polite boundary-setting reply…", "正在生成明確邊界、守住核心籌碼的防禦方案…"),
        tr("正在生成反客为主、把问题抛回对方的博弈方案…", "Drafting a sharp, confident high-leverage reply…", "正在生成反客為主、把問題拋回對方的博弈方案…")
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .background(palette.keyCapBg.copy(alpha = 0.72f))
            .border(1.dp, palette.keyBorder, RoundedCornerShape(9.dp))
            .padding(horizontal = 9.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Box(
            modifier = Modifier
                .height(17.5.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(palette.capsuleTrackBg)
                .border(1.dp, palette.keyBorder, RoundedCornerShape(5.dp))
                .padding(horizontal = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = defaultTags.getOrElse(index) { "策略 ${index + 1}" },
                style = TextStyle(
                    color = palette.keyTextMuted,
                    fontSize = 9.5.sp,
                    lineHeight = 11.5.sp,
                    fontWeight = FontWeight.Bold,
                    platformStyle = PlatformTextStyle(includeFontPadding = false)
                ),
                maxLines = 1,
                softWrap = false
            )
        }
        Text(
            text = if (isLoading) defaultHints.getOrElse(index) { "推演中…" } else "正在读取上文并推演策略…",
            style = TextStyle(
                color = palette.keyTextMuted,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                fontWeight = FontWeight.Medium,
                platformStyle = PlatformTextStyle(includeFontPadding = false)
            ),
            maxLines = 2,
            modifier = Modifier.weight(1f)
        )
    }
}

internal fun buildJevInspectorHeaderSummary(
    captured: CapturedChatContext,
    usedVision: Boolean = false
): String {
    val chatKind = if (captured.topology.isGroupChat) {
        if (captured.topology.groupMemberCount > 0) {
            "多人聊天群(${captured.topology.groupMemberCount}人)"
        } else {
            "多人聊天群"
        }
    } else {
        "双人私聊"
    }
    val roleLabel = captured.topology.roleMode.label
    val base = "$chatKind · $roleLabel · 共${captured.messageCount}条"
    val redactionPart = if (captured.redactionCount > 0) {
        " · 🛡脱敏${captured.redactionCount}处"
    } else {
        ""
    }
    val visionPart = if (usedVision || captured.hasScreenshot) {
        " · 含视觉"
    } else {
        ""
    }
    return base + redactionPart + visionPart
}

@Composable
private fun JevContextInspectorCard(
    captured: CapturedChatContext,
    usedVision: Boolean,
    isBusy: Boolean,
    onCollapse: () -> Unit,
    onTriggerFullScreenPeek: () -> Unit
) {
    val palette = LocalTypeFreeKeyboardPalette.current
    val summaryText = remember(captured, usedVision) {
        buildJevInspectorHeaderSummary(captured = captured, usedVision = usedVision)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(9.dp))
            .background(palette.keyCapBg)
            .border(1.dp, palette.keyBorder, RoundedCornerShape(9.dp))
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Card Top Header: Topology + Role + Count (+ Redaction / Vision) + `[收起 ▴]`
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = summaryText,
                style = TextStyle(
                    color = palette.keyTextPrimary,
                    fontSize = 9.5.sp,
                    lineHeight = 12.sp,
                    fontWeight = FontWeight.Bold,
                    platformStyle = PlatformTextStyle(includeFontPadding = false)
                ),
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .height(19.dp)
                    .clip(RoundedCornerShape(9.5.dp))
                    .background(palette.capsuleTrackBg)
                    .border(1.dp, palette.keyBorder, RoundedCornerShape(9.5.dp))
                    .clickable(onClick = onCollapse)
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "收起 ▴",
                    style = TextStyle(
                        color = palette.keyTextMuted,
                        fontSize = 9.sp,
                        lineHeight = 11.sp,
                        fontWeight = FontWeight.Bold,
                        platformStyle = PlatformTextStyle(includeFontPadding = false)
                    ),
                    maxLines = 1,
                    softWrap = false
                )
            }
        }

        // Scrollable read-only extracted message list
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(3.5.dp)
        ) {
            if (captured.messages.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Text(
                        text = "当前未提取到聊天文本，可点击右下角「⛶ 全屏补抓更多上文」重新取景识别",
                        style = TextStyle(
                            color = palette.keyTextMuted,
                            fontSize = 10.sp,
                            lineHeight = 13.5.sp,
                            fontWeight = FontWeight.Medium,
                            platformStyle = PlatformTextStyle(includeFontPadding = false)
                        ),
                        maxLines = 2
                    )
                }
            } else {
                captured.messages.forEach { msg ->
                    val isSelf = msg.speaker == ChatSpeaker.SELF
                    val roleBadgeText = if (msg.speaker == ChatSpeaker.OTHER && msg.senderAlias.isNotBlank()) {
                        "[${msg.senderAlias}]"
                    } else {
                        msg.speaker.tag
                    }
                    val badgeBg = if (isSelf) palette.capsuleSelectedBg else palette.capsuleTrackBg
                    val badgeBorder = if (isSelf) palette.primaryMicSolid.copy(alpha = 0.45f) else palette.keyBorder
                    val badgeFg = if (isSelf) palette.primaryMicSolid else palette.keyTextMuted

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .height(17.dp)
                                .clip(RoundedCornerShape(4.5.dp))
                                .background(badgeBg)
                                .border(1.dp, badgeBorder, RoundedCornerShape(4.5.dp))
                                .padding(horizontal = 4.5.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = roleBadgeText,
                                style = TextStyle(
                                    color = badgeFg,
                                    fontSize = 9.sp,
                                    lineHeight = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    platformStyle = PlatformTextStyle(includeFontPadding = false)
                                ),
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                        Text(
                            text = msg.text,
                            style = TextStyle(
                                color = palette.keyTextPrimary,
                                fontSize = 10.5.sp,
                                lineHeight = 14.sp,
                                fontWeight = FontWeight.Medium,
                                platformStyle = PlatformTextStyle(includeFontPadding = false)
                            ),
                            maxLines = Int.MAX_VALUE,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        // Card Bottom Row: Memory-only privacy note + `[⛶ 全屏补抓更多上文]`
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "仅驻留内存 · 关面板即焚",
                style = TextStyle(
                    color = palette.keyTextMuted,
                    fontSize = 9.sp,
                    lineHeight = 11.sp,
                    fontWeight = FontWeight.Medium,
                    platformStyle = PlatformTextStyle(includeFontPadding = false)
                ),
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .height(21.dp)
                    .clip(RoundedCornerShape(10.5.dp))
                    .background(palette.capsuleSelectedBg)
                    .border(1.dp, palette.primaryMicSolid, RoundedCornerShape(10.5.dp))
                    .clickable(enabled = !isBusy, onClick = onTriggerFullScreenPeek)
                    .padding(horizontal = 7.5.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "⛶ 全屏补抓更多上文",
                    style = TextStyle(
                        color = palette.primaryMicSolid,
                        fontSize = 9.5.sp,
                        lineHeight = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        platformStyle = PlatformTextStyle(includeFontPadding = false)
                    ),
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
    }
}

@Composable
private fun JevFirstTimeDisclosureCard(
    onAccept: () -> Unit,
    onDecline: () -> Unit
) {
    val palette = LocalTypeFreeKeyboardPalette.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(palette.cardElevatedBg)
            .border(1.dp, palette.keyBorder, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Text(
            text = tr("开启「✦ Jev 军师」端侧脱敏读屏说明", "Enable Jev Copilot On-Device Redacted Context", "開啟「✦ Jev 軍師」本機去識別化讀屏說明"),
            style = TextStyle(
                color = palette.keyTextPrimary,
                fontSize = 11.5.sp,
                lineHeight = 14.sp,
                fontWeight = FontWeight.Bold,
                platformStyle = PlatformTextStyle(includeFontPadding = false)
            )
        )
        Text(
            text = tr(
                "仅在你主动打开军师时按需读取当前窗口上下文，端侧自动将手机号/邮箱/地址替换为占位符、涂黑截图敏感区，遇密码/验证码/身份证/银行卡自动弃图，关闭面板即刻清空内存。",
                "Reads visible chat context only when you explicitly open Copilot. Phone numbers, emails, and sensitive fields are redacted locally on-device before AI processing, and cleared from memory as soon as you close the panel.",
                "僅在您主動打開軍師時按需讀取目前視窗對話內容，本機自動將手機/信箱/地址替換為遮罩並塗黑敏感區，關閉面板即刻清空記憶體。"
            ),
            style = TextStyle(
                color = palette.keyTextMuted,
                fontSize = 10.sp,
                lineHeight = 13.5.sp,
                platformStyle = PlatformTextStyle(includeFontPadding = false)
            ),
            maxLines = 3
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .height(24.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(palette.keyCapBg)
                    .border(1.dp, palette.keyBorder, RoundedCornerShape(12.dp))
                    .clickable(onClick = onDecline)
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = tr("仅口述底牌", "Voice Only", "僅口述底牌"),
                    style = TextStyle(
                        color = palette.keyTextMuted,
                        fontSize = 10.sp,
                        lineHeight = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        platformStyle = PlatformTextStyle(includeFontPadding = false)
                    )
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .height(24.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(palette.primaryMicSolid)
                    .clickable(onClick = onAccept)
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = tr("同意并启用", "Allow & Enable", "同意並啟用"),
                    style = TextStyle(
                        color = palette.onPrimaryMic,
                        fontSize = 10.sp,
                        lineHeight = 12.sp,
                        fontWeight = FontWeight.Bold,
                        platformStyle = PlatformTextStyle(includeFontPadding = false)
                    )
                )
            }
        }
    }
}

@Composable
private fun JevBottomControlRow(
    phase: VoicePhase,
    isJevLoading: Boolean,
    userBottomLine: String,
    isRelationOverlayOpen: Boolean = false,
    customRelationNote: String = "",
    onStartRecording: () -> Unit,
    onReleaseRecording: () -> Unit,
    onFinishHandsFree: () -> Unit,
    onCancelRecording: () -> Unit,
    onRefreshStrategies: () -> Unit
) {
    val palette = LocalTypeFreeKeyboardPalette.current
    val isRecording = phase is VoicePhase.Recording
    val isProcessing = phase is VoicePhase.Processing || isJevLoading

    val currentPhaseState by rememberUpdatedState(phase)
    val currentOnStart by rememberUpdatedState(onStartRecording)
    val currentOnRelease by rememberUpdatedState(onReleaseRecording)
    val currentOnFinish by rememberUpdatedState(onFinishHandsFree)

    val infiniteTransition = rememberInfiniteTransition(label = "jevVoicePulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.10f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "jevPulseScale"
    )

    val bottomStatusText = if (isRelationOverlayOpen) {
        when {
            isRecording -> tr("正在聆听备忘 · 松手脱敏保存", "Listening for note · Release to save", "正在聆聽備忘 · 放開儲存")
            isProcessing -> tr("军师推演中…", "Crafting replies…", "軍師推演中…")
            customRelationNote.isNotBlank() -> "${tr("备忘：", "Note: ", "備忘：")}$customRelationNote"
            else -> tr("按住说备忘 · 如：甲方总监忌催进度", "Hold to speak relationship note", "按住說備忘 · 如：客戶總監忌催進度")
        }
    } else {
        when {
            isRecording -> tr("正在聆听底牌 · 松手重算", "Listening for your stance · Release", "正在聆聽底牌 · 放開重算")
            isProcessing -> tr("军师推演中…", "Crafting replies…", "軍師推演中…")
            userBottomLine.isNotBlank() -> "${tr("底牌：", "Stance: ", "底牌：")}$userBottomLine"
            else -> tr("按住说底牌 · 如：底价800 / 周六补约", "Hold to speak your stance (e.g. reschedule to Fri)", "按住說底牌 · 如：底價800 / 週六補約")
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(32.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Left full-width hold-to-speak bottom-line capsule (`height(32.dp)`)
        Row(
            modifier = Modifier
                .weight(1f)
                .height(32.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(if (isRecording) palette.capsuleSelectedBg else palette.keyCapBg)
                .border(
                    width = if (isRecording) 1.5.dp else 1.dp,
                    color = if (isRecording) palette.primaryMicSolid else palette.keyBorder,
                    shape = RoundedCornerShape(16.dp)
                )
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        val initial = currentPhaseState
                        if (initial is VoicePhase.Processing) return@awaitEachGesture

                        val startedFromHandsFree =
                            initial is VoicePhase.Recording && initial.isHandsFree
                        if (!startedFromHandsFree) {
                            currentOnStart()
                        }
                        val downTime = System.currentTimeMillis()
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                change.consume()
                                break
                            }
                        }
                        val heldMs = System.currentTimeMillis() - downTime
                        if (startedFromHandsFree) {
                            currentOnFinish()
                        } else if (heldMs >= 200L) {
                            currentOnRelease()
                        } else {
                            currentOnRelease()
                        }
                    }
                }
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .scale(if (isRecording) pulseScale else 1f)
                    .clip(CircleShape)
                    .background(palette.primaryMicSolid),
                contentAlignment = Alignment.Center
            ) {
                if (isProcessing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        color = palette.onPrimaryMic,
                        strokeWidth = 1.6.dp
                    )
                } else {
                    Icon(
                        imageVector = TypeFreeIcons.VoiceWaveform,
                        contentDescription = "按住说底牌",
                        tint = palette.onPrimaryMic,
                        modifier = Modifier.size(13.dp)
                    )
                }
            }

            Text(
                text = bottomStatusText,
                style = TextStyle(
                    color = if (isRecording || userBottomLine.isNotBlank()) palette.keyTextPrimary else palette.keyTextMuted,
                    fontSize = 10.5.sp,
                    lineHeight = 13.sp,
                    fontWeight = if (isRecording || userBottomLine.isNotBlank()) FontWeight.Bold else FontWeight.SemiBold,
                    platformStyle = PlatformTextStyle(includeFontPadding = false)
                ),
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
        }

        // Right secondary capsule (`height(32.dp)`): Cancel (when recording) or `[↻ 换一批]`
        if (isRecording) {
            Box(
                modifier = Modifier
                    .height(32.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(palette.keyCapBg)
                    .border(1.dp, palette.keyBorder, RoundedCornerShape(16.dp))
                    .clickable(onClick = onCancelRecording)
                    .padding(horizontal = 11.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = tr("取消", "Cancel", "取消"),
                    style = TextStyle(
                        color = palette.keyTextPrimary,
                        fontSize = 10.5.sp,
                        lineHeight = 13.sp,
                        fontWeight = FontWeight.Bold,
                        platformStyle = PlatformTextStyle(includeFontPadding = false)
                    ),
                    maxLines = 1
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .height(32.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(palette.keyCapBg)
                    .border(1.dp, palette.keyBorder, RoundedCornerShape(16.dp))
                    .clickable(enabled = !isProcessing, onClick = onRefreshStrategies)
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = tr("↻ 换一批", "↻ Refresh", "↻ 換一批"),
                    style = TextStyle(
                        color = palette.keyTextPrimary,
                        fontSize = 10.5.sp,
                        lineHeight = 13.sp,
                        fontWeight = FontWeight.Bold,
                        platformStyle = PlatformTextStyle(includeFontPadding = false)
                    ),
                    maxLines = 1
                )
            }
        }
    }
}
