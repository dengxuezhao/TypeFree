package com.typefree.ime.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.typefree.ime.data.AppRepository
import com.typefree.ime.data.DefaultPresets
import com.typefree.ime.data.HistoryRecord
import com.typefree.ime.data.HistoryRetention
import com.typefree.ime.data.ToneStyle
import com.typefree.ime.data.VoiceIntentMode
import com.typefree.ime.network.AiPipelineEngine
import com.typefree.ime.network.PipelineExecutionResult
import com.typefree.ime.network.PipelineStage
import com.typefree.ime.ui.components.TypeFreeIcons
import com.typefree.ime.ui.theme.TypeFreeEmerald
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HistoryScreen(
    repository: AppRepository,
    aiEngine: AiPipelineEngine
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val history by repository.history.collectAsState()
    val settings by repository.settings.collectAsState()
    val styles = settings.styles.ifEmpty { DefaultPresets.defaultStyles() }

    var searchQuery by rememberSaveable { mutableStateOf("") }
    // 0 = 全部, 1 = 口述净写 (DICTATE), 2 = 帮我写与编辑 (HELP_ME_WRITE & SPEAK_TO_EDIT), 3 = 实时快译 (TRANSLATE)
    var selectedFilter by rememberSaveable { mutableIntStateOf(0) }

    var showClearAllConfirm by remember { mutableStateOf(false) }
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

    val filteredHistory = remember(history, searchQuery, selectedFilter) {
        val q = searchQuery.trim()
        history
            .sortedByDescending { it.timestamp }
            .filter { record ->
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
                modeMatch && queryMatch
            }
    }

    val groupedHistory = remember(filteredHistory) {
        groupRecordsByDate(filteredHistory)
    }

    fun triggerTextRepolish(record: HistoryRecord, overrideStyle: ToneStyle? = null) {
        if (activeBusyRecordId != null) return
        val styleToUse = overrideStyle ?: repository.resolveActiveStyle(
            record.targetPackage.ifBlank { null }
        ).first

        activeBusyRecordId = record.id
        activeBusyLabel = "重新润色中…"
        coroutineScope.launch {
            val mode = resolveRecordIntent(record)
            val isEditMode = mode == VoiceIntentMode.SPEAK_TO_EDIT && record.originalSelectedText.isNotBlank()
            val inputSource = if (isEditMode) {
                record.originalSelectedText
            } else {
                record.rawTranscript
                    .takeIf { it.isNotBlank() && !it.startsWith("[多模态") }
                    ?: record.polishedText
            }
            val explicitInstruction = when {
                isEditMode -> record.rawTranscript
                    .takeIf { it.isNotBlank() && !it.startsWith("[多模态") }
                    ?: "请按当前语气风格优化改写这段文本"
                mode == VoiceIntentMode.TRANSLATE -> {
                    val targetLang = record.translateTargetLanguage.ifBlank { settings.activeTranslateTarget }
                    "请将内容准确地道地翻译为 $targetLang，只输出译文"
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
                        err.message ?: "重新润色失败，请检查自定义 API 设置",
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
        activeBusyLabel = "读取本地录音…"
        coroutineScope.launch {
            val result = aiEngine.processVoiceAudio(
                wavFile = wavFile,
                settings = settings,
                activeStyle = styleToUse,
                targetAppName = record.targetAppName.ifBlank { "历史录音重跑" },
                selectedText = record.originalSelectedText,
                surroundingBeforeCursor = "",
                intentMode = mode,
                translateTargetLanguage = targetLang,
                onStageChanged = { stage ->
                    activeBusyLabel = when (stage) {
                        PipelineStage.TRANSCRIBING_STT -> "STT 识别中…"
                        PipelineStage.POLISHING_LLM -> "LLM 润色中…"
                        PipelineStage.MULTIMODAL_PROCESSING -> "多模态听音中…"
                    }
                }
            )
            activeBusyRecordId = null
            activeBusyLabel = null

            result.fold(
                onSuccess = { exec ->
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
                        err.message ?: "本地录音重试失败，请检查 API 配置",
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
                    text = "历史记录",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.ExtraBold
                )

                if (history.isNotEmpty()) {
                    OutlinedButton(
                        onClick = { showClearAllConfirm = true },
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
                            text = "清空历史",
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
                                    text = "保留周期 (Keep history)",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Text(
                                text = "口述与润色历史在当前设备上保留多长时间？",
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
                                        text = settings.historyRetention.label,
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Icon(
                                        imageVector = Icons.Default.KeyboardArrowDown,
                                        contentDescription = "选择保留周期",
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
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = option.label,
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
                                                "历史保留策略已设为：${option.label}",
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
                                text = "数据仅存于本机 · 零第三方云端留存",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "你的语音口述、改写指令与本地缓存录音完全保存在当前手机沙盒中，采用零云端留存架构，任何第三方均无法访问。",
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
                        placeholder = { Text("搜索上屏文本、语音原稿、宿主应用或目标语言...") },
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
                                        contentDescription = "清除搜索"
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
                            label = "全部 (${history.size})",
                            selected = selectedFilter == 0,
                            onClick = { selectedFilter = 0 }
                        )
                        HistoryCategoryPill(
                            label = "口述净写 ($dictateCount)",
                            selected = selectedFilter == 1,
                            onClick = { selectedFilter = 1 }
                        )
                        HistoryCategoryPill(
                            label = "帮我写与编辑 ($writeAndEditCount)",
                            selected = selectedFilter == 2,
                            onClick = { selectedFilter = 2 }
                        )
                        HistoryCategoryPill(
                            label = "实时快译 ($translateCount)",
                            selected = selectedFilter == 3,
                            onClick = { selectedFilter = 3 }
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
                                text = "未找到匹配当前筛选条件的历史记录",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            TextButton(
                                onClick = {
                                    searchQuery = ""
                                    selectedFilter = 0
                                }
                            ) {
                                Text("重置搜索与分类筛选")
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
                                    toastMessage = "已复制上屏文本"
                                )
                            },
                            onCopyRaw = {
                                val rawText = record.rawTranscript.ifBlank { record.originalSelectedText }
                                copyTextToClipboard(
                                    context = context,
                                    label = "TypeFree Raw",
                                    text = rawText,
                                    toastMessage = "已复制原始转写稿"
                                )
                            },
                            onRepolish = { triggerTextRepolish(record) },
                            onRetryAudio = { wavFile -> triggerAudioRetry(record, wavFile) },
                            onDelete = {
                                repository.deleteHistoryItem(record.id)
                                Toast.makeText(context, "已删除记录", Toast.LENGTH_SHORT).show()
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

    // Clear All Confirmation Dialog
    if (showClearAllConfirm) {
        AlertDialog(
            onDismissRequest = { showClearAllConfirm = false },
            title = { Text("清空全部历史记录") },
            text = {
                Text("确定要从本机永久清空全部 ${history.size} 条历史记录吗？此操作无法撤销。")
            },
            confirmButton = {
                Button(
                    onClick = {
                        repository.clearHistory()
                        showClearAllConfirm = false
                        Toast.makeText(context, "已清空全部历史记录", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = Color.White
                    )
                ) {
                    Text("确认清空")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearAllConfirm = false }) {
                    Text("取消")
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
                    toastMessage = "已复制新结果"
                )
            },
            onSaveAndCopy = {
                val src = dialogState.sourceRecord
                val resolvedMode = resolveRecordIntent(src)
                val newRecord = HistoryRecord(
                    targetAppName = src.targetAppName.ifBlank { "历史重跑" },
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
                        "录音重跑 (${dialogState.executionResult.modeSummary})"
                    } else {
                        "重润色 (${dialogState.executionResult.modeSummary})"
                    },
                    audioFilePath = src.audioFilePath
                )
                repository.addHistory(newRecord)
                copyTextToClipboard(
                    context = context,
                    label = "TypeFree Repolished",
                    text = dialogState.executionResult.finalOutput,
                    toastMessage = "已保存至最新历史并复制"
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
                text = if (isNeverRetention) "当前已开启「从不保留」隐私无痕模式" else "暂无语音与改写记录",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Text(
                text = if (isNeverRetention) {
                    "在此模式下，所有语音口述、改写与翻译上屏后立即销毁，不在本机写入任何历史记录。如需回溯历史，可在上方切换保留周期。"
                } else {
                    "在任意应用通过灵言键盘、悬浮语音胶囊或首页实测沙盒完成口述净写、帮我写、语音编辑与实时快译后，将按日期在此展示时间线。"
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
    var rawExpanded by rememberSaveable(record.id) { mutableStateOf(false) }

    val resolvedIntent = remember(record.intentMode, record.isSpeakToEdit) {
        resolveRecordIntent(record)
    }
    val modeBadgeText = remember(resolvedIntent, record.translateTargetLanguage, fallbackTranslateLang) {
        when (resolvedIntent) {
            VoiceIntentMode.DICTATE -> "口述净写"
            VoiceIntentMode.HELP_ME_WRITE -> "✍️ 帮我写"
            VoiceIntentMode.SPEAK_TO_EDIT -> "✨ 语音编辑"
            VoiceIntentMode.TRANSLATE -> {
                val lang = record.translateTargetLanguage.ifBlank { fallbackTranslateLang }
                "🌐 快译 → $lang"
            }
        }
    }
    val formattedStyleLabel = remember(record.styleName, styles) {
        resolveStyleDisplayLabel(record.styleName, styles)
    }
    val timeOnlyText = remember(record.timestamp) {
        formatClockTime(record.timestamp)
    }
    val metricsText = remember(record.durationMs, record.latencyMs, record.modeLabel) {
        formatMetricsLabel(record.durationMs, record.latencyMs, record.modeLabel)
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
                            text = record.targetAppName.ifBlank { "TypeFree" },
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
                }

                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "删除记录",
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

            // Expandable Original Transcript / Selected Text Details
            val hasRawOrSelected = record.rawTranscript.isNotBlank() || record.originalSelectedText.isNotBlank()
            if (hasRawOrSelected || metricsText.isNotBlank()) {
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
                                    VoiceIntentMode.SPEAK_TO_EDIT -> "选区原文 & 语音改写指令"
                                    VoiceIntentMode.HELP_ME_WRITE -> "语音写作指令原稿"
                                    VoiceIntentMode.TRANSLATE -> "翻译前原始语音识别"
                                    VoiceIntentMode.DICTATE -> "原始转写初稿对比"
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
                                    contentDescription = if (rawExpanded) "收起" else "展开",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }

                        if (rawExpanded) {
                            if (record.originalSelectedText.isNotBlank()) {
                                Text(
                                    text = "选区原文：${record.originalSelectedText}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (record.rawTranscript.isNotBlank()) {
                                Text(
                                    text = if (resolvedIntent == VoiceIntentMode.SPEAK_TO_EDIT ||
                                        resolvedIntent == VoiceIntentMode.HELP_ME_WRITE
                                    ) {
                                        "口述指令：${record.rawTranscript}"
                                    } else {
                                        "原始转写：${record.rawTranscript}"
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
                                    Text("复制原稿文本", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        } else if (record.rawTranscript.isNotBlank()) {
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
                    Text("复制", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
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
                        text = if (busyLabel != null && localWavFile == null) busyLabel else "重新润色",
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
                            text = busyLabel ?: "本地录音重试",
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
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (state.fromAudioRetry) "本地录音重新识别与润色结果" else "当前 API 重新润色结果")
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "应用风格：${state.styleUsed.emoji} ${state.styleUsed.name} · ${state.executionResult.modeSummary} · ${state.executionResult.latencyMs}ms",
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
                        text = "新识别初稿：${state.executionResult.rawTranscript}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Text(
                    text = "切换其他语气风格立即对比：",
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
                            label = { Text("${style.emoji} ${style.name}") }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onCopyOnly) {
                    Text("仅复制")
                }
                Button(
                    onClick = onSaveAndCopy,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text("保存并复制")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
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

private fun groupRecordsByDate(records: List<HistoryRecord>): List<HistoryDateGroup> {
    if (records.isEmpty()) return emptyList()
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val sameYearFmt = DateTimeFormatter.ofPattern("M月d日", Locale.CHINA)
    val fullDateFmt = DateTimeFormatter.ofPattern("yyyy年M月d日", Locale.CHINA)

    val map = LinkedHashMap<LocalDate, MutableList<HistoryRecord>>()
    for (record in records) {
        val date = Instant.ofEpochMilli(record.timestamp).atZone(zone).toLocalDate()
        map.getOrPut(date) { mutableListOf() }.add(record)
    }

    return map.entries.map { (date, list) ->
        val header = when {
            date == today -> "今天"
            date == today.minusDays(1) -> "昨天"
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
    return dt.format(DateTimeFormatter.ofPattern("HH:mm", Locale.CHINA))
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

private fun resolveStyleDisplayLabel(styleName: String, styles: List<ToneStyle>): String {
    val clean = styleName.trim()
    if (clean.isEmpty()) return ""
    val matched = styles.firstOrNull { it.name == clean || "${it.emoji} ${it.name}" == clean }
    return if (matched != null) {
        "${matched.emoji} ${matched.name}"
    } else {
        clean
    }
}

private fun formatMetricsLabel(durationMs: Long, latencyMs: Long, modeLabel: String): String {
    val parts = mutableListOf<String>()
    if (durationMs > 0L) {
        parts += String.format(Locale.US, "%.1fs", durationMs / 1000.0)
    }
    if (latencyMs > 0L) {
        parts += "${latencyMs}ms"
    }
    if (modeLabel.isNotBlank()) {
        parts += modeLabel
    }
    return parts.joinToString(" · ")
}
