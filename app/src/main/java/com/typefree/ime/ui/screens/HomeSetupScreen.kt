package com.typefree.ime.ui.screens

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.typefree.ime.audio.WavAudioRecorder
import com.typefree.ime.bubble.TypeFreeBubbleController
import com.typefree.ime.data.AppRepository
import com.typefree.ime.data.AppSettings
import com.typefree.ime.data.HistoryRecord
import com.typefree.ime.data.PipelineMode
import com.typefree.ime.data.SttProtocol
import com.typefree.ime.data.UsageStatsSummary
import com.typefree.ime.data.VoiceIntentMode
import com.typefree.ime.network.AiPipelineEngine
import com.typefree.ime.network.EndpointResolver
import com.typefree.ime.network.PipelineStage
import com.typefree.ime.network.asr.StreamingAsrSession
import com.typefree.ime.ui.AppDestinations
import com.typefree.ime.ui.components.TypeFreeIcons
import com.typefree.ime.ui.screens.home.PlaygroundBottomSheet
import com.typefree.ime.ui.screens.home.PlaygroundEntryCard
import com.typefree.ime.ui.screens.home.SandboxPreviewDiff
import com.typefree.ime.ui.theme.TypeFreeAmber
import com.typefree.ime.ui.theme.TypeFreeEmerald
import com.typefree.ime.ui.theme.TypeFreeRose
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

data class ProductivityStats(
    val totalPolishedChars: Int,
    val voiceInputCount: Int,
    val savedMinutes: Double,
    val avgLatencyMs: Long
)

object SetupStatsHelper {

    /**
     * Computes cumulative dictation metrics from user history records.
     * Assumes ~40 chars/min mobile thumb typing speed vs actual speaking duration.
     */
    fun computeProductivityStats(history: List<HistoryRecord>): ProductivityStats {
        if (history.isEmpty()) {
            return ProductivityStats(
                totalPolishedChars = 0,
                voiceInputCount = 0,
                savedMinutes = 0.0,
                avgLatencyMs = 0L
            )
        }

        val totalChars = history.sumOf { it.polishedText.length }
        val count = history.size
        val savedMin = history.sumOf { record ->
            val chars = record.polishedText.length
            if (chars <= 0) {
                0.0
            } else {
                val manualTypingMinutes = chars / 40.0
                val voiceMinutes = if (record.durationMs > 0L) {
                    record.durationMs / 60_000.0
                } else {
                    chars / 160.0
                }
                (manualTypingMinutes - voiceMinutes).coerceAtLeast(0.0)
            }
        }

        val latencies = history.mapNotNull { it.latencyMs.takeIf { ms -> ms > 0L } }
        val avgLatency = if (latencies.isNotEmpty()) {
            latencies.average().toLong()
        } else {
            0L
        }

        return ProductivityStats(
            totalPolishedChars = totalChars,
            voiceInputCount = count,
            savedMinutes = savedMin,
            avgLatencyMs = avgLatency
        )
    }

    fun formatCharCount(chars: Int): String {
        return if (chars >= 10_000) {
            String.format(Locale.US, "%.1fw", chars / 10_000.0)
        } else {
            String.format(Locale.US, "%,d", chars)
        }
    }

    fun formatSavedMinutes(minutes: Double): String {
        return when {
            minutes <= 0.0 -> "0"
            minutes < 10.0 -> String.format(Locale.US, "%.1f", minutes)
            else -> String.format(Locale.US, "%.0f", minutes)
        }
    }

    /**
     * Checks whether the active PipelineMode has valid endpoint & API key (or LAN local URL).
     */
    fun isApiConfigured(settings: AppSettings): Boolean {
        return when (settings.pipelineMode) {
            PipelineMode.DUAL_STAGE -> {
                val stt = settings.sttConfig
                val sttOk = when (stt.protocol) {
                    SttProtocol.OPENAI_HTTP -> stt.baseUrl.isNotBlank() &&
                        (stt.model.isNotBlank() || EndpointResolver.isDeepSeekAsrEndpoint(stt.baseUrl)) &&
                        (stt.apiKey.isNotBlank() || AiPipelineEngine.isLocalEndpoint(stt.baseUrl))
                    SttProtocol.ALIYUN_DASHSCOPE_WS,
                    SttProtocol.VOLCENGINE_SAUC_WS,
                    SttProtocol.GEMINI_LIVE_WS -> stt.apiKey.isNotBlank()
                }

                val llm = settings.llmConfig
                val llmOk = if (!llm.enabled) {
                    true
                } else {
                    llm.baseUrl.isNotBlank() &&
                        llm.model.isNotBlank() &&
                        (llm.apiKey.isNotBlank() || AiPipelineEngine.isLocalEndpoint(llm.baseUrl))
                }
                sttOk && llmOk
            }

            PipelineMode.MULTIMODAL_DIRECT -> {
                val mm = settings.multimodalConfig
                mm.baseUrl.isNotBlank() &&
                    mm.model.isNotBlank() &&
                    (mm.apiKey.isNotBlank() || AiPipelineEngine.isLocalEndpoint(mm.baseUrl))
            }
        }
    }

    fun formatActivePipelineBadge(settings: AppSettings): String {
        return when (settings.pipelineMode) {
            PipelineMode.DUAL_STAGE -> {
                val sttShort = when (settings.sttConfig.protocol) {
                    SttProtocol.OPENAI_HTTP -> if (EndpointResolver.isDeepSeekAsrEndpoint(settings.sttConfig.baseUrl) ||
                        settings.sttConfig.presetId == "deepseek_asr"
                    ) {
                        "DeepSeek ASR"
                    } else {
                        settings.sttConfig.model.substringAfterLast('/').ifBlank { "STT" }
                    }
                    SttProtocol.ALIYUN_DASHSCOPE_WS -> "百炼流式"
                    SttProtocol.VOLCENGINE_SAUC_WS -> "豆包流式"
                    SttProtocol.GEMINI_LIVE_WS -> "Gemini Live"
                }
                if (settings.llmConfig.enabled) {
                    val llmShort = settings.llmConfig.model.substringAfterLast('/').ifBlank { "LLM" }
                    "双阶段 · $sttShort + $llmShort"
                } else if (settings.sttConfig.protocol == SttProtocol.GEMINI_LIVE_WS) {
                    "流式智能净写 · Gemini Live"
                } else {
                    "双阶段 · 仅 $sttShort"
                }
            }

            PipelineMode.MULTIMODAL_DIRECT -> {
                val mmShort = settings.multimodalConfig.model.substringAfterLast('/').ifBlank { "多模态" }
                "多模态 · $mmShort"
            }
        }
    }

    /**
     * Pure helper to insert dictated text at cursor or replace selected span for Speak-to-Edit.
     * Returns updated (newText, newSelectionCursorIndex).
     */
    fun applySandboxInsertionOrReplacement(
        currentText: String,
        selectionStart: Int,
        selectionEnd: Int,
        outputText: String
    ): Pair<String, Int> {
        val safeMin = minOf(selectionStart, selectionEnd).coerceIn(0, currentText.length)
        val safeMax = maxOf(selectionStart, selectionEnd).coerceIn(0, currentText.length)
        val updated = buildString {
            append(currentText.substring(0, safeMin))
            append(outputText)
            append(currentText.substring(safeMax))
        }
        val newCursor = (safeMin + outputText.length).coerceIn(0, updated.length)
        return updated to newCursor
    }
}

private const val SAMPLE_PLAYGROUND_TEXT =
    "王总，明天下午三点那个项目评审会我可能赶不过去了，因为客户那边临时加了个 deep seek 接口联调需求，咱们能不能推迟到周四上午十点？"

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HomeSetupScreen(
    repository: AppRepository,
    aiEngine: AiPipelineEngine,
    onNavigateToTab: (Int) -> Unit,
    onNavigateToSettingsSubTab: (Int) -> Unit = { onNavigateToTab(AppDestinations.TAB_SETTINGS) }
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val windowInfo = LocalWindowInfo.current
    val coroutineScope = rememberCoroutineScope()

    val settings by repository.settings.collectAsState()
    val history by repository.history.collectAsState()
    val usageStats = remember(settings, history) {
        AppRepository.computeUsageStats(settings, history)
    }

    val a11yLiveConnected by TypeFreeBubbleController.isAccessibilityConnected.collectAsState()
    val forcePreviewVisible by TypeFreeBubbleController.forcePreviewVisible.collectAsState()

    // System & Bubble Permissions State (Lifecycle ON_RESUME + WindowFocus event-driven, no 1s polling loop)
    var hasMicPermission by remember {
        mutableStateOf(TypeFreeBubbleController.hasRecordAudioPermission(context))
    }
    var hasOverlayPermission by remember {
        mutableStateOf(TypeFreeBubbleController.hasOverlayPermission(context))
    }
    var isAccessibilityEnabled by remember {
        mutableStateOf(TypeFreeBubbleController.isAccessibilityEnabledInSettings(context))
    }
    var isImeEnabled by remember { mutableStateOf(checkImeEnabled(context)) }
    var isImeSelected by remember { mutableStateOf(checkImeSelected(context)) }
    var permissionDeniedOnce by remember { mutableStateOf(false) }
    var isPlaygroundSheetOpen by rememberSaveable { mutableStateOf(false) }

    val isApiReady = remember(settings) { SetupStatsHelper.isApiConfigured(settings) }
    val isA11yReady = isAccessibilityEnabled || a11yLiveConnected

    fun refreshSystemReadiness() {
        hasMicPermission = TypeFreeBubbleController.hasRecordAudioPermission(context)
        hasOverlayPermission = TypeFreeBubbleController.hasOverlayPermission(context)
        isAccessibilityEnabled = TypeFreeBubbleController.isAccessibilityEnabledInSettings(context)
        isImeEnabled = checkImeEnabled(context)
        isImeSelected = checkImeSelected(context)
    }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshSystemReadiness()
                if (repository.settings.value.bubbleEnabled) {
                    TypeFreeBubbleController.syncBubbleService(context)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Refresh immediately whenever the host window regains focus (e.g. after system IME picker dialog closes)
    LaunchedEffect(windowInfo.isWindowFocused) {
        if (windowInfo.isWindowFocused) {
            refreshSystemReadiness()
        }
    }

    // Built-in Playground Audio Recorder & State
    val recorder = remember(context) { WavAudioRecorder(context.applicationContext) }
    var sandboxStreamSession by remember { mutableStateOf<StreamingAsrSession?>(null) }
    DisposableEffect(recorder) {
        onDispose {
            sandboxStreamSession?.cancel()
            sandboxStreamSession = null
            CoroutineScope(Dispatchers.IO).launch {
                recorder.cancelRecording()
            }
        }
    }

    val amplitudes by recorder.amplitudes.collectAsState()
    val elapsedMs by recorder.elapsedMs.collectAsState()

    var sandboxValue by remember {
        mutableStateOf(
            TextFieldValue(
                text = SAMPLE_PLAYGROUND_TEXT,
                selection = TextRange(SAMPLE_PLAYGROUND_TEXT.length)
            )
        )
    }
    var lastDictatedBaseline by remember { mutableStateOf<String?>(SAMPLE_PLAYGROUND_TEXT) }
    var undoSnapshot by remember { mutableStateOf<TextFieldValue?>(null) }
    var sandboxManualIntent by rememberSaveable { mutableStateOf(VoiceIntentMode.DICTATE) }

    var isRecording by remember { mutableStateOf(false) }
    var processingStage by remember { mutableStateOf<PipelineStage?>(null) }
    var sandboxDiff by remember { mutableStateOf<SandboxPreviewDiff?>(null) }
    var sandboxError by remember { mutableStateOf<String?>(null) }
    var autoLearnedBanner by remember { mutableStateOf<String?>(null) }
    var copyFeedback by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasMicPermission = granted
        if (!granted) {
            permissionDeniedOnce = true
        } else {
            sandboxError = null
        }
    }

    val selMin = minOf(sandboxValue.selection.start, sandboxValue.selection.end)
        .coerceIn(0, sandboxValue.text.length)
    val selMax = maxOf(sandboxValue.selection.start, sandboxValue.selection.end)
        .coerceIn(0, sandboxValue.text.length)
    val selectedText = if (selMax > selMin) sandboxValue.text.substring(selMin, selMax) else ""
    val effectiveSandboxIntent = if (selectedText.isNotBlank()) {
        VoiceIntentMode.SPEAK_TO_EDIT
    } else {
        sandboxManualIntent
    }

    val (activeStyle, isAutoStyle) = remember(settings) {
        repository.resolveActiveStyle(context.packageName)
    }

    LaunchedEffect(
        settings.pipelineMode,
        settings.sttConfig.protocol,
        settings.sttConfig.apiKey,
        settings.sttConfig.baseUrl
    ) {
        runCatching {
            aiEngine.prewarmLiveStreamingIfNeeded(settings)
            aiEngine.prewarmEndpoints(settings)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(15.dp)
    ) {
        // 1. Minimalist Brand Bar (LogoMark + Bold TypeFree + API Readiness Capsule)
        TopBrandBar(
            settings = settings,
            isApiReady = isApiReady,
            onClickApiCapsule = {
                onNavigateToSettingsSubTab(AppDestinations.SUBTAB_API)
            }
        )

        // 2. Hero Efficiency Metrics Card (220 wpm + Total Words/Dictations + Saved Minutes)
        HeroMetricsCard(
            usageStats = usageStats,
            hasHistory = history.isNotEmpty(),
            onClickViewHistory = { onNavigateToTab(AppDestinations.TAB_HISTORY) }
        )

        // 3. Keyboard Setup Wizard (Auto-collapses to 1-line emerald banner when IME enabled + selected + API ready)
        KeyboardSetupWizardCard(
            hasMicPermission = hasMicPermission,
            permissionDeniedOnce = permissionDeniedOnce,
            isImeEnabled = isImeEnabled,
            isImeSelected = isImeSelected,
            isApiReady = isApiReady,
            onRequestMic = {
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            },
            onOpenAppSettings = {
                openAppPermissionSettings(context)
            },
            onOpenImeSettings = {
                openSystemImeSettings(context)
            },
            onShowImePicker = {
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                if (!isImeEnabled) {
                    openSystemImeSettings(context)
                } else {
                    imm?.showInputMethodPicker()
                }
            },
            onOpenApiConfig = {
                onNavigateToSettingsSubTab(AppDestinations.SUBTAB_API)
            }
        )

        // 4. TypeFree Floating Voice Bubble (Compact when disabled, expandable permission guide when enabled)
        FloatingBubbleHeroCard(
            bubbleEnabled = settings.bubbleEnabled,
            forcePreviewVisible = forcePreviewVisible,
            hasMicPermission = hasMicPermission,
            hasOverlayPermission = hasOverlayPermission,
            isAccessibilityReady = isA11yReady,
            onToggleBubbleEnabled = { enabled ->
                repository.updateSettings { it.copy(bubbleEnabled = enabled) }
                if (enabled && !TypeFreeBubbleController.hasOverlayPermission(context)) {
                    Toast.makeText(
                        context,
                        "请授予「悬浮窗显示权限」以启用跨应用悬浮声波球",
                        Toast.LENGTH_LONG
                    ).show()
                    TypeFreeBubbleController.openOverlayPermissionSettings(context)
                } else {
                    TypeFreeBubbleController.syncBubbleService(context)
                }
            },
            onToggleForcePreview = { preview ->
                if (preview) {
                    if (!TypeFreeBubbleController.hasOverlayPermission(context)) {
                        Toast.makeText(
                            context,
                            "请先开启「悬浮窗权限」后再预览屏幕悬浮球",
                            Toast.LENGTH_SHORT
                        ).show()
                        TypeFreeBubbleController.openOverlayPermissionSettings(context)
                    } else {
                        if (!settings.bubbleEnabled) {
                            repository.updateSettings { it.copy(bubbleEnabled = true) }
                        }
                        TypeFreeBubbleController.setForcePreviewVisible(true)
                        TypeFreeBubbleController.syncBubbleService(context)
                    }
                } else {
                    TypeFreeBubbleController.setForcePreviewVisible(false)
                }
            },
            onGrantMic = {
                if (permissionDeniedOnce) {
                    openAppPermissionSettings(context)
                } else {
                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            },
            onGrantOverlay = {
                TypeFreeBubbleController.openOverlayPermissionSettings(context)
            },
            onOpenAccessibility = {
                TypeFreeBubbleController.openAccessibilitySettings(context)
            }
        )

        // 5. Official Personalization Report Card
        PersonalizationReportCard(
            usageStats = usageStats,
            dictionarySize = settings.dictionary.size,
            onOpenStyles = {
                onNavigateToSettingsSubTab(AppDestinations.SUBTAB_STYLES)
            },
            onOpenDictionary = {
                onNavigateToTab(AppDestinations.TAB_DICTIONARY)
            }
        )

        // 6. Compact Playground Entry Card -> Opens ModalBottomSheet on demand (R5.1)
        val activeStyleBadge = "${activeStyle.emoji} ${activeStyle.name}" + if (isAutoStyle) " · 自动" else ""
        PlaygroundEntryCard(
            activeStyleLabel = activeStyleBadge,
            onOpenPlayground = { isPlaygroundSheetOpen = true }
        )

        Spacer(modifier = Modifier.height(10.dp))
    }

    if (isPlaygroundSheetOpen) {
        PlaygroundBottomSheet(
            sandboxValue = sandboxValue,
            onSandboxValueChange = { updatedValue ->
                val prevText = sandboxValue.text
                sandboxValue = updatedValue
                val baseline = lastDictatedBaseline
                if (baseline != null && updatedValue.text != prevText && updatedValue.text != baseline) {
                    val learned = repository.learnCorrectionFromDiff(baseline, updatedValue.text)
                    if (learned != null) {
                        lastDictatedBaseline = updatedValue.text
                        autoLearnedBanner = "已自动学习改错词条：「${learned.term}」已加入个人词典！"
                    }
                }
            },
            effectiveIntent = effectiveSandboxIntent,
            manualIntent = sandboxManualIntent,
            onSelectManualIntent = { mode -> sandboxManualIntent = mode },
            translateTarget = settings.activeTranslateTarget,
            activeStyleLabel = "${activeStyle.emoji} ${activeStyle.name}" + if (isAutoStyle) " · 自动" else "",
            selectedLength = selectedText.length,
            undoAvailable = undoSnapshot != null,
            copyFeedback = copyFeedback,
            autoLearnedBanner = autoLearnedBanner,
            isRecording = isRecording,
            processingStage = processingStage,
            amplitudes = amplitudes,
            elapsedMs = elapsedMs,
            sandboxError = sandboxError,
            isApiReady = isApiReady,
            sandboxDiff = sandboxDiff,
            onOpenStyles = {
                isPlaygroundSheetOpen = false
                onNavigateToSettingsSubTab(AppDestinations.SUBTAB_STYLES)
            },
            onOpenApiConfig = {
                isPlaygroundSheetOpen = false
                onNavigateToSettingsSubTab(AppDestinations.SUBTAB_API)
            },
            onOpenDictionary = {
                isPlaygroundSheetOpen = false
                onNavigateToTab(AppDestinations.TAB_DICTIONARY)
            },
            onSelectSamplePhrase = {
                undoSnapshot = sandboxValue
                val text = sandboxValue.text.ifBlank { SAMPLE_PLAYGROUND_TEXT }
                val targetSpan = "明天下午三点那个项目评审会我可能赶不过去了"
                val idx = text.indexOf(targetSpan)
                sandboxValue = if (idx >= 0) {
                    TextFieldValue(
                        text = text,
                        selection = TextRange(idx, idx + targetSpan.length)
                    )
                } else {
                    TextFieldValue(
                        text = SAMPLE_PLAYGROUND_TEXT,
                        selection = TextRange(0, SAMPLE_PLAYGROUND_TEXT.length)
                    )
                }
                lastDictatedBaseline = sandboxValue.text
                sandboxError = null
            },
            onResetSample = {
                undoSnapshot = sandboxValue
                sandboxValue = TextFieldValue(
                    text = SAMPLE_PLAYGROUND_TEXT,
                    selection = TextRange(SAMPLE_PLAYGROUND_TEXT.length)
                )
                lastDictatedBaseline = SAMPLE_PLAYGROUND_TEXT
                autoLearnedBanner = null
                sandboxError = null
            },
            onUndo = {
                undoSnapshot?.let { prev ->
                    sandboxValue = prev
                    undoSnapshot = null
                }
            },
            onCopy = {
                copyToClipboard(context, sandboxValue.text)
                copyFeedback = true
                coroutineScope.launch {
                    delay(1800L)
                    copyFeedback = false
                }
            },
            onClear = {
                undoSnapshot = sandboxValue
                sandboxValue = TextFieldValue("")
                lastDictatedBaseline = null
                sandboxDiff = null
                sandboxError = null
            },
            onStartRecording = {
                sandboxError = null
                if (!recorder.hasRecordPermission()) {
                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    sandboxError = "请先授予麦克风录音权限后再试"
                    return@PlaygroundBottomSheet
                }
                if (!SetupStatsHelper.isApiConfigured(settings)) {
                    sandboxError = "尚未填写自定义 API Key，请点击右上角前往「自定义 API」完成配置"
                    return@PlaygroundBottomSheet
                }
                sandboxStreamSession?.cancel()
                val liveSession = aiEngine.openLiveStreamingSession(settings)
                recorder.startRecording(
                    scope = coroutineScope,
                    onStreamChunk = if (liveSession != null) {
                        { pcm -> liveSession.sendPcmFrame(pcm) }
                    } else {
                        null
                    },
                    pauseMediaPlayback = settings.pauseMediaWhileRecording,
                    whisperMode = settings.whisperModeEnabled
                )
                    .onSuccess {
                        sandboxStreamSession = liveSession
                        isRecording = true
                        coroutineScope.launch {
                            aiEngine.prewarmEndpoints(settings)
                        }
                    }
                    .onFailure { err ->
                        liveSession?.cancel()
                        sandboxError = err.message ?: "无法启动麦克风录音"
                    }
            },
            onCancelRecording = {
                sandboxStreamSession?.cancel()
                sandboxStreamSession = null
                coroutineScope.launch {
                    recorder.cancelRecording()
                    isRecording = false
                }
            },
            onStopAndProcess = {
                val capturedSnapshot = sandboxValue
                val cMin = minOf(capturedSnapshot.selection.start, capturedSnapshot.selection.end)
                    .coerceIn(0, capturedSnapshot.text.length)
                val cMax = maxOf(capturedSnapshot.selection.start, capturedSnapshot.selection.end)
                    .coerceIn(0, capturedSnapshot.text.length)
                val capturedSelection = if (cMax > cMin) {
                    capturedSnapshot.text.substring(cMin, cMax)
                } else {
                    ""
                }
                val capturedBefore = capturedSnapshot.text.substring(0, cMin)
                val capturedIntent = if (capturedSelection.isNotBlank()) {
                    VoiceIntentMode.SPEAK_TO_EDIT
                } else {
                    sandboxManualIntent
                }

                val liveSession = sandboxStreamSession
                sandboxStreamSession = null

                coroutineScope.launch {
                    isRecording = false
                    val recorded = recorder.stopRecording().getOrElse { err ->
                        liveSession?.cancel()
                        sandboxError = err.message ?: "录音时间过短或未采集到声音"
                        return@launch
                    }

                    var preTranscribed: String? = null
                    var tailWaitMs = 0L
                    if (liveSession != null) {
                        processingStage = PipelineStage.TRANSCRIBING_STT
                        val tailStart = System.currentTimeMillis()
                        val streamRes = liveSession.finishAndAwaitFinal()
                        tailWaitMs = (System.currentTimeMillis() - tailStart).coerceAtLeast(0L)
                        val candidate = streamRes.getOrNull()?.trim().orEmpty()
                        if (candidate.isNotEmpty() && !AiPipelineEngine.isHallucinatedSilence(candidate)) {
                            preTranscribed = candidate
                        }
                    }

                    if (preTranscribed == null && !recorded.hasValidSpeech) {
                        liveSession?.cancel()
                        processingStage = null
                        sandboxError = "未检测到清晰说话声，请靠近麦克风重试"
                        return@launch
                    }

                    processingStage = when {
                        settings.pipelineMode == PipelineMode.MULTIMODAL_DIRECT -> PipelineStage.MULTIMODAL_PROCESSING
                        preTranscribed != null -> PipelineStage.POLISHING_LLM
                        else -> PipelineStage.TRANSCRIBING_STT
                    }
                    sandboxError = null

                    val (resolvedStyle, _) = repository.resolveActiveStyle(context.packageName)
                    val pipelineResult = aiEngine.processVoiceAudio(
                        wavFile = recorded.wavFile,
                        settings = settings,
                        activeStyle = resolvedStyle,
                        targetAppName = "TypeFree 沙盒",
                        selectedText = capturedSelection,
                        surroundingBeforeCursor = capturedBefore,
                        intentMode = capturedIntent,
                        translateTargetLanguage = settings.activeTranslateTarget,
                        preTranscribedText = preTranscribed,
                        sttTailWaitMs = tailWaitMs,
                        onStageChanged = { stage ->
                            processingStage = stage
                        }
                    )
                    processingStage = null

                    pipelineResult.onSuccess { exec ->
                        runCatching { aiEngine.prewarmLiveStreamingIfNeeded(settings) }
                        undoSnapshot = capturedSnapshot
                        val (newText, newCursor) = SetupStatsHelper.applySandboxInsertionOrReplacement(
                            currentText = capturedSnapshot.text,
                            selectionStart = cMin,
                            selectionEnd = cMax,
                            outputText = exec.finalOutput
                        )
                        sandboxValue = TextFieldValue(
                            text = newText,
                            selection = TextRange(newCursor)
                        )
                        lastDictatedBaseline = newText
                        sandboxDiff = SandboxPreviewDiff(
                            intentMode = capturedIntent,
                            originalSelection = capturedSelection,
                            rawTranscript = exec.rawTranscript,
                            polishedOutput = exec.finalOutput,
                            latencyMs = exec.latencyMs,
                            modeSummary = exec.modeSummary
                        )
                        repository.addHistory(
                            HistoryRecord(
                                targetAppName = "TypeFree 沙盒",
                                targetPackage = context.packageName,
                                styleName = resolvedStyle.name,
                                isSpeakToEdit = capturedIntent == VoiceIntentMode.SPEAK_TO_EDIT,
                                intentMode = capturedIntent,
                                translateTargetLanguage = if (capturedIntent == VoiceIntentMode.TRANSLATE) {
                                    settings.activeTranslateTarget
                                } else {
                                    ""
                                },
                                originalSelectedText = capturedSelection,
                                rawTranscript = exec.rawTranscript,
                                polishedText = exec.finalOutput,
                                durationMs = recorded.durationMs,
                                latencyMs = exec.latencyMs,
                                modeLabel = exec.modeSummary,
                                audioFilePath = recorded.wavFile.absolutePath
                            )
                        )
                    }.onFailure { err ->
                        sandboxError = err.message ?: "语音处理请求失败，请检查网络或 API 配置"
                    }
                }
            },
            onDismiss = { isPlaygroundSheetOpen = false }
        )
    }
}

@Composable
private fun TopBrandBar(
    settings: AppSettings,
    isApiReady: Boolean,
    onClickApiCapsule: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = TypeFreeIcons.LogoMark,
                    contentDescription = "TypeFree",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(19.dp)
                )
            }

            Text(
                text = "TypeFree",
                fontSize = 23.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-0.5).sp,
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        // Right Custom API Engine Status Capsule
        Surface(
            onClick = onClickApiCapsule,
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(
                1.dp,
                if (isApiReady) {
                    TypeFreeEmerald.copy(alpha = 0.45f)
                } else {
                    TypeFreeAmber.copy(alpha = 0.6f)
                }
            )
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(if (isApiReady) TypeFreeEmerald else TypeFreeAmber)
                )
                Text(
                    text = if (isApiReady) {
                        SetupStatsHelper.formatActivePipelineBadge(settings)
                    } else {
                        "配置自定义 API"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Icon(
                    imageVector = Icons.Default.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(15.dp)
                )
            }
        }
    }
}

@Composable
private fun HeroMetricsCard(
    usageStats: UsageStatsSummary,
    hasHistory: Boolean,
    onClickViewHistory: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Speak, don't type.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = "${usageStats.averageWpm}",
                            fontSize = 38.sp,
                            lineHeight = 40.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = (-1).sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "wpm",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 5.dp)
                        )
                    }
                    Text(
                        text = "口述净写语速 · 约为手指打字速度 4.2 倍",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = TypeFreeIcons.VoiceWaveform,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(25.dp)
                    )
                }
            }

            // 3-Column Metrics Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                HeroMiniStatBox(
                    label = "累计口述字数",
                    value = SetupStatsHelper.formatCharCount(usageStats.totalCharacters),
                    unit = "字",
                    modifier = Modifier.weight(1f)
                )
                HeroMiniStatBox(
                    label = "累计口述次数",
                    value = usageStats.totalDictations.toString(),
                    unit = "次",
                    modifier = Modifier.weight(1f)
                )
                HeroMiniStatBox(
                    label = "节省打字时间",
                    value = usageStats.savedMinutes.toString(),
                    unit = "分钟",
                    modifier = Modifier.weight(1f)
                )
            }

            if (hasHistory) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onClickViewHistory)
                        .padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = TypeFreeIcons.History,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "查看本机口述与润色历史 ›",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun HeroMiniStatBox(
    label: String,
    value: String,
    unit: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(13.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.width(3.dp))
                Text(
                    text = unit,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 1.dp)
                )
            }
        }
    }
}

/**
 * V2.10 Flagship Card: Voice Input Without Switching Keyboards (TypeFree Floating Soundwave Bubble)
 * Aligned with `ref_typefree_bubble.png`.
 */
@Composable
private fun FloatingBubbleHeroCard(
    bubbleEnabled: Boolean,
    forcePreviewVisible: Boolean,
    hasMicPermission: Boolean,
    hasOverlayPermission: Boolean,
    isAccessibilityReady: Boolean,
    onToggleBubbleEnabled: (Boolean) -> Unit,
    onToggleForcePreview: (Boolean) -> Unit,
    onGrantMic: () -> Unit,
    onGrantOverlay: () -> Unit,
    onOpenAccessibility: () -> Unit
) {
    var manualExpanded by rememberSaveable { mutableStateOf(false) }
    val showDetails = bubbleEnabled || manualExpanded

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(
            width = if (bubbleEnabled) 1.4.dp else 1.dp,
            color = if (bubbleEnabled) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outline.copy(alpha = 0.58f)
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Top Header with Miniature TypeFree Soundwave Orb Preview + Master Switch
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { manualExpanded = !manualExpanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(Color(0x22000000))
                            .padding(4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .shadow(4.dp, CircleShape)
                                .clip(CircleShape)
                                .background(Color(0xFF141417)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = TypeFreeIcons.VoiceWaveform,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(21.dp)
                            )
                        }
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "无需切换键盘语音输入",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.ExtraBold
                            )
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.primary
                            ) {
                                Text(
                                    text = "悬浮胶囊",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Text(
                            text = if (bubbleEnabled) {
                                "已开启跨应用悬浮球 · 输入框聚焦时自动浮现"
                            } else {
                                "可选特性：保留第三方键盘，输入框旁浮现声波球（点击展开权限说明）"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 18.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))
                Switch(
                    checked = bubbleEnabled,
                    onCheckedChange = onToggleBubbleEnabled
                )
            }

            AnimatedVisibility(visible = showDetails) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.28f))

                    BubblePermissionRow(
                        stepLabel = "1",
                        title = "麦克风录音权限",
                        subtitle = if (hasMicPermission) "已授权 16kHz 清晰语音采集" else "采集跨应用语音输入必需权限",
                        isGranted = hasMicPermission,
                        actionText = "去授权",
                        onClickAction = onGrantMic
                    )

                    BubblePermissionRow(
                        stepLabel = "2",
                        title = "悬浮窗显示权限",
                        subtitle = if (hasOverlayPermission) "允许在其他应用上方展示声波圆球" else "开启后方可在微信/飞书等应用旁浮现声波球",
                        isGranted = hasOverlayPermission,
                        actionText = "开启悬浮窗",
                        onClickAction = onGrantOverlay
                    )

                    BubblePermissionRow(
                        stepLabel = "3",
                        title = "无障碍光标直插服务",
                        subtitle = if (isAccessibilityReady) {
                            "已连接 · 自动感知焦点并将润色结果直插光标"
                        } else {
                            "识别输入框焦点并直接填入光标（未开启时自动回退剪贴板）"
                        },
                        isGranted = isAccessibilityReady,
                        actionText = "开启服务",
                        onClickAction = onOpenAccessibility
                    )

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(13.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(end = 10.dp)
                            ) {
                                Text(
                                    text = "在屏幕上预览悬浮球",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "无视输入框焦点立即在屏幕边缘显示悬浮声波球，可拖拽吸边并点击实测",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = forcePreviewVisible,
                                onCheckedChange = onToggleForcePreview
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BubblePermissionRow(
    stepLabel: String,
    title: String,
    subtitle: String,
    isGranted: Boolean,
    actionText: String,
    onClickAction: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(
                    if (isGranted) TypeFreeEmerald else MaterialTheme.colorScheme.surfaceVariant
                ),
            contentAlignment = Alignment.Center
        ) {
            if (isGranted) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(14.dp)
                )
            } else {
                Text(
                    text = stepLabel,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (isGranted) {
            Text(
                text = "已就绪",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = TypeFreeEmerald
            )
        } else {
            FilledTonalButton(
                onClick = onClickAction,
                shape = RoundedCornerShape(9.dp),
                contentPadding = PaddingValues(horizontal = 11.dp, vertical = 5.dp)
            ) {
                Text(
                    text = actionText,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

/**
 * Keyboard Setup Wizard:
 * Automatically collapses into a clean 1-line emerald status strip once IME enabled + selected + API ready!
 */
@Composable
private fun KeyboardSetupWizardCard(
    hasMicPermission: Boolean,
    permissionDeniedOnce: Boolean,
    isImeEnabled: Boolean,
    isImeSelected: Boolean,
    isApiReady: Boolean = true,
    onRequestMic: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onOpenImeSettings: () -> Unit,
    onShowImePicker: () -> Unit,
    onOpenApiConfig: () -> Unit = {}
) {
    val completedCount = listOf(isImeEnabled, isImeSelected, isApiReady).count { it }
    val allReady = isImeEnabled && isImeSelected && isApiReady && hasMicPermission
    var manualExpanded by rememberSaveable { mutableStateOf(false) }

    if (allReady && !manualExpanded) {
        // Sleek 1-Line Emerald Ready Banner (R5.1)
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .clickable { manualExpanded = true },
            shape = RoundedCornerShape(16.dp),
            color = TypeFreeEmerald.copy(alpha = 0.10f),
            border = BorderStroke(1.dp, TypeFreeEmerald.copy(alpha = 0.45f))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(TypeFreeEmerald),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(13.dp)
                        )
                    }
                    Text(
                        text = "灵言 TypeFree 语音键盘已就绪 (3/3) · 点击展开",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    TextButton(
                        onClick = onShowImePicker,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        Text("切换输入法", style = MaterialTheme.typography.labelSmall)
                    }
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = "展开详情",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
        return
    }

    val progress by animateFloatAsState(
        targetValue = completedCount / 3f,
        label = "keyboardWizardProgress"
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f))
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
                        text = "完整版语音键盘启用三步向导",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "完成输入法启用、默认切换与 API 配置后将自动收起为单行就绪条",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (allReady) {
                    TextButton(
                        onClick = { manualExpanded = false },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text("收起", style = MaterialTheme.typography.labelMedium)
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowUp,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                } else {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            text = "$completedCount / 3",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(RoundedCornerShape(50)),
                color = if (allReady) TypeFreeEmerald else MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )

            if (!hasMicPermission) {
                WizardStepItem(
                    stepNumber = 0,
                    title = "授予麦克风录音权限",
                    subtitle = "用于键盘语音采集",
                    isDone = false,
                    buttonLabel = if (permissionDeniedOnce) "系统设置" else "授予权限",
                    onClick = if (permissionDeniedOnce) onOpenAppSettings else onRequestMic
                )
            }

            WizardStepItem(
                stepNumber = 1,
                title = "在系统输入法列表中勾选「灵言 TypeFree」",
                subtitle = if (isImeEnabled) "已在系统虚拟键盘管理中启用" else "进入系统设置开启灵言键盘开关",
                isDone = isImeEnabled,
                buttonLabel = "去启用",
                onClick = onOpenImeSettings
            )

            WizardStepItem(
                stepNumber = 2,
                title = "切换默认输入法为「灵言 TypeFree」",
                subtitle = if (isImeSelected) "当前默认输入法已就绪" else "调出输入法选择器切换键盘",
                isDone = isImeSelected,
                buttonLabel = if (isImeSelected) "重选键盘" else "切换键盘",
                keepClickableWhenDone = true,
                onClick = onShowImePicker
            )

            WizardStepItem(
                stepNumber = 3,
                title = "配置语音识别与润色 API",
                subtitle = if (isApiReady) "API 密钥与模型端点已就绪" else "配置云端或本地 API 密钥",
                isDone = isApiReady,
                buttonLabel = if (isApiReady) "修改配置" else "去配置",
                keepClickableWhenDone = true,
                onClick = onOpenApiConfig
            )
        }
    }
}

@Composable
private fun WizardStepItem(
    stepNumber: Int,
    title: String,
    subtitle: String,
    isDone: Boolean,
    buttonLabel: String,
    keepClickableWhenDone: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(if (isDone) TypeFreeEmerald else MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            if (isDone) {
                Canvas(modifier = Modifier.size(12.dp)) {
                    val path = Path().apply {
                        moveTo(size.width * 0.16f, size.height * 0.52f)
                        lineTo(size.width * 0.42f, size.height * 0.78f)
                        lineTo(size.width * 0.85f, size.height * 0.24f)
                    }
                    drawPath(
                        path = path,
                        color = Color.White,
                        style = Stroke(
                            width = 2.2.dp.toPx(),
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round
                        )
                    )
                }
            } else {
                Text(
                    text = stepNumber.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (!isDone) {
            Button(
                onClick = onClick,
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Text(buttonLabel, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            }
        } else if (keepClickableWhenDone) {
            OutlinedButton(
                onClick = onClick,
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(buttonLabel, style = MaterialTheme.typography.labelSmall)
            }
        } else {
            Text(
                text = "✓ 已完成",
                style = MaterialTheme.typography.labelMedium,
                color = TypeFreeEmerald,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

/**
 * Official Personalization Report Card (Aligned with `personalization_report.webp`).
 */
@Composable
private fun PersonalizationReportCard(
    usageStats: UsageStatsSummary,
    dictionarySize: Int,
    onOpenStyles: () -> Unit,
    onOpenDictionary: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f))
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
                        text = "个性化适配度报告",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Overall personalization · 本机分析你的词典与宿主场景",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Overall Percentage Ring Pill
                Box(
                    modifier = Modifier.size(54.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        progress = { (usageStats.overallPersonalizationPercent / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        strokeWidth = 4.5.dp
                    )
                    Text(
                        text = "${usageStats.overallPersonalizationPercent}%",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            // 4 Application Category Progress Bars
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                usageStats.categoryProgress.forEach { (categoryName, progressVal) ->
                    val clamped = progressVal.coerceIn(0f, 1f)
                    val pct = (clamped * 100).roundToInt()
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = categoryName,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "$pct%",
                                style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        LinearProgressIndicator(
                            progress = { clamped },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(50)),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.28f))

            // Quick Navigation Shortcuts to Styles & Dictionary
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                FilledTonalButton(
                    onClick = onOpenStyles,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(11.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    Icon(
                        imageVector = TypeFreeIcons.Sparkles,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("场景风格规则 ›", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                }

                OutlinedButton(
                    onClick = onOpenDictionary,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(11.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    Icon(
                        imageVector = TypeFreeIcons.Book,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("个人词典 ($dictionarySize) ›", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

private fun checkImeEnabled(context: Context): Boolean {
    return try {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.enabledInputMethodList?.any { it.packageName == context.packageName } == true
    } catch (_: Exception) {
        false
    }
}

private fun checkImeSelected(context: Context): Boolean {
    return try {
        val defaultIme = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.DEFAULT_INPUT_METHOD
        )
        defaultIme?.startsWith(context.packageName) == true
    } catch (_: Exception) {
        false
    }
}

private fun openSystemImeSettings(context: Context) {
    try {
        val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (_: Exception) {
    }
}

private fun openAppPermissionSettings(context: Context) {
    try {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null)
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (_: Exception) {
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    cm?.setPrimaryClip(ClipData.newPlainText("TypeFree Sandbox", text))
}

private fun formatDurationSec(ms: Long): String {
    val totalTenths = (ms.coerceAtLeast(0L) / 100L)
    val secs = totalTenths / 10L
    val tenths = totalTenths % 10L
    val mins = secs / 60L
    val remSecs = secs % 60L
    return String.format(Locale.US, "%02d:%02d.%d", mins, remSecs, tenths)
}
