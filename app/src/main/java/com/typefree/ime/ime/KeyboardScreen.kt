package com.typefree.ime.ime

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.typefree.ime.data.AppSettings
import com.typefree.ime.data.ClipboardEntry
import com.typefree.ime.data.DefaultPresets
import com.typefree.ime.data.InputScheme
import com.typefree.ime.data.KeyboardHeightMode
import com.typefree.ime.data.ToneStyle
import com.typefree.ime.data.VoiceIntentMode
import com.typefree.ime.ime.clipboard.ClipboardSmartExtractor
import com.typefree.ime.ime.clipboard.SmartClipKind
import com.typefree.ime.ime.flypy.FlypyCandidate
import com.typefree.ime.ui.AppDestinations
import com.typefree.ime.ui.components.TypeFreeIcons
import com.typefree.ime.ui.theme.LocalTypeFreeKeyboardPalette
import com.typefree.ime.ui.theme.TypeFreeAmber
import com.typefree.ime.ui.theme.TypeFreeEmerald
import com.typefree.ime.ui.theme.TypeFreeRose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.sin

@Composable
fun KeyboardScreen(
    uiState: ImeUiState,
    settings: AppSettings,
    activeStyle: ToneStyle,
    isAutoMatched: Boolean,
    amplitudes: List<Float>,
    elapsedMs: Long,
    didPauseMedia: Boolean = false,
    onToggleWhisperMode: () -> Unit = {},
    onSelectTabMode: (KeyboardTabMode) -> Unit,
    onOpenHelpMeWrite: () -> Unit,
    onCloseSubPanel: () -> Unit,
    onAcceptChatContextDisclosure: () -> Unit = {},
    onDeclineChatContextDisclosure: () -> Unit = {},
    onToggleIgnoreChatContext: () -> Unit = {},
    onDismissSpeakToEdit: () -> Unit,
    onSelectTranslateLanguage: (String) -> Unit,
    onTogglePunctuationStrip: () -> Unit,
    onToggleStylePicker: () -> Unit,
    onSelectAutoStyle: () -> Unit,
    onSelectManualStyle: (ToneStyle) -> Unit,
    onPerformUndo: () -> Unit,
    onOpenHostApp: (Int) -> Unit,
    onQuickEditChipClick: (QuickEditChip) -> Unit,
    onHelpWritePresetClick: (HelpWritePromptPreset) -> Unit,
    onHelpWriteDirectGenerate: () -> Unit = {},
    onMicPressDown: (VoiceIntentMode) -> Unit,
    onMicPressUp: () -> Unit,
    onEnterArcTranslate: (Boolean) -> Unit,
    onUpdateArcTranslate: (String, Boolean) -> Unit,
    onReleaseArcTranslate: () -> Unit,
    onFinishHandsFree: () -> Unit,
    onCancelRecording: () -> Unit,
    onRetryLastAudio: () -> Unit,
    onDismissError: () -> Unit,
    onCommitText: (String) -> Unit,
    onCommitQwertyChar: (String) -> Unit,
    onCommaOrPeriodKey: (Boolean) -> Unit,
    onToggleFlypyLanguageMode: () -> Unit,
    onSelectFlypyCandidate: (Int) -> Unit,
    onClearFlypyComposing: () -> Unit,
    onFlushFlypyComposing: () -> Unit,
    onToggleFlypyCandidateSheet: () -> Unit,
    onRequestMoreCandidates: () -> Unit = {},
    onEditorShortcutAction: (EditorShortcutAction) -> Unit,
    onInsertPairedSymbol: (String, String) -> Unit,
    onHideKeyboard: () -> Unit,
    onSwitchKeyboard: () -> Unit,
    onShowImePicker: () -> Unit,
    onInsertSpace: () -> Unit,
    onBackspace: () -> Unit,
    onEnterOrNewline: () -> Unit,
    clipboardItems: List<ClipboardEntry> = emptyList(),
    onOpenClipboard: () -> Unit = {},
    onCloseClipboard: () -> Unit = {},
    onPasteClipboardText: (String) -> Unit = {},
    onTogglePinClipboardItem: (String) -> Unit = {},
    onDeleteClipboardItem: (String) -> Unit = {},
    onClearUnpinnedClipboard: () -> Unit = {},
    onSaveSelectionToClipboard: () -> Unit = {},
    onEnableClipboard: () -> Unit = {},
    onOpenClipboardSettings: () -> Unit = {},
    onToggleToolPanel: () -> Unit = {},
    onToggleToolPanelHeight: () -> Unit = {},
    onSelectKeyboardHeightLevel: (Int) -> Unit = {},
    onSelectKeyboardHeightMode: (KeyboardHeightMode) -> Unit = {},
    onSelectKeyboardLiftLevel: (Int) -> Unit = {},
    onCycleInputScheme: () -> Unit = {},
    onOpenStylePickerFromToolPanel: () -> Unit = {},
    onToggleAiCompletion: () -> Unit = {},
    onToggleLiveWrite: () -> Unit = {},
    onToggleManualIncognito: () -> Unit = {},
    onSelectPredictionCandidate: (Int) -> Unit = {},
    onSelectAiCompletion: (String) -> Unit = {},
    onDismissPredictions: () -> Unit = {}
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

    // Unified frame height from LocalKeyboardMetrics across VOICE, QWERTY, TRANSLATE,
    // CLIPBOARD, TOOL_PANEL, HELP_ME_WRITE, SPEAK_TO_EDIT, and ArcTranslate so cross-panel
    // switches and inline style/error overlays cause strictly 0px WindowInsets relayout!
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.keyboardCanvas)
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

        // Root layered stage: keeps VoiceMainPanel mounted with alpha(0f) when ArcTranslate overlay
        // activates mid-gesture so active pointer tracking never breaks before finger-up!
        // When QWERTY is active, outer horizontal squeeze is lifted so the 10-column grid expands
        // edge-to-edge (`3.dp` side margin) for a balanced ~1.18:1 keycap aspect ratio.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .then(
                    if (isActiveQwertyGrid) {
                        Modifier.padding(top = 8.dp, bottom = 0.dp)
                    } else {
                        Modifier.padding(
                            start = 12.dp,
                            end = 12.dp,
                            top = 8.dp,
                            bottom = (18f * metrics.elementScale).dp
                        )
                    }
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(if (uiState.isArcTranslateActive) 0f else 1f),
                verticalArrangement = Arrangement.spacedBy(if (isActiveQwertyGrid) 3.dp else 6.dp)
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
                        resolvedSubPanel == VoiceSubPanel.HELP_ME_WRITE -> {
                        HelpMeWritePanel(
                            uiState = uiState,
                            settings = settings,
                            amplitudes = amplitudes,
                            elapsedMs = elapsedMs,
                            onClose = onCloseSubPanel,
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

                        // Fixed 34.dp Top Bar Slot: renders CandidateStrip when composing,
                        // PredictionCandidateBar when predicting, or TypeFreeTopBar otherwise
                        // so QWERTY key positions NEVER shift vertically by even 1px!
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
                                        onToggleManualIncognito = onToggleManualIncognito
                                    )
                                }
                            }
                        }

                        // Mode Body: TOOL_PANEL | VOICE | QWERTY | TRANSLATE
                        // Style selector strip and error banner are overlaid at Alignment.TopCenter
                        // with zIndex above the key area so frameHeight never jumps!
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
                                        onToggleManualIncognito = onToggleManualIncognito,
                                        onOpenDictionary = { onOpenHostApp(AppDestinations.TAB_DICTIONARY) },
                                        onOpenSettings = { onOpenHostApp(AppDestinations.TAB_SETTINGS) }
                                    )
                                }

                                uiState.tabMode == KeyboardTabMode.VOICE -> {
                                    VoiceMainPanel(
                                        uiState = uiState,
                                        settings = settings,
                                        amplitudes = amplitudes,
                                        elapsedMs = elapsedMs,
                                        didPauseMedia = didPauseMedia,
                                        onToggleWhisperMode = onToggleWhisperMode,
                                        onSwipeToTab = onSelectTabMode,
                                        onVoicePenClick = onOpenHelpMeWrite,
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
                                        onCommaOrPeriodKey = onCommaOrPeriodKey,
                                        onInsertSpace = onInsertSpace,
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
                            // (Eliminates 298 -> 334.dp height jump by floating over the top of the key area)
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
                    amplitudes = amplitudes,
                    onHideKeyboard = onHideKeyboard,
                    onSwitchKeyboard = onSwitchKeyboard
                )
            }
        }

        // LIFT mode (垫高): blank bottom cushion that raises every panel without stretching keys.
        // Consumes taps so the gap never triggers accidental key presses; 0.dp in STRETCH mode.
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

// =============================================================================
// 1. TypeFree Top Bar + 3-Segment Mode Capsule Switcher (`VOICE` / `QWERTY` / `TRANSLATE`)
// =============================================================================

@Composable
private fun TopBarToolPanelButton(
    isOpen: Boolean,
    onClick: () -> Unit
) {
    val palette = LocalTypeFreeKeyboardPalette.current
    val bgColor = if (isOpen) palette.primaryMicSolid else palette.keyCapBg
    val borderColor = if (isOpen) Color.Transparent else palette.keyBorder
    val iconTint = if (isOpen) palette.onPrimaryMic else palette.keyTextPrimary

    Box(
        modifier = Modifier
            .requiredSize(28.dp)
            .clip(CircleShape)
            .background(bgColor)
            .border(1.dp, borderColor, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = TypeFreeIcons.LogoMark,
            contentDescription = if (isOpen) "收起工具面板" else "展开工具面板",
            tint = iconTint,
            modifier = Modifier.size(15.dp)
        )
    }
}

@Composable
private fun TypeFreeTopBar(
    uiState: ImeUiState,
    settings: AppSettings,
    activeStyle: ToneStyle,
    isAutoMatched: Boolean,
    onToggleToolPanel: () -> Unit,
    onSelectTabMode: (KeyboardTabMode) -> Unit,
    onOpenHelpMeWrite: () -> Unit,
    onToggleFlypyLanguageMode: () -> Unit,
    onToggleStylePicker: () -> Unit,
    onPerformUndo: () -> Unit,
    onPasteClipboardText: (String) -> Unit,
    onToggleManualIncognito: () -> Unit = {}
) {
    val palette = LocalTypeFreeKeyboardPalette.current
    val metrics = LocalKeyboardMetrics.current
    val density = LocalDensity.current
    val swipeThresholdPx = with(density) { 42.dp.toPx() }
    val currentTab by rememberUpdatedState(uiState.tabMode)
    val currentOnSelectTab by rememberUpdatedState(onSelectTabMode)
    val freshClip = if (settings.clipboardEnabled && settings.clipboardQuickPasteSuggestion) {
        uiState.freshClipboardBannerItem
    } else {
        null
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(metrics.topBarHeight)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val start = down.position
                    var last = start
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        last = change.position
                        if (!change.pressed) break
                    }
                    val next = ImeLogic.resolveNextTabByHorizontalSwipe(
                        currentTab = currentTab,
                        dragOffsetX = last.x - start.x,
                        dragOffsetY = last.y - start.y,
                        thresholdPx = swipeThresholdPx
                    )
                    if (next != null) {
                        currentOnSelectTab(next)
                    }
                }
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Left: 28.dp Circular Brand Expand Button + Smart Paste / Scene Badge / Undo / Toast
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            TopBarToolPanelButton(
                isOpen = uiState.isToolPanelOpen,
                onClick = onToggleToolPanel
            )

            if (uiState.isIncognitoMode) {
                Box(
                    modifier = Modifier
                        .height(24.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(TypeFreeEmerald.copy(alpha = 0.16f))
                        .border(1.dp, TypeFreeEmerald.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
                        .clickable(onClick = onToggleManualIncognito)
                        .padding(horizontal = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "🕵️ 无痕",
                        color = if (palette.isDark) Color(0xFF6EE7B7) else Color(0xFF047857),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }
            }

            val learnedTerm = uiState.autoLearnedToastTerm
            val quickToast = uiState.quickActionToast
            if (!learnedTerm.isNullOrBlank()) {
                Box(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .clip(RoundedCornerShape(12.dp))
                        .background(TypeFreeEmerald.copy(alpha = 0.15f))
                        .border(1.dp, TypeFreeEmerald.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = "✓ 已自动收入词典「$learnedTerm」",
                        color = if (palette.isDark) Color(0xFF6EE7B7) else Color(0xFF047857),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            } else if (!quickToast.isNullOrBlank()) {
                Box(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .clip(RoundedCornerShape(12.dp))
                        .background(palette.capsuleSelectedBg)
                        .border(1.dp, palette.keyBorder, RoundedCornerShape(12.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = quickToast,
                        color = palette.keyTextPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            } else if (uiState.shouldShowTopBarSpeakToEditPill) {
                Row(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .height(26.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(palette.primaryMicSolid)
                        .clickable(onClick = onOpenHelpMeWrite)
                        .padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Icon(
                        imageVector = TypeFreeIcons.VoicePen,
                        contentDescription = "AI 改写",
                        tint = palette.onPrimaryMic,
                        modifier = Modifier.size(11.dp)
                    )
                    Text(
                        text = "AI 改写",
                        color = palette.onPrimaryMic,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (uiState.undoSnapshot != null) {
                    TopBarUndoIconButton(onClick = onPerformUndo)
                }
            } else if (freshClip != null) {
                val smart = remember(freshClip.text) {
                    ClipboardSmartExtractor.extractPrimary(freshClip.text)
                }
                val hasDistinctSmartEntity = smart != null && smart.extractedValue != freshClip.text.trim()
                if (hasDistinctSmartEntity && smart != null) {
                    val leftLabel = when (smart.kind) {
                        SmartClipKind.OTP_CODE -> "🔢 ${smart.extractedValue}"
                        SmartClipKind.PICKUP_CODE -> "📦 ${smart.extractedValue}"
                        else -> smart.chipLabel
                    }
                    Row(
                        modifier = Modifier.weight(1f, fill = false),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .height(26.dp)
                                .clip(RoundedCornerShape(13.dp))
                                .background(TypeFreeEmerald.copy(alpha = 0.15f))
                                .border(1.dp, TypeFreeEmerald.copy(alpha = 0.48f), RoundedCornerShape(13.dp))
                                .clickable { onPasteClipboardText(smart.extractedValue) }
                                .padding(horizontal = 7.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = leftLabel,
                                color = if (palette.isDark) Color(0xFF6EE7B7) else Color(0xFF047857),
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Box(
                            modifier = Modifier
                                .height(26.dp)
                                .clip(RoundedCornerShape(13.dp))
                                .background(palette.keyCapBg)
                                .border(1.dp, palette.keyBorder, RoundedCornerShape(13.dp))
                                .clickable { onPasteClipboardText(freshClip.text) }
                                .padding(horizontal = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "📋 全文",
                                color = palette.keyTextPrimary,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1
                            )
                        }
                    }
                } else {
                    val pillLabel = "📋 粘贴「${ClipboardSmartExtractor.formatSingleLinePreview(freshClip.text, 7)}」"
                    Box(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .height(26.dp)
                            .clip(RoundedCornerShape(13.dp))
                            .background(TypeFreeEmerald.copy(alpha = 0.15f))
                            .border(1.dp, TypeFreeEmerald.copy(alpha = 0.48f), RoundedCornerShape(13.dp))
                            .clickable { onPasteClipboardText(freshClip.text) }
                            .padding(horizontal = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = pillLabel,
                            color = if (palette.isDark) Color(0xFF6EE7B7) else Color(0xFF047857),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            } else {
                // Compact Clickable Scene Style Badge (elastic width on 320dp–360dp narrow screens)
                val badgeLabel = ImeLogic.buildCompactStyleBadgeText(activeStyle, isAutoMatched)
                val chevron = if (uiState.isStylePickerExpanded) "▴" else "▾"
                Row(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .height(26.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(
                            if (uiState.isStylePickerExpanded) palette.capsuleSelectedBg else palette.keyCapBg
                        )
                        .border(1.dp, palette.keyBorder, RoundedCornerShape(13.dp))
                        .clickable(onClick = onToggleStylePicker)
                        .padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = badgeLabel,
                        color = palette.keyTextMuted,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Text(
                        text = chevron,
                        color = palette.keyTextMuted,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1
                    )
                }

                // Fixed 26.dp Circular 1-Tap Undo Button (never crushed into `||` on 360dp screens)
                if (uiState.undoSnapshot != null) {
                    TopBarUndoIconButton(onClick = onPerformUndo)
                }
            }
        }

        Spacer(modifier = Modifier.width(4.dp))

        // Right Cluster: Authentic 3-Segment Capsule Track (`VOICE` | `拼/鹤/EN` | `文A`)
        Row(
            modifier = Modifier
                .height(32.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(palette.capsuleTrackBg)
                .border(1.dp, palette.keyBorder, RoundedCornerShape(16.dp))
                .padding(3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            // Segment 1: Voice Dictation (`TypeFreeIcons.VoiceWaveform`)
            ModeSegmentItem(
                selected = uiState.tabMode == KeyboardTabMode.VOICE && !uiState.isToolPanelOpen,
                onClick = { onSelectTabMode(KeyboardTabMode.VOICE) }
            ) { contentColor ->
                Icon(
                    imageVector = TypeFreeIcons.VoiceWaveform,
                    contentDescription = "语音模式",
                    tint = contentColor,
                    modifier = Modifier.size(16.dp)
                )
            }

            // Segment 2: Dynamic Input Scheme (`拼` / `鹤`) / English (`EN`)
            // When already on QWERTY (and tool panel is closed), tapping toggles Chinese <-> EN immediately!
            val middleBadge = if (uiState.isFlypyChineseMode) {
                uiState.activeInputScheme.shortBadge
            } else {
                "EN"
            }
            ModeSegmentItem(
                selected = uiState.tabMode == KeyboardTabMode.QWERTY && !uiState.isToolPanelOpen,
                onClick = {
                    if (uiState.tabMode == KeyboardTabMode.QWERTY && !uiState.isToolPanelOpen) {
                        onToggleFlypyLanguageMode()
                    } else {
                        onSelectTabMode(KeyboardTabMode.QWERTY)
                    }
                }
            ) { contentColor ->
                Text(
                    text = middleBadge,
                    color = contentColor,
                    fontSize = if (uiState.isFlypyChineseMode) 12.sp else 11.5.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // Segment 3: Real-time Translate (`文A`)
            ModeSegmentItem(
                selected = uiState.tabMode == KeyboardTabMode.TRANSLATE && !uiState.isToolPanelOpen,
                onClick = { onSelectTabMode(KeyboardTabMode.TRANSLATE) }
            ) { contentColor ->
                Text(
                    text = "文A",
                    color = contentColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun TopBarUndoIconButton(
    onClick: () -> Unit
) {
    val palette = LocalTypeFreeKeyboardPalette.current
    Box(
        modifier = Modifier
            .requiredSize(26.dp)
            .clip(CircleShape)
            .background(palette.keyCapBg)
            .border(1.dp, palette.keyBorder, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = TypeFreeIcons.Undo,
            contentDescription = "撤销",
            tint = palette.keyTextPrimary,
            modifier = Modifier.size(13.dp)
        )
    }
}

/**
 * 34.dp Prediction & AI Completion Strip reusing the Top Bar slot:
 * `[28.dp Expand Button ⊞] [Scrollable L1/L2 Prediction Chips...] [✦ AI Completion Chip (<=12 chars)] [×]`
 */
@Composable
private fun PredictionCandidateBar(
    isToolPanelOpen: Boolean,
    predictions: List<FlypyCandidate>,
    aiCompletion: String?,
    onToggleToolPanel: () -> Unit,
    onSelectPrediction: (Int) -> Unit,
    onSelectAiCompletion: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val palette = LocalTypeFreeKeyboardPalette.current
    val metrics = LocalKeyboardMetrics.current
    val scrollState = rememberScrollState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(metrics.topBarHeight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        // 1. Stationary 28.dp Expand Button (identical position to TypeFreeTopBar)
        TopBarToolPanelButton(
            isOpen = isToolPanelOpen,
            onClick = onToggleToolPanel
        )

        // 2. Scrollable L1/L2 Prediction Chips
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .horizontalScroll(scrollState),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            predictions.forEachIndexed { idx, cand ->
                val visualText = if (cand.text.length > 16 || cand.text.contains('\n')) {
                    cand.text.replace('\n', '↵').take(16) + "…"
                } else {
                    cand.text
                }
                Box(
                    modifier = Modifier
                        .height(28.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(palette.keyCapBg)
                        .border(1.dp, palette.keyBorder, RoundedCornerShape(8.dp))
                        .clickable { onSelectPrediction(idx) }
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = visualText,
                        color = palette.keyTextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1
                    )
                }
            }
        }

        // 3. Pinned Right-Side L3 AI Completion Chip (TypeFreeEmerald border + `✦` prefix + max 12 chars)
        val cleanAiText = aiCompletion?.trim().orEmpty()
        if (cleanAiText.isNotEmpty()) {
            val singleLineAi = cleanAiText.replace('\n', '↵')
            val truncatedAi = if (singleLineAi.length > 12) {
                singleLineAi.take(12) + "…"
            } else {
                singleLineAi
            }
            Box(
                modifier = Modifier
                    .height(28.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(TypeFreeEmerald.copy(alpha = if (palette.isDark) 0.16f else 0.12f))
                    .border(1.dp, TypeFreeEmerald.copy(alpha = 0.72f), RoundedCornerShape(8.dp))
                    .clickable { onSelectAiCompletion(cleanAiText) }
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "✦ $truncatedAi",
                    color = if (palette.isDark) Color(0xFF6EE7B7) else Color(0xFF047857),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
            }
        }

        // 4. Dismiss Predictions (`×`)
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(palette.keyCapSecondaryBg)
                .border(1.dp, palette.keyBorder, RoundedCornerShape(8.dp))
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "×",
                color = palette.keyTextMuted,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/**
 * Zero-jitter 34.dp unified Candidate Strip ([CandidateStrip], generalized from [FlypyComposingCandidateBar])
 * occupying the exact height of [TypeFreeTopBar] while composing so the 26 QWERTY keys stay 100% stationary:
 * - In [InputScheme.FLYPY_TABLE], displays the monospace 4-code (`uijm` / `ji`` / `;`).
 * - In Rime Pinyin / Double-Pinyin / Custom schemes, displays the segmented `preeditDisplay`
 *   (e.g. `ni'hao'shi'jie`), left-truncated (`"…" + preedit.takeLast(18)`) when long so the latest
 *   typed Pinyin syllables remain visible.
 */
@Composable
fun CandidateStrip(
    composingCode: String,
    preeditDisplay: String = composingCode,
    inputScheme: InputScheme = InputScheme.FLYPY_TABLE,
    candidates: List<FlypyCandidate>,
    hasMoreCandidates: Boolean = false,
    isSheetExpanded: Boolean,
    onSelectCandidate: (Int) -> Unit,
    onToggleSheet: () -> Unit,
    onClearCode: () -> Unit,
    onRequestMoreCandidates: () -> Unit = {}
) {
    val palette = LocalTypeFreeKeyboardPalette.current
    val metrics = LocalKeyboardMetrics.current
    val scrollState = rememberScrollState()
    val displayPreedit = remember(preeditDisplay, composingCode, inputScheme) {
        ImeLogic.formatPreeditDisplay(
            preedit = preeditDisplay,
            rawCode = composingCode,
            scheme = inputScheme,
            maxVisibleChars = 18
        )
    }

    LaunchedEffect(composingCode) {
        scrollState.scrollTo(0)
    }

    LaunchedEffect(scrollState.value, scrollState.maxValue, hasMoreCandidates) {
        if (hasMoreCandidates && scrollState.maxValue > 0 && scrollState.value >= scrollState.maxValue - 48) {
            onRequestMoreCandidates()
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(metrics.topBarHeight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // 1. Active Composing Code / Segmented Preedit Pill (`uijm` / `ni'hao'shi'jie`)
        Box(
            modifier = Modifier
                .height(26.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(palette.primaryMicSolid)
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = displayPreedit,
                color = palette.onPrimaryMic,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = if (inputScheme.usesRime) FontFamily.Default else FontFamily.Monospace,
                maxLines = 1
            )
        }

        // 2. Scrollable Candidate List or Wildcard / Empty-Code Guide
        if (candidates.isEmpty()) {
            val emptyHint = when {
                composingCode.all { it == '`' } -> "反查：输双拼查形(如 ji`) 或 输双形查音(如 `bo)"
                inputScheme.usesRime -> "无匹配候选 · 按回车上屏编码或空格清屏"
                else -> "空码 · 第五码或空格自动清屏"
            }
            Text(
                text = emptyHint,
                color = if (composingCode.all { it == '`' }) palette.keyTextMuted else TypeFreeRose,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        } else {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .horizontalScroll(scrollState),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                candidates.take(40).forEachIndexed { idx, cand ->
                    val isFirst = idx == 0
                    Row(
                        modifier = Modifier
                            .height(28.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (isFirst) palette.capsuleSelectedBg else palette.keyCapBg
                            )
                            .border(
                                width = 1.dp,
                                color = if (isFirst) {
                                    palette.keyTextPrimary.copy(alpha = 0.28f)
                                } else {
                                    palette.keyBorder
                                },
                                shape = RoundedCornerShape(8.dp)
                            )
                            .clickable { onSelectCandidate(idx) }
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        if (idx < 3) {
                            Text(
                                text = "${idx + 1}",
                                color = palette.keyTextMuted,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        val visualCandText = if (cand.text.length > 16 || cand.text.contains('\n')) {
                            cand.text.replace('\n', '↵').take(16) + "…"
                        } else {
                            cand.text
                        }
                        Text(
                            text = visualCandText,
                            color = palette.keyTextPrimary,
                            fontSize = 14.sp,
                            fontWeight = if (isFirst) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1
                        )
                        if (cand.codeHint.isNotEmpty()) {
                            Text(
                                text = "〔${cand.codeHint}〕",
                                color = palette.keyTextMuted,
                                fontSize = 10.5.sp,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }

        // 3. Expand All Candidates Grid Toggle (when > 4 candidates or Rime has more pages)
        if (candidates.size > 4 || hasMoreCandidates) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isSheetExpanded) palette.capsuleSelectedBg else palette.keyCapSecondaryBg)
                    .border(1.dp, palette.keyBorder, RoundedCornerShape(8.dp))
                    .clickable(onClick = onToggleSheet),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (isSheetExpanded) "▴" else "▾",
                    color = palette.keyTextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // 4. 1-Tap Clear Code (`×`)
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(palette.keyCapSecondaryBg)
                .border(1.dp, palette.keyBorder, RoundedCornerShape(8.dp))
                .clickable(onClick = onClearCode),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "×",
                color = palette.keyTextMuted,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun FlypyComposingCandidateBar(
    composingCode: String,
    candidates: List<FlypyCandidate>,
    isSheetExpanded: Boolean,
    onSelectCandidate: (Int) -> Unit,
    onToggleSheet: () -> Unit,
    onClearCode: () -> Unit
) {
    CandidateStrip(
        composingCode = composingCode,
        preeditDisplay = composingCode,
        inputScheme = InputScheme.FLYPY_TABLE,
        candidates = candidates,
        isSheetExpanded = isSheetExpanded,
        onSelectCandidate = onSelectCandidate,
        onToggleSheet = onToggleSheet,
        onClearCode = onClearCode
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CandidateGridOverlay(
    candidates: List<FlypyCandidate>,
    hasMoreCandidates: Boolean = false,
    onSelectCandidate: (Int) -> Unit,
    onBackspace: () -> Unit,
    onCollapse: () -> Unit,
    onRequestMoreCandidates: () -> Unit = {}
) {
    val palette = LocalTypeFreeKeyboardPalette.current
    val vScroll = rememberScrollState()

    LaunchedEffect(hasMoreCandidates, vScroll.value, vScroll.maxValue) {
        if (hasMoreCandidates && (vScroll.maxValue == 0 || vScroll.value >= vScroll.maxValue - 80)) {
            onRequestMoreCandidates()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(10.dp))
            .background(palette.keyboardCanvas)
            .border(1.dp, palette.keyBorder, RoundedCornerShape(10.dp))
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(vScroll)
        ) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                candidates.forEachIndexed { idx, cand ->
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (idx == 0) palette.capsuleSelectedBg else palette.keyCapBg)
                            .border(1.dp, palette.keyBorder, RoundedCornerShape(8.dp))
                            .clickable { onSelectCandidate(idx) }
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        val gridCandText = if (cand.text.length > 22 || cand.text.contains('\n')) {
                            cand.text.replace('\n', '↵').take(22) + "…"
                        } else {
                            cand.text
                        }
                        Text(
                            text = gridCandText,
                            color = palette.keyTextPrimary,
                            fontSize = 15.sp,
                            fontWeight = if (idx == 0) FontWeight.Bold else FontWeight.Medium
                        )
                        if (cand.codeHint.isNotEmpty()) {
                            Text(
                                text = "〔${cand.codeHint}〕",
                                color = palette.keyTextMuted,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(8.dp))
                    .background(palette.keyCapSecondaryBg)
                    .border(1.dp, palette.keyBorder, RoundedCornerShape(8.dp))
                    .clickable(onClick = onCollapse),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "▴ 返回键盘",
                    color = palette.keyTextPrimary,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Box(
                modifier = Modifier
                    .width(72.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(8.dp))
                    .background(palette.keyCapSecondaryBg)
                    .border(1.dp, palette.keyBorder, RoundedCornerShape(8.dp))
                    .clickable(onClick = onBackspace),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = TypeFreeIcons.Backspace,
                    contentDescription = "退格",
                    tint = palette.keyTextPrimary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
fun FlypyExpandedCandidateGridOverlay(
    candidates: List<FlypyCandidate>,
    onSelectCandidate: (Int) -> Unit,
    onBackspace: () -> Unit,
    onCollapse: () -> Unit
) {
    CandidateGridOverlay(
        candidates = candidates,
        onSelectCandidate = onSelectCandidate,
        onBackspace = onBackspace,
        onCollapse = onCollapse
    )
}

@Composable
private fun ModeSegmentItem(
    selected: Boolean,
    onClick: () -> Unit,
    content: @Composable (Color) -> Unit
) {
    val palette = LocalTypeFreeKeyboardPalette.current
    val contentColor = if (selected) palette.keyTextPrimary else palette.keyTextMuted

    Box(
        modifier = Modifier
            .width(33.dp)
            .fillMaxHeight()
            .clip(RoundedCornerShape(13.dp))
            .background(if (selected) palette.capsuleSelectedBg else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        content(contentColor)
    }
}

@Composable
private fun InlineStyleSelectorStrip(
    settings: AppSettings,
    onSelectAutoStyle: () -> Unit,
    onSelectManualStyle: (ToneStyle) -> Unit,
    onOpenStyleRules: () -> Unit
) {
    val palette = LocalTypeFreeKeyboardPalette.current
    val isAutoSelected = settings.selectedManualStyleId == "auto"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StyleChoicePill(
            text = "✨ 自动场景感知",
            selected = isAutoSelected,
            onClick = onSelectAutoStyle
        )

        settings.styles.forEach { style ->
            val selected = !isAutoSelected && settings.selectedManualStyleId == style.id
            StyleChoicePill(
                text = "${style.emoji} ${style.name}",
                selected = selected,
                onClick = { onSelectManualStyle(style) }
            )
        }

        Box(
            modifier = Modifier
                .fillMaxHeight()
                .clip(RoundedCornerShape(14.dp))
                .background(palette.keyCapBg)
                .border(1.dp, palette.keyBorder, RoundedCornerShape(14.dp))
                .clickable(onClick = onOpenStyleRules)
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "管理风格 ›",
                color = palette.keyTextMuted,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun StyleChoicePill(
    text: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val palette = LocalTypeFreeKeyboardPalette.current
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) palette.primaryMicSolid else palette.keyCapBg)
            .border(
                width = 1.dp,
                color = if (selected) Color.Transparent else palette.keyBorder,
                shape = RoundedCornerShape(14.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (selected) palette.onPrimaryMic else palette.keyTextPrimary,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
        )
    }
}

// =============================================================================
// 2. Voice Main Panel (`ref_typefree_kb_idle.png` Geometry + Gestures)
// =============================================================================

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VoiceMainPanel(
    uiState: ImeUiState,
    settings: AppSettings,
    amplitudes: List<Float>,
    elapsedMs: Long,
    didPauseMedia: Boolean,
    onToggleWhisperMode: () -> Unit,
    onSwipeToTab: (KeyboardTabMode) -> Unit,
    onVoicePenClick: () -> Unit,
    onMicPressDown: () -> Unit,
    onMicPressUp: () -> Unit,
    onEnterArcTranslate: (Boolean) -> Unit,
    onUpdateArcTranslate: (String, Boolean) -> Unit,
    onReleaseArcTranslate: () -> Unit,
    onFinishHandsFree: () -> Unit,
    onCancelRecording: () -> Unit,
    onTogglePunctuationStrip: () -> Unit,
    onCommitText: (String) -> Unit,
    onInsertPairedSymbol: (String, String) -> Unit,
    onBackspace: () -> Unit,
    onEnterOrNewline: () -> Unit,
    onHideKeyboard: () -> Unit,
    onSwitchKeyboard: () -> Unit,
    onShowImePicker: () -> Unit
) {
    val palette = LocalTypeFreeKeyboardPalette.current
    val metrics = LocalKeyboardMetrics.current
    val s = metrics.elementScale
    val density = LocalDensity.current
    val phase = uiState.voicePhase
    val isRecording = phase is VoicePhase.Recording
    val isProcessing = phase is VoicePhase.Processing

    val swipeThresholdPx = with(density) { 52.dp.toPx() }
    val arcTriggerPx = with(density) { ImeLogic.ARC_TRANSLATE_TRIGGER_DP.dp.toPx() }
    val arcStepPx = with(density) { ImeLogic.ARC_WHEEL_STEP_DP.dp.toPx() }
    val arcCancelPx = with(density) { ImeLogic.ARC_CANCEL_THRESHOLD_DP.dp.toPx() }

    val currentPhaseState by rememberUpdatedState(phase)
    val currentSettingsState by rememberUpdatedState(settings)
    val currentOnSwipeToTab by rememberUpdatedState(onSwipeToTab)
    val currentOnMicDown by rememberUpdatedState(onMicPressDown)
    val currentOnMicUp by rememberUpdatedState(onMicPressUp)
    val currentOnEnterArc by rememberUpdatedState(onEnterArcTranslate)
    val currentOnUpdateArc by rememberUpdatedState(onUpdateArcTranslate)
    val currentOnReleaseArc by rememberUpdatedState(onReleaseArcTranslate)
    val currentOnFinishHandsFree by rememberUpdatedState(onFinishHandsFree)

    Box(
        modifier = Modifier
            .fillMaxSize()
            // Horizontal swipe anywhere on the Voice canvas switches VOICE <-> QWERTY
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = true)
                    val start = down.position
                    var last = start
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        last = change.position
                        if (!change.pressed) break
                    }
                    if (currentPhaseState !is VoicePhase.Recording &&
                        currentPhaseState !is VoicePhase.Processing
                    ) {
                        val next = ImeLogic.resolveNextTabByHorizontalSwipe(
                            currentTab = KeyboardTabMode.VOICE,
                            dragOffsetX = last.x - start.x,
                            dragOffsetY = last.y - start.y,
                            thresholdPx = swipeThresholdPx
                        )
                        if (next != null) {
                            currentOnSwipeToTab(next)
                        }
                    }
                }
            }
    ) {
        // 1. Top Subtitle (`Tap to speak · 轻点说话 / 上滑快译` or Toast / Timer / LiveWrite status)
        val activeTranslateLabel = uiState.arcSelectedLanguage.ifBlank { settings.activeTranslateTarget }
        val isTranslateRecording = phase is VoicePhase.Recording &&
            uiState.activeIntentMode == VoiceIntentMode.TRANSLATE &&
            activeTranslateLabel.isNotBlank()
        val topSubtitle = when (phase) {
            is VoicePhase.SuccessToast -> {
                ImeLogic.formatSuccessToastSubtitle(phase.latencyMs)
            }
            is VoicePhase.Recording -> {
                val timerText = ImeLogic.formatElapsedTimer(elapsedMs)
                val livePreview = uiState.streamingPartialPreview.trim()
                val recBadges = buildString {
                    if (didPauseMedia) append("🎵外放已静音 · ")
                    if (settings.whisperModeEnabled) append("🤫轻声 · ")
                }
                when {
                    uiState.liveWriteStatus == LiveWriteUiStatus.FROZEN ->
                        "已暂停写入 · 松手后在光标处上屏"
                    uiState.liveWriteStatus == LiveWriteUiStatus.WRITING ->
                        "$timerText · 正在写入输入框"
                    isTranslateRecording && livePreview.isNotEmpty() ->
                        "🌐 $activeTranslateLabel · ${livePreview.takeLast(18)}"
                    isTranslateRecording ->
                        "🌐 ${recBadges}正在聆听 → $activeTranslateLabel · $timerText"
                    livePreview.isNotEmpty() ->
                        "$recBadges$timerText · ${livePreview.takeLast(20)}"
                    else ->
                        "$recBadges$timerText"
                }
            }
            is VoicePhase.Processing -> phase.stageText
            else -> if (settings.whisperModeEnabled) {
                "🤫 轻声耳语高敏模式 · 靠近麦克风轻声说"
            } else {
                "Tap to speak · 轻点说话 / 上滑快译"
            }
        }

        val isTopOverlayExpanded = uiState.isStylePickerExpanded || phase is VoicePhase.Error
        if (!isTopOverlayExpanded) {
            Text(
                text = topSubtitle,
                color = when {
                    phase is VoicePhase.SuccessToast -> TypeFreeEmerald
                    isTranslateRecording -> TypeFreeEmerald
                    phase is VoicePhase.Recording && uiState.liveWriteStatus == LiveWriteUiStatus.WRITING -> TypeFreeEmerald
                    else -> palette.keyTextMuted
                },
                fontSize = 12.5.sp,
                fontWeight = if (phase is VoicePhase.Recording || phase is VoicePhase.SuccessToast) {
                    FontWeight.SemiBold
                } else {
                    FontWeight.Normal
                },
                fontFamily = if (phase is VoicePhase.Recording) FontFamily.Monospace else FontFamily.Default,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = (4f * s).dp)
            )
        }

        // 2. Stationary Gesture Anchor for Center Primary Mic Capsule <-> 84.dp Recording Circle
        val micButtonWidth by animateDpAsState(
            targetValue = ((if (isRecording || isProcessing) 84f else 142f) * s).dp,
            animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
            label = "micWidth"
        )
        val micButtonHeight by animateDpAsState(
            targetValue = ((if (isRecording || isProcessing) 84f else 56f) * s).dp,
            animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
            label = "micHeight"
        )
        val micCornerRadius by animateDpAsState(
            targetValue = ((if (isRecording || isProcessing) 42f else 28f) * s).dp,
            animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
            label = "micCorner"
        )

        val infiniteTransition = rememberInfiniteTransition(label = "haloPulse")
        val haloScale by infiniteTransition.animateFloat(
            initialValue = 1.0f,
            targetValue = 1.18f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 920, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "haloScale"
        )

        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = (24f * s).dp)
                .size(width = (160f * s).dp, height = (104f * s).dp)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()

                        val initialPhase = currentPhaseState
                        if (initialPhase is VoicePhase.Processing) {
                            return@awaitEachGesture
                        }

                        val startedFromHandsFree =
                            initialPhase is VoicePhase.Recording && initialPhase.isHandsFree
                        if (!startedFromHandsFree) {
                            currentOnMicDown()
                        }

                        val startPos = down.position
                        var inArcMode = false
                        val targets = currentSettingsState.translationTargets.ifEmpty {
                            DefaultPresets.defaultTranslationTargets()
                        }
                        val initialTargetIdx = targets.indexOf(currentSettingsState.activeTranslateTarget)
                            .let { if (it >= 0) it else 0 }

                        while (true) {
                            val event = awaitPointerEvent()
                            val pointerChange = event.changes.firstOrNull { it.id == down.id }
                                ?: event.changes.firstOrNull()
                                ?: break

                            val dx = pointerChange.position.x - startPos.x
                            val dy = pointerChange.position.y - startPos.y

                            if (!inArcMode && ImeLogic.shouldEnterArcTranslate(dy, arcTriggerPx)) {
                                inArcMode = true
                                currentOnEnterArc(startedFromHandsFree)
                            }

                            if (inArcMode) {
                                val selectedIdx = ImeLogic.computeArcWheelIndex(
                                    initialIndex = initialTargetIdx,
                                    targetCount = targets.size,
                                    dragOffsetX = dx,
                                    stepPx = arcStepPx
                                )
                                val cancelHovered = ImeLogic.isInArcCancelZone(dy, arcCancelPx)
                                currentOnUpdateArc(targets[selectedIdx], cancelHovered)
                            }

                            if (event.changes.all { !it.pressed }) {
                                event.changes.forEach { it.consume() }
                                break
                            }
                        }

                        when {
                            inArcMode -> currentOnReleaseArc()
                            startedFromHandsFree -> currentOnFinishHandsFree()
                            else -> currentOnMicUp()
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            // Outer breathing halo when recording
            if (isRecording) {
                val latestAmp = amplitudes.lastOrNull()?.coerceIn(0f, 1f) ?: 0.12f
                Box(
                    modifier = Modifier
                        .size((88f * s).dp)
                        .scale(haloScale + latestAmp * 0.14f)
                        .clip(CircleShape)
                        .background(palette.pulseHaloColor.copy(alpha = 0.65f))
                )
            }

            // Morphing Primary Pill / 84.dp Charcoal Circle
            Box(
                modifier = Modifier
                    .size(width = micButtonWidth, height = micButtonHeight)
                    .clip(RoundedCornerShape(micCornerRadius))
                    .background(palette.primaryMicBrush),
                contentAlignment = Alignment.Center
            ) {
                when {
                    isRecording -> {
                        SymmetricalSevenBarWaveform(
                            amplitudes = amplitudes,
                            color = palette.onPrimaryMic,
                            modifier = Modifier.size(width = (44f * s).dp, height = (32f * s).dp)
                        )
                    }
                    isProcessing -> {
                        CircularProgressIndicator(
                            color = palette.onPrimaryMic,
                            strokeWidth = 2.4.dp,
                            modifier = Modifier.size((26f * s).dp)
                        )
                    }
                    else -> {
                        Icon(
                            imageVector = TypeFreeIcons.Mic,
                            contentDescription = "轻点说话",
                            tint = palette.onPrimaryMic,
                            modifier = Modifier.size((26f * s).dp)
                        )
                    }
                }
            }
        }

        // 3. Recording / Processing Bottom Status & Cancel Action OR Idle TypeFree Key Cluster
        if (isRecording || isProcessing) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = (40f * s).dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (phase is VoicePhase.Recording && phase.isHandsFree) {
                    val finishLabel = if (uiState.activeIntentMode == VoiceIntentMode.TRANSLATE) {
                        "✓ 说完点此直译"
                    } else {
                        "✓ 说完轻点完成"
                    }
                    Box(
                        modifier = Modifier
                            .height((34f * s).dp)
                            .clip(RoundedCornerShape((17f * s).dp))
                            .background(palette.primaryMicSolid)
                            .clickable(onClick = onFinishHandsFree)
                            .padding(horizontal = 14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = finishLabel,
                            color = palette.onPrimaryMic,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                } else {
                    Text(
                        text = when {
                            phase is VoicePhase.Recording ->
                                "松手完成 · 上滑选语言快译"
                            else -> "正在处理语音..."
                        },
                        color = palette.keyTextMuted,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                CircleFloatingKey(
                    size = (34f * s).dp,
                    onClick = onCancelRecording
                ) {
                    Icon(
                        imageVector = TypeFreeIcons.Close,
                        contentDescription = "取消录音",
                        tint = palette.keyTextPrimary,
                        modifier = Modifier.size((16f * s).dp)
                    )
                }
            }
        } else {
            // Top-Right Compact Whisper AGC Toggle Pill (`🤫 轻声`) — hidden when style/error top overlay is expanded
            if (!isTopOverlayExpanded) {
                val whisperOn = settings.whisperModeEnabled
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = (28f * s).dp)
                        .height((26f * s).dp)
                        .clip(RoundedCornerShape((13f * s).dp))
                        .background(
                            if (whisperOn) TypeFreeEmerald.copy(alpha = 0.16f) else palette.keyCapBg
                        )
                        .border(
                            width = 1.dp,
                            color = if (whisperOn) TypeFreeEmerald.copy(alpha = 0.55f) else palette.keyBorder,
                            shape = RoundedCornerShape((13f * s).dp)
                        )
                        .clickable(onClick = onToggleWhisperMode)
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (whisperOn) "🤫 轻声开" else "🤫 轻声",
                        color = if (whisperOn) {
                            if (palette.isDark) Color(0xFF6EE7B7) else Color(0xFF047857)
                        } else {
                            palette.keyTextMuted
                        },
                        fontSize = 11.sp,
                        fontWeight = if (whisperOn) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1
                    )
                }
            }

            // Optional Quick Punctuation Floating Strip (toggled by `@` circle key)
            if (uiState.isPunctuationStripOpen) {
                FloatingQuickPunctuationStrip(
                    onCommitText = onCommitText,
                    onInsertPairedSymbol = onInsertPairedSymbol,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(bottom = (86f * s).dp, end = (52f * s).dp)
                )
            }

            // Bottom-Left Circular White Key (`46.dp`, `TypeFreeIcons.VoicePen`)
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(bottom = (33f * s).dp)
            ) {
                CircleFloatingKey(
                    size = (46f * s).dp,
                    highlighted = uiState.hasIgnoredSelection,
                    onClick = onVoicePenClick
                ) {
                    Icon(
                        imageVector = TypeFreeIcons.VoicePen,
                        contentDescription = "帮我写 / 语音编辑",
                        tint = if (uiState.hasIgnoredSelection) palette.onPrimaryMic else palette.keyTextPrimary,
                        modifier = Modifier.size((20f * s).dp)
                    )
                }
            }

            // Bottom-Center Stadium White Key (`106.dp × 44.dp`, adaptive Enter/Action label)
            val enterInfo = uiState.enterActionInfo
            val enterInteraction = remember { MutableInteractionSource() }
            val enterPressed by enterInteraction.collectIsPressedAsState()
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = (34f * s).dp)
                    .size(width = (106f * s).dp, height = (44f * s).dp)
                    .scale(if (enterPressed) 0.95f else 1f)
                    .clip(RoundedCornerShape((22f * s).dp))
                    .background(palette.keyCapBg)
                    .border(1.dp, palette.keyBorder, RoundedCornerShape((22f * s).dp))
                    .clickable(
                        interactionSource = enterInteraction,
                        indication = null,
                        onClick = onEnterOrNewline
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = enterInfo.label,
                    color = palette.keyTextMuted,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1
                )
            }

            // Right Vertically Stacked Dual Circular White Keys (`44.dp` Backspace + `44.dp` `@`)
            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = (34f * s).dp),
                verticalArrangement = Arrangement.spacedBy((8f * s).dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Upper Circle: Repeating Backspace (`44.dp`)
                RepeatingCircleBackspaceKey(
                    size = (44f * s).dp,
                    onBackspace = onBackspace
                )

                // Lower Circle: `@` Quick Punctuation Toggle (`44.dp`)
                CircleFloatingKey(
                    size = (44f * s).dp,
                    highlighted = uiState.isPunctuationStripOpen,
                    onClick = onTogglePunctuationStrip
                ) {
                    Text(
                        text = "@",
                        color = if (uiState.isPunctuationStripOpen) {
                            palette.onPrimaryMic
                        } else {
                            palette.keyTextPrimary
                        },
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        // 4. Bottom Corner Utility Row (`ChevronDown` left, `KeyboardGrid` right)
        BottomCornerUtilityIcons(
            onHideKeyboard = onHideKeyboard,
            onSwitchKeyboard = onSwitchKeyboard,
            onShowImePicker = onShowImePicker,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

// =============================================================================
// 3. Hold & Swipe-Up Arc Translate Wheel (`Release to translate`, `ref_typefree_translate_arc.png`)
// =============================================================================

@Composable
private fun ArcTranslateFullPanel(
    targets: List<String>,
    selectedLanguage: String,
    startedFromHandsFree: Boolean,
    isCancelHovered: Boolean,
    amplitudes: List<Float>,
    onHideKeyboard: () -> Unit,
    onSwitchKeyboard: () -> Unit
) {
    val palette = LocalTypeFreeKeyboardPalette.current
    val s = LocalKeyboardMetrics.current.elementScale
    val selectedIdx = targets.indexOf(selectedLanguage).let { if (it >= 0) it else 0 }
    val slots = ImeLogic.resolveArcWheelSlots(targets, selectedIdx)

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = (4f * s).dp)
            ) {
                SymmetricalSevenBarWaveform(
                    amplitudes = amplitudes,
                    color = palette.keyTextPrimary,
                    modifier = Modifier.size(width = (22f * s).dp, height = (16f * s).dp)
                )
                Text(
                    text = if (startedFromHandsFree) {
                        "Release to translate · 松手立即直译"
                    } else {
                        "松手选定此语言 · 随后开口说话"
                    },
                    color = palette.keyTextMuted,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            // Center 3-Capsule Curved Language Wheel (`-10°` | `0°` | `+10°`)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = (6f * s).dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Left Tilted Capsule (-10°)
                ArcSideLanguageCapsule(
                    language = slots.leftLanguage,
                    rotationDeg = -10f,
                    modifier = Modifier.weight(0.92f)
                )

                // Center Enlarged Highlighted Target Capsule (0°)
                val centerScale = if (isCancelHovered) 0.95f else 1.04f
                Box(
                    modifier = Modifier
                        .weight(1.24f)
                        .height((58f * s).dp)
                        .graphicsLayer {
                            scaleX = centerScale
                            scaleY = centerScale
                        }
                        .clip(RoundedCornerShape((29f * s).dp))
                        .background(
                            if (isCancelHovered) palette.keyCapSecondaryBg else palette.primaryMicSolid
                        )
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = slots.centerLanguage,
                        color = if (isCancelHovered) palette.keyTextMuted else palette.onPrimaryMic,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Right Tilted Capsule (+10°)
                ArcSideLanguageCapsule(
                    language = slots.rightLanguage,
                    rotationDeg = 10f,
                    modifier = Modifier.weight(0.92f)
                )
            }

            // Bottom Curved Cancel Zone (`Cancel / 取消`)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height((82f * s).dp)
                    .padding(bottom = (26f * s).dp)
                    .clip(
                        RoundedCornerShape(
                            topStart = (44f * s).dp,
                            topEnd = (44f * s).dp,
                            bottomStart = (18f * s).dp,
                            bottomEnd = (18f * s).dp
                        )
                    )
                    .background(
                        if (isCancelHovered) {
                            TypeFreeRose.copy(alpha = 0.16f)
                        } else {
                            palette.keyCapSecondaryBg.copy(alpha = 0.65f)
                        }
                    )
                    .border(
                        width = 1.dp,
                        color = if (isCancelHovered) {
                            TypeFreeRose.copy(alpha = 0.55f)
                        } else {
                            palette.keyBorder
                        },
                        shape = RoundedCornerShape(
                            topStart = (44f * s).dp,
                            topEnd = (44f * s).dp,
                            bottomStart = (18f * s).dp,
                            bottomEnd = (18f * s).dp
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (isCancelHovered) "松手取消翻译 · Cancel" else "取消 · Cancel",
                    color = if (isCancelHovered) TypeFreeRose else palette.keyTextMuted,
                    fontSize = 13.5.sp,
                    fontWeight = if (isCancelHovered) FontWeight.SemiBold else FontWeight.Medium
                )
            }
        }

        BottomCornerUtilityIcons(
            onHideKeyboard = onHideKeyboard,
            onSwitchKeyboard = onSwitchKeyboard,
            onShowImePicker = onSwitchKeyboard,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

@Composable
private fun ArcSideLanguageCapsule(
    language: String?,
    rotationDeg: Float,
    modifier: Modifier = Modifier
) {
    val palette = LocalTypeFreeKeyboardPalette.current
    val s = LocalKeyboardMetrics.current.elementScale
    if (language.isNullOrBlank()) {
        Spacer(modifier = modifier.height((50f * s).dp))
        return
    }
    Box(
        modifier = modifier
            .height((50f * s).dp)
            .graphicsLayer {
                rotationZ = rotationDeg
                translationY = (10f * s).dp.toPx()
            }
            .clip(RoundedCornerShape((25f * s).dp))
            .background(palette.keyCapSecondaryBg.copy(alpha = 0.85f))
            .border(1.dp, palette.keyBorder, RoundedCornerShape((25f * s).dp))
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = language,
            color = palette.keyTextPrimary.copy(alpha = 0.82f),
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            lineHeight = 15.sp,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// =============================================================================
// 4. Inline `Help me write · 帮我写` Panel (`ref_typefree_help_write.png`)
// =============================================================================

@Composable
private fun HelpMeWritePanel(
    uiState: ImeUiState,
    settings: AppSettings,
    amplitudes: List<Float>,
    elapsedMs: Long,
    onClose: () -> Unit,
    onAcceptChatContextDisclosure: () -> Unit,
    onDeclineChatContextDisclosure: () -> Unit,
    onToggleIgnoreChatContext: () -> Unit,
    onSwitchToQwerty: () -> Unit,
    onOpenClipboard: () -> Unit,
    onPresetClick: (HelpWritePromptPreset) -> Unit,
    onDirectGenerate: () -> Unit = {},
    onStartRecording: () -> Unit,
    onReleaseRecording: () -> Unit,
    onFinishHandsFree: () -> Unit,
    onCancelRecording: () -> Unit,
    onOpenHostApp: (Int) -> Unit,
    onRetryLastAudio: () -> Unit,
    onDismissError: () -> Unit,
    onHideKeyboard: () -> Unit,
    onSwitchKeyboard: () -> Unit,
    onShowImePicker: () -> Unit
) {
    val palette = LocalTypeFreeKeyboardPalette.current
    val s = LocalKeyboardMetrics.current.elementScale
    val phase = uiState.voicePhase
    val isBusy = phase is VoicePhase.Recording || phase is VoicePhase.Processing
    val hasCapturedContext = uiState.capturedChatContext.hasContext
    val isContextActive = uiState.hasActiveChatContext
    val selectedPreset = uiState.selectedHelpWritePreset
    val attitudePresets = remember(settings.replyAttitudePresets) {
        ImeLogic.resolveReplyAttitudePresets(settings)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = (28f * s).dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Header: Top-Left `Help me write · 帮我写` or `AI 帮回 + 已截屏识图/已读 N 条` + Top-Right Bridge
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(34.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Icon(
                        imageVector = TypeFreeIcons.VoicePen,
                        contentDescription = null,
                        tint = if (isContextActive) TypeFreeEmerald else palette.keyTextPrimary,
                        modifier = Modifier.size(if (hasCapturedContext) 16.dp else 18.dp)
                    )
                    if (hasCapturedContext) {
                        Text(
                            text = "AI 帮回",
                            color = palette.keyTextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                        val msgCount = uiState.capturedChatContext.messageCount
                        val hasScreen = uiState.capturedChatContext.hasScreenshot
                        val badgeText = when {
                            uiState.ignoreChatContextForThisTurn -> "已忽略 · 点此恢复"
                            hasScreen && msgCount > 0 -> "📸 已识图+${msgCount}条 · 点此忽略"
                            hasScreen -> "📸 已截屏识图 · 点此忽略"
                            else -> "已读 $msgCount 条 · 点此忽略"
                        }
                        Box(
                            modifier = Modifier
                                .height(22.dp)
                                .clip(RoundedCornerShape(11.dp))
                                .background(
                                    if (!uiState.ignoreChatContextForThisTurn) {
                                        TypeFreeEmerald.copy(alpha = 0.14f)
                                    } else {
                                        palette.keyCapBg
                                    }
                                )
                                .border(
                                    width = 1.dp,
                                    color = if (!uiState.ignoreChatContextForThisTurn) {
                                        TypeFreeEmerald.copy(alpha = 0.48f)
                                    } else {
                                        palette.keyBorder
                                    },
                                    shape = RoundedCornerShape(11.dp)
                                )
                                .clickable(enabled = !isBusy, onClick = onToggleIgnoreChatContext)
                                .padding(horizontal = 7.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = badgeText,
                                color = if (!uiState.ignoreChatContextForThisTurn) {
                                    if (palette.isDark) Color(0xFF6EE7B7) else Color(0xFF047857)
                                } else {
                                    palette.keyTextMuted
                                },
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    } else {
                        Text(
                            text = "Help me write · 帮我写",
                            color = palette.keyTextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                SubPanelHeaderBridgeBar(
                    isFlypyChineseMode = uiState.isFlypyChineseMode,
                    inputSchemeShortBadge = uiState.activeInputScheme.shortBadge,
                    hasFreshClipboard = uiState.freshClipboardBannerItem != null,
                    onSwitchToQwerty = onSwitchToQwerty,
                    onOpenClipboard = onOpenClipboard,
                    closeDescription = "关闭帮我写",
                    onClose = onClose
                )
            }

            if (phase is VoicePhase.Error) {
                ErrorContextBanner(
                    error = phase,
                    onRetry = onRetryLastAudio,
                    onOpenApiSetup = { onOpenHostApp(AppDestinations.TAB_API) },
                    onOpenPermissionSetup = { onOpenHostApp(AppDestinations.TAB_HOME) },
                    onDismiss = onDismissError
                )
            } else if (uiState.showChatContextDisclosurePrompt) {
                // First-time in-frame privacy disclosure card when opening ✍️ in a messaging app
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(palette.cardElevatedBg)
                        .border(1.dp, TypeFreeEmerald.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 10.dp, vertical = (6f * s).dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "📸 开启 AI 帮回读屏与识图上下文？",
                            color = palette.keyTextPrimary,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(palette.keyCapBg)
                                    .border(1.dp, palette.keyBorder, RoundedCornerShape(10.dp))
                                    .clickable(onClick = onDeclineChatContextDisclosure)
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    text = "暂不开启",
                                    color = palette.keyTextMuted,
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(TypeFreeEmerald)
                                    .clickable(onClick = onAcceptChatContextDisclosure)
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    text = "同意并启用",
                                    color = Color.White,
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                    Text(
                        text = "仅在你点 ✍️ 时截取当前聊天区画面（自动裁除状态栏与键盘）并提取消息，发给配置的 AI 模型帮回；用完即焚、不落盘。",
                        color = palette.keyTextMuted,
                        fontSize = 10.sp,
                        lineHeight = 13.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            } else if (isContextActive) {
                // Active Contextual Smart Reply: 1-line incoming message / vision quote + Attitude Chips + Custom
                val latestPreview = uiState.capturedChatContext.latestIncomingMessage
                    .ifBlank { uiState.capturedChatContext.formattedLines.lastOrNull().orEmpty() }
                    .ifBlank { "📸 已截取当前聊天区画面（含气泡文字与图片），AI 将结合画面视觉帮回" }
                Column(verticalArrangement = Arrangement.spacedBy((5f * s).dp)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(palette.cardElevatedBg)
                            .border(1.dp, TypeFreeEmerald.copy(alpha = 0.38f), RoundedCornerShape(10.dp))
                            .padding(horizontal = 10.dp, vertical = (5f * s).dp)
                    ) {
                        Text(
                            text = if (uiState.capturedChatContext.latestIncomingMessage.isNotBlank()) {
                                "💬 $latestPreview"
                            } else {
                                latestPreview
                            },
                            color = palette.keyTextPrimary,
                            fontSize = 11.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        attitudePresets.forEach { preset ->
                            val isSelected = selectedPreset?.id == preset.id
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(13.dp))
                                    .background(
                                        if (isSelected) TypeFreeEmerald else TypeFreeEmerald.copy(alpha = 0.12f)
                                    )
                                    .border(
                                        width = 1.dp,
                                        color = if (isSelected) TypeFreeEmerald else TypeFreeEmerald.copy(alpha = 0.45f),
                                        shape = RoundedCornerShape(13.dp)
                                    )
                                    .clickable(enabled = !isBusy) { onPresetClick(preset) }
                                    .padding(horizontal = 10.dp, vertical = (5.5f * s).dp)
                            ) {
                                Text(
                                    text = if (isSelected) "✓ ${preset.label}" else preset.label,
                                    color = when {
                                        isBusy -> palette.keyTextMuted
                                        isSelected -> Color.White
                                        palette.isDark -> Color(0xFF6EE7B7)
                                        else -> Color(0xFF047857)
                                    },
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                        ImeLogic.helpWritePresets.forEach { preset ->
                            val isSelected = selectedPreset?.id == preset.id
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(13.dp))
                                    .background(if (isSelected) TypeFreeEmerald else palette.cardElevatedBg)
                                    .border(
                                        width = 1.dp,
                                        color = if (isSelected) TypeFreeEmerald else palette.keyBorder,
                                        shape = RoundedCornerShape(13.dp)
                                    )
                                    .clickable(enabled = !isBusy) { onPresetClick(preset) }
                                    .padding(horizontal = 10.dp, vertical = (5.5f * s).dp)
                            ) {
                                Text(
                                    text = if (isSelected) "✓ ${preset.label}" else preset.label,
                                    color = when {
                                        isBusy -> palette.keyTextMuted
                                        isSelected -> Color.White
                                        else -> palette.keyTextPrimary
                                    },
                                    fontSize = 11.5.sp,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium
                                )
                            }
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(13.dp))
                                .background(palette.keyCapBg)
                                .border(1.dp, palette.keyBorder, RoundedCornerShape(13.dp))
                                .clickable(enabled = !isBusy) { onOpenHostApp(AppDestinations.TAB_STYLES) }
                                .padding(horizontal = 10.dp, vertical = (5.5f * s).dp)
                        ) {
                            Text(
                                text = "＋ 自定义",
                                color = palette.keyTextMuted,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            } else {
                // Attitude + Inspiration Chips (Select attitude first, then speak or tap ⚡ 直接生成)
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(
                        text = if (selectedPreset != null) {
                            "已选态度「${selectedPreset.label}」· 按住下方麦克风补充细节，或点右侧 ⚡ 直接生成："
                        } else {
                            "先点选回复态度或灵感（支持选后按住语音补充细节），或直接口述："
                        },
                        color = if (selectedPreset != null) {
                            if (palette.isDark) Color(0xFF6EE7B7) else Color(0xFF047857)
                        } else {
                            palette.keyTextMuted
                        },
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        attitudePresets.forEach { preset ->
                            val isSelected = selectedPreset?.id == preset.id
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(
                                        if (isSelected) TypeFreeEmerald else TypeFreeEmerald.copy(alpha = 0.12f)
                                    )
                                    .border(
                                        width = 1.dp,
                                        color = if (isSelected) TypeFreeEmerald else TypeFreeEmerald.copy(alpha = 0.45f),
                                        shape = RoundedCornerShape(14.dp)
                                    )
                                    .clickable(enabled = !isBusy) { onPresetClick(preset) }
                                    .padding(horizontal = 10.dp, vertical = (6.5f * s).dp)
                            ) {
                                Text(
                                    text = if (isSelected) "✓ ${preset.label}" else preset.label,
                                    color = when {
                                        isBusy -> palette.keyTextMuted
                                        isSelected -> Color.White
                                        palette.isDark -> Color(0xFF6EE7B7)
                                        else -> Color(0xFF047857)
                                    },
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                        ImeLogic.helpWritePresets.forEach { preset ->
                            val isSelected = selectedPreset?.id == preset.id
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(if (isSelected) TypeFreeEmerald else palette.cardElevatedBg)
                                    .border(
                                        width = 1.dp,
                                        color = if (isSelected) TypeFreeEmerald else palette.keyBorder,
                                        shape = RoundedCornerShape(14.dp)
                                    )
                                    .clickable(enabled = !isBusy) { onPresetClick(preset) }
                                    .padding(horizontal = 11.dp, vertical = (6.5f * s).dp)
                            ) {
                                Text(
                                    text = if (isSelected) "✓ ${preset.label}" else preset.label,
                                    color = when {
                                        isBusy -> palette.keyTextMuted
                                        isSelected -> Color.White
                                        else -> palette.keyTextPrimary
                                    },
                                    fontSize = 11.5.sp,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium
                                )
                            }
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .background(palette.keyCapBg)
                                .border(1.dp, palette.keyBorder, RoundedCornerShape(14.dp))
                                .clickable(enabled = !isBusy) { onOpenHostApp(AppDestinations.TAB_STYLES) }
                                .padding(horizontal = 10.dp, vertical = (6.5f * s).dp)
                        ) {
                            Text(
                                text = "＋ 自定义",
                                color = palette.keyTextMuted,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }

            // Bottom-Left Pulsing Circle Button + Prompt Description + Optional `⚡ 直接生成`
            SubPanelVoiceTriggerRow(
                phase = phase,
                amplitudes = amplitudes,
                elapsedMs = elapsedMs,
                idleTitle = when {
                    selectedPreset != null -> "已选「${selectedPreset.label}」· 按住口述补充细节"
                    isContextActive -> "Say how to reply · 口述你的回复态度"
                    else -> "Describe what to write · 口述你想写的内容"
                },
                idleSubtitle = when {
                    selectedPreset != null -> "说具体原因/时间，或点右侧「⚡ 直接生成」直接起草"
                    isContextActive -> "点上方选态度后可继续语音补充 · 如：跟他说下午三点没问题"
                    else -> "可先点选上方态度芯片再说话，或直接口述需求"
                },
                recordingTitle = when {
                    selectedPreset != null -> "已选「${selectedPreset.label}」· 正在聆听补充细节"
                    isContextActive -> "Say how to reply · 正在聆听回复意图"
                    else -> "Describe what to write · 正在聆听需求"
                },
                directActionLabel = if (selectedPreset != null) "⚡ 直接生成" else null,
                onDirectActionClick = onDirectGenerate,
                onStartRecording = onStartRecording,
                onReleaseRecording = onReleaseRecording,
                onFinishHandsFree = onFinishHandsFree,
                onCancelRecording = onCancelRecording
            )
        }

        BottomCornerUtilityIcons(
            onHideKeyboard = onHideKeyboard,
            onSwitchKeyboard = onSwitchKeyboard,
            onShowImePicker = onShowImePicker,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

// =============================================================================
// 5. Inline `Speak to edit · 语音编辑` Panel (`ref_typefree_speak_edit.png`)
// =============================================================================

@Composable
private fun SpeakToEditPanel(
    uiState: ImeUiState,
    amplitudes: List<Float>,
    elapsedMs: Long,
    onDismissSelection: () -> Unit,
    onSwitchToQwerty: () -> Unit,
    onOpenClipboard: () -> Unit,
    onQuickChipClick: (QuickEditChip) -> Unit,
    onStartRecording: () -> Unit,
    onReleaseRecording: () -> Unit,
    onFinishHandsFree: () -> Unit,
    onCancelRecording: () -> Unit,
    onOpenHostApp: (Int) -> Unit,
    onRetryLastAudio: () -> Unit,
    onDismissError: () -> Unit,
    onHideKeyboard: () -> Unit,
    onSwitchKeyboard: () -> Unit,
    onShowImePicker: () -> Unit
) {
    val palette = LocalTypeFreeKeyboardPalette.current
    val s = LocalKeyboardMetrics.current.elementScale
    val phase = uiState.voicePhase
    val isBusy = phase is VoicePhase.Recording || phase is VoicePhase.Processing

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = (28f * s).dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Header: Brand Logo + `TypeFree · 语音编辑` + Top-Right Direct QWERTY / Clipboard / `×`
            // Locked to 34.dp height & identical Brand inner Row geometry so LogoMark + TypeFree
            // stay at exact (0, 0) px shift relative to TypeFreeTopBar!
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(34.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .requiredWidth(IntrinsicSize.Max)
                            .padding(vertical = 3.dp, horizontal = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = TypeFreeIcons.LogoMark,
                            contentDescription = null,
                            tint = palette.keyTextPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "TypeFree",
                            color = palette.keyTextPrimary,
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    }
                    Text(
                        text = "· 语音编辑",
                        color = palette.keyTextMuted,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                SubPanelHeaderBridgeBar(
                    isFlypyChineseMode = uiState.isFlypyChineseMode,
                    inputSchemeShortBadge = uiState.activeInputScheme.shortBadge,
                    hasFreshClipboard = uiState.freshClipboardBannerItem != null,
                    onSwitchToQwerty = onSwitchToQwerty,
                    onOpenClipboard = onOpenClipboard,
                    closeDescription = "忽略选区切回普通口述",
                    onClose = onDismissSelection
                )
            }

            if (phase is VoicePhase.Error) {
                ErrorContextBanner(
                    error = phase,
                    onRetry = onRetryLastAudio,
                    onOpenApiSetup = { onOpenHostApp(AppDestinations.TAB_API) },
                    onOpenPermissionSetup = { onOpenHostApp(AppDestinations.TAB_HOME) },
                    onDismiss = onDismissError
                )
            } else {
                // Selected Text Preview Card + Quick Rewrite Chips (`ImeLogic.quickEditChips`)
                Column(verticalArrangement = Arrangement.spacedBy((6f * s).dp)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(palette.cardElevatedBg)
                            .border(1.dp, palette.keyBorder, RoundedCornerShape(12.dp))
                            .padding(horizontal = 10.dp, vertical = (6f * s).dp)
                    ) {
                        Text(
                            text = ImeLogic.formatSelectionPreview(uiState.effectiveSelectedText, 64),
                            color = palette.keyTextPrimary,
                            fontSize = 12.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(
                                    if (palette.isDark) {
                                        Color(0xFF1E3A5F).copy(alpha = 0.55f)
                                    } else {
                                        Color(0xFFBFDBFE).copy(alpha = 0.65f)
                                    }
                                )
                                .padding(horizontal = 5.dp, vertical = 2.dp)
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        ImeLogic.quickEditChips.forEach { chip ->
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(palette.keyCapBg)
                                    .border(1.dp, palette.keyBorder, RoundedCornerShape(12.dp))
                                    .clickable(enabled = !isBusy) { onQuickChipClick(chip) }
                                    .padding(horizontal = 10.dp, vertical = (5f * s).dp)
                            ) {
                                Text(
                                    text = chip.label,
                                    color = if (isBusy) palette.keyTextMuted else palette.keyTextPrimary,
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            }

            // Bottom-Left Pulsing Circle Button + `Speak to edit` (`ref_typefree_speak_edit.png`)
            SubPanelVoiceTriggerRow(
                phase = phase,
                amplitudes = amplitudes,
                elapsedMs = elapsedMs,
                idleTitle = "Speak to edit · 语音编辑",
                idleSubtitle = "轻点说出修改指令（如：改成正式语气 / 翻译成英文）",
                recordingTitle = "Speak to edit · 正在聆听修改指令",
                onStartRecording = onStartRecording,
                onReleaseRecording = onReleaseRecording,
                onFinishHandsFree = onFinishHandsFree,
                onCancelRecording = onCancelRecording
            )
        }

        BottomCornerUtilityIcons(
            onHideKeyboard = onHideKeyboard,
            onSwitchKeyboard = onSwitchKeyboard,
            onShowImePicker = onShowImePicker,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

@Composable
private fun SubPanelHeaderBridgeBar(
    isFlypyChineseMode: Boolean,
    inputSchemeShortBadge: String = "鹤",
    hasFreshClipboard: Boolean,
    onSwitchToQwerty: () -> Unit,
    onOpenClipboard: () -> Unit,
    closeDescription: String,
    onClose: () -> Unit
) {
    val palette = LocalTypeFreeKeyboardPalette.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // 1-Tap Jump to Built-in QWERTY (`拼/鹤 打字` / `EN 打字`) to type directly over selection
        Box(
            modifier = Modifier
                .height(30.dp)
                .clip(RoundedCornerShape(15.dp))
                .background(palette.keyCapBg)
                .border(1.dp, palette.keyBorder, RoundedCornerShape(15.dp))
                .clickable(onClick = onSwitchToQwerty)
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (isFlypyChineseMode) "$inputSchemeShortBadge 打字" else "EN 打字",
                color = palette.keyTextPrimary,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
        }

        // 1-Tap Jump to Built-in Clipboard (`📋`) to paste over selection
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(palette.keyCapBg)
                .border(1.dp, palette.keyBorder, CircleShape)
                .clickable(onClick = onOpenClipboard),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = TypeFreeIcons.Clipboard,
                contentDescription = "剪贴板",
                tint = palette.keyTextPrimary,
                modifier = Modifier.size(14.5.dp)
            )
            if (hasFreshClipboard) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(3.dp)
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(TypeFreeEmerald)
                )
            }
        }

        // Dismiss Sub-Panel (`×`)
        CircleFloatingKey(
            size = 30.dp,
            onClick = onClose
        ) {
            Icon(
                imageVector = TypeFreeIcons.Close,
                contentDescription = closeDescription,
                tint = palette.keyTextPrimary,
                modifier = Modifier.size(14.5.dp)
            )
        }
    }
}

@Composable
private fun SubPanelVoiceTriggerRow(
    phase: VoicePhase,
    amplitudes: List<Float>,
    elapsedMs: Long,
    idleTitle: String,
    idleSubtitle: String,
    recordingTitle: String,
    directActionLabel: String? = null,
    onDirectActionClick: () -> Unit = {},
    onStartRecording: () -> Unit,
    onReleaseRecording: () -> Unit,
    onFinishHandsFree: () -> Unit,
    onCancelRecording: () -> Unit
) {
    val palette = LocalTypeFreeKeyboardPalette.current
    val s = LocalKeyboardMetrics.current.elementScale
    val isRecording = phase is VoicePhase.Recording
    val isProcessing = phase is VoicePhase.Processing

    val currentPhaseState by rememberUpdatedState(phase)
    val currentOnStart by rememberUpdatedState(onStartRecording)
    val currentOnRelease by rememberUpdatedState(onReleaseRecording)
    val currentOnFinish by rememberUpdatedState(onFinishHandsFree)

    val infiniteTransition = rememberInfiniteTransition(label = "subPanelPulse")
    val haloScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.16f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "subHaloScale"
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Left Pulsing Halo + Circular Dark Voice Button (`ref_typefree_help_write.png` & `ref_typefree_speak_edit.png`)
        Box(
            modifier = Modifier
                .size((64f * s).dp)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        val initial = currentPhaseState
                        if (initial is VoicePhase.Processing) return@awaitEachGesture

                        val startedFromHandsFree =
                            initial is VoicePhase.Recording && initial.isHandsFree
                        if (!startedFromHandsFree) {
                            currentOnStart()
                        }

                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.changes.all { !it.pressed }) {
                                event.changes.forEach { it.consume() }
                                break
                            }
                        }

                        if (startedFromHandsFree) {
                            currentOnFinish()
                        } else {
                            currentOnRelease()
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size((58f * s).dp)
                    .scale(if (isRecording) haloScale else 1.03f)
                    .clip(CircleShape)
                    .background(palette.pulseHaloColor.copy(alpha = if (isRecording) 0.72f else 0.45f))
            )

            Box(
                modifier = Modifier
                    .size((46f * s).dp)
                    .clip(CircleShape)
                    .background(palette.primaryMicBrush),
                contentAlignment = Alignment.Center
            ) {
                when {
                    isRecording -> {
                        SymmetricalSevenBarWaveform(
                            amplitudes = amplitudes,
                            color = palette.onPrimaryMic,
                            modifier = Modifier.size(width = (26f * s).dp, height = (20f * s).dp)
                        )
                    }
                    isProcessing -> {
                        CircularProgressIndicator(
                            color = palette.onPrimaryMic,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size((20f * s).dp)
                        )
                    }
                    else -> {
                        Icon(
                            imageVector = TypeFreeIcons.VoiceWaveform,
                            contentDescription = idleTitle,
                            tint = palette.onPrimaryMic,
                            modifier = Modifier.size((22f * s).dp)
                        )
                    }
                }
            }
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = when {
                    isRecording -> "$recordingTitle · ${ImeLogic.formatElapsedTimer(elapsedMs)}"
                    phase is VoicePhase.Processing -> phase.stageText
                    else -> idleTitle
                },
                color = palette.keyTextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = when {
                    phase is VoicePhase.Recording && phase.isHandsFree ->
                        "Tap again to finish · 轻点完成"
                    isRecording -> "松手立即执行 · 轻点保持免提"
                    isProcessing -> "请稍候，正在生成..."
                    else -> idleSubtitle
                },
                color = palette.keyTextMuted,
                fontSize = 11.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (isRecording || isProcessing) {
            CircleFloatingKey(
                size = (32f * s).dp,
                onClick = onCancelRecording
            ) {
                Icon(
                    imageVector = TypeFreeIcons.Close,
                    contentDescription = "取消",
                    tint = palette.keyTextPrimary,
                    modifier = Modifier.size((15f * s).dp)
                )
            }
        } else if (!directActionLabel.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .height((34f * s).dp)
                    .clip(RoundedCornerShape(17.dp))
                    .background(TypeFreeEmerald)
                    .clickable(onClick = onDirectActionClick)
                    .padding(horizontal = 11.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = directActionLabel,
                    color = Color.White,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            }
        }
    }
}

// =============================================================================
// 6. Real-Time Translate Panel (`文A` Mode)
// =============================================================================

@Composable
private fun RealtimeTranslatePanel(
    uiState: ImeUiState,
    settings: AppSettings,
    amplitudes: List<Float>,
    elapsedMs: Long,
    onSelectLanguage: (String) -> Unit,
    onStartRecording: () -> Unit,
    onReleaseRecording: () -> Unit,
    onFinishHandsFree: () -> Unit,
    onCancelRecording: () -> Unit,
    onBackspace: () -> Unit,
    onEnterOrNewline: () -> Unit,
    onHideKeyboard: () -> Unit,
    onSwitchKeyboard: () -> Unit,
    onShowImePicker: () -> Unit
) {
    val palette = LocalTypeFreeKeyboardPalette.current
    val s = LocalKeyboardMetrics.current.elementScale
    val phase = uiState.voicePhase
    val isRecording = phase is VoicePhase.Recording
    val isProcessing = phase is VoicePhase.Processing
    val targets = settings.translationTargets.ifEmpty { DefaultPresets.defaultTranslationTargets() }
    val activeLang = settings.activeTranslateTarget.ifBlank { targets.first() }

    val currentPhaseState by rememberUpdatedState(phase)
    val currentOnStart by rememberUpdatedState(onStartRecording)
    val currentOnRelease by rememberUpdatedState(onReleaseRecording)
    val currentOnFinish by rememberUpdatedState(onFinishHandsFree)

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = (28f * s).dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Target Language Capsule Strip
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = TypeFreeIcons.Translate,
                    contentDescription = null,
                    tint = palette.keyTextMuted,
                    modifier = Modifier.size(16.dp)
                )
                targets.forEach { lang ->
                    val selected = lang == activeLang
                    Box(
                        modifier = Modifier
                            .height((30f * s).dp)
                            .clip(RoundedCornerShape((15f * s).dp))
                            .background(if (selected) palette.primaryMicSolid else palette.keyCapBg)
                            .border(
                                width = 1.dp,
                                color = if (selected) Color.Transparent else palette.keyBorder,
                                shape = RoundedCornerShape((15f * s).dp)
                            )
                            .clickable(enabled = !isRecording && !isProcessing) {
                                onSelectLanguage(lang)
                            }
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = lang,
                            color = if (selected) palette.onPrimaryMic else palette.keyTextPrimary,
                            fontSize = 12.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
                        )
                    }
                }
            }

            // Center Translate Voice Button + Live Status
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy((6f * s).dp)
            ) {
                Text(
                    text = when {
                        isRecording -> "${ImeLogic.formatElapsedTimer(elapsedMs)} · 译为 $activeLang"
                        phase is VoicePhase.Processing -> phase.stageText
                        else -> "说任意语言，自动直译为 $activeLang"
                    },
                    color = palette.keyTextMuted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )

                val btnWidth by animateDpAsState(
                    targetValue = ((if (isRecording || isProcessing) 76f else 142f) * s).dp,
                    animationSpec = tween(180),
                    label = "transBtnWidth"
                )
                val btnHeight by animateDpAsState(
                    targetValue = ((if (isRecording || isProcessing) 76f else 54f) * s).dp,
                    animationSpec = tween(180),
                    label = "transBtnHeight"
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (isRecording || isProcessing) {
                        Spacer(modifier = Modifier.width((34f * s).dp))
                    }

                    Box(
                        modifier = Modifier
                            .size(width = btnWidth, height = btnHeight)
                            .clip(RoundedCornerShape((38f * s).dp))
                            .background(palette.primaryMicBrush)
                            .pointerInput(Unit) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    down.consume()
                                    val initial = currentPhaseState
                                    if (initial is VoicePhase.Processing) return@awaitEachGesture

                                    val startedFromHandsFree =
                                        initial is VoicePhase.Recording && initial.isHandsFree
                                    if (!startedFromHandsFree) {
                                        currentOnStart()
                                    }

                                    while (true) {
                                        val event = awaitPointerEvent()
                                        if (event.changes.all { !it.pressed }) {
                                            event.changes.forEach { it.consume() }
                                            break
                                        }
                                    }

                                    if (startedFromHandsFree) {
                                        currentOnFinish()
                                    } else {
                                        currentOnRelease()
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        when {
                            isRecording -> {
                                SymmetricalSevenBarWaveform(
                                    amplitudes = amplitudes,
                                    color = palette.onPrimaryMic,
                                    modifier = Modifier.size(width = (38f * s).dp, height = (28f * s).dp)
                                )
                            }
                            isProcessing -> {
                                CircularProgressIndicator(
                                    color = palette.onPrimaryMic,
                                    strokeWidth = 2.2.dp,
                                    modifier = Modifier.size((24f * s).dp)
                                )
                            }
                            else -> {
                                Icon(
                                    imageVector = TypeFreeIcons.Mic,
                                    contentDescription = "说话快译",
                                    tint = palette.onPrimaryMic,
                                    modifier = Modifier.size((25f * s).dp)
                                )
                            }
                        }
                    }

                    if (isRecording || isProcessing) {
                        CircleFloatingKey(
                            size = (34f * s).dp,
                            onClick = onCancelRecording
                        ) {
                            Icon(
                                imageVector = TypeFreeIcons.Close,
                                contentDescription = "取消快译",
                                tint = palette.keyTextPrimary,
                                modifier = Modifier.size((15f * s).dp)
                            )
                        }
                    }
                }
            }

            // Compact Send + Backspace Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(modifier = Modifier.width((42f * s).dp))

                Box(
                    modifier = Modifier
                        .size(width = (104f * s).dp, height = (40f * s).dp)
                        .clip(RoundedCornerShape((20f * s).dp))
                        .background(palette.keyCapBg)
                        .border(1.dp, palette.keyBorder, RoundedCornerShape((20f * s).dp))
                        .clickable(onClick = onEnterOrNewline),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = uiState.enterActionInfo.label,
                        color = palette.keyTextMuted,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                RepeatingCircleBackspaceKey(
                    size = (42f * s).dp,
                    onBackspace = onBackspace
                )
            }
        }

        BottomCornerUtilityIcons(
            onHideKeyboard = onHideKeyboard,
            onSwitchKeyboard = onSwitchKeyboard,
            onShowImePicker = onShowImePicker,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

// =============================================================================
// 7. Shared TypeFree Monochrome Key Components & 7-Bar Symmetrical Waveform
// =============================================================================

@Composable
private fun SymmetricalSevenBarWaveform(
    amplitudes: List<Float>,
    color: Color,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "sevenBarWave")
    val wavePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 6.283185f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 760, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wavePhase"
    )

    // 7 symmetrical envelope weights (`· | ||| | ·`)
    val envelope = remember { floatArrayOf(0.18f, 0.48f, 0.78f, 1.0f, 0.78f, 0.48f, 0.18f) }

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas

        val barCount = 7
        val barWidth = (w / 13.5f).coerceAtLeast(2.2f)
        val totalBarsWidth = barWidth * barCount
        val gap = ((w - totalBarsWidth) / (barCount - 1)).coerceAtLeast(1.5f)
        val corner = CornerRadius(barWidth / 2f, barWidth / 2f)

        val liveEnergy = amplitudes.takeLast(4)
            .let { tail -> if (tail.isEmpty()) 0.12f else tail.average().toFloat() }
            .coerceIn(0.08f, 1.0f)

        for (i in 0 until barCount) {
            val mirrorIdx = if (i <= 3) i else 6 - i
            val sampleAmp = amplitudes.getOrElse((amplitudes.size - 1 - mirrorIdx).coerceAtLeast(0)) {
                liveEnergy
            }.coerceIn(0.08f, 1.0f)

            val breathing = 0.16f * ((sin(wavePhase + mirrorIdx * 0.9f) + 1f) * 0.5f)
            val combined = (0.24f + sampleAmp * 0.76f + breathing).coerceIn(0.16f, 1.0f)
            val targetRatio = (envelope[i] * combined).coerceIn(0.14f, 0.98f)

            val barHeight = (h * targetRatio).coerceAtLeast(barWidth)
            val x = i * (barWidth + gap)
            val y = (h - barHeight) / 2f

            drawRoundRect(
                color = color,
                topLeft = Offset(x, y),
                size = Size(barWidth, barHeight),
                cornerRadius = corner
            )
        }
    }
}

@Composable
private fun FloatingQuickPunctuationStrip(
    onCommitText: (String) -> Unit,
    onInsertPairedSymbol: (String, String) -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalTypeFreeKeyboardPalette.current
    Row(
        modifier = modifier
            .height(38.dp)
            .clip(RoundedCornerShape(19.dp))
            .background(palette.cardElevatedBg)
            .border(1.dp, palette.keyBorder, RoundedCornerShape(19.dp))
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ImeLogic.quickPunctuationItems.forEach { sym ->
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(14.dp))
                    .background(palette.keyboardCanvas.copy(alpha = 0.65f))
                    .clickable {
                        if (sym == "“”") {
                            onInsertPairedSymbol("“", "”")
                        } else {
                            onCommitText(sym)
                        }
                    }
                    .padding(horizontal = 11.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = sym,
                    color = palette.keyTextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun CircleFloatingKey(
    size: Dp,
    highlighted: Boolean = false,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    val palette = LocalTypeFreeKeyboardPalette.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val bg = when {
        highlighted -> palette.primaryMicSolid
        isPressed -> palette.keyCapSecondaryBg
        else -> palette.keyCapBg
    }

    Box(
        modifier = Modifier
            .size(size)
            .scale(if (isPressed) 0.93f else 1f)
            .clip(CircleShape)
            .background(bg)
            .border(
                width = 1.dp,
                color = if (highlighted) Color.Transparent else palette.keyBorder,
                shape = CircleShape
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

@Composable
private fun RepeatingCircleBackspaceKey(
    size: Dp,
    onBackspace: () -> Unit
) {
    val palette = LocalTypeFreeKeyboardPalette.current
    val currentOnBackspace by rememberUpdatedState(onBackspace)
    var isHolding by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .size(size)
            .scale(if (isHolding) 0.93f else 1f)
            .clip(CircleShape)
            .background(if (isHolding) palette.keyCapSecondaryBg else palette.keyCapBg)
            .border(1.dp, palette.keyBorder, CircleShape)
            .pointerInput(Unit) {
                coroutineScope {
                    val outerScope = this
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        isHolding = true
                        currentOnBackspace()
                        val repeatJob = outerScope.launch {
                            delay(360L)
                            while (isActive) {
                                currentOnBackspace()
                                delay(50L)
                            }
                        }
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                if (event.changes.all { !it.pressed }) {
                                    event.changes.forEach { it.consume() }
                                    break
                                }
                            }
                        } finally {
                            isHolding = false
                            repeatJob.cancel()
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = TypeFreeIcons.Backspace,
            contentDescription = "退格删除",
            tint = palette.keyTextPrimary,
            modifier = Modifier.size(19.dp)
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BottomCornerUtilityIcons(
    onHideKeyboard: () -> Unit,
    onSwitchKeyboard: () -> Unit,
    onShowImePicker: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalTypeFreeKeyboardPalette.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(26.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Bottom-Left: Dismiss Keyboard (`TypeFreeIcons.ChevronDown` -> `requestHideSelf(0)`)
        Box(
            modifier = Modifier
                .size(width = 38.dp, height = 26.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onHideKeyboard),
            contentAlignment = Alignment.CenterStart
        ) {
            Icon(
                imageVector = TypeFreeIcons.ChevronDown,
                contentDescription = "收起键盘",
                tint = palette.keyTextPrimary.copy(alpha = 0.78f),
                modifier = Modifier
                    .padding(start = 6.dp)
                    .size(18.dp)
            )
        }

        // Bottom-Right: Switch IME / System IME Picker (`TypeFreeIcons.KeyboardGrid`)
        Box(
            modifier = Modifier
                .size(width = 38.dp, height = 26.dp)
                .clip(RoundedCornerShape(8.dp))
                .combinedClickable(
                    onClick = onSwitchKeyboard,
                    onLongClick = onShowImePicker
                ),
            contentAlignment = Alignment.CenterEnd
        ) {
            Icon(
                imageVector = TypeFreeIcons.KeyboardGrid,
                contentDescription = "切换输入法",
                tint = palette.keyTextPrimary.copy(alpha = 0.78f),
                modifier = Modifier
                    .padding(end = 6.dp)
                    .size(18.dp)
            )
        }
    }
}

@Composable
private fun ErrorContextBanner(
    error: VoicePhase.Error,
    onRetry: () -> Unit,
    onOpenApiSetup: () -> Unit,
    onOpenPermissionSetup: () -> Unit,
    onDismiss: () -> Unit
) {
    val palette = LocalTypeFreeKeyboardPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (palette.isDark) Color(0xFF2A141D) else Color(0xFFFFF1F2)
            )
            .border(1.dp, TypeFreeRose.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = "⚠ ${error.message}",
            color = if (palette.isDark) Color(0xFFFECDD3) else Color(0xFF9F1239),
            fontSize = 11.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )

        if (error.needsMicPermission) {
            BannerActionChip(
                label = "去授权",
                color = TypeFreeAmber,
                onClick = onOpenPermissionSetup
            )
        }
        if (error.needsApiSetup) {
            BannerActionChip(
                label = "配置 API",
                color = palette.primaryMicSolid,
                textColor = palette.onPrimaryMic,
                onClick = onOpenApiSetup
            )
        }
        if (error.canRetryAudio) {
            BannerActionChip(
                label = "重试",
                color = TypeFreeEmerald,
                onClick = onRetry
            )
        }

        Icon(
            imageVector = TypeFreeIcons.Close,
            contentDescription = "关闭提示",
            tint = palette.keyTextMuted,
            modifier = Modifier
                .size(18.dp)
                .clip(CircleShape)
                .clickable(onClick = onDismiss)
                .padding(2.dp)
        )
    }
}

@Composable
private fun BannerActionChip(
    label: String,
    color: Color,
    textColor: Color = Color.White,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text = label,
            color = textColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}
