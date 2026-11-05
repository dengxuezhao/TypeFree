package com.typefree.ime.ime

import android.view.inputmethod.EditorInfo
import com.typefree.ime.audio.WavAudioRecorder
import com.typefree.ime.data.ToneStyle
import com.typefree.ime.data.VoiceIntentMode
import com.typefree.ime.network.PipelineStage
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImeLogicTest {

    @Test
    fun resolveEnterAction_mapsStandardImeActionsAndNoEnterFlag() {
        val send = ImeLogic.resolveEnterAction(EditorInfo.IME_ACTION_SEND)
        assertEquals("发送", send.label)
        assertEquals(EditorInfo.IME_ACTION_SEND, send.performEditorActionId)

        val search = ImeLogic.resolveEnterAction(EditorInfo.IME_ACTION_SEARCH)
        assertEquals("搜索", search.label)
        assertEquals(EditorInfo.IME_ACTION_SEARCH, search.performEditorActionId)

        val go = ImeLogic.resolveEnterAction(EditorInfo.IME_ACTION_GO)
        assertEquals("前往", go.label)
        assertEquals(EditorInfo.IME_ACTION_GO, go.performEditorActionId)

        val next = ImeLogic.resolveEnterAction(EditorInfo.IME_ACTION_NEXT)
        assertEquals("下一步", next.label)
        assertEquals(EditorInfo.IME_ACTION_NEXT, next.performEditorActionId)

        val done = ImeLogic.resolveEnterAction(EditorInfo.IME_ACTION_DONE)
        assertEquals("完成", done.label)
        assertEquals(EditorInfo.IME_ACTION_DONE, done.performEditorActionId)

        val unspecified = ImeLogic.resolveEnterAction(EditorInfo.IME_ACTION_UNSPECIFIED)
        assertEquals("换行", unspecified.label)
        assertNull(unspecified.performEditorActionId)

        // Multi-line field carrying IME_FLAG_NO_ENTER_ACTION must insert newline instead of sending
        val multiLineSend = ImeLogic.resolveEnterAction(
            EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_ENTER_ACTION
        )
        assertEquals("换行", multiLineSend.label)
        assertNull(multiLineSend.performEditorActionId)
    }

    @Test
    fun formatElapsedTimer_formatsMinutesAndSecondsCleanly() {
        assertEquals("00:00", ImeLogic.formatElapsedTimer(-200L))
        assertEquals("00:00", ImeLogic.formatElapsedTimer(0L))
        assertEquals("00:08", ImeLogic.formatElapsedTimer(8_950L))
        assertEquals("01:05", ImeLogic.formatElapsedTimer(65_100L))
        assertEquals("12:34", ImeLogic.formatElapsedTimer(754_000L))
    }

    @Test
    fun formatSelectionPreview_normalizesWhitespaceAndTruncates() {
        assertEquals("", ImeLogic.formatSelectionPreview("   \n\t  "))
        assertEquals(
            "第一行内容 第二行内容",
            ImeLogic.formatSelectionPreview("  第一行内容\n\n   第二行内容  ", maxChars = 20)
        )
        assertEquals(
            "这是一段非常长的选中文本…",
            ImeLogic.formatSelectionPreview("这是一段非常长的选中文本需要被优雅截断预览", maxChars = 12)
        )
    }

    @Test
    fun isPressAndHoldGesture_distinguishesTapFromHoldAt280ms() {
        assertFalse(ImeLogic.isPressAndHoldGesture(90L))
        assertFalse(ImeLogic.isPressAndHoldGesture(279L))
        assertTrue(ImeLogic.isPressAndHoldGesture(280L))
        assertTrue(ImeLogic.isPressAndHoldGesture(1_500L))
    }

    @Test
    fun undoCalculation_restoresDictationAndSpeakToEditCorrectly() {
        assertNull(ImeLogic.createUndoSnapshot(committedText = "", replacedSelection = ""))

        // Case 1: Pure voice dictation appended after existing text
        val dictationSnap = ImeLogic.createUndoSnapshot(
            committedText = "明天下午三点开会。",
            replacedSelection = ""
        )
        assertNotNull(dictationSnap)
        val (beforeAfterDictationUndo, afterAfterDictationUndo) = ImeLogic.applyUndoToBuffer(
            beforeCursor = "王总好，明天下午三点开会。",
            afterCursor = "请知悉",
            snapshot = dictationSnap!!
        )
        assertEquals("王总好，", beforeAfterDictationUndo)
        assertEquals("请知悉", afterAfterDictationUndo)

        // Case 2: Speak-to-Edit replaced selected text in-place
        val editSnap = ImeLogic.createUndoSnapshot(
            committedText = "Could we reschedule the meeting to 4 PM?",
            replacedSelection = "能不能把会议改到下午四点"
        )
        assertNotNull(editSnap)
        val (restoredBefore, restoredAfter) = ImeLogic.applyUndoToBuffer(
            beforeCursor = "Hi John, Could we reschedule the meeting to 4 PM?",
            afterCursor = " Thanks!",
            snapshot = editSnap!!
        )
        assertEquals("Hi John, 能不能把会议改到下午四点", restoredBefore)
        assertEquals(" Thanks!", restoredAfter)
    }

    @Test
    fun imeUiState_handlesIgnoreSelectionToggleCleanly() {
        val selectedState = ImeUiState(
            rawSelectedText = "需要翻译的原句",
            ignoreSelectionForThisTurn = false
        )
        assertTrue(selectedState.isSpeakToEditActive)
        assertEquals("需要翻译的原句", selectedState.effectiveSelectedText)
        assertFalse(selectedState.hasIgnoredSelection)

        val ignoredState = selectedState.copy(ignoreSelectionForThisTurn = true)
        assertFalse(ignoredState.isSpeakToEditActive)
        assertEquals("", ignoredState.effectiveSelectedText)
        assertTrue(ignoredState.hasIgnoredSelection)
    }

    @Test
    fun buildStylePillText_formatsAutoAndManualModes() {
        val chatStyle = ToneStyle(
            id = "casual_chat",
            name = "社交聊天",
            emoji = "💬",
            summary = "",
            promptInstruction = ""
        )
        assertEquals(
            "✨ 自动 · 微信：社交聊天",
            ImeLogic.buildStylePillText(chatStyle, isAutoMatched = true, appName = "微信")
        )
        assertEquals(
            "✨ 自动 · 社交聊天",
            ImeLogic.buildStylePillText(chatStyle, isAutoMatched = true, appName = "未知应用")
        )
        assertEquals(
            "💬 固定 · 社交聊天",
            ImeLogic.buildStylePillText(chatStyle, isAutoMatched = false, appName = "微信")
        )
        assertEquals("✨ 社交聊天", ImeLogic.buildCompactStyleBadgeText(chatStyle, isAutoMatched = true))
        assertEquals("💬 社交聊天", ImeLogic.buildCompactStyleBadgeText(chatStyle, isAutoMatched = false))
    }

    @Test
    fun computeBackspaceDeleteLength_preservesSurrogatePairs() {
        assertEquals(0, ImeLogic.computeBackspaceDeleteLength(""))
        assertEquals(1, ImeLogic.computeBackspaceDeleteLength("A"))
        assertEquals(1, ImeLogic.computeBackspaceDeleteLength("中文"))
        // "😊" is U+1F60A (\uD83D\uDE0A, 2 UTF-16 code units)
        assertEquals(2, ImeLogic.computeBackspaceDeleteLength("好的😊"))
    }

    @Test
    fun classifyErrorFlags_detectsApiPermissionAndRetryStates() {
        val apiError = ImeLogic.classifyErrorFlags(
            errorMessage = "请先在「API 配置」中填写 STT API Key",
            hasCachedAudio = true,
            hasMicPermission = true
        )
        assertTrue(apiError.needsApiSetup)
        assertFalse(apiError.needsMicPermission)
        assertTrue(apiError.canRetryAudio)

        val permError = ImeLogic.classifyErrorFlags(
            errorMessage = "尚未授予麦克风录音权限",
            hasCachedAudio = true,
            hasMicPermission = false
        )
        assertTrue(permError.needsMicPermission)
        assertFalse(permError.canRetryAudio)

        val shortError = ImeLogic.classifyErrorFlags(
            errorMessage = "说话时间太短，请按住麦克风说话后再松开",
            hasCachedAudio = true,
            hasMicPermission = true
        )
        assertFalse(shortError.canRetryAudio)
    }

    @Test
    fun stageDisplayText_formatsDualStageAndMultimodalLabels() {
        assertEquals(
            "1/2 语音识别中...",
            ImeLogic.stageDisplayText(PipelineStage.TRANSCRIBING_STT, isSpeakToEdit = false)
        )
        assertEquals(
            "2/2 AI 润色中...",
            ImeLogic.stageDisplayText(PipelineStage.POLISHING_LLM, isSpeakToEdit = false)
        )
        assertEquals(
            "2/2 AI 改写中...",
            ImeLogic.stageDisplayText(PipelineStage.POLISHING_LLM, isSpeakToEdit = true)
        )
        assertEquals(
            "多模态直出中...",
            ImeLogic.stageDisplayText(PipelineStage.MULTIMODAL_PROCESSING, isSpeakToEdit = false)
        )
    }

    @Test
    fun arcTranslateWheel_triggersStepsWrapsAndResolvesThreeSlots() {
        assertFalse(ImeLogic.shouldEnterArcTranslate(dragOffsetY = -20f, triggerPx = 90f))
        assertTrue(ImeLogic.shouldEnterArcTranslate(dragOffsetY = -92f, triggerPx = 90f))

        assertFalse(ImeLogic.isInArcCancelZone(dragOffsetY = -10f, cancelThresholdPx = 45f))
        assertTrue(ImeLogic.isInArcCancelZone(dragOffsetY = 50f, cancelThresholdPx = 45f))

        val targets = listOf("English (US)", "日本語", "简体中文", "Español")
        val stepPx = 120f

        // Stay near center -> index 0 ("English (US)")
        assertEquals(0, ImeLogic.computeArcWheelIndex(initialIndex = 0, targetCount = 4, dragOffsetX = 25f, stepPx = stepPx))
        // Slide right 1 step -> index 1 ("日本語")
        assertEquals(1, ImeLogic.computeArcWheelIndex(initialIndex = 0, targetCount = 4, dragOffsetX = 130f, stepPx = stepPx))
        // Slide left 1 step -> wraps to index 3 ("Español")
        assertEquals(3, ImeLogic.computeArcWheelIndex(initialIndex = 0, targetCount = 4, dragOffsetX = -130f, stepPx = stepPx))
        // Slide right 5 steps from index 1 -> wraps to (1 + 5) % 4 = 2 ("简体中文")
        assertEquals(2, ImeLogic.computeArcWheelIndex(initialIndex = 1, targetCount = 4, dragOffsetX = 600f, stepPx = stepPx))

        val slotsAtJapanese = ImeLogic.resolveArcWheelSlots(targets, selectedIndex = 1)
        assertEquals("English (US)", slotsAtJapanese.leftLanguage)
        assertEquals("日本語", slotsAtJapanese.centerLanguage)
        assertEquals("简体中文", slotsAtJapanese.rightLanguage)

        val singleSlot = ImeLogic.resolveArcWheelSlots(listOf("Français"), selectedIndex = 0)
        assertNull(singleSlot.leftLanguage)
        assertEquals("Français", singleSlot.centerLanguage)
        assertNull(singleSlot.rightLanguage)
    }

    @Test
    fun modeAndSubPanelTransitions_handleHorizontalSwipeAndSelectionPriority() {
        // Horizontal swipe switches VOICE <-> QWERTY, while vertical drag is ignored
        assertNull(
            ImeLogic.resolveNextTabByHorizontalSwipe(
                currentTab = KeyboardTabMode.VOICE,
                dragOffsetX = 40f,
                dragOffsetY = 10f,
                thresholdPx = 100f
            )
        )
        assertNull(
            ImeLogic.resolveNextTabByHorizontalSwipe(
                currentTab = KeyboardTabMode.VOICE,
                dragOffsetX = -120f,
                dragOffsetY = -110f,
                thresholdPx = 100f
            )
        )
        assertEquals(
            KeyboardTabMode.QWERTY,
            ImeLogic.resolveNextTabByHorizontalSwipe(
                currentTab = KeyboardTabMode.VOICE,
                dragOffsetX = -140f,
                dragOffsetY = 15f,
                thresholdPx = 100f
            )
        )
        assertEquals(
            KeyboardTabMode.VOICE,
            ImeLogic.resolveNextTabByHorizontalSwipe(
                currentTab = KeyboardTabMode.QWERTY,
                dragOffsetX = 140f,
                dragOffsetY = -10f,
                thresholdPx = 100f
            )
        )

        // Sub-panel resolution: Speak-to-Edit auto-opens on selection and yields when ignored
        val idleVoice = ImeUiState(tabMode = KeyboardTabMode.VOICE)
        assertEquals(VoiceSubPanel.NONE, idleVoice.resolvedVoiceSubPanel)

        val helpWriteOpen = idleVoice.copy(voiceSubPanel = VoiceSubPanel.HELP_ME_WRITE)
        assertEquals(VoiceSubPanel.HELP_ME_WRITE, helpWriteOpen.resolvedVoiceSubPanel)

        val withHostSelection = helpWriteOpen.copy(rawSelectedText = "需要改写的句子")
        assertEquals(VoiceSubPanel.SPEAK_TO_EDIT, withHostSelection.resolvedVoiceSubPanel)

        val ignoredSelection = withHostSelection.copy(
            ignoreSelectionForThisTurn = true,
            voiceSubPanel = VoiceSubPanel.SPEAK_TO_EDIT
        )
        assertEquals(VoiceSubPanel.NONE, ignoredSelection.resolvedVoiceSubPanel)
    }

    @Test
    fun qwertyShiftAndIntentStages_behaveDeterministically() {
        var shift = ShiftKeyState.OFF
        shift = ImeLogic.nextShiftState(shift, timeSinceLastTapMs = 9_999L)
        assertEquals(ShiftKeyState.ONCE, shift)

        // Single character commit consumes ONCE back to OFF
        assertEquals(ShiftKeyState.OFF, ImeLogic.consumeShiftAfterCharCommit(shift))

        // Rapid double-tap locks CAPS_LOCK
        val locked = ImeLogic.nextShiftState(ShiftKeyState.ONCE, timeSinceLastTapMs = 180L)
        assertEquals(ShiftKeyState.CAPS_LOCK, locked)
        assertEquals(ShiftKeyState.CAPS_LOCK, ImeLogic.consumeShiftAfterCharCommit(locked))
        assertEquals(ShiftKeyState.OFF, ImeLogic.nextShiftState(locked, timeSinceLastTapMs = 100L))

        // Intent-specific stage labels
        assertEquals(
            "2/2 AI 正在起草...",
            ImeLogic.stageDisplayTextForIntent(
                PipelineStage.POLISHING_LLM,
                VoiceIntentMode.HELP_ME_WRITE
            )
        )
        assertEquals(
            "直译为 日本語...",
            ImeLogic.stageDisplayTextForIntent(
                PipelineStage.MULTIMODAL_PROCESSING,
                VoiceIntentMode.TRANSLATE,
                targetLanguage = "日本語"
            )
        )
    }

    @Test
    fun shouldLockArcIntoHandsFree_deterministicallyFollowsInitialOrigin() {
        // Swiping up from Idle (`startedFromHandsFree == false`) MUST ALWAYS lock into
        // Hands-Free Translate recording, never cutting off the mic on finger-up!
        assertTrue(ImeLogic.shouldLockArcIntoHandsFree(startedFromHandsFree = false))

        // Swiping up from an already-active Hands-Free recording (`startedFromHandsFree == true`)
        // MUST immediately finish recording and translate on finger-up!
        assertFalse(ImeLogic.shouldLockArcIntoHandsFree(startedFromHandsFree = true))

        val shortErr = ImeLogic.classifyErrorFlags(
            errorMessage = "录音时间太短，请说完后再点击完成",
            hasCachedAudio = true,
            hasMicPermission = true
        )
        assertFalse(shortErr.canRetryAudio)
    }

    @Test
    fun normalizePcm16Le_boostsQuietVoiceIgnoresEdgeClicksAndLeavesSilenceUntouched() {
        fun shortsToLeBytes(samples: ShortArray): ByteArray {
            val buf = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (s in samples) buf.putShort(s)
            return buf.array()
        }
        fun leBytesToShorts(bytes: ByteArray): ShortArray {
            val out = ShortArray(bytes.size / 2)
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            for (i in out.indices) out[i] = buf.short
            return out
        }

        // 1. Pure silence (< 220 peak) stays untouched (1.0x gain)
        val silentSamples = ShortArray(16_000) { 45 }
        val silentBytes = shortsToLeBytes(silentSamples)
        val silentOut = leBytesToShorts(WavAudioRecorder.normalizePcm16Le(silentBytes, 16_000))
        assertEquals(45, silentOut[8_000].toInt())

        // 2. Quiet arm's-length voice (peak 2_000) with a loud tail finger-tap click (peak 22_000 in last 50ms)
        // must still boost interior voice by 6x (-> 12_000) while clamping the tail click cleanly to 32_767!
        val quietVoiceWithTailTap = ShortArray(16_000) { idx ->
            when {
                idx >= 15_500 -> 22_000 // tail tap click inside last 100ms edge margin
                idx % 2 == 0 -> 2_000
                else -> -2_000
            }
        }
        val boostedShorts = leBytesToShorts(
            WavAudioRecorder.normalizePcm16Le(shortsToLeBytes(quietVoiceWithTailTap), 16_000)
        )
        assertEquals(12_000, boostedShorts[8_000].toInt())
        assertEquals(-12_000, boostedShorts[8_001].toInt())
        assertEquals(32_767, boostedShorts[15_600].toInt())
    }
}
