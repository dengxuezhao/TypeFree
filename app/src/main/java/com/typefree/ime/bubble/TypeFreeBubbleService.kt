package com.typefree.ime.bubble

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
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
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
    private var overlayView: ComposeView? = null
    private lateinit var windowParams: WindowManager.LayoutParams

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pipelineJob: Job? = null
    private var activeStreamingSession: StreamingAsrSession? = null
    private var feedbackResetJob: Job? = null

    private val _phase = MutableStateFlow<BubblePhase>(BubblePhase.Idle)
    val phase = _phase.asStateFlow()

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
        if (overlayView != null) return
        val metrics = resources.displayMetrics
        val screenWidth = metrics.widthPixels
        val screenHeight = metrics.heightPixels
        val settings = repository.settings.value
        val edgeMarginPx = (10 * metrics.density).roundToInt()
        val bubbleWidthPx = (62 * metrics.density).roundToInt()

        val initialX = if (settings.bubbleDockLeft) {
            edgeMarginPx
        } else {
            (screenWidth - bubbleWidthPx - edgeMarginPx).coerceAtLeast(0)
        }
        val initialY = (screenHeight * settings.bubbleYRatio)
            .roundToInt()
            .coerceIn((screenHeight * 0.08f).roundToInt(), (screenHeight * 0.85f).roundToInt())

        windowParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = initialX
            y = initialY
        }

        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@TypeFreeBubbleService)
            setViewTreeViewModelStoreOwner(this@TypeFreeBubbleService)
            setViewTreeSavedStateRegistryOwner(this@TypeFreeBubbleService)
            setContent {
                val currSettings by repository.settings.collectAsState()
                val imeVisible by repository.isImeWindowVisible.collectAsState()
                val focusedSnap by TypeFreeBubbleController.focusedInput.collectAsState()
                val a11yConnected by TypeFreeBubbleController.isAccessibilityConnected.collectAsState()
                val forcePreview by TypeFreeBubbleController.forcePreviewVisible.collectAsState()
                val currPhase by phase.collectAsState()
                val amplitudes by audioRecorder.amplitudes.collectAsState()
                val elapsedMs by audioRecorder.elapsedMs.collectAsState()

                val shouldShow = !imeVisible &&
                    !focusedSnap.isSensitivePassword &&
                    (currPhase != BubblePhase.Idle ||
                        forcePreview ||
                        !a11yConnected ||
                        focusedSnap.hasEditableFocus)

                LaunchedEffect(shouldShow) {
                    this@apply.visibility = if (shouldShow) View.VISIBLE else View.GONE
                }

                if (shouldShow) {
                    TypeFreeTheme(themePreference = currSettings.themePreference) {
                        val isDark = resolveDarkTheme(currSettings.themePreference)
                        BubbleOverlayWidget(
                            isDark = isDark,
                            phase = currPhase,
                            hasSelectedText = focusedSnap.selectedText.isNotBlank(),
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
            windowManager.addView(view, windowParams)
            overlayView = view
        } catch (_: Exception) {
            stopSelf()
        }
    }

    private fun detachOverlayWindow() {
        val view = overlayView ?: return
        overlayView = null
        try {
            windowManager.removeView(view)
        } catch (_: Exception) {
        }
    }

    private fun moveWindowBy(dx: Float, dy: Float) {
        val view = overlayView ?: return
        val metrics = resources.displayMetrics
        windowParams.x = (windowParams.x + dx.roundToInt())
            .coerceIn(0, (metrics.widthPixels - view.width.coerceAtLeast(100)).coerceAtLeast(0))
        windowParams.y = (windowParams.y + dy.roundToInt())
            .coerceIn(
                (metrics.heightPixels * 0.05f).roundToInt(),
                (metrics.heightPixels * 0.88f).roundToInt()
            )
        try {
            windowManager.updateViewLayout(view, windowParams)
        } catch (_: Exception) {
        }
    }

    private fun snapWindowToEdge() {
        val view = overlayView ?: return
        val metrics = resources.displayMetrics
        val screenWidth = metrics.widthPixels
        val screenHeight = metrics.heightPixels
        val edgeMarginPx = (10 * metrics.density).roundToInt()
        val viewW = view.width.takeIf { it > 0 } ?: (58 * metrics.density).roundToInt()

        val centerX = windowParams.x + viewW / 2
        val dockLeft = centerX < screenWidth / 2
        windowParams.x = if (dockLeft) {
            edgeMarginPx
        } else {
            (screenWidth - viewW - edgeMarginPx).coerceAtLeast(0)
        }
        try {
            windowManager.updateViewLayout(view, windowParams)
        } catch (_: Exception) {
        }
        val yRatio = (windowParams.y.toFloat() / screenHeight.coerceAtLeast(1)).coerceIn(0.08f, 0.88f)
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

        // Refresh accessibility focus snapshot right before recording
        TypeFreeAccessibilityService.activeInstance?.refreshFocusedInputState()
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
        val liveSession = activeStreamingSession
        activeStreamingSession = null
        vibrate(strong = true)
        pipelineJob?.cancel()
        pipelineJob = serviceScope.launch {
            val stopRes = audioRecorder.stopRecording()
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

                    val liveSnap = TypeFreeAccessibilityService.activeInstance?.refreshFocusedInputState()
                        ?: TypeFreeBubbleController.focusedInput.value
                    val pkg = liveSnap.packageName
                    val appName = repository.resolveAppName(pkg)
                    val (activeStyle, _) = repository.resolveActiveStyle(pkg, liveSnap.inputType)
                    val selectedText = liveSnap.selectedText
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
                        surroundingBeforeCursor = liveSnap.surroundingBeforeCursor,
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
                            val a11y = TypeFreeAccessibilityService.activeInstance
                            val inserted = a11y?.insertPolishedText(finalOut) ?: false
                            if (!inserted) {
                                copyToClipboard(finalOut)
                            }
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
                                val archivedWav = WavAudioRecorder.archiveWavForHistory(
                                    baseDir = filesDir,
                                    wavFile = recorded.wavFile,
                                    retention = settings.historyRetention
                                )
                                repository.addHistory(
                                    HistoryRecord(
                                        targetAppName = if (appName == "未知应用") "悬浮胶囊" else appName,
                                        targetPackage = pkg,
                                        styleName = activeStyle.name,
                                        isSpeakToEdit = selectedText.isNotBlank(),
                                        intentMode = intentMode,
                                        originalSelectedText = selectedText,
                                        rawTranscript = exec.rawTranscript,
                                        polishedText = finalOut,
                                        durationMs = recorded.durationMs,
                                        latencyMs = exec.latencyMs,
                                        modeLabel = "胶囊 · ${exec.modeSummary}",
                                        audioFilePath = archivedWav.absolutePath
                                    )
                                )
                            }
                            vibrate(strong = false)
                            runCatching { aiEngine.prewarmLiveStreamingIfNeeded(settings) }
                            showTemporaryFeedback(
                                if (inserted) "✓ 已上屏" else "✓ 已复制剪贴板",
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
private fun BubbleOverlayWidget(
    isDark: Boolean,
    phase: BubblePhase,
    hasSelectedText: Boolean,
    amplitudes: List<Float>,
    elapsedMs: Long,
    onTapPrimary: () -> Unit,
    onCancel: () -> Unit,
    onDragDelta: (Float, Float) -> Unit,
    onDragEnd: () -> Unit
) {
    // Authentic `bubble_hero_light.png` / `bubble_hero_dark.png` colors:
    // Light mode -> Pure charcoal (#141417) circle + white waveform + translucent light outer halo
    // Dark mode  -> Crisp off-white (#F4F4F6) circle + dark waveform + translucent dark outer halo
    val primaryFill = if (isDark) Color(0xFFF4F4F6) else Color(0xFF141417)
    val contentFg = if (isDark) Color(0xFF141417) else Color(0xFFFFFFFF)
    val haloRing = if (isDark) Color(0x38FFFFFF) else Color(0x22000000)

    Box(
        modifier = Modifier
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
            }
    ) {
        AnimatedContent(
            targetState = phase,
            transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(120)) },
            label = "BubblePhaseAnim"
        ) { targetPhase ->
            when (targetPhase) {
                is BubblePhase.Idle -> {
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

                is BubblePhase.Recording -> {
                    Row(
                        modifier = Modifier
                            .height(48.dp)
                            .shadow(8.dp, RoundedCornerShape(24.dp))
                            .clip(RoundedCornerShape(24.dp))
                            .background(primaryFill)
                            .clickable { onTapPrimary() }
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Mini live symmetrical 5-bar waveform
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
                                        .background(contentFg)
                                )
                            }
                        }

                        Text(
                            text = ImeLogic.formatElapsedTimer(elapsedMs),
                            color = contentFg,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )

                        Box(
                            modifier = Modifier
                                .size(22.dp)
                                .clip(CircleShape)
                                .background(contentFg.copy(alpha = 0.16f))
                                .clickable { onCancel() },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = TypeFreeIcons.Close,
                                contentDescription = "取消",
                                tint = contentFg,
                                modifier = Modifier.size(13.dp)
                            )
                        }
                    }
                }

                is BubblePhase.Processing -> {
                    Row(
                        modifier = Modifier
                            .height(44.dp)
                            .shadow(6.dp, RoundedCornerShape(22.dp))
                            .clip(RoundedCornerShape(22.dp))
                            .background(primaryFill)
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(15.dp),
                            strokeWidth = 2.dp,
                            color = contentFg
                        )
                        Text(
                            text = targetPhase.label,
                            color = contentFg,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                is BubblePhase.Feedback -> {
                    Row(
                        modifier = Modifier
                            .height(42.dp)
                            .shadow(6.dp, RoundedCornerShape(21.dp))
                            .clip(RoundedCornerShape(21.dp))
                            .background(if (targetPhase.isError) TypeFreeRose else primaryFill)
                            .clickable { onTapPrimary() }
                            .padding(horizontal = 13.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = targetPhase.message,
                            color = if (targetPhase.isError) Color.White else contentFg,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                    }
                }
            }
        }
    }
}
