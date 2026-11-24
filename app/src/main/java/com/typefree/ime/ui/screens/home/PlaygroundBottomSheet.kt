package com.typefree.ime.ui.screens.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.typefree.ime.audio.WavAudioRecorder
import com.typefree.ime.data.VoiceIntentMode
import com.typefree.ime.i18n.AppLang
import com.typefree.ime.i18n.AppStrings
import com.typefree.ime.i18n.LocalAppLanguage
import com.typefree.ime.i18n.tr
import com.typefree.ime.network.PipelineStage
import com.typefree.ime.ui.components.TypeFreeIcons
import com.typefree.ime.ui.theme.TypeFreeEmerald
import com.typefree.ime.ui.theme.TypeFreeRose
import java.util.Locale

data class SandboxPreviewDiff(
    val intentMode: VoiceIntentMode,
    val originalSelection: String,
    val rawTranscript: String,
    val polishedOutput: String,
    val latencyMs: Long,
    val modeSummary: String
)

private fun localizeStyleBadgeText(raw: String, lang: AppLang): String {
    if (lang == AppLang.ZH_CN) return raw
    return raw
        .replace("通用灵动", AppStrings.pick(lang, "通用灵动", "Smart Default", "通用靈動"))
        .replace("社交聊天", AppStrings.pick(lang, "社交聊天", "Casual Chat", "社交聊天"))
        .replace("职场干练", AppStrings.pick(lang, "职场干练", "Work & Slack", "職場幹練"))
        .replace("正式邮件", AppStrings.pick(lang, "正式邮件", "Formal Email", "正式郵件"))
        .replace("编程与 Prompt", AppStrings.pick(lang, "编程与 Prompt", "Code & Prompt", "程式與 Prompt"))
        .replace("直出地道英文", AppStrings.pick(lang, "直出地道英文", "Native English Output", "直出地道英文"))
}

/**
 * Compact launch card on `HomeSetupScreen` that opens [PlaygroundBottomSheet] on demand,
 * keeping `HomeSetupScreen` lightweight and preventing nested keyboard occlusion.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlaygroundEntryCard(
    activeStyleLabel: String,
    onOpenPlayground: () -> Unit
) {
    val appLang = LocalAppLanguage.current
    val dispStyle = localizeStyleBadgeText(activeStyleLabel, appLang)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenPlayground),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = TypeFreeIcons.KeyboardGrid,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(22.dp)
                    )
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = tr("全功能实测沙盒 (Playground)", "Voice & AI Playground", "全功能實測沙盒 (Playground)"),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.align(Alignment.CenterVertically)
                        )
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = TypeFreeEmerald.copy(alpha = 0.14f),
                            modifier = Modifier.align(Alignment.CenterVertically)
                        ) {
                            Text(
                                text = tr("弹层试玩", "Live Demo", "彈層試玩"),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = TypeFreeEmerald,
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    Text(
                        text = tr(
                            "当前风格：$dispStyle · 点击打开独立试玩抽屉体验听写、改写与快译",
                            "Active tone: $dispStyle · Dictate, edit & translate",
                            "目前風格：$dispStyle · 點擊開啟獨立試玩抽屜體驗聽寫、改寫與快譯"
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            FilledTonalButton(
                onClick = onOpenPlayground,
                shape = RoundedCornerShape(11.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(
                    text = tr("立即体验", "Try Now", "立即體驗"),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaygroundBottomSheet(
    sandboxValue: TextFieldValue,
    onSandboxValueChange: (TextFieldValue) -> Unit,
    effectiveIntent: VoiceIntentMode,
    manualIntent: VoiceIntentMode,
    onSelectManualIntent: (VoiceIntentMode) -> Unit,
    translateTarget: String,
    activeStyleLabel: String,
    selectedLength: Int,
    undoAvailable: Boolean,
    copyFeedback: Boolean,
    autoLearnedBanner: String?,
    isRecording: Boolean,
    processingStage: PipelineStage?,
    amplitudes: List<Float>,
    elapsedMs: Long,
    sandboxError: String?,
    isApiReady: Boolean,
    sandboxDiff: SandboxPreviewDiff?,
    onOpenStyles: () -> Unit,
    onOpenApiConfig: () -> Unit,
    onOpenDictionary: () -> Unit,
    onSelectSamplePhrase: () -> Unit,
    onResetSample: () -> Unit,
    onUndo: () -> Unit,
    onCopy: () -> Unit,
    onClear: () -> Unit,
    onStartRecording: () -> Unit,
    onCancelRecording: () -> Unit,
    onStopAndProcess: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            PlaygroundCard(
                sandboxValue = sandboxValue,
                onSandboxValueChange = onSandboxValueChange,
                effectiveIntent = effectiveIntent,
                manualIntent = manualIntent,
                onSelectManualIntent = onSelectManualIntent,
                translateTarget = translateTarget,
                activeStyleLabel = activeStyleLabel,
                selectedLength = selectedLength,
                undoAvailable = undoAvailable,
                copyFeedback = copyFeedback,
                autoLearnedBanner = autoLearnedBanner,
                isRecording = isRecording,
                processingStage = processingStage,
                amplitudes = amplitudes,
                elapsedMs = elapsedMs,
                sandboxError = sandboxError,
                isApiReady = isApiReady,
                sandboxDiff = sandboxDiff,
                onOpenStyles = onOpenStyles,
                onOpenApiConfig = onOpenApiConfig,
                onOpenDictionary = onOpenDictionary,
                onSelectSamplePhrase = onSelectSamplePhrase,
                onResetSample = onResetSample,
                onUndo = onUndo,
                onCopy = onCopy,
                onClear = onClear,
                onStartRecording = onStartRecording,
                onCancelRecording = onCancelRecording,
                onStopAndProcess = onStopAndProcess
            )
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun PlaygroundCard(
    sandboxValue: TextFieldValue,
    onSandboxValueChange: (TextFieldValue) -> Unit,
    effectiveIntent: VoiceIntentMode,
    manualIntent: VoiceIntentMode,
    onSelectManualIntent: (VoiceIntentMode) -> Unit,
    translateTarget: String,
    activeStyleLabel: String,
    selectedLength: Int,
    undoAvailable: Boolean,
    copyFeedback: Boolean,
    autoLearnedBanner: String?,
    isRecording: Boolean,
    processingStage: PipelineStage?,
    amplitudes: List<Float>,
    elapsedMs: Long,
    sandboxError: String?,
    isApiReady: Boolean,
    sandboxDiff: SandboxPreviewDiff?,
    onOpenStyles: () -> Unit,
    onOpenApiConfig: () -> Unit,
    onOpenDictionary: () -> Unit,
    onSelectSamplePhrase: () -> Unit,
    onResetSample: () -> Unit,
    onUndo: () -> Unit,
    onCopy: () -> Unit,
    onClear: () -> Unit,
    onStartRecording: () -> Unit,
    onCancelRecording: () -> Unit,
    onStopAndProcess: () -> Unit
) {
    val appLang = LocalAppLanguage.current
    val dispStyle = localizeStyleBadgeText(activeStyleLabel, appLang)
    val isSpeakToEdit = effectiveIntent == VoiceIntentMode.SPEAK_TO_EDIT

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.58f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = tr("全功能实测沙盒 (Playground)", "Voice & AI Playground", "全功能實測沙盒 (Playground)"),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = tr(
                            "预置真实文本，一站体验五大核心语音交互黑科技",
                            "Hands-on sandbox for dictation, voice editing, writing, and live translation",
                            "預置真實文字，一站體驗五大核心語音互動能力"
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                Surface(
                    onClick = onOpenStyles,
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        text = dispStyle,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.52f)
            ) {
                Column(
                    modifier = Modifier.padding(11.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = tr(
                            "推荐直接点击下方输入框依次体验：",
                            "Tap the editor below to try each workflow:",
                            "推薦直接點選下方輸入框依序體驗："
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = tr(
                            "① 口述净写（去口癖与改口自纠） · ② 顶栏 [帮写] · ③ 选中文字 Speak to edit · ④ 长按上滑弧形快译 · ⑤ 键盘改错自动入词典",
                            "① Smart Dictation (removes fillers & self-corrections) · ② [Help Write] · ③ Select text for Speak-to-Edit · ④ Hold & swipe up for Arc Translate · ⑤ Auto-learn typing fixes",
                            "① 口述淨寫（去口頭禪與改口自糾） · ② 頂欄 [幫寫] · ③ 選取文字 Speak to edit · ④ 長按上滑弧形快譯 · ⑤ 鍵盤改錯自動入詞典"
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 17.sp
                    )
                }
            }

            if (!isSpeakToEdit) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(3.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        PlaygroundIntentPill(
                            label = tr("口述净写", "Dictate", "口述淨寫"),
                            selected = manualIntent == VoiceIntentMode.DICTATE,
                            onClick = { onSelectManualIntent(VoiceIntentMode.DICTATE) },
                            modifier = Modifier.weight(1f)
                        )
                        PlaygroundIntentPill(
                            label = tr("帮我写", "Help Write", "幫我寫"),
                            selected = manualIntent == VoiceIntentMode.HELP_ME_WRITE,
                            onClick = { onSelectManualIntent(VoiceIntentMode.HELP_ME_WRITE) },
                            modifier = Modifier.weight(1f)
                        )
                        PlaygroundIntentPill(
                            label = tr("快译 ($translateTarget)", "Translate ($translateTarget)", "快譯 ($translateTarget)"),
                            selected = manualIntent == VoiceIntentMode.TRANSLATE,
                            onClick = { onSelectManualIntent(VoiceIntentMode.TRANSLATE) },
                            modifier = Modifier.weight(1.25f)
                        )
                    }
                }
            } else {
                val editBg by animateColorAsState(
                    targetValue = TypeFreeEmerald.copy(alpha = 0.12f),
                    label = "editBannerBg"
                )
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    color = editBg,
                    border = BorderStroke(1.dp, TypeFreeEmerald.copy(alpha = 0.45f))
                ) {
                    Text(
                        text = tr(
                            "已激活「选中文字 Speak to edit」（已选 $selectedLength 字）：点击下方录音说出修改指令，如「改成更礼貌简短的商务语气」或「翻译成日文」！",
                            "Speak-to-Edit active ($selectedLength chars selected): Tap Record below and speak an instruction, e.g. \"Make this more concise and polite\" or \"Translate to Japanese\".",
                            "已啟用「選取文字 Speak to edit」（已選 $selectedLength 字）：點選下方錄音說出修改指令，如「改成更禮貌簡短的商務語氣」或「翻譯成日文」！"
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = TypeFreeEmerald,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp)
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AssistChip(
                    onClick = onSelectSamplePhrase,
                    label = {
                        Text(
                            tr("一键选中句子测 Speak to edit", "Select Sentence for Speak-to-Edit", "一鍵選取句子測 Speak to edit"),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                )

                AssistChip(
                    onClick = onResetSample,
                    label = { Text(tr("恢复示例文本", "Reset Sample", "恢復範例文字"), style = MaterialTheme.typography.labelMedium) }
                )

                if (undoAvailable) {
                    AssistChip(
                        onClick = onUndo,
                        leadingIcon = {
                            Icon(
                                imageVector = TypeFreeIcons.Undo,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                        },
                        label = { Text(tr("撤销", "Undo", "復原"), style = MaterialTheme.typography.labelMedium) }
                    )
                }

                if (sandboxValue.text.isNotEmpty()) {
                    AssistChip(
                        onClick = onCopy,
                        leadingIcon = {
                            Icon(
                                imageVector = TypeFreeIcons.Copy,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                        },
                        label = {
                            Text(
                                if (copyFeedback) tr("已复制", "Copied", "已複製") else tr("复制", "Copy", "複製"),
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    )

                    AssistChip(
                        onClick = onClear,
                        label = { Text(tr("清空", "Clear", "清空"), style = MaterialTheme.typography.labelMedium) }
                    )
                }
            }

            OutlinedTextField(
                value = sandboxValue,
                onValueChange = onSandboxValueChange,
                modifier = Modifier.fillMaxWidth(),
                minLines = 4,
                maxLines = 8,
                shape = RoundedCornerShape(14.dp),
                placeholder = {
                    Text(
                        text = tr(
                            "点击此处唤起灵言语音键盘或悬浮声波球，也可直接点击下方内置录音按钮实测...",
                            "Tap here to open the TypeFree keyboard, or use the built-in record button below to test...",
                            "點選此處喚起靈言語音鍵盤或懸浮聲波球，也可直接點選下方內建錄音按鈕實測..."
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
                    )
                }
            )

            AnimatedVisibility(visible = autoLearnedBanner != null) {
                autoLearnedBanner?.let { msg ->
                    Surface(
                        onClick = onOpenDictionary,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        color = TypeFreeEmerald.copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, TypeFreeEmerald.copy(alpha = 0.45f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = msg,
                                style = MaterialTheme.typography.labelMedium,
                                color = TypeFreeEmerald,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = tr("查看词典 ›", "Dictionary ›", "查看詞典 ›"),
                                style = MaterialTheme.typography.labelSmall,
                                color = TypeFreeEmerald,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }
            }

            SandboxVoiceCapsule(
                isRecording = isRecording,
                processingStage = processingStage,
                effectiveIntent = effectiveIntent,
                translateTarget = translateTarget,
                amplitudes = amplitudes,
                elapsedMs = elapsedMs,
                onStartRecording = onStartRecording,
                onCancelRecording = onCancelRecording,
                onStopAndProcess = onStopAndProcess
            )

            AnimatedVisibility(visible = sandboxError != null) {
                sandboxError?.let { errMsg ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = TypeFreeRose.copy(alpha = 0.10f),
                        border = BorderStroke(1.dp, TypeFreeRose.copy(alpha = 0.45f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = errMsg,
                                style = MaterialTheme.typography.bodySmall,
                                color = TypeFreeRose,
                                modifier = Modifier.weight(1f)
                            )
                            if (!isApiReady || errMsg.contains("API", ignoreCase = true)) {
                                TextButton(
                                    onClick = onOpenApiConfig,
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text(tr("去配置 ›", "Configure ›", "去設定 ›"), style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false)
                                }
                            }
                        }
                    }
                }
            }

            AnimatedVisibility(visible = sandboxDiff != null) {
                sandboxDiff?.let { diff ->
                    SandboxDiffPreviewCard(diff = diff)
                }
            }
        }
    }
}

@Composable
private fun PlaygroundIntentPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = if (selected) MaterialTheme.colorScheme.surface else Color.Transparent,
        shadowElevation = if (selected) 1.dp else 0.dp
    ) {
        Box(
            modifier = Modifier.padding(vertical = 7.dp, horizontal = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun SandboxVoiceCapsule(
    isRecording: Boolean,
    processingStage: PipelineStage?,
    effectiveIntent: VoiceIntentMode,
    translateTarget: String,
    amplitudes: List<Float>,
    elapsedMs: Long,
    onStartRecording: () -> Unit,
    onCancelRecording: () -> Unit,
    onStopAndProcess: () -> Unit
) {
    val appLang = LocalAppLanguage.current
    val isSpeakToEdit = effectiveIntent == VoiceIntentMode.SPEAK_TO_EDIT
    val intentLabel = AppStrings.voiceIntentLabel(effectiveIntent, appLang)

    when {
        processingStage != null -> {
            val stageLabel = when (processingStage) {
                PipelineStage.TRANSCRIBING_STT -> tr(
                    "正在通过 STT 接口识别语音初稿...",
                    "Transcribing speech via STT...",
                    "正在透過 STT 介面識別語音初稿..."
                )
                PipelineStage.POLISHING_LLM -> tr(
                    "正在调用大模型执行${intentLabel}...",
                    "Running $intentLabel with LLM...",
                    "正在呼叫大模型執行${intentLabel}..."
                )
                PipelineStage.MULTIMODAL_PROCESSING -> tr(
                    "多模态大模型正在听音理解直出...",
                    "Multimodal model processing audio directly...",
                    "多模態大模型正在聽音理解直出..."
                )
            }
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = stageLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        isRecording -> {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(9.dp)
                                    .clip(CircleShape)
                                    .background(TypeFreeRose)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = when (effectiveIntent) {
                                    VoiceIntentMode.SPEAK_TO_EDIT -> tr("正在聆听选区改写指令...", "Listening for edit instruction...", "正在聆聽選區改寫指令...")
                                    VoiceIntentMode.HELP_ME_WRITE -> tr("正在聆听写作需求...", "Listening for writing prompt...", "正在聆聽寫作需求...")
                                    VoiceIntentMode.TRANSLATE -> tr("正在聆听并准备译为 $translateTarget...", "Listening to translate into $translateTarget...", "正在聆聽並準備譯為 $translateTarget...")
                                    VoiceIntentMode.DICTATE -> tr("正在聆听口述语音...", "Listening for dictation...", "正在聆聽口述語音...")
                                },
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = formatDurationSec(elapsedMs),
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontFamily = FontFamily.Monospace
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    val waveColor = MaterialTheme.colorScheme.primary
                    Canvas(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(36.dp)
                    ) {
                        val bars = amplitudes.ifEmpty { List(WavAudioRecorder.BAR_COUNT) { 0.1f } }
                        val count = bars.size
                        val gapPx = 4.dp.toPx()
                        val totalGap = gapPx * (count - 1).coerceAtLeast(0)
                        val barWidth = ((size.width - totalGap) / count.coerceAtLeast(1)).coerceAtLeast(2f)

                        bars.forEachIndexed { index, amp ->
                            val clamped = amp.coerceIn(0.08f, 1.0f)
                            val barHeight = (size.height * clamped).coerceAtLeast(4.dp.toPx())
                            val x = index * (barWidth + gapPx)
                            val y = (size.height - barHeight) / 2f
                            drawRoundRect(
                                color = if (isSpeakToEdit) TypeFreeEmerald else waveColor,
                                topLeft = Offset(x, y),
                                size = Size(barWidth, barHeight),
                                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = onCancelRecording,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(tr("取消", "Cancel", "取消"), maxLines = 1, softWrap = false)
                        }
                        Button(
                            onClick = onStopAndProcess,
                            modifier = Modifier.weight(1.7f),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isSpeakToEdit) {
                                    TypeFreeEmerald
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                                contentColor = if (isSpeakToEdit) {
                                    Color.White
                                } else {
                                    MaterialTheme.colorScheme.onPrimary
                                }
                            )
                        ) {
                            Icon(
                                imageVector = TypeFreeIcons.Sparkles,
                                contentDescription = null,
                                modifier = Modifier.size(17.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = tr("说完了 · 立即生成", "Done · Generate", "說完了 · 立即生成"),
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }
            }
        }

        else -> {
            Button(
                onClick = onStartRecording,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isSpeakToEdit) {
                        TypeFreeEmerald
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    contentColor = if (isSpeakToEdit) {
                        Color.White
                    } else {
                        MaterialTheme.colorScheme.onPrimary
                    }
                )
            ) {
                Icon(
                    imageVector = when (effectiveIntent) {
                        VoiceIntentMode.HELP_ME_WRITE, VoiceIntentMode.SPEAK_TO_EDIT -> TypeFreeIcons.VoicePen
                        VoiceIntentMode.TRANSLATE -> TypeFreeIcons.Translate
                        VoiceIntentMode.DICTATE -> TypeFreeIcons.VoiceWaveform
                    },
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = when (effectiveIntent) {
                        VoiceIntentMode.SPEAK_TO_EDIT -> tr("点击录音 · 对选中文字下达语音编辑指令", "Tap to Record · Speak Edit Instruction", "點選錄音 · 對選取文字下達語音編輯指令")
                        VoiceIntentMode.HELP_ME_WRITE -> tr("点击录音 · 说出写作主题直接生成草稿", "Tap to Record · Speak Topic to Draft", "點選錄音 · 說出寫作主題直接產生草稿")
                        VoiceIntentMode.TRANSLATE -> tr("点击录音 · 说中文直接快译为 $translateTarget", "Tap to Record · Translate into $translateTarget", "點選錄音 · 說話直接快譯為 $translateTarget")
                        VoiceIntentMode.DICTATE -> tr("点击内置麦克风实测口述净写", "Tap Built-in Mic to Test Smart Dictation", "點選內建麥克風實測口述淨寫")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SandboxDiffPreviewCard(diff: SandboxPreviewDiff) {
    val intentLabel = AppStrings.voiceIntentLabel(diff.intentMode, LocalAppLanguage.current)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = tr("✓ ${intentLabel}完成对比", "✓ $intentLabel Before & After", "✓ ${intentLabel}完成對比"),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = TypeFreeEmerald
                )
                Text(
                    text = "${diff.modeSummary} · ${diff.latencyMs} ms",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (diff.originalSelection.isNotBlank()) {
                Text(
                    text = tr("选区原文：${diff.originalSelection}", "Selected text: ${diff.originalSelection}", "選區原文：${diff.originalSelection}"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text(
                text = tr("语音初稿：${diff.rawTranscript}", "Raw transcript: ${diff.rawTranscript}", "語音初稿：${diff.rawTranscript}"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text(
                text = tr("上屏结果：${diff.polishedOutput}", "Final output: ${diff.polishedOutput}", "上屏結果：${diff.polishedOutput}"),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

private fun formatDurationSec(ms: Long): String {
    val totalTenths = (ms.coerceAtLeast(0L) / 100L)
    val secs = totalTenths / 10L
    val tenths = totalTenths % 10L
    val mins = secs / 60L
    val remSecs = secs % 60L
    return String.format(Locale.US, "%02d:%02d.%d", mins, remSecs, tenths)
}
