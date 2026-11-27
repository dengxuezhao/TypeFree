package com.typefree.ime.bubble

import com.typefree.ime.network.VoiceLatencyTraceAssembler

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.typefree.ime.R
import com.typefree.ime.TypeFreeApp
import com.typefree.ime.audio.WavAudioRecorder
import com.typefree.ime.data.HistoryRecord
import com.typefree.ime.data.PipelineMode
import com.typefree.ime.data.VoiceIntentMode
import com.typefree.ime.data.stats.TokenFeatureKeys
import com.typefree.ime.ime.ImeLogic
import com.typefree.ime.network.AiPipelineEngine
import com.typefree.ime.network.PipelineStage
import com.typefree.ime.network.asr.StreamingAsrSession
import com.typefree.ime.ui.components.TypeFreeIcons
import com.typefree.ime.ui.theme.TypeFreeEmerald
import com.typefree.ime.ui.theme.TypeFreeRose
import com.typefree.ime.ui.theme.TypeFreeTheme
import com.typefree.ime.ui.theme.resolveDarkTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.roundToInt

sealed interface BubblePhase {
    data object Idle : BubblePhase
    data object Recording : BubblePhase
    data class Processing(val label: String) : BubblePhase
    data class Feedback(val message: String, val isError: Boolean = false) : BubblePhase
}

class TypeFreeBubbleService :
    Service(),
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    private val repository get() = TypeFreeApp.instance.repository
    private val aiEngine get() = TypeFreeApp.instance.aiEngine

    private lateinit var windowManager: WindowManager
    private lateinit var audioRecorder: WavAudioRecorder
    private var orbOverlayView: ComposeView? = null
    private var capsuleOverlayView: ComposeView? = null
    private lateinit var orbWindowParams: WindowManager.LayoutParams
    private lateinit var capsuleWindowParams: WindowManager.LayoutParams

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pipelineJob: Job? = null
    private var activeStreamingSession: StreamingAsrSession? = null
    private var feedbackResetJob: Job? = null
    @Volatile
    private var lockedRecordingFingerprint: InputTargetFingerprint? = null
    @Volatile
    private var lockedRecordingTargetKind: BubbleTargetKind = BubbleTargetKind.NONE

    private val _phase = MutableStateFlow<BubblePhase>(BubblePhase.Idle)
    val phase = _phase.asStateFlow()
    private val _dockedLeft = MutableStateFlow(false)
    private val _capsuleSurfaceVisible = MutableStateFlow(false)
    private val _orbHandoffHidden = MutableStateFlow(false)
    private val _pillExpanded = MutableStateFlow(false)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        savedStateController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        audioRecorder = WavAudioRecorder(this)
        ensureNotificationChannel()

        if (!TypeFreeBubbleController.hasOverlayPermission(this)) {
            stopSelf()
            return
        }

        attachOverlayWindow()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        serviceScope.launch {
            runCatching {
                val snap = repository.settings.value
                aiEngine.prewarmLiveStreamingIfNeeded(snap)
                aiEngine.prewarmEndpoints(snap)
            }
        }
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        TypeFreeBubbleController.setBubbleServiceRunning(true)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!TypeFreeBubbleController.hasOverlayPermission(this) || !repository.settings.value.bubbleEnabled) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        TypeFreeBubbleController.setBubbleServiceRunning(false)
        runCatching { repository.dailyStatsStore.flushAsync() }
        activeStreamingSession?.cancel()
        activeStreamingSession = null
        pipelineJob?.cancel()
        feedbackResetJob?.cancel()
        if (::audioRecorder.isInitialized) {
            serviceScope.launch { audioRecorder.cancelRecording() }
        }
        detachOverlayWindow()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun attachOverlayWindow() {
        if (orbOverlayView != null || capsuleOverlayView != null) return
        val metrics = resources.displayMetrics
        val screenHeight = metrics.heightPixels
        val settings = repository.settings.value
        val edgeMarginPx = (10 * metrics.density).roundToInt()
        val orbSizePx = (64 * metrics.density).roundToInt()
        val capsuleWidthPx = (172 * metrics.density).roundToInt()

        val initialY = (screenHeight * settings.bubbleYRatio)
            .roundToInt()
            .coerceIn((screenHeight * 0.08f).roundToInt(), (screenHeight * 0.85f).roundToInt())

        _dockedLeft.value = settings.bubbleDockLeft
        val initialGravity = Gravity.TOP or if (settings.bubbleDockLeft) Gravity.START else Gravity.END

        // Window 1: Compact 64dp × 64dp Idle Orb Surface (never resizes, never blocks adjacent app touches)
        orbWindowParams = WindowManager.LayoutParams(
            orbSizePx,
            orbSizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = initialGravity
            x = edgeMarginPx
            y = initialY
            windowAnimations = 0
        }

        // Window 2: Fixed 172dp × 64dp Active Capsule Surface (never resizes -> zero MIUI relayoutWindow position drift;
        // View.GONE while Idle so underlying apps receive 100% pass-through touches with zero Android 12 untrusted-touch penalty)
        capsuleWindowParams = WindowManager.LayoutParams(
            capsuleWidthPx,
            orbSizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = initialGravity
            x = edgeMarginPx
            y = initialY
            windowAnimations = 0
        }

        // Make-Before-Break VSync handoff coordinator between fixed Orb surface and fixed Capsule surface
        serviceScope.launch {
            phase.collect { p ->
                if (p !is BubblePhase.Idle) {
                    _capsuleSurfaceVisible.value = true
                    awaitChoreographerFrame()
                    awaitChoreographerFrame()
                    _orbHandoffHidden.value = true
                    _pillExpanded.value = true
                } else {
                    _pillExpanded.value = false
                    delay(175L)
                    if (_phase.value is BubblePhase.Idle) {
                        _orbHandoffHidden.value = false
                        awaitChoreographerFrame()
                        awaitChoreographerFrame()
                        if (_phase.value is BubblePhase.Idle) {
                            _capsuleSurfaceVisible.value = false
                        }
                    }
                }
            }
        }

        val orbView = ComposeView(this).apply {
            id = android.R.id.button1
            setViewTreeLifecycleOwner(this@TypeFreeBubbleService)
            setViewTreeViewModelStoreOwner(this@TypeFreeBubbleService)
            setViewTreeSavedStateRegistryOwner(this@TypeFreeBubbleService)
            setContent {
                val currSettings by repository.settings.collectAsState()
                val imeVisible by repository.isImeWindowVisible.collectAsState()
                val focusedSnap by TypeFreeBubbleController.focusedInput.collectAsState()
                val a11yConnected by TypeFreeBubbleController.isAccessibilityConnected.collectAsState()
                val forcePreview by TypeFreeBubbleController.forcePreviewVisible.collectAsState()
                val hostInForeground by TypeFreeBubbleController.isHostAppInForeground.collectAsState()
                val currPhase by phase.collectAsState()
                val handoffHidden by _orbHandoffHidden.collectAsState()

                val baseAllowed = !imeVisible &&
                    !focusedSnap.isSensitivePassword &&
                    (currPhase != BubblePhase.Idle ||
                        forcePreview ||
                        (!hostInForeground && !a11yConnected) ||
                        (!hostInForeground && focusedSnap.hasBubbleTarget))
                val showOrb = baseAllowed && !handoffHidden

                LaunchedEffect(showOrb) {
                    this@apply.visibility = if (showOrb) View.VISIBLE else View.GONE
                }

                if (showOrb) {
                    TypeFreeTheme(
                        themePreference = currSettings.themePreference,
                        appLanguage = currSettings.appLanguage
                    ) {
                        val isDark = resolveDarkTheme(currSettings.themePreference)
                        BubbleIdleOrbWidget(
                            isDark = isDark,
                            hasSelectedText = focusedSnap.selectedText.isNotBlank(),
                            onTapPrimary = ::handleBubbleTap,
                            onDragDelta = ::moveWindowBy,
                            onDragEnd = ::snapWindowToEdge
                        )
                    }
                }
            }
        }

        val capView = ComposeView(this).apply {
            id = android.R.id.button2
            visibility = View.GONE
            setViewTreeLifecycleOwner(this@TypeFreeBubbleService)
            setViewTreeViewModelStoreOwner(this@TypeFreeBubbleService)
            setViewTreeSavedStateRegistryOwner(this@TypeFreeBubbleService)
            setContent {
                val currSettings by repository.settings.collectAsState()
                val imeVisible by repository.isImeWindowVisible.collectAsState()
                val focusedSnap by TypeFreeBubbleController.focusedInput.collectAsState()
                val currPhase by phase.collectAsState()
                val isDockedLeft by _dockedLeft.collectAsState()
                val capVisible by _capsuleSurfaceVisible.collectAsState()
                val pillExpanded by _pillExpanded.collectAsState()
                val amplitudes by audioRecorder.amplitudes.collectAsState()
                val elapsedMs by audioRecorder.elapsedMs.collectAsState()

                val showCap = capVisible && !imeVisible && !focusedSnap.isSensitivePassword

                LaunchedEffect(showCap) {
                    this@apply.visibility = if (showCap) View.VISIBLE else View.GONE
                }

                if (showCap) {
                    TypeFreeTheme(
                        themePreference = currSettings.themePreference,
                        appLanguage = currSettings.appLanguage
                    ) {
                        val isDark = resolveDarkTheme(currSettings.themePreference)
                        BubbleActiveCapsuleWidget(
                            isDark = isDark,
                            dockLeft = isDockedLeft,
                            pillExpanded = pillExpanded,
                            phase = currPhase,
                            amplitudes = amplitudes,
                            elapsedMs = elapsedMs,
                            onTapPrimary = ::handleBubbleTap,
                            onCancel = ::cancelBubbleAction,
                            onDragDelta = ::moveWindowBy,
                            onDragEnd = ::snapWindowToEdge
                        )
                    }
                }
            }
        }

        try {
            windowManager.addView(orbView, orbWindowParams)
            orbOverlayView = orbView
            windowManager.addView(capView, capsuleWindowParams)
            capsuleOverlayView = capView
        } catch (_: Exception) {
            detachOverlayWindow()
            stopSelf()
        }
    }

    private suspend fun awaitChoreographerFrame() {
        suspendCancellableCoroutine<Unit> { cont ->
            val callback = Choreographer.FrameCallback {
                if (cont.isActive) cont.resume(Unit)
            }
            Choreographer.getInstance().postFrameCallback(callback)
            cont.invokeOnCancellation {
                Choreographer.getInstance().removeFrameCallback(callback)
            }
        }
    }

    private fun detachOverlayWindow() {
        orbOverlayView?.let { v ->
            orbOverlayView = null
            runCatching { windowManager.removeView(v) }
        }
        capsuleOverlayView?.let { v ->
            capsuleOverlayView = null
            runCatching { windowManager.removeView(v) }
        }
    }

    private fun moveWindowBy(dx: Float, dy: Float) {
        val orb = orbOverlayView ?: return
        val metrics = resources.displayMetrics
        val orbW = (64 * metrics.density).roundToInt()
        val isRightAnchored = (orbWindowParams.gravity and Gravity.END) == Gravity.END
        val nextX = if (isRightAnchored) {
            orbWindowParams.x - dx.roundToInt()
        } else {
            orbWindowParams.x + dx.roundToInt()
        }
        val clampedX = nextX.coerceIn(0, (metrics.widthPixels - orbW).coerceAtLeast(0))
        val clampedY = (orbWindowParams.y + dy.roundToInt())
            .coerceIn(
                (metrics.heightPixels * 0.05f).roundToInt(),
                (metrics.heightPixels * 0.88f).roundToInt()
            )
        orbWindowParams.x = clampedX
        orbWindowParams.y = clampedY
        capsuleWindowParams.x = clampedX
        capsuleWindowParams.y = clampedY
        runCatching { windowManager.updateViewLayout(orb, orbWindowParams) }
        capsuleOverlayView?.let { cap ->
            runCatching { windowManager.updateViewLayout(cap, capsuleWindowParams) }
        }
    }

    private fun snapWindowToEdge() {
        val orb = orbOverlayView ?: return
        val metrics = resources.displayMetrics
        val screenWidth = metrics.widthPixels
        val screenHeight = metrics.heightPixels
        val edgeMarginPx = (10 * metrics.density).roundToInt()
        val orbW = (64 * metrics.density).roundToInt()
        val isRightAnchored = (orbWindowParams.gravity and Gravity.END) == Gravity.END

        val centerX = if (isRightAnchored) {
            screenWidth - orbWindowParams.x - orbW / 2
        } else {
            orbWindowParams.x + orbW / 2
        }
        val dockLeft = centerX < screenWidth / 2
        _dockedLeft.value = dockLeft
        val newGravity = Gravity.TOP or if (dockLeft) Gravity.START else Gravity.END
        orbWindowParams.gravity = newGravity
        orbWindowParams.x = edgeMarginPx
        capsuleWindowParams.gravity = newGravity
        capsuleWindowParams.x = edgeMarginPx
        runCatching { windowManager.updateViewLayout(orb, orbWindowParams) }
        capsuleOverlayView?.let { cap ->
            runCatching { windowManager.updateViewLayout(cap, capsuleWindowParams) }
        }
        val yRatio = (orbWindowParams.y.toFloat() / screenHeight.coerceAtLeast(1)).coerceIn(0.08f, 0.88f)
        repository.updateSettings {
            it.copy(bubbleDockLeft = dockLeft, bubbleYRatio = yRatio)
        }
    }

    private fun handleBubbleTap() {
        when (_phase.value) {
            is BubblePhase.Idle, is BubblePhase.Feedback -> startBubbleRecording()
            is BubblePhase.Recording -> finishBubbleRecordingAndInject()
            is BubblePhase.Processing -> Unit
        }
    }

    private fun startBubbleRecording() {
        feedbackResetJob?.cancel()
        if (!audioRecorder.hasRecordPermission()) {
            vibrate(strong = true)
            showTemporaryFeedback("请先授予麦克风权限", isError = true)
            return
        }

        // Lock target fingerprint from the latest background-scanned snapshot so a mid-recording
        // page/chat switch can never inject text into the wrong chat window.
        val snapAtRecordStart = TypeFreeBubbleController.focusedInput.value
        lockedRecordingFingerprint = snapAtRecordStart.targetFingerprint
        lockedRecordingTargetKind = snapAtRecordStart.targetKind
        TypeFreeAccessibilityService.activeInstance?.scheduleFocusedInputRefresh(
            allowMultiWindowScan = true,
            delayMs = 0L
        )
        promoteToMicrophoneForeground()

        activeStreamingSession?.cancel()
        activeStreamingSession = null
        val settingsSnap = repository.settings.value
        val streamSession = aiEngine.openLiveStreamingSession(settingsSnap)

        val res = audioRecorder.startRecording(
            scope = serviceScope,
            onStreamChunk = if (streamSession != null) {
                { pcmChunk -> streamSession.sendPcmFrame(pcmChunk) }
            } else {
                null
            },
            pauseMediaPlayback = settingsSnap.pauseMediaWhileRecording,
            whisperMode = settingsSnap.whisperModeEnabled
        )
        res.fold(
            onSuccess = {
                activeStreamingSession = streamSession
                serviceScope.launch {
                    aiEngine.prewarmEndpoints(settingsSnap)
                }
                vibrate(strong = true)
                _phase.value = BubblePhase.Recording
            },
            onFailure = { err ->
                streamSession?.cancel()
                demoteFromForeground()
                vibrate(strong = true)
                showTemporaryFeedback(err.message ?: "无法开始录音", isError = true)
            }
        )
    }

    private fun finishBubbleRecordingAndInject() {
        if (_phase.value !is BubblePhase.Recording) return
        val releaseStartNano = System.nanoTime()
        val liveSession = activeStreamingSession
        activeStreamingSession = null
        vibrate(strong = true)
        pipelineJob?.cancel()
        pipelineJob = serviceScope.launch {
            val stopIoStartNano = System.nanoTime()
            val stopRes = audioRecorder.stopRecording()
            val stopIoMs = ((System.nanoTime() - stopIoStartNano) / 1_000_000L).coerceAtLeast(1L)
            demoteFromForeground()
            stopRes.fold(
                onSuccess = { recorded ->
                    val settings = repository.settings.value
                    var preTranscribed: String? = null
                    var tailWaitMs = 0L

                    if (liveSession != null) {
                        _phase.value = BubblePhase.Processing("收尾中…")
                        val tailStart = System.currentTimeMillis()
                        val streamRes = liveSession.finishAndAwaitFinal()
                        tailWaitMs = (System.currentTimeMillis() - tailStart).coerceAtLeast(0L)

                        val streamErr = streamRes.exceptionOrNull()
                        if (streamErr is IllegalStateException) {
                            vibrate(strong = true)
                            showTemporaryFeedback((streamErr.message ?: "API 未配置").take(18), isError = true)
                            return@launch
                        }
                        val candidate = streamRes.getOrNull()?.trim().orEmpty()
                        if (candidate.isNotEmpty() && !AiPipelineEngine.isHallucinatedSilence(candidate)) {
                            preTranscribed = candidate
                        }
                    }

                    if (preTranscribed == null && !recorded.hasValidSpeech) {
                        liveSession?.cancel()
                        vibrate(strong = true)
                        showTemporaryFeedback("未检测到说话声", isError = true)
                        return@launch
                    }

                    val lockedFingerprint = lockedRecordingFingerprint
                    val lockedKind = lockedRecordingTargetKind
                    val liveSnap = TypeFreeAccessibilityService.activeInstance?.refreshFocusedInputStateAsync()
                        ?: TypeFreeBubbleController.focusedInput.value
                    val pkg = liveSnap.packageName
                    val appName = repository.resolveAppName(pkg)
                    val (activeStyle, _) = repository.resolveActiveStyle(pkg, liveSnap.inputType)
                    val selectedText = if (liveSnap.targetKind == BubbleTargetKind.FOCUSED) liveSnap.selectedText else ""
                    val surroundingContext = if (liveSnap.targetKind == BubbleTargetKind.FOCUSED) liveSnap.surroundingBeforeCursor else ""
                    val intentMode = if (selectedText.isNotBlank()) {
                        VoiceIntentMode.SPEAK_TO_EDIT
                    } else {
                        VoiceIntentMode.DICTATE
                    }

                    _phase.value = BubblePhase.Processing(
                        when {
                            settings.pipelineMode == PipelineMode.MULTIMODAL_DIRECT -> "直出中…"
                            preTranscribed != null -> if (selectedText.isNotBlank()) "改写中…" else "润色中…"
                            else -> "识别中…"
                        }
                    )

                    val result = aiEngine.processVoiceAudio(
                        wavFile = recorded.wavFile,
                        settings = settings,
                        activeStyle = activeStyle,
                        targetAppName = appName,
                        selectedText = selectedText,
                        surroundingBeforeCursor = surroundingContext,
                        intentMode = intentMode,
                        preTranscribedText = preTranscribed,
                        sttTailWaitMs = tailWaitMs,
                        onStageChanged = { stage ->
                            _phase.value = BubblePhase.Processing(
                                when (stage) {
                                    PipelineStage.TRANSCRIBING_STT -> "识别中…"
                                    PipelineStage.POLISHING_LLM -> if (selectedText.isNotBlank()) "改写中…" else "润色中…"
                                    PipelineStage.MULTIMODAL_PROCESSING -> "生成中…"
                                }
                            )
                        }
                    )

                    result.fold(
                        onSuccess = { exec ->
                            val finalOut = exec.finalOutput
                            val commitStartNano = System.nanoTime()
                            val a11y = TypeFreeAccessibilityService.activeInstance
                            val insertOutcome = a11y?.insertPolishedTextAsync(
                                polishedText = finalOut,
                                expectedFingerprint = lockedFingerprint,
                                expectedTargetKind = lockedKind
                            ) ?: BubbleInsertOutcome.COPIED_FALLBACK
                            val inserted = insertOutcome == BubbleInsertOutcome.INSERTED
                            if (!inserted) {
                                copyToClipboard(finalOut)
                            }
                            val commitDoneNano = System.nanoTime()
                            val commitMs = ((commitDoneNano - commitStartNano) / 1_000_000L).coerceAtLeast(1L)
                            val postReleaseE2eMs = ((commitDoneNano - releaseStartNano) / 1_000_000L).coerceAtLeast(1L)
                            val isIncognito = settings.manualIncognitoMode ||
                                liveSnap.isSensitivePassword ||
                                ImeLogic.isIncognitoImeOptions(
                                    imeOptions = 0,
                                    inputType = liveSnap.inputType,
                                    manualIncognitoMode = settings.manualIncognitoMode
                                )
                            if (isIncognito) {
                                runCatching { recorded.wavFile.delete() }
                            } else {
                                val resolvedHostName = if (appName == "未知应用") "悬浮胶囊" else appName
                                val voiceTrace = VoiceLatencyTraceAssembler.assemble(
                                    micInitMs = 0L,
                                    recordingDurationMs = recorded.durationMs,
                                    firstPartialMs = 0L,
                                    hadStreamingSession = liveSession != null,
                                    stopIoMs = stopIoMs,
                                    commitMs = commitMs,
                                    measuredPostReleaseMs = postReleaseE2eMs,
                                    exec = exec,
                                    hostAppName = resolvedHostName
                                )
                                if (settings.inputStatsEnabled) {
                                    repository.dailyStatsStore.recordTokenUsage(
                                        usage = exec.tokenUsage,
                                        modelName = exec.modelName,
                                        featureKey = TokenFeatureKeys.fromVoiceIntent(
                                            intent = intentMode,
                                            isMultimodalAudio = settings.pipelineMode == PipelineMode.MULTIMODAL_DIRECT
                                        ),
                                        enabled = true,
                                        incognito = false
                                    )
                                }
                                val archivedWav = WavAudioRecorder.archiveWavForHistory(
                                    baseDir = filesDir,
                                    wavFile = recorded.wavFile,
                                    retention = settings.historyRetention
                                )
                                repository.addHistory(
                                    HistoryRecord(
                                        targetAppName = resolvedHostName,
                                        targetPackage = pkg,
                                        styleName = activeStyle.name,
                                        isSpeakToEdit = selectedText.isNotBlank(),
                                        intentMode = intentMode,
                                        originalSelectedText = selectedText,
                                        rawTranscript = exec.rawTranscript,
                                        polishedText = finalOut,
                                        durationMs = recorded.durationMs,
                                        latencyMs = voiceTrace.postReleaseTotalMs,
                                        modeLabel = "胶囊 · ${exec.modeSummary}",
                                        audioFilePath = archivedWav.absolutePath,
                                        executionSource = exec.executionSource.name,
                                        voiceTrace = voiceTrace
                                    )
                                )
                            }
                            vibrate(strong = false)
                            runCatching { aiEngine.prewarmLiveStreamingIfNeeded(settings) }
                            showTemporaryFeedback(
                                when (insertOutcome) {
                                    BubbleInsertOutcome.INSERTED -> "✓ 已上屏"
                                    BubbleInsertOutcome.TARGET_MISMATCH_COPIED -> "已切页 · 已复制剪贴板"
                                    BubbleInsertOutcome.COPIED_FALLBACK -> "✓ 已复制剪贴板"
                                },
                                isError = false
                            )
                        },
                        onFailure = { err ->
                            if (err is CancellationException) return@launch
                            vibrate(strong = true)
                            showTemporaryFeedback(
                                (err.message ?: "处理失败").take(18),
                                isError = true
                            )
                        }
                    )
                },
                onFailure = { err ->
                    liveSession?.cancel()
                    showTemporaryFeedback((err.message ?: "录音太短").take(16), isError = true)
                }
            )
        }
    }

    private fun cancelBubbleAction() {
        vibrate(strong = false)
        activeStreamingSession?.cancel()
        activeStreamingSession = null
        pipelineJob?.cancel()
        feedbackResetJob?.cancel()
        serviceScope.launch { audioRecorder.cancelRecording() }
        demoteFromForeground()
        _phase.value = BubblePhase.Idle
    }

    private fun showTemporaryFeedback(message: String, isError: Boolean) {
        feedbackResetJob?.cancel()
        _phase.value = BubblePhase.Feedback(message, isError)
        feedbackResetJob = serviceScope.launch {
            delay(1800L)
            if (_phase.value is BubblePhase.Feedback) {
                _phase.value = BubblePhase.Idle
            }
        }
    }

    private fun promoteToMicrophoneForeground() {
        try {
            val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("TypeFree 悬浮语音胶囊")
                .setContentText("正在聆听语音输入…")
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (_: Exception) {
        }
    }

    private fun demoteFromForeground() {
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val channel = NotificationChannel(
                CHANNEL_ID,
                "TypeFree 语音录音服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保障悬浮胶囊在跨应用时稳定录音"
                setShowBadge(false)
            }
            mgr.createNotificationChannel(channel)
        }
    }

    private fun copyToClipboard(text: String) {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
            cm.setPrimaryClip(ClipData.newPlainText("TypeFree", text))
        } catch (_: Exception) {
        }
    }

    private fun vibrate(strong: Boolean) {
        if (!repository.settings.value.hapticFeedback) return
        try {
            val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            vibrator?.vibrate(
                VibrationEffect.createOneShot(
                    if (strong) 22L else 10L,
                    if (strong) 170 else 90
                )
            )
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val CHANNEL_ID = "typefree_bubble_mic"
        private const val NOTIFICATION_ID = 2100
    }
}

@Composable
private fun BubbleIdleOrbWidget(
    isDark: Boolean,
    hasSelectedText: Boolean,
    onTapPrimary: () -> Unit,
    onDragDelta: (Float, Float) -> Unit,
    onDragEnd: () -> Unit
) {
    val primaryFill = if (isDark) Color(0xFFF4F4F6) else Color(0xFF141417)
    val contentFg = if (isDark) Color(0xFF141417) else Color(0xFFFFFFFF)
    val haloRing = if (isDark) Color(0x38FFFFFF) else Color(0x22000000)

    Box(
        modifier = Modifier
            .size(64.dp)
            .padding(4.dp)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragEnd = { onDragEnd() },
                    onDragCancel = { onDragEnd() },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        onDragDelta(dragAmount.x, dragAmount.y)
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(haloRing)
                .padding(4.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .shadow(6.dp, CircleShape)
                    .clip(CircleShape)
                    .background(primaryFill)
                    .clickable { onTapPrimary() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = TypeFreeIcons.VoiceWaveform,
                    contentDescription = "TypeFree 悬浮语音",
                    tint = contentFg,
                    modifier = Modifier.size(24.dp)
                )
            }
            if (hasSelectedText) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(2.dp)
                        .size(11.dp)
                        .clip(CircleShape)
                        .background(TypeFreeEmerald)
                        .border(1.5.dp, Color.White, CircleShape)
                )
            }
        }
    }
}

@Composable
private fun BubbleActiveCapsuleWidget(
    isDark: Boolean,
    dockLeft: Boolean,
    pillExpanded: Boolean,
    phase: BubblePhase,
    amplitudes: List<Float>,
    elapsedMs: Long,
    onTapPrimary: () -> Unit,
    onCancel: () -> Unit,
    onDragDelta: (Float, Float) -> Unit,
    onDragEnd: () -> Unit
) {
    val primaryFill = if (isDark) Color(0xFFF4F4F6) else Color(0xFF141417)
    val contentFg = if (isDark) Color(0xFF141417) else Color(0xFFFFFFFF)
    val haloRing = if (isDark) Color(0x38FFFFFF) else Color(0x22000000)

    val isActive = phase !is BubblePhase.Idle
    var lastActivePhase by remember { mutableStateOf<BubblePhase>(BubblePhase.Recording) }
    if (isActive) {
        lastActivePhase = phase
    }

    val expandProgress by animateFloatAsState(
        targetValue = if (pillExpanded) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (pillExpanded) 185 else 155,
            easing = FastOutSlowInEasing
        ),
        label = "BubbleCapsuleProgress"
    )

    val displayActivePhase = if (isActive) phase else lastActivePhase
    val expandedPillWidth = when (displayActivePhase) {
        is BubblePhase.Recording -> 156.dp
        is BubblePhase.Processing -> 148.dp
        is BubblePhase.Feedback -> 152.dp
        else -> 156.dp
    }
    val currentPillWidth = 48.dp + (expandedPillWidth - 48.dp) * expandProgress
    val outerPad = (4f * (1f - expandProgress)).dp
    val pillBg = if (displayActivePhase is BubblePhase.Feedback && displayActivePhase.isError && expandProgress > 0.25f) {
        TypeFreeRose
    } else {
        primaryFill
    }
    val activeFg = if (displayActivePhase is BubblePhase.Feedback && displayActivePhase.isError) {
        Color.White
    } else {
        contentFg
    }
    val dockAlignment = if (dockLeft) Alignment.CenterStart else Alignment.CenterEnd
    val orbAlpha = (1f - expandProgress * 2.4f).coerceIn(0f, 1f)
    val detailAlpha = ((expandProgress - 0.35f) / 0.65f).coerceIn(0f, 1f)

    Box(
        modifier = Modifier
            .width(172.dp)
            .height(64.dp)
            .padding(horizontal = 4.dp)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragEnd = { onDragEnd() },
                    onDragCancel = { onDragEnd() },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        onDragDelta(dragAmount.x, dragAmount.y)
                    }
                )
            },
        contentAlignment = dockAlignment
    ) {
        Box(
            modifier = Modifier
                .height(48.dp + outerPad * 2)
                .width(currentPillWidth + outerPad * 2)
                .clip(RoundedCornerShape(50))
                .background(haloRing.copy(alpha = haloRing.alpha * (1f - expandProgress)))
                .padding(outerPad),
            contentAlignment = dockAlignment
        ) {
            Box(
                modifier = Modifier
                    .width(currentPillWidth)
                    .height(48.dp)
                    .shadow(6.dp, RoundedCornerShape(24.dp))
                    .clip(RoundedCornerShape(24.dp))
                    .background(pillBg)
                    .clickable { onTapPrimary() },
                contentAlignment = Alignment.Center
            ) {
                if (orbAlpha > 0.01f) {
                    Box(
                        modifier = Modifier
                            .align(dockAlignment)
                            .size(48.dp)
                            .graphicsLayer { alpha = orbAlpha },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = TypeFreeIcons.VoiceWaveform,
                            contentDescription = "TypeFree 悬浮语音",
                            tint = contentFg,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                if (detailAlpha > 0.01f) {
                    Box(
                        modifier = Modifier
                            .requiredWidth(expandedPillWidth)
                            .height(48.dp)
                            .graphicsLayer { alpha = detailAlpha },
                        contentAlignment = Alignment.Center
                    ) {
                        when (displayActivePhase) {
                            is BubblePhase.Recording -> {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    val sample = amplitudes.takeLast(5).let {
                                        if (it.size < 5) List(5 - it.size) { 0.25f } + it else it
                                    }
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(2.5.dp)
                                    ) {
                                        sample.forEachIndexed { idx, amp ->
                                            val baseFactor = if (idx == 2) 1.0f else if (idx == 1 || idx == 3) 0.75f else 0.5f
                                            val barH = (6f + (amp.coerceIn(0.08f, 1f) * 16f * baseFactor)).dp
                                            Box(
                                                modifier = Modifier
                                                    .width(2.5.dp)
                                                    .height(barH)
                                                    .clip(CircleShape)
                                                    .background(activeFg)
                                            )
                                        }
                                    }

                                    Text(
                                        text = ImeLogic.formatElapsedTimer(elapsedMs),
                                        color = activeFg,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        style = TextStyle(fontFeatureSettings = "tnum"),
                                        maxLines = 1,
                                        softWrap = false
                                    )

                                    Box(
                                        modifier = Modifier
                                            .size(22.dp)
                                            .clip(CircleShape)
                                            .background(activeFg.copy(alpha = 0.16f))
                                            .clickable { onCancel() },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = TypeFreeIcons.Close,
                                            contentDescription = "取消",
                                            tint = activeFg,
                                            modifier = Modifier.size(13.dp)
                                        )
                                    }
                                }
                            }

                            is BubblePhase.Processing -> {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(15.dp),
                                        strokeWidth = 2.dp,
                                        color = activeFg
                                    )
                                    Text(
                                        text = displayActivePhase.label,
                                        color = activeFg,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            }

                            is BubblePhase.Feedback -> {
                                Row(
                                    modifier = Modifier.padding(horizontal = 13.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = displayActivePhase.message,
                                        color = activeFg,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                    Spacer(modifier = Modifier.width(2.dp))
                                }
                            }

                            is BubblePhase.Idle -> Unit
                        }
                    }
                }
            }
        }
    }
}
