package com.typefree.ime.v27

import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.typefree.ime.data.AppRepository
import com.typefree.ime.data.AppSettings
import com.typefree.ime.data.DefaultPresets
import com.typefree.ime.data.DictionaryEntry
import com.typefree.ime.data.HistoryRetention
import com.typefree.ime.data.InputScheme
import com.typefree.ime.data.LlmConfig
import com.typefree.ime.data.PipelineMode
import com.typefree.ime.data.SnippetEntry
import com.typefree.ime.data.ThinkingMode
import com.typefree.ime.data.ToneStyle
import com.typefree.ime.ime.ImeHostCallbacks
import com.typefree.ime.ime.ImeLogic
import com.typefree.ime.ime.ImeUiState
import com.typefree.ime.ime.KeyboardTabMode
import com.typefree.ime.ime.TypingController
import com.typefree.ime.ime.UndoSnapshot
import com.typefree.ime.ime.engine.EngineResult
import com.typefree.ime.ime.engine.EngineState
import com.typefree.ime.ime.engine.FlypyInputEngine
import com.typefree.ime.ime.engine.InputEngine
import com.typefree.ime.ime.engine.KeyInput
import com.typefree.ime.ime.flypy.FlypyDictionary
import com.typefree.ime.ime.flypy.FlypyEngine
import com.typefree.ime.ime.predict.AiSentenceCompleter
import com.typefree.ime.ime.predict.PredictionCoordinator
import com.typefree.ime.ime.predict.PredictionStore
import com.typefree.ime.ime.predict.StaticPredictor
import com.typefree.ime.network.AiPipelineEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class PredictionAndIncognitoTest {

    private fun buildSampleFlypyDictionary(): FlypyDictionary {
        val mainTsv = """
            jntm	今天
            xxwu	下午
            womn	我们
            keyi	可以
            khui	开会
            tlun	讨论
            fgan	方案
            uuln	上线
            ;w	？
        """.trimIndent()
        val dzTsv = """
            jntm	今
        """.trimIndent()
        return FlypyDictionary.loadFromStreams(
            ByteArrayInputStream(mainTsv.toByteArray(Charsets.UTF_8)),
            ByteArrayInputStream(dzTsv.toByteArray(Charsets.UTF_8))
        )
    }

    private class FakeImeHost(
        override val scope: CoroutineScope,
        initialState: ImeUiState = ImeUiState(
            tabMode = KeyboardTabMode.QWERTY,
            isFlypyChineseMode = true
        ),
        var currentSettings: AppSettings = AppSettings(
            inputScheme = InputScheme.FLYPY_TABLE,
            predictionEnabled = true,
            aiCompletionEnabled = false
        ),
        override val aiEngine: AiPipelineEngine = AiPipelineEngine()
    ) : ImeHostCallbacks {

        override val repository: AppRepository
            get() = throw UnsupportedOperationException("FakeImeHost uses safeSettings fallback")

        override val stateFlow: MutableStateFlow<ImeUiState> = MutableStateFlow(initialState)

        val committedTexts = mutableListOf<String>()
        var surroundingBuffer: String = ""
        var autoLearnTriggeredCount = 0
        var lastDictationBaseline: String? = null
        var clipboardSyncTriggeredCount = 0

        override fun currentInputConnection(): InputConnection? = null
        override fun performKeyFeedback(strong: Boolean) {}
        override fun performHaptic(strong: Boolean) {}
        override fun showQuickActionToast(message: String) {}

        override fun commitDirectText(
            text: String,
            recordUndo: Boolean,
            markManualEdit: Boolean,
            playFeedback: Boolean
        ) {
            committedTexts.add(text)
            surroundingBuffer += text
        }

        override fun pushUndoSnapshot(snapshot: UndoSnapshot?) {}
        override fun peekTopUndoSnapshot(): UndoSnapshot? = null
        override fun recordManualEditTouch() {}

        override fun tryAutoLearnFromRecentEdit() {
            if (stateFlow.value.isIncognitoMode) {
                clearAutoLearnBaseline()
                return
            }
            autoLearnTriggeredCount++
        }

        override fun onDictationCommittedForAutoLearn(fullBufferAfterCommit: String) {
            if (stateFlow.value.isIncognitoMode) return
            lastDictationBaseline = fullBufferAfterCommit
        }

        override fun clearAutoLearnBaseline() {
            lastDictationBaseline = null
        }

        override fun readSelectedTextSafely(): String = ""

        override fun readSurroundingBeforeCursor(maxChars: Int): String {
            return surroundingBuffer.takeLast(maxChars)
        }

        override fun readFullEditorTextSafely(): String = surroundingBuffer
        override fun readSystemClipboardText(): String = ""

        override fun syncSystemClipboardIfNeeded(allowFreshBanner: Boolean) {
            if (stateFlow.value.isIncognitoMode) return
            clipboardSyncTriggeredCount++
        }

        override fun archiveWavForHistory(wavFile: File, retention: HistoryRetention): File = wavFile
        override fun getLastRecordingWavFile(): File = File("dummy.wav")
        override fun hasRetryWavFile(): Boolean = false
    }

    // =========================================================================
    // 1. PredictionStore L2 Bigram Learning, Frequency Ranking, Filtering & Merging with L1
    // =========================================================================

    @Test
    fun `PredictionStore learns bigrams, accumulates counts, ranks by count then recency, and filters invalid tokens`() {
        val store = PredictionStore.inMemory()

        // Learn "今天" -> "下午" (count = 1), "今天" -> "晚上" (count = 2), "今天" -> "上线" (count = 2, newer)
        assertTrue(store.recordBigram("今天", "下午", nowMs = 1_000L))
        assertTrue(store.recordBigram("今天", "晚上", nowMs = 2_000L))
        assertTrue(store.recordBigram("今天", "晚上", nowMs = 3_000L))
        assertTrue(store.recordBigram("今天", "上线", nowMs = 2_500L))
        assertTrue(store.recordBigram("今天", "上线", nowMs = 4_000L))

        assertEquals(2, store.getBigramCount("今天", "上线"))
        assertEquals(2, store.getBigramCount("今天", "晚上"))
        assertEquals(1, store.getBigramCount("今天", "下午"))

        val ranked = store.queryNextWords("今天", limit = 5)
        // "上线" (count=2, lastUsed=4000) > "晚上" (count=2, lastUsed=3000) > "下午" (count=1, lastUsed=1000)
        assertEquals(listOf("上线", "晚上", "下午"), ranked)

        // Filtering rules: ignore pure whitespace, pure punctuation, single-char ASCII/digit/control, and self-repeat
        assertFalse(store.recordBigram("今天", "   "))
        assertFalse(store.recordBigram("   ", "下午"))
        assertFalse(store.recordBigram("今天", "，"))
        assertFalse(store.recordBigram("。", "下午"))
        assertFalse(store.recordBigram("今天", "?!"))
        assertFalse(store.recordBigram("今天", "a"))
        assertFalse(store.recordBigram("今天", "9"))
        assertFalse(store.recordBigram("今天", "\n"))
        assertFalse(store.recordBigram("今天", "今天"))

        assertEquals(3, store.totalBigramCount())
    }

    @Test
    fun `PredictionCoordinator merges L2 personal bigrams before L1 StaticPredictor and deduplicates`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val store = PredictionStore.inMemory()
            val staticPredictor = StaticPredictor()
            val stateFlow = MutableStateFlow(
                ImeUiState(tabMode = KeyboardTabMode.QWERTY, isFlypyChineseMode = true)
            )

            // L1 StaticPredictor for "今天" starts with ["下午", "晚上", "早上", ...]
            val l1ForToday = staticPredictor.predictWords("今天", limit = 6)
            assertTrue(l1ForToday.isNotEmpty())
            assertEquals("下午", l1ForToday.first())

            // Record personal L2 bigrams: "今天" -> "发版" (x2), "今天" -> "晚上" (x1, overlaps with L1!)
            store.recordBigram("今天", "发版", nowMs = 100L)
            store.recordBigram("今天", "发版", nowMs = 200L)
            store.recordBigram("今天", "晚上", nowMs = 300L)

            val coordinator = PredictionCoordinator(
                scope = scope,
                stateFlow = stateFlow,
                predictionStore = store,
                staticPredictor = staticPredictor,
                maxCandidates = 8
            )

            val merged = coordinator.mergePredictions("今天", limit = 8).map { it.text }
            // 1. L2 personal words ("发版", "晚上") MUST come first
            assertEquals("发版", merged[0])
            assertEquals("晚上", merged[1])
            // 2. L1 static words follow ("下午", "早上", ...), with "晚上" deduplicated
            assertEquals("下午", merged[2])
            assertEquals(merged.distinct().size, merged.size)
            assertFalse(merged.contains("今天"))

            // Also verify StaticPredictor TSV extension loading
            val customTsv = "开源\t大模型\t社区\n"
            val loaded = staticPredictor.loadFromTsvStream(
                ByteArrayInputStream(customTsv.toByteArray(Charsets.UTF_8))
            )
            assertEquals(1, loaded)
            val openSourcePreds = staticPredictor.predictWords("开源", limit = 4)
            assertEquals("大模型", openSourcePreds[0])
            assertEquals("社区", openSourcePreds[1])
        } finally {
            scope.cancel()
        }
    }

    // =========================================================================
    // 2. PredictionCoordinator Lifecycle: Commit Trigger, Punctuation/Backspace/Panel/Dismiss/4s Timeout & Chain Selection
    // =========================================================================

    @Test
    fun `PredictionCoordinator triggers on commit, chains prediction taps, and exits on punctuation, backspace, panel switch, dismiss button, and 4s idle timeout`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val host = FakeImeHost(scope = scope)
            val flypyEngine = FlypyInputEngine(FlypyEngine(buildSampleFlypyDictionary()))
            val controller = TypingController(
                host = host,
                flypyInputEngine = flypyEngine,
                predictionStore = PredictionStore.inMemory(),
                staticPredictor = StaticPredictor(),
                predictionIdleDismissMs = 120L // Shortened for fast deterministic real-time test, plus 4000L constant verification
            )

            assertEquals(4_000L, PredictionCoordinator.IDLE_DISMISS_MS)

            // Step 1: Type 4-code unique word "jntm" -> auto-commits "今天"
            controller.commitQwertyChar("j")
            controller.commitQwertyChar("n")
            controller.commitQwertyChar("t")
            controller.commitQwertyChar("m")

            assertEquals(listOf("今天"), host.committedTexts)
            assertFalse(host.stateFlow.value.isFlypyComposing)
            assertTrue(host.stateFlow.value.predictionCandidates.isNotEmpty())
            assertTrue(host.stateFlow.value.isPredictionBarVisible)
            assertEquals("今天", controller.predictionCoordinator.lastCommittedWord)

            // Step 2: Chain-select 1st prediction candidate ("下午") via selectPredictionCandidate(0)
            val firstPred = host.stateFlow.value.predictionCandidates[0].text
            assertEquals("下午", firstPred)
            controller.selectPredictionCandidate(0)

            // Verify "下午" was committed, bigram "今天" -> "下午" was learned in L2, and predictions advanced to "下午"
            assertEquals(listOf("今天", "下午"), host.committedTexts)
            assertEquals(1, controller.predictionStore.getBigramCount("今天", "下午"))
            assertEquals("下午", controller.predictionCoordinator.lastCommittedWord)
            assertTrue(host.stateFlow.value.predictionCandidates.isNotEmpty())
            assertEquals("好", host.stateFlow.value.predictionCandidates[0].text)

            // Step 3: Starting new composing code ('k') immediately hides prediction bar while keeping lastCommittedWord
            controller.commitQwertyChar("k")
            assertTrue(host.stateFlow.value.isFlypyComposing)
            assertTrue(host.stateFlow.value.predictionCandidates.isEmpty())
            assertFalse(host.stateFlow.value.isPredictionBarVisible)
            assertEquals("下午", controller.predictionCoordinator.lastCommittedWord)

            // Finish typing "khui" -> auto-commits "开会" and records bigram "下午" -> "开会"
            controller.commitQwertyChar("h")
            controller.commitQwertyChar("u")
            controller.commitQwertyChar("i")
            assertEquals(1, controller.predictionStore.getBigramCount("下午", "开会"))
            assertTrue(host.stateFlow.value.predictionCandidates.isNotEmpty())

            // Step 4: Clicking '×' dismiss button (dismissPredictions) clears predictions and restores top bar
            controller.dismissPredictions()
            assertTrue(host.stateFlow.value.predictionCandidates.isEmpty())
            assertFalse(host.stateFlow.value.isPredictionBarVisible)

            // Step 5: Inputting punctuation ('，' via handleCommaOrPeriodKey) exits predictions AND breaks lastCommittedWord chain
            controller.predictionCoordinator.onWordCommitted("讨论", isComposing = false)
            assertTrue(host.stateFlow.value.predictionCandidates.isNotEmpty())
            assertEquals("讨论", controller.predictionCoordinator.lastCommittedWord)

            controller.handleCommaOrPeriodKey(isComma = true)
            assertTrue(host.stateFlow.value.predictionCandidates.isEmpty())
            assertNull(controller.predictionCoordinator.lastCommittedWord)

            // Committing next word after punctuation must NOT record bigram with pre-punctuation "讨论"
            controller.predictionCoordinator.onWordCommitted("方案", isComposing = false)
            assertEquals(0, controller.predictionStore.getBigramCount("讨论", "方案"))
            assertTrue(host.stateFlow.value.predictionCandidates.isNotEmpty())

            // Step 6: Pressing Backspace (deleteBackward) immediately exits predictions and resets chain
            controller.deleteBackward()
            assertTrue(host.stateFlow.value.predictionCandidates.isEmpty())
            assertNull(controller.predictionCoordinator.lastCommittedWord)

            // Step 7: Switching panels (e.g. opening Clipboard or switching TabMode) immediately exits predictions
            controller.predictionCoordinator.onWordCommitted("我们", isComposing = false)
            assertTrue(host.stateFlow.value.predictionCandidates.isNotEmpty())
            host.stateFlow.update { it.copy(isClipboardOpen = true) }
            assertTrue(host.stateFlow.value.predictionCandidates.isEmpty())
            host.stateFlow.update { it.copy(isClipboardOpen = false) }

            // Step 8: Idle timeout automatically clears predictions and aiCompletionSuggestion
            controller.predictionCoordinator.onWordCommitted("今天", isComposing = false)
            assertTrue(host.stateFlow.value.predictionCandidates.isNotEmpty())
            delay(180L)
            assertTrue(host.stateFlow.value.predictionCandidates.isEmpty())
            assertNull(host.stateFlow.value.aiCompletionSuggestion)
        } finally {
            scope.cancel()
        }
    }

    // =========================================================================
    // 3. AiSentenceCompleter: 350ms Debounce, Cancel on Keystroke, LRU Cache Hit, and Adopting Completion
    // =========================================================================

    @Test
    fun `AiSentenceCompleter debounces 350ms, cancels on new keystroke during debounce, and hits LRU cache without repeat network call`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val networkCalls = AtomicInteger(0)
            val completer = AiSentenceCompleter(
                scope = scope,
                debounceMs = 150L,
                cacheCapacity = 32,
                executor = { _, style, context, _ ->
                    networkCalls.incrementAndGet()
                    Result.success("下午三点同步(${style.id.take(4)}:${context.takeLast(2)})")
                }
            )
            assertEquals(350L, AiSentenceCompleter.DEBOUNCE_MS)

            val enabledSettings = AppSettings(aiCompletionEnabled = true)
            val workStyle = ToneStyle(
                id = "work_collab",
                name = "职场干练",
                emoji = "💼",
                summary = "",
                promptInstruction = ""
            )

            // 1. Schedule request, then press a new key at 50ms (inside debounce window) -> cancelled!
            var receivedSuggestion: String? = null
            completer.scheduleCompletion(
                contextBeforeCursor = "王总您好，我们今天",
                settings = enabledSettings,
                activeStyle = workStyle,
                onResult = { receivedSuggestion = it }
            )
            assertTrue(completer.isPending)
            delay(50L)
            completer.cancel() // Simulates user pressing a new key before debounce elapses
            delay(160L)

            assertEquals(0, networkCalls.get())
            assertNull(receivedSuggestion)

            // 2. Schedule request and let debounce complete -> triggers network call once
            val job = completer.scheduleCompletion(
                contextBeforeCursor = "王总您好，我们今天",
                settings = enabledSettings,
                activeStyle = workStyle,
                onResult = { receivedSuggestion = it }
            )
            assertNotNull(job)
            job?.join()

            assertEquals(1, networkCalls.get())
            assertEquals("下午三点同步(work:今天)", receivedSuggestion)
            assertEquals(1, completer.cacheSize())

            // 3. Request again with the same (contextBeforeCursor <= 200 chars, style.id) -> LRU cache hit!
            var secondReceived: String? = null
            val cachedJob = completer.scheduleCompletion(
                contextBeforeCursor = "王总您好，我们今天",
                settings = enabledSettings,
                activeStyle = workStyle,
                onResult = { secondReceived = it }
            )
            assertNull(cachedJob) // Returned immediately from LRU cache
            assertEquals("下午三点同步(work:今天)", secondReceived)
            assertEquals(1, networkCalls.get()) // Zero additional network calls!

            // 4. Verify chip display truncation (`✦ ` prefix + max 12 chars before `…`) and 20-char max completion cap
            val chipLabel = AiSentenceCompleter.formatChipPreview("下午三点在飞书开会讨论一下新版本方案")
            assertEquals("✦ 下午三点在飞书开会讨论一…", chipLabel)
            val capped20 = AiSentenceCompleter.cleanCompletionOutput("这是一段超过二十个汉字的超长续写句子测试内容")
            assertEquals(20, capped20.length)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `TypingController adopts L3 AI completion suggestion in full and clears prediction bar`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val host = FakeImeHost(
                scope = scope,
                currentSettings = AppSettings(
                    predictionEnabled = true,
                    aiCompletionEnabled = true
                )
            )
            val completer = AiSentenceCompleter(
                scope = scope,
                debounceMs = 0L,
                executor = { _, _, _, _ ->
                    Result.success("“今天下午三点把方案发给大家审阅”")
                }
            )
            val controller = TypingController(
                host = host,
                predictionStore = PredictionStore.inMemory(),
                staticPredictor = StaticPredictor(),
                aiSentenceCompleter = completer,
                predictionIdleDismissMs = 4_000L
            )
            // Inject settings into coordinator via custom completer trigger
            completer.putCached("今天", DefaultPresets.defaultStyles().first().id, "下午三点把方案发给大家审阅")
            host.stateFlow.update {
                it.copy(
                    predictionCandidates = controller.staticPredictor.predict("今天", 4),
                    aiCompletionSuggestion = "下午三点把方案发给大家审阅"
                )
            }
            assertTrue(host.stateFlow.value.isPredictionBarVisible)

            // User clicks the AI completion chip -> commits full text and clears prediction bar
            controller.selectAiCompletion()
            assertEquals(listOf("下午三点把方案发给大家审阅"), host.committedTexts)
            assertNull(host.stateFlow.value.aiCompletionSuggestion)
            assertTrue(host.stateFlow.value.predictionCandidates.isEmpty())
            assertFalse(host.stateFlow.value.isPredictionBarVisible)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `AiPipelineEngine completeSentence sends last 200 chars, style, and hotwords and strips prefix overlap`() = runBlocking {
        var capturedBody = ""
        val mockHttp = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val req = chain.request()
                val buf = Buffer()
                req.body?.writeTo(buf)
                capturedBody = buf.readUtf8()

                val respJson = """
                    {
                      "choices": [
                        {
                          "message": {
                            "content": "“我们今天下午一起评审一下新版本方案”"
                          }
                        }
                      ]
                    }
                """.trimIndent()
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(respJson.toResponseBody("application/json".toMediaType()))
                    .build()
            })
            .build()

        val engine = AiPipelineEngine(httpClient = mockHttp)
        val settings = AppSettings(
            pipelineMode = PipelineMode.DUAL_STAGE,
            llmConfig = LlmConfig(
                enabled = true,
                baseUrl = "https://api.deepseek.com/v1",
                apiKey = "sk-test-key",
                model = "deepseek-chat",
                thinkingMode = ThinkingMode.DISABLED
            ),
            dictionary = listOf(
                DictionaryEntry(term = "TypeFree", wrongVariants = "太普利斯")
            )
        )
        val style = DefaultPresets.defaultStyles().first { it.id == "work_collab" }
        val longPrefix = "前文填充".repeat(60) + "各位同事，我们今天下午"

        val result = engine.completeSentence(
            settings = settings,
            activeStyle = style,
            contextBeforeCursor = longPrefix,
            targetAppName = "飞书"
        )
        assertTrue(result.isSuccess)
        // Overlapping prefix "我们今天下午" and wrapping quotes are automatically stripped!
        assertEquals("一起评审一下新版本方案", result.getOrNull())
        assertTrue(capturedBody.contains("职场干练"))
        assertTrue(capturedBody.contains("TypeFree"))
    }

    // =========================================================================
    // 4. Incognito Mode (IME_FLAG_NO_PERSONALIZED_LEARNING) Comprehensive Protection
    // =========================================================================

    @Test
    fun `Incognito mode detects IME_FLAG_NO_PERSONALIZED_LEARNING and password fields, and blocks PredictionStore writes, Engine user dict, AutoLearn, and Clipboard sync`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            // 1. Verify ImeLogic.isIncognitoImeOptions detection
            assertFalse(
                ImeLogic.isIncognitoImeOptions(
                    imeOptions = EditorInfo.IME_ACTION_SEND,
                    inputType = InputType.TYPE_CLASS_TEXT
                )
            )
            assertTrue(
                ImeLogic.isIncognitoImeOptions(
                    imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING,
                    inputType = InputType.TYPE_CLASS_TEXT
                )
            )
            assertTrue(
                ImeLogic.isIncognitoImeOptions(
                    imeOptions = EditorInfo.IME_ACTION_DONE,
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                )
            )
            assertTrue(
                ImeLogic.isIncognitoImeOptions(
                    imeOptions = 0,
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
                )
            )

            // 2. Pre-populate 1 bigram in normal mode so read-only query can be verified during incognito
            val store = PredictionStore.inMemory()
            assertTrue(store.recordBigram("今天", "开会", nowMs = 1_000L))
            assertEquals(1, store.totalBigramCount())

            val host = FakeImeHost(scope = scope)
            val flypyEngine = FlypyInputEngine(FlypyEngine(buildSampleFlypyDictionary()))
            var rimeIncognitoFlag = false
            val fakeRimeEngine = object : InputEngine {
                override val state: StateFlow<EngineState> = MutableStateFlow(EngineState())
                override fun onKey(key: KeyInput): EngineResult = EngineResult.PassThrough
                override fun select(index: Int): EngineResult = EngineResult.PassThrough
                override fun clear() {}
                override fun flush(): String = ""
                override fun setIncognitoMode(incognito: Boolean) {
                    rimeIncognitoFlag = incognito
                }
            }

            val aiNetworkCalls = AtomicInteger(0)
            val completer = AiSentenceCompleter(
                scope = scope,
                debounceMs = 10L,
                executor = { _, _, _, _ ->
                    aiNetworkCalls.incrementAndGet()
                    Result.success("绝不应在无痕模式下请求")
                }
            )

            val controller = TypingController(
                host = host,
                flypyInputEngine = flypyEngine,
                predictionStore = store,
                staticPredictor = StaticPredictor(),
                aiSentenceCompleter = completer
            )
            controller.rimeInputEngine = fakeRimeEngine

            // 3. Enter Incognito Mode
            controller.setIncognitoMode(true)
            assertTrue(controller.isIncognitoMode)
            assertTrue(flypyEngine.isIncognitoMode)
            assertTrue(rimeIncognitoFlag)
            assertTrue(store.isIncognitoMode)
            assertTrue(host.stateFlow.value.isIncognitoMode)

            // 4. Commit words in Incognito Mode -> ZERO writes to PredictionStore, while read-only query still works
            controller.commitQwertyChar("j")
            controller.commitQwertyChar("n")
            controller.commitQwertyChar("t")
            controller.commitQwertyChar("m") // commits "今天"

            // Read-only predictions still include previously learned "开会" + L1 static predictions
            val incognitoPreds = host.stateFlow.value.predictionCandidates.map { it.text }
            assertTrue(incognitoPreds.contains("开会"))

            // Now commit "下午" ("xxwu") after "今天" while in incognito mode
            controller.commitQwertyChar("x")
            controller.commitQwertyChar("x")
            controller.commitQwertyChar("w")
            controller.commitQwertyChar("u") // commits "下午"

            // Verify "今天" -> "下午" was NEVER written to PredictionStore!
            assertEquals(0, store.getBigramCount("今天", "下午"))
            assertEquals(1, store.totalBigramCount())

            // Direct recordBigram call on store while incognito also returns false
            assertFalse(store.recordBigram("我们", "可以"))
            assertEquals(1, store.totalBigramCount())

            // 5. Verify AI Sentence Completer does not trigger in incognito mode
            val aiJob = completer.scheduleCompletion(
                contextBeforeCursor = "今天下午",
                settings = AppSettings(aiCompletionEnabled = true),
                activeStyle = DefaultPresets.defaultStyles().first(),
                isIncognitoMode = true,
                onResult = {}
            )
            assertNull(aiJob)
            delay(30L)
            assertEquals(0, aiNetworkCalls.get())

            // 6. Verify Auto-Learn baseline/trigger and Clipboard sync are blocked in incognito mode
            host.onDictationCommittedForAutoLearn("口述文本")
            assertNull(host.lastDictationBaseline)
            host.tryAutoLearnFromRecentEdit()
            assertEquals(0, host.autoLearnTriggeredCount)
            host.syncSystemClipboardIfNeeded(allowFreshBanner = true)
            assertEquals(0, host.clipboardSyncTriggeredCount)

            // 7. Exit Incognito Mode -> learning resumes normally
            controller.setIncognitoMode(false)
            assertFalse(controller.isIncognitoMode)
            assertFalse(flypyEngine.isIncognitoMode)
            assertFalse(rimeIncognitoFlag)
            assertFalse(store.isIncognitoMode)
            assertTrue(store.recordBigram("今天", "下午"))
            assertEquals(1, store.getBigramCount("今天", "下午"))
        } finally {
            scope.cancel()
        }
    }
}
