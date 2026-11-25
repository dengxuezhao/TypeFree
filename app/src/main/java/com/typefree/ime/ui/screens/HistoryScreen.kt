package com.typefree.ime.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.typefree.ime.data.AppRepository
import com.typefree.ime.data.DefaultPresets
import com.typefree.ime.data.HistoryRecord
import com.typefree.ime.data.HistoryRetention
import com.typefree.ime.data.ToneStyle
import com.typefree.ime.data.VoiceIntentMode
import com.typefree.ime.data.VoiceLatencyTrace
import com.typefree.ime.data.VoiceTraceStage
import com.typefree.ime.data.stats.TokenFeatureKeys
import com.typefree.ime.i18n.AppLang
import com.typefree.ime.i18n.AppStrings
import com.typefree.ime.i18n.LocalAppLanguage
import com.typefree.ime.i18n.tr
import com.typefree.ime.network.AiPipelineEngine
import com.typefree.ime.network.PipelineExecutionResult
import com.typefree.ime.network.PipelineStage
import com.typefree.ime.network.VoiceLatencyTraceAssembler
import com.typefree.ime.ui.components.ContinuousRoundedCornerShape
import com.typefree.ime.ui.components.RollingDigitCounter
import com.typefree.ime.ui.components.SmoothDisclosure
import com.typefree.ime.ui.components.TypeFreeIcons
import com.typefree.ime.ui.components.springPressScale
import com.typefree.ime.ui.theme.LocalTypeFreeKeyboardPalette
import com.typefree.ime.ui.theme.TypeFreeEmerald
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

// Plan A: Obsidian × Emerald cohesive 5-step tonal scale (Dark & Light)
private val PlanADarkShades = listOf(
    Color(0xFF52525E), // 0: Audio Stop / Flush / Encode
    Color(0xFF0F766E), // 1: Stage-1 ASR / Streaming Tail
    Color(0xFF10B981), // 2: Stage-2 LLM TTFT / Prefill
    Color(0xFF6EE7B7), // 3: Stage-2 LLM Stream / Decode
    Color(0xFFE4E4E7)  // 4: Editor InputConnection Commit
)

private val PlanALightShades = listOf(
    Color(0xFFA1A1AA), // 0: Audio Stop / Flush / Encode
    Color(0xFF0D9488), // 1: Stage-1 ASR / Streaming Tail
    Color(0xFF059669), // 2: Stage-2 LLM TTFT / Prefill
    Color(0xFF34D399), // 3: Stage-2 LLM Stream / Decode
    Color(0xFF27272A)  // 4: Editor InputConnection Commit
)

private fun resolvePlanAToneColor(toneStep: Int, isDark: Boolean): Color {
    val shades = if (isDark) PlanADarkShades else PlanALightShades
    return shades[toneStep.coerceIn(0, shades.lastIndex)]
}

private data class RepolishDialogState(
    val sourceRecord: HistoryRecord,
    val styleUsed: ToneStyle,
    val executionResult: PipelineExecutionResult,
    val fromAudioRetry: Boolean = false
)

private data class HistoryDateGroup(
    val dateKey: String,
    val dateHeader: String,
    val records: List<HistoryRecord>
)

private val HISTORY_FACTORY_STYLES: Map<String, ToneStyle> by lazy {
    DefaultPresets.defaultStyles().associateBy { it.id }
}

private fun localizedHistoryStyleName(style: ToneStyle, lang: AppLang): String {
    if (!style.isBuiltIn) return style.name
    val factory = HISTORY_FACTORY_STYLES[style.id]
    if (factory != null && style.name != factory.name) return style.name
    return AppStrings.toneStyleName(style, lang)
}

private fun localizeHistoryTargetAppName(raw: String, lang: AppLang): String {
    return when (raw.trim()) {
        "历史录音重跑" -> AppStrings.pick(lang, "历史录音重跑", "Audio Retry", "歷史錄音重跑")
        "历史重跑" -> AppStrings.pick(lang, "历史重跑", "History Repolish", "歷史重跑")
        "微信" -> AppStrings.pick(lang, "微信", "WeChat", "微信")
        "飞书" -> AppStrings.pick(lang, "飞书", "Feishu / Lark", "飛書")
        "钉钉" -> AppStrings.pick(lang, "钉钉", "DingTalk", "釘釘")
        "企业微信" -> AppStrings.pick(lang, "企业微信", "WeCom", "企業微信")
        "小红书" -> AppStrings.pick(lang, "小红书", "Xiaohongshu", "小紅書")
        "系统短信" -> AppStrings.pick(lang, "系统短信", "Messages", "系統簡訊")
        else -> raw
    }
}

private fun formatHistoryChars(chars: Int, lang: AppLang): String {
    val safe = chars.coerceAtLeast(0)
    return if (lang == AppLang.EN) {
        if (safe >= 10_000) String.format(Locale.US, "%.1fk", safe / 1_000.0)
        else String.format(Locale.US, "%,d", safe)
    } else {
        SetupStatsHelper.formatCharCount(safe)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HistoryScreen(
    repository: AppRepository,
    aiEngine: AiPipelineEngine,
    initialDateKey: Int? = null
) {
    val context = LocalContext.current
    val appLang = LocalAppLanguage.current
    val coroutineScope = rememberCoroutineScope()

    val history by repository.history.collectAsState()
    val settings by repository.settings.collectAsState()
    val styles = settings.styles.ifEmpty { DefaultPresets.defaultStyles() }

    var searchQuery by rememberSaveable { mutableStateOf("") }
    // 0 = 全部, 1 = 口述净写 (DICTATE), 2 = 帮我写与编辑 (HELP_ME_WRITE & SPEAK_TO_EDIT), 3 = 实时快译 (TRANSLATE)
    var selectedFilter by rememberSaveable { mutableIntStateOf(0) }
    var activeDateFilterKey by rememberSaveable(initialDateKey) { mutableStateOf(initialDateKey) }
    LaunchedEffect(initialDateKey) {
        activeDateFilterKey = initialDateKey
    }

    var showClearAllConfirm by remember { mutableStateOf(false) }
    var alsoResetStats by remember { mutableStateOf(false) }
    var retentionMenuExpanded by remember { mutableStateOf(false) }

    var activeBusyRecordId by remember { mutableStateOf<String?>(null) }
    var activeBusyLabel by remember { mutableStateOf<String?>(null) }
    var repolishDialogState by remember { mutableStateOf<RepolishDialogState?>(null) }

    val dictateCount = remember(history) {
        history.count { resolveRecordIntent(it) == VoiceIntentMode.DICTATE }
    }
    val writeAndEditCount = remember(history) {
        history.count {
            val mode = resolveRecordIntent(it)
            mode == VoiceIntentMode.HELP_ME_WRITE || mode == VoiceIntentMode.SPEAK_TO_EDIT
        }
    }
    val translateCount = remember(history) {
        history.count { resolveRecordIntent(it) == VoiceIntentMode.TRANSLATE }
    }

    val filteredHistory = remember(history, searchQuery, selectedFilter, activeDateFilterKey) {
        val q = searchQuery.trim()
        history
            .sortedByDescending { it.timestamp }
            .filter { record ->
                val dateMatch = activeDateFilterKey == null || epochMsToDateKey(record.timestamp) == activeDateFilterKey
                val mode = resolveRecordIntent(record)
                val modeMatch = when (selectedFilter) {
                    1 -> mode == VoiceIntentMode.DICTATE
                    2 -> mode == VoiceIntentMode.HELP_ME_WRITE || mode == VoiceIntentMode.SPEAK_TO_EDIT
                    3 -> mode == VoiceIntentMode.TRANSLATE
                    else -> true
                }
                val queryMatch = if (q.isEmpty()) {
                    true
                } else {
                    record.polishedText.contains(q, ignoreCase = true) ||
                        record.rawTranscript.contains(q, ignoreCase = true) ||
                        record.targetAppName.contains(q, ignoreCase = true) ||
                        record.originalSelectedText.contains(q, ignoreCase = true) ||
                        record.styleName.contains(q, ignoreCase = true) ||
                        record.translateTargetLanguage.contains(q, ignoreCase = true)
                }
                dateMatch && modeMatch && queryMatch
            }
    }

    val filteredStats = remember(filteredHistory) {
        SetupStatsHelper.computeProductivityStats(filteredHistory)
    }

    val groupedHistory = remember(filteredHistory, appLang) {
        groupRecordsByDate(filteredHistory, appLang)
    }

    val multimodalPrefixZh = AppStrings.pick(AppLang.ZH_CN, "[多模态", "[Multimodal", "[多模態")

    fun triggerTextRepolish(record: HistoryRecord, overrideStyle: ToneStyle? = null) {
        if (activeBusyRecordId != null) return
        val styleToUse = overrideStyle ?: repository.resolveActiveStyle(
            record.targetPackage.ifBlank { null }
        ).first

        activeBusyRecordId = record.id
        activeBusyLabel = AppStrings.pick(appLang, "重新润色中…", "Repolishing…", "重新潤飾中…")
        coroutineScope.launch {
            val mode = resolveRecordIntent(record)
            val isEditMode = mode == VoiceIntentMode.SPEAK_TO_EDIT && record.originalSelectedText.isNotBlank()
            val inputSource = if (isEditMode) {
                record.originalSelectedText
            } else {
                record.rawTranscript
                    .takeIf { it.isNotBlank() && !it.startsWith(multimodalPrefixZh) && !it.startsWith("[Multimodal") }
                    ?: record.polishedText
            }
            val explicitInstruction = when {
                isEditMode -> record.rawTranscript
                    .takeIf { it.isNotBlank() && !it.startsWith(multimodalPrefixZh) && !it.startsWith("[Multimodal") }
                    ?: AppStrings.pick(
                        appLang,
                        "请按当前语气风格优化改写这段文本",
                        "Please polish and rewrite this text in the active tone style",
                        "請依目前語氣風格最佳化改寫這段文字"
                    )
                mode == VoiceIntentMode.TRANSLATE -> {
                    val targetLang = record.translateTargetLanguage.ifBlank { settings.activeTranslateTarget }
                    AppStrings.pick(
                        appLang,
                        "请将内容准确地道地翻译为 $targetLang，只输出译文",
                        "Translate the content accurately and naturally into $targetLang. Output only the translation.",
                        "請將內容準確道地翻譯為 $targetLang，僅輸出譯文"
                    )
                }
                else -> null
            }

            val result = aiEngine.runTextTransform(
                settings = settings,
                activeStyle = styleToUse,
                rawOrSelectedText = inputSource,
                explicitInstruction = explicitInstruction,
                targetAppName = record.targetAppName
            )
            activeBusyRecordId = null
            activeBusyLabel = null

            result.fold(
                onSuccess = { exec ->
                    if (settings.inputStatsEnabled && !settings.manualIncognitoMode) {
                        repository.dailyStatsStore.recordTokenUsage(
                            usage = exec.tokenUsage,
                            modelName = exec.modelName,
                            featureKey = TokenFeatureKeys.fromVoiceIntent(mode),
                            enabled = true,
                            incognito = false
                        )
                    }
                    repolishDialogState = RepolishDialogState(
                        sourceRecord = record,
                        styleUsed = styleToUse,
                        executionResult = exec,
                        fromAudioRetry = false
                    )
                },
                onFailure = { err ->
                    Toast.makeText(
                        context,
                        err.message ?: AppStrings.pick(
                            appLang,
                            "重新润色失败，请检查自定义 API 设置",
                            "Repolish failed. Please check your API settings.",
                            "重新潤飾失敗，請檢查 API 設定"
                        ),
                        Toast.LENGTH_LONG
                    ).show()
                }
            )
        }
    }

    fun triggerAudioRetry(record: HistoryRecord, wavFile: File) {
        if (activeBusyRecordId != null) return
        val styleToUse = repository.resolveActiveStyle(
            record.targetPackage.ifBlank { null }
        ).first
        val mode = resolveRecordIntent(record)
        val targetLang = record.translateTargetLanguage.ifBlank { settings.activeTranslateTarget }

        activeBusyRecordId = record.id
        activeBusyLabel = AppStrings.pick(appLang, "读取本地录音…", "Reading local audio…", "讀取本機錄音…")
        coroutineScope.launch {
            val result = aiEngine.processVoiceAudio(
                wavFile = wavFile,
                settings = settings,
                activeStyle = styleToUse,
                targetAppName = record.targetAppName.ifBlank {
                    AppStrings.pick(appLang, "历史录音重跑", "Audio Retry", "歷史錄音重跑")
                },
                selectedText = record.originalSelectedText,
                surroundingBeforeCursor = "",
                intentMode = mode,
                translateTargetLanguage = targetLang,
                onStageChanged = { stage ->
                    activeBusyLabel = when (stage) {
                        PipelineStage.TRANSCRIBING_STT -> AppStrings.pick(appLang, "STT 识别中…", "STT transcribing…", "STT 識別中…")
                        PipelineStage.POLISHING_LLM -> AppStrings.pick(appLang, "LLM 润色中…", "LLM polishing…", "LLM 潤飾中…")
                        PipelineStage.MULTIMODAL_PROCESSING -> AppStrings.pick(appLang, "多模态听音中…", "Multimodal audio…", "多模態聽音中…")
                    }
                }
            )
            activeBusyRecordId = null
            activeBusyLabel = null

            result.fold(
                onSuccess = { exec ->
                    if (settings.inputStatsEnabled && !settings.manualIncognitoMode) {
                        repository.dailyStatsStore.recordTokenUsage(
                            usage = exec.tokenUsage,
                            modelName = exec.modelName,
                            featureKey = TokenFeatureKeys.fromVoiceIntent(
                                intent = mode,
                                isMultimodalAudio = settings.pipelineMode == com.typefree.ime.data.PipelineMode.MULTIMODAL_DIRECT
                            ),
                            enabled = true,
                            incognito = false
                        )
                    }
                    repolishDialogState = RepolishDialogState(
                        sourceRecord = record,
                        styleUsed = styleToUse,
                        executionResult = exec,
                        fromAudioRetry = true
                    )
                },
                onFailure = { err ->
                    Toast.makeText(
                        context,
                        err.message ?: AppStrings.pick(
                            appLang,
                            "本地录音重试失败，请检查 API 配置",
                            "Local audio retry failed. Please check your API settings.",
                            "本機錄音重試失敗，請檢查 API 設定"
                        ),
                        Toast.LENGTH_LONG
                    ).show()
                }
            )
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Official TypeFree History Header
        item(key = "history_header") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = tr("历史记录", "History", "歷史記錄"),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.ExtraBold
                )

                if (history.isNotEmpty()) {
                    OutlinedButton(
                        onClick = {
                            alsoResetStats = false
                            showClearAllConfirm = true
                        },
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = tr("清空历史", "Clear All", "清空歷史"),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        // 2. Official Keep History + Zero Data Retention Privacy Card (Aligned with history_overview.webp)
        item(key = "history_privacy_retention_card") {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.52f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Row A: Keep history selector
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = TypeFreeIcons.History,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(17.dp)
                                )
                                Text(
                                    text = tr("保留周期", "History Retention", "保留週期"),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Text(
                                text = tr(
                                    "口述与润色历史在当前设备上保留多长时间？",
                                    "How long should dictation and polish history stay on this device?",
                                    "口述與潤飾歷史在目前裝置上保留多長時間？"
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Box {
                            Surface(
                                onClick = { retentionMenuExpanded = true },
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                border = BorderStroke(
                                    1.dp,
                                    MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
                                )
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = AppStrings.historyRetentionLabel(settings.historyRetention, appLang),
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Icon(
                                        imageVector = Icons.Default.KeyboardArrowDown,
                                        contentDescription = tr("选择保留周期", "Select retention period", "選擇保留週期"),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }

                            DropdownMenu(
                                expanded = retentionMenuExpanded,
                                onDismissRequest = { retentionMenuExpanded = false }
                            ) {
                                HistoryRetention.entries.forEach { option ->
                                    val selected = settings.historyRetention == option
                                    val optLabel = AppStrings.historyRetentionLabel(option, appLang)
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = optLabel,
                                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                                            )
                                        },
                                        trailingIcon = if (selected) {
                                            {
                                                Icon(
                                                    imageVector = Icons.Default.Check,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        } else null,
                                        onClick = {
                                            retentionMenuExpanded = false
                                            repository.updateSettings {
                                                it.copy(historyRetention = option)
                                            }
                                            Toast.makeText(
                                                context,
                                                AppStrings.pick(
                                                    appLang,
                                                    "历史保留策略已设为：$optLabel",
                                                    "History retention set to: $optLabel",
                                                    "歷史保留策略已設為：$optLabel"
                                                ),
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    )
                                }
                            }
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.28f))

                    // Row B: Privacy statement
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(TypeFreeEmerald.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = null,
                                tint = TypeFreeEmerald,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Text(
                                text = tr("历史只保存在本机 · 自有接口直连", "Stored On-Device Only · Direct API", "歷史僅儲存在本機 · 自有介面直連"),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = tr(
                                    "历史只保存在本机；语音与文本仅发送给你配置的服务商。",
                                    "History stays strictly on this device; audio and text are sent only to your configured provider.",
                                    "歷史僅儲存在本機；語音與文字僅傳送給你設定的服務商。"
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                lineHeight = 18.sp
                            )
                        }
                    }
                }
            }
        }

        // 3. Search Box + 4-Category Segmented Capsule Filter
        item(key = "history_search_and_filters") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (history.isNotEmpty() || searchQuery.isNotEmpty()) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        placeholder = {
                            Text(
                                tr(
                                    "搜索上屏文本、语音原稿、宿主应用或目标语言...",
                                    "Search output text, transcript, host app, or language...",
                                    "搜尋上屏文字、語音原稿、宿主應用或目標語言..."
                                )
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = null
                            )
                        },
                        trailingIcon = if (searchQuery.isNotEmpty()) {
                            {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = tr("清除搜索", "Clear search", "清除搜尋")
                                    )
                                }
                            }
                        } else null
                    )
                }

                // Segmented Capsule Track (Aligned with history_overview.webp)
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                ) {
                    Row(
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        HistoryCategoryPill(
                            label = tr("全部 (${history.size})", "All (${history.size})", "全部 (${history.size})"),
                            selected = selectedFilter == 0,
                            onClick = { selectedFilter = 0 }
                        )
                        HistoryCategoryPill(
                            label = tr("口述净写 ($dictateCount)", "Dictate ($dictateCount)", "口述淨寫 ($dictateCount)"),
                            selected = selectedFilter == 1,
                            onClick = { selectedFilter = 1 }
                        )
                        HistoryCategoryPill(
                            label = tr("帮我写与编辑 ($writeAndEditCount)", "Write & Edit ($writeAndEditCount)", "幫我寫與編輯 ($writeAndEditCount)"),
                            selected = selectedFilter == 2,
                            onClick = { selectedFilter = 2 }
                        )
                        HistoryCategoryPill(
                            label = tr("实时快译 ($translateCount)", "Translate ($translateCount)", "即時快譯 ($translateCount)"),
                            selected = selectedFilter == 3,
                            onClick = { selectedFilter = 3 }
                        )
                    }
                }

                activeDateFilterKey?.let { dateKey ->
                    val formattedDate = formatDateKeyLabel(dateKey, appLang)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            onClick = { activeDateFilterKey = null },
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.78f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 11.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Text(
                                    text = tr("筛选日期：$formattedDate ×", "Date: $formattedDate ×", "篩選日期：$formattedDate ×"),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    }
                }

                if (filteredHistory.isNotEmpty()) {
                    val formatChars = formatHistoryChars(filteredStats.totalPolishedChars, appLang)
                    val savedMin = SetupStatsHelper.formatSavedMinutes(filteredStats.savedMinutes)
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f)
                    ) {
                        Text(
                            text = tr(
                                "当前筛选 ${filteredHistory.size} 条 · 共 $formatChars 字 · 约省 $savedMin 分钟",
                                "${filteredHistory.size} items · $formatChars chars · ~$savedMin min saved",
                                "目前篩選 ${filteredHistory.size} 條 · 共 $formatChars 字 · 約省 $savedMin 分鐘"
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                        )
                    }
                }
            }
        }

        // 4. Grouped Date Timeline or Empty State
        when {
            history.isEmpty() -> {
                item(key = "history_empty_all") {
                    EmptyHistoryIllustration(
                        isNeverRetention = settings.historyRetention == HistoryRetention.NEVER
                    )
                }
            }

            groupedHistory.isEmpty() -> {
                item(key = "history_empty_filter") {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = tr("未找到匹配当前筛选条件的历史记录", "No history items match the current filters", "未找到符合目前篩選條件的歷史記錄"),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            TextButton(
                                onClick = {
                                    searchQuery = ""
                                    selectedFilter = 0
                                    activeDateFilterKey = null
                                }
                            ) {
                                Text(tr("重置搜索与分类筛选", "Reset Search & Filters", "重設搜尋與分類篩選"))
                            }
                        }
                    }
                }
            }

            else -> {
                groupedHistory.forEach { group ->
                    item(key = "date_header_${group.dateKey}") {
                        Text(
                            text = group.dateHeader,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp, start = 2.dp)
                        )
                    }

                    items(group.records, key = { it.id }) { record ->
                        HistoryTimelineCard(
                            record = record,
                            styles = styles,
                            fallbackTranslateLang = settings.activeTranslateTarget,
                            busyLabel = if (activeBusyRecordId == record.id) activeBusyLabel else null,
                            isAnyBusy = activeBusyRecordId != null,
                            onCopyPolished = {
                                copyTextToClipboard(
                                    context = context,
                                    label = "TypeFree Polished",
                                    text = record.polishedText,
                                    toastMessage = AppStrings.pick(appLang, "已复制上屏文本", "Copied final text", "已複製上屏文字")
                                )
                            },
                            onCopyRaw = {
                                val rawText = record.rawTranscript.ifBlank { record.originalSelectedText }
                                copyTextToClipboard(
                                    context = context,
                                    label = "TypeFree Raw",
                                    text = rawText,
                                    toastMessage = AppStrings.pick(appLang, "已复制原始转写稿", "Copied raw transcript", "已複製原始轉寫稿")
                                )
                            },
                            onRepolish = { triggerTextRepolish(record) },
                            onRetryAudio = { wavFile -> triggerAudioRetry(record, wavFile) },
                            onDelete = {
                                repository.deleteHistoryItem(record.id)
                                Toast.makeText(
                                    context,
                                    AppStrings.pick(appLang, "已删除记录", "Record deleted", "已刪除記錄"),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        )
                    }
                }
            }
        }

        item(key = "history_bottom_spacer") {
            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    // Clear All Confirmation Dialog (with optional dual-clear pure numeric stats checkbox)
    if (showClearAllConfirm) {
        AlertDialog(
            onDismissRequest = {
                showClearAllConfirm = false
                alsoResetStats = false
            },
            title = { Text(tr("清空全部历史记录", "Clear All History", "清空全部歷史記錄")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = tr(
                            "确定要从本机永久清空全部 ${history.size} 条历史记录吗？此操作无法撤销。",
                            "Permanently delete all ${history.size} history records from this device? This cannot be undone.",
                            "確定要從本機永久清空全部 ${history.size} 條歷史記錄嗎？此操作無法復原。"
                        ),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { alsoResetStats = !alsoResetStats },
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                        border = BorderStroke(
                            1.dp,
                            if (alsoResetStats) {
                                MaterialTheme.colorScheme.error.copy(alpha = 0.45f)
                            } else {
                                MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                            }
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Checkbox(
                                checked = alsoResetStats,
                                onCheckedChange = { alsoResetStats = it },
                                modifier = Modifier.size(22.dp)
                            )
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = tr("同时清零累计纯数字统计与里程碑", "Also reset cumulative stats & milestones", "同時清零累計純數字統計與里程碑"),
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = tr(
                                        "按日累计的纯数字统计不包含任何正文内容，默认保留以便继续追踪长期效率与成就。",
                                        "Daily numeric totals contain zero text content and are kept by default to preserve your long-term insights and milestones.",
                                        "按日累計的純數字統計不包含任何內文，預設保留以便繼續追蹤長期效率與成就。"
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    lineHeight = 17.sp
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val resetStats = alsoResetStats
                        repository.clearHistory(alsoResetStats = resetStats)
                        showClearAllConfirm = false
                        alsoResetStats = false
                        Toast.makeText(
                            context,
                            if (resetStats) {
                                AppStrings.pick(appLang, "已清空全部历史记录并清零累计统计", "Cleared all history and reset cumulative stats", "已清空全部歷史記錄並清零累計統計")
                            } else {
                                AppStrings.pick(appLang, "已清空全部历史记录（已保留纯数字统计）", "Cleared all history (numeric stats preserved)", "已清空全部歷史記錄（已保留純數字統計）")
                            },
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = Color.White
                    )
                ) {
                    Text(tr("确认清空", "Clear All", "確認清空"))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showClearAllConfirm = false
                        alsoResetStats = false
                    }
                ) {
                    Text(tr("取消", "Cancel", "取消"))
                }
            }
        )
    }

    // Repolish / Audio Retry Result Dialog
    repolishDialogState?.let { dialogState ->
        RepolishResultDialog(
            state = dialogState,
            allStyles = styles,
            isLoading = activeBusyRecordId == dialogState.sourceRecord.id,
            onTryStyle = { pickedStyle ->
                triggerTextRepolish(dialogState.sourceRecord, overrideStyle = pickedStyle)
            },
            onCopyOnly = {
                copyTextToClipboard(
                    context = context,
                    label = "TypeFree Repolished",
                    text = dialogState.executionResult.finalOutput,
                    toastMessage = AppStrings.pick(appLang, "已复制新结果", "Copied new result", "已複製新結果")
                )
            },
            onSaveAndCopy = {
                val src = dialogState.sourceRecord
                val resolvedMode = resolveRecordIntent(src)
                val newRecord = HistoryRecord(
                    targetAppName = src.targetAppName.ifBlank {
                        AppStrings.pick(appLang, "历史重跑", "History Repolish", "歷史重跑")
                    },
                    targetPackage = src.targetPackage,
                    styleName = "${dialogState.styleUsed.emoji} ${dialogState.styleUsed.name}",
                    isSpeakToEdit = resolvedMode == VoiceIntentMode.SPEAK_TO_EDIT,
                    intentMode = resolvedMode,
                    translateTargetLanguage = src.translateTargetLanguage,
                    originalSelectedText = src.originalSelectedText,
                    rawTranscript = if (dialogState.fromAudioRetry) {
                        dialogState.executionResult.rawTranscript
                    } else {
                        src.rawTranscript.ifBlank { src.polishedText }
                    },
                    polishedText = dialogState.executionResult.finalOutput,
                    durationMs = src.durationMs,
                    latencyMs = dialogState.executionResult.latencyMs,
                    modeLabel = if (dialogState.fromAudioRetry) {
                        AppStrings.pick(
                            appLang,
                            "录音重跑 (${dialogState.executionResult.modeSummary})",
                            "Audio Retry (${dialogState.executionResult.modeSummary})",
                            "錄音重跑 (${dialogState.executionResult.modeSummary})"
                        )
                    } else {
                        AppStrings.pick(
                            appLang,
                            "重润色 (${dialogState.executionResult.modeSummary})",
                            "Repolished (${dialogState.executionResult.modeSummary})",
                            "重潤飾 (${dialogState.executionResult.modeSummary})"
                        )
                    },
                    audioFilePath = src.audioFilePath,
                    voiceTrace = VoiceLatencyTraceAssembler.assemble(
                        micInitMs = 0L,
                        recordingDurationMs = if (dialogState.fromAudioRetry) src.durationMs else 0L,
                        firstPartialMs = 0L,
                        hadStreamingSession = false,
                        stopIoMs = 0L,
                        commitMs = 0L,
                        measuredPostReleaseMs = dialogState.executionResult.latencyMs,
                        exec = dialogState.executionResult,
                        hostAppName = src.targetAppName,
                        commitTargetLabelZh = ""
                    )
                )
                repository.addHistory(newRecord)
                copyTextToClipboard(
                    context = context,
                    label = "TypeFree Repolished",
                    text = dialogState.executionResult.finalOutput,
                    toastMessage = AppStrings.pick(appLang, "已保存至最新历史并复制", "Saved to history and copied", "已儲存至最新歷史並複製")
                )
                repolishDialogState = null
            },
            onDismiss = { repolishDialogState = null }
        )
    }
}

@Composable
private fun HistoryCategoryPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(9.dp),
        color = if (selected) MaterialTheme.colorScheme.surface else Color.Transparent,
        shadowElevation = if (selected) 1.dp else 0.dp
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
        )
    }
}

@Composable
private fun EmptyHistoryIllustration(isNeverRetention: Boolean) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 34.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = TypeFreeIcons.VoiceWaveform,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(30.dp)
                )
            }

            Text(
                text = if (isNeverRetention) {
                    tr("当前已开启「从不保留」隐私无痕模式", "\"Never Save\" Privacy Mode Is Active", "目前已開啟「從不保留」隱私無痕模式")
                } else {
                    tr("暂无语音与改写记录", "No Voice or Edit History Yet", "暫無語音與改寫記錄")
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Text(
                text = if (isNeverRetention) {
                    tr(
                        "在此模式下，所有语音口述、改写与翻译上屏后立即销毁，不在本机写入任何历史记录。如需回溯历史，可在上方切换保留周期。",
                        "All dictations, edits, and translations are discarded immediately after insertion and never written to disk. Change History Retention above to keep records.",
                        "在此模式下，所有語音口述、改寫與翻譯上屏後立即銷毀，不在本機寫入任何歷史記錄。如需回溯歷史，可在上方切換保留週期。"
                    )
                } else {
                    tr(
                        "在任意应用通过灵言键盘、悬浮语音胶囊或首页实测沙盒完成口述净写、帮我写、语音编辑与实时快译后，将按日期在此展示时间线。",
                        "Dictations, Help Write drafts, voice edits, and live translations from the keyboard or Playground will appear here by date.",
                        "在任意應用透過靈言鍵盤、懸浮語音膠囊或首頁實測沙盒完成口述淨寫、幫我寫、語音編輯與即時快譯後，將依日期在此顯示時間軸。"
                    )
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                lineHeight = 19.sp
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HistoryTimelineCard(
    record: HistoryRecord,
    styles: List<ToneStyle>,
    fallbackTranslateLang: String,
    busyLabel: String?,
    isAnyBusy: Boolean,
    onCopyPolished: () -> Unit,
    onCopyRaw: () -> Unit,
    onRepolish: () -> Unit,
    onRetryAudio: (File) -> Unit,
    onDelete: () -> Unit
) {
    val appLang = LocalAppLanguage.current
    var rawExpanded by rememberSaveable(record.id) { mutableStateOf(false) }

    val resolvedIntent = remember(record.intentMode, record.isSpeakToEdit) {
        resolveRecordIntent(record)
    }
    val modeBadgeText = remember(resolvedIntent, record.translateTargetLanguage, fallbackTranslateLang, appLang) {
        when (resolvedIntent) {
            VoiceIntentMode.DICTATE -> AppStrings.pick(appLang, "口述净写", "Dictate", "口述淨寫")
            VoiceIntentMode.HELP_ME_WRITE -> AppStrings.pick(appLang, "✍️ 帮我写", "✍️ Help Write", "✍️ 幫我寫")
            VoiceIntentMode.SPEAK_TO_EDIT -> AppStrings.pick(appLang, "✨ 语音编辑", "✨ Voice Edit", "✨ 語音編輯")
            VoiceIntentMode.TRANSLATE -> {
                val lang = record.translateTargetLanguage.ifBlank { fallbackTranslateLang }
                AppStrings.pick(appLang, "🌐 快译 → $lang", "🌐 Translate → $lang", "🌐 快譯 → $lang")
            }
        }
    }
    val formattedStyleLabel = remember(record.styleName, styles, appLang) {
        resolveStyleDisplayLabel(record.styleName, styles, appLang)
    }
    val timeOnlyText = remember(record.timestamp) {
        formatClockTime(record.timestamp)
    }
    val metricsText = remember(record.durationMs, record.latencyMs, record.modeLabel, appLang) {
        formatMetricsLabel(record.durationMs, record.latencyMs, record.modeLabel, appLang)
    }
    val activeVoiceTrace = remember(record.voiceTrace) {
        record.voiceTrace?.takeIf { it.stages.isNotEmpty() }
    }
    val localWavFile = remember(record.audioFilePath) {
        record.audioFilePath
            .takeIf { it.isNotBlank() }
            ?.let { File(it) }
            ?.takeIf { it.exists() && it.length() > 44L }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.48f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Top Row: Time + Host App + Mode Badge + Style Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                FlowRow(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = timeOnlyText,
                        style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.CenterVertically)
                    )

                    // Host App Pill
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            text = localizeHistoryTargetAppName(record.targetAppName, appLang).ifBlank { "TypeFree" },
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.5.dp)
                        )
                    }

                    // Intent Mode Corner Badge
                    val isSpecialMode = resolvedIntent != VoiceIntentMode.DICTATE
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (isSpecialMode) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
                        }
                    ) {
                        Text(
                            text = modeBadgeText,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (isSpecialMode) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.5.dp)
                        )
                    }

                    if (formattedStyleLabel.isNotBlank()) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
                        ) {
                            Text(
                                text = formattedStyleLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.5.dp)
                            )
                        }
                    }

                    if (activeVoiceTrace != null && record.modeLabel.isNotBlank()) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Color.Transparent,
                            border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                        ) {
                            Text(
                                text = localizeModeLabel(record.modeLabel, appLang),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 6.5.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = tr("删除记录", "Delete record", "刪除記錄"),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            // Final Polished Text (Clean TypeFree Minimalist Typography)
            SelectionContainer {
                Text(
                    text = record.polishedText,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    lineHeight = 23.sp
                )
            }

            // Expandable Original Transcript / Full-Chain Voice Latency Waterfall (Plan A)
            val hasRawOrSelected = record.rawTranscript.isNotBlank() || record.originalSelectedText.isNotBlank()
            if (activeVoiceTrace != null) {
                VoiceLatencyWaterfallSubCard(
                    record = record,
                    trace = activeVoiceTrace,
                    resolvedIntent = resolvedIntent,
                    timeOnlyText = timeOnlyText,
                    expanded = rawExpanded,
                    onToggleExpand = { rawExpanded = !rawExpanded },
                    onCopyRaw = onCopyRaw
                )
            } else if (hasRawOrSelected || metricsText.isNotBlank()) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { rawExpanded = !rawExpanded },
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 11.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = when (resolvedIntent) {
                                    VoiceIntentMode.SPEAK_TO_EDIT -> tr("选区原文 & 语音改写指令", "Selected Text & Voice Prompt", "選區原文 & 語音改寫指令")
                                    VoiceIntentMode.HELP_ME_WRITE -> tr("语音写作指令原稿", "Original Writing Prompt", "語音寫作指令原稿")
                                    VoiceIntentMode.TRANSLATE -> tr("翻译前原始语音识别", "Original Speech Transcript", "翻譯前原始語音識別")
                                    VoiceIntentMode.DICTATE -> tr("原始转写初稿对比", "Raw Transcript Comparison", "原始轉寫初稿對比")
                                },
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )

                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.End
                            ) {
                                if (metricsText.isNotBlank()) {
                                    Text(
                                        text = metricsText,
                                        modifier = Modifier.weight(1f, fill = false),
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 10.sp,
                                            lineHeight = 13.sp
                                        ),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                                        textAlign = TextAlign.End,
                                        maxLines = if (rawExpanded) 2 else 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                }
                                Icon(
                                    imageVector = if (rawExpanded) {
                                        Icons.Default.KeyboardArrowUp
                                    } else {
                                        Icons.Default.KeyboardArrowDown
                                    },
                                    contentDescription = if (rawExpanded) tr("收起", "Collapse", "收起") else tr("展开", "Expand", "展開"),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }

                        SmoothDisclosure(expanded = rawExpanded) {
                            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                if (record.originalSelectedText.isNotBlank()) {
                                    Text(
                                        text = tr("选区原文：${record.originalSelectedText}", "Selected text: ${record.originalSelectedText}", "選區原文：${record.originalSelectedText}"),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (record.rawTranscript.isNotBlank()) {
                                    Text(
                                        text = if (resolvedIntent == VoiceIntentMode.SPEAK_TO_EDIT ||
                                            resolvedIntent == VoiceIntentMode.HELP_ME_WRITE
                                        ) {
                                            tr("口述指令：${record.rawTranscript}", "Spoken instruction: ${record.rawTranscript}", "口述指令：${record.rawTranscript}")
                                        } else {
                                            tr("原始转写：${record.rawTranscript}", "Raw transcript: ${record.rawTranscript}", "原始轉寫：${record.rawTranscript}")
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (hasRawOrSelected) {
                                    TextButton(
                                        onClick = onCopyRaw,
                                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                                    ) {
                                        Text(tr("复制原稿文本", "Copy Raw Text", "複製原稿文字"), style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }

                        if (!rawExpanded && record.rawTranscript.isNotBlank()) {
                            Text(
                                text = record.rawTranscript,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.82f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f))

            // Action Row: Copy, Repolish with Current API, Retry Local Audio (if exists)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilledTonalButton(
                    onClick = onCopyPolished,
                    shape = RoundedCornerShape(9.dp),
                    contentPadding = PaddingValues(horizontal = 11.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = TypeFreeIcons.Copy,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(tr("复制", "Copy", "複製"), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                }

                OutlinedButton(
                    onClick = onRepolish,
                    enabled = !isAnyBusy,
                    shape = RoundedCornerShape(9.dp),
                    contentPadding = PaddingValues(horizontal = 11.dp, vertical = 6.dp)
                ) {
                    if (busyLabel != null && localWavFile == null) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(13.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (busyLabel != null && localWavFile == null) busyLabel else tr("重新润色", "Repolish", "重新潤飾"),
                        style = MaterialTheme.typography.labelMedium
                    )
                }

                if (localWavFile != null) {
                    OutlinedButton(
                        onClick = { onRetryAudio(localWavFile) },
                        enabled = !isAnyBusy,
                        shape = RoundedCornerShape(9.dp),
                        contentPadding = PaddingValues(horizontal = 11.dp, vertical = 6.dp)
                    ) {
                        if (busyLabel != null) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(13.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                imageVector = TypeFreeIcons.Mic,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = busyLabel ?: tr("本地录音重试", "Retry Audio", "本機錄音重試"),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun RepolishResultDialog(
    state: RepolishDialogState,
    allStyles: List<ToneStyle>,
    isLoading: Boolean,
    onTryStyle: (ToneStyle) -> Unit,
    onCopyOnly: () -> Unit,
    onSaveAndCopy: () -> Unit,
    onDismiss: () -> Unit
) {
    val appLang = LocalAppLanguage.current
    val dispStyleUsed = localizedHistoryStyleName(state.styleUsed, appLang)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (state.fromAudioRetry) {
                    tr("本地录音重新识别与润色结果", "Local Audio Retry Result", "本機錄音重新識別與潤飾結果")
                } else {
                    tr("当前 API 重新润色结果", "Repolish Result", "目前 API 重新潤飾結果")
                }
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = tr(
                        "应用风格：${state.styleUsed.emoji} $dispStyleUsed · ${state.executionResult.modeSummary} · ${state.executionResult.latencyMs}ms",
                        "Style: ${state.styleUsed.emoji} $dispStyleUsed · ${state.executionResult.modeSummary} · ${state.executionResult.latencyMs}ms",
                        "套用風格：${state.styleUsed.emoji} $dispStyleUsed · ${state.executionResult.modeSummary} · ${state.executionResult.latencyMs}ms"
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                ) {
                    SelectionContainer {
                        Text(
                            text = state.executionResult.finalOutput,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp)
                        )
                    }
                }

                if (state.fromAudioRetry && state.executionResult.rawTranscript.isNotBlank()) {
                    Text(
                        text = tr(
                            "新识别初稿：${state.executionResult.rawTranscript}",
                            "New raw transcript: ${state.executionResult.rawTranscript}",
                            "新識別初稿：${state.executionResult.rawTranscript}"
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Text(
                    text = tr("切换其他语气风格立即对比：", "Try another tone style immediately:", "切換其他語氣風格立即對比："),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    allStyles.forEach { style ->
                        FilterChip(
                            selected = style.id == state.styleUsed.id,
                            enabled = !isLoading,
                            onClick = { onTryStyle(style) },
                            label = { Text("${style.emoji} ${localizedHistoryStyleName(style, appLang)}") }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onCopyOnly) {
                    Text(tr("仅复制", "Copy Only", "僅複製"))
                }
                Button(
                    onClick = onSaveAndCopy,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text(tr("保存并复制", "Save & Copy", "儲存並複製"))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(tr("关闭", "Close", "關閉"))
            }
        }
    )
}

private fun resolveRecordIntent(record: HistoryRecord): VoiceIntentMode {
    return if (record.isSpeakToEdit && record.intentMode == VoiceIntentMode.DICTATE) {
        VoiceIntentMode.SPEAK_TO_EDIT
    } else {
        record.intentMode
    }
}

private fun groupRecordsByDate(records: List<HistoryRecord>, lang: AppLang): List<HistoryDateGroup> {
    if (records.isEmpty()) return emptyList()
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val sameYearFmt = if (lang == AppLang.EN) {
        DateTimeFormatter.ofPattern("MMM d", Locale.US)
    } else {
        DateTimeFormatter.ofPattern("M月d日", Locale.CHINA)
    }
    val fullDateFmt = if (lang == AppLang.EN) {
        DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)
    } else {
        DateTimeFormatter.ofPattern("yyyy年M月d日", Locale.CHINA)
    }

    val map = LinkedHashMap<LocalDate, MutableList<HistoryRecord>>()
    for (record in records) {
        val date = Instant.ofEpochMilli(record.timestamp).atZone(zone).toLocalDate()
        map.getOrPut(date) { mutableListOf() }.add(record)
    }

    return map.entries.map { (date, list) ->
        val header = when {
            date == today -> AppStrings.pick(lang, "今天", "Today", "今天")
            date == today.minusDays(1) -> AppStrings.pick(lang, "昨天", "Yesterday", "昨天")
            date.year == today.year -> date.format(sameYearFmt)
            else -> date.format(fullDateFmt)
        }
        HistoryDateGroup(
            dateKey = date.toString(),
            dateHeader = header,
            records = list
        )
    }
}

private fun formatClockTime(epochMs: Long): String {
    val zone = ZoneId.systemDefault()
    val dt = Instant.ofEpochMilli(epochMs).atZone(zone)
    return dt.format(DateTimeFormatter.ofPattern("HH:mm", Locale.US))
}

private fun epochMsToDateKey(epochMs: Long): Int {
    val date = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDate()
    return date.year * 10000 + date.monthValue * 100 + date.dayOfMonth
}

private fun formatDateKeyLabel(dateKey: Int, lang: AppLang): String {
    val year = dateKey / 10000
    val month = (dateKey % 10000) / 100
    val day = dateKey % 100
    if (month !in 1..12 || day !in 1..31) return dateKey.toString()
    val today = LocalDate.now(ZoneId.systemDefault())
    val parsed = runCatching { LocalDate.of(year, month, day) }.getOrNull()
    if (lang == AppLang.EN && parsed != null) {
        return if (year == today.year) {
            parsed.format(DateTimeFormatter.ofPattern("MMM d", Locale.US))
        } else {
            parsed.format(DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US))
        }
    }
    return if (year == today.year) {
        AppStrings.pick(lang, "${month}月${day}日", "$month/$day", "${month}月${day}日")
    } else {
        AppStrings.pick(lang, "${year}年${month}月${day}日", "$year-$month-$day", "${year}年${month}月${day}日")
    }
}

private fun copyTextToClipboard(
    context: Context,
    label: String,
    text: String,
    toastMessage: String
) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(context, toastMessage, Toast.LENGTH_SHORT).show()
}

private fun resolveStyleDisplayLabel(styleName: String, styles: List<ToneStyle>, lang: AppLang): String {
    val clean = styleName.trim()
    if (clean.isEmpty()) return ""
    val matched = styles.firstOrNull { it.name == clean || "${it.emoji} ${it.name}" == clean }
    if (matched != null) {
        return "${matched.emoji} ${localizedHistoryStyleName(matched, lang)}"
    }
    if (lang == AppLang.ZH_CN) return clean
    return clean
        .replace("通用灵动", AppStrings.pick(lang, "通用灵动", "Smart Default", "通用靈動"))
        .replace("社交聊天", AppStrings.pick(lang, "社交聊天", "Casual Chat", "社交聊天"))
        .replace("职场干练", AppStrings.pick(lang, "职场干练", "Work & Slack", "職場幹練"))
        .replace("正式邮件", AppStrings.pick(lang, "正式邮件", "Formal Email", "正式郵件"))
        .replace("编程与 Prompt", AppStrings.pick(lang, "编程与 Prompt", "Code & Prompt", "程式與 Prompt"))
        .replace("直出地道英文", AppStrings.pick(lang, "直出地道英文", "Native English Output", "直出地道英文"))
}

private fun localizeModeLabel(modeLabel: String, lang: AppLang): String {
    if (lang == AppLang.ZH_CN) return modeLabel
    return modeLabel
        .replace("胶囊 · ", AppStrings.pick(lang, "胶囊 · ", "Bubble · ", "膠囊 · "))
        .replace("录音重跑 (", AppStrings.pick(lang, "录音重跑 (", "Audio Retry (", "錄音重跑 ("))
        .replace("重润色 (", AppStrings.pick(lang, "重润色 (", "Repolished (", "重潤飾 ("))
        .replace("多模态 (", AppStrings.pick(lang, "多模态 (", "Multimodal (", "多模態 ("))
        .replace("(云端回退)", AppStrings.pick(lang, "(云端回退)", "(Cloud Fallback)", "(雲端回退)"))
        .replace("本机离线", AppStrings.pick(lang, "本机离线", "Local Offline", "本機離線"))
        .replace("本机 ", AppStrings.pick(lang, "本机 ", "Local ", "本機 "))
        .replace("Zipformer 双语", AppStrings.pick(lang, "Zipformer 双语", "Zipformer Bilingual", "Zipformer 雙語"))
        .replace("Paraformer 流式", AppStrings.pick(lang, "Paraformer 流式", "Paraformer Stream", "Paraformer 串流"))
        .replace("豆包流式", AppStrings.pick(lang, "豆包流式", "Doubao Stream", "豆包串流"))
        .replace("百炼流式", AppStrings.pick(lang, "百炼流式", "Bailian Stream", "百煉串流"))
        .replace("硅基 SenseVoice", AppStrings.pick(lang, "硅基 SenseVoice", "SiliconFlow SenseVoice", "矽基 SenseVoice"))
}

private fun formatMetricsLabel(durationMs: Long, latencyMs: Long, modeLabel: String, lang: AppLang): String {
    val parts = mutableListOf<String>()
    if (durationMs > 0L) {
        parts += String.format(Locale.US, "%.1fs", durationMs / 1000.0)
    }
    if (latencyMs > 0L) {
        parts += "${latencyMs}ms"
    }
    if (modeLabel.isNotBlank()) {
        parts += localizeModeLabel(modeLabel, lang)
    }
    return parts.joinToString(" · ")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VoiceLatencyWaterfallSubCard(
    record: HistoryRecord,
    trace: VoiceLatencyTrace,
    resolvedIntent: VoiceIntentMode,
    timeOnlyText: String,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onCopyRaw: () -> Unit
) {
    val context = LocalContext.current
    val appLang = LocalAppLanguage.current
    val isDark = LocalTypeFreeKeyboardPalette.current.isDark
    val hasRawOrSelected = record.rawTranscript.isNotBlank() || record.originalSelectedText.isNotBlank()

    var selectedStageIdx by remember(record.id) { mutableStateOf<Int?>(null) }
    var barUnfurled by remember(record.id) { mutableStateOf(expanded) }
    val cometProgress = remember(record.id) { Animatable(-0.35f) }

    LaunchedEffect(expanded) {
        if (expanded) {
            barUnfurled = true
            cometProgress.snapTo(-0.35f)
            cometProgress.animateTo(
                targetValue = 1.35f,
                animationSpec = tween(durationMillis = 860, easing = FastOutSlowInEasing)
            )
        } else {
            barUnfurled = false
            selectedStageIdx = null
        }
    }

    val outerShape = remember { ContinuousRoundedCornerShape(14.dp) }
    val outerContainerColor = if (isDark) {
        Color(0xFF121318)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.52f)
    }
    val outerBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = if (isDark) 0.55f else 0.48f)

    val recMs = if (trace.recordingDurationMs > 0L) trace.recordingDurationMs else record.durationMs
    val dotColor = when {
        trace.isSlowWarning -> Color(0xFFF59E0B)
        isDark -> Color(0xFF34D399)
        else -> Color(0xFF059669)
    }
    val monoPillStyle = MaterialTheme.typography.labelSmall.copy(
        fontFamily = FontFamily.Monospace,
        fontSize = 10.5.sp,
        lineHeight = 13.sp
    )
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = spring(dampingRatio = 0.68f, stiffness = Spring.StiffnessMedium),
        label = "traceChevronRotation"
    )
    val headerInteraction = remember { MutableInteractionSource() }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = outerShape,
        color = outerContainerColor,
        border = BorderStroke(0.8.dp, outerBorderColor)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Header Row (Collapsed by default; click to smoothly expand Plan A waterfall)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .springPressScale(pressedScale = 0.985f, interactionSource = headerInteraction)
                    .clickable(
                        interactionSource = headerInteraction,
                        indication = null,
                        onClick = onToggleExpand
                    )
                    .padding(horizontal = 11.dp, vertical = 8.5.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (resolvedIntent == VoiceIntentMode.SPEAK_TO_EDIT) {
                            tr("选区 & 耗时拆解", "Selection & Latency", "選區 & 耗時拆解")
                        } else {
                            tr("原稿 & 耗时拆解", "Raw & Latency", "原稿 & 耗時拆解")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false
                    )

                    if (!expanded && record.rawTranscript.isNotBlank()) {
                        Text(
                            text = record.rawTranscript,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (recMs > 0L) {
                        Text(
                            text = String.format(Locale.US, "%.1fs", recMs / 1000.0),
                            style = monoPillStyle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.58f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 7.5.dp, vertical = 2.5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(5.5.dp)
                                    .clip(CircleShape)
                                    .background(dotColor)
                            )
                            Spacer(modifier = Modifier.width(4.5.dp))
                            Text(
                                text = tr("松手 ", "Rel ", "鬆手 "),
                                style = monoPillStyle,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            RollingDigitCounter(
                                text = "${trace.postReleaseTotalMs}",
                                style = monoPillStyle,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "ms",
                                style = monoPillStyle,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = if (expanded) tr("收起", "Collapse", "收起") else tr("展开", "Expand", "展開"),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .size(16.dp)
                            .graphicsLayer { rotationZ = chevronRotation }
                    )
                }
            }

            // Expandable Body
            SmoothDisclosure(expanded = expanded) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    HorizontalDivider(
                        thickness = 0.6.dp,
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                    )

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 11.dp, vertical = 9.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // 1. Caption + Slender 10.5dp Obsidian × Emerald Tonal Waterfall Bar
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = tr(
                                        "松手 → 上屏关键路径（点按分段查看详情）",
                                        "Release → Commit path (tap segment to inspect)",
                                        "鬆手 → 上屏關鍵路徑（點按分段查看詳情）"
                                    ),
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )

                                if (selectedStageIdx != null) {
                                    Text(
                                        text = tr("返回总览 ↺", "Overview ↺", "返回總覽 ↺"),
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp),
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .clickable { selectedStageIdx = null }
                                            .padding(horizontal = 4.dp, vertical = 1.dp)
                                    )
                                }
                            }

                            val trackBg = if (isDark) Color(0xFF23252E) else Color(0xFFE4E5EC)
                            val totalPostMs = trace.postReleaseTotalMs.coerceAtLeast(1L).toFloat()
                            val stageCount = trace.stages.size.coerceAtLeast(1)

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp)
                                    .height(10.5.dp)
                                    .clip(CircleShape)
                                    .background(trackBg)
                                    .padding(1.5.dp)
                                    .drawWithContent {
                                        drawContent()
                                        val p = cometProgress.value
                                        if (p > -0.3f && p < 1.3f && size.width > 0f) {
                                            val bandHalf = size.width * 0.22f
                                            val center = size.width * p
                                            val fadeAlpha = (1f - ((p - 0.85f).coerceAtLeast(0f) / 0.45f))
                                                .coerceIn(0f, 1f) * (if (isDark) 0.52f else 0.65f)
                                            drawRect(
                                                brush = Brush.horizontalGradient(
                                                    colors = listOf(
                                                        Color.Transparent,
                                                        Color.White.copy(alpha = fadeAlpha),
                                                        Color.Transparent
                                                    ),
                                                    startX = center - bandHalf,
                                                    endX = center + bandHalf
                                                )
                                            )
                                        }
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxSize(),
                                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    trace.stages.forEachIndexed { idx, stage ->
                                        val rawShare = (stage.durationMs.toFloat() / totalPostMs).coerceIn(0.055f, 1f)
                                        val targetWeight = if (barUnfurled) rawShare else (1f / stageCount)
                                        val animatedWeight by animateFloatAsState(
                                            targetValue = targetWeight,
                                            animationSpec = spring(dampingRatio = 0.76f, stiffness = 360f),
                                            label = "waterfallWeight_$idx"
                                        )
                                        val isSelected = selectedStageIdx == idx
                                        val targetAlpha = if (selectedStageIdx == null || isSelected) 1f else 0.28f
                                        val animatedAlpha by animateFloatAsState(
                                            targetValue = targetAlpha,
                                            animationSpec = spring(stiffness = Spring.StiffnessMedium),
                                            label = "waterfallAlpha_$idx"
                                        )
                                        val segScaleY by animateFloatAsState(
                                            targetValue = if (isSelected) 1.24f else 1f,
                                            animationSpec = spring(
                                                dampingRatio = 0.62f,
                                                stiffness = Spring.StiffnessMedium
                                            ),
                                            label = "waterfallScaleY_$idx"
                                        )
                                        val segShape = remember(idx, stageCount) {
                                            RoundedCornerShape(
                                                topStart = if (idx == 0) 99.dp else 2.5.dp,
                                                bottomStart = if (idx == 0) 99.dp else 2.5.dp,
                                                topEnd = if (idx == stageCount - 1) 99.dp else 2.5.dp,
                                                bottomEnd = if (idx == stageCount - 1) 99.dp else 2.5.dp
                                            )
                                        }
                                        val segColor = resolvePlanAToneColor(stage.toneStep, isDark)
                                        val segInteraction = remember { MutableInteractionSource() }

                                        Box(
                                            modifier = Modifier
                                                .weight(animatedWeight.coerceAtLeast(0.04f))
                                                .fillMaxHeight()
                                                .graphicsLayer {
                                                    scaleY = segScaleY
                                                    alpha = animatedAlpha
                                                }
                                                .clip(segShape)
                                                .background(segColor)
                                                .drawWithContent {
                                                    drawContent()
                                                    drawLine(
                                                        color = Color.White.copy(alpha = 0.22f),
                                                        start = Offset(0f, 0.5f),
                                                        end = Offset(size.width, 0.5f),
                                                        strokeWidth = 1f
                                                    )
                                                }
                                                .clickable(
                                                    interactionSource = segInteraction,
                                                    indication = null
                                                ) {
                                                    selectedStageIdx = if (selectedStageIdx == idx) null else idx
                                                }
                                        )
                                    }
                                }
                            }
                        }

                        // 2. SINGLE Unified Focus Card (Overview when null; Stage Detail when tapped)
                        val focusCardShape = remember { ContinuousRoundedCornerShape(11.dp) }
                        Surface(
                            shape = focusCardShape,
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            AnimatedContent(
                                targetState = selectedStageIdx,
                                transitionSpec = {
                                    val enter = fadeIn(tween(180)) + slideInVertically(
                                        animationSpec = spring(
                                            dampingRatio = 0.82f,
                                            stiffness = Spring.StiffnessMedium
                                        )
                                    ) { height -> height / 6 }
                                    val exit = fadeOut(tween(120)) + slideOutVertically(
                                        animationSpec = tween(120)
                                    ) { height -> -height / 8 }
                                    (enter togetherWith exit).using(SizeTransform(clip = false))
                                },
                                label = "singleFocusLatencyCard"
                            ) { focusedIdx ->
                                val focusedStage = focusedIdx?.let { trace.stages.getOrNull(it) }
                                if (focusedStage == null) {
                                    val fullSpanMs = trace.micInitMs + recMs + trace.postReleaseTotalMs
                                    val badgeText = AppStrings.pick(
                                        appLang,
                                        trace.summaryBadgeZh,
                                        trace.summaryBadgeEn,
                                        trace.summaryBadgeTw
                                    )
                                    val wsLabel = AppStrings.pick(
                                        appLang,
                                        trace.wsSetupZh,
                                        trace.wsSetupEn,
                                        trace.wsSetupTw
                                    )
                                    val streamHint = AppStrings.pick(
                                        appLang,
                                        trace.streamHintZh,
                                        trace.streamHintEn,
                                        trace.streamHintTw
                                    )

                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 10.dp, vertical = 8.5.dp),
                                        verticalArrangement = Arrangement.spacedBy(5.5.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = tr("全流程耗时总览", "Full-Chain Overview", "全流程耗時總覽"),
                                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.5.sp),
                                                fontWeight = FontWeight.SemiBold,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                maxLines = 1,
                                                softWrap = false
                                            )

                                            if (badgeText.isNotBlank()) {
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Surface(
                                                    shape = RoundedCornerShape(5.dp),
                                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
                                                    border = BorderStroke(
                                                        0.6.dp,
                                                        MaterialTheme.colorScheme.outline.copy(alpha = 0.42f)
                                                    ),
                                                    modifier = Modifier.weight(1f, fill = false)
                                                ) {
                                                    Text(
                                                        text = badgeText,
                                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.5.dp)
                                                    )
                                                }
                                            }
                                        }

                                        val primaryColor = MaterialTheme.colorScheme.onSurface
                                        val mutedColor = MaterialTheme.colorScheme.onSurfaceVariant
                                        Text(
                                            text = buildAnnotatedString {
                                                if (recMs > 0L || trace.micInitMs > 0L) {
                                                    withStyle(SpanStyle(color = mutedColor)) {
                                                        append(tr("① 启动 ", "① Init ", "① 啟動 "))
                                                    }
                                                    withStyle(SpanStyle(color = primaryColor, fontWeight = FontWeight.SemiBold)) {
                                                        append("${trace.micInitMs}ms")
                                                    }
                                                    if (wsLabel.isNotBlank()) {
                                                        withStyle(SpanStyle(color = mutedColor)) {
                                                            append(" ($wsLabel)  /  ")
                                                        }
                                                    } else {
                                                        withStyle(SpanStyle(color = mutedColor)) { append("  /  ") }
                                                    }

                                                    withStyle(SpanStyle(color = mutedColor)) {
                                                        append(tr("② 录音 ", "② Rec ", "② 錄音 "))
                                                    }
                                                    withStyle(SpanStyle(color = primaryColor, fontWeight = FontWeight.SemiBold)) {
                                                        append(String.format(Locale.US, "%.2fs", recMs / 1000.0))
                                                    }
                                                    if (streamHint.isNotBlank()) {
                                                        withStyle(SpanStyle(color = mutedColor)) {
                                                            append(" ($streamHint)  /  ")
                                                        }
                                                    } else {
                                                        withStyle(SpanStyle(color = mutedColor)) { append("  /  ") }
                                                    }

                                                    withStyle(SpanStyle(color = mutedColor)) {
                                                        append(tr("③ 松手等待 ", "③ Post-Release ", "③ 鬆手等待 "))
                                                    }
                                                    withStyle(SpanStyle(color = primaryColor, fontWeight = FontWeight.SemiBold)) {
                                                        append("${trace.postReleaseTotalMs}ms")
                                                    }
                                                    withStyle(SpanStyle(color = mutedColor)) {
                                                        append(
                                                            tr(
                                                                "  ·  按下→上屏 ${String.format(Locale.US, "%.2fs", fullSpanMs / 1000.0)}",
                                                                "  ·  Total ${String.format(Locale.US, "%.2fs", fullSpanMs / 1000.0)}",
                                                                "  ·  按下→上屏 ${String.format(Locale.US, "%.2fs", fullSpanMs / 1000.0)}"
                                                            )
                                                        )
                                                    }
                                                } else {
                                                    withStyle(SpanStyle(color = mutedColor)) {
                                                        append(tr("文本直调耗时 ", "Direct Text Pass ", "文字直調耗時 "))
                                                    }
                                                    withStyle(SpanStyle(color = primaryColor, fontWeight = FontWeight.SemiBold)) {
                                                        append("${trace.postReleaseTotalMs}ms")
                                                    }
                                                    if (streamHint.isNotBlank()) {
                                                        withStyle(SpanStyle(color = mutedColor)) {
                                                            append(" ($streamHint)")
                                                        }
                                                    }
                                                }
                                            },
                                            style = monoPillStyle.copy(lineHeight = 15.sp)
                                        )

                                        HorizontalDivider(
                                            thickness = 0.5.dp,
                                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                                        )

                                        // Compact Tonal Dot Legend (tappable as easy finger targets for each stage)
                                        FlowRow(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            trace.stages.forEachIndexed { idx, st ->
                                                val stLabel = AppStrings.pick(appLang, st.labelZh, st.labelEn, st.labelTw)
                                                val stColor = resolvePlanAToneColor(st.toneStep, isDark)
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                                    modifier = Modifier
                                                        .clip(RoundedCornerShape(4.dp))
                                                        .clickable { selectedStageIdx = idx }
                                                        .padding(vertical = 1.dp)
                                                ) {
                                                    Box(
                                                        modifier = Modifier
                                                            .size(6.5.dp)
                                                            .clip(CircleShape)
                                                            .background(stColor)
                                                    )
                                                    Text(
                                                        text = "$stLabel ${st.durationMs}ms",
                                                        style = monoPillStyle.copy(fontSize = 10.sp),
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                            }
                                        }
                                    }
                                } else {
                                    val stLabel = AppStrings.pick(
                                        appLang,
                                        focusedStage.labelZh,
                                        focusedStage.labelEn,
                                        focusedStage.labelTw
                                    )
                                    val stDetail = AppStrings.pick(
                                        appLang,
                                        focusedStage.detailZh,
                                        focusedStage.detailEn,
                                        focusedStage.detailTw
                                    )
                                    val pct = ((focusedStage.durationMs * 100.0) /
                                        trace.postReleaseTotalMs.coerceAtLeast(1L).toDouble())
                                        .roundToInt()
                                        .coerceIn(1, 100)
                                    val stColor = resolvePlanAToneColor(focusedStage.toneStep, isDark)

                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { selectedStageIdx = null }
                                            .padding(horizontal = 10.dp, vertical = 8.5.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.5.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(
                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(8.5.dp)
                                                        .clip(CircleShape)
                                                        .background(stColor)
                                                )
                                                Text(
                                                    text = stLabel,
                                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.5.sp),
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = MaterialTheme.colorScheme.onSurface,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }

                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                RollingDigitCounter(
                                                    text = "${focusedStage.durationMs}",
                                                    style = monoPillStyle.copy(fontSize = 11.sp),
                                                    color = MaterialTheme.colorScheme.onSurface,
                                                    fontWeight = FontWeight.Bold
                                                )
                                                Text(
                                                    text = "ms",
                                                    style = monoPillStyle.copy(fontSize = 11.sp),
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.onSurface
                                                )
                                                Text(
                                                    text = tr(
                                                        "（占松手等待 $pct%）",
                                                        " ($pct% of wait)",
                                                        "（佔鬆手等待 $pct%）"
                                                    ),
                                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp),
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }

                                        if (stDetail.isNotBlank()) {
                                            Text(
                                                text = stDetail,
                                                style = MaterialTheme.typography.bodySmall.copy(
                                                    fontSize = 11.5.sp,
                                                    lineHeight = 16.sp
                                                ),
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // 3. Raw Transcript / Selected Text + Copy Raw / Copy Trace Log Actions
                        if (record.originalSelectedText.isNotBlank()) {
                            SelectionContainer {
                                Text(
                                    text = tr(
                                        "选区原文：${record.originalSelectedText}",
                                        "Selected text: ${record.originalSelectedText}",
                                        "選區原文：${record.originalSelectedText}"
                                    ),
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp, lineHeight = 16.sp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        if (record.rawTranscript.isNotBlank()) {
                            SelectionContainer {
                                Text(
                                    text = if (resolvedIntent == VoiceIntentMode.SPEAK_TO_EDIT ||
                                        resolvedIntent == VoiceIntentMode.HELP_ME_WRITE
                                    ) {
                                        tr(
                                            "口述指令：${record.rawTranscript}",
                                            "Spoken instruction: ${record.rawTranscript}",
                                            "口述指令：${record.rawTranscript}"
                                        )
                                    } else {
                                        tr(
                                            "原始转写：${record.rawTranscript}",
                                            "Raw transcript: ${record.rawTranscript}",
                                            "原始轉寫：${record.rawTranscript}"
                                        )
                                    },
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp, lineHeight = 16.sp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (hasRawOrSelected) {
                                TextButton(
                                    onClick = onCopyRaw,
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                    modifier = Modifier.height(26.dp)
                                ) {
                                    Text(
                                        text = tr("复制原稿文本", "Copy Raw Text", "複製原稿文字"),
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            } else {
                                Spacer(modifier = Modifier.width(1.dp))
                            }

                            val copyTraceInteraction = remember { MutableInteractionSource() }
                            Surface(
                                shape = RoundedCornerShape(7.dp),
                                color = MaterialTheme.colorScheme.surface,
                                border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)),
                                modifier = Modifier
                                    .springPressScale(pressedScale = 0.96f, interactionSource = copyTraceInteraction)
                                    .clip(RoundedCornerShape(7.dp))
                                    .clickable(
                                        interactionSource = copyTraceInteraction,
                                        indication = null
                                    ) {
                                        val diag = trace.formatClipboardDiagnostic(
                                            clockTime = timeOnlyText,
                                            hostApp = localizeHistoryTargetAppName(record.targetAppName, appLang).ifBlank { "TypeFree" },
                                            routeSummary = record.modeLabel.ifBlank { "Default" }
                                        )
                                        copyTextToClipboard(
                                            context = context,
                                            label = "TypeFree Voice Trace",
                                            text = diag,
                                            toastMessage = AppStrings.pick(
                                                appLang,
                                                "已复制全链路耗时日志",
                                                "Copied latency trace log",
                                                "已複製全鏈路耗時日誌"
                                            )
                                        )
                                    }
                            ) {
                                Text(
                                    text = tr("复制耗时日志", "Copy Trace Log", "複製耗時日誌"),
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp),
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(horizontal = 8.5.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
