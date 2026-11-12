package com.typefree.ime.ime

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.typefree.ime.data.AppSettings
import com.typefree.ime.data.ClipboardEntry
import com.typefree.ime.data.DefaultPresets
import com.typefree.ime.data.KeyboardHeightMode
import com.typefree.ime.data.ToneStyle
import com.typefree.ime.data.VoiceIntentMode
import com.typefree.ime.ime.candidates.CandidateGridOverlay
import com.typefree.ime.ime.candidates.CandidateStrip
import com.typefree.ime.ime.candidates.PredictionCandidateBar
import com.typefree.ime.ime.chrome.ErrorContextBanner
import com.typefree.ime.ime.chrome.InlineStyleSelectorStrip
import com.typefree.ime.ime.chrome.TypeFreeTopBar
import com.typefree.ime.ime.panels.ArcTranslateFullPanel
import com.typefree.ime.ime.panels.HelpMeWritePanel
import com.typefree.ime.ime.panels.JevCopilotPanel
import com.typefree.ime.ime.panels.RealtimeTranslatePanel
import com.typefree.ime.ime.panels.SpeakToEditPanel
import com.typefree.ime.ime.panels.VoiceMainPanel
import com.typefree.ime.ui.AppDestinations
import com.typefree.ime.ui.theme.LocalTypeFreeKeyboardPalette
import com.typefree.ime.ui.theme.TypeFreeEmerald
import com.typefree.ime.ui.theme.TypeFreeRose

/**
 * TypeFree v3.1.0 Root Keyboard Orchestrator (`<= 650` lines).
 *
 * Preserves the exact `298.dp` [KeyboardMetrics.frameHeight] and `LIFT` bottom cushion so
 * switching across `VOICE`, `QWERTY`, `TRANSLATE`, `CLIPBOARD`, `TOOL_PANEL`, `HELP_ME_WRITE`,
 * `SPEAK_TO_EDIT`, and `ArcTranslate` causes 0px WindowInsets relayout.
 */
@Composable
fun KeyboardScreen(
    uiState: ImeUiState,
    settings: AppSettings,
    activeStyle: ToneStyle,
    isAutoMatched: Boolean,
    amplitudeProvider: () -> Float = { 0f },
    amplitudes: List<Float> = emptyList(),
    elapsedMs: Long,
    didPauseMedia: Boolean = false,
    clipboardItems: List<ClipboardEntry> = emptyList(),
    onAction: (ImeUiAction) -> Unit = {},
    onToggleWhisperMode: () -> Unit = { onAction(ImeUiAction.ToggleWhisperMode) },
    onSelectTabMode: (KeyboardTabMode) -> Unit = { onAction(ImeUiAction.SelectTab(it)) },
    onOpenHelpMeWrite: () -> Unit = { onAction(ImeUiAction.OpenHelpMeWrite) },
    onCloseSubPanel: () -> Unit = { onAction(ImeUiAction.CloseSubPanel) },
    onAcceptChatContextDisclosure: () -> Unit = { onAction(ImeUiAction.AcceptChatContextDisclosure) },
    onDeclineChatContextDisclosure: () -> Unit = { onAction(ImeUiAction.DeclineChatContextDisclosure) },
    onToggleIgnoreChatContext: () -> Unit = { onAction(ImeUiAction.ToggleIgnoreChatContext) },
    onDismissSpeakToEdit: () -> Unit = { onAction(ImeUiAction.DismissSpeakToEdit) },
    onSelectTranslateLanguage: (String) -> Unit = { onAction(ImeUiAction.SelectTranslateLanguage(it)) },
    onTogglePunctuationStrip: () -> Unit = { onAction(ImeUiAction.TogglePunctuationStrip) },
    onToggleStylePicker: () -> Unit = { onAction(ImeUiAction.ToggleStylePicker) },
    onSelectAutoStyle: () -> Unit = { onAction(ImeUiAction.SelectAutoStyle) },
    onSelectManualStyle: (ToneStyle) -> Unit = { onAction(ImeUiAction.SelectManualStyle(it)) },
    onPerformUndo: () -> Unit = { onAction(ImeUiAction.TriggerUndo) },
    onOpenHostApp: (Int) -> Unit = { onAction(ImeUiAction.OpenHostApp(it)) },
    onQuickEditChipClick: (QuickEditChip) -> Unit = { onAction(ImeUiAction.SelectQuickEditChip(it)) },
    onHelpWritePresetClick: (HelpWritePromptPreset) -> Unit = { onAction(ImeUiAction.SelectHelpWritePreset(it)) },
    onHelpWriteDirectGenerate: () -> Unit = { onAction(ImeUiAction.DirectGenerateHelpWrite) },
    onMicPressDown: (VoiceIntentMode) -> Unit = { onAction(ImeUiAction.MicPressDown(it)) },
    onMicPressUp: () -> Unit = { onAction(ImeUiAction.MicPressUp) },
    onEnterArcTranslate: (Boolean) -> Unit = { onAction(ImeUiAction.EnterArcTranslate(it)) },
    onUpdateArcTranslate: (String, Boolean) -> Unit = { lang, cancel ->
        onAction(ImeUiAction.UpdateArcTranslate(lang, cancel))
    },
    onReleaseArcTranslate: () -> Unit = { onAction(ImeUiAction.ReleaseArcTranslate) },
    onFinishHandsFree: () -> Unit = { onAction(ImeUiAction.FinishHandsFree) },
    onCancelRecording: () -> Unit = { onAction(ImeUiAction.CancelVoiceRecording) },
    onRetryLastAudio: () -> Unit = { onAction(ImeUiAction.RetryLastAudio) },
    onDismissError: () -> Unit = { onAction(ImeUiAction.DismissError) },
    onCommitText: (String) -> Unit = { onAction(ImeUiAction.CommitText(it)) },
    onCommitQwertyChar: (String) -> Unit = { onAction(ImeUiAction.CommitQwertyChar(it)) },
    onCommaOrPeriodKey: (Boolean) -> Unit = { onAction(ImeUiAction.CommaOrPeriodKey(it)) },
    onToggleFlypyLanguageMode: () -> Unit = { onAction(ImeUiAction.ToggleFlypyLanguageMode) },
    onSelectFlypyCandidate: (Int) -> Unit = { onAction(ImeUiAction.SelectFlypyCandidate(it)) },
    onClearFlypyComposing: () -> Unit = { onAction(ImeUiAction.ClearFlypyComposing) },
    onFlushFlypyComposing: () -> Unit = { onAction(ImeUiAction.FlushFlypyComposing) },
    onToggleFlypyCandidateSheet: () -> Unit = { onAction(ImeUiAction.ToggleFlypyCandidateSheet) },
    onRequestMoreCandidates: () -> Unit = { onAction(ImeUiAction.RequestMoreCandidates) },
    onEditorShortcutAction: (EditorShortcutAction) -> Unit = { onAction(ImeUiAction.RunEditorShortcut(it)) },
    onInsertPairedSymbol: (String, String) -> Unit = { l, r -> onAction(ImeUiAction.InsertPairedSymbol(l, r)) },
    onHideKeyboard: () -> Unit = { onAction(ImeUiAction.HideKeyboard) },
    onSwitchKeyboard: () -> Unit = { onAction(ImeUiAction.SwitchKeyboard) },
    onShowImePicker: () -> Unit = { onAction(ImeUiAction.ShowImePicker) },
    onInsertSpace: () -> Unit = { onAction(ImeUiAction.InsertSpace) },
    onBackspace: () -> Unit = { onAction(ImeUiAction.Backspace) },
    onEnterOrNewline: () -> Unit = { onAction(ImeUiAction.EnterOrNewline) },
    onOpenClipboard: () -> Unit = { onAction(ImeUiAction.OpenClipboard) },
    onCloseClipboard: () -> Unit = { onAction(ImeUiAction.CloseClipboard) },
    onPasteClipboardText: (String) -> Unit = { onAction(ImeUiAction.PasteClipboardText(it)) },
    onTogglePinClipboardItem: (String) -> Unit = { onAction(ImeUiAction.TogglePinClipboardItem(it)) },
    onDeleteClipboardItem: (String) -> Unit = { onAction(ImeUiAction.DeleteClipboardItem(it)) },
    onClearUnpinnedClipboard: () -> Unit = { onAction(ImeUiAction.ClearUnpinnedClipboard) },
    onSaveSelectionToClipboard: () -> Unit = { onAction(ImeUiAction.SaveSelectionToClipboard) },
    onEnableClipboard: () -> Unit = { onAction(ImeUiAction.EnableClipboard) },
    onOpenClipboardSettings: () -> Unit = { onAction(ImeUiAction.OpenClipboardSettings) },
    onToggleToolPanel: () -> Unit = { onAction(ImeUiAction.ToggleToolPanel) },
    onToggleToolPanelHeight: () -> Unit = { onAction(ImeUiAction.ToggleToolPanelHeight) },
    onSelectKeyboardHeightLevel: (Int) -> Unit = { onAction(ImeUiAction.SelectKeyboardHeightLevel(it)) },
    onSelectKeyboardHeightMode: (KeyboardHeightMode) -> Unit = { onAction(ImeUiAction.SelectKeyboardHeightMode(it)) },
    onSelectKeyboardLiftLevel: (Int) -> Unit = { onAction(ImeUiAction.SelectKeyboardLiftLevel(it)) },
    onCycleInputScheme: () -> Unit = { onAction(ImeUiAction.CycleInputScheme) },
    onOpenStylePickerFromToolPanel: () -> Unit = { onAction(ImeUiAction.OpenStylePickerFromToolPanel) },
    onToggleAiCompletion: () -> Unit = { onAction(ImeUiAction.ToggleAiCompletion) },
    onToggleLiveWrite: () -> Unit = { onAction(ImeUiAction.ToggleLiveWrite) },
    onToggleManualIncognito: () -> Unit = { onAction(ImeUiAction.ToggleManualIncognito) },
    onSelectPredictionCandidate: (Int) -> Unit = { onAction(ImeUiAction.SelectPredictionCandidate(it)) },
    onSelectAiCompletion: (String) -> Unit = { onAction(ImeUiAction.SelectAiCompletion(it)) },
    onDismissPredictions: () -> Unit = { onAction(ImeUiAction.DismissPredictions) }
) {
    val palette = LocalTypeFreeKeyboardPalette.current
    val metrics = LocalKeyboardMetrics.current
    val resolvedSubPanel = uiState.resolvedVoiceSubPanel
    val isInSubPanel = uiState.tabMode == KeyboardTabMode.VOICE && resolvedSubPanel != VoiceSubPanel.NONE
    val isActiveQwertyGrid = !uiState.isClipboardOpen &&
        !uiState.isToolPanelOpen &&
        !isInSubPanel &&
        !uiState.isArcTranslateActive &&
        uiState.tabMode == KeyboardTabMode.QWERTY

    val topDividerColor by animateColorAsState(
        targetValue = when (uiState.voicePhase) {
            is VoicePhase.Error -> TypeFreeRose.copy(alpha = 0.65f)
            is VoicePhase.SuccessToast -> TypeFreeEmerald.copy(alpha = 0.55f)
            else -> palette.keyBorder
        },
        animationSpec = tween(durationMillis = 200),
        label = "topDividerColor"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (uiState.isFullScreenPeekCapturing) 0f else 1f)
            .background(if (uiState.isFullScreenPeekCapturing) androidx.compose.ui.graphics.Color.Transparent else palette.keyboardCanvas)
            .navigationBarsPadding()
            .height(metrics.frameHeight)
    ) {
        // Subtle 1.dp top divider (fixed across all height levels)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(metrics.dividerHeight)
                .background(topDividerColor)
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .then(
                    if (isActiveQwertyGrid) {
                        val qwertyTopPad = if (uiState.isFlypyComposing) 1.dp else 5.dp
                        Modifier.padding(top = qwertyTopPad, bottom = 0.dp)
                    } else {
                        Modifier.padding(
                            start = 12.dp,
                            end = 12.dp,
                            top = 5.dp,
                            bottom = 6.dp
                        )
                    }
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(if (uiState.isArcTranslateActive) 0f else 1f),
                verticalArrangement = Arrangement.spacedBy(if (isActiveQwertyGrid) 2.dp else 4.dp)
            ) {
                when {
                    uiState.isClipboardOpen -> {
                        ClipboardKeyboardPanel(
                            items = clipboardItems,
                            clipboardEnabled = settings.clipboardEnabled,
                            selectedEditorText = uiState.rawSelectedText,
                            onPasteText = onPasteClipboardText,
                            onTogglePin = onTogglePinClipboardItem,
                            onDeleteItem = onDeleteClipboardItem,
                            onClearUnpinned = onClearUnpinnedClipboard,
                            onSaveSelectionToClipboard = onSaveSelectionToClipboard,
                            onEnableClipboard = onEnableClipboard,
                            onOpenClipboardSettings = onOpenClipboardSettings,
                            onClose = onCloseClipboard,
                            onHideKeyboard = onHideKeyboard,
                            onInsertSpace = onInsertSpace,
                            onBackspace = onBackspace
                        )
                    }

                    uiState.tabMode == KeyboardTabMode.VOICE &&
                        resolvedSubPanel == VoiceSubPanel.JEV_COPILOT -> {
                        JevCopilotPanel(
                            uiState = uiState,
                            settings = settings,
                            onAction = onAction
                        )
                    }

                    uiState.tabMode == KeyboardTabMode.VOICE &&
                        resolvedSubPanel == VoiceSubPanel.HELP_ME_WRITE -> {
                        HelpMeWritePanel(
                            uiState = uiState,
                            settings = settings,
                            amplitudeProvider = amplitudeProvider,
                            amplitudes = amplitudes,
                            elapsedMs = elapsedMs,
                            onClose = onCloseSubPanel,
                            onOpenJevCopilot = { onAction(ImeUiAction.OpenJevCopilot) },
                            onAcceptChatContextDisclosure = onAcceptChatContextDisclosure,
                            onDeclineChatContextDisclosure = onDeclineChatContextDisclosure,
                            onToggleIgnoreChatContext = onToggleIgnoreChatContext,
                            onSwitchToQwerty = { onSelectTabMode(KeyboardTabMode.QWERTY) },
                            onOpenClipboard = onOpenClipboard,
                            onPresetClick = onHelpWritePresetClick,
                            onDirectGenerate = onHelpWriteDirectGenerate,
                            onStartRecording = { onMicPressDown(VoiceIntentMode.HELP_ME_WRITE) },
                            onReleaseRecording = onMicPressUp,
                            onFinishHandsFree = onFinishHandsFree,
                            onCancelRecording = onCancelRecording,
                            onOpenHostApp = onOpenHostApp,
                            onRetryLastAudio = onRetryLastAudio,
                            onDismissError = onDismissError,
                            onHideKeyboard = onHideKeyboard,
                            onSwitchKeyboard = onSwitchKeyboard,
                            onShowImePicker = onShowImePicker
                        )
                    }

                    uiState.tabMode == KeyboardTabMode.VOICE &&
                        resolvedSubPanel == VoiceSubPanel.SPEAK_TO_EDIT -> {
                        SpeakToEditPanel(
                            uiState = uiState,
                            amplitudeProvider = amplitudeProvider,
                            amplitudes = amplitudes,
                            elapsedMs = elapsedMs,
                            onDismissSelection = onDismissSpeakToEdit,
                            onSwitchToQwerty = { onSelectTabMode(KeyboardTabMode.QWERTY) },
                            onOpenClipboard = onOpenClipboard,
                            onQuickChipClick = onQuickEditChipClick,
                            onStartRecording = { onMicPressDown(VoiceIntentMode.SPEAK_TO_EDIT) },
                            onReleaseRecording = onMicPressUp,
                            onFinishHandsFree = onFinishHandsFree,
                            onCancelRecording = onCancelRecording,
                            onOpenHostApp = onOpenHostApp,
                            onRetryLastAudio = onRetryLastAudio,
                            onDismissError = onDismissError,
                            onHideKeyboard = onHideKeyboard,
                            onSwitchKeyboard = onSwitchKeyboard,
                            onShowImePicker = onShowImePicker
                        )
                    }

                    else -> {
                        val headerSidePadModifier = if (isActiveQwertyGrid) {
                            Modifier.padding(horizontal = 12.dp)
                        } else {
                            Modifier
                        }

                        // Fixed 34.dp Top Bar Slot
                        Box(modifier = Modifier.fillMaxWidth().then(headerSidePadModifier)) {
                            when {
                                uiState.isFlypyComposing -> {
                                    CandidateStrip(
                                        composingCode = uiState.flypyComposingCode,
                                        preeditDisplay = uiState.preeditDisplay,
                                        inputScheme = uiState.activeInputScheme,
                                        candidates = uiState.flypyCandidates,
                                        hasMoreCandidates = uiState.hasMoreCandidates,
                                        isSheetExpanded = uiState.isFlypyCandidateSheetExpanded,
                                        onSelectCandidate = onSelectFlypyCandidate,
                                        onToggleSheet = onToggleFlypyCandidateSheet,
                                        onClearCode = onClearFlypyComposing,
                                        onRequestMoreCandidates = onRequestMoreCandidates
                                    )
                                }

                                !uiState.isToolPanelOpen && uiState.isPredictionBarVisible -> {
                                    PredictionCandidateBar(
                                        isToolPanelOpen = uiState.isToolPanelOpen,
                                        predictions = uiState.predictionCandidates,
                                        aiCompletion = uiState.aiCompletionSuggestion,
                                        onToggleToolPanel = onToggleToolPanel,
                                        onSelectPrediction = onSelectPredictionCandidate,
                                        onSelectAiCompletion = onSelectAiCompletion,
                                        onDismiss = onDismissPredictions
                                    )
                                }

                                else -> {
                                    TypeFreeTopBar(
                                        uiState = uiState,
                                        settings = settings,
                                        activeStyle = activeStyle,
                                        isAutoMatched = isAutoMatched,
                                        onToggleToolPanel = onToggleToolPanel,
                                        onSelectTabMode = onSelectTabMode,
                                        onOpenHelpMeWrite = onOpenHelpMeWrite,
                                        onToggleFlypyLanguageMode = onToggleFlypyLanguageMode,
                                        onToggleStylePicker = onToggleStylePicker,
                                        onPerformUndo = onPerformUndo,
                                        onPasteClipboardText = onPasteClipboardText,
                                        onToggleManualIncognito = onToggleManualIncognito,
                                        onOpenJevCopilot = { onAction(ImeUiAction.OpenJevCopilot) },
                                        onToggleClipboard = {
                                            if (uiState.isClipboardOpen) onCloseClipboard() else onOpenClipboard()
                                        },
                                        onStartVoiceDictation = { onMicPressDown(VoiceIntentMode.DICTATE) },
                                        onStopVoiceDictation = onMicPressUp
                                    )
                                }
                            }
                        }

                        // Mode Body: TOOL_PANEL | VOICE | QWERTY | TRANSLATE
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .then(
                                    if (isActiveQwertyGrid) {
                                        Modifier.padding(horizontal = 3.dp)
                                    } else {
                                        Modifier
                                    }
                                )
                        ) {
                            when {
                                uiState.isToolPanelOpen -> {
                                    KeyboardToolPanel(
                                        uiState = uiState,
                                        settings = settings,
                                        activeStyle = activeStyle,
                                        clipboardCount = clipboardItems.size,
                                        onOpenClipboard = onOpenClipboard,
                                        onToggleHeightExpanded = onToggleToolPanelHeight,
                                        onSelectHeightLevel = onSelectKeyboardHeightLevel,
                                        onSelectHeightMode = onSelectKeyboardHeightMode,
                                        onSelectLiftLevel = onSelectKeyboardLiftLevel,
                                        onCycleInputScheme = onCycleInputScheme,
                                        onOpenStylePicker = onOpenStylePickerFromToolPanel,
                                        onToggleAiCompletion = onToggleAiCompletion,
                                        onToggleLiveWrite = onToggleLiveWrite,
                                        onToggleWhisperMode = onToggleWhisperMode,
                                        onOpenHelpMeWrite = onOpenHelpMeWrite,
                                        onOpenJevCopilot = { onAction(ImeUiAction.OpenJevCopilot) },
                                        onToggleManualIncognito = onToggleManualIncognito,
                                        onOpenDictionary = { onOpenHostApp(AppDestinations.TAB_DICTIONARY) },
                                        onOpenSettings = { onOpenHostApp(AppDestinations.TAB_SETTINGS) }
                                    )
                                }

                                uiState.tabMode == KeyboardTabMode.VOICE -> {
                                    VoiceMainPanel(
                                        uiState = uiState,
                                        settings = settings,
                                        amplitudeProvider = amplitudeProvider,
                                        amplitudes = amplitudes,
                                        elapsedMs = elapsedMs,
                                        didPauseMedia = didPauseMedia,
                                        onSwipeToTab = onSelectTabMode,
                                        onVoicePenClick = onOpenHelpMeWrite,
                                        onOpenJevCopilot = { onAction(ImeUiAction.OpenJevCopilot) },
                                        onMicPressDown = { onMicPressDown(VoiceIntentMode.DICTATE) },
                                        onMicPressUp = onMicPressUp,
                                        onEnterArcTranslate = onEnterArcTranslate,
                                        onUpdateArcTranslate = onUpdateArcTranslate,
                                        onReleaseArcTranslate = onReleaseArcTranslate,
                                        onFinishHandsFree = onFinishHandsFree,
                                        onCancelRecording = onCancelRecording,
                                        onTogglePunctuationStrip = onTogglePunctuationStrip,
                                        onCommitText = onCommitText,
                                        onInsertPairedSymbol = onInsertPairedSymbol,
                                        onBackspace = onBackspace,
                                        onEnterOrNewline = onEnterOrNewline,
                                        onHideKeyboard = onHideKeyboard,
                                        onSwitchKeyboard = onSwitchKeyboard,
                                        onShowImePicker = onShowImePicker
                                    )
                                }

                                uiState.tabMode == KeyboardTabMode.QWERTY -> {
                                    BuiltInQwertyKeyboard(
                                        enterActionInfo = uiState.enterActionInfo,
                                        isFlypyChineseMode = uiState.isFlypyChineseMode,
                                        flypyComposingCode = uiState.flypyComposingCode,
                                        flypyCandidateCount = uiState.flypyCandidates.size,
                                        commaPeriodQuickSelect = settings.flypyCommaPeriodQuickSelect,
                                        onCommitChar = onCommitQwertyChar,
                                        onCommitSecondarySymbol = { onAction(ImeUiAction.CommitSecondarySymbol(it)) },
                                        onInsertPairedSymbol = onInsertPairedSymbol,
                                        onCommaOrPeriodKey = onCommaOrPeriodKey,
                                        onInsertSpace = onInsertSpace,
                                        onMoveCursorByStep = { onAction(ImeUiAction.MoveCursorByChars(it)) },
                                        onToggleFlypyLanguageMode = onToggleFlypyLanguageMode,
                                        onClearComposing = onClearFlypyComposing,
                                        onFlushComposingBeforeLayerSwitch = onFlushFlypyComposing,
                                        onBackspace = onBackspace,
                                        onSwipeUpBackspaceUndo = onPerformUndo,
                                        onEditorShortcutAction = onEditorShortcutAction,
                                        onEnterOrNewline = onEnterOrNewline,
                                        onSwitchToVoice = { onSelectTabMode(KeyboardTabMode.VOICE) },
                                        onSwitchSystemIme = onSwitchKeyboard,
                                        onHideKeyboard = onHideKeyboard,
                                        onShowImePicker = onShowImePicker,
                                        showNumberRow = settings.qwertyShowNumberRow,
                                        inputSessionToken = uiState.inputSessionToken,
                                        showKeyPopupPreview = settings.qwertyKeyPopupPreview,
                                        activeInputScheme = uiState.activeInputScheme
                                    )

                                    if (
                                        uiState.isFlypyComposing &&
                                        uiState.isFlypyCandidateSheetExpanded &&
                                        uiState.flypyCandidates.isNotEmpty()
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .padding(horizontal = 5.dp, vertical = 3.dp)
                                        ) {
                                            CandidateGridOverlay(
                                                candidates = uiState.flypyCandidates,
                                                hasMoreCandidates = uiState.hasMoreCandidates,
                                                onSelectCandidate = onSelectFlypyCandidate,
                                                onBackspace = onBackspace,
                                                onCollapse = onToggleFlypyCandidateSheet,
                                                onRequestMoreCandidates = onRequestMoreCandidates
                                            )
                                        }
                                    }
                                }

                                else -> {
                                    RealtimeTranslatePanel(
                                        uiState = uiState,
                                        settings = settings,
                                        amplitudeProvider = amplitudeProvider,
                                        amplitudes = amplitudes,
                                        elapsedMs = elapsedMs,
                                        onSelectLanguage = onSelectTranslateLanguage,
                                        onStartRecording = { onMicPressDown(VoiceIntentMode.TRANSLATE) },
                                        onReleaseRecording = onMicPressUp,
                                        onFinishHandsFree = onFinishHandsFree,
                                        onCancelRecording = onCancelRecording,
                                        onBackspace = onBackspace,
                                        onEnterOrNewline = onEnterOrNewline,
                                        onHideKeyboard = onHideKeyboard,
                                        onSwitchKeyboard = onSwitchKeyboard,
                                        onShowImePicker = onShowImePicker
                                    )
                                }
                            }

                            // In-frame Top Overlay for Style Selector Strip & Error Context Banner
                            val overlaySidePad = if (isActiveQwertyGrid) {
                                Modifier.padding(horizontal = 9.dp)
                            } else {
                                Modifier
                            }
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .align(Alignment.TopCenter)
                                    .zIndex(8f)
                                    .then(overlaySidePad),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                AnimatedVisibility(
                                    visible = uiState.isStylePickerExpanded &&
                                        !uiState.isFlypyComposing &&
                                        !uiState.isToolPanelOpen,
                                    enter = fadeIn(tween(130)) + expandVertically(tween(150)),
                                    exit = fadeOut(tween(110)) + shrinkVertically(tween(130))
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(14.dp))
                                            .background(palette.keyboardCanvas.copy(alpha = 0.96f))
                                            .border(1.dp, palette.keyBorder, RoundedCornerShape(14.dp))
                                            .padding(horizontal = 6.dp, vertical = 4.dp)
                                    ) {
                                        InlineStyleSelectorStrip(
                                            settings = settings,
                                            onSelectAutoStyle = onSelectAutoStyle,
                                            onSelectManualStyle = onSelectManualStyle,
                                            onOpenStyleRules = { onOpenHostApp(AppDestinations.TAB_STYLES) }
                                        )
                                    }
                                }

                                val errorPhase = uiState.voicePhase as? VoicePhase.Error
                                if (errorPhase != null && !uiState.isFlypyComposing && !uiState.isToolPanelOpen) {
                                    ErrorContextBanner(
                                        error = errorPhase,
                                        onRetry = onRetryLastAudio,
                                        onOpenApiSetup = { onOpenHostApp(AppDestinations.TAB_API) },
                                        onOpenPermissionSetup = { onOpenHostApp(AppDestinations.TAB_HOME) },
                                        onDismiss = onDismissError
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Full-panel Hold & Swipe-Up Arc Translate Overlay (`Release to translate`)
            if (uiState.isArcTranslateActive) {
                ArcTranslateFullPanel(
                    targets = settings.translationTargets.ifEmpty {
                        DefaultPresets.defaultTranslationTargets()
                    },
                    selectedLanguage = uiState.arcSelectedLanguage.ifBlank {
                        settings.activeTranslateTarget
                    },
                    startedFromHandsFree = uiState.arcStartedFromHandsFree,
                    isCancelHovered = uiState.isArcCancelHovered,
                    amplitudeProvider = amplitudeProvider,
                    amplitudes = amplitudes,
                    onHideKeyboard = onHideKeyboard,
                    onSwitchKeyboard = onSwitchKeyboard
                )
            }
        }

        // LIFT mode (垫高): blank bottom cushion that raises every panel without stretching keys.
        if (metrics.liftHeight > 0.dp) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(metrics.liftHeight)
                    .background(palette.keyboardCanvas)
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false).consume()
                        }
                    }
            )
        }
    }
}
