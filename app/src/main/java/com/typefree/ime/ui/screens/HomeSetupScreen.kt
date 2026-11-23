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
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
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
import com.typefree.core.offline.LocalModelRegistrySnapshot
import com.typefree.core.offline.OfflineMode
import com.typefree.core.offline.ReadinessState
import com.typefree.ime.audio.WavAudioRecorder
import com.typefree.ime.bubble.TypeFreeBubbleController
import com.typefree.ime.data.AppRepository
import com.typefree.ime.data.AppSettings
import com.typefree.ime.data.HistoryRecord
import com.typefree.ime.data.OfflineRouteBridge
import com.typefree.ime.data.PipelineMode
import com.typefree.ime.data.SttProtocol
import com.typefree.ime.data.VoiceIntentMode
import com.typefree.ime.data.stats.InsightsSnapshot
import com.typefree.ime.data.stats.StatsTimeRange
import com.typefree.ime.data.stats.TokenFeatureKeys
import com.typefree.ime.network.AiPipelineEngine
import com.typefree.ime.network.EndpointResolver
import com.typefree.ime.network.PipelineStage
import com.typefree.ime.network.asr.StreamingAsrSession
import com.typefree.ime.ui.AppDestinations
import com.typefree.ime.ui.components.ContinuousRoundedCornerShape
import com.typefree.ime.ui.components.SmoothDisclosure
import com.typefree.ime.ui.components.TypeFreeIcons
import com.typefree.ime.ui.components.rememberLocalModelRegistrySnapshot
import com.typefree.ime.ui.components.springPressScale
import com.typefree.ime.ui.screens.home.PlaygroundBottomSheet
import com.typefree.ime.ui.screens.home.PlaygroundEntryCard
import com.typefree.ime.ui.screens.home.SandboxPreviewDiff
import com.typefree.ime.ui.screens.insights.InsightsScreen
import com.typefree.ime.ui.screens.insights.MiniSevenDayWaveform
import com.typefree.ime.ui.theme.TypeFreeAmber
import com.typefree.ime.ui.theme.TypeFreeEmerald
import com.typefree.ime.ui.theme.TypeFreeRose
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

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
     * Unified readiness check delegating to [OfflineRouteBridge.resolveReadiness] so cloud BYOK,
     * hybrid offline ASR + cloud polish, pure offline, and raw-only degraded states stay consistent.
     */
    fun resolveReadiness(
        settings: AppSettings,
        registrySnapshot: LocalModelRegistrySnapshot = LocalModelRegistrySnapshot(),
        isNetworkAvailable: Boolean = true
    ): ReadinessState {
        return OfflineRouteBridge.resolveReadiness(
            settings = settings,
            registrySnapshot = registrySnapshot,
            isNetworkAvailable = isNetworkAvailable
        )
    }

    /**
     * Backward-compatible boolean readiness helper delegating to [resolveReadiness].
     */
    fun isApiConfigured(
        settings: AppSettings,
        registrySnapshot: LocalModelRegistrySnapshot = LocalModelRegistrySnapshot()
    ): Boolean {
        return resolveReadiness(settings, registrySnapshot) !is ReadinessState.NotReady
    }

    fun formatActivePipelineBadge(
        settings: AppSettings,
        registrySnapshot: LocalModelRegistrySnapshot = LocalModelRegistrySnapshot()
    ): String {
        val routeBadge = OfflineRouteBridge.formatBadgeForDictate(
            settings = settings,
            registrySnapshot = registrySnapshot
        )
        if (settings.offlineConfig.mode != OfflineMode.OFF &&
            (routeBadge.isPureOffline || routeBadge.isHybrid || resolveReadiness(settings, registrySnapshot) is ReadinessState.NotReady)
        ) {
            return routeBadge.shortLabel
        }
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
    onNavigateToSettingsSubTab: (Int) -> Unit = { onNavigateToTab(AppDestinations.TAB_VOICE_AI) },
    onNavigateToVoiceAiSubTab: (Int) -> Unit = onNavigateToSettingsSubTab,
    onOpenOfflineModels: () -> Unit = { onNavigateToVoiceAiSubTab(AppDestinations.SUBTAB_VOICE_API) },
    initialOpenInsights: Boolean = false,
    onConsumeOpenInsights: () -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val windowInfo = LocalWindowInfo.current
    val coroutineScope = rememberCoroutineScope()

    val settings by repository.settings.collectAsState()
    val history by repository.history.collectAsState()
    val statsRev by repository.dailyStatsRevision.collectAsState()
    var showInsightsPage by rememberSaveable { mutableStateOf(initialOpenInsights) }
    LaunchedEffect(initialOpenInsights) {
        if (initialOpenInsights) {
            showInsightsPage = true
            onConsumeOpenInsights()
        }
    }
    var historyFilterDateKey by rememberSaveable { mutableStateOf<Int?>(null) }
    var showJevMemoryDialog by remember { mutableStateOf(false) }
    val weekSnapshot = remember(settings, history, statsRev, showInsightsPage, showJevMemoryDialog) {
        repository.computeInsightsSnapshot(StatsTimeRange.DAYS_7)
    }
    val allSnapshot = remember(settings, history, statsRev, showInsightsPage, showJevMemoryDialog) {
        repository.computeInsightsSnapshot(StatsTimeRange.ALL)
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
    var showHistoryPage by rememberSaveable { mutableStateOf(false) }

    BackHandler(enabled = showHistoryPage) {
        showHistoryPage = false
        historyFilterDateKey = null
    }

    BackHandler(enabled = showInsightsPage) {
        showInsightsPage = false
    }

    if (showInsightsPage) {
        InsightsScreen(
            repository = repository,
            onBack = { showInsightsPage = false },
            onOpenHistoryForDate = { dateKey ->
                showInsightsPage = false
                historyFilterDateKey = dateKey
                showHistoryPage = true
            },
            onOpenPrivacySettings = {
                showInsightsPage = false
                onNavigateToTab(AppDestinations.TAB_GENERAL)
            },
            onOpenStylesSettings = {
                showInsightsPage = false
                onNavigateToVoiceAiSubTab(AppDestinations.SUBTAB_VOICE_AI_STYLE)
            },
            onOpenPlayground = {
                showInsightsPage = false
                isPlaygroundSheetOpen = true
            },
            onOpenJevMemory = { showJevMemoryDialog = true },
            onOpenApiUsage = {
                showInsightsPage = false
                onNavigateToVoiceAiSubTab(AppDestinations.SUBTAB_VOICE_API)
            }
        )
        if (showJevMemoryDialog) {
            JevMemoryManagementDialog(
                repository = repository,
                onDismiss = { showJevMemoryDialog = false }
            )
        }
        return
    }

    if (showHistoryPage) {
        Column(modifier = Modifier.fillMaxSize()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 1.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(
                        onClick = {
                            showHistoryPage = false
                            historyFilterDateKey = null
                        }
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                            contentDescription = "返回首页",
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                        Text("返回首页", fontWeight = FontWeight.SemiBold)
                    }
                    Text(
                        text = "识别历史 (${history.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(72.dp))
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                HistoryScreen(
                    repository = repository,
                    aiEngine = aiEngine,
                    initialDateKey = historyFilterDateKey
                )
            }
        }
        return
    }

    val registrySnapshot = rememberLocalModelRegistrySnapshot()
    val readiness = remember(settings, registrySnapshot) {
        SetupStatsHelper.resolveReadiness(settings, registrySnapshot)
    }
    val isApiReady = readiness !is ReadinessState.NotReady
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
            registrySnapshot = registrySnapshot,
            readiness = readiness,
            onClickApiCapsule = {
                onNavigateToVoiceAiSubTab(AppDestinations.SUBTAB_VOICE_API)
            }
        )

        // 2. Hero Efficiency & History Unified Card (Real Insights Metrics + Recognition History Link)
        HeroMetricsCard(
            weekSnapshot = weekSnapshot,
            allSnapshot = allSnapshot,
            historyCount = history.size,
            onClickOpenInsights = { showInsightsPage = true },
            onClickOpenHistory = {
                historyFilterDateKey = null
                showHistoryPage = true
            }
        )

        // 3. Keyboard Setup Wizard (Auto-collapses to 1-line emerald banner when IME enabled + selected + API ready)
        KeyboardSetupWizardCard(
            hasMicPermission = hasMicPermission,
            permissionDeniedOnce = permissionDeniedOnce,
            isImeEnabled = isImeEnabled,
            isImeSelected = isImeSelected,
            readiness = readiness,
            hasPendingOfflineRestore = settings.pendingOfflineRestore != null,
            isAccessibilityReady = isA11yReady,
            jevCopilotEnabled = settings.jevCopilotEnabled,
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
                onNavigateToVoiceAiSubTab(AppDestinations.SUBTAB_VOICE_API)
            },
            onOpenOfflineModels = onOpenOfflineModels,
            onOpenAccessibility = {
                TypeFreeBubbleController.openAccessibilitySettings(context)
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

        // 5. Real Growth Card (✦ 越来越懂你 -> InsightsScreen)
        PersonalizationReportCard(
            allSnapshot = allSnapshot,
            dictionarySize = settings.dictionary.size,
            onOpenInsights = { showInsightsPage = true },
            onOpenStyles = {
                onNavigateToVoiceAiSubTab(AppDestinations.SUBTAB_VOICE_AI_STYLE)
            },
            onOpenDictionary = {
                onNavigateToVoiceAiSubTab(AppDestinations.SUBTAB_VOICE_MAIN)
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
                onNavigateToVoiceAiSubTab(AppDestinations.SUBTAB_VOICE_AI_STYLE)
            },
            onOpenApiConfig = {
                isPlaygroundSheetOpen = false
                onNavigateToVoiceAiSubTab(AppDestinations.SUBTAB_VOICE_API)
            },
            onOpenDictionary = {
                isPlaygroundSheetOpen = false
                onNavigateToVoiceAiSubTab(AppDestinations.SUBTAB_VOICE_MAIN)
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
                if (!SetupStatsHelper.isApiConfigured(settings, registrySnapshot)) {
                    sandboxError = "尚未配置云端 API 或启用本机离线模型，请前往「模型与 API」完成配置"
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
                        if (settings.inputStatsEnabled && !settings.manualIncognitoMode) {
                            repository.dailyStatsStore.recordTokenUsage(
                                usage = exec.tokenUsage,
                                modelName = exec.modelName,
                                featureKey = TokenFeatureKeys.fromVoiceIntent(
                                    intent = capturedIntent,
                                    isMultimodalAudio = settings.pipelineMode == PipelineMode.MULTIMODAL_DIRECT
                                ),
                                enabled = true,
                                incognito = false
                            )
                        }
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
                                audioFilePath = recorded.wavFile.absolutePath,
                                executionSource = exec.executionSource.name
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
    registrySnapshot: LocalModelRegistrySnapshot,
    readiness: ReadinessState,
    onClickApiCapsule: () -> Unit
) {
    val isFullReady = readiness is ReadinessState.Ready
    val capsuleAccent = if (isFullReady) TypeFreeEmerald else TypeFreeAmber
    val capsuleLabel = when (readiness) {
        is ReadinessState.Ready -> SetupStatsHelper.formatActivePipelineBadge(settings, registrySnapshot)
        is ReadinessState.DegradedRawOnly -> readiness.badge.shortLabel
        is ReadinessState.NotReady -> "配置模型 / API"
    }
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

        // Right Custom API / Offline Engine Status Capsule
        Surface(
            onClick = onClickApiCapsule,
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(
                1.dp,
                capsuleAccent.copy(alpha = if (isFullReady) 0.45f else 0.6f)
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
                        .background(capsuleAccent)
                )
                Text(
                    text = capsuleLabel,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
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
    weekSnapshot: InsightsSnapshot,
    allSnapshot: InsightsSnapshot,
    historyCount: Int = 0,
    onClickOpenInsights: () -> Unit,
    onClickOpenHistory: () -> Unit = {}
) {
    val interactionSource = remember { MutableInteractionSource() }
    val streakDays = allSnapshot.streakDays
    val hasMeasuredWpm = weekSnapshot.hasMeasuredWpm || allSnapshot.hasMeasuredWpm
    val displayWpm = if (weekSnapshot.hasMeasuredWpm) weekSnapshot.averageWpm else allSnapshot.averageWpm
    val displayMultiplier = if (weekSnapshot.hasMeasuredWpm) weekSnapshot.speedMultiplier else allSnapshot.speedMultiplier
    val wpmSubtitle = when {
        !hasMeasuredWpm -> "口述 3 秒以上自动测算语速"
        displayMultiplier > 1.05f -> String.format(
            Locale.US,
            "实测口述语速 · 约为手指打字 %.1f 倍",
            displayMultiplier
        )
        else -> "实测语音口述语速"
    }
    val totalInputCount = allSnapshot.voiceCount + allSnapshot.keyCommits

    Card(
        onClick = onClickOpenInsights,
        interactionSource = interactionSource,
        modifier = Modifier
            .fillMaxWidth()
            .springPressScale(interactionSource = interactionSource),
        shape = ContinuousRoundedCornerShape(24.dp),
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
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Speak, don't type.",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Medium
                        )
                        if (streakDays > 0) {
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = TypeFreeAmber.copy(alpha = 0.14f),
                                border = BorderStroke(1.dp, TypeFreeAmber.copy(alpha = 0.36f))
                            ) {
                                Text(
                                    text = "🔥 连续 $streakDays 天",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = TypeFreeAmber,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(3.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = if (hasMeasuredWpm) "$displayWpm" else "—",
                            fontSize = 38.sp,
                            lineHeight = 40.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = (-1).sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "字/分",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 5.dp)
                        )
                    }
                    Text(
                        text = wpmSubtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    MiniSevenDayWaveform(
                        values = weekSnapshot.mini7DayChars,
                        modifier = Modifier
                            .width(88.dp)
                            .height(48.dp)
                    )
                    Text(
                        text = "近 7 日活跃",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 3-Column Real Insights Metrics Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                HeroMiniStatBox(
                    label = "本周总输出",
                    value = SetupStatsHelper.formatCharCount(weekSnapshot.totalChars),
                    unit = "字",
                    modifier = Modifier.weight(1f)
                )
                HeroMiniStatBox(
                    label = "节省打字时间",
                    value = allSnapshot.savedMinutes.toString(),
                    unit = "分钟",
                    modifier = Modifier.weight(1f)
                )
                if (allSnapshot.hasOfflineSplitData) {
                    HeroMiniStatBox(
                        label = "本机离线护航",
                        value = allSnapshot.offlinePercent.toString(),
                        unit = "%",
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    HeroMiniStatBox(
                        label = "累计输入次数",
                        value = SetupStatsHelper.formatCharCount(totalInputCount),
                        unit = "次",
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onClickOpenHistory)
                        .padding(horizontal = 4.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = TypeFreeIcons.History,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = "识别历史 ($historyCount 条) ›",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onClickOpenInsights)
                        .padding(horizontal = 4.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = TypeFreeIcons.Sparkles,
                        contentDescription = null,
                        tint = TypeFreeEmerald,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = "表达洞察 ›",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun HistoryEntryCard(
    totalCount: Int,
    latestSnippet: String,
    onOpenHistory: () -> Unit
) {
    Card(
        onClick = onOpenHistory,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = TypeFreeIcons.History,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "识别历史",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Text(
                                text = "$totalCount 条",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }
                    Text(
                        text = if (latestSnippet.isNotBlank()) {
                            "最近：$latestSnippet"
                        } else {
                            "查看本机口述、帮写、改写与翻译历史记录"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            FilledTonalButton(
                onClick = onOpenHistory,
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text("查看", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
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
    readiness: ReadinessState,
    hasPendingOfflineRestore: Boolean = false,
    isAccessibilityReady: Boolean = false,
    jevCopilotEnabled: Boolean = true,
    onRequestMic: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onOpenImeSettings: () -> Unit,
    onShowImePicker: () -> Unit,
    onOpenApiConfig: () -> Unit = {},
    onOpenOfflineModels: () -> Unit = onOpenApiConfig,
    onOpenAccessibility: () -> Unit = {}
) {
    val isApiReady = readiness !is ReadinessState.NotReady
    val isDegradedRawOnly = readiness is ReadinessState.DegradedRawOnly
    val completedCount = listOf(isImeEnabled, isImeSelected, isApiReady).count { it }
    val allReady = isImeEnabled && isImeSelected && isApiReady && hasMicPermission
    var manualExpanded by rememberSaveable { mutableStateOf(false) }
    var a11ySoftTipDismissed by rememberSaveable { mutableStateOf(false) }

    if (allReady && !manualExpanded) {
        if (hasPendingOfflineRestore) {
            Surface(
                onClick = onOpenOfflineModels,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = TypeFreeAmber.copy(alpha = 0.10f),
                border = BorderStroke(1.dp, TypeFreeAmber.copy(alpha = 0.40f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "检测到导入配置启用了离线模型，点击前往下载恢复",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "去恢复 ›",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = TypeFreeAmber
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
                title = "配置语音识别与润色引擎",
                subtitle = when (readiness) {
                    is ReadinessState.Ready -> "语音识别与润色引擎已就绪"
                    is ReadinessState.DegradedRawOnly -> readiness.message
                    is ReadinessState.NotReady -> "配置云端 API 密钥，或免 Key 下载开源离线语音"
                },
                isDone = isApiReady,
                buttonLabel = if (isApiReady) "修改配置" else "去配置",
                keepClickableWhenDone = true,
                onClick = onOpenApiConfig
            )

            if (readiness is ReadinessState.NotReady) {
                OutlinedButton(
                    onClick = onOpenOfflineModels,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(11.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "📱 免 Key 体验：下载开源离线语音",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            } else if (hasPendingOfflineRestore) {
                Surface(
                    onClick = onOpenOfflineModels,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    color = TypeFreeAmber.copy(alpha = 0.10f),
                    border = BorderStroke(1.dp, TypeFreeAmber.copy(alpha = 0.40f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "检测到导入配置启用了离线模型，点击前往下载恢复",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "去恢复 ›",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = TypeFreeAmber
                        )
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f))

            WizardStepItem(
                stepNumber = 4,
                title = "进阶可选 · 开启无障碍服务（✦ 军师读屏 / 悬浮球）",
                subtitle = if (isAccessibilityReady) {
                    "无障碍服务已就绪，✦ 军师可按需读取当前聊天上文"
                } else {
                    "使用「✦ 军师」读屏回复或悬浮胶囊时需要开启；纯打字与基础语音可跳过"
                },
                isDone = isAccessibilityReady,
                buttonLabel = if (isAccessibilityReady) "已开启" else "去开启",
                onClick = onOpenAccessibility
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
 * Real Growth Card (✦ 越来越懂你) powered by live end-side Insights signals.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PersonalizationReportCard(
    allSnapshot: InsightsSnapshot,
    dictionarySize: Int,
    onOpenInsights: () -> Unit,
    onOpenStyles: () -> Unit,
    onOpenDictionary: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val topDna = remember(allSnapshot.jevDnaItems) { allSnapshot.jevDnaItems.take(3) }
    val personalLinkMemoryCount = allSnapshot.personalBigramCount + allSnapshot.personalTrigramCount
    val hasEnoughUsageToAutoExpand = (allSnapshot.voiceCount + allSnapshot.keyCommits) >= 5 ||
        allSnapshot.autoLearnedDictCount > 0 ||
        personalLinkMemoryCount > 0 ||
        allSnapshot.jevSessionCount > 0
    var manualExpanded by rememberSaveable { mutableStateOf(false) }
    val expanded = hasEnoughUsageToAutoExpand || manualExpanded

    Card(
        onClick = {
            if (!expanded) {
                manualExpanded = true
            } else {
                onOpenInsights()
            }
        },
        interactionSource = interactionSource,
        modifier = Modifier
            .fillMaxWidth()
            .springPressScale(interactionSource = interactionSource),
        shape = ContinuousRoundedCornerShape(22.dp),
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
                        text = "✦ 越来越懂你",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (expanded) {
                            "本机沉淀你的语体习惯、纠错词库与私人连词记忆"
                        } else {
                            "累计口述满 5 次自动展开语体与个人词库沉淀 · 点击预览"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = if (expanded) "成长洞察" else "展开",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Icon(
                        imageVector = if (expanded) {
                            Icons.AutoMirrored.Filled.KeyboardArrowRight
                        } else {
                            Icons.Default.KeyboardArrowDown
                        },
                        contentDescription = "查看表达洞察",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            SmoothDisclosure(expanded = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Top 3 Jev Style DNA Chips (Active emerald / Observing amber)
                    if (topDna.isNotEmpty()) {
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            topDna.forEach { item ->
                                val accent = if (item.isActive) TypeFreeEmerald else TypeFreeAmber
                                val chipText = if (item.isActive) {
                                    "✓ ${item.title}"
                                } else {
                                    "${item.title} · 观察中 ${item.currentCount}/${item.requiredCount}"
                                }
                                Surface(
                                    shape = RoundedCornerShape(50),
                                    color = accent.copy(alpha = 0.12f),
                                    border = BorderStroke(1.dp, accent.copy(alpha = 0.36f))
                                ) {
                                    Text(
                                        text = chipText,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = accent,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                    )
                                }
                            }
                        }
                    } else {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(11.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f)
                        ) {
                            Text(
                                text = "正在端侧观察你的表达习惯，使用 ✦ Jev 军师或日常输入后将自动提炼专属语体 DNA",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp)
                            )
                        }
                    }

                    // 3 Real End-Side Asset Counters
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        HeroMiniStatBox(
                            label = "自动纠错词条",
                            value = allSnapshot.autoLearnedDictCount.toString(),
                            unit = "条",
                            modifier = Modifier.weight(1f)
                        )
                        HeroMiniStatBox(
                            label = "私人连词记忆",
                            value = SetupStatsHelper.formatCharCount(personalLinkMemoryCount),
                            unit = "条",
                            modifier = Modifier.weight(1f)
                        )
                        HeroMiniStatBox(
                            label = "Jev 关系档案",
                            value = allSnapshot.jevSessionCount.toString(),
                            unit = "个",
                            modifier = Modifier.weight(1f)
                        )
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
