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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.typefree.ime.TypeFreeApp
import com.typefree.ime.audio.WavAudioRecorder
import com.typefree.ime.bubble.CapturedChatContext
import com.typefree.ime.data.AppRepository
import com.typefree.ime.data.HistoryRetention
import com.typefree.ime.data.InputScheme
import com.typefree.ime.data.KeyboardHeightMode
import com.typefree.ime.data.ToneStyle
import com.typefree.ime.data.VoiceIntentMode
import com.typefree.ime.data.stats.SchemeStatsKeys
import com.typefree.core.ime.coordinator.DirectCommandType
import com.typefree.ime.commit.AndroidCommitExecutor
import com.typefree.ime.ime.engine.CodetableEnhancedInputEngine
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
import kotlinx.coroutines.withContext
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
    private var androidCommitExecutor: AndroidCommitExecutor? = null
    private var currentEditorFieldId: Int = 0
    private var lastEditorTextSyncMs: Long = 0L

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
                activeHeightLevel = initialSettings.keyboardHeightLevel,
                activeHeightMode = initialSettings.keyboardHeightMode,
                activeLiftLevel = initialSettings.keyboardLiftLevel
            )
        }
        TypeFreeApp.instance.ensureFlypyDictionaryLoaded()
        typingController.rimeInputEngine = TypeFreeApp.instance.rimeInputEngine
        typingController.enhancedSchemePredicate = { scheme ->
            TypeFreeApp.instance.rimeDataManager.isSchemeHandledByEnhancedEngine(scheme)
        }
        typingController.onUndoRequested = { performUndo() }
        typingController.reverseLookupMapProvider = {
            TypeFreeApp.instance.rimeDataManager.activeReverseLookupMap
        }
        typingController.onSaveUserPhrase = { word, code ->
            saveUserPhraseAsync(word, code)
        }
        typingController.isRimeReady = TypeFreeApp.instance.rimeDataManager.isReady
        bindCodetableEnhancedEngineIfAvailable()
        TypeFreeApp.instance.ensureRimeDeployed()
        serviceScope.launch {
            TypeFreeApp.instance.flypyDictionary.collect { dict ->
                if (dict.isLoaded) {
                    typingController.updateFlypyDictionary(dict)
                }
            }
        }
        serviceScope.launch(Dispatchers.IO) {
            runCatching {
                typingController.staticPredictor.loadFromContextIfAvailable(applicationContext)
            }
        }
        serviceScope.launch {
            TypeFreeApp.instance.rimeDataManager.deploymentState.collect { deployState ->
                val ready = deployState is RimeDeploymentState.Ready
                typingController.isRimeReady = ready
                if (ready) {
                    val activeScheme = repository.settings.value.inputScheme
                    val dm = TypeFreeApp.instance.rimeDataManager
                    if (dm.isSchemeHandledByEnhancedEngine(activeScheme)) {
                        dm.activateEnhancedSchemeSync(activeScheme)
                    }
                    bindCodetableEnhancedEngineIfAvailable()
                    if (!dm.isSchemeHandledByEnhancedEngine(activeScheme)) {
                        TypeFreeApp.instance.rimeInputEngine.setInputScheme(activeScheme)
                    }
                    typingController.setFixedCandidateOrder(repository.settings.value.fixedCandidateOrder)
                    typingController.setIncognitoMode(_imeState.value.isIncognitoMode)
                    serviceScope.launch(Dispatchers.IO) {
                        runCatching {
                            typingController.staticPredictor.reloadFromRimeSharedDir(dm.sharedDir)
                        }
                    }
                }
                _imeState.update { prev ->
                    prev.copy(
                        isRimeDeploying = prev.activeInputScheme.usesRime && !ready,
                        codetableEnhancerActive = typingController.activeEngine is CodetableEnhancedInputEngine,
                        singleCharModeEnabled = typingController.codetableEnhancedEngine?.singleCharModeEnabled ?: false,
                        pinnedWordsForCurrentCode = typingController.codetableEnhancedEngine?.currentPinnedWordsForBuffer().orEmpty()
                    )
                }
                if (ready && typingController.isComposing) {
                    typingController.syncEngineStateToUi()
                }
            }
        }
        serviceScope.launch {
            var lastVersion = TypeFreeApp.instance.rimeDataManager.metadataVersion.value
            TypeFreeApp.instance.rimeDataManager.metadataVersion.collect { version ->
                if (version != lastVersion) {
                    lastVersion = version
                    val dm = TypeFreeApp.instance.rimeDataManager
                    bindCodetableEnhancedEngineIfAvailable()
                    typingController.codetableEnhancedEngine?.let { eng ->
                        eng.reloadUserDictWordsCache()
                        eng.reloadShadowRules()
                        applyCodetableV362Settings(eng, repository.settings.value)
                    }
                    syncCodetableUiFlags()
                    serviceScope.launch(Dispatchers.IO) {
                        runCatching {
                            typingController.staticPredictor.reloadFromRimeSharedDir(dm.sharedDir)
                        }
                    }
                }
            }
        }
        serviceScope.launch {
            var lastFlypySetting: Boolean? = null
            var lastScheme = initialSettings.inputScheme
            repository.settings.collect { currentSettings ->
                val dm = TypeFreeApp.instance.rimeDataManager
                val schemeChanged = lastScheme != currentSettings.inputScheme
                TypeFreeApp.instance.rimeInputEngine.setInputScheme(currentSettings.inputScheme)
                if (dm.isReady && dm.isSchemeHandledByEnhancedEngine(currentSettings.inputScheme)) {
                    val expectedSchemaId = if (currentSettings.inputScheme == InputScheme.FLYPY_TABLE) {
                        "flypy"
                    } else {
                        dm.resolveCustomSchemaId()
                    }
                    val needsActivation = schemeChanged ||
                        typingController.codetableEnhancedEngine == null ||
                        dm.rimeBridge.activeSchemaId != expectedSchemaId ||
                        typingController.codetableEnhancedEngine?.activeProfile?.schemaId != expectedSchemaId
                    if (needsActivation) {
                        dm.activateEnhancedSchemeSync(currentSettings.inputScheme)
                        bindCodetableEnhancedEngineIfAvailable()
                    }
                }
                typingController.updateSnippets(currentSettings.snippets)
                typingController.setFixedCandidateOrder(currentSettings.fixedCandidateOrder)
                typingController.codetableEnhancedEngine?.let { eng ->
                    applyCodetableV362Settings(eng, currentSettings)
                }
                if (schemeChanged) {
                    lastScheme = currentSettings.inputScheme
                    typingController.resetComposing()
                }
                val nextIncognito = ImeLogic.isIncognitoImeOptions(
                    imeOptions = _imeState.value.currentImeOptions,
                    inputType = _imeState.value.currentInputType,
                    manualIncognitoMode = currentSettings.manualIncognitoMode
                )
                typingController.setIncognitoMode(nextIncognito)
                _imeState.update {
                    it.copy(
                        activeInputScheme = currentSettings.inputScheme,
                        codetableEnhancerActive = typingController.activeEngine is CodetableEnhancedInputEngine,
                        singleCharModeEnabled = typingController.codetableEnhancedEngine?.singleCharModeEnabled ?: false,
                        pinnedWordsForCurrentCode = typingController.codetableEnhancedEngine?.currentPinnedWordsForBuffer().orEmpty(),
                        isRimeDeploying = currentSettings.inputScheme.usesRime && !typingController.isRimeReady,
                        activeHeightLevel = currentSettings.keyboardHeightLevel,
                        activeHeightMode = currentSettings.keyboardHeightMode,
                        activeLiftLevel = currentSettings.keyboardLiftLevel,
                        isIncognitoMode = nextIncognito
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
        currentEditorFieldId = attribute?.fieldId ?: 0
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
        currentEditorFieldId = info?.fieldId ?: 0
        repository.setImeWindowVisible(true)
        val latestHeightLevel = repository.settings.value.keyboardHeightLevel
        val latestHeightMode = repository.settings.value.keyboardHeightMode
        val latestLiftLevel = repository.settings.value.keyboardLiftLevel
        if (!restarting) {
            voiceController.finalizeJevDraftAndClearMemory(clearContext = true)
            typingController.resetComposing()
            _imeState.update {
                it.copy(
                    isClipboardOpen = false,
                    isToolPanelOpen = false,
                    isToolPanelHeightExpanded = false,
                    voiceSubPanel = VoiceSubPanel.NONE,
                    capturedChatContext = CapturedChatContext(),
                    ignoreChatContextForThisTurn = false,
                    showChatContextDisclosurePrompt = false,
                    jevCopilotResult = null,
                    selectedJevStrategyIndex = -1,
                    isJevLoading = false,
                    jevErrorMessage = null,
                    jevUserBottomLine = "",
                    jevExcludedReplies = emptyList(),
                    isFullScreenPeekCapturing = false,
                    isJevContextInspectorOpen = false,
                    isPunctuationStripOpen = false,
                    isStylePickerExpanded = false,
                    flypyComposingCode = "",
                    preeditDisplay = "",
                    flypyCandidates = emptyList(),
                    isOrqDateActive = false,
                    orqDateOffsetDays = 0,
                    passiveMathResult = null,
                    passiveMathExpression = null,
                    passiveMathEndsWithEquals = false,
                    l2PhraseSuggestionWord = null,
                    l2PhraseSuggestionCode = null,
                    englishSuggestions = emptyList(),
                    englishSuggestionReplaceLength = 0,
                    isFlypyCandidateSheetExpanded = false,
                    predictionCandidates = emptyList(),
                    aiCompletionSuggestion = null,
                    activeHeightLevel = latestHeightLevel,
                    activeHeightMode = latestHeightMode,
                    activeLiftLevel = latestLiftLevel
                )
            }
        } else {
            _imeState.update {
                it.copy(
                    activeHeightLevel = latestHeightLevel,
                    activeHeightMode = latestHeightMode,
                    activeLiftLevel = latestLiftLevel
                )
            }
        }
        syncEditorMetadata(info, restarting)
        typingController.codetableEnhancedEngine?.let { eng ->
            eng.reloadShadowRules()
            eng.reloadUserDictWordsCache()
        }
        syncCodetableUiFlags()
        syncSystemClipboardIfNeeded(allowFreshBanner = true)
        syncCurrentEditorText()
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
        voiceController.finalizeJevDraftAndClearMemory(clearContext = true)
        repository.setImeWindowVisible(false)
        _imeState.update {
            it.copy(
                capturedChatContext = CapturedChatContext(),
                ignoreChatContextForThisTurn = false,
                showChatContextDisclosurePrompt = false,
                jevCopilotResult = null,
                selectedJevStrategyIndex = -1,
                isJevLoading = false,
                jevErrorMessage = null,
                jevUserBottomLine = "",
                jevExcludedReplies = emptyList(),
                isFullScreenPeekCapturing = false,
                isJevContextInspectorOpen = false
            )
        }
        super.onFinishInputView(finishingInput)
    }

    override fun onComputeInsets(outInsets: Insets?) {
        super.onComputeInsets(outInsets)
        if (outInsets == null) return
        val decorHeight = window?.window?.decorView?.height ?: 0
        if (_imeState.value.isFullScreenPeekCapturing) {
            if (decorHeight > 0) {
                outInsets.contentTopInsets = decorHeight
                outInsets.visibleTopInsets = decorHeight
                outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_FRAME
                outInsets.touchableRegion.setEmpty()
            }
            return
        }
        val floatingOverhangPx = (KeyboardMetrics.FLOATING_PREEDIT_OVERHANG_DP * resources.displayMetrics.density).toInt()
        if (floatingOverhangPx > 0 && decorHeight > floatingOverhangPx) {
            val adjustedTop = KeyboardMetrics.computeFloatingOverhangContentTopInset(
                baseContentTopInsets = outInsets.contentTopInsets,
                floatingOverhangPx = floatingOverhangPx,
                decorHeight = decorHeight
            )
            outInsets.contentTopInsets = adjustedTop
            outInsets.visibleTopInsets = adjustedTop
            outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_CONTENT
            outInsets.touchableRegion.setEmpty()
        }
    }

    private fun requestRefreshImeInsets() {
        runCatching {
            window?.window?.decorView?.let { decor ->
                decor.requestLayout()
                decor.invalidate()
            }
        }
    }

    private fun syncEditorMetadata(info: EditorInfo?, restarting: Boolean) {
        currentEditorFieldId = info?.fieldId ?: 0
        val pkg = info?.packageName.orEmpty()
        val appName = repository.resolveAppName(pkg)
        val inputType = info?.inputType ?: 0
        val imeOptions = info?.imeOptions ?: 0
        val selected = readSelectedTextSafely()
        val hasMicPerm = ::audioRecorder.isInitialized && audioRecorder.hasRecordPermission()
        val hasCachedWav = hasRetryWavFile()
        val settingsSnapshot = repository.settings.value
        val isPasswordField = ImeLogic.isPasswordEditorInputType(inputType)
        val isIncognito = ImeLogic.isIncognitoImeOptions(
            imeOptions = imeOptions,
            inputType = inputType,
            manualIncognitoMode = settingsSnapshot.manualIncognitoMode
        )
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
        voiceController.onEditorTargetChanged(pkg, info?.fieldId ?: 0, restarting)
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
        val isFlypyComposing = _imeState.value.isFlypyComposing
        val isSystemComposing = candidatesStart >= 0 && candidatesEnd > candidatesStart
        val isComposingNow = isFlypyComposing || isSystemComposing
        androidCommitExecutor?.notifyExternalCursorChange(newSelStart, newSelEnd)
        val looksClearedByHostSend = newSelStart == 0 &&
            newSelEnd == 0 &&
            candidatesStart == -1 &&
            candidatesEnd == -1
        val nowMs = System.currentTimeMillis()
        val jevActive = voiceController.isJevFeedbackActive()
        val shouldSyncEditorText = when {
            isComposingNow -> false
            looksClearedByHostSend -> true
            jevActive -> voiceController.shouldPollEditorTextForJev(isComposing = false)
            else -> (nowMs - lastEditorTextSyncMs) >= 180L
        }
        if (shouldSyncEditorText) {
            syncCurrentEditorText()
        }
        val editorSnapshotForJev = when {
            isComposingNow -> null
            shouldSyncEditorText -> _imeState.value.currentEditorText
            jevActive && (nowMs - lastEditorTextSyncMs) <= 120L && _imeState.value.currentEditorText.isNotEmpty() ->
                _imeState.value.currentEditorText
            else -> null
        }
        voiceController.onSelectionUpdated(
            oldSelStart = oldSelStart,
            oldSelEnd = oldSelEnd,
            newSelStart = newSelStart,
            newSelEnd = newSelEnd,
            candidatesStart = candidatesStart,
            candidatesEnd = candidatesEnd,
            isComposing = isComposingNow,
            editorTextSnapshot = editorSnapshotForJev
        )
    }

    override fun onWindowHidden() {
        tryAutoLearnFromRecentEdit()
        repository.dailyStatsStore.flushAsync()
        repository.setImeWindowVisible(false)
        super.onWindowHidden()
        voiceController.stopRecordingOnTabSwitchOrWindowHidden()
        repository.jevAnchorExtractor.sessionPin.clear()
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
                capturedChatContext = CapturedChatContext(),
                ignoreChatContextForThisTurn = false,
                showChatContextDisclosurePrompt = false,
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
                isOrqDateActive = false,
                orqDateOffsetDays = 0,
                passiveMathResult = null,
                passiveMathExpression = null,
                passiveMathEndsWithEquals = false,
                isPhraseRecording = false,
                recordedPhraseBuffer = "",
                recordedPhraseCode = null,
                l2PhraseSuggestionWord = null,
                l2PhraseSuggestionCode = null,
                englishSuggestions = emptyList(),
                englishSuggestionReplaceLength = 0,
                isFlypyCandidateSheetExpanded = false,
                predictionCandidates = emptyList(),
                aiCompletionSuggestion = null
            )
        }
    }

    override fun onDestroy() {
        repository.dailyStatsStore.flushAsync()
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

        val keyboardMetrics = remember(
            uiState.activeHeightLevel,
            uiState.activeHeightMode,
            uiState.activeLiftLevel,
            settings.qwertyShowNumberRow,
            isLandscape
        ) {
            KeyboardMetrics.fromLevel(
                level = uiState.activeHeightLevel,
                isLandscape = isLandscape,
                heightMode = uiState.activeHeightMode,
                liftLevel = uiState.activeLiftLevel,
                showNumberRow = settings.qwertyShowNumberRow
            )
        }

        val (activeStyle, isAutoMatched) = remember(
            settings,
            uiState.targetPackage,
            uiState.currentInputType
        ) {
            repository.resolveActiveStyle(uiState.targetPackage, uiState.currentInputType)
        }

        val baseDensity = LocalDensity.current
        val lockedImeDensity = remember(baseDensity.density) {
            Density(density = baseDensity.density, fontScale = 1.0f)
        }

        TypeFreeTheme(themePreference = settings.themePreference) {
            CompositionLocalProvider(
                LocalDensity provides lockedImeDensity,
                LocalKeyboardMetrics provides keyboardMetrics
            ) {
                KeyboardScreen(
                    uiState = uiState,
                    settings = settings,
                    activeStyle = activeStyle,
                    isAutoMatched = isAutoMatched,
                    amplitudeProvider = { audioRecorder.amplitudes.value.lastOrNull() ?: 0f },
                    amplitudes = amplitudes,
                    elapsedMs = elapsedMs,
                    didPauseMedia = didPauseMedia,
                    clipboardItems = clipboardItems,
                    onAction = ::handleImeUiAction
                )
            }
        }
    }

    private fun handleImeUiAction(action: ImeUiAction) {
        when (action) {
            is ImeUiAction.SelectTab -> selectTabMode(action.tab)
            ImeUiAction.ToggleToolPanel -> toggleToolPanel()
            ImeUiAction.ToggleToolPanelHeight -> toggleToolPanelHeight()
            ImeUiAction.OpenHelpMeWrite -> voiceController.openOrRestoreVoicePenPanel()
            ImeUiAction.CloseSubPanel -> voiceController.closeVoiceSubPanel()
            ImeUiAction.DismissSpeakToEdit -> voiceController.dismissSpeakToEditForTurn()
            ImeUiAction.OpenClipboard -> openClipboardPanel()
            ImeUiAction.CloseClipboard -> closeClipboardPanel()
            ImeUiAction.ToggleStylePicker -> toggleStylePicker()
            ImeUiAction.OpenStylePickerFromToolPanel -> openStylePickerFromToolPanel()
            ImeUiAction.TogglePunctuationStrip -> voiceController.togglePunctuationStrip()
            is ImeUiAction.OpenHostApp -> openHostApp(action.tabIndex, action.subTabIndex)
            ImeUiAction.OpenJevCopilot -> voiceController.openJevCopilotPanel()
            is ImeUiAction.SelectJevStrategyCard -> voiceController.selectJevStrategyCard(action.index)
            ImeUiAction.RefreshJevStrategies -> voiceController.refreshJevStrategies()
            ImeUiAction.TriggerFullScreenPeekCapture ->
                voiceController.triggerFullScreenPeekCapture(::requestRefreshImeInsets)
            ImeUiAction.ToggleJevContextInspector -> voiceController.toggleJevContextInspector()
            ImeUiAction.ToggleJevRelationOverlay -> voiceController.toggleJevRelationOverlay()
            is ImeUiAction.SelectJevPrivateRelation -> voiceController.selectJevPrivateRelation(action.tag)
            is ImeUiAction.SelectJevGroupScene -> voiceController.selectJevGroupScene(action.scene)
            is ImeUiAction.AppendJevNoteQuickChip -> voiceController.appendJevNoteQuickChip(action.chipText)
            ImeUiAction.PasteJevNoteFromClipboard -> voiceController.pasteJevNoteFromClipboard()
            ImeUiAction.ClearJevRelationNote -> voiceController.clearJevRelationNote()
            is ImeUiAction.RebindJevSessionAlias -> voiceController.rebindJevSessionAlias(action.targetSessionUid)

            ImeUiAction.StartVoiceRecording -> voiceController.handleMicPressDown(VoiceIntentMode.DICTATE)
            ImeUiAction.StopVoiceRecording -> voiceController.handleMicPressUp()
            is ImeUiAction.MicPressDown -> voiceController.handleMicPressDown(action.mode)
            ImeUiAction.MicPressUp -> voiceController.handleMicPressUp()
            ImeUiAction.FinishHandsFree -> voiceController.finishHandsFreeRecording()
            ImeUiAction.CancelVoiceRecording -> voiceController.cancelRecordingOrProcessing()
            ImeUiAction.RetryLastAudio -> voiceController.retryLastAudio()
            ImeUiAction.DismissError -> voiceController.dismissErrorBanner()
            is ImeUiAction.EnterArcTranslate -> voiceController.enterArcTranslateMode(action.startedFromHandsFree)
            is ImeUiAction.UpdateArcTranslate ->
                voiceController.updateArcTranslateSelection(action.selectedLanguage, action.cancelHovered)
            ImeUiAction.ReleaseArcTranslate -> voiceController.releaseArcTranslate()
            is ImeUiAction.SelectTranslateLanguage -> voiceController.selectTranslateLanguage(action.language)

            is ImeUiAction.SelectQuickEditChip -> voiceController.runQuickEditChip(action.chip)
            is ImeUiAction.SelectHelpWritePreset -> voiceController.toggleHelpWritePreset(action.preset)
            is ImeUiAction.RunHelpWriteDraftTransform -> voiceController.runHelpWriteDraftTransform(action.preset)
            ImeUiAction.OpenAskAiCard -> voiceController.openAskAiCardPanel()
            ImeUiAction.ReturnFromAskAiToQwerty -> voiceController.returnFromAskAiToQwerty()
            ImeUiAction.RetryAskAiCard -> voiceController.retryAskAiCard()
            ImeUiAction.DismissAskAiAndClearEditor -> voiceController.dismissAskAiAndClearEditor()
            ImeUiAction.CopyAskAiAnswer -> voiceController.copyAskAiAnswer()
            ImeUiAction.CommitAskAiAnswerToEditor -> voiceController.commitAskAiAnswerToEditor()
            ImeUiAction.DirectGenerateHelpWrite -> voiceController.runSelectedHelpWritePresetDirect()
            ImeUiAction.AcceptChatContextDisclosure -> voiceController.acceptChatContextDisclosure()
            ImeUiAction.DeclineChatContextDisclosure -> voiceController.declineChatContextDisclosure()
            ImeUiAction.ToggleIgnoreChatContext -> voiceController.toggleIgnoreChatContextForTurn()

            ImeUiAction.SelectAutoStyle -> selectAutoStyle()
            is ImeUiAction.SelectManualStyle -> selectManualStyle(action.style)
            ImeUiAction.ToggleWhisperMode -> voiceController.toggleWhisperMode()
            ImeUiAction.ToggleManualIncognito -> toggleManualIncognito()
            ImeUiAction.ToggleAiCompletion -> toggleAiCompletion()
            ImeUiAction.ToggleLiveWrite -> toggleLiveWrite()
            ImeUiAction.CycleInputScheme -> cycleInputScheme()
            is ImeUiAction.SelectKeyboardHeightLevel -> selectKeyboardHeightLevel(action.level)
            is ImeUiAction.SelectKeyboardHeightMode -> selectKeyboardHeightMode(action.mode)
            is ImeUiAction.SelectKeyboardLiftLevel -> selectKeyboardLiftLevel(action.liftLevel)

            ImeUiAction.TriggerUndo -> performUndo()
            ImeUiAction.ClearInputBox -> clearCurrentInputBox()
            is ImeUiAction.PinCandidate -> pinCandidateFromBar(action.word)
            is ImeUiAction.UnpinCandidate -> unpinCandidateFromBar(action.word)
            is ImeUiAction.BlockCandidate -> blockCandidateFromBar(action.word)
            ImeUiAction.ToggleSingleCharMode -> toggleSingleCharMode()
            is ImeUiAction.TriggerKeyGesture -> typingController.handleKeyGesture(action.binding)
            ImeUiAction.SwipeUpPeriodSlash -> typingController.triggerSwipeUpPeriodSlash()
            is ImeUiAction.StepOrqDate -> typingController.stepOrqDate(action.deltaDays)
            ImeUiAction.TogglePhraseRecording -> typingController.togglePhraseRecording()
            ImeUiAction.AcceptPhraseSuggestion -> typingController.acceptPhraseSuggestion()
            ImeUiAction.DismissPhraseSuggestion -> typingController.dismissPhraseSuggestion()
            is ImeUiAction.SelectPassiveMath -> typingController.selectPassiveMath(action.result)
            is ImeUiAction.SelectEnglishSuggestion -> typingController.selectEnglishSuggestion(action.word)
            is ImeUiAction.CommitText -> commitDirectText(action.text, recordUndo = true, markManualEdit = true)
            is ImeUiAction.CommitQwertyChar -> typingController.commitQwertyChar(action.char)
            is ImeUiAction.CommaOrPeriodKey -> typingController.handleCommaOrPeriodKey(action.isComma)
            ImeUiAction.ToggleFlypyLanguageMode -> typingController.toggleFlypyLanguageMode()
            is ImeUiAction.SelectFlypyCandidate -> typingController.selectFlypyCandidate(action.index)
            is ImeUiAction.SelectT9Syllable -> typingController.selectT9Syllable(action.syllable)
            ImeUiAction.ClearFlypyComposing -> typingController.clearFlypyComposing()
            ImeUiAction.FlushFlypyComposing -> typingController.flushFlypyComposing()
            ImeUiAction.ToggleFlypyCandidateSheet -> typingController.toggleFlypyCandidateSheet()
            ImeUiAction.RequestMoreCandidates -> typingController.requestMoreCandidates()
            is ImeUiAction.SelectPredictionCandidate -> selectPredictionCandidate(action.index)
            is ImeUiAction.SelectAiCompletion -> selectAiCompletion(action.text)
            ImeUiAction.DismissPredictions -> dismissPredictions()
            is ImeUiAction.RunEditorShortcut -> typingController.handleEditorShortcutAction(action.action)
            is ImeUiAction.InsertPairedSymbol -> typingController.commitPairedSymbol(action.left, action.right)
            is ImeUiAction.CommitSecondarySymbol -> typingController.commitSecondarySymbol(action.symbol)
            is ImeUiAction.MoveCursorByChars -> typingController.moveCursorByChars(action.delta)
            ImeUiAction.InsertSpace -> typingController.insertSpace()
            ImeUiAction.Backspace -> typingController.deleteBackward()
            ImeUiAction.EnterOrNewline -> performEnterOrNewline()
            ImeUiAction.HideKeyboard -> hideKeyboardSelf()
            ImeUiAction.SwitchKeyboard -> switchKeyboard()
            ImeUiAction.ShowImePicker -> showImePicker()

            is ImeUiAction.PasteClipboardText -> pasteFromClipboard(action.text)
            ImeUiAction.DismissFreshClipboardBanner -> dismissFreshClipboardBanner()
            is ImeUiAction.TogglePinClipboardItem -> togglePinClipboardEntry(action.id)
            is ImeUiAction.DeleteClipboardItem -> deleteClipboardEntry(action.id)
            ImeUiAction.ClearUnpinnedClipboard -> clearUnpinnedClipboardEntries()
            ImeUiAction.SaveSelectionToClipboard -> saveCurrentSelectionToClipboard()
            ImeUiAction.EnableClipboard -> enableClipboardFromKeyboard()
            ImeUiAction.OpenClipboardSettings -> openClipboardPreferencesInHostApp()
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
        } else {
            typingController.onPanelSwitched()
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
                capturedChatContext = CapturedChatContext(),
                ignoreChatContextForThisTurn = false,
                showChatContextDisclosurePrompt = false,
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
            recordKeyboardCommitStatsIfEligible(text)
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
        syncCurrentEditorText()
    }

    override fun currentEditorFieldId(): Int = currentEditorFieldId

    private fun recordKeyboardCommitStatsIfEligible(text: String) {
        if (text.isBlank()) return
        val stateSnap = _imeState.value
        val settingsSnap = repository.settings.value
        if (!settingsSnap.inputStatsEnabled ||
            settingsSnap.manualIncognitoMode ||
            stateSnap.isIncognitoMode ||
            ImeLogic.isPasswordEditorInputType(stateSnap.currentInputType)
        ) {
            return
        }
        repository.dailyStatsStore.recordKeyboardCommit(
            charCount = text.length,
            schemeKey = SchemeStatsKeys.fromInputScheme(
                scheme = settingsSnap.inputScheme,
                isChineseMode = stateSnap.isFlypyChineseMode
            ),
            appName = stateSnap.targetAppName
        )
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

    override fun onUserDeleteOrCutForJev(remainingTextLengthHint: Int) {
        voiceController.onUserDeleteOrCutForJev(remainingTextLengthHint)
    }

    override fun onUserUndoForJev() {
        voiceController.onUserUndoForJev()
    }

    override fun onExplicitImeSendForJev(currentEditorText: String?) {
        voiceController.onExplicitImeSendForJev(currentEditorText)
    }

    override fun readClipboardTextForJevNote(): String? {
        val cm = clipboardManager
            ?: (getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager).also {
                clipboardManager = it
            }
            ?: return null
        val clip = runCatching { cm.primaryClip }.getOrNull() ?: return null
        if (clip.itemCount <= 0) return null
        val isSensitive = runCatching {
            clip.description?.extras?.getBoolean("android.content.extra.IS_SENSITIVE", false) ?: false
        }.getOrDefault(false)
        if (isSensitive) return null
        return runCatching { clip.getItemAt(0)?.coerceToText(this)?.toString() }.getOrNull()
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
        if (_imeState.value.isIncognitoMode || !typingController.activeAllowsAutoLearn) {
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
        voiceController.onUserUndoForJev()

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

    fun clearCurrentInputBox() {
        if (_imeState.value.isFlypyComposing) {
            typingController.clearComposing()
        }
        val ic = currentInputConnection ?: return
        val extracted = runCatching {
            ic.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)
        }.getOrNull()
        val extractedText = extracted?.text?.toString()
        val beforeText = if (extractedText == null) {
            runCatching { ic.getTextBeforeCursor(8000, 0)?.toString().orEmpty() }.getOrDefault("")
        } else {
            ""
        }
        val afterText = if (extractedText == null) {
            runCatching { ic.getTextAfterCursor(8000, 0)?.toString().orEmpty() }.getOrDefault("")
        } else {
            ""
        }
        val fullText = extractedText ?: (beforeText + afterText)
        if (fullText.isEmpty()) {
            performKeyFeedback(strong = false)
            showQuickActionToast("输入框已为空")
            return
        }

        performKeyFeedback(strong = true)
        clearAutoLearnBaseline()
        val snapshot = UndoSnapshot(
            insertedTextLength = 0,
            replacedOriginalText = fullText
        )
        undoStack.addFirst(snapshot)
        while (undoStack.size > 10) {
            undoStack.removeLast()
        }

        ic.beginBatchEdit()
        if (extractedText != null) {
            ic.setSelection(0, extractedText.length)
            ic.commitText("", 1)
        } else {
            ic.deleteSurroundingText(beforeText.length, afterText.length)
        }
        ic.endBatchEdit()

        _imeState.update { it.copy(undoSnapshot = undoStack.firstOrNull()) }
        showQuickActionToast("已清空输入框（可撤回）")
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

    fun openHostApp(tabIndex: Int = AppDestinations.TAB_HOME, subTabIndex: Int? = null) {
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
                if (subTabIndex != null) {
                    putExtra(AppDestinations.EXTRA_SETTINGS_SUBTAB, subTabIndex)
                }
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
        } else {
            typingController.onPanelSwitched()
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

    private fun dismissFreshClipboardBanner() {
        performKeyFeedback(strong = false)
        freshClipBannerJob?.cancel()
        _imeState.update { it.copy(freshClipboardBannerItem = null) }
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
                putExtra(AppDestinations.EXTRA_OPEN_TAB, AppDestinations.TAB_GENERAL)
                putExtra(AppDestinations.EXTRA_SETTINGS_SUBTAB, AppDestinations.SUBTAB_PREFERENCES)
            }
            startActivity(intent)
            requestHideSelf(0)
        } catch (_: Exception) {
        }
    }

    // -------------------------------------------------------------------------
    // Tool Panel (4x2 Grid) & Prediction / AI Completion Slot Actions
    // -------------------------------------------------------------------------

    private fun toggleToolPanel() {
        performKeyFeedback(strong = false)
        val willOpen = !_imeState.value.isToolPanelOpen
        if (willOpen) {
            if (_imeState.value.isFlypyComposing) {
                typingController.resetComposing()
            } else {
                typingController.onPanelSwitched()
            }
            voiceController.stopRecordingOnTabSwitchOrWindowHidden()
        }
        _imeState.update { prev ->
            prev.copy(
                isToolPanelOpen = willOpen,
                isToolPanelHeightExpanded = if (willOpen) prev.isToolPanelHeightExpanded else false,
                isClipboardOpen = false,
                voicePhase = if (willOpen && prev.voicePhase is VoicePhase.Recording) VoicePhase.Idle else prev.voicePhase,
                isStylePickerExpanded = false,
                isPunctuationStripOpen = false
            )
        }
    }

    private fun toggleToolPanelHeight() {
        performKeyFeedback(strong = false)
        _imeState.update { it.copy(isToolPanelHeightExpanded = !it.isToolPanelHeightExpanded) }
    }

    private fun selectKeyboardHeightLevel(level: Int) {
        val clamped = level.coerceIn(KeyboardMetrics.MIN_LEVEL, KeyboardMetrics.MAX_LEVEL)
        performKeyFeedback(strong = false)
        repository.updateSettings { it.copy(keyboardHeightLevel = clamped) }
        _imeState.update { it.copy(activeHeightLevel = clamped) }
    }

    private fun selectKeyboardHeightMode(mode: KeyboardHeightMode) {
        performKeyFeedback(strong = false)
        repository.updateSettings { it.copy(keyboardHeightMode = mode) }
        _imeState.update { it.copy(activeHeightMode = mode) }
    }

    private fun selectKeyboardLiftLevel(liftLevel: Int) {
        val clamped = liftLevel.coerceIn(KeyboardMetrics.MIN_LIFT_LEVEL, KeyboardMetrics.MAX_LIFT_LEVEL)
        performKeyFeedback(strong = false)
        repository.updateSettings { it.copy(keyboardLiftLevel = clamped) }
        _imeState.update { it.copy(activeLiftLevel = clamped) }
    }

    private fun cycleInputScheme() {
        performKeyFeedback(strong = false)
        val current = repository.settings.value.inputScheme
        val hasCustom = TypeFreeApp.instance.rimeDataManager.hasCustomSchemaImported()
        val next = nextCycledInputScheme(current, hasCustomSchema = hasCustom)
        typingController.resetComposing()
        repository.updateSettings { it.copy(inputScheme = next) }
        _imeState.update { it.copy(activeInputScheme = next) }
        showQuickActionToast("输入方案：${next.label}")
    }

    private fun toggleManualIncognito() {
        performKeyFeedback(strong = true)
        val nextManual = !repository.settings.value.manualIncognitoMode
        repository.updateSettings { it.copy(manualIncognitoMode = nextManual) }
        val curr = _imeState.value
        val effectiveIncognito = ImeLogic.isIncognitoImeOptions(
            imeOptions = curr.currentImeOptions,
            inputType = curr.currentInputType,
            manualIncognitoMode = nextManual
        )
        typingController.setIncognitoMode(effectiveIncognito)
        _imeState.update {
            it.copy(
                isIncognitoMode = effectiveIncognito,
                capturedChatContext = if (effectiveIncognito) CapturedChatContext() else it.capturedChatContext,
                aiCompletionSuggestion = if (effectiveIncognito) null else it.aiCompletionSuggestion
            )
        }
        showQuickActionToast(
            if (nextManual) "🕵️ 已开启手动无痕模式（暂停历史、词频与读屏）" else "✓ 已关闭手动无痕模式"
        )
    }

    private fun openStylePickerFromToolPanel() {
        performKeyFeedback(strong = false)
        _imeState.update {
            it.copy(
                isToolPanelOpen = false,
                isToolPanelHeightExpanded = false,
                isStylePickerExpanded = true,
                isPunctuationStripOpen = false,
                isHistoryDrawerOpen = false
            )
        }
    }

    private fun toggleAiCompletion() {
        performKeyFeedback(strong = false)
        val next = !repository.settings.value.aiCompletionEnabled
        repository.updateSettings { it.copy(aiCompletionEnabled = next) }
        if (!next) {
            _imeState.update { it.copy(aiCompletionSuggestion = null) }
        }
        showQuickActionToast(if (next) "✦ AI 整句补全已开启" else "AI 整句补全已关闭")
    }

    private fun toggleLiveWrite() {
        performKeyFeedback(strong = false)
        val next = !repository.settings.value.liveWriteToEditor
        repository.updateSettings { it.copy(liveWriteToEditor = next) }
        showQuickActionToast(if (next) "✓ 实时写入输入框已开启" else "实时写入输入框已关闭")
    }

    private fun selectPredictionCandidate(index: Int) {
        typingController.selectPredictionCandidate(index)
    }

    private fun selectAiCompletion(@Suppress("UNUSED_PARAMETER") text: String) {
        typingController.selectAiCompletion()
    }

    private fun dismissPredictions() {
        typingController.dismissPredictions()
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

    override fun copyTextToClipboard(text: String) {
        runCatching {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
            cm.setPrimaryClip(android.content.ClipData.newPlainText("TypeFree AskAI", text))
        }
    }

    override fun openJevCopilotFromCommand() {
        voiceController.openJevCopilotPanel()
    }

    private fun applyCodetableV362Settings(
        eng: CodetableEnhancedInputEngine,
        settings: com.typefree.ime.data.AppSettings
    ) {
        eng.setXiaoheDirectCommandsEnabled(settings.xiaoheDirectCommandsEnabled)
        eng.setXiaoheReverseSplitEnabled(settings.xiaoheReverseSplitEnabled)
        eng.setXiaoheNaturalContinuationEnabled(settings.xiaoheNaturalContinuationEnabled)
        eng.setXiaoheNaturalContinuationLimit(settings.xiaoheNaturalContinuationLimit)
        eng.setPhraseSuggestionL2Enabled(settings.phraseSuggestionL2Enabled)
    }

    private fun saveUserPhraseAsync(word: String, code: String) {
        val cleanWord = word.trim()
        val cleanCode = code.trim().lowercase()
        if (cleanWord.isEmpty() || cleanCode.isEmpty()) return
        serviceScope.launch(Dispatchers.IO) {
            val dm = TypeFreeApp.instance.rimeDataManager
            val schemaId = dm.activeSchemaProfile.schemaId.ifBlank { "flypy" }
            val result = runCatching {
                dm.getUserPhraseEditor(schemaId).addPhrase(cleanWord, cleanCode, isTopPinned = false)
                val sharedUserFile = File(dm.sharedDir, "${schemaId}_user.txt")
                if (sharedUserFile.isFile) {
                    runCatching {
                        dm.userDir.mkdirs()
                        sharedUserFile.copyTo(File(dm.userDir, "${schemaId}_user.txt"), overwrite = true)
                    }
                }
                dm.reloadActiveEnhancedSchemaSync(schemaId)
            }
            withContext(Dispatchers.Main) {
                if (result.isSuccess) {
                    typingController.codetableEnhancedEngine?.reloadUserDictWordsCache(schemaId)
                    showQuickActionToast("✓ 已存入个人词：$cleanWord · $cleanCode")
                } else {
                    showQuickActionToast("保存个人词失败：${result.exceptionOrNull()?.message ?: "未知错误"}")
                }
            }
        }
    }

    private fun bindCodetableEnhancedEngineIfAvailable() {
        val dm = TypeFreeApp.instance.rimeDataManager
        if (!dm.hasXiaoheSchemaInstalled() && !dm.hasCustomSchemaImported()) return

        val latestSettings = repository.settings.value
        val existing = typingController.codetableEnhancedEngine
        if (existing != null) {
            existing.refreshFromMetadata(
                profile = dm.activeSchemaProfile,
                symbolTrie = dm.activeSymbolTrie,
                briefIndex = dm.activeBriefIndex,
                reverseLookupMap = dm.activeReverseLookupMap,
                liveContinuationWordFrequencyLookup = dm.activeLiveWordFrequencyLookup
            )
            applyCodetableV362Settings(existing, latestSettings)
            existing.updateSnippets(latestSettings.snippets)
            syncCodetableUiFlags()
            return
        }

        val executor = AndroidCommitExecutor.fromInputConnection(
            inputConnectionProvider = { currentInputConnection() },
            onCopyClipboard = { code, toast ->
                copyTextToClipboard(code)
                showQuickActionToast(toast)
            },
            onDirectCommand = { cmd ->
                when (cmd) {
                    DirectCommandType.OPEN_JEV_COPILOT ->
                        openJevCopilotFromCommand()
                    DirectCommandType.TOGGLE_FULL_CODE_DICT -> {
                        val next = typingController.codetableEnhancedEngine
                            ?.subDictToggleManager
                            ?.toggleFullCodeDict(dm.activeSchemaProfile.schemaId) ?: false
                        showQuickActionToast(if (next) "✓ 全码字词库已开启 (oqm)" else "✓ 全码字词库已关闭 (oqm)")
                    }
                    DirectCommandType.TOGGLE_ASCII_PUNCT -> {
                        val next = !dm.rimeBridge.getOption("ascii_punct")
                        dm.rimeBridge.setOption("ascii_punct", next)
                        showQuickActionToast(if (next) "英文标点 (.)" else "中文标点 (。)")
                    }
                    DirectCommandType.TOGGLE_SIMPLIFICATION -> {
                        val next = !dm.rimeBridge.getOption("simplification")
                        dm.rimeBridge.setOption("simplification", next)
                        showQuickActionToast(if (next) "繁體中文模式" else "简体中文模式")
                    }
                    DirectCommandType.TOGGLE_SINGLE_CHAR_MODE -> {
                        val enabled = typingController.codetableEnhancedEngine?.singleCharModeEnabled ?: false
                        _imeState.update { it.copy(singleCharModeEnabled = enabled) }
                        showQuickActionToast(if (enabled) "✓ 单字模式已开启 (odz)" else "✓ 单字模式已关闭 (odz)")
                    }
                }
            },
            onAutoLearnEligibleCommit = { recordManualEditTouch() },
            onTextCommitted = { committedText, allowAutoLearn ->
                onEnhancedEngineCommitted(committedText, allowAutoLearn)
            },
            onUndoExecuted = {
                onEnhancedEngineUndo()
            }
        )
        androidCommitExecutor = executor
        val created = CodetableEnhancedInputEngine(
            rimeClient = dm.rimeBridge,
            initialProfile = dm.activeSchemaProfile,
            symbolTrie = dm.activeSymbolTrie,
            briefIndex = dm.activeBriefIndex,
            reverseLookupMap = dm.activeReverseLookupMap,
            schemaDir = dm.sharedDir,
            clipboardReader = { readSystemClipboardText() },
            commitExecutor = executor,
            onToast = { msg -> showQuickActionToast(msg) },
            onOpenJevCopilot = { openJevCopilotFromCommand() },
            onAsyncReloadSchema = { schemaId ->
                serviceScope.launch(Dispatchers.IO) {
                    dm.rimeBridge.deploy(schemaId)
                    withContext(Dispatchers.Main) {
                        typingController.codetableEnhancedEngine?.let { eng ->
                            if (typingController.activeEngine === eng) {
                                eng.coordinator.updateProfile(eng.activeProfile)
                                val s = repository.settings.value
                                applyCodetableV362Settings(eng, s)
                                eng.setIncognitoMode(_imeState.value.isIncognitoMode)
                                eng.setFixedCandidateOrder(s.fixedCandidateOrder)
                            }
                        }
                    }
                }
            },
            isActiveEnginePredicate = {
                typingController.activeEngine === typingController.codetableEnhancedEngine
            },
            isIncognitoPredicate = {
                _imeState.value.isIncognitoMode ||
                    ImeLogic.isPasswordEditorInputType(_imeState.value.currentInputType)
            },
            onSingleCharModeChanged = { enabled ->
                _imeState.update { it.copy(singleCharModeEnabled = enabled) }
            },
            onPhraseSuggestion = { suggestion ->
                typingController.onL2PhraseSuggestion(suggestion)
            },
            onUiStateSynced = { _, singleCharEnabled, pinnedWords ->
                _imeState.update {
                    it.copy(
                        codetableEnhancerActive = typingController.activeEngine is CodetableEnhancedInputEngine,
                        singleCharModeEnabled = singleCharEnabled,
                        pinnedWordsForCurrentCode = pinnedWords
                    )
                }
            },
            naturalContinuationLimit = latestSettings.xiaoheNaturalContinuationLimit,
            liveContinuationWordFrequencyLookup = dm.activeLiveWordFrequencyLookup
        )
        applyCodetableV362Settings(created, latestSettings)
        created.updateSnippets(latestSettings.snippets)
        typingController.codetableEnhancedEngine = created
        syncCodetableUiFlags()
    }

    private fun syncCodetableUiFlags() {
        val eng = typingController.codetableEnhancedEngine
        _imeState.update {
            it.copy(
                codetableEnhancerActive = typingController.activeEngine is CodetableEnhancedInputEngine,
                singleCharModeEnabled = eng?.singleCharModeEnabled ?: false,
                pinnedWordsForCurrentCode = eng?.currentPinnedWordsForBuffer().orEmpty()
            )
        }
    }

    private fun pinCandidateFromBar(word: String) {
        val eng = typingController.codetableEnhancedEngine ?: return
        performKeyFeedback(strong = false)
        eng.pinCandidate(word)
        typingController.syncEngineStateToUi()
        syncCodetableUiFlags()
        showQuickActionToast("📌 已置顶首选「$word」")
    }

    private fun unpinCandidateFromBar(word: String) {
        val eng = typingController.codetableEnhancedEngine ?: return
        performKeyFeedback(strong = false)
        eng.unpinCandidate(word)
        typingController.syncEngineStateToUi()
        syncCodetableUiFlags()
        showQuickActionToast("已取消置顶「$word」")
    }

    private fun blockCandidateFromBar(word: String) {
        val eng = typingController.codetableEnhancedEngine ?: return
        performKeyFeedback(strong = true)
        eng.blockCandidate(word)
        typingController.syncEngineStateToUi()
        syncCodetableUiFlags()
        showQuickActionToast("已屏蔽「$word」")
    }

    private fun toggleSingleCharMode() {
        val eng = typingController.codetableEnhancedEngine ?: return
        performKeyFeedback(strong = false)
        val next = eng.toggleSingleCharMode()
        typingController.syncEngineStateToUi()
        syncCodetableUiFlags()
        showQuickActionToast(if (next) "✓ 单字模式已开启" else "✓ 单字模式已关闭")
    }

    private fun onEnhancedEngineCommitted(committedText: String, @Suppress("UNUSED_PARAMETER") allowAutoLearn: Boolean) {
        if (committedText.isNotEmpty()) {
            typingController.recordExternalCommit(committedText)
            recordKeyboardCommitStatsIfEligible(committedText)
            val undo = ImeLogic.createUndoSnapshot(
                committedText = committedText,
                replacedSelection = ""
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
        syncCurrentEditorText()
        if (committedText.isNotEmpty()) {
            typingController.onExternalCommittedTextForV362(committedText)
            typingController.predictionCoordinator.onWordCommitted(
                committedText = committedText,
                isComposing = typingController.isComposing
            )
        }
    }

    private fun onEnhancedEngineUndo() {
        undoStack.removeFirstOrNull()
        _imeState.update { it.copy(undoSnapshot = undoStack.firstOrNull()) }
        onUserUndoForJev()
        syncCurrentEditorText()
    }

    private fun syncCurrentEditorText() {
        lastEditorTextSyncMs = System.currentTimeMillis()
        val stateSnap = _imeState.value
        if (ImeLogic.isPasswordEditorInputType(stateSnap.currentInputType)) {
            if (stateSnap.currentEditorText.isNotEmpty()) {
                _imeState.update { it.copy(currentEditorText = "") }
            }
            return
        }
        val ic = currentInputConnection ?: return
        val text = try {
            val before = ic.getTextBeforeCursor(1000, 0)?.toString().orEmpty()
            val sel = ic.getSelectedText(0)?.toString().orEmpty()
            val after = ic.getTextAfterCursor(1000, 0)?.toString().orEmpty()
            (before + sel + after).trim()
        } catch (_: Exception) {
            ""
        }
        if (text != stateSnap.currentEditorText) {
            _imeState.update { it.copy(currentEditorText = text) }
        }
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
        return WavAudioRecorder.archiveWavForHistory(
            baseDir = filesDir,
            wavFile = wavFile,
            retention = retention
        )
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
