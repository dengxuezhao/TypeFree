package com.typefree.ime.ime

import android.view.inputmethod.ExtractedTextRequest
import com.typefree.ime.audio.WavAudioRecorder
import com.typefree.ime.data.DefaultPresets
import com.typefree.ime.data.HistoryRecord
import com.typefree.ime.data.PipelineMode
import com.typefree.ime.data.VoiceIntentMode
import com.typefree.ime.network.AiPipelineEngine
import com.typefree.ime.network.PipelineStage
import com.typefree.ime.network.asr.StreamingAsrSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/**
 * Controller encapsulating microphone recording, streaming ASR preview / live write,
 * ArcTranslate wheel, Speak-to-Edit, Help-me-Write, `==` Ask AI, and voice pipeline execution.
 */
class VoiceController(
    private val host: ImeHostCallbacks,
    private val audioRecorderProvider: () -> WavAudioRecorder,
    private val onExternalCommitToEngine: (String) -> Unit,
    private val onResetComposing: () -> Unit
) {
    private var activePipelineJob: Job? = null
    private var activeStreamingSession: StreamingAsrSession? = null
    private var streamingPreviewJob: Job? = null
    private var handsFreeSilenceJob: Job? = null
    private var toastDismissJob: Job? = null
    private var activeComposer: StreamingComposer? = null
    private var preSessionSurroundingContext: String = ""
    private var lastKnownSelectionStart: Int = 0
    private var lastKnownSelectionEnd: Int = 0

    @Volatile
    var micPressDownTimeMs: Long = 0L
        private set

    fun warmVoiceConnectionsInBackground() {
        val snap = host.repository.settings.value
        host.scope.launch {
            runCatching {
                host.aiEngine.prewarmLiveStreamingIfNeeded(snap)
                host.aiEngine.prewarmEndpoints(snap)
            }
        }
    }

    fun openOrRestoreVoicePenPanel() {
        host.performKeyFeedback(strong = false)
        onResetComposing()
        host.stateFlow.update { prev ->
            if (prev.rawSelectedText.isNotBlank()) {
                prev.copy(
                    tabMode = KeyboardTabMode.VOICE,
                    ignoreSelectionForThisTurn = false,
                    voiceSubPanel = VoiceSubPanel.SPEAK_TO_EDIT,
                    isClipboardOpen = false,
                    isToolPanelOpen = false,
                    isPunctuationStripOpen = false,
                    isStylePickerExpanded = false,
                    flypyComposingCode = "",
                    preeditDisplay = "",
                    flypyCandidates = emptyList(),
                    isFlypyCandidateSheetExpanded = false
                )
            } else {
                prev.copy(
                    tabMode = KeyboardTabMode.VOICE,
                    voiceSubPanel = VoiceSubPanel.HELP_ME_WRITE,
                    isClipboardOpen = false,
                    isToolPanelOpen = false,
                    isPunctuationStripOpen = false,
                    isStylePickerExpanded = false,
                    flypyComposingCode = "",
                    preeditDisplay = "",
                    flypyCandidates = emptyList(),
                    isFlypyCandidateSheetExpanded = false
                )
            }
        }
    }

    fun closeVoiceSubPanel() {
        host.performKeyFeedback(strong = false)
        if (host.stateFlow.value.voicePhase is VoicePhase.Recording ||
            host.stateFlow.value.voicePhase is VoicePhase.Processing
        ) {
            cancelRecordingOrProcessing()
        }
        host.stateFlow.update {
            it.copy(
                voiceSubPanel = VoiceSubPanel.NONE,
                activeIntentMode = VoiceIntentMode.DICTATE
            )
        }
    }

    fun dismissSpeakToEditForTurn() {
        host.performKeyFeedback(strong = false)
        if (host.stateFlow.value.voicePhase is VoicePhase.Recording ||
            host.stateFlow.value.voicePhase is VoicePhase.Processing
        ) {
            cancelRecordingOrProcessing()
        }
        host.stateFlow.update {
            it.copy(
                ignoreSelectionForThisTurn = true,
                voiceSubPanel = VoiceSubPanel.NONE,
                activeIntentMode = VoiceIntentMode.DICTATE
            )
        }
    }

    fun selectTranslateLanguage(language: String) {
        if (language.isBlank()) return
        host.performKeyFeedback(strong = false)
        host.repository.updateSettings {
            it.copy(activeTranslateTarget = language)
        }
        host.stateFlow.update {
            it.copy(arcSelectedLanguage = language)
        }
    }

    fun togglePunctuationStrip() {
        host.performKeyFeedback(strong = false)
        host.stateFlow.update {
            it.copy(
                isPunctuationStripOpen = !it.isPunctuationStripOpen,
                isStylePickerExpanded = false
            )
        }
    }

    fun enterArcTranslateMode(startedFromHandsFree: Boolean) {
        val curr = host.stateFlow.value
        if (curr.voicePhase !is VoicePhase.Recording) return
        val settings = host.repository.settings.value
        val defaultLang = settings.activeTranslateTarget.ifBlank {
            settings.translationTargets.firstOrNull() ?: DefaultPresets.defaultTranslationTargets().first()
        }
        activeComposer?.cancelSession(rollbackWrittenText = true)
        activeComposer = null
        host.performHaptic(strong = false)
        host.stateFlow.update {
            it.copy(
                isArcTranslateActive = true,
                arcStartedFromHandsFree = startedFromHandsFree,
                isArcCancelHovered = false,
                arcSelectedLanguage = defaultLang,
                activeIntentMode = VoiceIntentMode.TRANSLATE,
                isPunctuationStripOpen = false,
                isStylePickerExpanded = false,
                liveWriteStatus = LiveWriteUiStatus.INACTIVE
            )
        }
    }

    fun updateArcTranslateSelection(language: String, cancelHovered: Boolean) {
        val prev = host.stateFlow.value
        if (!prev.isArcTranslateActive) return
        val changed = prev.arcSelectedLanguage != language || prev.isArcCancelHovered != cancelHovered
        if (!changed) return

        host.performHaptic(strong = false)
        host.stateFlow.update {
            it.copy(
                arcSelectedLanguage = language,
                isArcCancelHovered = cancelHovered
            )
        }
    }

    fun releaseArcTranslate() {
        val snapshot = host.stateFlow.value
        micPressDownTimeMs = 0L
        if (!snapshot.isArcTranslateActive) {
            handleMicPressUp()
            return
        }

        if (snapshot.isArcCancelHovered) {
            cancelRecordingOrProcessing()
            return
        }

        val chosenLang = snapshot.arcSelectedLanguage.ifBlank {
            host.repository.settings.value.activeTranslateTarget.ifBlank { "English (US)" }
        }
        host.repository.updateSettings {
            it.copy(activeTranslateTarget = chosenLang)
        }

        val recorder = runCatching { audioRecorderProvider() }.getOrNull()
        if (ImeLogic.shouldLockArcIntoHandsFree(snapshot.arcStartedFromHandsFree)) {
            host.performHaptic(strong = false)
            recorder?.clearSilencePrefix(skipHapticMs = 120L)
            host.stateFlow.update {
                it.copy(
                    voicePhase = VoicePhase.Recording(isHandsFree = true),
                    isArcTranslateActive = false,
                    arcStartedFromHandsFree = false,
                    isArcCancelHovered = false,
                    arcSelectedLanguage = chosenLang,
                    activeIntentMode = VoiceIntentMode.TRANSLATE
                )
            }
            startHandsFreeSilenceMonitor()
        } else {
            host.performKeyFeedback(strong = true)
            host.stateFlow.update {
                it.copy(
                    isArcTranslateActive = false,
                    arcStartedFromHandsFree = false,
                    isArcCancelHovered = false,
                    arcSelectedLanguage = chosenLang,
                    activeIntentMode = VoiceIntentMode.TRANSLATE
                )
            }
            stopRecordingAndRunPipeline(
                intentOverride = VoiceIntentMode.TRANSLATE,
                translateLangOverride = chosenLang
            )
        }
    }

    fun handleMicPressDown(intentMode: VoiceIntentMode = VoiceIntentMode.DICTATE) {
        val currentPhase = host.stateFlow.value.voicePhase
        if (currentPhase is VoicePhase.Processing) return
        if (currentPhase is VoicePhase.Recording && currentPhase.isHandsFree) return

        host.tryAutoLearnFromRecentEdit()
        toastDismissJob?.cancel()

        val defaultTranslateLang = host.repository.settings.value.activeTranslateTarget
        host.stateFlow.update {
            it.copy(
                activeIntentMode = intentMode,
                isArcTranslateActive = false,
                arcStartedFromHandsFree = false,
                isArcCancelHovered = false,
                arcSelectedLanguage = defaultTranslateLang,
                isPunctuationStripOpen = false,
                isStylePickerExpanded = false,
                isHistoryDrawerOpen = false
            )
        }

        val audioRecorder = audioRecorderProvider()
        if (!audioRecorder.hasRecordPermission()) {
            micPressDownTimeMs = 0L
            host.performHaptic(strong = true)
            host.stateFlow.update {
                it.copy(
                    voicePhase = VoicePhase.Error(
                        message = "尚未授予麦克风录音权限，请先授权后使用",
                        canRetryAudio = false,
                        needsApiSetup = false,
                        needsMicPermission = true
                    )
                )
            }
            return
        }

        // Fire tactile feedback BEFORE opening AudioRecord so speaker click / motor hum isn't recorded
        host.performHaptic(strong = true)

        abortActiveStreamingSession()
        handsFreeSilenceJob?.cancel()
        handsFreeSilenceJob = null
        activeComposer?.cancelSession(rollbackWrittenText = false)
        activeComposer = null

        val settingsSnap = host.repository.settings.value
        val prevState = host.stateFlow.value
        val liveSelected = host.readSelectedTextSafely().ifBlank { prevState.rawSelectedText }
        val effectiveSelected = if (prevState.ignoreSelectionForThisTurn) "" else liveSelected
        val resolvedStartIntent = when {
            intentMode != VoiceIntentMode.DICTATE -> intentMode
            effectiveSelected.isNotBlank() -> VoiceIntentMode.SPEAK_TO_EDIT
            else -> VoiceIntentMode.DICTATE
        }
        preSessionSurroundingContext = host.readSurroundingBeforeCursor(80)

        val streamSession = host.aiEngine.openLiveStreamingSession(settingsSnap)
        val enableLiveWrite = StreamingComposer.shouldEnableLiveWrite(
            settings = settingsSnap,
            intentMode = resolvedStartIntent,
            effectiveSelectedText = effectiveSelected,
            hasStreamingSession = streamSession != null,
            targetPackage = prevState.targetPackage,
            isFloatingBubbleChannel = false
        )

        val startRes = audioRecorder.startRecording(
            scope = host.scope,
            onStreamChunk = if (streamSession != null) {
                { pcmChunk -> streamSession.sendPcmFrame(pcmChunk) }
            } else {
                null
            },
            onSilencePrefixCleared = {
                streamSession?.resetPrefix()
                activeComposer?.cancelSession(rollbackWrittenText = true)
                host.stateFlow.update {
                    it.copy(
                        streamingPartialPreview = "",
                        liveWriteStatus = activeComposer?.uiStatus ?: LiveWriteUiStatus.INACTIVE
                    )
                }
            },
            pauseMediaPlayback = settingsSnap.pauseMediaWhileRecording,
            whisperMode = settingsSnap.whisperModeEnabled
        )
        startRes.fold(
            onSuccess = {
                micPressDownTimeMs = System.currentTimeMillis()
                activeStreamingSession = streamSession
                if (enableLiveWrite) {
                    onResetComposing()
                    val anchor = resolveCurrentCursorAnchor()
                    val composer = StreamingComposer(
                        editorBridge = InputConnectionEditorBridge { host.currentInputConnection() }
                    )
                    composer.startSession(
                        anchorCursor = anchor,
                        smartDirectMode = StreamingComposer.isSmartDirectDictation(settingsSnap, resolvedStartIntent)
                    )
                    activeComposer = composer
                } else {
                    activeComposer = null
                }

                if (streamSession != null) {
                    streamingPreviewJob = host.scope.launch {
                        streamSession.transcript.collect { liveTranscript ->
                            val composer = activeComposer
                            if (composer != null && composer.isActive) {
                                composer.onTranscriptUpdate(liveTranscript)
                                host.stateFlow.update {
                                    it.copy(
                                        streamingPartialPreview = liveTranscript.fullText,
                                        liveWriteStatus = composer.uiStatus
                                    )
                                }
                            } else {
                                host.stateFlow.update {
                                    it.copy(streamingPartialPreview = liveTranscript.fullText)
                                }
                            }
                        }
                    }
                }
                // Warm up LLM / HTTP STT TCP+TLS sockets during the first ~250ms of speaking
                host.scope.launch {
                    host.aiEngine.prewarmEndpoints(settingsSnap)
                }
                host.stateFlow.update {
                    it.copy(
                        voicePhase = VoicePhase.Recording(isHandsFree = false),
                        streamingPartialPreview = "",
                        liveWriteStatus = activeComposer?.uiStatus ?: LiveWriteUiStatus.INACTIVE
                    )
                }
            },
            onFailure = { err ->
                streamSession?.cancel()
                activeComposer = null
                micPressDownTimeMs = 0L
                host.performHaptic(strong = true)
                val errorPhase = ImeLogic.classifyErrorFlags(
                    errorMessage = err.message ?: "无法启动麦克风录音",
                    hasCachedAudio = host.hasRetryWavFile(),
                    hasMicPermission = audioRecorder.hasRecordPermission()
                )
                host.stateFlow.update {
                    it.copy(
                        voicePhase = errorPhase,
                        streamingPartialPreview = "",
                        liveWriteStatus = LiveWriteUiStatus.INACTIVE
                    )
                }
            }
        )
    }

    fun abortActiveStreamingSession() {
        streamingPreviewJob?.cancel()
        streamingPreviewJob = null
        activeStreamingSession?.cancel()
        activeStreamingSession = null
    }

    fun handleMicPressUp() {
        if (host.stateFlow.value.isArcTranslateActive) {
            releaseArcTranslate()
            return
        }

        val downTime = micPressDownTimeMs
        micPressDownTimeMs = 0L
        val phase = host.stateFlow.value.voicePhase
        if (downTime <= 0L || phase !is VoicePhase.Recording || phase.isHandsFree) {
            return
        }

        val heldDurationMs = System.currentTimeMillis() - downTime
        if (ImeLogic.isPressAndHoldGesture(heldDurationMs)) {
            host.performKeyFeedback(strong = false)
            stopRecordingAndRunPipeline()
        } else {
            host.performHaptic(strong = false)
            host.stateFlow.update {
                it.copy(voicePhase = VoicePhase.Recording(isHandsFree = true))
            }
            startHandsFreeSilenceMonitor()
        }
    }

    private fun startHandsFreeSilenceMonitor() {
        handsFreeSilenceJob?.cancel()
        val recorder = runCatching { audioRecorderProvider() }.getOrNull() ?: return
        handsFreeSilenceJob = host.scope.launch {
            var lastVoiceActivityAtMs = System.currentTimeMillis()
            val ampCollectorJob = launch {
                recorder.amplitudes.collect { amps ->
                    if (!isSilentAmplitudeFrame(amps)) {
                        lastVoiceActivityAtMs = System.currentTimeMillis()
                    }
                }
            }
            try {
                while (true) {
                    delay(200L)
                    val curr = host.stateFlow.value
                    val phase = curr.voicePhase
                    if (phase !is VoicePhase.Recording || !phase.isHandsFree) {
                        break
                    }
                    if (curr.isArcTranslateActive) {
                        lastVoiceActivityAtMs = System.currentTimeMillis()
                        continue
                    }
                    if (!isSilentAmplitudeFrame(recorder.amplitudes.value)) {
                        lastVoiceActivityAtMs = System.currentTimeMillis()
                        continue
                    }
                    if (System.currentTimeMillis() - lastVoiceActivityAtMs >= HANDS_FREE_SILENCE_AUTO_STOP_MS) {
                        finishHandsFreeRecording()
                        break
                    }
                }
            } finally {
                ampCollectorJob.cancel()
            }
        }
    }

    private fun resolveCurrentCursorAnchor(): Int {
        val ic = host.currentInputConnection()
        if (ic != null) {
            val extracted = runCatching { ic.getExtractedText(ExtractedTextRequest(), 0) }.getOrNull()
            if (extracted != null && extracted.selectionStart >= 0) {
                return extracted.selectionStart
            }
            val before = runCatching { ic.getTextBeforeCursor(4000, 0) }.getOrNull()
            if (before != null) {
                return before.length
            }
        }
        return lastKnownSelectionStart.coerceAtLeast(0)
    }

    fun finishHandsFreeRecording() {
        val snapshot = host.stateFlow.value
        if (snapshot.voicePhase !is VoicePhase.Recording) return
        host.performKeyFeedback(strong = true)
        if (snapshot.activeIntentMode == VoiceIntentMode.TRANSLATE) {
            val targetLang = snapshot.arcSelectedLanguage.ifBlank {
                host.repository.settings.value.activeTranslateTarget
            }
            stopRecordingAndRunPipeline(
                intentOverride = VoiceIntentMode.TRANSLATE,
                translateLangOverride = targetLang
            )
        } else {
            stopRecordingAndRunPipeline()
        }
    }

    fun cancelRecordingOrProcessing() {
        host.performKeyFeedback(strong = false)
        micPressDownTimeMs = 0L
        handsFreeSilenceJob?.cancel()
        handsFreeSilenceJob = null
        abortActiveStreamingSession()
        activeComposer?.cancelSession(rollbackWrittenText = true)
        activeComposer = null
        activePipelineJob?.cancel()
        activePipelineJob = null
        val audioRecorder = runCatching { audioRecorderProvider() }.getOrNull()
        if (audioRecorder != null) {
            host.scope.launch {
                audioRecorder.cancelRecording()
            }
        }
        host.stateFlow.update {
            it.copy(
                voicePhase = VoicePhase.Idle,
                isArcTranslateActive = false,
                arcStartedFromHandsFree = false,
                isArcCancelHovered = false,
                streamingPartialPreview = "",
                liveWriteStatus = LiveWriteUiStatus.INACTIVE
            )
        }
    }

    fun stopRecordingAndRunPipeline(
        intentOverride: VoiceIntentMode? = null,
        translateLangOverride: String? = null
    ) {
        val liveSession = activeStreamingSession
        activeStreamingSession = null
        streamingPreviewJob?.cancel()
        streamingPreviewJob = null
        handsFreeSilenceJob?.cancel()
        handsFreeSilenceJob = null

        val audioRecorder = audioRecorderProvider()
        activePipelineJob?.cancel()
        activePipelineJob = host.scope.launch {
            val stopResult = audioRecorder.stopRecording()
            stopResult.fold(
                onSuccess = { recorded ->
                    var preTranscribed: String? = null
                    var tailWaitMs = 0L

                    if (liveSession != null) {
                        host.stateFlow.update {
                            it.copy(
                                voicePhase = VoicePhase.Processing(stageText = "收尾识别中..."),
                                isArcTranslateActive = false,
                                arcStartedFromHandsFree = false,
                                isArcCancelHovered = false,
                                streamingPartialPreview = ""
                            )
                        }
                        val tailStart = System.currentTimeMillis()
                        val streamRes = liveSession.finishAndAwaitFinal()
                        tailWaitMs = (System.currentTimeMillis() - tailStart).coerceAtLeast(0L)

                        val streamErr = streamRes.exceptionOrNull()
                        if (streamErr is IllegalStateException) {
                            activeComposer?.cancelSession(rollbackWrittenText = true)
                            activeComposer = null
                            val errorPhase = ImeLogic.classifyErrorFlags(
                                errorMessage = streamErr.message ?: "请先完成语音 API 配置",
                                hasCachedAudio = recorded.wavFile.exists(),
                                hasMicPermission = audioRecorder.hasRecordPermission()
                            )
                            host.stateFlow.update {
                                it.copy(
                                    voicePhase = errorPhase,
                                    hasCachedAudioForRetry = errorPhase.canRetryAudio,
                                    liveWriteStatus = LiveWriteUiStatus.INACTIVE
                                )
                            }
                            return@launch
                        }

                        val candidate = streamRes.getOrNull()?.trim().orEmpty()
                        if (candidate.isNotEmpty() && !AiPipelineEngine.isHallucinatedSilence(candidate)) {
                            preTranscribed = candidate
                        }
                    }

                    if (preTranscribed == null && !recorded.hasValidSpeech) {
                        liveSession?.cancel()
                        activeComposer?.cancelSession(rollbackWrittenText = true)
                        activeComposer = null
                        val errorPhase = ImeLogic.classifyErrorFlags(
                            errorMessage = "未检测到清晰说话声，请靠近麦克风重试",
                            hasCachedAudio = false,
                            hasMicPermission = audioRecorder.hasRecordPermission()
                        )
                        host.stateFlow.update {
                            it.copy(
                                voicePhase = errorPhase,
                                isArcTranslateActive = false,
                                arcStartedFromHandsFree = false,
                                isArcCancelHovered = false,
                                streamingPartialPreview = "",
                                hasCachedAudioForRetry = false,
                                liveWriteStatus = LiveWriteUiStatus.INACTIVE
                            )
                        }
                        return@launch
                    }

                    executeVoicePipelineOnWav(
                        wavFile = recorded.wavFile,
                        durationMs = recorded.durationMs,
                        intentOverride = intentOverride,
                        translateLangOverride = translateLangOverride,
                        preTranscribedText = preTranscribed,
                        sttTailWaitMs = tailWaitMs
                    )
                },
                onFailure = { err ->
                    liveSession?.cancel()
                    activeComposer?.cancelSession(rollbackWrittenText = true)
                    activeComposer = null
                    if (err is CancellationException) return@launch
                    val errorPhase = ImeLogic.classifyErrorFlags(
                        errorMessage = err.message ?: "录音时间过短，请重试",
                        hasCachedAudio = host.hasRetryWavFile(),
                        hasMicPermission = audioRecorder.hasRecordPermission()
                    )
                    host.stateFlow.update {
                        it.copy(
                            voicePhase = errorPhase,
                            isArcTranslateActive = false,
                            arcStartedFromHandsFree = false,
                            isArcCancelHovered = false,
                            streamingPartialPreview = "",
                            hasCachedAudioForRetry = errorPhase.canRetryAudio,
                            liveWriteStatus = LiveWriteUiStatus.INACTIVE
                        )
                    }
                }
            )
        }
    }

    private suspend fun executeVoicePipelineOnWav(
        wavFile: File,
        durationMs: Long,
        intentOverride: VoiceIntentMode? = null,
        translateLangOverride: String? = null,
        preTranscribedText: String? = null,
        sttTailWaitMs: Long = 0L
    ) {
        val settings = host.repository.settings.value
        val snapshot = host.stateFlow.value
        val targetPkg = snapshot.targetPackage
        val targetAppName = snapshot.targetAppName
        val (activeStyle, _) = host.repository.resolveActiveStyle(targetPkg, snapshot.currentInputType)

        val liveSelection = host.readSelectedTextSafely().ifBlank { snapshot.rawSelectedText }
        val effectiveSelected = if (snapshot.ignoreSelectionForThisTurn) "" else liveSelection

        val resolvedIntent = when {
            intentOverride != null -> intentOverride
            snapshot.activeIntentMode == VoiceIntentMode.TRANSLATE -> VoiceIntentMode.TRANSLATE
            snapshot.activeIntentMode == VoiceIntentMode.HELP_ME_WRITE -> VoiceIntentMode.HELP_ME_WRITE
            effectiveSelected.isNotBlank() -> VoiceIntentMode.SPEAK_TO_EDIT
            else -> VoiceIntentMode.DICTATE
        }
        val isSpeakToEdit = resolvedIntent == VoiceIntentMode.SPEAK_TO_EDIT
        val targetTranslateLang = if (resolvedIntent == VoiceIntentMode.TRANSLATE) {
            (translateLangOverride ?: settings.activeTranslateTarget).ifBlank { "English (US)" }
        } else {
            ""
        }

        val composer = activeComposer
        val surroundingContext = if (composer != null && !composer.wasFrozenDuringSession) {
            preSessionSurroundingContext
        } else {
            host.readSurroundingBeforeCursor(80)
        }

        if (composer != null && composer.isActive) {
            if (resolvedIntent != VoiceIntentMode.DICTATE) {
                composer.cancelSession(rollbackWrittenText = true)
                activeComposer = null
            } else {
                val needsPolish = !StreamingComposer.isSmartDirectDictation(settings, resolvedIntent)
                composer.onRecordingFinished(
                    finalRawTranscript = preTranscribedText.orEmpty(),
                    needsPolishing = needsPolish
                )
            }
        }

        val initialStage = when {
            settings.pipelineMode == PipelineMode.MULTIMODAL_DIRECT -> PipelineStage.MULTIMODAL_PROCESSING
            !preTranscribedText.isNullOrBlank() -> PipelineStage.POLISHING_LLM
            else -> PipelineStage.TRANSCRIBING_STT
        }

        host.stateFlow.update {
            it.copy(
                voicePhase = VoicePhase.Processing(
                    stageText = ImeLogic.stageDisplayTextForIntent(
                        stage = initialStage,
                        intentMode = resolvedIntent,
                        targetLanguage = targetTranslateLang
                    )
                ),
                isArcTranslateActive = false,
                arcStartedFromHandsFree = false,
                isArcCancelHovered = false,
                streamingPartialPreview = "",
                hasCachedAudioForRetry = wavFile.exists(),
                liveWriteStatus = activeComposer?.uiStatus ?: LiveWriteUiStatus.INACTIVE
            )
        }

        val pipelineResult = host.aiEngine.processVoiceAudio(
            wavFile = wavFile,
            settings = settings,
            activeStyle = activeStyle,
            targetAppName = targetAppName,
            selectedText = if (isSpeakToEdit) effectiveSelected else "",
            surroundingBeforeCursor = surroundingContext,
            intentMode = resolvedIntent,
            translateTargetLanguage = targetTranslateLang.ifBlank { settings.activeTranslateTarget },
            preTranscribedText = preTranscribedText,
            sttTailWaitMs = sttTailWaitMs,
            onStageChanged = { stage ->
                host.stateFlow.update { curr ->
                    curr.copy(
                        voicePhase = VoicePhase.Processing(
                            stageText = ImeLogic.stageDisplayTextForIntent(
                                stage = stage,
                                intentMode = resolvedIntent,
                                targetLanguage = targetTranslateLang
                            )
                        )
                    )
                }
            }
        )

        pipelineResult.fold(
            onSuccess = { exec ->
                val finalOutput = exec.finalOutput
                val activeComp = activeComposer
                activeComposer = null
                if (activeComp != null) {
                    finalizeLiveWriteOutput(
                        composer = activeComp,
                        finalText = finalOutput,
                        rawTranscript = exec.rawTranscript,
                        latencyMs = exec.latencyMs
                    )
                } else {
                    val overwritten = if (isSpeakToEdit) liveSelection else host.readSelectedTextSafely()
                    commitPipelineOutput(
                        finalText = finalOutput,
                        overwrittenSelection = overwritten,
                        latencyMs = exec.latencyMs
                    )
                }
                if (!host.stateFlow.value.isIncognitoMode) {
                    val historyAudioFile = host.archiveWavForHistory(wavFile, settings.historyRetention)
                    host.repository.addHistory(
                        HistoryRecord(
                            targetAppName = targetAppName,
                            targetPackage = targetPkg,
                            styleName = activeStyle.name,
                            isSpeakToEdit = isSpeakToEdit,
                            intentMode = resolvedIntent,
                            translateTargetLanguage = targetTranslateLang,
                            originalSelectedText = if (isSpeakToEdit) effectiveSelected else "",
                            rawTranscript = exec.rawTranscript,
                            polishedText = finalOutput,
                            durationMs = durationMs,
                            latencyMs = exec.latencyMs,
                            modeLabel = exec.modeSummary,
                            audioFilePath = historyAudioFile.absolutePath
                        )
                    )
                }
                if (host.repository.isImeWindowVisible.value) {
                    warmVoiceConnectionsInBackground()
                }
            },
            onFailure = { err ->
                activeComposer?.cancelSession(rollbackWrittenText = false)
                activeComposer = null
                if (err is CancellationException) return
                host.performHaptic(strong = true)
                val audioRecorder = runCatching { audioRecorderProvider() }.getOrNull()
                val errorPhase = ImeLogic.classifyErrorFlags(
                    errorMessage = err.message ?: "语音处理失败，请检查网络或 API 配置",
                    hasCachedAudio = wavFile.exists(),
                    hasMicPermission = audioRecorder?.hasRecordPermission() == true
                )
                host.stateFlow.update {
                    it.copy(
                        voicePhase = errorPhase,
                        hasCachedAudioForRetry = errorPhase.canRetryAudio,
                        liveWriteStatus = LiveWriteUiStatus.INACTIVE
                    )
                }
            }
        )
    }

    fun runQuickEditChip(chip: QuickEditChip) {
        val currPhase = host.stateFlow.value.voicePhase
        if (currPhase is VoicePhase.Recording || currPhase is VoicePhase.Processing) return

        val liveSelected = host.readSelectedTextSafely().ifBlank { host.stateFlow.value.effectiveSelectedText }
        if (liveSelected.isBlank()) return

        host.performKeyFeedback(strong = false)
        toastDismissJob?.cancel()
        activePipelineJob?.cancel()

        activePipelineJob = host.scope.launch {
            val settings = host.repository.settings.value
            val snapshot = host.stateFlow.value
            val targetPkg = snapshot.targetPackage
            val targetAppName = snapshot.targetAppName
            val (activeStyle, _) = host.repository.resolveActiveStyle(targetPkg, snapshot.currentInputType)

            host.stateFlow.update {
                it.copy(voicePhase = VoicePhase.Processing(stageText = "${chip.label}中..."))
            }

            val transformResult = host.aiEngine.runTextTransform(
                settings = settings,
                activeStyle = activeStyle,
                rawOrSelectedText = liveSelected,
                explicitInstruction = chip.instruction,
                targetAppName = targetAppName
            )

            transformResult.fold(
                onSuccess = { exec ->
                    val finalOutput = exec.finalOutput
                    commitPipelineOutput(
                        finalText = finalOutput,
                        overwrittenSelection = liveSelected,
                        latencyMs = exec.latencyMs
                    )
                    if (!host.stateFlow.value.isIncognitoMode) {
                        host.repository.addHistory(
                            HistoryRecord(
                                targetAppName = targetAppName,
                                targetPackage = targetPkg,
                                styleName = activeStyle.name,
                                isSpeakToEdit = true,
                                intentMode = VoiceIntentMode.SPEAK_TO_EDIT,
                                translateTargetLanguage = "",
                                originalSelectedText = liveSelected,
                                rawTranscript = "[快捷改写] ${chip.label}",
                                polishedText = finalOutput,
                                durationMs = 0L,
                                latencyMs = exec.latencyMs,
                                modeLabel = exec.modeSummary,
                                audioFilePath = ""
                            )
                        )
                    }
                },
                onFailure = { err ->
                    if (err is CancellationException) return@launch
                    host.performHaptic(strong = true)
                    val audioRecorder = runCatching { audioRecorderProvider() }.getOrNull()
                    val errorPhase = ImeLogic.classifyErrorFlags(
                        errorMessage = err.message ?: "改写失败，请检查网络或 API 配置",
                        hasCachedAudio = false,
                        hasMicPermission = audioRecorder?.hasRecordPermission() == true
                    )
                    host.stateFlow.update { it.copy(voicePhase = errorPhase) }
                }
            )
        }
    }

    fun runHelpWritePreset(preset: HelpWritePromptPreset) {
        val currPhase = host.stateFlow.value.voicePhase
        if (currPhase is VoicePhase.Recording || currPhase is VoicePhase.Processing) return

        host.performKeyFeedback(strong = false)
        toastDismissJob?.cancel()
        activePipelineJob?.cancel()

        activePipelineJob = host.scope.launch {
            val settings = host.repository.settings.value
            val snapshot = host.stateFlow.value
            val targetPkg = snapshot.targetPackage
            val targetAppName = snapshot.targetAppName
            val (activeStyle, _) = host.repository.resolveActiveStyle(targetPkg, snapshot.currentInputType)

            host.stateFlow.update {
                it.copy(voicePhase = VoicePhase.Processing(stageText = "正在起草：${preset.label}..."))
            }

            val draftInstruction =
                "请忽略<selected_text>中的占位文字，直接根据以下写作需求起草完整、自然得体、可直接发送的成稿正文（绝不输出任何寒暄或解释）：${preset.instruction}"

            val transformResult = host.aiEngine.runTextTransform(
                settings = settings,
                activeStyle = activeStyle,
                rawOrSelectedText = preset.label,
                explicitInstruction = draftInstruction,
                targetAppName = targetAppName
            )

            transformResult.fold(
                onSuccess = { exec ->
                    val finalOutput = exec.finalOutput
                    val existingSel = host.readSelectedTextSafely()
                    commitPipelineOutput(
                        finalText = finalOutput,
                        overwrittenSelection = existingSel,
                        latencyMs = exec.latencyMs
                    )
                    if (!host.stateFlow.value.isIncognitoMode) {
                        host.repository.addHistory(
                            HistoryRecord(
                                targetAppName = targetAppName,
                                targetPackage = targetPkg,
                                styleName = activeStyle.name,
                                isSpeakToEdit = false,
                                intentMode = VoiceIntentMode.HELP_ME_WRITE,
                                translateTargetLanguage = "",
                                originalSelectedText = "",
                                rawTranscript = "[帮我写灵感] ${preset.instruction}",
                                polishedText = finalOutput,
                                durationMs = 0L,
                                latencyMs = exec.latencyMs,
                                modeLabel = exec.modeSummary,
                                audioFilePath = ""
                            )
                        )
                    }
                },
                onFailure = { err ->
                    if (err is CancellationException) return@launch
                    host.performHaptic(strong = true)
                    val audioRecorder = runCatching { audioRecorderProvider() }.getOrNull()
                    val errorPhase = ImeLogic.classifyErrorFlags(
                        errorMessage = err.message ?: "起草失败，请检查网络或 API 配置",
                        hasCachedAudio = false,
                        hasMicPermission = audioRecorder?.hasRecordPermission() == true
                    )
                    host.stateFlow.update { it.copy(voicePhase = errorPhase) }
                }
            )
        }
    }

    fun retryLastAudio() {
        val currPhase = host.stateFlow.value.voicePhase
        if (currPhase is VoicePhase.Recording || currPhase is VoicePhase.Processing) return

        val wavFile = host.getLastRecordingWavFile()
        if (!wavFile.exists() || wavFile.length() <= 44L) {
            host.stateFlow.update {
                it.copy(
                    voicePhase = VoicePhase.Error(
                        message = "未找到可重试的最近录音文件，请重新说话",
                        canRetryAudio = false,
                        needsApiSetup = false,
                        needsMicPermission = false
                    ),
                    hasCachedAudioForRetry = false
                )
            }
            return
        }

        host.performKeyFeedback(strong = false)
        val estimatedDurationMs =
            ((wavFile.length() - 44L).coerceAtLeast(0L) * 1000L) / (WavAudioRecorder.SAMPLE_RATE * 2L)

        activePipelineJob?.cancel()
        activePipelineJob = host.scope.launch {
            executeVoicePipelineOnWav(wavFile, estimatedDurationMs)
        }
    }

    fun dismissErrorBanner() {
        host.performKeyFeedback(strong = false)
        host.stateFlow.update {
            if (it.voicePhase is VoicePhase.Error) {
                it.copy(voicePhase = VoicePhase.Idle)
            } else {
                it
            }
        }
    }

    private fun finalizeLiveWriteOutput(
        composer: StreamingComposer,
        finalText: String,
        rawTranscript: String,
        latencyMs: Long
    ) {
        val outcome = if (composer.state == StreamingComposer.State.Done &&
            finalText == composer.writtenRawText
        ) {
            ComposerFinalizeOutcome(
                appliedToEditor = true,
                wasFrozen = composer.wasFrozenDuringSession,
                rawWrittenText = composer.writtenRawText,
                finalCommittedText = finalText,
                undoSnapshot = ImeLogic.createUndoSnapshot(
                    committedText = finalText,
                    replacedSelection = ""
                )
            )
        } else {
            composer.applyPolishedResult(
                polishedText = finalText,
                fullRawTranscript = rawTranscript
            )
        }

        val fullBufferAfterCommit = host.readFullEditorTextSafely().ifBlank { outcome.finalCommittedText }
        host.onDictationCommittedForAutoLearn(fullBufferAfterCommit)

        host.performHaptic(strong = true)
        if (outcome.finalCommittedText.isNotEmpty()) {
            onExternalCommitToEngine(outcome.finalCommittedText)
        }
        host.pushUndoSnapshot(outcome.undoSnapshot)

        host.stateFlow.update {
            it.copy(
                voicePhase = VoicePhase.SuccessToast(
                    preview = ImeLogic.formatSelectionPreview(outcome.finalCommittedText.ifBlank { finalText }, 24),
                    latencyMs = latencyMs
                ),
                voiceSubPanel = VoiceSubPanel.NONE,
                activeIntentMode = VoiceIntentMode.DICTATE,
                undoSnapshot = host.peekTopUndoSnapshot(),
                rawSelectedText = "",
                ignoreSelectionForThisTurn = false,
                liveWriteStatus = LiveWriteUiStatus.INACTIVE
            )
        }
        scheduleToastReset()
    }

    fun commitPipelineOutput(
        finalText: String,
        overwrittenSelection: String,
        latencyMs: Long
    ) {
        val ic = host.currentInputConnection()
        if (ic != null) {
            ic.beginBatchEdit()
            ic.commitText(finalText, 1)
            ic.endBatchEdit()
        }

        val fullBufferAfterCommit = host.readFullEditorTextSafely().ifBlank { finalText }
        host.onDictationCommittedForAutoLearn(fullBufferAfterCommit)

        host.performHaptic(strong = true)
        onExternalCommitToEngine(finalText)
        val undo = ImeLogic.createUndoSnapshot(
            committedText = finalText,
            replacedSelection = overwrittenSelection
        )
        host.pushUndoSnapshot(undo)

        host.stateFlow.update {
            it.copy(
                voicePhase = VoicePhase.SuccessToast(
                    preview = ImeLogic.formatSelectionPreview(finalText, 24),
                    latencyMs = latencyMs
                ),
                voiceSubPanel = VoiceSubPanel.NONE,
                activeIntentMode = VoiceIntentMode.DICTATE,
                undoSnapshot = host.peekTopUndoSnapshot(),
                rawSelectedText = "",
                ignoreSelectionForThisTurn = false,
                liveWriteStatus = LiveWriteUiStatus.INACTIVE
            )
        }
        scheduleToastReset()
    }

    fun scheduleToastReset() {
        toastDismissJob?.cancel()
        toastDismissJob = host.scope.launch {
            delay(2200L)
            host.stateFlow.update { curr ->
                if (curr.voicePhase is VoicePhase.SuccessToast) {
                    curr.copy(voicePhase = VoicePhase.Idle)
                } else {
                    curr
                }
            }
        }
    }

    fun toggleWhisperMode() {
        host.performKeyFeedback(strong = true)
        val nextWhisper = !host.repository.settings.value.whisperModeEnabled
        host.repository.updateSettings { it.copy(whisperModeEnabled = nextWhisper) }
        host.showQuickActionToast(if (nextWhisper) "🤫 已开启轻声耳语高敏收音" else "🎙️ 已恢复标准收音灵敏度")
    }

    fun runEqualEqualAskAi(fullEditorText: String, query: String) {
        val currPhase = host.stateFlow.value.voicePhase
        if (currPhase is VoicePhase.Recording || currPhase is VoicePhase.Processing) return

        host.performKeyFeedback(strong = true)
        toastDismissJob?.cancel()
        activePipelineJob?.cancel()

        activePipelineJob = host.scope.launch {
            val settings = host.repository.settings.value
            val snapshot = host.stateFlow.value
            val targetPkg = snapshot.targetPackage
            val targetAppName = snapshot.targetAppName
            val (activeStyle, _) = host.repository.resolveActiveStyle(targetPkg, snapshot.currentInputType)

            host.stateFlow.update {
                it.copy(voicePhase = VoicePhase.Processing(stageText = "🤖 AI 思考解答中..."))
            }
            if (snapshot.tabMode != KeyboardTabMode.VOICE) {
                host.showQuickActionToast("🤖 AI 正在解答「${query.take(10)}」…")
            }

            val directAskInstruction =
                "用户在输入框通过 == 向你直接提问或下达写作指令。请忽略<selected_text>的选中改写语义，直接把<selected_text>当作用户的问题或指令本身，直接输出准确、简洁、立即可发送或使用的结果正文（严禁寒暄、严禁解释前缀、严禁复述问题）。"

            val transformResult = host.aiEngine.runTextTransform(
                settings = settings,
                activeStyle = activeStyle,
                rawOrSelectedText = query,
                explicitInstruction = directAskInstruction,
                targetAppName = targetAppName
            )

            transformResult.fold(
                onSuccess = { exec ->
                    val finalOutput = exec.finalOutput
                    val ic = host.currentInputConnection()
                    val currentFull = host.readFullEditorTextSafely().ifEmpty { fullEditorText }
                    if (ic != null) {
                        ic.beginBatchEdit()
                        val extracted = runCatching {
                            ic.getExtractedText(ExtractedTextRequest(), 0)
                        }.getOrNull()
                        if (extracted?.text != null) {
                            ic.setSelection(0, extracted.text.length)
                            ic.commitText(finalOutput, 1)
                        } else {
                            val beforeLen = runCatching {
                                ic.getTextBeforeCursor(4000, 0)?.length ?: currentFull.length
                            }.getOrDefault(currentFull.length)
                            val afterLen = runCatching {
                                ic.getTextAfterCursor(4000, 0)?.length ?: 0
                            }.getOrDefault(0)
                            ic.deleteSurroundingText(beforeLen, afterLen)
                            ic.commitText(finalOutput, 1)
                        }
                        ic.endBatchEdit()
                    }

                    host.clearAutoLearnBaseline()
                    host.performHaptic(strong = true)
                    onExternalCommitToEngine(finalOutput)

                    val undo = ImeLogic.createUndoSnapshot(
                        committedText = finalOutput,
                        replacedSelection = currentFull
                    )
                    host.pushUndoSnapshot(undo)

                    host.stateFlow.update {
                        it.copy(
                            voicePhase = VoicePhase.SuccessToast(
                                preview = ImeLogic.formatSelectionPreview(finalOutput, 24),
                                latencyMs = exec.latencyMs
                            ),
                            undoSnapshot = host.peekTopUndoSnapshot(),
                            rawSelectedText = "",
                            ignoreSelectionForThisTurn = false
                        )
                    }
                    scheduleToastReset()
                    if (host.stateFlow.value.tabMode != KeyboardTabMode.VOICE) {
                        host.showQuickActionToast("✓ AI 已填入答案 · 可点 ↶ 撤回")
                    }

                    if (!host.stateFlow.value.isIncognitoMode) {
                        host.repository.addHistory(
                            HistoryRecord(
                                targetAppName = targetAppName,
                                targetPackage = targetPkg,
                                styleName = activeStyle.name,
                                isSpeakToEdit = false,
                                intentMode = VoiceIntentMode.HELP_ME_WRITE,
                                translateTargetLanguage = "",
                                originalSelectedText = currentFull,
                                rawTranscript = "[== 问 AI] $query",
                                polishedText = finalOutput,
                                durationMs = 0L,
                                latencyMs = exec.latencyMs,
                                modeLabel = exec.modeSummary,
                                audioFilePath = ""
                            )
                        )
                    }
                },
                onFailure = { err ->
                    if (err is CancellationException) return@launch
                    host.performHaptic(strong = true)
                    val audioRecorder = runCatching { audioRecorderProvider() }.getOrNull()
                    val errorPhase = ImeLogic.classifyErrorFlags(
                        errorMessage = err.message ?: "AI 回答失败，请检查网络或 API 配置",
                        hasCachedAudio = false,
                        hasMicPermission = audioRecorder?.hasRecordPermission() == true
                    )
                    host.stateFlow.update { it.copy(voicePhase = errorPhase) }
                    if (host.stateFlow.value.tabMode != KeyboardTabMode.VOICE) {
                        host.showQuickActionToast("⚠ ${(err.message ?: "AI 回答失败").take(22)}")
                    }
                }
            )
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun onSelectionUpdated(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int
    ) {
        if (newSelStart >= 0 && newSelEnd >= 0) {
            lastKnownSelectionStart = newSelStart
            lastKnownSelectionEnd = newSelEnd
        }
        val composer = activeComposer ?: return
        if (!composer.isActive) return
        composer.onUpdateSelection(newSelStart, newSelEnd)
        val nextUiStatus = composer.uiStatus
        if (host.stateFlow.value.liveWriteStatus != nextUiStatus) {
            host.stateFlow.update { it.copy(liveWriteStatus = nextUiStatus) }
        }
    }

    fun stopRecordingOnTabSwitchOrWindowHidden() {
        handsFreeSilenceJob?.cancel()
        handsFreeSilenceJob = null
        activeComposer?.cancelSession(rollbackWrittenText = false)
        activeComposer = null
        if (host.stateFlow.value.voicePhase is VoicePhase.Recording) {
            micPressDownTimeMs = 0L
            abortActiveStreamingSession()
            val recorder = runCatching { audioRecorderProvider() }.getOrNull()
            if (recorder != null) {
                host.scope.launch {
                    recorder.cancelRecording()
                }
            }
        }
        if (host.stateFlow.value.liveWriteStatus != LiveWriteUiStatus.INACTIVE) {
            host.stateFlow.update { it.copy(liveWriteStatus = LiveWriteUiStatus.INACTIVE) }
        }
    }

    fun onDestroy() {
        micPressDownTimeMs = 0L
        handsFreeSilenceJob?.cancel()
        handsFreeSilenceJob = null
        activeComposer?.cancelSession(rollbackWrittenText = false)
        activeComposer = null
        abortActiveStreamingSession()
        host.aiEngine.evictLiveStreamingPrewarm()
        activePipelineJob?.cancel()
        toastDismissJob?.cancel()
    }

    companion object {
        const val HANDS_FREE_SILENCE_AUTO_STOP_MS: Long = 8_000L
        const val HANDS_FREE_SILENCE_AMPLITUDE_THRESHOLD: Float = 0.12f

        fun isSilentAmplitudeFrame(
            amplitudes: List<Float>,
            threshold: Float = HANDS_FREE_SILENCE_AMPLITUDE_THRESHOLD
        ): Boolean {
            return (amplitudes.lastOrNull() ?: 0.08f) <= threshold
        }
    }
}
