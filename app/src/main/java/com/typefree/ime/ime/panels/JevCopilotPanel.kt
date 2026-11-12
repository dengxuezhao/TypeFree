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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.typefree.ime.data.AppSettings
import com.typefree.ime.data.VoiceIntentMode
import com.typefree.ime.ime.ImeUiAction
import com.typefree.ime.ime.ImeUiState
import com.typefree.ime.ime.KeyboardTabMode
import com.typefree.ime.ime.LocalKeyboardMetrics
import com.typefree.ime.ime.VoicePhase
import com.typefree.ime.ime.chrome.BottomCornerUtilityIcons
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
 * TypeFree v3.2.0 `✦ Jev 军师` Panel (Plan B 1:1 Monochrome Keycap Architecture).
 *
 * Strictly fits inside the constant `298.dp` keyboard frame height with zero layout jump:
 * 1. Header (`30.dp`): `[ 帮写 | ✦ 军师 ]` segmented switch + privacy/redaction badge + `[⛶ 全屏深读]`
 *    240ms non-disconnecting peek trigger + `[鹤 打字] [📋] [×]`.
 * 2. Subtext Radar Bar (`25.dp`): single `6.dp` breathing semantic dot (`Lv.1–3` Emerald, `Lv.4–6` Amber,
 *    `Lv.7–9` Rose) + `Lv.X 定性标签` + 1-line subtext decoding.
 * 3. 3-Strategy Keycap Cards: monochrome keycaps (`palette.keyCapBg` + `1.dp` `palette.keyBorder`),
 *    selected state with `1.5.dp` `palette.primaryMicSolid` border + Emerald `✓ 已置入草稿` badge.
 * 4. Bottom Voice Bottom-Line Bar (`42.dp` sphere) + `[↻ 换一批]` + `BottomCornerUtilityIcons`.
 */
@Composable
fun JevCopilotPanel(
    uiState: ImeUiState,
    settings: AppSettings,
    onAction: (ImeUiAction) -> Unit
) {
    val palette = LocalTypeFreeKeyboardPalette.current
    val s = LocalKeyboardMetrics.current.elementScale
    val phase = uiState.voicePhase
    val isRecording = phase is VoicePhase.Recording
    val isProcessing = phase is VoicePhase.Processing || uiState.isJevLoading
    val captured = uiState.capturedChatContext
    val jevResult = uiState.jevCopilotResult

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = (26f * s).dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // 1. Top Header Bar (`30.dp`): Mode Switcher + Privacy/Redaction Pill + `[⛶ 全屏深读]` + Bridge
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(30.dp),
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

                    // Redaction / Domain / Context status pill
                    val statusLabel = when {
                        uiState.ignoreChatContextForThisTurn -> "已忽略上文"
                        captured.redactionCount > 0 -> "🛡已脱敏${captured.redactionCount}处"
                        captured.hasContext -> "${captured.effectiveBadgeTitle}·${captured.messageCount}条"
                        else -> captured.effectiveBadgeTitle
                    }
                    val statusTint = if (!uiState.ignoreChatContextForThisTurn &&
                        (captured.redactionCount > 0 || captured.hasContext)
                    ) {
                        if (palette.isDark) Color(0xFF6EE7B7) else Color(0xFF047857)
                    } else {
                        palette.keyTextMuted
                    }
                    Row(
                        modifier = Modifier
                            .height(22.dp)
                            .clip(RoundedCornerShape(11.dp))
                            .background(palette.keyCapBg)
                            .border(1.dp, palette.keyBorder, RoundedCornerShape(11.dp))
                            .clickable(enabled = !isProcessing) {
                                onAction(ImeUiAction.ToggleIgnoreChatContext)
                            }
                            .padding(horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = statusLabel,
                            color = statusTint,
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // `[⛶ 全屏深读]` 240ms non-disconnecting peek pill
                    Row(
                        modifier = Modifier
                            .height(22.dp)
                            .clip(RoundedCornerShape(11.dp))
                            .background(palette.keyCapBg)
                            .border(1.dp, palette.keyBorder, RoundedCornerShape(11.dp))
                            .clickable(enabled = !isProcessing && !uiState.isFullScreenPeekCapturing) {
                                onAction(ImeUiAction.TriggerFullScreenPeekCapture)
                            }
                            .padding(horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = if (uiState.isFullScreenPeekCapturing) "取景中…" else "⛶ 全屏深读",
                            color = palette.keyTextPrimary,
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
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

            // 2. Subtext Intent Radar Bar (`25.dp`)
            JevSubtextRadarBar(
                result = jevResult,
                isLoading = isProcessing,
                domainBadge = captured.effectiveBadgeTitle
            )

            // 3. Center Area: Disclosure Prompt / Error Banner / 3 Strategy Keycap Cards
            if (uiState.showChatContextDisclosurePrompt) {
                JevFirstTimeDisclosureCard(
                    onAccept = { onAction(ImeUiAction.AcceptChatContextDisclosure) },
                    onDecline = { onAction(ImeUiAction.DeclineChatContextDisclosure) }
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
                        color = TypeFreeRose,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
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
                            text = "重试",
                            color = palette.onPrimaryMic,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            } else {
                val options = jevResult?.options.orEmpty()
                Column(
                    modifier = Modifier.fillMaxWidth(),
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
                        options.take(3).forEachIndexed { index, option ->
                            JevStrategyKeycapCard(
                                rankIndex = index,
                                option = option,
                                isSelected = uiState.selectedJevStrategyIndex == index,
                                enabled = !isProcessing,
                                onClick = { onAction(ImeUiAction.SelectJevStrategyCard(index)) }
                            )
                        }
                    }
                }
            }

            // 4. Bottom Voice Bottom-Line Bar + `[↻ 换一批]`
            JevBottomControlRow(
                phase = phase,
                isJevLoading = uiState.isJevLoading,
                userBottomLine = uiState.jevUserBottomLine,
                onStartRecording = { onAction(ImeUiAction.MicPressDown(VoiceIntentMode.JEV_COPILOT)) },
                onReleaseRecording = { onAction(ImeUiAction.MicPressUp) },
                onFinishHandsFree = { onAction(ImeUiAction.FinishHandsFree) },
                onCancelRecording = { onAction(ImeUiAction.CancelVoiceRecording) },
                onRefreshStrategies = { onAction(ImeUiAction.RefreshJevStrategies) }
            )
        }

        if (!isRecording) {
            BottomCornerUtilityIcons(
                onHideKeyboard = { onAction(ImeUiAction.HideKeyboard) },
                onSwitchKeyboard = { onAction(ImeUiAction.SwitchKeyboard) },
                onShowImePicker = { onAction(ImeUiAction.ShowImePicker) },
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }
}

@Composable
private fun JevSubtextRadarBar(
    result: JevCopilotResult?,
    isLoading: Boolean,
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

    val dotColor = when (result?.tensionTier) {
        JevTensionTier.SAFE_GREEN -> TypeFreeEmerald
        JevTensionTier.DANGER_ROSE -> TypeFreeRose
        JevTensionTier.PROBE_AMBER, null -> TypeFreeAmber
    }

    val badgeText = when {
        isLoading -> "Lv.-- 局势推演中"
        result != null -> "Lv.${result.clampedLevel} ${result.tag}"
        else -> "Lv.4 $domainBadge"
    }

    val subtext = when {
        isLoading -> "正在结合脱敏上文拆解对方潜台词与三套博弈话术…"
        result != null -> result.subtext
        else -> "点「↻ 换一批」读取上文推演，或按住声波球口述底牌"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(25.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(palette.capsuleTrackBg)
            .border(1.dp, palette.keyBorder, RoundedCornerShape(7.dp))
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .alpha(dotAlpha)
                .clip(CircleShape)
                .background(dotColor)
        )
        Text(
            text = badgeText,
            color = palette.keyTextPrimary,
            fontSize = 10.sp,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1
        )
        Text(
            text = "·",
            color = palette.keyTextMuted,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = subtext,
            color = palette.keyTextMuted,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
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

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .background(bgColor)
            .border(borderWidth, borderColor, RoundedCornerShape(9.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        // Left Strategy Tag + Normalized Probability Pill
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(5.dp))
                .background(if (isSelected) palette.primaryMicSolid else palette.capsuleTrackBg)
                .border(
                    width = pillBorderWidth,
                    color = pillBorderColor,
                    shape = RoundedCornerShape(5.dp)
                )
                .padding(horizontal = 5.5.dp, vertical = 2.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = badgeText,
                color = pillTextColor,
                fontSize = 10.sp,
                fontWeight = if (isTopPick || isSelected) FontWeight.ExtraBold else FontWeight.Bold,
                maxLines = 1
            )
        }

        // Center Pure Reply Text
        Text(
            text = option.replyText,
            color = palette.keyTextPrimary,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.SemiBold,
            lineHeight = 15.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )

        // Right Status Indicator (`✓ 已置入草稿`)
        if (isSelected) {
            val checkColor = if (palette.isDark) Color(0xFF6EE7B7) else Color(0xFF047857)
            Text(
                text = "✓ 已置入",
                color = checkColor,
                fontSize = 9.5.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun JevPlaceholderCard(
    index: Int,
    isLoading: Boolean
) {
    val palette = LocalTypeFreeKeyboardPalette.current
    val defaultTags = listOf("稳妥顺接", "温和设限", "高维反客")
    val defaultHints = listOf(
        "正在生成顺水推舟、不失礼貌的稳健回复方案…",
        "正在生成明确边界、守住核心筹码的防御方案…",
        "正在生成反客为主、把问题抛回对方的博弈方案…"
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
                .clip(RoundedCornerShape(5.dp))
                .background(palette.capsuleTrackBg)
                .border(1.dp, palette.keyBorder, RoundedCornerShape(5.dp))
                .padding(horizontal = 5.5.dp, vertical = 2.dp)
        ) {
            Text(
                text = defaultTags.getOrElse(index) { "策略 ${index + 1}" },
                color = palette.keyTextMuted,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Text(
            text = if (isLoading) defaultHints.getOrElse(index) { "推演中…" } else "点击「↻ 换一批」或按住下方声波球生成策略",
            color = palette.keyTextMuted,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
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
            text = "开启「✦ Jev 军师」端侧脱敏读屏说明",
            color = palette.keyTextPrimary,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "仅在你主动打开军师时按需读取当前窗口上下文，端侧自动将手机号/邮箱/地址替换为占位符、涂黑截图敏感区，遇密码/验证码/身份证/银行卡自动弃图，关闭面板即刻清空内存。",
            color = palette.keyTextMuted,
            fontSize = 10.sp,
            lineHeight = 13.5.sp,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
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
                    text = "仅口述底牌",
                    color = palette.keyTextMuted,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold
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
                    text = "同意并启用",
                    color = palette.onPrimaryMic,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
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
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "jevPulseScale"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 36.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Left 42.dp Dark Charcoal Voice Sphere
        Box(
            modifier = Modifier
                .size(42.dp)
                .scale(if (isRecording) pulseScale else 1f)
                .clip(CircleShape)
                .background(palette.primaryMicSolid)
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
                        } else if (heldMs >= 240L) {
                            currentOnRelease()
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            if (isProcessing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = palette.onPrimaryMic,
                    strokeWidth = 2.dp
                )
            } else {
                Icon(
                    imageVector = TypeFreeIcons.VoiceWaveform,
                    contentDescription = "按住补充底牌",
                    tint = palette.onPrimaryMic,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        // Center Title & Subtitle
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            Text(
                text = when {
                    isRecording -> "正在聆听底牌 · 松手重算三策"
                    isProcessing -> "军师推演中…"
                    userBottomLine.isNotBlank() -> "底牌：$userBottomLine"
                    else -> "按住补充底牌"
                },
                color = palette.keyTextPrimary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = if (isRecording) {
                    "说出你的底线或真实意图"
                } else {
                    "如「底价800不包邮」「周三前交不出」"
                },
                color = palette.keyTextMuted,
                fontSize = 9.5.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Right Action: Cancel (when recording) or `[↻ 换一批]`
        if (isRecording) {
            Box(
                modifier = Modifier
                    .height(28.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(palette.keyCapBg)
                    .border(1.dp, palette.keyBorder, RoundedCornerShape(14.dp))
                    .clickable(onClick = onCancelRecording)
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "取消",
                    color = palette.keyTextPrimary,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .height(28.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(palette.keyCapBg)
                    .border(1.dp, palette.keyBorder, RoundedCornerShape(14.dp))
                    .clickable(enabled = !isProcessing, onClick = onRefreshStrategies)
                    .padding(horizontal = 9.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "↻ 换一批",
                    color = palette.keyTextPrimary,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            }
        }
    }
}
