package com.typefree.ime.v27

import com.typefree.ime.data.AppSettings
import com.typefree.ime.data.LlmConfig
import com.typefree.ime.data.PipelineMode
import com.typefree.ime.data.SttConfig
import com.typefree.ime.data.SttProtocol
import com.typefree.ime.data.VoiceIntentMode
import com.typefree.ime.ime.ComposerEditorBridge
import com.typefree.ime.ime.ImeLogic
import com.typefree.ime.ime.LiveWriteUiStatus
import com.typefree.ime.ime.StreamingComposer
import com.typefree.ime.network.AiPipelineEngine
import com.typefree.ime.network.asr.AsrSessionLimits
import com.typefree.ime.network.asr.DashScopeStreamingAsrClient
import com.typefree.ime.network.asr.GeminiLiveTranscribeClient
import com.typefree.ime.network.asr.LiveTranscript
import com.typefree.ime.network.asr.PcmRingBuffer
import com.typefree.ime.network.asr.RotatingStreamingAsrSession
import com.typefree.ime.network.asr.SessionDurationLimit
import com.typefree.ime.network.asr.StreamingAsrSession
import com.typefree.ime.network.asr.VolcengineSaucAsrClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingComposerAndLiveTranscriptTest {

    /**
     * Faithful pure-JVM implementation of Android's `BaseInputConnection` buffer, cursor,
     * and composing span semantics for testing [StreamingComposer].
     */
    private class FakeEditorBridge(
        initialText: String = "",
        initialCursor: Int = initialText.length,
        var supportsComposingRegion: Boolean = true
    ) : ComposerEditorBridge {
        val buffer = StringBuilder(initialText)
        var cursor: Int = initialCursor.coerceIn(0, buffer.length)
        var composingStart: Int = -1
        var composingEnd: Int = -1
        var batchDepth: Int = 0
        val callLog = mutableListOf<String>()

        val hasComposingRegion: Boolean
            get() = composingStart >= 0 && composingEnd >= composingStart

        val text: String
            get() = buffer.toString()

        val composingText: String
            get() = if (hasComposingRegion) buffer.substring(composingStart, composingEnd) else ""

        override fun beginBatchEdit(): Boolean {
            batchDepth += 1
            callLog += "beginBatchEdit"
            return true
        }

        override fun endBatchEdit(): Boolean {
            batchDepth = (batchDepth - 1).coerceAtLeast(0)
            callLog += "endBatchEdit"
            return batchDepth == 0
        }

        override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
            val str = text.toString()
            callLog += "setComposingText($str,$newCursorPosition)"
            val replaceStart: Int
            val replaceEnd: Int
            if (hasComposingRegion) {
                replaceStart = composingStart
                replaceEnd = composingEnd
            } else {
                replaceStart = cursor
                replaceEnd = cursor
            }
            buffer.replace(replaceStart, replaceEnd, str)
            if (str.isNotEmpty()) {
                composingStart = replaceStart
                composingEnd = replaceStart + str.length
            } else {
                composingStart = -1
                composingEnd = -1
            }
            cursor = if (newCursorPosition > 0) {
                replaceStart + str.length + (newCursorPosition - 1)
            } else {
                replaceStart + newCursorPosition
            }.coerceIn(0, buffer.length)
            return true
        }

        override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
            val str = text.toString()
            callLog += "commitText($str,$newCursorPosition)"
            val replaceStart: Int
            val replaceEnd: Int
            if (hasComposingRegion) {
                replaceStart = composingStart
                replaceEnd = composingEnd
            } else {
                replaceStart = cursor
                replaceEnd = cursor
            }
            buffer.replace(replaceStart, replaceEnd, str)
            composingStart = -1
            composingEnd = -1
            cursor = if (newCursorPosition > 0) {
                replaceStart + str.length + (newCursorPosition - 1)
            } else {
                replaceStart + newCursorPosition
            }.coerceIn(0, buffer.length)
            return true
        }

        override fun finishComposingText(): Boolean {
            callLog += "finishComposingText"
            composingStart = -1
            composingEnd = -1
            return true
        }

        override fun setComposingRegion(start: Int, end: Int): Boolean {
            callLog += "setComposingRegion($start,$end)"
            if (!supportsComposingRegion) return false
            val s = minOf(start, end).coerceIn(0, buffer.length)
            val e = maxOf(start, end).coerceIn(0, buffer.length)
            if (s == e) {
                composingStart = -1
                composingEnd = -1
            } else {
                composingStart = s
                composingEnd = e
            }
            return true
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            callLog += "deleteSurroundingText($beforeLength,$afterLength)"
            val delBeforeStart = (cursor - beforeLength.coerceAtLeast(0)).coerceAtLeast(0)
            val delAfterEnd = (cursor + afterLength.coerceAtLeast(0)).coerceAtMost(buffer.length)
            buffer.delete(cursor, delAfterEnd)
            buffer.delete(delBeforeStart, cursor)
            cursor = delBeforeStart
            composingStart = -1
            composingEnd = -1
            return true
        }

        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence {
            val start = (cursor - n.coerceAtLeast(0)).coerceAtLeast(0)
            return buffer.substring(start, cursor)
        }
    }

    // =========================================================================
    // 1. StreamingComposer Tests
    // =========================================================================

    @Test
    fun `StreamingComposer replaces interim in-place and commits finalized clauses`() {
        var nowMs = 1_000L
        val editor = FakeEditorBridge(initialText = "前缀：", initialCursor = 3)
        val composer = StreamingComposer(
            editorBridge = editor,
            minUpdateIntervalMs = 66L,
            clockMs = { nowMs }
        )

        composer.startSession(anchorCursor = editor.cursor, smartDirectMode = false)
        assertEquals(StreamingComposer.State.Live, composer.state)
        assertEquals(LiveWriteUiStatus.WRITING, composer.uiStatus)

        // Step 1: Interim "你"
        assertTrue(composer.onTranscriptUpdate(LiveTranscript(finalized = "", interim = "你", revision = 1)))
        assertEquals("前缀：你", editor.text)
        assertEquals("你", editor.composingText)
        assertEquals(4, composer.expectedCursorPosition)

        // Step 2: Self-correction in interim "你" -> "泥土" after 70ms
        nowMs += 70L
        assertTrue(composer.onTranscriptUpdate(LiveTranscript(finalized = "", interim = "泥土", revision = 2)))
        assertEquals("前缀：泥土", editor.text)
        assertEquals("泥土", editor.composingText)
        assertEquals(5, composer.expectedCursorPosition)

        // Step 3: First clause finalized ("泥土芬芳，") + new interim ("今天")
        // Finalized advancement flushes immediately even if < 66ms!
        nowMs += 10L
        assertTrue(
            composer.onTranscriptUpdate(
                LiveTranscript(finalized = "泥土芬芳，", interim = "今天", revision = 3)
            )
        )
        assertEquals("前缀：泥土芬芳，今天", editor.text)
        assertEquals("今天", editor.composingText)
        assertEquals("泥土芬芳，", composer.committedFinalText)
        assertEquals("今天", composer.currentInterimText)
    }

    @Test
    fun `StreamingComposer throttles interim updates within 66ms and skips duplicate transcripts`() {
        var nowMs = 5_000L
        val editor = FakeEditorBridge()
        val composer = StreamingComposer(
            editorBridge = editor,
            minUpdateIntervalMs = 66L,
            clockMs = { nowMs }
        )
        composer.startSession(anchorCursor = 0)

        // First flush succeeds
        assertTrue(composer.onTranscriptUpdate(LiveTranscript(finalized = "", interim = "第一", revision = 1)))
        assertEquals("第一", editor.text)

        // Identical transcript is skipped even after 100ms
        nowMs += 100L
        val callCountBeforeDuplicate = editor.callLog.size
        assertFalse(composer.onTranscriptUpdate(LiveTranscript(finalized = "", interim = "第一", revision = 2)))
        assertEquals(callCountBeforeDuplicate, editor.callLog.size)

        // Interim update only 20ms after first flush is throttled
        nowMs = 5_020L
        assertFalse(composer.onTranscriptUpdate(LiveTranscript(finalized = "", interim = "第一句话", revision = 3)))
        assertEquals("第一", editor.text)

        // Once 66ms has elapsed since 5_000L, next interim update flushes
        nowMs = 5_070L
        assertTrue(composer.onTranscriptUpdate(LiveTranscript(finalized = "", interim = "第一句话正在输入", revision = 4)))
        assertEquals("第一句话正在输入", editor.text)
    }

    @Test
    fun `StreamingComposer enters Frozen when cursor moves and commits only remainder on finish`() {
        var nowMs = 1_000L
        val editor = FakeEditorBridge()
        val composer = StreamingComposer(
            editorBridge = editor,
            clockMs = { nowMs }
        )
        composer.startSession(anchorCursor = 0)

        composer.onTranscriptUpdate(LiveTranscript(finalized = "今天天气", interim = "真不错", revision = 1))
        assertEquals("今天天气真不错", editor.text)
        assertTrue(editor.hasComposingRegion)

        // Expected cursor is at index 7; matching selection callback does NOT freeze
        composer.onUpdateSelection(newSelStart = 7, newSelEnd = 7)
        assertEquals(StreamingComposer.State.Live, composer.state)

        // User taps cursor to index 2 in host editor -> immediately solidifies composing and freezes
        editor.cursor = 2
        composer.onUpdateSelection(newSelStart = 2, newSelEnd = 2)
        assertEquals(StreamingComposer.State.Frozen, composer.state)
        assertEquals(LiveWriteUiStatus.FROZEN, composer.uiStatus)
        assertFalse(editor.hasComposingRegion)
        assertEquals("今天天气真不错", composer.writtenBeforeFreeze)

        // Subsequent live updates while Frozen do NOT touch the editor
        nowMs += 100L
        assertFalse(
            composer.onTranscriptUpdate(
                LiveTranscript(finalized = "今天天气真不错，", interim = "我们去散步吧", revision = 2)
            )
        )
        assertEquals("今天天气真不错", editor.text)

        // On mic release + dual-stage polish completion, only the unwritten remainder is inserted at current cursor (2)
        val finishNull = composer.onRecordingFinished(
            finalRawTranscript = "今天天气真不错，我们去散步吧",
            needsPolishing = true
        )
        assertNull(finishNull)
        assertEquals(StreamingComposer.State.Polishing, composer.state)

        val outcome = composer.applyPolishedResult(
            polishedText = "今天天气真不错，我们去散步吧。",
            fullRawTranscript = "今天天气真不错，我们去散步吧"
        )
        assertTrue(outcome.wasFrozen)
        assertTrue(outcome.appliedToEditor)
        assertEquals("我们去散步吧。", outcome.finalCommittedText)
        assertEquals("今天我们去散步吧。天气真不错", editor.text)
        assertEquals(StreamingComposer.State.Done, composer.state)
    }

    @Test
    fun `StreamingComposer Dual-Stage polish verifies text before cursor, replaces once, and UndoSnapshot restores raw text`() {
        var nowMs = 1_000L
        val editor = FakeEditorBridge(initialText = "备注：", initialCursor = 3)
        val composer = StreamingComposer(
            editorBridge = editor,
            clockMs = { nowMs }
        )
        composer.startSession(anchorCursor = editor.cursor)

        composer.onTranscriptUpdate(
            LiveTranscript(finalized = "嗯那个我觉得", interim = "这个方案可行", revision = 1)
        )
        assertEquals("备注：嗯那个我觉得这个方案可行", editor.text)

        // Release mic -> enters Polishing and solidifies composing text
        composer.onRecordingFinished(
            finalRawTranscript = "嗯那个我觉得这个方案可行",
            needsPolishing = true
        )
        assertEquals(StreamingComposer.State.Polishing, composer.state)
        assertFalse(editor.hasComposingRegion)
        assertEquals("备注：嗯那个我觉得这个方案可行", editor.text)

        // Stage 2 LLM returns polished text -> verifies getTextBeforeCursor and replaces via setComposingRegion + commitText
        val outcome = composer.applyPolishedResult(
            polishedText = "我认为这个方案可行。",
            fullRawTranscript = "嗯那个我觉得这个方案可行"
        )
        assertTrue(outcome.appliedToEditor)
        assertFalse(outcome.wasFrozen)
        assertEquals("嗯那个我觉得这个方案可行", outcome.rawWrittenText)
        assertEquals("我认为这个方案可行。", outcome.finalCommittedText)
        assertEquals("备注：我认为这个方案可行。", editor.text)
        assertEquals(StreamingComposer.State.Done, composer.state)

        // Verify UndoSnapshot restores the unpolished raw ASR text when user taps Undo
        val undo = outcome.undoSnapshot
        assertNotNull(undo)
        assertEquals("嗯那个我觉得这个方案可行", undo!!.replacedOriginalText)
        assertEquals("我认为这个方案可行。".length, undo.insertedTextLength)

        val (restoredBefore, restoredAfter) = ImeLogic.applyUndoToBuffer(
            beforeCursor = editor.text,
            afterCursor = "",
            snapshot = undo
        )
        assertEquals("备注：嗯那个我觉得这个方案可行", restoredBefore + restoredAfter)
    }

    @Test
    fun `StreamingComposer falls back to deleteSurroundingText when setComposingRegion returns false`() {
        val editor = FakeEditorBridge(initialText = "开头：", initialCursor = 3, supportsComposingRegion = false)
        val composer = StreamingComposer(editorBridge = editor)
        composer.startSession(anchorCursor = 3)

        composer.onTranscriptUpdate(LiveTranscript(finalized = "原始语音内容", interim = "", revision = 1))
        composer.onRecordingFinished(finalRawTranscript = "原始语音内容", needsPolishing = true)

        val outcome = composer.applyPolishedResult(
            polishedText = "润色后的正式内容。",
            fullRawTranscript = "原始语音内容"
        )
        assertTrue(outcome.appliedToEditor)
        assertEquals("开头：润色后的正式内容。", editor.text)
        assertTrue(editor.callLog.any { it.startsWith("deleteSurroundingText(6,0)") })
    }

    @Test
    fun `StreamingComposer NEVER overwrites editor when user modified text before polish returns`() {
        val editor = FakeEditorBridge(initialText = "", initialCursor = 0)
        val composer = StreamingComposer(editorBridge = editor)
        composer.startSession(anchorCursor = 0)

        composer.onTranscriptUpdate(LiveTranscript(finalized = "今天下午三点开会", interim = "", revision = 1))
        composer.onRecordingFinished(finalRawTranscript = "今天下午三点开会", needsPolishing = true)
        assertEquals("今天下午三点开会", editor.text)

        // Simulate user manually editing "三点" -> "四点" while LLM request is in-flight
        editor.buffer.replace(4, 6, "四点")
        assertEquals("今天下午四点开会", editor.text)

        val outcome = composer.applyPolishedResult(
            polishedText = "今天下午 15:00 开会。",
            fullRawTranscript = "今天下午三点开会"
        )
        assertFalse(outcome.appliedToEditor)
        assertNull(outcome.undoSnapshot)
        // User's manual edit is strictly preserved!
        assertEquals("今天下午四点开会", editor.text)
    }

    @Test
    fun `StreamingComposer SMART direct mode transitions Finishing to Done with zero replacement jump`() {
        val editor = FakeEditorBridge()
        val composer = StreamingComposer(editorBridge = editor)
        composer.startSession(anchorCursor = 0, smartDirectMode = true)

        composer.onTranscriptUpdate(LiveTranscript(finalized = "这是一段直接润色好的", interim = "智能听写文本。", revision = 1))
        assertEquals("这是一段直接润色好的智能听写文本。", editor.text)

        val callCountBeforeFinish = editor.callLog.size
        val outcome = composer.onRecordingFinished(
            finalRawTranscript = "这是一段直接润色好的智能听写文本。",
            needsPolishing = false
        )
        assertNotNull(outcome)
        assertTrue(outcome!!.appliedToEditor)
        assertEquals(StreamingComposer.State.Done, composer.state)
        assertEquals("这是一段直接润色好的智能听写文本。", editor.text)
        assertFalse(editor.hasComposingRegion)

        // Verify no setComposingRegion or deleteSurroundingText was invoked after finish
        val postCalls = editor.callLog.subList(callCountBeforeFinish, editor.callLog.size)
        assertFalse(postCalls.any { it.startsWith("setComposingRegion") || it.startsWith("deleteSurroundingText") })
    }

    @Test
    fun `shouldEnableLiveWrite checks all 6 gating conditions including blacklist and floating bubble`() {
        val baseSettings = AppSettings(
            liveWriteToEditor = true,
            pipelineMode = PipelineMode.DUAL_STAGE,
            sttConfig = SttConfig(protocol = SttProtocol.VOLCENGINE_SAUC_WS)
        )

        // All conditions met -> true
        assertTrue(
            StreamingComposer.shouldEnableLiveWrite(
                settings = baseSettings,
                intentMode = VoiceIntentMode.DICTATE,
                effectiveSelectedText = "",
                hasStreamingSession = true,
                targetPackage = "com.tencent.mm",
                isFloatingBubbleChannel = false
            )
        )

        // 1. Switch off -> false
        assertFalse(
            StreamingComposer.shouldEnableLiveWrite(
                settings = baseSettings.copy(liveWriteToEditor = false),
                intentMode = VoiceIntentMode.DICTATE,
                effectiveSelectedText = "",
                hasStreamingSession = true,
                targetPackage = "com.tencent.mm"
            )
        )

        // 2. Non-DICTATE intent (e.g. TRANSLATE, EDIT_SELECTION, COMMAND_WRITE) -> false
        assertFalse(
            StreamingComposer.shouldEnableLiveWrite(
                settings = baseSettings,
                intentMode = VoiceIntentMode.TRANSLATE,
                effectiveSelectedText = "",
                hasStreamingSession = true,
                targetPackage = "com.tencent.mm"
            )
        )

        // 3. Selected text non-blank -> false
        assertFalse(
            StreamingComposer.shouldEnableLiveWrite(
                settings = baseSettings,
                intentMode = VoiceIntentMode.DICTATE,
                effectiveSelectedText = "被选中的文字",
                hasStreamingSession = true,
                targetPackage = "com.tencent.mm"
            )
        )

        // 4. Non-streaming session or MULTIMODAL_DIRECT -> false
        assertFalse(
            StreamingComposer.shouldEnableLiveWrite(
                settings = baseSettings,
                intentMode = VoiceIntentMode.DICTATE,
                effectiveSelectedText = "",
                hasStreamingSession = false,
                targetPackage = "com.tencent.mm"
            )
        )
        assertFalse(
            StreamingComposer.shouldEnableLiveWrite(
                settings = baseSettings.copy(pipelineMode = PipelineMode.MULTIMODAL_DIRECT),
                intentMode = VoiceIntentMode.DICTATE,
                effectiveSelectedText = "",
                hasStreamingSession = true,
                targetPackage = "com.tencent.mm"
            )
        )

        // 5. Blacklisted terminal package -> false
        assertFalse(
            StreamingComposer.shouldEnableLiveWrite(
                settings = baseSettings,
                intentMode = VoiceIntentMode.DICTATE,
                effectiveSelectedText = "",
                hasStreamingSession = true,
                targetPackage = "com.termux"
            )
        )

        // 6. Floating bubble accessibility channel -> false
        assertFalse(
            StreamingComposer.shouldEnableLiveWrite(
                settings = baseSettings,
                intentMode = VoiceIntentMode.DICTATE,
                effectiveSelectedText = "",
                hasStreamingSession = true,
                targetPackage = "com.tencent.mm",
                isFloatingBubbleChannel = true
            )
        )
    }

    // =========================================================================
    // 2. Provider LiveTranscript Parsing Tests
    // =========================================================================

    @Test
    fun `VolcengineSaucAsrClient splits utterances by definite flag and falls back to result text`() {
        val jsonWithUtterances = """
            {
              "result": {
                "text": "第一句已经定稿。第二句也定稿了，第三句还在说",
                "utterances": [
                  {"text": "第一句已经定稿。", "definite": true},
                  {"text": "第二句也定稿了，", "definite": true},
                  {"text": "第三句还在说", "definite": false}
                ]
              }
            }
        """.trimIndent()

        val parsed = VolcengineSaucAsrClient.parseLiveTranscriptFromResultJson(
            rawJson = jsonWithUtterances,
            previousRevision = 4L,
            isFinalPackage = false
        )
        assertNotNull(parsed)
        assertEquals("第一句已经定稿。第二句也定稿了，", parsed!!.finalized)
        assertEquals("第三句还在说", parsed.interim)
        assertEquals(5L, parsed.revision)
        assertEquals("第一句已经定稿。第二句也定稿了，第三句还在说", parsed.fullText)

        // Final server package promotes everything to finalized
        val parsedFinal = VolcengineSaucAsrClient.parseLiveTranscriptFromResultJson(
            rawJson = jsonWithUtterances,
            previousRevision = parsed.revision,
            isFinalPackage = true
        )
        assertEquals("第一句已经定稿。第二句也定稿了，第三句还在说", parsedFinal!!.finalized)
        assertEquals("", parsedFinal.interim)

        // Fallback when utterances array is absent
        val jsonFallback = """{"result": {"text": "仅返回整句文本"}}"""
        val fallbackParsed = VolcengineSaucAsrClient.parseLiveTranscriptFromResultJson(
            rawJson = jsonFallback,
            previousRevision = 0L,
            isFinalPackage = false
        )
        assertEquals("", fallbackParsed!!.finalized)
        assertEquals("仅返回整句文本", fallbackParsed.interim)
    }

    @Test
    fun `DashScopeStreamingAsrClient aggregates sentence_end finalized clauses and active interim sentence`() {
        val events = listOf(
            """{"header":{"event":"result-generated"},"payload":{"output":{"sentence":{"sentence_id":1,"begin_time":0,"text":"你好"}}}}""",
            """{"header":{"event":"result-generated"},"payload":{"output":{"sentence":{"sentence_id":1,"begin_time":0,"end_time":1200,"sentence_end":true,"text":"你好，世界。"}}}}""",
            """{"header":{"event":"result-generated"},"payload":{"output":{"sentence":{"sentence_id":2,"begin_time":1250,"sentence_end":false,"text":"正在输入第二句"}}}}"""
        )

        val transcript = DashScopeStreamingAsrClient.parseAndAggregateLiveTranscript(events)
        assertEquals("你好，世界。", transcript.finalized)
        assertEquals("正在输入第二句", transcript.interim)
        assertEquals("你好，世界。正在输入第二句", transcript.fullText)
    }

    @Test
    fun `GeminiLiveTranscribeClient splits interim exceeding 80 chars at nearest punctuation and finalizes on turnComplete`() {
        val clauseA = "这是一段超过八十个字符的流式语音识别测试文本，包含多个自然标点符号，用于验证当未定稿内容过长时能够自动在标点处切分！"
        val clauseB = "这里是紧接着标点之后的后半段内容，长度相加已经超过八十个字符上限，应当保留在未定稿区域继续滚动更新"
        val over80Text = clauseA + clauseB
        assertTrue("Test input must exceed 80 chars, was ${over80Text.length}", over80Text.length > 80)

        var current = LiveTranscript()
        current = GeminiLiveTranscribeClient.reduceLiveTranscript(
            current = current,
            inputChunk = over80Text,
            isInterimChunk = false,
            turnComplete = false,
            generationComplete = false
        )

        assertTrue("Finalized should not be empty after >80 char overflow", current.finalized.isNotEmpty())
        assertTrue(
            "Finalized split should end at punctuation",
            current.finalized.last() in "，。？！；、,.?!"
        )
        assertTrue("Remaining interim must be <= 80 chars", current.interim.length <= 80)
        assertEquals(over80Text, current.fullText)

        // When turnComplete arrives, remaining interim is promoted into finalized
        val completed = GeminiLiveTranscribeClient.reduceLiveTranscript(
            current = current,
            inputChunk = null,
            isInterimChunk = false,
            turnComplete = true,
            generationComplete = false
        )
        assertEquals(over80Text, completed.finalized)
        assertEquals("", completed.interim)
    }

    // =========================================================================
    // 3. Long Dictation: Chunked Polishing, PcmRingBuffer, and Session Rotation
    // =========================================================================

    @Test
    fun `AiPipelineEngine splitLongTextForChunkedPolish splits over 600 char text on sentence boundaries and preserves full text`() {
        val shortText = "短文本不需要切分，直接单路润色即可。"
        assertEquals(listOf(shortText), AiPipelineEngine.splitLongTextForChunkedPolish(shortText, thresholdChars = 600))

        val sentenceUnit = "这是用于验证长文本分块润色的标准测试句子，每句话都带有明确的句号边界。"
        val longText = buildString {
            repeat(40) { idx ->
                append("第${idx + 1}句：").append(sentenceUnit)
                if ((idx + 1) % 10 == 0) append("\n")
            }
        }
        assertTrue("Long text should exceed 1200 chars, was ${longText.length}", longText.length > 1200)

        val chunks = AiPipelineEngine.splitLongTextForChunkedPolish(
            text = longText,
            thresholdChars = 600
        )
        assertTrue("Expected at least 3 chunks, got ${chunks.size}", chunks.size >= 3)
        for (i in 0 until chunks.size - 1) {
            assertTrue("Chunk $i length ${chunks[i].length} should be <= 600", chunks[i].length <= 600)
            assertTrue("Chunk $i length ${chunks[i].length} should be >= 300", chunks[i].length >= 300)
        }
        assertEquals(longText, chunks.joinToString(""))
    }

    @Test
    fun `PcmRingBuffer retains exact trailing 2-second 64000-byte window across wraparound`() {
        assertEquals(64_000, PcmRingBuffer.DEFAULT_CAPACITY_BYTES)

        val ring = PcmRingBuffer(capacityBytes = 10)
        ring.write(byteArrayOf(1, 2, 3, 4, 5, 6))
        assertEquals(6, ring.size)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), ring.snapshot())

        // Write 7 more bytes -> wraps around and keeps the latest 10 bytes (4..13)
        ring.write(byteArrayOf(7, 8, 9, 10, 11, 12, 13))
        assertEquals(10, ring.size)
        assertArrayEquals(byteArrayOf(4, 5, 6, 7, 8, 9, 10, 11, 12, 13), ring.snapshot())

        val chunks = ring.snapshotChunks(chunkSizeBytes = 4)
        assertEquals(3, chunks.size)
        assertArrayEquals(byteArrayOf(4, 5, 6, 7), chunks[0])
        assertArrayEquals(byteArrayOf(8, 9, 10, 11), chunks[1])
        assertArrayEquals(byteArrayOf(12, 13), chunks[2])
    }

    @Test
    fun `RotatingStreamingAsrSession rotates seamlessly at 85 percent soft boundary and replays ring buffer on 95 percent hard boundary`() = runBlocking {
        class FakeDelegateSession : StreamingAsrSession {
            val mutableTranscript = MutableStateFlow(LiveTranscript())
            val mutablePartial = MutableStateFlow("")
            val receivedPcm = mutableListOf<ByteArray>()
            var wasCancelled = false

            override val transcript: StateFlow<LiveTranscript> = mutableTranscript
            override val partialText: StateFlow<String> = mutablePartial

            override fun sendPcmFrame(pcm16Le: ByteArray) {
                receivedPcm += pcm16Le.copyOf()
            }

            override suspend fun finishAndAwaitFinal(timeoutMs: Long): Result<String> {
                return Result.success(mutableTranscript.value.fullText)
            }

            override fun cancel() {
                wasCancelled = true
            }
        }

        var nowMs = 0L
        val createdSessions = mutableListOf<FakeDelegateSession>()
        val limit = SessionDurationLimit(
            maxSessionDurationMs = 1_000L,
            softRotateThresholdMs = 850L,
            hardRotateThresholdMs = 950L
        )
        val rotating = RotatingStreamingAsrSession(
            sessionFactory = {
                FakeDelegateSession().also { createdSessions += it }
            },
            limit = limit,
            ringBufferCapacityBytes = 8,
            clockMs = { nowMs }
        )

        assertEquals(1, createdSessions.size)
        assertEquals(600_000L, AsrSessionLimits.forProtocol(SttProtocol.ALIYUN_DASHSCOPE_WS).maxSessionDurationMs)
        assertEquals(600_000L, AsrSessionLimits.forProtocol(SttProtocol.VOLCENGINE_SAUC_WS).maxSessionDurationMs)
        assertEquals(900_000L, AsrSessionLimits.forProtocol(SttProtocol.GEMINI_LIVE_WS).maxSessionDurationMs)

        // Session 1 reaches 860ms (>= 85% soft threshold) with a clean finalized sentence boundary
        nowMs = 860L
        createdSessions[0].mutableTranscript.value = LiveTranscript(
            finalized = "第一段已完成。",
            interim = "",
            revision = 1
        )
        rotating.sendPcmFrame(byteArrayOf(10, 20))

        assertEquals(1, rotating.rotationCount)
        assertEquals(2, createdSessions.size)
        assertTrue(createdSessions[0].wasCancelled)

        // Session 2 emits new finalized + interim; rotating session preserves Session 1's prefix
        createdSessions[1].mutableTranscript.value = LiveTranscript(
            finalized = "第二段定稿，",
            interim = "还在说下半句",
            revision = 1
        )
        val snap = rotating.transcript.value
        assertEquals("第一段已完成。第二段定稿，", snap.finalized)
        assertEquals("还在说下半句", snap.interim)

        // Advance Session 2 past 95% hard threshold while interim is still non-empty -> forces rotation with ring-buffer replay
        rotating.sendPcmFrame(byteArrayOf(1, 2, 3, 4))
        nowMs += 960L
        rotating.sendPcmFrame(byteArrayOf(5, 6))

        assertEquals(2, rotating.rotationCount)
        assertEquals(3, createdSessions.size)
        // Session 3 should have received the replayed ring-buffer bytes [10,20,1,2,3,4] followed by [5,6]
        val totalReceivedBySession3 = createdSessions[2].receivedPcm.fold(ByteArray(0)) { acc, b -> acc + b }
        assertArrayEquals(byteArrayOf(10, 20, 1, 2, 3, 4, 5, 6), totalReceivedBySession3)

        createdSessions[2].mutableTranscript.value = LiveTranscript(
            finalized = "最终收尾。",
            interim = "",
            revision = 1
        )
        val finalMerged = rotating.finishAndAwaitFinal(500L).getOrThrow()
        assertEquals("第一段已完成。第二段定稿，最终收尾。", finalMerged)
    }
}
