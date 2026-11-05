package com.typefree.ime.ime

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.typefree.ime.TypeFreeApp
import com.typefree.ime.audio.WavAudioRecorder
import com.typefree.ime.data.DefaultPresets
import com.typefree.ime.data.HistoryRecord
import com.typefree.ime.data.HistoryRetention
import com.typefree.ime.data.PipelineMode
import com.typefree.ime.data.ToneStyle
import com.typefree.ime.data.VoiceIntentMode
import com.typefree.ime.ime.flypy.FlypyAction
import com.typefree.ime.ime.flypy.FlypyEngine
import com.typefree.ime.network.PipelineStage
import com.typefree.ime.ui.AppDestinations
import com.typefree.ime.ui.theme.TypeFreeTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

class TypeFreeInputMethodService : ComposeInputMethodService() {

    private val repository
        get() = TypeFreeApp.instance.repository

    private val aiEngine
        get() = TypeFreeApp.instance.aiEngine

    private lateinit var audioRecorder: WavAudioRecorder

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var activePipelineJob: Job? = null
    private var toastDismissJob: Job? = null
    private var autoLearnToastJob: Job? = null
    private var quickToastJob: Job? = null
    private var freshClipBannerJob: Job? = null

    private var clipboardManager: ClipboardManager? = null
    private var lastObservedSystemClip: String? = null
    private val primaryClipListener = ClipboardManager.OnPrimaryClipChangedListener {
        syncSystemClipboardIfNeeded(allowFreshBanner = true)
    }

    private val flypyEngine = FlypyEngine()
    private val undoStack = ArrayDeque<UndoSnapshot>()

    @Volatile
    private var micPressDownTimeMs: Long = 0L

    // Auto-Learned Dictionary (`Auto-added`) tracking state
    private var lastDictatedFullText: String? = null
    private var lastDictatedAtMs: Long = 0L
    private var hasManualEditSinceDictation: Boolean = false

    private val _imeState = MutableStateFlow(ImeUiState())
    val imeState = _imeState.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        audioRecorder = WavAudioRecorder(this)
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        runCatching {
            clipboardManager?.addPrimaryClipChangedListener(primaryClipListener)
        }
        syncSystemClipboardIfNeeded(allowFreshBanner = false)
        TypeFreeApp.instance.ensureFlypyDictionaryLoaded()
        serviceScope.launch {
            TypeFreeApp.instance.flypyDictionary.collect { dict ->
                if (dict.isLoaded) {
                    flypyEngine.updateDictionary(dict)
                    syncFlypyStateToUi()
                }
            }
        }
        serviceScope.launch {
            var lastFlypySetting: Boolean? = null
            repository.settings.collect { currentSettings ->
                val prevSetting = lastFlypySetting
                lastFlypySetting = currentSettings.flypyEnabled
                if (prevSetting != null && prevSetting != currentSettings.flypyEnabled) {
                    flypyEngine.resetComposing()
                    val isPwd = ImeLogic.isPasswordEditorInputType(_imeState.value.currentInputType)
                    _imeState.update {
                        it.copy(
                            isFlypyChineseMode = currentSettings.flypyEnabled && !isPwd,
                            flypyComposingCode = "",
                            flypyCandidates = emptyList(),
                            isFlypyCandidateSheetExpanded = false
                        )
                    }
                }
            }
        }
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        if (!restarting) {
            lastDictatedFullText = null
            lastDictatedAtMs = 0L
            hasManualEditSinceDictation = false
            flypyEngine.resetComposing()
            undoStack.clear()
        }
        syncEditorMetadata(attribute, restarting)
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        val wasVisible = repository.isImeWindowVisible.value
        repository.setImeWindowVisible(true)
        if (!restarting && !wasVisible) {
            flypyEngine.resetComposing()
            _imeState.update {
                it.copy(
                    tabMode = KeyboardTabMode.VOICE,
                    isClipboardOpen = false,
                    flypyComposingCode = "",
                    flypyCandidates = emptyList(),
                    isFlypyCandidateSheetExpanded = false
                )
            }
        }
        syncEditorMetadata(info, restarting)
        syncSystemClipboardIfNeeded(allowFreshBanner = true)
    }

    override fun onWindowShown() {
        super.onWindowShown()
        repository.setImeWindowVisible(true)
        syncSystemClipboardIfNeeded(allowFreshBanner = true)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        tryAutoLearnFromRecentEdit()
        repository.setImeWindowVisible(false)
        super.onFinishInputView(finishingInput)
    }

    private fun syncEditorMetadata(info: EditorInfo?, restarting: Boolean) {
        val pkg = info?.packageName.orEmpty()
        val appName = repository.resolveAppName(pkg)
        val inputType = info?.inputType ?: 0
        val imeOptions = info?.imeOptions ?: 0
        val selected = readSelectedTextSafely()
        val hasMicPerm = ::audioRecorder.isInitialized && audioRecorder.hasRecordPermission()
        val hasCachedWav = hasRetryWavFile()
        val isPasswordField = ImeLogic.isPasswordEditorInputType(inputType)
        val defaultFlypyChinese = repository.settings.value.flypyEnabled && !isPasswordField

        _imeState.update { prev ->
            val currentPhase = prev.voicePhase
            val clearedPhase = if (
                currentPhase is VoicePhase.Error &&
                currentPhase.needsMicPermission &&
                hasMicPerm
            ) {
                VoicePhase.Idle
            } else {
                currentPhase
            }
            val nextIgnore = if (!restarting || selected.isBlank()) {
                false
            } else {
                prev.ignoreSelectionForThisTurn
            }
            val nextFlypyMode = when {
                isPasswordField -> false
                !restarting -> defaultFlypyChinese
                else -> prev.isFlypyChineseMode
            }
            prev.copy(
                voicePhase = clearedPhase,
                targetPackage = pkg,
                targetAppName = appName,
                currentInputType = inputType,
                currentImeOptions = imeOptions,
                rawSelectedText = selected,
                ignoreSelectionForThisTurn = nextIgnore,
                hasCachedAudioForRetry = hasCachedWav,
                isFlypyChineseMode = nextFlypyMode,
                flypyComposingCode = if (!restarting) "" else prev.flypyComposingCode,
                flypyCandidates = if (!restarting) emptyList() else prev.flypyCandidates,
                isFlypyCandidateSheetExpanded = if (!restarting) false else prev.isFlypyCandidateSheetExpanded,
                undoSnapshot = if (!restarting) null else prev.undoSnapshot
            )
        }
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int
    ) {
        super.onUpdateSelection(
            oldSelStart,
            oldSelEnd,
            newSelStart,
            newSelEnd,
            candidatesStart,
            candidatesEnd
        )
        val selected = if (newSelStart != newSelEnd && newSelStart >= 0 && newSelEnd >= 0) {
            readSelectedTextSafely()
        } else {
            ""
        }
        _imeState.update { prev ->
            prev.copy(
                rawSelectedText = selected,
                ignoreSelectionForThisTurn = if (selected.isBlank()) {
                    false
                } else {
                    prev.ignoreSelectionForThisTurn
                }
            )
        }
    }

    override fun onWindowHidden() {
        tryAutoLearnFromRecentEdit()
        repository.setImeWindowVisible(false)
        super.onWindowHidden()
        // Never leave microphone recording silently when the keyboard window is dismissed
        if (_imeState.value.voicePhase is VoicePhase.Recording) {
            micPressDownTimeMs = 0L
            serviceScope.launch {
                audioRecorder.cancelRecording()
            }
        }
        flypyEngine.resetComposing()
        _imeState.update { prev ->
            prev.copy(
                tabMode = KeyboardTabMode.VOICE,
                voicePhase = if (prev.voicePhase is VoicePhase.Recording) VoicePhase.Idle else prev.voicePhase,
                isArcTranslateActive = false,
                arcStartedFromHandsFree = false,
                isArcCancelHovered = false,
                isStylePickerExpanded = false,
                isPunctuationStripOpen = false,
                isHistoryDrawerOpen = false,
                isClipboardOpen = false,
                flypyComposingCode = "",
                flypyCandidates = emptyList(),
                isFlypyCandidateSheetExpanded = false
            )
        }
    }

    override fun onDestroy() {
        repository.setImeWindowVisible(false)
        micPressDownTimeMs = 0L
        activePipelineJob?.cancel()
        toastDismissJob?.cancel()
        autoLearnToastJob?.cancel()
        quickToastJob?.cancel()
        freshClipBannerJob?.cancel()
        runCatching {
            clipboardManager?.removePrimaryClipChangedListener(primaryClipListener)
        }
        if (::audioRecorder.isInitialized) {
            serviceScope.launch {
                audioRecorder.cancelRecording()
            }
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    @Composable
    override fun KeyboardContent() {
        val settings by repository.settings.collectAsState()
        val uiState by imeState.collectAsState()
        val clipboardItems by repository.clipboardItems.collectAsState()
        val amplitudes by audioRecorder.amplitudes.collectAsState()
        val elapsedMs by audioRecorder.elapsedMs.collectAsState()

        val (activeStyle, isAutoMatched) = remember(
            settings,
            uiState.targetPackage,
            uiState.currentInputType
        ) {
            repository.resolveActiveStyle(uiState.targetPackage, uiState.currentInputType)
        }

        TypeFreeTheme(themePreference = settings.themePreference) {
            KeyboardScreen(
                uiState = uiState,
                settings = settings,
                activeStyle = activeStyle,
                isAutoMatched = isAutoMatched,
                amplitudes = amplitudes,
                elapsedMs = elapsedMs,
                onSelectTabMode = ::selectTabMode,
                onOpenHelpMeWrite = ::openOrRestoreVoicePenPanel,
                onCloseSubPanel = ::closeVoiceSubPanel,
                onDismissSpeakToEdit = ::dismissSpeakToEditForTurn,
                onSelectTranslateLanguage = ::selectTranslateLanguage,
                onTogglePunctuationStrip = ::togglePunctuationStrip,
                onToggleStylePicker = ::toggleStylePicker,
                onSelectAutoStyle = ::selectAutoStyle,
                onSelectManualStyle = ::selectManualStyle,
                onPerformUndo = ::performUndo,
                onOpenHostApp = ::openHostApp,
                onQuickEditChipClick = ::runQuickEditChip,
                onHelpWritePresetClick = ::runHelpWritePreset,
                onMicPressDown = ::handleMicPressDown,
                onMicPressUp = ::handleMicPressUp,
                onEnterArcTranslate = ::enterArcTranslateMode,
                onUpdateArcTranslate = ::updateArcTranslateSelection,
                onReleaseArcTranslate = ::releaseArcTranslate,
                onFinishHandsFree = ::finishHandsFreeRecording,
                onCancelRecording = ::cancelRecordingOrProcessing,
                onRetryLastAudio = ::retryLastAudio,
                onDismissError = ::dismissErrorBanner,
                onCommitText = { text -> commitDirectText(text, recordUndo = true, markManualEdit = true) },
                onCommitQwertyChar = ::commitQwertyChar,
                onCommaOrPeriodKey = ::handleCommaOrPeriodKey,
                onToggleFlypyLanguageMode = ::toggleFlypyLanguageMode,
                onSelectFlypyCandidate = ::selectFlypyCandidate,
                onClearFlypyComposing = ::clearFlypyComposing,
                onFlushFlypyComposing = ::flushFlypyComposing,
                onToggleFlypyCandidateSheet = ::toggleFlypyCandidateSheet,
                onEditorShortcutAction = ::handleEditorShortcutAction,
                onInsertPairedSymbol = ::insertPairedSymbol,
                onHideKeyboard = ::hideKeyboardSelf,
                onSwitchKeyboard = ::switchKeyboard,
                onShowImePicker = ::showImePicker,
                onInsertSpace = ::insertSpace,
                onBackspace = ::deleteBackward,
                onEnterOrNewline = ::performEnterOrNewline,
                clipboardItems = clipboardItems,
                onOpenClipboard = ::openClipboardPanel,
                onCloseClipboard = ::closeClipboardPanel,
                onPasteClipboardText = ::pasteFromClipboard,
                onTogglePinClipboardItem = ::togglePinClipboardEntry,
                onDeleteClipboardItem = ::deleteClipboardEntry,
                onClearUnpinnedClipboard = ::clearUnpinnedClipboardEntries,
                onSaveSelectionToClipboard = ::saveCurrentSelectionToClipboard,
                onEnableClipboard = ::enableClipboardFromKeyboard,
                onOpenClipboardSettings = ::openClipboardPreferencesInHostApp
            )
        }
    }

    // -------------------------------------------------------------------------
    // Mode Switching, Sub-Panels & Arc Translate Wheel
    // -------------------------------------------------------------------------

    private fun selectTabMode(mode: KeyboardTabMode) {
        val prevMode = _imeState.value.tabMode
        if (prevMode == mode && !_imeState.value.isClipboardOpen) return

        performKeyFeedback(strong = false)
        if (mode == KeyboardTabMode.QWERTY && !flypyEngine.dictionary.isLoaded) {
            TypeFreeApp.instance.ensureFlypyDictionaryLoaded()
        }

        if (prevMode == KeyboardTabMode.QWERTY && mode != KeyboardTabMode.QWERTY) {
            flypyEngine.resetComposing()
            tryAutoLearnFromRecentEdit()
        }

        if (_imeState.value.voicePhase is VoicePhase.Recording) {
            micPressDownTimeMs = 0L
            serviceScope.launch {
                audioRecorder.cancelRecording()
            }
        }

        _imeState.update { prev ->
            prev.copy(
                tabMode = mode,
                voicePhase = if (prev.voicePhase is VoicePhase.Recording) VoicePhase.Idle else prev.voicePhase,
                voiceSubPanel = VoiceSubPanel.NONE,
                isArcTranslateActive = false,
                arcStartedFromHandsFree = false,
                isArcCancelHovered = false,
                isPunctuationStripOpen = false,
                isStylePickerExpanded = false,
                isClipboardOpen = false,
                flypyComposingCode = "",
                flypyCandidates = emptyList(),
                isFlypyCandidateSheetExpanded = false
            )
        }
    }

    private fun openOrRestoreVoicePenPanel() {
        performKeyFeedback(strong = false)
        _imeState.update { prev ->
            if (prev.rawSelectedText.isNotBlank()) {
                prev.copy(
                    tabMode = KeyboardTabMode.VOICE,
                    ignoreSelectionForThisTurn = false,
                    voiceSubPanel = VoiceSubPanel.SPEAK_TO_EDIT,
                    isPunctuationStripOpen = false,
                    isStylePickerExpanded = false
                )
            } else {
                prev.copy(
                    tabMode = KeyboardTabMode.VOICE,
                    voiceSubPanel = VoiceSubPanel.HELP_ME_WRITE,
                    isPunctuationStripOpen = false,
                    isStylePickerExpanded = false
                )
            }
        }
    }

    private fun closeVoiceSubPanel() {
        performKeyFeedback(strong = false)
        if (_imeState.value.voicePhase is VoicePhase.Recording ||
            _imeState.value.voicePhase is VoicePhase.Processing
        ) {
            cancelRecordingOrProcessing()
        }
        _imeState.update {
            it.copy(
                voiceSubPanel = VoiceSubPanel.NONE,
                activeIntentMode = VoiceIntentMode.DICTATE
            )
        }
    }

    private fun dismissSpeakToEditForTurn() {
        performKeyFeedback(strong = false)
        if (_imeState.value.voicePhase is VoicePhase.Recording ||
            _imeState.value.voicePhase is VoicePhase.Processing
        ) {
            cancelRecordingOrProcessing()
        }
        _imeState.update {
            it.copy(
                ignoreSelectionForThisTurn = true,
                voiceSubPanel = VoiceSubPanel.NONE,
                activeIntentMode = VoiceIntentMode.DICTATE
            )
        }
    }

    private fun selectTranslateLanguage(language: String) {
        if (language.isBlank()) return
        performKeyFeedback(strong = false)
        repository.updateSettings {
            it.copy(activeTranslateTarget = language)
        }
        _imeState.update {
            it.copy(arcSelectedLanguage = language)
        }
    }

    private fun togglePunctuationStrip() {
        performKeyFeedback(strong = false)
        _imeState.update {
            it.copy(
                isPunctuationStripOpen = !it.isPunctuationStripOpen,
                isStylePickerExpanded = false
            )
        }
    }

    private fun enterArcTranslateMode(startedFromHandsFree: Boolean) {
        val curr = _imeState.value
        if (curr.voicePhase !is VoicePhase.Recording) return
        val settings = repository.settings.value
        val defaultLang = settings.activeTranslateTarget.ifBlank {
            settings.translationTargets.firstOrNull() ?: DefaultPresets.defaultTranslationTargets().first()
        }
        performHaptic(strong = false)
        _imeState.update {
            it.copy(
                isArcTranslateActive = true,
                arcStartedFromHandsFree = startedFromHandsFree,
                isArcCancelHovered = false,
                arcSelectedLanguage = defaultLang,
                activeIntentMode = VoiceIntentMode.TRANSLATE,
                isPunctuationStripOpen = false,
                isStylePickerExpanded = false
            )
        }
    }

    private fun updateArcTranslateSelection(language: String, cancelHovered: Boolean) {
        val prev = _imeState.value
        if (!prev.isArcTranslateActive) return
        val changed = prev.arcSelectedLanguage != language || prev.isArcCancelHovered != cancelHovered
        if (!changed) return

        performHaptic(strong = false)
        _imeState.update {
            it.copy(
                arcSelectedLanguage = language,
                isArcCancelHovered = cancelHovered
            )
        }
    }

    private fun releaseArcTranslate() {
        val snapshot = _imeState.value
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
            repository.settings.value.activeTranslateTarget.ifBlank { "English (US)" }
        }
        repository.updateSettings {
            it.copy(activeTranslateTarget = chosenLang)
        }

        if (ImeLogic.shouldLockArcIntoHandsFree(snapshot.arcStartedFromHandsFree)) {
            // Started from Idle (`上滑选语言松开 → 开口说话 → 轻点完成`):
            // Fire haptic first, then unconditionally wipe pre-release swipe/haptic audio and reset 00:00 timer!
            performHaptic(strong = false)
            if (::audioRecorder.isInitialized) {
                audioRecorder.clearSilencePrefix(skipHapticMs = 120L)
            }
            _imeState.update {
                it.copy(
                    voicePhase = VoicePhase.Recording(isHandsFree = true),
                    isArcTranslateActive = false,
                    arcStartedFromHandsFree = false,
                    isArcCancelHovered = false,
                    arcSelectedLanguage = chosenLang,
                    activeIntentMode = VoiceIntentMode.TRANSLATE
                )
            }
        } else {
            // Started from already-active Hands-Free recording (`轻点先说话 → 上滑选语言松手立即直译`):
            performKeyFeedback(strong = true)
            _imeState.update {
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

    // -------------------------------------------------------------------------
    // Dual Mic Interaction (Push-to-Talk >= 280ms vs Short-Tap Hands-Free < 280ms)
    // -------------------------------------------------------------------------

    private fun handleMicPressDown(intentMode: VoiceIntentMode = VoiceIntentMode.DICTATE) {
        val currentPhase = _imeState.value.voicePhase
        if (currentPhase is VoicePhase.Processing) return
        if (currentPhase is VoicePhase.Recording && currentPhase.isHandsFree) return

        tryAutoLearnFromRecentEdit()
        toastDismissJob?.cancel()

        val defaultTranslateLang = repository.settings.value.activeTranslateTarget
        _imeState.update {
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

        if (!audioRecorder.hasRecordPermission()) {
            micPressDownTimeMs = 0L
            performHaptic(strong = true)
            _imeState.update {
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
        performHaptic(strong = true)

        val startRes = audioRecorder.startRecording(serviceScope)
        startRes.fold(
            onSuccess = {
                micPressDownTimeMs = System.currentTimeMillis()
                _imeState.update {
                    it.copy(voicePhase = VoicePhase.Recording(isHandsFree = false))
                }
            },
            onFailure = { err ->
                micPressDownTimeMs = 0L
                performHaptic(strong = true)
                val errorPhase = ImeLogic.classifyErrorFlags(
                    errorMessage = err.message ?: "无法启动麦克风录音",
                    hasCachedAudio = hasRetryWavFile(),
                    hasMicPermission = audioRecorder.hasRecordPermission()
                )
                _imeState.update { it.copy(voicePhase = errorPhase) }
            }
        )
    }

    private fun handleMicPressUp() {
        if (_imeState.value.isArcTranslateActive) {
            releaseArcTranslate()
            return
        }

        val downTime = micPressDownTimeMs
        micPressDownTimeMs = 0L
        val phase = _imeState.value.voicePhase
        if (downTime <= 0L || phase !is VoicePhase.Recording || phase.isHandsFree) {
            return
        }

        val heldDurationMs = System.currentTimeMillis() - downTime
        if (ImeLogic.isPressAndHoldGesture(heldDurationMs)) {
            // Press-and-Hold (>=280ms): stop immediately and run AI pipeline
            performKeyFeedback(strong = false)
            stopRecordingAndRunPipeline()
        } else {
            // Short Tap (<280ms): keep recording seamlessly in Hands-Free mode
            performHaptic(strong = false)
            _imeState.update {
                it.copy(voicePhase = VoicePhase.Recording(isHandsFree = true))
            }
        }
    }

    private fun finishHandsFreeRecording() {
        val snapshot = _imeState.value
        if (snapshot.voicePhase !is VoicePhase.Recording) return
        performKeyFeedback(strong = true)
        if (snapshot.activeIntentMode == VoiceIntentMode.TRANSLATE) {
            val targetLang = snapshot.arcSelectedLanguage.ifBlank {
                repository.settings.value.activeTranslateTarget
            }
            stopRecordingAndRunPipeline(
                intentOverride = VoiceIntentMode.TRANSLATE,
                translateLangOverride = targetLang
            )
        } else {
            stopRecordingAndRunPipeline()
        }
    }

    private fun cancelRecordingOrProcessing() {
        performKeyFeedback(strong = false)
        micPressDownTimeMs = 0L
        activePipelineJob?.cancel()
        activePipelineJob = null
        serviceScope.launch {
            audioRecorder.cancelRecording()
        }
        _imeState.update {
            it.copy(
                voicePhase = VoicePhase.Idle,
                isArcTranslateActive = false,
                arcStartedFromHandsFree = false,
                isArcCancelHovered = false
            )
        }
    }

    private fun stopRecordingAndRunPipeline(
        intentOverride: VoiceIntentMode? = null,
        translateLangOverride: String? = null
    ) {
        activePipelineJob?.cancel()
        activePipelineJob = serviceScope.launch {
            val stopResult = audioRecorder.stopRecording()
            stopResult.fold(
                onSuccess = { recorded ->
                    executeVoicePipelineOnWav(
                        wavFile = recorded.wavFile,
                        durationMs = recorded.durationMs,
                        intentOverride = intentOverride,
                        translateLangOverride = translateLangOverride
                    )
                },
                onFailure = { err ->
                    if (err is CancellationException) return@launch
                    val errorPhase = ImeLogic.classifyErrorFlags(
                        errorMessage = err.message ?: "录音时间过短，请重试",
                        hasCachedAudio = hasRetryWavFile(),
                        hasMicPermission = audioRecorder.hasRecordPermission()
                    )
                    _imeState.update {
                        it.copy(
                            voicePhase = errorPhase,
                            isArcTranslateActive = false,
                            arcStartedFromHandsFree = false,
                            isArcCancelHovered = false,
                            hasCachedAudioForRetry = errorPhase.canRetryAudio
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
        translateLangOverride: String? = null
    ) {
        val settings = repository.settings.value
        val snapshot = _imeState.value
        val targetPkg = snapshot.targetPackage
        val targetAppName = snapshot.targetAppName
        val (activeStyle, _) = repository.resolveActiveStyle(targetPkg, snapshot.currentInputType)

        val liveSelection = readSelectedTextSafely().ifBlank { snapshot.rawSelectedText }
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
        val surroundingContext = readSurroundingBeforeCursor(80)

        val initialStage = if (settings.pipelineMode == PipelineMode.MULTIMODAL_DIRECT) {
            PipelineStage.MULTIMODAL_PROCESSING
        } else {
            PipelineStage.TRANSCRIBING_STT
        }

        _imeState.update {
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
                hasCachedAudioForRetry = wavFile.exists()
            )
        }

        val pipelineResult = aiEngine.processVoiceAudio(
            wavFile = wavFile,
            settings = settings,
            activeStyle = activeStyle,
            targetAppName = targetAppName,
            selectedText = if (isSpeakToEdit) effectiveSelected else "",
            surroundingBeforeCursor = surroundingContext,
            intentMode = resolvedIntent,
            translateTargetLanguage = targetTranslateLang.ifBlank { settings.activeTranslateTarget },
            onStageChanged = { stage ->
                _imeState.update { curr ->
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
                val overwritten = if (isSpeakToEdit) liveSelection else readSelectedTextSafely()
                commitPipelineOutput(
                    finalText = finalOutput,
                    overwrittenSelection = overwritten,
                    latencyMs = exec.latencyMs
                )
                val historyAudioFile = archiveWavForHistory(wavFile, settings.historyRetention)
                repository.addHistory(
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
            },
            onFailure = { err ->
                if (err is CancellationException) return
                performHaptic(strong = true)
                val errorPhase = ImeLogic.classifyErrorFlags(
                    errorMessage = err.message ?: "语音处理失败，请检查网络或 API 配置",
                    hasCachedAudio = wavFile.exists(),
                    hasMicPermission = audioRecorder.hasRecordPermission()
                )
                _imeState.update {
                    it.copy(
                        voicePhase = errorPhase,
                        hasCachedAudioForRetry = errorPhase.canRetryAudio
                    )
                }
            }
        )
    }

    // -------------------------------------------------------------------------
    // Speak-to-Edit 1-Tap Quick Chips, Help-me-Write Presets & Retry
    // -------------------------------------------------------------------------

    private fun runQuickEditChip(chip: QuickEditChip) {
        val currPhase = _imeState.value.voicePhase
        if (currPhase is VoicePhase.Recording || currPhase is VoicePhase.Processing) return

        val liveSelected = readSelectedTextSafely().ifBlank { _imeState.value.effectiveSelectedText }
        if (liveSelected.isBlank()) return

        performKeyFeedback(strong = false)
        toastDismissJob?.cancel()
        activePipelineJob?.cancel()

        activePipelineJob = serviceScope.launch {
            val settings = repository.settings.value
            val snapshot = _imeState.value
            val targetPkg = snapshot.targetPackage
            val targetAppName = snapshot.targetAppName
            val (activeStyle, _) = repository.resolveActiveStyle(targetPkg, snapshot.currentInputType)

            _imeState.update {
                it.copy(voicePhase = VoicePhase.Processing(stageText = "${chip.label}中..."))
            }

            val transformResult = aiEngine.runTextTransform(
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
                    repository.addHistory(
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
                },
                onFailure = { err ->
                    if (err is CancellationException) return@launch
                    performHaptic(strong = true)
                    val errorPhase = ImeLogic.classifyErrorFlags(
                        errorMessage = err.message ?: "改写失败，请检查网络或 API 配置",
                        hasCachedAudio = false,
                        hasMicPermission = audioRecorder.hasRecordPermission()
                    )
                    _imeState.update { it.copy(voicePhase = errorPhase) }
                }
            )
        }
    }

    private fun runHelpWritePreset(preset: HelpWritePromptPreset) {
        val currPhase = _imeState.value.voicePhase
        if (currPhase is VoicePhase.Recording || currPhase is VoicePhase.Processing) return

        performKeyFeedback(strong = false)
        toastDismissJob?.cancel()
        activePipelineJob?.cancel()

        activePipelineJob = serviceScope.launch {
            val settings = repository.settings.value
            val snapshot = _imeState.value
            val targetPkg = snapshot.targetPackage
            val targetAppName = snapshot.targetAppName
            val (activeStyle, _) = repository.resolveActiveStyle(targetPkg, snapshot.currentInputType)

            _imeState.update {
                it.copy(voicePhase = VoicePhase.Processing(stageText = "正在起草：${preset.label}..."))
            }

            val draftInstruction =
                "请忽略<selected_text>中的占位文字，直接根据以下写作需求起草完整、自然得体、可直接发送的成稿正文（绝不输出任何寒暄或解释）：${preset.instruction}"

            val transformResult = aiEngine.runTextTransform(
                settings = settings,
                activeStyle = activeStyle,
                rawOrSelectedText = preset.label,
                explicitInstruction = draftInstruction,
                targetAppName = targetAppName
            )

            transformResult.fold(
                onSuccess = { exec ->
                    val finalOutput = exec.finalOutput
                    val existingSel = readSelectedTextSafely()
                    commitPipelineOutput(
                        finalText = finalOutput,
                        overwrittenSelection = existingSel,
                        latencyMs = exec.latencyMs
                    )
                    repository.addHistory(
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
                },
                onFailure = { err ->
                    if (err is CancellationException) return@launch
                    performHaptic(strong = true)
                    val errorPhase = ImeLogic.classifyErrorFlags(
                        errorMessage = err.message ?: "起草失败，请检查网络或 API 配置",
                        hasCachedAudio = false,
                        hasMicPermission = audioRecorder.hasRecordPermission()
                    )
                    _imeState.update { it.copy(voicePhase = errorPhase) }
                }
            )
        }
    }

    fun retryLastAudio() {
        val currPhase = _imeState.value.voicePhase
        if (currPhase is VoicePhase.Recording || currPhase is VoicePhase.Processing) return

        val wavFile = WavAudioRecorder.getLastRecordingFile(this)
        if (!wavFile.exists() || wavFile.length() <= 44L) {
            _imeState.update {
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

        performKeyFeedback(strong = false)
        val estimatedDurationMs =
            ((wavFile.length() - 44L).coerceAtLeast(0L) * 1000L) / (WavAudioRecorder.SAMPLE_RATE * 2L)

        activePipelineJob?.cancel()
        activePipelineJob = serviceScope.launch {
            executeVoicePipelineOnWav(wavFile, estimatedDurationMs)
        }
    }

    private fun dismissErrorBanner() {
        performKeyFeedback(strong = false)
        _imeState.update {
            if (it.voicePhase is VoicePhase.Error) {
                it.copy(voicePhase = VoicePhase.Idle)
            } else {
                it
            }
        }
    }

    // -------------------------------------------------------------------------
    // Commit, Undo, Auto-Learned Dictionary (`Auto-added`), Style Selector
    // -------------------------------------------------------------------------

    private fun commitPipelineOutput(
        finalText: String,
        overwrittenSelection: String,
        latencyMs: Long
    ) {
        val ic = currentInputConnection
        if (ic != null) {
            ic.beginBatchEdit()
            ic.commitText(finalText, 1)
            ic.endBatchEdit()
        }

        // Snapshot full editor buffer right after dictation commits for Auto-Learned Dictionary diffing
        val fullBufferAfterCommit = readFullEditorTextSafely().ifBlank { finalText }
        lastDictatedFullText = fullBufferAfterCommit
        lastDictatedAtMs = System.currentTimeMillis()
        hasManualEditSinceDictation = false

        performHaptic(strong = true)
        flypyEngine.recordExternalCommit(finalText)
        val undo = ImeLogic.createUndoSnapshot(
            committedText = finalText,
            replacedSelection = overwrittenSelection
        )
        pushUndoSnapshot(undo)

        _imeState.update {
            it.copy(
                voicePhase = VoicePhase.SuccessToast(
                    preview = ImeLogic.formatSelectionPreview(finalText, 24),
                    latencyMs = latencyMs
                ),
                voiceSubPanel = VoiceSubPanel.NONE,
                activeIntentMode = VoiceIntentMode.DICTATE,
                undoSnapshot = undoStack.firstOrNull(),
                rawSelectedText = "",
                ignoreSelectionForThisTurn = false
            )
        }
        scheduleToastReset()
    }

    // -------------------------------------------------------------------------
    // Built-in Xiaohe Yinxing (小鹤音形) & QWERTY Handlers
    // -------------------------------------------------------------------------

    private fun syncFlypyStateToUi() {
        val code = flypyEngine.composingCode
        val cands = flypyEngine.candidates
        _imeState.update { prev ->
            prev.copy(
                flypyComposingCode = code,
                flypyCandidates = cands,
                isFlypyCandidateSheetExpanded = if (code.isEmpty() || cands.size <= 4) {
                    false
                } else {
                    prev.isFlypyCandidateSheetExpanded
                }
            )
        }
    }

    private fun handleFlypyAction(action: FlypyAction) {
        when (action) {
            FlypyAction.ConsumedComposing -> {
                syncFlypyStateToUi()
            }
            is FlypyAction.CommitText -> {
                syncFlypyStateToUi()
                if (action.committed.isNotEmpty()) {
                    commitDirectText(
                        text = action.committed,
                        recordUndo = true,
                        markManualEdit = true,
                        playFeedback = false
                    )
                }
            }
        }
    }

    private fun toggleFlypyLanguageMode() {
        performKeyFeedback(strong = true)
        val wasChinese = _imeState.value.isFlypyChineseMode
        if (wasChinese && flypyEngine.isComposing) {
            val rawAscii = flypyEngine.onEnterWhenComposing()
            if (!rawAscii.isNullOrEmpty()) {
                commitDirectText(
                    text = rawAscii,
                    recordUndo = true,
                    markManualEdit = true,
                    playFeedback = false
                )
            }
        } else {
            flypyEngine.resetComposing()
        }
        val nextChinese = !wasChinese
        _imeState.update {
            it.copy(
                isFlypyChineseMode = nextChinese,
                flypyComposingCode = "",
                flypyCandidates = emptyList(),
                isFlypyCandidateSheetExpanded = false
            )
        }
        showQuickActionToast(if (nextChinese) "鹤 · 小鹤音形" else "EN · 英文键盘")
    }

    private fun commitQwertyChar(ch: String) {
        if (ch.isEmpty()) return
        val curr = _imeState.value
        if (curr.tabMode == KeyboardTabMode.QWERTY && curr.isFlypyChineseMode) {
            if (ch.length == 1) {
                performKeyFeedback(strong = false)
                val action = flypyEngine.onCharInput(ch[0])
                handleFlypyAction(action)
                return
            } else {
                val flushed = flypyEngine.flushTopCandidateOrClear()
                syncFlypyStateToUi()
                commitDirectText(
                    text = flushed + ch,
                    recordUndo = true,
                    markManualEdit = true
                )
                return
            }
        }
        commitDirectText(text = ch, recordUndo = true, markManualEdit = true)
    }

    private fun handleCommaOrPeriodKey(isComma: Boolean) {
        val curr = _imeState.value
        if (curr.tabMode == KeyboardTabMode.QWERTY && curr.isFlypyChineseMode) {
            performKeyFeedback(strong = false)
            val quickSelect = repository.settings.value.flypyCommaPeriodQuickSelect
            val action = flypyEngine.onCommaOrPeriod(
                isComma = isComma,
                commaPeriodQuickSelect = quickSelect
            )
            handleFlypyAction(action)
            return
        }
        commitDirectText(
            text = if (isComma) "," else ".",
            recordUndo = true,
            markManualEdit = true
        )
    }

    private fun selectFlypyCandidate(index: Int) {
        performKeyFeedback(strong = false)
        val action = flypyEngine.selectCandidate(index)
        handleFlypyAction(action)
    }

    private fun clearFlypyComposing() {
        performKeyFeedback(strong = false)
        if (flypyEngine.clearComposing()) {
            syncFlypyStateToUi()
        }
    }

    private fun flushFlypyComposing() {
        val flushed = flypyEngine.flushTopCandidateOrClear()
        syncFlypyStateToUi()
        if (flushed.isNotEmpty()) {
            commitDirectText(
                text = flushed,
                recordUndo = true,
                markManualEdit = true,
                playFeedback = false
            )
        }
    }

    private fun toggleFlypyCandidateSheet() {
        performKeyFeedback(strong = false)
        _imeState.update {
            it.copy(isFlypyCandidateSheetExpanded = !it.isFlypyCandidateSheetExpanded)
        }
    }

    private fun handleEditorShortcutAction(action: EditorShortcutAction) {
        flypyEngine.resetComposing()
        syncFlypyStateToUi()
        performHaptic(strong = true)
        val ic = currentInputConnection ?: return
        when (action) {
            EditorShortcutAction.SELECT_ALL -> {
                runCatching { ic.performContextMenuAction(android.R.id.selectAll) }
                showQuickActionToast("✓ 已全选")
            }
            EditorShortcutAction.CUT -> {
                runCatching { ic.performContextMenuAction(android.R.id.cut) }
                recordManualEditTouch()
                syncSystemClipboardIfNeeded(allowFreshBanner = true)
                showQuickActionToast("✓ 已剪切")
            }
            EditorShortcutAction.COPY -> {
                runCatching { ic.performContextMenuAction(android.R.id.copy) }
                syncSystemClipboardIfNeeded(allowFreshBanner = true)
                showQuickActionToast("✓ 已复制")
            }
            EditorShortcutAction.PASTE -> {
                val handled = runCatching {
                    ic.performContextMenuAction(android.R.id.paste)
                }.getOrDefault(false)
                if (!handled) {
                    val clipText = runCatching {
                        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        cm?.primaryClip?.takeIf { it.itemCount > 0 }
                            ?.getItemAt(0)
                            ?.coerceToText(this)
                            ?.toString()
                            .orEmpty()
                    }.getOrDefault("")
                    if (clipText.isNotEmpty()) {
                        commitDirectText(
                            text = clipText,
                            recordUndo = true,
                            markManualEdit = true,
                            playFeedback = false
                        )
                    }
                } else {
                    recordManualEditTouch()
                }
                showQuickActionToast("✓ 已粘贴")
            }
        }
    }

    private fun showQuickActionToast(message: String) {
        quickToastJob?.cancel()
        _imeState.update { it.copy(quickActionToast = message) }
        quickToastJob = serviceScope.launch {
            delay(1600L)
            _imeState.update { curr ->
                if (curr.quickActionToast == message) {
                    curr.copy(quickActionToast = null)
                } else {
                    curr
                }
            }
        }
    }

    private fun pushUndoSnapshot(snapshot: UndoSnapshot?) {
        if (snapshot == null) return
        undoStack.addFirst(snapshot)
        while (undoStack.size > 12) {
            undoStack.removeLast()
        }
    }

    private fun commitDirectText(
        text: String,
        recordUndo: Boolean,
        markManualEdit: Boolean = false,
        playFeedback: Boolean = true
    ) {
        if (playFeedback) {
            performKeyFeedback(strong = false)
        }
        val ic = currentInputConnection ?: return
        val existingSelection = readSelectedTextSafely()
        ic.beginBatchEdit()
        ic.commitText(text, 1)
        ic.endBatchEdit()

        if (text.isNotBlank()) {
            flypyEngine.recordExternalCommit(text)
        }

        if (markManualEdit) {
            recordManualEditTouch()
        }

        if (recordUndo) {
            val undo = ImeLogic.createUndoSnapshot(
                committedText = text,
                replacedSelection = existingSelection
            )
            pushUndoSnapshot(undo)
            _imeState.update {
                it.copy(
                    undoSnapshot = undoStack.firstOrNull(),
                    rawSelectedText = "",
                    ignoreSelectionForThisTurn = false
                )
            }
        }
    }

    private fun recordManualEditTouch() {
        val baseline = lastDictatedFullText ?: return
        if (baseline.isBlank()) return
        if (System.currentTimeMillis() - lastDictatedAtMs <= ImeLogic.AUTO_LEARN_WINDOW_MS) {
            hasManualEditSinceDictation = true
        } else {
            lastDictatedFullText = null
            hasManualEditSinceDictation = false
        }
    }

    private fun tryAutoLearnFromRecentEdit() {
        val before = lastDictatedFullText ?: return
        val now = System.currentTimeMillis()
        if (now - lastDictatedAtMs > ImeLogic.AUTO_LEARN_WINDOW_MS) {
            lastDictatedFullText = null
            hasManualEditSinceDictation = false
            return
        }
        if (!hasManualEditSinceDictation) return

        val after = readFullEditorTextSafely()
        if (after.isBlank() || after == before) return

        val learnedEntry = repository.learnCorrectionFromDiff(
            beforeText = before,
            afterText = after
        )
        if (learnedEntry != null) {
            lastDictatedFullText = after
            hasManualEditSinceDictation = false
            showAutoLearnedToast(learnedEntry.term)
        }
    }

    private fun showAutoLearnedToast(term: String) {
        autoLearnToastJob?.cancel()
        _imeState.update {
            it.copy(autoLearnedToastTerm = term)
        }
        autoLearnToastJob = serviceScope.launch {
            delay(3200L)
            _imeState.update { curr ->
                if (curr.autoLearnedToastTerm == term) {
                    curr.copy(autoLearnedToastTerm = null)
                } else {
                    curr
                }
            }
        }
    }

    fun performUndo() {
        if (_imeState.value.isFlypyComposing && flypyEngine.clearComposing()) {
            performKeyFeedback(strong = false)
            syncFlypyStateToUi()
            showQuickActionToast("已清码")
            return
        }

        val snapshot = undoStack.removeFirstOrNull() ?: _imeState.value.undoSnapshot ?: return
        val ic = currentInputConnection ?: return
        performKeyFeedback(strong = true)

        // Clear auto-learn baseline so an Undo is never treated as a manual correction
        lastDictatedFullText = null
        hasManualEditSinceDictation = false

        ic.beginBatchEdit()
        val currentlySelected = readSelectedTextSafely()
        if (currentlySelected.isNotEmpty() && currentlySelected.length == snapshot.insertedTextLength) {
            ic.commitText(snapshot.replacedOriginalText, 1)
        } else {
            if (snapshot.insertedTextLength > 0) {
                ic.deleteSurroundingText(snapshot.insertedTextLength, 0)
            }
            if (snapshot.replacedOriginalText.isNotEmpty()) {
                ic.commitText(snapshot.replacedOriginalText, 1)
            }
        }
        ic.endBatchEdit()

        _imeState.update {
            it.copy(
                undoSnapshot = undoStack.firstOrNull(),
                voicePhase = if (it.tabMode == KeyboardTabMode.VOICE) {
                    VoicePhase.SuccessToast(preview = "已撤销上次输入", latencyMs = 0L)
                } else {
                    it.voicePhase
                }
            )
        }
        if (_imeState.value.tabMode == KeyboardTabMode.VOICE) {
            scheduleToastReset()
        } else {
            showQuickActionToast("↶ 已撤回上屏")
        }
    }

    private fun scheduleToastReset() {
        toastDismissJob?.cancel()
        toastDismissJob = serviceScope.launch {
            delay(2200L)
            _imeState.update { curr ->
                if (curr.voicePhase is VoicePhase.SuccessToast) {
                    curr.copy(voicePhase = VoicePhase.Idle)
                } else {
                    curr
                }
            }
        }
    }

    private fun toggleStylePicker() {
        performKeyFeedback(strong = false)
        _imeState.update {
            it.copy(
                isStylePickerExpanded = !it.isStylePickerExpanded,
                isPunctuationStripOpen = false,
                isHistoryDrawerOpen = false
            )
        }
    }

    private fun selectAutoStyle() {
        performKeyFeedback(strong = false)
        repository.updateSettings {
            it.copy(selectedManualStyleId = "auto", appAwareToneEnabled = true)
        }
        _imeState.update { it.copy(isStylePickerExpanded = false) }
    }

    private fun selectManualStyle(style: ToneStyle) {
        performKeyFeedback(strong = false)
        repository.updateSettings {
            it.copy(selectedManualStyleId = style.id)
        }
        _imeState.update { it.copy(isStylePickerExpanded = false) }
    }

    // -------------------------------------------------------------------------
    // Auxiliary Keyboard Actions (Punctuation, Hide, Switch IME, Backspace, Enter)
    // -------------------------------------------------------------------------

    private fun insertPairedSymbol(left: String, right: String) {
        performKeyFeedback(strong = false)
        val ic = currentInputConnection ?: return
        val selected = readSelectedTextSafely()
        ic.beginBatchEdit()
        if (selected.isNotEmpty()) {
            ic.commitText("$left$selected$right", 1)
        } else {
            ic.commitText("$left$right", 1)
            val extracted = try {
                ic.getExtractedText(ExtractedTextRequest(), 0)
            } catch (_: Exception) {
                null
            }
            if (extracted != null && extracted.selectionStart >= right.length) {
                val cursorInside = extracted.selectionStart - right.length
                ic.setSelection(cursorInside, cursorInside)
            }
        }
        ic.endBatchEdit()
    }

    fun hideKeyboardSelf() {
        performKeyFeedback(strong = false)
        tryAutoLearnFromRecentEdit()
        try {
            requestHideSelf(0)
        } catch (_: Exception) {
        }
    }

    fun switchKeyboard() {
        performKeyFeedback(strong = false)
        tryAutoLearnFromRecentEdit()
        val switched = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                switchToPreviousInputMethod()
            } catch (_: Exception) {
                false
            }
        } else {
            false
        }
        if (!switched) {
            showImePicker()
        }
    }

    fun showImePicker() {
        performKeyFeedback(strong = false)
        try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showInputMethodPicker()
        } catch (_: Exception) {
        }
    }

    fun selectAllText() {
        performKeyFeedback(strong = false)
        try {
            currentInputConnection?.performContextMenuAction(android.R.id.selectAll)
        } catch (_: Exception) {
        }
    }

    fun insertSpace() {
        val curr = _imeState.value
        if (curr.tabMode == KeyboardTabMode.QWERTY && curr.isFlypyChineseMode && flypyEngine.isComposing) {
            performKeyFeedback(strong = false)
            val action = flypyEngine.onSpace()
            handleFlypyAction(action)
            return
        }
        // Evaluate auto-learned correction before/after space commit
        tryAutoLearnFromRecentEdit()
        commitDirectText(" ", recordUndo = false, markManualEdit = false)
    }

    fun deleteBackward() {
        val curr = _imeState.value
        if (curr.tabMode == KeyboardTabMode.QWERTY && curr.isFlypyChineseMode && flypyEngine.onBackspace()) {
            performKeyFeedback(strong = false)
            syncFlypyStateToUi()
            return
        }
        performKeyFeedback(strong = false)
        val ic = currentInputConnection ?: return
        try {
            val selected = ic.getSelectedText(0)?.toString().orEmpty()
            if (selected.isNotEmpty()) {
                ic.commitText("", 1)
            } else {
                val before = ic.getTextBeforeCursor(2, 0)?.toString().orEmpty()
                val deleteLen = ImeLogic.computeBackspaceDeleteLength(before).coerceAtLeast(1)
                ic.deleteSurroundingText(deleteLen, 0)
            }
            recordManualEditTouch()
        } catch (_: Exception) {
        }
    }

    fun performEnterOrNewline() {
        val curr = _imeState.value
        if (curr.tabMode == KeyboardTabMode.QWERTY && curr.isFlypyChineseMode && flypyEngine.isComposing) {
            val rawAscii = flypyEngine.onEnterWhenComposing()
            syncFlypyStateToUi()
            if (!rawAscii.isNullOrEmpty()) {
                commitDirectText(
                    text = rawAscii,
                    recordUndo = true,
                    markManualEdit = true,
                    playFeedback = true
                )
                return
            }
        }
        performKeyFeedback(strong = false)
        // Must read ExtractedText BEFORE performEditorAction(SEND) clears the host chat input!
        tryAutoLearnFromRecentEdit()

        val ic = currentInputConnection ?: return
        val actionInfo = ImeLogic.resolveEnterAction(
            imeOptions = _imeState.value.currentImeOptions,
            inputType = _imeState.value.currentInputType
        )
        val actionId = actionInfo.performEditorActionId
        if (actionId != null) {
            val handled = try {
                ic.performEditorAction(actionId)
            } catch (_: Exception) {
                false
            }
            if (!handled) {
                ic.commitText("\n", 1)
            }
        } else {
            ic.commitText("\n", 1)
        }
    }

    fun openHostApp(tabIndex: Int = AppDestinations.TAB_HOME) {
        performKeyFeedback(strong = false)
        tryAutoLearnFromRecentEdit()
        try {
            val intent = Intent().apply {
                setClassName(packageName, "com.typefree.ime.MainActivity")
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
                putExtra(AppDestinations.EXTRA_OPEN_TAB, tabIndex)
            }
            startActivity(intent)
            requestHideSelf(0)
        } catch (_: Exception) {
        }
    }

    // -------------------------------------------------------------------------
    // Built-in IME Clipboard Manager & Smart Quick-Paste Actions
    // -------------------------------------------------------------------------

    private fun syncSystemClipboardIfNeeded(allowFreshBanner: Boolean) {
        val currentSettings = repository.settings.value
        if (!currentSettings.clipboardEnabled) return

        val cm = clipboardManager
            ?: (getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager).also {
                clipboardManager = it
            }
            ?: return

        val clip = runCatching { cm.primaryClip }.getOrNull() ?: return
        if (clip.itemCount <= 0) return

        // Respect Android 13+ EXTRA_IS_SENSITIVE so password-manager secrets are not persisted
        val isSensitive = runCatching {
            clip.description?.extras?.getBoolean("android.content.extra.IS_SENSITIVE", false) ?: false
        }.getOrDefault(false)
        if (isSensitive) return

        val rawText = runCatching {
            clip.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        }.getOrDefault("")
        val normalized = rawText.trim()
        if (normalized.isEmpty()) return

        if (normalized == lastObservedSystemClip) return
        lastObservedSystemClip = normalized

        val savedEntry = repository.recordClipboardText(rawText) ?: return
        if (allowFreshBanner && currentSettings.clipboardQuickPasteSuggestion && !_imeState.value.isClipboardOpen) {
            freshClipBannerJob?.cancel()
            _imeState.update { it.copy(freshClipboardBannerItem = savedEntry) }
            freshClipBannerJob = serviceScope.launch {
                delay(45_000L)
                _imeState.update { curr ->
                    if (curr.freshClipboardBannerItem?.id == savedEntry.id) {
                        curr.copy(freshClipboardBannerItem = null)
                    } else {
                        curr
                    }
                }
            }
        }
    }

    private fun openClipboardPanel() {
        performKeyFeedback(strong = false)
        if (_imeState.value.isFlypyComposing) {
            flypyEngine.resetComposing()
            syncFlypyStateToUi()
        }
        if (_imeState.value.voicePhase is VoicePhase.Recording) {
            micPressDownTimeMs = 0L
            serviceScope.launch {
                audioRecorder.cancelRecording()
            }
        }
        syncSystemClipboardIfNeeded(allowFreshBanner = false)
        freshClipBannerJob?.cancel()
        _imeState.update { prev ->
            prev.copy(
                isClipboardOpen = true,
                freshClipboardBannerItem = null,
                voicePhase = if (prev.voicePhase is VoicePhase.Recording) VoicePhase.Idle else prev.voicePhase,
                isStylePickerExpanded = false,
                isPunctuationStripOpen = false
            )
        }
    }

    private fun closeClipboardPanel() {
        performKeyFeedback(strong = false)
        _imeState.update { it.copy(isClipboardOpen = false) }
    }

    private fun pasteFromClipboard(text: String) {
        if (text.isEmpty()) return
        if (_imeState.value.isFlypyComposing) {
            flypyEngine.resetComposing()
            syncFlypyStateToUi()
        }
        freshClipBannerJob?.cancel()
        _imeState.update { it.copy(freshClipboardBannerItem = null) }
        commitDirectText(
            text = text,
            recordUndo = true,
            markManualEdit = true,
            playFeedback = true
        )
        showQuickActionToast("✓ 已粘贴")
    }

    private fun togglePinClipboardEntry(id: String) {
        performKeyFeedback(strong = false)
        repository.togglePinClipboardItem(id)
    }

    private fun deleteClipboardEntry(id: String) {
        performKeyFeedback(strong = false)
        repository.deleteClipboardItem(id)
        if (_imeState.value.freshClipboardBannerItem?.id == id) {
            freshClipBannerJob?.cancel()
            _imeState.update { it.copy(freshClipboardBannerItem = null) }
        }
    }

    private fun clearUnpinnedClipboardEntries() {
        performKeyFeedback(strong = true)
        repository.clearUnpinnedClipboardItems()
        _imeState.update { curr ->
            if (curr.freshClipboardBannerItem?.isPinned == true) {
                curr
            } else {
                curr.copy(freshClipboardBannerItem = null)
            }
        }
        showQuickActionToast("✓ 已清空未置顶记录")
    }

    private fun saveCurrentSelectionToClipboard() {
        val selected = readSelectedTextSafely().ifBlank { _imeState.value.rawSelectedText }
        if (selected.isBlank()) return
        performKeyFeedback(strong = true)
        lastObservedSystemClip = selected.trim()
        repository.recordClipboardText(selected, pinned = true)
        showQuickActionToast("📌 已存为置顶常用语")
    }

    private fun enableClipboardFromKeyboard() {
        performKeyFeedback(strong = false)
        repository.updateSettings { it.copy(clipboardEnabled = true) }
        lastObservedSystemClip = null
        syncSystemClipboardIfNeeded(allowFreshBanner = false)
    }

    private fun openClipboardPreferencesInHostApp() {
        performKeyFeedback(strong = false)
        tryAutoLearnFromRecentEdit()
        try {
            val intent = Intent().apply {
                setClassName(packageName, "com.typefree.ime.MainActivity")
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
                putExtra(AppDestinations.EXTRA_OPEN_TAB, AppDestinations.TAB_SETTINGS)
                putExtra(AppDestinations.EXTRA_SETTINGS_SUBTAB, AppDestinations.SUBTAB_PREFERENCES)
            }
            startActivity(intent)
            requestHideSelf(0)
        } catch (_: Exception) {
        }
    }

    private fun readSelectedTextSafely(): String {
        return try {
            currentInputConnection?.getSelectedText(0)?.toString().orEmpty()
        } catch (_: Exception) {
            ""
        }
    }

    private fun readSurroundingBeforeCursor(maxChars: Int): String {
        return try {
            currentInputConnection?.getTextBeforeCursor(maxChars, 0)?.toString().orEmpty()
        } catch (_: Exception) {
            ""
        }
    }

    private fun readFullEditorTextSafely(): String {
        val ic = currentInputConnection ?: return ""
        return try {
            val extracted = ic.getExtractedText(ExtractedTextRequest(), 0)?.text?.toString()
            if (!extracted.isNullOrEmpty()) {
                extracted
            } else {
                val before = ic.getTextBeforeCursor(2000, 0)?.toString().orEmpty()
                val selected = ic.getSelectedText(0)?.toString().orEmpty()
                val after = ic.getTextAfterCursor(2000, 0)?.toString().orEmpty()
                before + selected + after
            }
        } catch (_: Exception) {
            ""
        }
    }

    private fun archiveWavForHistory(wavFile: File, retention: HistoryRetention): File {
        if (retention == HistoryRetention.NEVER || !wavFile.exists()) {
            return wavFile
        }
        return try {
            val dir = File(cacheDir, "history_audio").apply { mkdirs() }
            val archived = File(dir, "voice_${System.currentTimeMillis()}.wav")
            wavFile.copyTo(archived, overwrite = true)
            val files = dir.listFiles()?.sortedByDescending { it.lastModified() }.orEmpty()
            if (files.size > 50) {
                files.drop(50).forEach { runCatching { it.delete() } }
            }
            archived
        } catch (_: Exception) {
            wavFile
        }
    }

    private fun hasRetryWavFile(): Boolean {
        return try {
            val f = WavAudioRecorder.getLastRecordingFile(this)
            f.exists() && f.length() > 44L
        } catch (_: Exception) {
            false
        }
    }

    private fun performKeyFeedback(strong: Boolean = false) {
        performHaptic(strong = strong)
        playKeyClickSound()
    }

    private fun playKeyClickSound() {
        if (!repository.settings.value.interactionSounds) return
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            am?.playSoundEffect(AudioManager.FX_KEY_CLICK)
        } catch (_: Exception) {
        }
    }

    private fun performHaptic(strong: Boolean = false) {
        if (!repository.settings.value.hapticFeedback) return
        try {
            val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (vibrator?.hasVibrator() == true) {
                val duration = if (strong) 20L else 10L
                val amplitude = if (strong) 115 else 60
                vibrator.vibrate(VibrationEffect.createOneShot(duration, amplitude))
            }
        } catch (_: Exception) {
        }
    }
}
