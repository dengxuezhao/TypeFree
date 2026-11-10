package com.typefree.ime.ime

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.media.AudioManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import com.typefree.ime.TypeFreeApp
import com.typefree.ime.audio.WavAudioRecorder
import com.typefree.ime.data.AppRepository
import com.typefree.ime.data.HistoryRetention
import com.typefree.ime.data.ToneStyle
import com.typefree.ime.ime.engine.RimeDeploymentState
import com.typefree.ime.network.AiPipelineEngine
import com.typefree.ime.ui.AppDestinations
import com.typefree.ime.ui.theme.TypeFreeTheme
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

class TypeFreeInputMethodService : ComposeInputMethodService(), ImeHostCallbacks {

    override val repository: AppRepository
        get() = TypeFreeApp.instance.repository

    override val aiEngine: AiPipelineEngine
        get() = TypeFreeApp.instance.aiEngine

    private lateinit var audioRecorder: WavAudioRecorder

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    override val scope: CoroutineScope
        get() = serviceScope

    private var autoLearnToastJob: Job? = null
    private var quickToastJob: Job? = null
    private var freshClipBannerJob: Job? = null

    private var clipboardManager: ClipboardManager? = null
    private var lastObservedSystemClip: String? = null
    private val primaryClipListener = ClipboardManager.OnPrimaryClipChangedListener {
        syncSystemClipboardIfNeeded(allowFreshBanner = true)
    }

    private val undoStack = ArrayDeque<UndoSnapshot>()

    // Auto-Learned Dictionary (`Auto-added`) tracking state
    private var lastDictatedFullText: String? = null
    private var lastDictatedAtMs: Long = 0L
    private var hasManualEditSinceDictation: Boolean = false

    private val _imeState = MutableStateFlow(ImeUiState())
    override val stateFlow: MutableStateFlow<ImeUiState>
        get() = _imeState
    val imeState = _imeState.asStateFlow()

    val typingController = TypingController(host = this)
    val voiceController = VoiceController(
        host = this,
        audioRecorderProvider = { audioRecorder },
        onExternalCommitToEngine = { text -> typingController.recordExternalCommit(text) },
        onResetComposing = { typingController.resetComposing() }
    )

    override fun onCreate() {
        super.onCreate()
        audioRecorder = WavAudioRecorder(this)
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        runCatching {
            clipboardManager?.addPrimaryClipChangedListener(primaryClipListener)
        }
        syncSystemClipboardIfNeeded(allowFreshBanner = false)
        val initialSettings = repository.settings.value
        val initialMode = ImeLogic.resolveInitialKeyboardMode(
            settings = initialSettings,
            isPasswordField = false
        )
        _imeState.update {
            it.copy(
                tabMode = initialMode.tabMode,
                isFlypyChineseMode = initialMode.isFlypyChineseMode,
                activeInputScheme = initialSettings.inputScheme,
                activeHeightLevel = initialSettings.keyboardHeightLevel
            )
        }
        TypeFreeApp.instance.ensureFlypyDictionaryLoaded()
        typingController.rimeInputEngine = TypeFreeApp.instance.rimeInputEngine
        typingController.isRimeReady = TypeFreeApp.instance.rimeDataManager.isReady
        TypeFreeApp.instance.ensureRimeDeployed()
        serviceScope.launch {
            TypeFreeApp.instance.flypyDictionary.collect { dict ->
                if (dict.isLoaded) {
                    typingController.updateFlypyDictionary(dict)
                }
            }
        }
        serviceScope.launch {
            TypeFreeApp.instance.rimeDataManager.deploymentState.collect { deployState ->
                val ready = deployState is RimeDeploymentState.Ready
                typingController.isRimeReady = ready
                _imeState.update { prev ->
                    prev.copy(
                        isRimeDeploying = prev.activeInputScheme.usesRime && !ready
                    )
                }
                if (ready && typingController.isComposing) {
                    typingController.syncEngineStateToUi()
                }
            }
        }
        serviceScope.launch {
            var lastFlypySetting: Boolean? = null
            var lastScheme = initialSettings.inputScheme
            repository.settings.collect { currentSettings ->
                TypeFreeApp.instance.rimeInputEngine.setInputScheme(currentSettings.inputScheme)
                typingController.updateSnippets(currentSettings.snippets)
                typingController.setFixedCandidateOrder(currentSettings.fixedCandidateOrder)
                if (lastScheme != currentSettings.inputScheme) {
                    lastScheme = currentSettings.inputScheme
                    typingController.resetComposing()
                }
                _imeState.update {
                    it.copy(
                        activeInputScheme = currentSettings.inputScheme,
                        isRimeDeploying = currentSettings.inputScheme.usesRime && !typingController.isRimeReady
                    )
                }
                val prevSetting = lastFlypySetting
                lastFlypySetting = currentSettings.flypyEnabled
                if (prevSetting != null && prevSetting != currentSettings.flypyEnabled) {
                    typingController.resetComposing()
                    val isPwd = ImeLogic.isPasswordEditorInputType(_imeState.value.currentInputType)
                    val nextChinese = currentSettings.flypyEnabled && !isPwd
                    if (currentSettings.flypyEnabled) {
                        repository.updateSettings { s -> s.copy(lastQwertyIsChinese = true) }
                    }
                    _imeState.update {
                        it.copy(
                            isFlypyChineseMode = nextChinese,
                            flypyComposingCode = "",
                            preeditDisplay = "",
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
            typingController.resetComposing()
            undoStack.clear()
        }
        syncEditorMetadata(attribute, restarting)
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        repository.setImeWindowVisible(true)
        val latestHeightLevel = repository.settings.value.keyboardHeightLevel
        if (!restarting) {
            typingController.resetComposing()
            _imeState.update {
                it.copy(
                    isClipboardOpen = false,
                    isToolPanelOpen = false,
                    isToolPanelHeightExpanded = false,
                    voiceSubPanel = VoiceSubPanel.NONE,
                    isPunctuationStripOpen = false,
                    isStylePickerExpanded = false,
                    flypyComposingCode = "",
                    preeditDisplay = "",
                    flypyCandidates = emptyList(),
                    isFlypyCandidateSheetExpanded = false,
                    predictionCandidates = emptyList(),
                    aiCompletionSuggestion = null,
                    activeHeightLevel = latestHeightLevel
                )
            }
        } else {
            _imeState.update { it.copy(activeHeightLevel = latestHeightLevel) }
        }
        syncEditorMetadata(info, restarting)
        syncSystemClipboardIfNeeded(allowFreshBanner = true)
        voiceController.warmVoiceConnectionsInBackground()
    }

    override fun onWindowShown() {
        super.onWindowShown()
        repository.setImeWindowVisible(true)
        syncSystemClipboardIfNeeded(allowFreshBanner = true)
        voiceController.warmVoiceConnectionsInBackground()
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
        val settingsSnapshot = repository.settings.value
        val isPasswordField = ImeLogic.isPasswordEditorInputType(inputType)
        val isIncognito = ImeLogic.isIncognitoImeOptions(imeOptions, inputType)
        typingController.setIncognitoMode(isIncognito)

        var shouldEnsureFlypyDict = false
        _imeState.update { prev ->
            val sessionMode = ImeLogic.resolveSessionKeyboardMode(
                previousTabMode = prev.tabMode,
                previousFlypyChineseMode = prev.isFlypyChineseMode,
                settings = settingsSnapshot,
                isPasswordField = isPasswordField,
                restarting = restarting
            )
            if (sessionMode.tabMode == KeyboardTabMode.QWERTY && !typingController.isDictionaryLoaded) {
                shouldEnsureFlypyDict = true
            }
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
            prev.copy(
                voicePhase = clearedPhase,
                tabMode = sessionMode.tabMode,
                inputSessionToken = if (!restarting) prev.inputSessionToken + 1 else prev.inputSessionToken,
                targetPackage = pkg,
                targetAppName = appName,
                currentInputType = inputType,
                currentImeOptions = imeOptions,
                isIncognitoMode = isIncognito,
                activeInputScheme = settingsSnapshot.inputScheme,
                rawSelectedText = selected,
                ignoreSelectionForThisTurn = nextIgnore,
                hasCachedAudioForRetry = hasCachedWav,
                isFlypyChineseMode = sessionMode.isFlypyChineseMode,
                flypyComposingCode = if (!restarting) "" else prev.flypyComposingCode,
                preeditDisplay = if (!restarting) "" else prev.preeditDisplay,
                flypyCandidates = if (!restarting) emptyList() else prev.flypyCandidates,
                isFlypyCandidateSheetExpanded = if (!restarting) false else prev.isFlypyCandidateSheetExpanded,
                undoSnapshot = if (!restarting) null else prev.undoSnapshot
            )
        }
        if (shouldEnsureFlypyDict) {
            TypeFreeApp.instance.ensureFlypyDictionaryLoaded()
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
        voiceController.onSelectionUpdated(
            oldSelStart = oldSelStart,
            oldSelEnd = oldSelEnd,
            newSelStart = newSelStart,
            newSelEnd = newSelEnd,
            candidatesStart = candidatesStart,
            candidatesEnd = candidatesEnd
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
        voiceController.stopRecordingOnTabSwitchOrWindowHidden()
        typingController.resetComposing()
        val hiddenBaseMode = ImeLogic.resolveInitialKeyboardMode(
            settings = repository.settings.value,
            isPasswordField = false
        )
        _imeState.update { prev ->
            prev.copy(
                tabMode = hiddenBaseMode.tabMode,
                isFlypyChineseMode = hiddenBaseMode.isFlypyChineseMode,
                inputSessionToken = prev.inputSessionToken + 1,
                voicePhase = if (prev.voicePhase is VoicePhase.Recording) VoicePhase.Idle else prev.voicePhase,
                voiceSubPanel = VoiceSubPanel.NONE,
                isArcTranslateActive = false,
                arcStartedFromHandsFree = false,
                isArcCancelHovered = false,
                isStylePickerExpanded = false,
                isPunctuationStripOpen = false,
                isHistoryDrawerOpen = false,
                isClipboardOpen = false,
                isToolPanelOpen = false,
                isToolPanelHeightExpanded = false,
                flypyComposingCode = "",
                preeditDisplay = "",
                flypyCandidates = emptyList(),
                isFlypyCandidateSheetExpanded = false,
                predictionCandidates = emptyList(),
                aiCompletionSuggestion = null
            )
        }
    }

    override fun onDestroy() {
        repository.setImeWindowVisible(false)
        voiceController.onDestroy()
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
        val didPauseMedia by audioRecorder.didPauseExternalMedia.collectAsState()
        val configuration = LocalConfiguration.current
        val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

        val keyboardMetrics = remember(uiState.activeHeightLevel, isLandscape) {
            KeyboardMetrics.fromLevel(uiState.activeHeightLevel, isLandscape)
        }

        val (activeStyle, isAutoMatched) = remember(
            settings,
            uiState.targetPackage,
            uiState.currentInputType
        ) {
            repository.resolveActiveStyle(uiState.targetPackage, uiState.currentInputType)
        }

        TypeFreeTheme(themePreference = settings.themePreference) {
            CompositionLocalProvider(LocalKeyboardMetrics provides keyboardMetrics) {
                KeyboardScreen(
                    uiState = uiState,
                    settings = settings,
                    activeStyle = activeStyle,
                    isAutoMatched = isAutoMatched,
                    amplitudes = amplitudes,
                    elapsedMs = elapsedMs,
                    didPauseMedia = didPauseMedia,
                    onToggleWhisperMode = voiceController::toggleWhisperMode,
                    onSelectTabMode = ::selectTabMode,
                    onOpenHelpMeWrite = voiceController::openOrRestoreVoicePenPanel,
                    onCloseSubPanel = voiceController::closeVoiceSubPanel,
                    onDismissSpeakToEdit = voiceController::dismissSpeakToEditForTurn,
                    onSelectTranslateLanguage = voiceController::selectTranslateLanguage,
                    onTogglePunctuationStrip = voiceController::togglePunctuationStrip,
                    onToggleStylePicker = ::toggleStylePicker,
                    onSelectAutoStyle = ::selectAutoStyle,
                    onSelectManualStyle = ::selectManualStyle,
                    onPerformUndo = ::performUndo,
                    onOpenHostApp = ::openHostApp,
                    onQuickEditChipClick = voiceController::runQuickEditChip,
                    onHelpWritePresetClick = voiceController::runHelpWritePreset,
                    onMicPressDown = voiceController::handleMicPressDown,
                    onMicPressUp = voiceController::handleMicPressUp,
                    onEnterArcTranslate = voiceController::enterArcTranslateMode,
                    onUpdateArcTranslate = voiceController::updateArcTranslateSelection,
                    onReleaseArcTranslate = voiceController::releaseArcTranslate,
                    onFinishHandsFree = voiceController::finishHandsFreeRecording,
                    onCancelRecording = voiceController::cancelRecordingOrProcessing,
                    onRetryLastAudio = voiceController::retryLastAudio,
                    onDismissError = voiceController::dismissErrorBanner,
                    onCommitText = { text -> commitDirectText(text, recordUndo = true, markManualEdit = true) },
                    onCommitQwertyChar = typingController::commitQwertyChar,
                    onCommaOrPeriodKey = typingController::handleCommaOrPeriodKey,
                    onToggleFlypyLanguageMode = typingController::toggleFlypyLanguageMode,
                    onSelectFlypyCandidate = typingController::selectFlypyCandidate,
                    onClearFlypyComposing = typingController::clearFlypyComposing,
                    onFlushFlypyComposing = typingController::flushFlypyComposing,
                    onToggleFlypyCandidateSheet = typingController::toggleFlypyCandidateSheet,
                    onRequestMoreCandidates = typingController::requestMoreCandidates,
                    onEditorShortcutAction = typingController::handleEditorShortcutAction,
                    onInsertPairedSymbol = typingController::insertPairedSymbol,
                    onHideKeyboard = ::hideKeyboardSelf,
                    onSwitchKeyboard = ::switchKeyboard,
                    onShowImePicker = ::showImePicker,
                    onInsertSpace = typingController::insertSpace,
                    onBackspace = typingController::deleteBackward,
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
    }

    // -------------------------------------------------------------------------
    // Mode Switching & Top-Level Navigation
    // -------------------------------------------------------------------------

    private fun selectTabMode(mode: KeyboardTabMode) {
        val prevMode = _imeState.value.tabMode
        if (prevMode == mode && !_imeState.value.isClipboardOpen && !_imeState.value.isToolPanelOpen) return

        performKeyFeedback(strong = false)
        if (mode == KeyboardTabMode.QWERTY && !typingController.isDictionaryLoaded) {
            TypeFreeApp.instance.ensureFlypyDictionaryLoaded()
        }

        if (prevMode == KeyboardTabMode.QWERTY && mode != KeyboardTabMode.QWERTY) {
            typingController.resetComposing()
            tryAutoLearnFromRecentEdit()
        }

        voiceController.stopRecordingOnTabSwitchOrWindowHidden()

        if (!ImeLogic.isPasswordEditorInputType(_imeState.value.currentInputType)) {
            repository.updateSettings { it.copy(lastKeyboardTab = mode.name) }
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
                isToolPanelOpen = false,
                isToolPanelHeightExpanded = false,
                flypyComposingCode = "",
                preeditDisplay = "",
                flypyCandidates = emptyList(),
                isFlypyCandidateSheetExpanded = false
            )
        }
    }

    fun retryLastAudio() {
        voiceController.retryLastAudio()
    }

    fun performEnterOrNewline() {
        typingController.performEnterOrNewline(
            onRunEqualEqualAskAi = voiceController::runEqualEqualAskAi
        )
    }

    // -------------------------------------------------------------------------
    // Commit, Undo, Auto-Learned Dictionary (`Auto-added`), Style Selector
    // -------------------------------------------------------------------------

    override fun showQuickActionToast(message: String) {
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

    override fun pushUndoSnapshot(snapshot: UndoSnapshot?) {
        if (snapshot == null) return
        undoStack.addFirst(snapshot)
        while (undoStack.size > 12) {
            undoStack.removeLast()
        }
    }

    override fun peekTopUndoSnapshot(): UndoSnapshot? = undoStack.firstOrNull()

    override fun commitDirectText(
        text: String,
        recordUndo: Boolean,
        markManualEdit: Boolean,
        playFeedback: Boolean
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
            typingController.recordExternalCommit(text)
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

    override fun onDictationCommittedForAutoLearn(fullBufferAfterCommit: String) {
        if (_imeState.value.isIncognitoMode) return
        lastDictatedFullText = fullBufferAfterCommit
        lastDictatedAtMs = System.currentTimeMillis()
        hasManualEditSinceDictation = false
    }

    override fun clearAutoLearnBaseline() {
        lastDictatedFullText = null
        hasManualEditSinceDictation = false
    }

    override fun recordManualEditTouch() {
        val baseline = lastDictatedFullText ?: return
        if (baseline.isBlank()) return
        if (System.currentTimeMillis() - lastDictatedAtMs <= ImeLogic.AUTO_LEARN_WINDOW_MS) {
            hasManualEditSinceDictation = true
        } else {
            lastDictatedFullText = null
            hasManualEditSinceDictation = false
        }
    }

    override fun tryAutoLearnFromRecentEdit() {
        if (_imeState.value.isIncognitoMode) {
            clearAutoLearnBaseline()
            return
        }
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
        if (_imeState.value.isFlypyComposing && typingController.clearComposing()) {
            performKeyFeedback(strong = false)
            showQuickActionToast("已清码")
            return
        }

        val snapshot = undoStack.removeFirstOrNull() ?: _imeState.value.undoSnapshot ?: return
        val ic = currentInputConnection ?: return
        performKeyFeedback(strong = true)

        clearAutoLearnBaseline()

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
            voiceController.scheduleToastReset()
        } else {
            showQuickActionToast("↶ 已撤回上屏")
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
    // Auxiliary Keyboard Actions (Hide, Switch IME, Select All, Open Host App)
    // -------------------------------------------------------------------------

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
        typingController.insertSpace()
    }

    fun deleteBackward() {
        typingController.deleteBackward()
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

    override fun syncSystemClipboardIfNeeded(allowFreshBanner: Boolean) {
        if (_imeState.value.isIncognitoMode) return
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
            typingController.resetComposing()
        }
        voiceController.stopRecordingOnTabSwitchOrWindowHidden()
        syncSystemClipboardIfNeeded(allowFreshBanner = false)
        freshClipBannerJob?.cancel()
        _imeState.update { prev ->
            prev.copy(
                isClipboardOpen = true,
                isToolPanelOpen = false,
                isToolPanelHeightExpanded = false,
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
            typingController.resetComposing()
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

    override fun currentInputConnection(): InputConnection? = super.getCurrentInputConnection()

    override fun readSystemClipboardText(): String {
        return runCatching {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.primaryClip?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)
                ?.coerceToText(this)
                ?.toString()
                .orEmpty()
        }.getOrDefault("")
    }

    override fun readSelectedTextSafely(): String {
        return try {
            currentInputConnection?.getSelectedText(0)?.toString().orEmpty()
        } catch (_: Exception) {
            ""
        }
    }

    override fun readSurroundingBeforeCursor(maxChars: Int): String {
        return try {
            currentInputConnection?.getTextBeforeCursor(maxChars, 0)?.toString().orEmpty()
        } catch (_: Exception) {
            ""
        }
    }

    override fun readFullEditorTextSafely(): String {
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

    override fun archiveWavForHistory(wavFile: File, retention: HistoryRetention): File {
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

    override fun getLastRecordingWavFile(): File = WavAudioRecorder.getLastRecordingFile(this)

    override fun hasRetryWavFile(): Boolean {
        return try {
            val f = WavAudioRecorder.getLastRecordingFile(this)
            f.exists() && f.length() > 44L
        } catch (_: Exception) {
            false
        }
    }

    override fun performKeyFeedback(strong: Boolean) {
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

    override fun performHaptic(strong: Boolean) {
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
