package com.typefree.ime.v29

import androidx.compose.ui.unit.dp
import com.typefree.ime.data.AppSettings
import com.typefree.ime.data.KeyboardHeightMode
import com.typefree.ime.ime.ComposerEditorBridge
import com.typefree.ime.ime.KeyboardMetrics
import com.typefree.ime.ime.StreamingComposer
import com.typefree.ime.network.asr.LiveTranscript
import com.typefree.ime.ui.screens.ApiConfigBackupHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.9: keyboard height LIFT mode (垫高) and mobile-QQ style live-write duplication regressions.
 */
class LiftModeAndLiveWriteDedupTest {

    // =========================================================================
    // 1. KeyboardMetrics LIFT mode
    // =========================================================================

    @Test
    fun `LIFT mode keeps standard keys and adds 0-8-16-24-32dp bottom cushion`() {
        val expectedFrames = listOf(298, 306, 314, 322, 330)
        KeyboardMetrics.ALL_LIFT_LEVELS.forEachIndexed { idx, liftLevel ->
            val m = KeyboardMetrics.fromLevel(
                level = 0,
                isLandscape = false,
                heightMode = KeyboardHeightMode.LIFT,
                liftLevel = liftLevel
            )
            assertEquals(KeyboardHeightMode.LIFT, m.heightMode)
            assertEquals(250.dp, m.keyAreaHeight)
            assertEquals(1.0f, m.scale, 0.0001f)
            assertEquals(1.0f, m.elementScale, 0.0001f)
            assertEquals((liftLevel * 8).dp, m.liftHeight)
            assertEquals(expectedFrames[idx].dp, m.frameHeight)
            assertEquals(34.dp, m.topBarHeight)
        }
    }

    @Test
    fun `LIFT mode ignores stretch level, clamps lift level, and never lifts in landscape`() {
        // Stretch level must not stretch keys while in LIFT mode
        val liftWithStretchLevel = KeyboardMetrics.fromLevel(
            level = 2,
            heightMode = KeyboardHeightMode.LIFT,
            liftLevel = 2
        )
        assertEquals(250.dp, liftWithStretchLevel.keyAreaHeight)
        assertEquals(314.dp, liftWithStretchLevel.frameHeight)

        // Out-of-range lift levels are clamped
        val over = KeyboardMetrics.fromLevel(level = 0, heightMode = KeyboardHeightMode.LIFT, liftLevel = 99)
        assertEquals(32.dp, over.liftHeight)
        val under = KeyboardMetrics.fromLevel(level = 0, heightMode = KeyboardHeightMode.LIFT, liftLevel = -5)
        assertEquals(0.dp, under.liftHeight)

        // Landscape: no cushion
        val landscape = KeyboardMetrics.fromLevel(
            level = 0,
            isLandscape = true,
            heightMode = KeyboardHeightMode.LIFT,
            liftLevel = 4
        )
        assertEquals(0.dp, landscape.liftHeight)
        assertEquals(224.dp, landscape.frameHeight)

        // STRETCH mode is unchanged and never has a cushion
        val stretch = KeyboardMetrics.fromLevel(level = 2, heightMode = KeyboardHeightMode.STRETCH, liftLevel = 4)
        assertEquals(0.dp, stretch.liftHeight)
        assertEquals(333.dp, stretch.frameHeight)
        assertEquals(KeyboardMetrics.fromLevel(level = 2), stretch.copy(liftLevel = 0))
    }

    @Test
    fun `height mode and lift level survive config export and import`() {
        val original = AppSettings(
            keyboardHeightMode = KeyboardHeightMode.LIFT,
            keyboardLiftLevel = 3,
            keyboardHeightLevel = -1
        )
        val json = ApiConfigBackupHelper.exportSettingsJson(original, includeSecrets = false)
        assertTrue(json.contains("\"keyboardHeightMode\": \"LIFT\""))
        assertTrue(json.contains("\"keyboardLiftLevel\": 3"))

        val merged = ApiConfigBackupHelper.parseAndMergeConfigJson(json, AppSettings()).getOrThrow()
        assertEquals(KeyboardHeightMode.LIFT, merged.keyboardHeightMode)
        assertEquals(3, merged.keyboardLiftLevel)
        assertEquals(-1, merged.keyboardHeightLevel)

        // Partial JSON without the new keys keeps existing values; invalid lift level is clamped
        val partial = ApiConfigBackupHelper.parseAndMergeConfigJson(
            """{"keyboardHeightLevel": 1, "keyboardLiftLevel": 42}""",
            AppSettings(keyboardHeightMode = KeyboardHeightMode.LIFT)
        ).getOrThrow()
        assertEquals(KeyboardHeightMode.LIFT, partial.keyboardHeightMode)
        assertEquals(4, partial.keyboardLiftLevel)

        // Defaults for fresh installs
        assertEquals(KeyboardHeightMode.STRETCH, AppSettings().keyboardHeightMode)
        assertEquals(0, AppSettings().keyboardLiftLevel)
    }

    // =========================================================================
    // 2. Live-write duplication on hosts like mobile QQ
    // =========================================================================

    /**
     * BaseInputConnection-like buffer with switches that reproduce mobile QQ behaviors:
     * - [ignoreComposingRegion]: `setComposingRegion` returns true but does nothing.
     * - [dropComposingSpan]: the host ends composition on its own (text stays, span disappears).
     * - [ignoreNextDelete]: the host ignores one `deleteSurroundingText` call.
     */
    private class QqLikeEditor(
        initialText: String = "",
        var ignoreComposingRegion: Boolean = true
    ) : ComposerEditorBridge {
        val buffer = StringBuilder(initialText)
        var cursor = buffer.length
        var composingStart = -1
        var composingEnd = -1
        var ignoreNextDelete = false
        val text: String get() = buffer.toString()
        private val hasComposing: Boolean get() = composingStart >= 0 && composingEnd >= composingStart

        fun dropComposingSpan() {
            composingStart = -1
            composingEnd = -1
        }

        override fun beginBatchEdit() = true
        override fun endBatchEdit() = true

        override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
            val str = text.toString()
            val start = if (hasComposing) composingStart else cursor
            val end = if (hasComposing) composingEnd else cursor
            buffer.replace(start, end, str)
            if (str.isEmpty()) dropComposingSpan() else {
                composingStart = start
                composingEnd = start + str.length
            }
            cursor = start + str.length
            return true
        }

        override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
            val str = text.toString()
            val start = if (hasComposing) composingStart else cursor
            val end = if (hasComposing) composingEnd else cursor
            buffer.replace(start, end, str)
            dropComposingSpan()
            cursor = start + str.length
            return true
        }

        override fun finishComposingText(): Boolean {
            dropComposingSpan()
            return true
        }

        override fun setComposingRegion(start: Int, end: Int): Boolean {
            if (!ignoreComposingRegion) {
                composingStart = start
                composingEnd = end
            }
            return true // like a real async RemoteInputConnection: always "true"
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            if (ignoreNextDelete) {
                ignoreNextDelete = false
                return true
            }
            val from = (cursor - beforeLength).coerceAtLeast(0)
            buffer.delete(from, cursor)
            cursor = from
            dropComposingSpan()
            return true
        }

        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence =
            buffer.substring((cursor - n).coerceAtLeast(0), cursor)
    }

    @Test
    fun `polished text replaces the spaced streaming draft exactly once when host ignores setComposingRegion`() {
        val editor = QqLikeEditor(initialText = "群聊：", ignoreComposingRegion = true)
        val composer = StreamingComposer(editorBridge = editor, minUpdateIntervalMs = 0L)
        composer.startSession(anchorCursor = editor.cursor)

        composer.onTranscriptUpdate(LiveTranscript(finalized = "现在 有一个 bug ", interim = "出现", revision = 1))
        assertEquals("群聊：现在 有一个 bug 出现", editor.text)

        composer.onRecordingFinished(finalRawTranscript = "现在 有一个 bug 出现", needsPolishing = true)
        val outcome = composer.applyPolishedResult(
            polishedText = "现在有一个 bug 出现。",
            fullRawTranscript = "现在 有一个 bug 出现"
        )

        assertTrue(outcome.appliedToEditor)
        assertEquals("群聊：现在有一个 bug 出现。", editor.text)
    }

    @Test
    fun `host dropping the composing span mid-stream does not leave a second copy of the interim`() {
        var now = 1_000L
        val editor = QqLikeEditor()
        val composer = StreamingComposer(editorBridge = editor, minUpdateIntervalMs = 66L, clockMs = { now })
        composer.startSession(anchorCursor = 0)

        composer.onTranscriptUpdate(LiveTranscript(finalized = "", interim = "我正在", revision = 1))
        assertEquals("我正在", editor.text)

        // QQ ends composition by itself; cursor stays at the end, span reported as -1/-1
        editor.dropComposingSpan()
        composer.onUpdateSelection(newSelStart = 3, newSelEnd = 3, candidatesStart = -1, candidatesEnd = -1)
        assertTrue(composer.hostDroppedComposing)
        assertEquals(StreamingComposer.State.Live, composer.state)

        now += 100L
        composer.onTranscriptUpdate(LiveTranscript(finalized = "", interim = "我正在测试", revision = 2))
        assertEquals("我正在测试", editor.text)
        assertFalse(composer.hostDroppedComposing)

        // Drop again right before release: the final transcript must still appear once
        editor.dropComposingSpan()
        composer.onUpdateSelection(newSelStart = 5, newSelEnd = 5, candidatesStart = -1, candidatesEnd = -1)
        composer.onRecordingFinished(finalRawTranscript = "我正在测试语音输入", needsPolishing = true)
        assertEquals("我正在测试语音输入", editor.text)

        composer.applyPolishedResult(polishedText = "我正在测试语音输入。", fullRawTranscript = "我正在测试语音输入")
        assertEquals("我正在测试语音输入。", editor.text)
    }

    @Test
    fun `final transcript with different spacing is aligned by common prefix instead of appended`() {
        val editor = QqLikeEditor()
        val composer = StreamingComposer(editorBridge = editor, minUpdateIntervalMs = 0L)
        composer.startSession(anchorCursor = 0)

        // Streaming slices trimmed differently from the final combined transcript
        composer.onTranscriptUpdate(LiveTranscript(finalized = "字 。", interim = "我正在测试的这个", revision = 1))
        assertEquals("字 。我正在测试的这个", editor.text)

        composer.onRecordingFinished(finalRawTranscript = "字。我正在测试的 这个功能", needsPolishing = false)
        assertEquals("字。我正在测试的 这个功能", editor.text)
        assertEquals(StreamingComposer.State.Done, composer.state)
    }

    @Test
    fun `safety net removes raw draft when host ignores the deletion before committing polished text`() {
        val editor = QqLikeEditor(initialText = "A：")
        val composer = StreamingComposer(editorBridge = editor, minUpdateIntervalMs = 0L)
        composer.startSession(anchorCursor = editor.cursor)
        composer.onTranscriptUpdate(LiveTranscript(finalized = "嗯 这个 方案 可以", interim = "", revision = 1))
        composer.onRecordingFinished(finalRawTranscript = "嗯 这个 方案 可以", needsPolishing = true)

        editor.ignoreNextDelete = true
        composer.applyPolishedResult(polishedText = "这个方案可以。", fullRawTranscript = "嗯 这个 方案 可以")
        assertEquals("A：这个方案可以。", editor.text)
    }

    @Test
    fun `silent composing drop without any selection callback is healed on the next write`() {
        var now = 1_000L
        val editor = QqLikeEditor(initialText = "群：")
        val composer = StreamingComposer(editorBridge = editor, minUpdateIntervalMs = 66L, clockMs = { now })
        composer.startSession(anchorCursor = editor.cursor)

        composer.onTranscriptUpdate(LiveTranscript(finalized = "", interim = "明天开会", revision = 1))
        editor.dropComposingSpan() // no onUpdateSelection callback delivered

        now += 100L
        composer.onTranscriptUpdate(LiveTranscript(finalized = "明天开会，", interim = "记得带电脑", revision = 2))
        assertEquals("群：明天开会，记得带电脑", editor.text)

        editor.dropComposingSpan()
        now += 100L
        composer.onTranscriptUpdate(LiveTranscript(finalized = "明天开会，", interim = "记得带电脑和充电器", revision = 3))
        assertEquals("群：明天开会，记得带电脑和充电器", editor.text)
    }

    @Test
    fun `self-heal never deletes user text that happens to precede the anchor`() {
        var now = 1_000L
        // User text before the anchor ends with the same characters as the first interim
        val editor = QqLikeEditor(initialText = "我正在", ignoreComposingRegion = false)
        val composer = StreamingComposer(editorBridge = editor, minUpdateIntervalMs = 66L, clockMs = { now })
        composer.startSession(anchorCursor = editor.cursor)

        composer.onTranscriptUpdate(LiveTranscript(finalized = "", interim = "我正在", revision = 1))
        assertEquals("我正在我正在", editor.text)
        now += 100L
        // Host honors composing: interim replaced in place -> buffer legitimately ends with "我正在我正在测试"
        composer.onTranscriptUpdate(LiveTranscript(finalized = "", interim = "我正在测试", revision = 2))
        assertEquals("我正在我正在测试", editor.text)
    }

    @Test
    fun `user edits before polish still block overwriting`() {
        val editor = QqLikeEditor(initialText = "")
        val composer = StreamingComposer(editorBridge = editor, minUpdateIntervalMs = 0L)
        composer.startSession(anchorCursor = 0)
        composer.onTranscriptUpdate(LiveTranscript(finalized = "原始内容", interim = "", revision = 1))
        composer.onRecordingFinished(finalRawTranscript = "原始内容", needsPolishing = true)

        // User typed extra text after the draft
        editor.commitText("我改了", 1)
        val outcome = composer.applyPolishedResult(polishedText = "润色内容。", fullRawTranscript = "原始内容")
        assertFalse(outcome.appliedToEditor)
        assertEquals("原始内容我改了", editor.text)
    }
}
