package com.typefree.ime.ime.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.typefree.ime.data.AppSettings
import com.typefree.ime.ime.HelpWritePromptPreset
import com.typefree.ime.ime.ImeLogic
import com.typefree.ime.ime.ImeUiState
import com.typefree.ime.ime.LocalKeyboardMetrics
import com.typefree.ime.ime.VoicePhase
import com.typefree.ime.ime.chrome.BottomCornerUtilityIcons
import com.typefree.ime.ime.chrome.ErrorContextBanner
import com.typefree.ime.ime.chrome.SubPanelHeaderBridgeBar
import com.typefree.ime.ime.chrome.SubPanelVoiceTriggerRow
import com.typefree.ime.ui.AppDestinations
import com.typefree.ime.ui.components.TypeFreeIcons
import com.typefree.ime.ui.theme.LocalTypeFreeKeyboardPalette
import com.typefree.ime.ui.theme.TypeFreeEmerald

/**
 * Smart filter for `HelpMeWritePanel` presets in TypeFree v3.1.0:
 * When the host app is an IM chat app (WeChat, QQ, Feishu/Lark, DingTalk, WeWork, Telegram,
 * WhatsApp, Discord, etc.) or [chatContextActive] is true, filters out long-form office
 * templates (weekly recap, formal email) so reply attitude chips stay focused and uncluttered.
 */
object HelpWriteChipFilter {
    private val IM_CHAT_PACKAGES = setOf(
        "com.tencent.mm",
        "com.tencent.mobileqq",
        "com.tencent.tim",
        "com.tencent.wework",
        "com.ss.android.lark",
        "com.larksuite.suite",
        "com.alibaba.android.rimet",
        "org.telegram.messenger",
        "org.telegram.messenger.web",
        "tw.nekomimi.nekogram",
        "com.whatsapp",
        "com.whatsapp.w4b",
        "com.discord",
        "com.Slack",
        "jp.naver.line.android",
        "com.facebook.orca"
    )

    private val LONG_FORM_PRESET_IDS = setOf(
        "weekly_recap",
        "meeting_invite"
    )

    val shortRewritePresets: List<Pair<String, String>> = listOf(
        "✨ 润色得体" to "将这段话润色得更加自然得体、通顺清晰",
        "💼 职场专业" to "改为专业、干练的职场沟通表达",
        "🤝 委婉高情商" to "用委婉、高情商、顾及对方感受的语气改写",
        "✂️ 精简短句" to "精简冗余字词，保留核心意思"
    )

    val shortDraftPresets: List<Pair<String, String>> = listOf(
        "💬 礼貌回复" to "根据我说的要点，起草一段得体礼貌的回复",
        "🙏 委婉拒绝" to "根据我说的理由，写一段真诚委婉的谢绝回复",
        "⏰ 协调时间" to "起草一段简洁礼貌的沟通，询问并协调双方时间"
    )

    val longFormOfficePresets: List<Pair<String, String>> = listOf(
        "📝 工作简报" to "根据我口述的要点，整理成结构清晰的工作简报/周报要点",
        "✉️ 正式邮件" to "起草一封格式规范、语气得体的商务/工作邮件",
        "📌 要点提炼" to "将核心内容提炼为条理清晰的分点结论",
        "📋 整理待办" to "提取关键行动项，整理为清晰的待办清单"
    )

    fun shouldShowLongFormPresets(targetPackage: String, chatContextActive: Boolean): Boolean {
        if (chatContextActive) return false
        val pkg = targetPackage.trim()
        if (pkg.isEmpty()) return true
        if (pkg in IM_CHAT_PACKAGES) return false
        val lower = pkg.lowercase()
        return !(lower.contains("wechat") ||
            lower.contains("mobileqq") ||
            lower.contains("telegram") ||
            lower.contains("whatsapp") ||
            lower.contains("lark") ||
            lower.contains("feishu") ||
            lower.contains("dingtalk"))
    }

    fun filterPresets(
        presets: List<HelpWritePromptPreset>,
        targetPackage: String,
        chatContextActive: Boolean
    ): List<HelpWritePromptPreset> {
        if (shouldShowLongFormPresets(targetPackage, chatContextActive)) {
            return presets
        }
        return presets.filterNot { preset ->
            preset.id in LONG_FORM_PRESET_IDS ||
                preset.label.contains("工作简报") ||
                preset.label.contains("邮件")
        }
    }

    fun resolveVisiblePresets(
        hasSelection: Boolean,
        targetPackage: String,
        chatContextActive: Boolean
    ): List<Pair<String, String>> {
        val base = if (hasSelection) shortRewritePresets else shortDraftPresets
        return if (shouldShowLongFormPresets(targetPackage, chatContextActive)) {
            base + longFormOfficePresets
        } else {
            base
        }
    }
}

@Composable
fun HelpMeWritePanel(
    uiState: ImeUiState,
    settings: AppSettings,
    amplitudeProvider: () -> Float = { 0f },
    amplitudes: List<Float> = emptyList(),
    elapsedMs: Long,
    onClose: () -> Unit,
    onOpenJevCopilot: () -> Unit = {},
    onAcceptChatContextDisclosure: () -> Unit,
    onDeclineChatContextDisclosure: () -> Unit,
    onToggleIgnoreChatContext: () -> Unit,
    onSwitchToQwerty: () -> Unit,
    onOpenClipboard: () -> Unit,
    onPresetClick: (HelpWritePromptPreset) -> Unit,
    onDirectGenerate: () -> Unit = {},
    onStartRecording: () -> Unit,
    onReleaseRecording: () -> Unit,
    onFinishHandsFree: () -> Unit,
    onCancelRecording: () -> Unit,
    onOpenHostApp: (Int) -> Unit,
    onRetryLastAudio: () -> Unit,
    onDismissError: () -> Unit,
    onHideKeyboard: () -> Unit,
    onSwitchKeyboard: () -> Unit,
    onShowImePicker: () -> Unit
) {
    val palette = LocalTypeFreeKeyboardPalette.current
    val s = LocalKeyboardMetrics.current.elementScale
    val phase = uiState.voicePhase
    val isBusy = phase is VoicePhase.Recording || phase is VoicePhase.Processing
    val hasCapturedContext = uiState.capturedChatContext.hasContext
    val isContextActive = uiState.hasActiveChatContext
    val selectedPreset = uiState.selectedHelpWritePreset
    val attitudePresets = remember(settings.replyAttitudePresets) {
        ImeLogic.resolveReplyAttitudePresets(settings)
    }
    val visibleHelpPresets = remember(uiState.targetPackage, isContextActive) {
        HelpWriteChipFilter.filterPresets(
            presets = ImeLogic.helpWritePresets,
            targetPackage = uiState.targetPackage,
            chatContextActive = isContextActive
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = (28f * s).dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Header: Top-Left `Help me write · 帮我写` or `AI 帮回 + 已截屏识图/已读 N 条` + Top-Right Bridge
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(34.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    com.typefree.ime.ime.chrome.SubPanelModeSwitchTrack(
                        isJevActive = false,
                        jevEnabled = settings.jevCopilotEnabled,
                        onSelectHelpMeWrite = {},
                        onSelectJevCopilot = onOpenJevCopilot
                    )
                    if (hasCapturedContext) {
                        val msgCount = uiState.capturedChatContext.messageCount
                        val hasScreen = uiState.capturedChatContext.hasScreenshot
                        val badgeText = when {
                            uiState.ignoreChatContextForThisTurn -> "已忽略 · 点此恢复"
                            hasScreen && msgCount > 0 -> "已识图+${msgCount}条 · 点此忽略"
                            hasScreen -> "已截屏识图 · 点此忽略"
                            else -> "已读 $msgCount 条 · 点此忽略"
                        }
                        val badgeFg = if (!uiState.ignoreChatContextForThisTurn) {
                            if (palette.isDark) Color(0xFF6EE7B7) else Color(0xFF047857)
                        } else {
                            palette.keyTextMuted
                        }
                        Row(
                            modifier = Modifier
                                .height(22.dp)
                                .clip(RoundedCornerShape(11.dp))
                                .background(
                                    if (!uiState.ignoreChatContextForThisTurn) {
                                        TypeFreeEmerald.copy(alpha = 0.14f)
                                    } else {
                                        palette.keyCapBg
                                    }
                                )
                                .border(
                                    width = 1.dp,
                                    color = if (!uiState.ignoreChatContextForThisTurn) {
                                        TypeFreeEmerald.copy(alpha = 0.48f)
                                    } else {
                                        palette.keyBorder
                                    },
                                    shape = RoundedCornerShape(11.dp)
                                )
                                .clickable(enabled = !isBusy, onClick = onToggleIgnoreChatContext)
                                .padding(horizontal = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            if (hasScreen && !uiState.ignoreChatContextForThisTurn) {
                                Icon(
                                    imageVector = TypeFreeIcons.ScreenContext,
                                    contentDescription = null,
                                    tint = badgeFg,
                                    modifier = Modifier.size(11.dp)
                                )
                            }
                            Text(
                                text = badgeText,
                                color = badgeFg,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    } else if (!settings.jevCopilotEnabled) {
                        Text(
                            text = "Help me write · 帮我写",
                            color = palette.keyTextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                SubPanelHeaderBridgeBar(
                    isFlypyChineseMode = uiState.isFlypyChineseMode,
                    inputSchemeShortBadge = uiState.activeInputScheme.shortBadge,
                    hasFreshClipboard = uiState.freshClipboardBannerItem != null,
                    onSwitchToQwerty = onSwitchToQwerty,
                    onOpenClipboard = onOpenClipboard,
                    closeDescription = "关闭帮我写",
                    onClose = onClose
                )
            }

            if (phase is VoicePhase.Error) {
                ErrorContextBanner(
                    error = phase,
                    onRetry = onRetryLastAudio,
                    onOpenApiSetup = { onOpenHostApp(AppDestinations.TAB_API) },
                    onOpenPermissionSetup = { onOpenHostApp(AppDestinations.TAB_HOME) },
                    onDismiss = onDismissError
                )
            } else if (uiState.showChatContextDisclosurePrompt) {
                // First-time in-frame privacy disclosure card when opening ✍️ in a messaging app
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(palette.cardElevatedBg)
                        .border(1.dp, TypeFreeEmerald.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 10.dp, vertical = (6f * s).dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(
                                imageVector = TypeFreeIcons.ScreenContext,
                                contentDescription = null,
                                tint = TypeFreeEmerald,
                                modifier = Modifier.size(13.dp)
                            )
                            Text(
                                text = "开启 AI 帮回读屏与识图上下文？",
                                color = palette.keyTextPrimary,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(palette.keyCapBg)
                                    .border(1.dp, palette.keyBorder, RoundedCornerShape(10.dp))
                                    .clickable(onClick = onDeclineChatContextDisclosure)
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    text = "暂不开启",
                                    color = palette.keyTextMuted,
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(TypeFreeEmerald)
                                    .clickable(onClick = onAcceptChatContextDisclosure)
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    text = "同意并启用",
                                    color = Color.White,
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                    Text(
                        text = "仅在你点 ✍️ 时截取当前聊天区画面（自动裁除状态栏与键盘）并提取消息，发给配置的 AI 模型帮回；用完即焚、不落盘。",
                        color = palette.keyTextMuted,
                        fontSize = 10.5.sp,
                        lineHeight = 13.5.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            } else if (isContextActive) {
                // Active Contextual Smart Reply: 1-line incoming message / vision quote + Attitude Chips + Custom
                val latestPreview = uiState.capturedChatContext.latestIncomingMessage
                    .ifBlank { uiState.capturedChatContext.formattedLines.lastOrNull().orEmpty() }
                    .ifBlank { "已截取当前聊天区画面（含气泡文字与图片），AI 将结合画面视觉帮回" }
                Column(verticalArrangement = Arrangement.spacedBy((5f * s).dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(palette.cardElevatedBg)
                            .border(1.dp, TypeFreeEmerald.copy(alpha = 0.38f), RoundedCornerShape(10.dp))
                            .padding(horizontal = 10.dp, vertical = (5f * s).dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Icon(
                            imageVector = TypeFreeIcons.ScreenContext,
                            contentDescription = null,
                            tint = TypeFreeEmerald,
                            modifier = Modifier.size(12.dp)
                        )
                        Text(
                            text = latestPreview,
                            color = palette.keyTextPrimary,
                            fontSize = 11.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        attitudePresets.forEach { preset ->
                            val isSelected = selectedPreset?.id == preset.id
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(13.dp))
                                    .background(
                                        if (isSelected) TypeFreeEmerald else TypeFreeEmerald.copy(alpha = 0.12f)
                                    )
                                    .border(
                                        width = 1.dp,
                                        color = if (isSelected) TypeFreeEmerald else TypeFreeEmerald.copy(alpha = 0.45f),
                                        shape = RoundedCornerShape(13.dp)
                                    )
                                    .clickable(enabled = !isBusy) { onPresetClick(preset) }
                                    .padding(horizontal = 10.dp, vertical = (5.5f * s).dp)
                            ) {
                                Text(
                                    text = if (isSelected) "✓ ${preset.label}" else preset.label,
                                    color = when {
                                        isBusy -> palette.keyTextMuted
                                        isSelected -> Color.White
                                        palette.isDark -> Color(0xFF6EE7B7)
                                        else -> Color(0xFF047857)
                                    },
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                        visibleHelpPresets.forEach { preset ->
                            val isSelected = selectedPreset?.id == preset.id
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(13.dp))
                                    .background(if (isSelected) TypeFreeEmerald else palette.cardElevatedBg)
                                    .border(
                                        width = 1.dp,
                                        color = if (isSelected) TypeFreeEmerald else palette.keyBorder,
                                        shape = RoundedCornerShape(13.dp)
                                    )
                                    .clickable(enabled = !isBusy) { onPresetClick(preset) }
                                    .padding(horizontal = 10.dp, vertical = (5.5f * s).dp)
                            ) {
                                Text(
                                    text = if (isSelected) "✓ ${preset.label}" else preset.label,
                                    color = when {
                                        isBusy -> palette.keyTextMuted
                                        isSelected -> Color.White
                                        else -> palette.keyTextPrimary
                                    },
                                    fontSize = 11.5.sp,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium
                                )
                            }
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(13.dp))
                                .background(palette.keyCapBg)
                                .border(1.dp, palette.keyBorder, RoundedCornerShape(13.dp))
                                .clickable(enabled = !isBusy) { onOpenHostApp(AppDestinations.TAB_STYLES) }
                                .padding(horizontal = 10.dp, vertical = (5.5f * s).dp)
                        ) {
                            Text(
                                text = "＋ 自定义",
                                color = palette.keyTextMuted,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            } else {
                // Attitude + Inspiration Chips (Select attitude first, then speak or tap ⚡ 直接生成)
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(
                        text = if (selectedPreset != null) {
                            "已选态度「${selectedPreset.label}」· 按住下方麦克风补充细节，或点右侧 直接生成："
                        } else {
                            "先点选回复态度或灵感（支持选后按住语音补充细节），或直接口述："
                        },
                        color = if (selectedPreset != null) {
                            if (palette.isDark) Color(0xFF6EE7B7) else Color(0xFF047857)
                        } else {
                            palette.keyTextMuted
                        },
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        attitudePresets.forEach { preset ->
                            val isSelected = selectedPreset?.id == preset.id
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(
                                        if (isSelected) TypeFreeEmerald else TypeFreeEmerald.copy(alpha = 0.12f)
                                    )
                                    .border(
                                        width = 1.dp,
                                        color = if (isSelected) TypeFreeEmerald else TypeFreeEmerald.copy(alpha = 0.45f),
                                        shape = RoundedCornerShape(14.dp)
                                    )
                                    .clickable(enabled = !isBusy) { onPresetClick(preset) }
                                    .padding(horizontal = 10.dp, vertical = (6.5f * s).dp)
                            ) {
                                Text(
                                    text = if (isSelected) "✓ ${preset.label}" else preset.label,
                                    color = when {
                                        isBusy -> palette.keyTextMuted
                                        isSelected -> Color.White
                                        palette.isDark -> Color(0xFF6EE7B7)
                                        else -> Color(0xFF047857)
                                    },
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                        visibleHelpPresets.forEach { preset ->
                            val isSelected = selectedPreset?.id == preset.id
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(if (isSelected) TypeFreeEmerald else palette.cardElevatedBg)
                                    .border(
                                        width = 1.dp,
                                        color = if (isSelected) TypeFreeEmerald else palette.keyBorder,
                                        shape = RoundedCornerShape(14.dp)
                                    )
                                    .clickable(enabled = !isBusy) { onPresetClick(preset) }
                                    .padding(horizontal = 11.dp, vertical = (6.5f * s).dp)
                            ) {
                                Text(
                                    text = if (isSelected) "✓ ${preset.label}" else preset.label,
                                    color = when {
                                        isBusy -> palette.keyTextMuted
                                        isSelected -> Color.White
                                        else -> palette.keyTextPrimary
                                    },
                                    fontSize = 11.5.sp,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium
                                )
                            }
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .background(palette.keyCapBg)
                                .border(1.dp, palette.keyBorder, RoundedCornerShape(14.dp))
                                .clickable(enabled = !isBusy) { onOpenHostApp(AppDestinations.TAB_STYLES) }
                                .padding(horizontal = 10.dp, vertical = (6.5f * s).dp)
                        ) {
                            Text(
                                text = "＋ 自定义",
                                color = palette.keyTextMuted,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }

            // Bottom-Left Pulsing Circle Button + Prompt Description + Optional `直接生成`
            SubPanelVoiceTriggerRow(
                phase = phase,
                amplitudeProvider = amplitudeProvider,
                amplitudes = amplitudes,
                elapsedMs = elapsedMs,
                idleTitle = when {
                    selectedPreset != null -> "已选「${selectedPreset.label}」· 按住口述补充细节"
                    isContextActive -> "Say how to reply · 口述你的回复态度"
                    else -> "Describe what to write · 口述你想写的内容"
                },
                idleSubtitle = when {
                    selectedPreset != null -> "说具体原因/时间，或点右侧「直接生成」直接起草"
                    isContextActive -> "点上方选态度后可继续语音补充 · 如：跟他说下午三点没问题"
                    else -> "可先点选上方态度芯片再说话，或直接口述需求"
                },
                recordingTitle = when {
                    selectedPreset != null -> "已选「${selectedPreset.label}」· 正在聆听补充细节"
                    isContextActive -> "Say how to reply · 正在聆听回复意图"
                    else -> "Describe what to write · 正在聆听需求"
                },
                directActionLabel = if (selectedPreset != null) "直接生成" else null,
                onDirectActionClick = onDirectGenerate,
                onStartRecording = onStartRecording,
                onReleaseRecording = onReleaseRecording,
                onFinishHandsFree = onFinishHandsFree,
                onCancelRecording = onCancelRecording
            )
        }

        BottomCornerUtilityIcons(
            onHideKeyboard = onHideKeyboard,
            onSwitchKeyboard = onSwitchKeyboard,
            onShowImePicker = onShowImePicker,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}
