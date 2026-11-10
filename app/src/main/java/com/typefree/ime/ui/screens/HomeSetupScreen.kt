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
import com.typefree.ime.network.PipelineStage
import com.typefree.ime.network.asr.StreamingAsrSession
import com.typefree.ime.ui.AppDestinations
import com.typefree.ime.ui.components.TypeFreeIcons
import com.typefree.ime.ui.theme.TypeFreeAmber
import com.typefree.ime.ui.theme.TypeFreeEmerald
import com.typefree.ime.ui.theme.TypeFreeRose
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
                        stt.model.isNotBlank() &&
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
                    SttProtocol.OPENAI_HTTP -> settings.sttConfig.model.substringAfterLast('/').ifBlank { "STT" }
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

private data class SandboxPreviewDiff(
    val intentMode: VoiceIntentMode,
    val originalSelection: String,
    val rawTranscript: String,
    val polishedOutput: String,
    val latencyMs: Long,
    val modeSummary: String
)

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
    val coroutineScope = rememberCoroutineScope()

    val settings by repository.settings.collectAsState()
    val history by repository.history.collectAsState()
    val usageStats = remember(settings, history) {
        AppRepository.computeUsageStats(settings, history)
    }

    val a11yLiveConnected by TypeFreeBubbleController.isAccessibilityConnected.collectAsState()
    val forcePreviewVisible by TypeFreeBubbleController.forcePreviewVisible.collectAsState()

    // System & Bubble Permissions State
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

    val isApiReady = remember(settings) { SetupStatsHelper.isApiConfigured(settings) }
    val isKeyboardAllReady = hasMicPermission && isImeEnabled && isImeSelected
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

    LaunchedEffect(isKeyboardAllReady, hasOverlayPermission, isA11yReady) {
        while (isActive) {
            refreshSystemReadiness()
            delay(if (isKeyboardAllReady && hasOverlayPermission && isA11yReady) 2500L else 1000L)
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

        // 3. V2.10 Flagship Card: TypeFree Floating Voice Bubble (No Keyboard Switch Needed!)
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

        // 4. 3-Step Keyboard Setup Wizard (Auto-collapses to 1-line green strip when all 3 ready!)
        KeyboardSetupWizardCard(
            hasMicPermission = hasMicPermission,
            permissionDeniedOnce = permissionDeniedOnce,
            isImeEnabled = isImeEnabled,
            isImeSelected = isImeSelected,
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
            }
        )

        // 5. Official Personalization Report Card (Aligned with personalization_report.webp)
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

        // 6. Full-Featured Interactive Playground
        PlaygroundCard(
            sandboxValue = sandboxValue,
            onSandboxValueChange = { updatedValue ->
                val prevText = sandboxValue.text
                sandboxValue = updatedValue
                // Live Auto-Learn Dictionary Hook when manually editing freshly dictated/sample text!
                val baseline = lastDictatedBaseline
                if (baseline != null && updatedValue.text != prevText && updatedValue.text != baseline) {
                    val learned = repository.learnCorrectionFromDiff(baseline, updatedValue.text)
                    if (learned != null) {
                        lastDictatedBaseline = updatedValue.text
                        autoLearnedBanner = "📖 已自动学习改错词条：「${learned.term}」已加入个人词典！"
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
            onOpenStyles = { onNavigateToSettingsSubTab(AppDestinations.SUBTAB_STYLES) },
            onOpenApiConfig = { onNavigateToSettingsSubTab(AppDestinations.SUBTAB_API) },
            onOpenDictionary = { onNavigateToTab(AppDestinations.TAB_DICTIONARY) },
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
                    return@PlaygroundCard
                }
                if (!SetupStatsHelper.isApiConfigured(settings)) {
                    sandboxError = "尚未填写自定义 API Key，请点击右上角前往「自定义 API」完成配置"
                    return@PlaygroundCard
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
            }
        )

        Spacer(modifier = Modifier.height(10.dp))
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
            // Top Header with Authentic Miniature TypeFree Soundwave Orb Preview + Master Switch
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    // Authentic `ref_typefree_bubble.png` Soundwave Orb Miniature
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
                            text = "保留 Gboard / 搜狗等日常输入法，输入框聚焦时自动浮现极简纯黑声波球，轻点说话直插光标！",
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

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.28f))

            // 3 Real-Time Permission Status & 1-Tap Grant Rows
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

            // Quick On-Screen Preview Switch (`TypeFreeBubbleController.forcePreviewVisible`)
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
 * 3-Step Built-in Keyboard Setup Wizard:
 * Automatically collapses into a clean 1-line green status strip once all 3 steps are ready!
 */
@Composable
private fun KeyboardSetupWizardCard(
    hasMicPermission: Boolean,
    permissionDeniedOnce: Boolean,
    isImeEnabled: Boolean,
    isImeSelected: Boolean,
    onRequestMic: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onOpenImeSettings: () -> Unit,
    onShowImePicker: () -> Unit
) {
    val completedCount = listOf(hasMicPermission, isImeEnabled, isImeSelected).count { it }
    val allReady = completedCount == 3
    var manualExpanded by rememberSaveable { mutableStateOf(false) }

    if (allReady && !manualExpanded) {
        // Sleek 1-Line Green Status Strip
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
                        text = "灵言 TypeFree 专属语音键盘已就绪 (3/3)",
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
                        text = "解锁左下角语音笔、长按弧形快译与内置 EN 键盘改错学习",
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

            WizardStepItem(
                stepNumber = 1,
                title = "授予麦克风权限",
                subtitle = if (hasMicPermission) "已授权录音" else "用于键盘语音采集",
                isDone = hasMicPermission,
                buttonLabel = if (permissionDeniedOnce && !hasMicPermission) "系统设置" else "授予权限",
                onClick = if (permissionDeniedOnce && !hasMicPermission) onOpenAppSettings else onRequestMic
            )

            WizardStepItem(
                stepNumber = 2,
                title = "在系统输入法列表中勾选「灵言 TypeFree」",
                subtitle = if (isImeEnabled) "已在系统虚拟键盘管理中启用" else "进入系统设置开启灵言键盘开关",
                isDone = isImeEnabled,
                buttonLabel = "去启用",
                onClick = onOpenImeSettings
            )

            WizardStepItem(
                stepNumber = 3,
                title = "切换默认输入法为「灵言 TypeFree」",
                subtitle = if (isImeSelected) "当前默认输入法已就绪" else "调出输入法选择器切换键盘",
                isDone = isImeSelected,
                buttonLabel = if (isImeSelected) "重选键盘" else "切换键盘",
                keepClickableWhenDone = true,
                onClick = onShowImePicker
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

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun PlaygroundCard(
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
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "全功能实测沙盒 (Playground)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "预置真实文本，一站体验五大核心语音交互黑科技",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Surface(
                    onClick = onOpenStyles,
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        text = activeStyleLabel,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            }

            // 5-Feature Guided Prompt Strip
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
                        text = "💡 推荐直接点击下方输入框依次体验：",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "① 口述净写（去口癖与改口自纠） · ② 左下角语音笔 Help me write · ③ 选中文字 Speak to edit · ④ 长按上滑弧形快译 · ⑤ EN 内置键盘改错自动入词典（试试把下方 deep seek 改成 DeepSeek！）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 17.sp
                    )
                }
            }

            // Built-in Mode Switcher when no text is selected
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
                            label = "🎙️ 口述净写",
                            selected = manualIntent == VoiceIntentMode.DICTATE,
                            onClick = { onSelectManualIntent(VoiceIntentMode.DICTATE) },
                            modifier = Modifier.weight(1f)
                        )
                        PlaygroundIntentPill(
                            label = "✍️ 帮我写",
                            selected = manualIntent == VoiceIntentMode.HELP_ME_WRITE,
                            onClick = { onSelectManualIntent(VoiceIntentMode.HELP_ME_WRITE) },
                            modifier = Modifier.weight(1f)
                        )
                        PlaygroundIntentPill(
                            label = "🌐 快译 ($translateTarget)",
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
                        text = "✨ 已激活「选中文字 Speak to edit」（已选 $selectedLength 字）：点击下方录音说出修改指令，如「改成更礼貌简短的商务语气」或「翻译成日文」！",
                        style = MaterialTheme.typography.labelMedium,
                        color = TypeFreeEmerald,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp)
                    )
                }
            }

            // Utility Chips Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AssistChip(
                    onClick = onSelectSamplePhrase,
                    label = { Text("一键选中句子测 Speak to edit", style = MaterialTheme.typography.labelMedium) }
                )

                AssistChip(
                    onClick = onResetSample,
                    label = { Text("恢复示例文本", style = MaterialTheme.typography.labelMedium) }
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
                        label = { Text("撤销", style = MaterialTheme.typography.labelMedium) }
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
                                if (copyFeedback) "已复制" else "复制",
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    )

                    AssistChip(
                        onClick = onClear,
                        label = { Text("清空", style = MaterialTheme.typography.labelMedium) }
                    )
                }
            }

            // Multi-line Pre-populated Playground Editor
            OutlinedTextField(
                value = sandboxValue,
                onValueChange = onSandboxValueChange,
                modifier = Modifier.fillMaxWidth(),
                minLines = 4,
                maxLines = 8,
                shape = RoundedCornerShape(14.dp),
                placeholder = {
                    Text(
                        text = "点击此处唤起灵言语音键盘或悬浮声波球，也可直接点击下方内置录音按钮实测...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
                    )
                }
            )

            // Auto-Learned Dictionary Feedback Banner
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
                            Text(
                                text = "查看词典 ›",
                                style = MaterialTheme.typography.labelSmall,
                                color = TypeFreeEmerald
                            )
                        }
                    }
                }
            }

            // Built-in Voice Recorder Capsule
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

            // Error Diagnostic Banner
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
                            if (!isApiReady || errMsg.contains("API")) {
                                TextButton(
                                    onClick = onOpenApiConfig,
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text("去配置 ›", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                }
            }

            // Live Raw vs Polished Diff Preview
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
    val isSpeakToEdit = effectiveIntent == VoiceIntentMode.SPEAK_TO_EDIT

    when {
        processingStage != null -> {
            val stageLabel = when (processingStage) {
                PipelineStage.TRANSCRIBING_STT -> "正在通过 STT 接口识别语音初稿..."
                PipelineStage.POLISHING_LLM -> "正在调用大模型执行${effectiveIntent.label}..."
                PipelineStage.MULTIMODAL_PROCESSING -> "多模态大模型正在听音理解直出..."
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
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(9.dp)
                                    .clip(CircleShape)
                                    .background(TypeFreeRose)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = when (effectiveIntent) {
                                    VoiceIntentMode.SPEAK_TO_EDIT -> "正在聆听选区改写指令..."
                                    VoiceIntentMode.HELP_ME_WRITE -> "正在聆听写作需求..."
                                    VoiceIntentMode.TRANSLATE -> "正在聆听并准备译为 $translateTarget..."
                                    VoiceIntentMode.DICTATE -> "正在聆听口述语音..."
                                },
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
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
                            Text("取消")
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
                                text = "说完了 · 立即生成",
                                fontWeight = FontWeight.Bold
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
                        VoiceIntentMode.SPEAK_TO_EDIT -> "点击录音 · 对选中文字下达语音编辑指令"
                        VoiceIntentMode.HELP_ME_WRITE -> "点击录音 · 说出写作主题直接生成草稿"
                        VoiceIntentMode.TRANSLATE -> "点击录音 · 说中文直接快译为 $translateTarget"
                        VoiceIntentMode.DICTATE -> "点击内置麦克风实测口述净写"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SandboxDiffPreviewCard(diff: SandboxPreviewDiff) {
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
                    text = "✓ ${diff.intentMode.label}完成对比",
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
                    text = "选区原文：${diff.originalSelection}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text(
                text = "语音初稿：${diff.rawTranscript}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text(
                text = "上屏结果：${diff.polishedOutput}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
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
