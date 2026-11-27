package com.typefree.ime.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo
import com.typefree.ime.data.AppSettings
import com.typefree.ime.data.CandidateFontScale
import com.typefree.ime.data.ClipboardEntry
import com.typefree.ime.data.DefaultPresets
import com.typefree.ime.data.ExpressionTab
import com.typefree.ime.data.InputScheme
import com.typefree.ime.data.KeyboardHeightMode
import com.typefree.ime.data.SpaceLongPressAction
import com.typefree.ime.data.ToneStyle
import com.typefree.ime.data.VoiceIntentMode
import com.typefree.ime.i18n.AppLang
import com.typefree.ime.i18n.AppStrings
import com.typefree.ime.ime.autofill.InlineAutofillUiState
import com.typefree.ime.ime.flypy.FlypyCandidate
import com.typefree.ime.network.PipelineStage
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

sealed interface VoicePhase {
    data object Idle : VoicePhase

    data class Recording(
        val isHandsFree: Boolean
    ) : VoicePhase

    data class Processing(
        val stageText: String
    ) : VoicePhase

    data class Error(
        val message: String,
        val canRetryAudio: Boolean,
        val needsApiSetup: Boolean,
        val needsMicPermission: Boolean
    ) : VoicePhase

    data class SuccessToast(
        val preview: String,
        val latencyMs: Long
    ) : VoicePhase
}

enum class KeyboardTabMode {
    VOICE,
    QWERTY,
    TRANSLATE
}

enum class VoiceSubPanel {
    NONE,
    HELP_ME_WRITE,
    SPEAK_TO_EDIT,
    ASK_AI_CARD
}

enum class ShiftKeyState {
    OFF,
    ONCE,
    CAPS_LOCK
}

enum class LiveWriteUiStatus {
    INACTIVE,
    WRITING,
    FROZEN
}

data class UndoSnapshot(
    val replacedOriginalText: String,
    val insertedTextLength: Int
)

data class EnterActionInfo(
    val label: String,
    val performEditorActionId: Int?
)

data class QuickEditChip(
    val id: String,
    val label: String,
    val instruction: String
)

data class HelpWritePromptPreset(
    val id: String,
    val label: String,
    val instruction: String
)

data class ArcWheelSlots(
    val leftLanguage: String?,
    val centerLanguage: String,
    val rightLanguage: String?
)

data class InitialKeyboardResolution(
    val tabMode: KeyboardTabMode,
    val isFlypyChineseMode: Boolean,
    val isPasswordOverride: Boolean
)

data class ImeUiState(
    val voicePhase: VoicePhase = VoicePhase.Idle,
    val tabMode: KeyboardTabMode = KeyboardTabMode.VOICE,
    val inputSessionToken: Int = 0,
    val voiceSubPanel: VoiceSubPanel = VoiceSubPanel.NONE,
    val activeIntentMode: VoiceIntentMode = VoiceIntentMode.DICTATE,
    val isArcTranslateActive: Boolean = false,
    val arcStartedFromHandsFree: Boolean = false,
    val isArcCancelHovered: Boolean = false,
    val arcSelectedLanguage: String = "",
    val isPunctuationStripOpen: Boolean = false,
    val autoLearnedToastTerm: String? = null,
    val targetPackage: String = "",
    val targetAppName: String = "未知应用",
    val currentInputType: Int = 0,
    val currentImeOptions: Int = 0,
    val rawSelectedText: String = "",
    val ignoreSelectionForThisTurn: Boolean = false,
    val undoSnapshot: UndoSnapshot? = null,
    val isStylePickerExpanded: Boolean = false,
    val isHistoryDrawerOpen: Boolean = false,
    val hasCachedAudioForRetry: Boolean = false,
    val isFlypyChineseMode: Boolean = true,
    val flypyComposingCode: String = "",
    val flypyCandidates: List<FlypyCandidate> = emptyList(),
    val isFlypyCandidateSheetExpanded: Boolean = false,
    val isClipboardOpen: Boolean = false,
    val isExpressionPanelOpen: Boolean = false,
    val activeExpressionTab: ExpressionTab = ExpressionTab.EMOJI,
    val isToolPanelOpen: Boolean = false,
    val isToolPanelHeightExpanded: Boolean = false,
    val isToolPanelSchemeExpanded: Boolean = false,
    val isToolPanelDoublePinyinSubOpen: Boolean = false,
    val freshClipboardBannerItem: ClipboardEntry? = null,
    val quickActionToast: String? = null,
    val streamingPartialPreview: String = "",
    val liveWriteStatus: LiveWriteUiStatus = LiveWriteUiStatus.INACTIVE,
    val preeditDisplay: String = "",
    val t9Syllables: List<String> = emptyList(),
    val hasMoreCandidates: Boolean = false,
    val activeInputScheme: InputScheme = InputScheme.FLYPY_TABLE,
    val codetableEnhancerActive: Boolean = false,
    val singleCharModeEnabled: Boolean = false,
    val pinnedWordsForCurrentCode: Set<String> = emptySet(),
    val isOrqDateActive: Boolean = false,
    val orqDateOffsetDays: Int = 0,
    val passiveMathResult: String? = null,
    val passiveMathExpression: String? = null,
    val passiveMathEndsWithEquals: Boolean = false,
    val isPhraseRecording: Boolean = false,
    val recordedPhraseBuffer: String = "",
    val recordedPhraseCode: String? = null,
    val l2PhraseSuggestionWord: String? = null,
    val l2PhraseSuggestionCode: String? = null,
    val englishSuggestions: List<String> = emptyList(),
    val englishSuggestionReplaceLength: Int = 0,
    val englishAutoCapActive: Boolean = false,
    val isRimeDeploying: Boolean = false,
    val predictionCandidates: List<FlypyCandidate> = emptyList(),
    val aiCompletionSuggestion: String? = null,
    val isIncognitoMode: Boolean = false,
    val activeHeightLevel: Int = KeyboardMetrics.DEFAULT_LEVEL,
    val activeHeightMode: KeyboardHeightMode = KeyboardHeightMode.LIFT,
    val activeLiftLevel: Int = KeyboardMetrics.DEFAULT_LIFT_LEVEL,
    val activeBottomLiftDp: Int = KeyboardMetrics.DEFAULT_BOTTOM_LIFT_DP,
    val activeCandidateFontScale: CandidateFontScale = CandidateFontScale.STANDARD,
    val systemBottomInsetPx: Int = 0,
    val isT9QuickPunctuationOpen: Boolean = false,
    val activeT9CycleSymbol: String? = null,
    val inlineAutofillState: InlineAutofillUiState = InlineAutofillUiState(),
    val selectedHelpWritePreset: HelpWritePromptPreset? = null,
    val bigBangClipboardItem: ClipboardEntry? = null,
    val currentEditorText: String = "",
    val askAiQuery: String = "",
    val askAiAnswer: String = "",
    val isAskAiLoading: Boolean = false,
    val askAiErrorMessage: String? = null,
    val askAiFromSelection: Boolean = false
) {
    val isFlypyComposing: Boolean
        get() = tabMode == KeyboardTabMode.QWERTY &&
            isFlypyChineseMode &&
            (flypyComposingCode.isNotEmpty() || preeditDisplay.isNotEmpty())

    val activePhraseSuggestionLabel: String?
        get() = when {
            isPhraseRecording && recordedPhraseBuffer.codePointCount(0, recordedPhraseBuffer.length) >= 2 &&
                !recordedPhraseCode.isNullOrBlank() ->
                "+ 存入个人词: $recordedPhraseBuffer · $recordedPhraseCode"
            isPhraseRecording && recordedPhraseBuffer.isNotEmpty() ->
                "+ 造词中: $recordedPhraseBuffer"
            !l2PhraseSuggestionWord.isNullOrBlank() && !l2PhraseSuggestionCode.isNullOrBlank() ->
                "+ 录入: $l2PhraseSuggestionWord · $l2PhraseSuggestionCode"
            else -> null
        }

    val isPredictionBarVisible: Boolean
        get() = tabMode == KeyboardTabMode.QWERTY &&
            !isFlypyComposing &&
            !isT9QuickPunctuationOpen &&
            !inlineAutofillState.hasVisibleSuggestions &&
            voicePhase !is VoicePhase.Recording &&
            voicePhase !is VoicePhase.Processing &&
            rawSelectedText.isBlank() && (
                (isFlypyChineseMode && (
                    predictionCandidates.isNotEmpty() ||
                        !aiCompletionSuggestion.isNullOrBlank() ||
                        !activePhraseSuggestionLabel.isNullOrBlank()
                    )) ||
                    !passiveMathResult.isNullOrBlank() ||
                    (!isFlypyChineseMode && englishSuggestions.isNotEmpty())
            )
    val effectiveSelectedText: String
        get() = if (ignoreSelectionForThisTurn) "" else rawSelectedText

    val isSpeakToEditActive: Boolean
        get() = effectiveSelectedText.isNotBlank()

    val hasIgnoredSelection: Boolean
        get() = ignoreSelectionForThisTurn && rawSelectedText.isNotBlank()

    val shouldShowTopBarSpeakToEditPill: Boolean
        get() = rawSelectedText.isNotBlank() &&
            (tabMode != KeyboardTabMode.VOICE || ignoreSelectionForThisTurn)

    val enterActionInfo: EnterActionInfo
        get() = ImeLogic.resolveEnterAction(currentImeOptions, currentInputType)

    fun resolveEnterActionInfo(isChineseLocale: Boolean): EnterActionInfo {
        return ImeLogic.resolveEnterAction(currentImeOptions, currentInputType, isChineseLocale)
    }

    val resolvedVoiceSubPanel: VoiceSubPanel
        get() = when {
            voiceSubPanel == VoiceSubPanel.ASK_AI_CARD -> VoiceSubPanel.ASK_AI_CARD
            isSpeakToEditActive -> VoiceSubPanel.SPEAK_TO_EDIT
            voiceSubPanel == VoiceSubPanel.SPEAK_TO_EDIT -> VoiceSubPanel.NONE
            else -> voiceSubPanel
        }
}

object ImeLogic {

    const val PUSH_TO_TALK_THRESHOLD_MS: Long = 280L
    const val ARC_PUSH_TO_TALK_MIN_MS: Long = 1400L
    const val AUTO_LEARN_WINDOW_MS: Long = 60_000L
    const val ARC_TRANSLATE_TRIGGER_DP: Float = 34f
    const val ARC_WHEEL_STEP_DP: Float = 56f
    const val ARC_CANCEL_THRESHOLD_DP: Float = 20f
    const val DOUBLE_TAP_SHIFT_MS: Long = 360L
    const val SPACE_CURSOR_SLIDE_ACTIVATE_DP: Float = 10f
    const val SPACE_CURSOR_SLIDE_STEP_DP: Float = 11f
    const val SPACE_VOICE_HOLD_MS: Long = 240L
    const val SPACE_LANGUAGE_LONG_PRESS_MS: Long = 420L
    const val SECONDARY_KEY_LONG_PRESS_MS: Long = 250L
    const val SECONDARY_KEY_SWIPE_UP_DP: Float = 18f
    const val TOP_BAR_VOICE_HOLD_MS: Long = 220L
    const val T9_KEY1_CYCLE_WINDOW_MS: Long = 1200L

    val t9Key1CycleSymbols: List<String> = listOf("，", "。", "@", "？", "！")
    val t9QuickPunctuationItems: List<String> = listOf("，", "。", "@", "？", "！", "、", "：", "；")

    private val WHITESPACE_COLLAPSE_REGEX = Regex("\\s+")

    val quickEditChips: List<QuickEditChip> = listOf(
        QuickEditChip(
            id = "smart_polish",
            label = "✨ 智能润色",
            instruction = "保持原意，清理口语赘余与语病，优化表达流畅度与标点排版"
        ),
        QuickEditChip(
            id = "make_formal",
            label = "💼 改正式",
            instruction = "改写为专业、得体、干练的正式职场或书面表达"
        ),
        QuickEditChip(
            id = "translate_en",
            label = "🌐 译英文",
            instruction = "将选中内容翻译为自然、地道、符合母语习惯的英文，仅输出译文"
        ),
        QuickEditChip(
            id = "translate_zh",
            label = "🇨🇳 译中文",
            instruction = "将选中内容翻译为自然、准确、通顺的简体中文，仅输出译文"
        ),
        QuickEditChip(
            id = "bullet_summary",
            label = "📌 提炼要点",
            instruction = "提炼核心结论与关键信息，整理为简洁清晰的分点要点列表"
        )
    )

    val helpWritePresets: List<HelpWritePromptPreset> = listOf(
        HelpWritePromptPreset(
            id = "meeting_invite",
            label = "📧 约周五下午三点开会",
            instruction = "写一封得体简洁的邮件，询问对方这周五下午三点是否有空开会同步进展"
        ),
        HelpWritePromptPreset(
            id = "polite_decline",
            label = "💬 委婉礼貌拒绝邀请",
            instruction = "写一段真诚、礼貌而得体的回复，感谢对方的邀请，说明因日程冲突无法出席并期待下次交流"
        ),
        HelpWritePromptPreset(
            id = "weekly_recap",
            label = "📝 起草本周工作简报",
            instruction = "起草一份条理清晰的本周核心成果总结与下周重点计划简报模板"
        )
    )

    val smartReplyAttitudePresets: List<HelpWritePromptPreset> =
        DefaultPresets.defaultReplyAttitudes().map { preset ->
            HelpWritePromptPreset(
                id = preset.id,
                label = preset.label,
                instruction = preset.instruction
            )
        }

    fun resolveReplyAttitudePresets(settings: AppSettings): List<HelpWritePromptPreset> {
        val source = settings.replyAttitudePresets.ifEmpty { DefaultPresets.defaultReplyAttitudes() }
        return source.map { preset ->
            HelpWritePromptPreset(
                id = preset.id,
                label = preset.label,
                instruction = preset.instruction
            )
        }
    }

    val quickPunctuationItems: List<String> = listOf(
        "@", "，", "。", "？", "！", "、", ".", ",", "“”"
    )

    val punctuationItems: List<String> = listOf(
        "@", "，", "。", "？", "！", "、", ".", ",", "：", "；", "“”", "（）", "换行"
    )

    /**
     * Resolves the smart Enter/Action button label and optional editor action ID
     * from [EditorInfo.imeOptions].
     */
    fun resolveEnterAction(
        imeOptions: Int,
        @Suppress("UNUSED_PARAMETER") inputType: Int = 0,
        isChineseLocale: Boolean = true
    ): EnterActionInfo {
        val noEnterAction = (imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        if (noEnterAction) {
            return EnterActionInfo(
                label = if (isChineseLocale) "换行" else "Return",
                performEditorActionId = null
            )
        }
        return when (val action = imeOptions and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_SEND -> EnterActionInfo(if (isChineseLocale) "发送" else "Send", action)
            EditorInfo.IME_ACTION_SEARCH -> EnterActionInfo(if (isChineseLocale) "搜索" else "Search", action)
            EditorInfo.IME_ACTION_GO -> EnterActionInfo(if (isChineseLocale) "前往" else "Go", action)
            EditorInfo.IME_ACTION_NEXT -> EnterActionInfo(if (isChineseLocale) "下一步" else "Next", action)
            EditorInfo.IME_ACTION_DONE -> EnterActionInfo(if (isChineseLocale) "完成" else "Done", action)
            else -> EnterActionInfo(if (isChineseLocale) "换行" else "Return", null)
        }
    }

    fun resolveEnterLabel(
        imeOptions: Int,
        inputType: Int = 0,
        isChineseLocale: Boolean = true
    ): String {
        return resolveEnterAction(imeOptions, inputType, isChineseLocale).label
    }

    fun isPasswordEditorInputType(inputType: Int): Boolean {
        val cls = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return when (cls) {
            InputType.TYPE_CLASS_TEXT -> {
                variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            }
            InputType.TYPE_CLASS_NUMBER -> {
                variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            }
            else -> false
        }
    }

    /**
     * Checks whether an input field explicitly requests raw ASCII / Latin characters
     * (such as password fields, activation/redeem codes with [EditorInfo.IME_FLAG_FORCE_ASCII],
     * URLs, or email addresses) so QWERTY starts in `EN` instead of Chinese composition.
     */
    fun isAsciiPreferredEditor(inputType: Int, imeOptions: Int = 0): Boolean {
        if (isPasswordEditorInputType(inputType)) return true
        if ((imeOptions and EditorInfo.IME_FLAG_FORCE_ASCII) != 0) return true
        val cls = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return cls == InputType.TYPE_CLASS_TEXT && (
            variation == InputType.TYPE_TEXT_VARIATION_URI ||
                variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
            )
    }

    fun isIncognitoImeOptions(
        imeOptions: Int,
        inputType: Int = 0,
        manualIncognitoMode: Boolean = false
    ): Boolean {
        return manualIncognitoMode ||
            (imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0 ||
            isPasswordEditorInputType(inputType)
    }

    /**
     * Resolves which top-level keyboard tab (`VOICE` / `QWERTY` / `TRANSLATE`) and QWERTY
     * language mode (`鹤` Chinese vs `EN` English) should activate when entering an input session:
     * - Password fields temporarily force `QWERTY` + `EN` (`isPasswordOverride = true`) without
     *   overwriting the user's remembered preference.
     * - When [AppSettings.rememberLastKeyboardMode] is enabled, restores the last used tab and
     *   `鹤/EN` sub-mode across input boxes, app switches, and IME restarts.
     */
    fun resolveInitialKeyboardMode(
        settings: AppSettings,
        isPasswordField: Boolean
    ): InitialKeyboardResolution {
        if (isPasswordField) {
            return InitialKeyboardResolution(
                tabMode = KeyboardTabMode.QWERTY,
                isFlypyChineseMode = false,
                isPasswordOverride = true
            )
        }
        if (settings.rememberLastKeyboardMode) {
            val rememberedTab = when (settings.lastKeyboardTab.trim().uppercase(Locale.US)) {
                KeyboardTabMode.QWERTY.name -> KeyboardTabMode.QWERTY
                KeyboardTabMode.TRANSLATE.name -> KeyboardTabMode.TRANSLATE
                else -> KeyboardTabMode.VOICE
            }
            return InitialKeyboardResolution(
                tabMode = rememberedTab,
                isFlypyChineseMode = settings.lastQwertyIsChinese,
                isPasswordOverride = false
            )
        }
        return InitialKeyboardResolution(
            tabMode = KeyboardTabMode.VOICE,
            isFlypyChineseMode = settings.flypyEnabled,
            isPasswordOverride = false
        )
    }

    /**
     * Resolves the keyboard mode for `onStartInput` / `onStartInputView`:
     * - Whenever `restarting == false` (new input session or switching from EditText A to EditText B
     *   on the same screen), unconditionally evaluates [resolveInitialKeyboardMode], so turning off
     *   [AppSettings.rememberLastKeyboardMode] reliably resets back to `VOICE` on every new field.
     * - Whenever `restarting == true` (in-place `restartInput` on the same field), preserves the
     *   user's active tab and `鹤/EN` sub-mode unless the field became a password input.
     */
    fun resolveSessionKeyboardMode(
        previousTabMode: KeyboardTabMode,
        previousFlypyChineseMode: Boolean,
        settings: AppSettings,
        isPasswordField: Boolean,
        restarting: Boolean
    ): InitialKeyboardResolution {
        if (!restarting) {
            return resolveInitialKeyboardMode(
                settings = settings,
                isPasswordField = isPasswordField
            )
        }
        return if (isPasswordField) {
            InitialKeyboardResolution(
                tabMode = KeyboardTabMode.QWERTY,
                isFlypyChineseMode = false,
                isPasswordOverride = true
            )
        } else {
            InitialKeyboardResolution(
                tabMode = previousTabMode,
                isFlypyChineseMode = previousFlypyChineseMode,
                isPasswordOverride = false
            )
        }
    }

    /**
     * Formats elapsed milliseconds as `MM:SS` (e.g. `00:08`, `01:35`).
     */
    fun formatElapsedTimer(elapsedMs: Long): String {
        val totalSeconds = elapsedMs.coerceAtLeast(0L) / 1000L
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }

    /**
     * Resolves the elapsed milliseconds shown in [VoicePhase.SuccessToast] (`✓ 已上屏 · X.Xs`):
     * - When post-stop pipeline processing latency ([pipelineLatencyMs]) is >= 100ms (e.g. Stage-2 LLM polish
     *   or non-streaming STT), uses [pipelineLatencyMs].
     * - When [pipelineLatencyMs] is < 100ms (e.g. live streaming ASR that already converged during recording
     *   so post-stop tail wait is 0~5ms), falls back to [recordingDurationMs] if available so an 18s
     *   streaming dictation shows `18.0s` instead of `0.0s`.
     * - Clamps any positive value to at least 100ms (`0.1s`) so formatting with `%.1fs` never displays `0.0s`.
     */
    fun resolveSuccessToastElapsedMs(
        pipelineLatencyMs: Long,
        recordingDurationMs: Long = 0L
    ): Long {
        return when {
            pipelineLatencyMs >= 100L -> pipelineLatencyMs
            recordingDurationMs >= 100L -> recordingDurationMs
            pipelineLatencyMs > 0L || recordingDurationMs > 0L -> 100L
            else -> 0L
        }
    }

    /**
     * Formats the top subtitle for [VoicePhase.SuccessToast] (`✓ 已上屏 · X.Xs`), guaranteeing
     * that any positive latency never rounds down to `0.0s`.
     */
    fun formatSuccessToastSubtitle(latencyMs: Long, isChineseLocale: Boolean = true): String {
        val prefix = if (isChineseLocale) "✓ 已上屏" else "✓ Inserted"
        if (latencyMs <= 0L) return prefix
        val seconds = (latencyMs / 1000f).coerceAtLeast(0.1f)
        return "$prefix · ${String.format(Locale.US, "%.1fs", seconds)}"
    }

    /**
     * Normalizes internal newlines/whitespace and truncates long selected text cleanly.
     */
    fun formatSelectionPreview(selectedText: String, maxChars: Int = 36): String {
        val normalized = selectedText.trim().replace(WHITESPACE_COLLAPSE_REGEX, " ")
        if (normalized.isEmpty()) return ""
        val limit = maxChars.coerceAtLeast(1)
        if (normalized.length <= limit) return normalized
        return normalized.take(limit).trimEnd() + "…"
    }

    /**
     * Distinguishes Press-and-Hold (>= 280ms -> Push-to-Talk) from Short Tap (< 280ms -> Hands-Free).
     */
    fun isPressAndHoldGesture(
        pressDurationMs: Long,
        thresholdMs: Long = PUSH_TO_TALK_THRESHOLD_MS
    ): Boolean {
        return pressDurationMs >= thresholdMs
    }

    /**
     * Maps [PipelineStage] to user-facing status indicator text.
     */
    fun stageDisplayText(
        stage: PipelineStage,
        isSpeakToEdit: Boolean = false,
        isChineseLocale: Boolean = true
    ): String {
        if (!isChineseLocale) {
            return when (stage) {
                PipelineStage.TRANSCRIBING_STT -> "1/2 Transcribing..."
                PipelineStage.POLISHING_LLM -> if (isSpeakToEdit) "2/2 Rewriting..." else "2/2 Polishing..."
                PipelineStage.MULTIMODAL_PROCESSING -> if (isSpeakToEdit) "Rewriting..." else "Polishing..."
            }
        }
        return when (stage) {
            PipelineStage.TRANSCRIBING_STT -> "1/2 语音识别中..."
            PipelineStage.POLISHING_LLM -> if (isSpeakToEdit) "2/2 AI 改写中..." else "2/2 AI 润色中..."
            PipelineStage.MULTIMODAL_PROCESSING -> if (isSpeakToEdit) "多模态改写中..." else "多模态直出中..."
        }
    }

    /**
     * Intent-aware stage indicator supporting DICTATE, SPEAK_TO_EDIT, HELP_ME_WRITE, and TRANSLATE.
     */
    fun stageDisplayTextForIntent(
        stage: PipelineStage,
        intentMode: VoiceIntentMode,
        targetLanguage: String = "",
        isChineseLocale: Boolean = true
    ): String {
        if (!isChineseLocale) {
            return when (intentMode) {
                VoiceIntentMode.DICTATE -> stageDisplayText(stage, isSpeakToEdit = false, isChineseLocale = false)
                VoiceIntentMode.SPEAK_TO_EDIT -> stageDisplayText(stage, isSpeakToEdit = true, isChineseLocale = false)
                VoiceIntentMode.HELP_ME_WRITE -> when (stage) {
                    PipelineStage.TRANSCRIBING_STT -> "1/2 Hearing prompt..."
                    PipelineStage.POLISHING_LLM -> "2/2 AI drafting..."
                    PipelineStage.MULTIMODAL_PROCESSING -> "AI drafting..."
                }
                VoiceIntentMode.TRANSLATE -> {
                    val lang = targetLanguage.ifBlank { "target language" }
                    when (stage) {
                        PipelineStage.TRANSCRIBING_STT -> "1/2 Transcribing..."
                        PipelineStage.POLISHING_LLM -> "2/2 Translating to $lang..."
                        PipelineStage.MULTIMODAL_PROCESSING -> "Translating to $lang..."
                    }
                }
            }
        }
        return when (intentMode) {
            VoiceIntentMode.DICTATE -> stageDisplayText(stage, isSpeakToEdit = false)
            VoiceIntentMode.SPEAK_TO_EDIT -> stageDisplayText(stage, isSpeakToEdit = true)
            VoiceIntentMode.HELP_ME_WRITE -> when (stage) {
                PipelineStage.TRANSCRIBING_STT -> "1/2 识别写作需求中..."
                PipelineStage.POLISHING_LLM -> "2/2 AI 正在起草..."
                PipelineStage.MULTIMODAL_PROCESSING -> "多模态起草中..."
            }
            VoiceIntentMode.TRANSLATE -> {
                val lang = targetLanguage.ifBlank { "目标语言" }
                when (stage) {
                    PipelineStage.TRANSCRIBING_STT -> "1/2 语音识别中..."
                    PipelineStage.POLISHING_LLM -> "2/2 译为 $lang..."
                    PipelineStage.MULTIMODAL_PROCESSING -> "直译为 $lang..."
                }
            }
        }
    }

    /**
     * Formats the top status bar App + Tone Style pill label.
     */
    fun buildStylePillText(
        activeStyle: ToneStyle,
        isAutoMatched: Boolean,
        appName: String
    ): String {
        return if (isAutoMatched) {
            val cleanApp = appName.trim()
            if (cleanApp.isNotEmpty() && cleanApp != "未知应用") {
                "✨ 自动 · $cleanApp：${activeStyle.name}"
            } else {
                "✨ 自动 · ${activeStyle.name}"
            }
        } else {
            "${activeStyle.emoji} 固定 · ${activeStyle.name}"
        }
    }

    /**
     * Compact scene style badge label for the minimalist TypeFree v2.10 top bar.
     */
    fun buildCompactStyleBadgeText(
        activeStyle: ToneStyle,
        isAutoMatched: Boolean
    ): String {
        return if (isAutoMatched) {
            "✨ ${activeStyle.name}"
        } else {
            "${activeStyle.emoji} ${activeStyle.name}"
        }
    }

    /**
     * Returns true when upward drag from the primary Mic button crosses the arc-translate threshold.
     * Note: upward screen movement produces negative `dragOffsetY`.
     */
    fun shouldEnterArcTranslate(dragOffsetY: Float, triggerPx: Float): Boolean {
        if (triggerPx <= 0f) return false
        return dragOffsetY <= -triggerPx
    }

    /**
     * Returns true when finger slides back down into the bottom `Cancel / 取消` zone during arc translate.
     */
    fun isInArcCancelZone(dragOffsetY: Float, cancelThresholdPx: Float): Boolean {
        return dragOffsetY >= cancelThresholdPx
    }

    /**
     * Deterministic Swipe-Up Arc Translate release rule:
     * - Started from Idle (`startedFromHandsFree == false`): releasing finger on the Arc Wheel
     *   ALWAYS locks into Hands-Free Translate recording (`上滑选定语言松开 → 开口说话 → 轻点完成`),
     *   never cutting off the microphone on finger-up regardless of how long choosing a language took.
     * - Started from active Hands-Free recording (`startedFromHandsFree == true`): the user
     *   already tapped Mic and spoke first, then swiped up onto the Arc Wheel (`Release to translate`),
     *   so releasing finger ALWAYS finishes recording and translates immediately.
     */
    fun shouldLockArcIntoHandsFree(startedFromHandsFree: Boolean): Boolean {
        return !startedFromHandsFree
    }

    /**
     * Computes the active target language index on the hold-and-swipe-up arc wheel
     * as the user slides horizontally (`dragOffsetX`) from `initialIndex`.
     */
    fun computeArcWheelIndex(
        initialIndex: Int,
        targetCount: Int,
        dragOffsetX: Float,
        stepPx: Float
    ): Int {
        if (targetCount <= 1) return 0
        val safeInitial = initialIndex.coerceIn(0, targetCount - 1)
        if (stepPx <= 0f) return safeInitial
        val deltaSteps = (dragOffsetX / stepPx).roundToInt()
        return ((safeInitial + deltaSteps) % targetCount + targetCount) % targetCount
    }

    /**
     * Resolves the left (`-10°`), center (`0°`), and right (`+10°`) capsule labels
     * for the 3-slot curved translation wheel.
     */
    fun resolveArcWheelSlots(
        targets: List<String>,
        selectedIndex: Int
    ): ArcWheelSlots {
        val clean = targets.filter { it.isNotBlank() }.ifEmpty { listOf("English (US)") }
        val n = clean.size
        val centerIdx = selectedIndex.coerceIn(0, n - 1)
        val center = clean[centerIdx]
        return when (n) {
            1 -> ArcWheelSlots(leftLanguage = null, centerLanguage = center, rightLanguage = null)
            2 -> ArcWheelSlots(
                leftLanguage = if (centerIdx == 1) clean[0] else null,
                centerLanguage = center,
                rightLanguage = if (centerIdx == 0) clean[1] else null
            )
            else -> ArcWheelSlots(
                leftLanguage = clean[(centerIdx - 1 + n) % n],
                centerLanguage = center,
                rightLanguage = clean[(centerIdx + 1) % n]
            )
        }
    }

    /**
     * Resolves horizontal swipe switching between `VOICE` and `QWERTY` modes.
     */
    fun resolveNextTabByHorizontalSwipe(
        currentTab: KeyboardTabMode,
        dragOffsetX: Float,
        dragOffsetY: Float,
        thresholdPx: Float
    ): KeyboardTabMode? {
        if (thresholdPx <= 0f) return null
        if (abs(dragOffsetX) < thresholdPx) return null
        if (abs(dragOffsetX) < abs(dragOffsetY) * 1.35f) return null
        return when (currentTab) {
            KeyboardTabMode.VOICE -> KeyboardTabMode.QWERTY
            KeyboardTabMode.QWERTY -> KeyboardTabMode.VOICE
            KeyboardTabMode.TRANSLATE -> if (dragOffsetX > 0f) KeyboardTabMode.VOICE else KeyboardTabMode.QWERTY
        }
    }

    /**
     * Computes next QWERTY Shift state supporting single-tap uppercase (`ONCE`),
     * double-tap Caps Lock (`CAPS_LOCK`), and tap-to-release (`OFF`).
     */
    fun nextShiftState(
        current: ShiftKeyState,
        timeSinceLastTapMs: Long,
        doubleTapWindowMs: Long = DOUBLE_TAP_SHIFT_MS
    ): ShiftKeyState {
        return when (current) {
            ShiftKeyState.OFF -> ShiftKeyState.ONCE
            ShiftKeyState.ONCE -> {
                if (timeSinceLastTapMs in 0L..doubleTapWindowMs) {
                    ShiftKeyState.CAPS_LOCK
                } else {
                    ShiftKeyState.OFF
                }
            }
            ShiftKeyState.CAPS_LOCK -> ShiftKeyState.OFF
        }
    }

    fun consumeShiftAfterCharCommit(current: ShiftKeyState): ShiftKeyState {
        return if (current == ShiftKeyState.ONCE) ShiftKeyState.OFF else current
    }

    /**
     * Formats the top preedit display in [CandidateStrip]:
     * - For [InputScheme.FLYPY_TABLE], displays the raw 4-code (`rawCode`).
     * - For Rime Pinyin / Double-Pinyin / Custom schemes, displays the segmented preedit
     *   (e.g. `wo'men'ming'tian'xia'wu'san'dian`) and left-truncates (`"…" + preedit.takeLast(maxVisibleChars)`)
     *   when longer than [maxVisibleChars] (38 chars in v3.4.0) so full-sentence pinyin stays visible.
     */
    fun formatPreeditDisplay(
        preedit: String,
        rawCode: String = "",
        scheme: InputScheme = InputScheme.RIME_PINYIN,
        maxVisibleChars: Int = 38
    ): String {
        if (!scheme.usesRime) {
            if (preedit.startsWith("[") ||
                (rawCode.length in 3..4 && preedit == "${rawCode.substring(0, 2)}'${rawCode.substring(2)}")
            ) {
                return preedit
            }
            return rawCode.ifEmpty { preedit }
        }
        val base = preedit.ifEmpty { rawCode }
        val limit = maxVisibleChars.coerceAtLeast(4)
        return if (base.length > limit) {
            "…" + base.takeLast(limit)
        } else {
            base
        }
    }

    /**
     * Computes integer character steps and remaining unconsumed pixel delta during
     * horizontal spacebar cursor sliding (`11.dp` per character step).
     */
    fun computeSpaceCursorSlideSteps(
        accumulatedDxPx: Float,
        stepPx: Float
    ): Pair<Int, Float> {
        if (stepPx <= 0f) return 0 to accumulatedDxPx
        val steps = (accumulatedDxPx / stepPx).toInt()
        if (steps == 0) return 0 to accumulatedDxPx
        val remainder = accumulatedDxPx - (steps * stepPx)
        return steps to remainder
    }

    fun resolveSpaceHoldDelayMs(action: SpaceLongPressAction): Long = when (action) {
        SpaceLongPressAction.VOICE_HOLD -> SPACE_VOICE_HOLD_MS
        SpaceLongPressAction.TOGGLE_LANGUAGE -> SPACE_LANGUAGE_LONG_PRESS_MS
    }

    /**
     * 5-key bottom row language switch fallback when spacebar hold is assigned to [SpaceLongPressAction.VOICE_HOLD]:
     * upward swipe (`dyPx <= -thresholdPx` and `abs(dyPx) > abs(dxPx)`) before hold fires toggles 中文 <-> EN.
     */
    fun shouldSwipeUpSpaceToggleLanguage(
        fiveKeyBottomRow: Boolean,
        spaceLongPressAction: SpaceLongPressAction,
        dxPx: Float,
        dyPx: Float,
        thresholdPx: Float
    ): Boolean {
        if (!fiveKeyBottomRow || spaceLongPressAction != SpaceLongPressAction.VOICE_HOLD || thresholdPx <= 0f) {
            return false
        }
        return dyPx <= -thresholdPx && abs(dyPx) > abs(dxPx)
    }

    /**
     * In 5-key bottom row Chinese idle state (`!hasActiveFlypyComposing && !isSymbolLayer`),
     * tapping left `⇧ Shift` toggles cleanly to English (`EN`) without needing a dedicated bottom key.
     */
    fun shouldFiveKeyShiftTapToggleLanguage(
        fiveKeyBottomRow: Boolean,
        isFlypyChineseMode: Boolean,
        hasActiveFlypyComposing: Boolean,
        isSymbolLayer: Boolean = false
    ): Boolean {
        return fiveKeyBottomRow && isFlypyChineseMode && !hasActiveFlypyComposing && !isSymbolLayer
    }

    /**
     * Pure spacebar label resolver enforcing clean idle labels (`雾凇全拼` / `小鹤音形 · 中` / `English`,
     * never cluttered with `"按住说话"` or `"↑切EN"`), showing `"🎙 聆听中 · 松手上屏"` ONLY while
     * in-place voice is active via spacebar hold, and `"‹ 滑动移光标 ›"` while sliding cursor.
     */
    fun resolveQwertySpaceBarLabel(
        lang: AppLang,
        isSpaceVoiceActive: Boolean,
        isCursorSliding: Boolean,
        hasComposing: Boolean,
        isFlypyChineseMode: Boolean,
        fiveKeyBottomRow: Boolean,
        resolvedChineseSchemeName: String
    ): String {
        return when {
            isSpaceVoiceActive -> AppStrings.pick(
                lang,
                "🎙 聆听中 · 松手上屏",
                "🎙 Listening · Release",
                "🎙 聆聽中 · 放開上屏"
            )
            isCursorSliding -> AppStrings.pick(
                lang,
                "‹ 滑动移光标 ›",
                "‹ Slide cursor ›",
                "‹ 滑動移游標 ›"
            )
            hasComposing -> AppStrings.pick(lang, "空格首选", "Space: 1st", "空白首選")
            isFlypyChineseMode -> if (fiveKeyBottomRow) "$resolvedChineseSchemeName · 中" else resolvedChineseSchemeName
            else -> if (fiveKeyBottomRow) "English · EN" else "English"
        }
    }

    /**
     * Builds an [UndoSnapshot] for restoring editor state on 1-tap Undo.
     */
    fun createUndoSnapshot(
        committedText: String,
        replacedSelection: String = ""
    ): UndoSnapshot? {
        if (committedText.isEmpty() && replacedSelection.isEmpty()) return null
        return UndoSnapshot(
            replacedOriginalText = replacedSelection,
            insertedTextLength = committedText.length
        )
    }

    /**
     * Pure helper simulating applying [UndoSnapshot] to `(textBeforeCursor, textAfterCursor)`.
     * Returns updated `(newBeforeCursor, newAfterCursor)`.
     */
    fun applyUndoToBuffer(
        beforeCursor: String,
        afterCursor: String,
        snapshot: UndoSnapshot
    ): Pair<String, String> {
        val deleteCount = snapshot.insertedTextLength.coerceIn(0, beforeCursor.length)
        val remainingBefore = beforeCursor.dropLast(deleteCount)
        val restoredBefore = remainingBefore + snapshot.replacedOriginalText
        return restoredBefore to afterCursor
    }

    /**
     * Computes how many UTF-16 code units before the cursor should be deleted on a single Backspace,
     * preserving surrogate pair integrity for Emoji/CJK supplementary characters.
     */
    fun computeBackspaceDeleteLength(beforeCursor: String): Int {
        if (beforeCursor.isEmpty()) return 0
        if (beforeCursor.length >= 2) {
            val high = beforeCursor[beforeCursor.length - 2]
            val low = beforeCursor[beforeCursor.length - 1]
            if (high.isHighSurrogate() && low.isLowSurrogate()) {
                return 2
            }
        }
        return 1
    }

    /**
     * Classifies an error string into actionable [VoicePhase.Error] flags.
     */
    fun classifyErrorFlags(
        errorMessage: String,
        hasCachedAudio: Boolean,
        hasMicPermission: Boolean
    ): VoicePhase.Error {
        val cleanMsg = errorMessage.trim().ifEmpty { "语音处理失败，请重试" }
        val needsMic = !hasMicPermission ||
            cleanMsg.contains("麦克风") && cleanMsg.contains("权限")
        val needsApi = cleanMsg.contains("API Key", ignoreCase = true) ||
            cleanMsg.contains("API 配置") ||
            cleanMsg.contains("云端 API") ||
            cleanMsg.contains("接口地址") ||
            cleanMsg.contains("(401)") ||
            cleanMsg.contains("(403)")
        val isTooShort = cleanMsg.contains("说话时间太短") ||
            cleanMsg.contains("录音时间太短") ||
            cleanMsg.contains("未检测到清晰说话声")
        return VoicePhase.Error(
            message = cleanMsg,
            canRetryAudio = hasCachedAudio && !needsMic && !isTooShort,
            needsApiSetup = needsApi,
            needsMicPermission = needsMic
        )
    }
}
