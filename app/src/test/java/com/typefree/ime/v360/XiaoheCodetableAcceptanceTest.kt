package com.typefree.ime.v360

import android.view.inputmethod.InputConnection
import com.typefree.core.ime.coordinator.CommitAction
import com.typefree.core.ime.coordinator.CoordinatorModeBadge
import com.typefree.core.ime.coordinator.CoordinatorUiSnapshot
import com.typefree.core.ime.coordinator.EnhancedCandidate
import com.typefree.core.ime.enhancer.BriefPrefixIndex
import com.typefree.core.ime.importer.ImportMode
import com.typefree.core.ime.overlay.SymbolTrie
import com.typefree.core.ime.profile.SchemaArchetype
import com.typefree.core.ime.profile.SchemaProfile
import com.typefree.ime.commit.AndroidCommitExecutor
import com.typefree.ime.commit.EditorBatchConnection
import com.typefree.ime.data.AppRepository
import com.typefree.ime.data.AppSettings
import com.typefree.ime.data.HistoryRetention
import com.typefree.ime.data.InMemoryKeyValueStore
import com.typefree.ime.data.InputScheme
import com.typefree.ime.data.JvmAesGcmCipher
import com.typefree.ime.data.SecretVault
import com.typefree.ime.ime.ImeHostCallbacks
import com.typefree.ime.ime.ImeLogic
import com.typefree.ime.ime.ImeUiState
import com.typefree.ime.ime.KeyboardTabMode
import com.typefree.ime.ime.TypingController
import com.typefree.ime.ime.UndoSnapshot
import com.typefree.ime.ime.engine.CodetableEnhancedInputEngine
import com.typefree.ime.ime.engine.EngineResult
import com.typefree.ime.ime.engine.KeyInput
import com.typefree.ime.ime.engine.RimeDataManager
import com.typefree.ime.ime.engine.RimeDeploymentState
import com.typefree.ime.ime.engine.RimeInputEngine
import com.typefree.ime.ime.predict.PredictionStore
import com.typefree.ime.ime.predict.StaticPredictor
import com.typefree.ime.network.AiPipelineEngine
import com.typefree.ime.ui.CandidateBarViewModel
import com.typefree.rime.RimeBridge
import com.typefree.rime.RimeCandidate
import com.typefree.rime.RimeContextSnapshot
import com.typefree.rime.RimeSessionClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Host `:app` acceptance tests verifying `AndroidCommitExecutor` Mock InputConnection
 * paired-symbol cursor centering & `;i` atomic undo (`QA-OVERLAY-02`), 4-byte CJK Ext-B
 * surrogate pair undo, `Auto-Learn v2.0` isolation, and `CandidateBarViewModel` mapping.
 */
class XiaoheCodetableAcceptanceTest {

    private class MockBufferInputConnection : EditorBatchConnection {
        val buffer = StringBuilder()
        var selectionStart: Int = 0
        var selectionEnd: Int = 0
        var batchDepth: Int = 0
        val commitHistory = ArrayList<Pair<String, Int>>()

        override fun beginBatchEdit(): Boolean {
            batchDepth++
            return true
        }

        override fun endBatchEdit(): Boolean {
            batchDepth = (batchDepth - 1).coerceAtLeast(0)
            return batchDepth == 0
        }

        override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
            val s = selectionStart.coerceIn(0, buffer.length)
            val e = selectionEnd.coerceIn(s, buffer.length)
            buffer.replace(s, e, text.toString())
            val nextCursor = if (newCursorPosition > 0) {
                s + text.length
            } else {
                s
            }
            selectionStart = nextCursor
            selectionEnd = nextCursor
            commitHistory.add(text.toString() to newCursorPosition)
            return true
        }

        override fun getExtractedSelectionStart(): Int = selectionStart

        override fun setSelection(start: Int, end: Int): Boolean {
            selectionStart = start.coerceIn(0, buffer.length)
            selectionEnd = end.coerceIn(0, buffer.length)
            return true
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            val delAfterEnd = (selectionEnd + afterLength).coerceAtMost(buffer.length)
            if (delAfterEnd > selectionEnd) {
                buffer.delete(selectionEnd, delAfterEnd)
            }
            val delBeforeStart = (selectionStart - beforeLength).coerceAtLeast(0)
            if (selectionStart > delBeforeStart) {
                buffer.delete(delBeforeStart, selectionStart)
            }
            selectionStart = delBeforeStart
            selectionEnd = delBeforeStart
            return true
        }

        override fun sendDpadLeftKeyEvent() {
            if (selectionStart > 0) {
                selectionStart--
                selectionEnd = selectionStart
            }
        }
    }

    @Test
    fun `QA-OVERLAY-02 Mock InputConnection paired bracket cursor centering and atomic undo`() {
        val mockIc = MockBufferInputConnection()
        val xiaoheProfile = SchemaProfile.defaultXiaoheFlypy()
        val executor = AndroidCommitExecutor(connectionProvider = { mockIc })

        // 1) `;o` -> commitText("「」", 1) + cursor centered at index 1
        executor.execute(CommitAction.PairedText("「", "」"), profile = xiaoheProfile)
        assertEquals(listOf("「」" to 1), mockIc.commitHistory)
        assertEquals("「」", mockIc.buffer.toString())
        assertEquals("Cursor must sit at index 1 between 「 and 」", 1, mockIc.selectionStart)
        assertEquals(1, mockIc.selectionEnd)
        assertEquals(0, mockIc.batchDepth)

        // 2) Immediate `;i` -> atomically deletes both 「 and 」
        executor.execute(CommitAction.UndoLastCommit, profile = xiaoheProfile)
        assertEquals("Both brackets must be atomically undone", "", mockIc.buffer.toString())
        assertEquals(0, mockIc.selectionStart)

        // 3) 4-byte CJK Ext-B `𩽾` (2 UTF-16 code units) commit + `;f` repeat + `;i` undo
        val extB = "\uD867\uDF7E"
        executor.execute(CommitAction.PlainText(extB), profile = xiaoheProfile)
        assertEquals(extB, mockIc.buffer.toString())
        executor.execute(CommitAction.RepeatLastCommit, profile = xiaoheProfile)
        assertEquals(extB + extB, mockIc.buffer.toString())
        executor.execute(CommitAction.UndoLastCommit, profile = xiaoheProfile)
        assertEquals(extB, mockIc.buffer.toString())
        assertEquals("Xiaohe codetable must NEVER increment Auto-Learn count", 0, executor.autoLearnCommitCount)
    }

    @Test
    fun `CandidateBarViewModel maps reverse-split preedit and suffix-only secondary candidate`() {
        val snapshot = CoordinatorUiSnapshot(
            isComposing = true,
            rawCode = "hfkn",
            preedit = "hf'kn",
            badge = CoordinatorModeBadge.NONE,
            candidates = listOf(
                EnhancedCandidate(displayText = "很可能", commitText = "很可能"),
                EnhancedCandidate(displayText = "困难", commitText = "很困难")
            ),
            isReverseSplitActive = true
        )
        val vm = CandidateBarViewModel()
        val state = vm.updateFromCoordinator(snapshot)
        assertTrue(state.isComposing)
        assertEquals("hf'kn", state.preeditText)
        assertEquals("很可能", state.candidates[0].displayText)
        assertEquals("很可能", state.candidates[0].text)
        assertEquals("困难", state.candidates[1].displayText)
        assertEquals("很困难", state.candidates[1].text)
    }

    private class FakeHostRimeClient : RimeSessionClient {
        override var activeSchemaId: String = "rime_ice"
        override var pageSize: Int = 24
        override val isNativeLoaded: Boolean = false
        override var isDeployed: Boolean = false
        override var wordCount: Int = 0

        val options = HashMap<String, Boolean>()
        val schemaSelectHistory = ArrayList<String>()
        private val rawBuf = StringBuilder()

        var schemaTables: Map<String, Map<String, List<String>>> = mapOf(
            "flypy" to mapOf(
                "h" to listOf("和"),
                "hf" to listOf("很"),
                "kn" to listOf("可能", "困难"),
                "an" to (1..40).map { "安$it" }
            ),
            "rime_ice" to mapOf(
                "ni" to listOf("你", "泥"),
                "nihao" to listOf("你好")
            ),
            "xkjd6" to mapOf(
                "a" to listOf("一"),
                "b" to listOf("不")
            )
        )

        override fun initialize(sharedDir: String, userDir: String): Boolean = true

        override fun deploy(schemaId: String): Boolean {
            if (schemaId.isNotBlank()) activeSchemaId = schemaId
            isDeployed = true
            wordCount = 12345
            return true
        }

        override fun selectSchema(schemaId: String): Boolean {
            activeSchemaId = schemaId
            schemaSelectHistory.add(schemaId)
            rawBuf.clear()
            return true
        }

        override fun processKey(keyCode: Int, mask: Int): Boolean {
            if (keyCode == RimeBridge.XK_BackSpace) {
                if (rawBuf.isNotEmpty()) rawBuf.deleteCharAt(rawBuf.length - 1)
                return true
            }
            val ch = keyCode.toChar()
            if (ch in 'a'..'z' || ch == '\'') {
                rawBuf.append(ch)
                return true
            }
            return false
        }

        override fun processKeyText(keys: String): RimeContextSnapshot {
            rawBuf.clear()
            keys.forEach { processKey(it.code, 0) }
            return getContext(pageSize)
        }

        override fun getContext(maxCandidates: Int): RimeContextSnapshot {
            val raw = rawBuf.toString()
            if (raw.isEmpty()) return RimeContextSnapshot()
            val fullList = schemaTables[activeSchemaId]?.get(raw).orEmpty()
            val hasMore = fullList.size > maxCandidates
            return RimeContextSnapshot(
                preedit = raw,
                rawInput = raw,
                candidates = fullList.take(maxCandidates).map { RimeCandidate(it) },
                hasMore = hasMore,
                isLastPage = !hasMore
            )
        }

        override fun selectCandidate(index: Int): String? {
            val picked = getContext(pageSize).candidates.getOrNull(index)?.text
            rawBuf.clear()
            return picked
        }

        override fun commitComposition(): String? {
            val top = getContext(pageSize).candidates.firstOrNull()?.text
            rawBuf.clear()
            return top
        }

        override fun getCommit(): String? = null
        override fun clearComposition() { rawBuf.clear() }
        override fun setOption(option: String, value: Boolean) { options[option] = value }
        override fun getOption(option: String): Boolean = options[option] ?: false
        override fun syncUserData(): Boolean = true
        override fun getVersion(): String = "1.17.0-test"
    }

    private class TestImeHost(
        override val scope: CoroutineScope,
        private val mockIc: MockBufferInputConnection,
        initialSettings: AppSettings = AppSettings(inputScheme = InputScheme.FLYPY_TABLE)
    ) : ImeHostCallbacks {
        override val repository: AppRepository
            get() = throw UnsupportedOperationException("TestImeHost uses settingsOverride")

        override val aiEngine: AiPipelineEngine = AiPipelineEngine()
        override val stateFlow = MutableStateFlow(
            ImeUiState(
                tabMode = KeyboardTabMode.QWERTY,
                isFlypyChineseMode = true,
                activeInputScheme = initialSettings.inputScheme
            )
        )

        var currentSettings: AppSettings = initialSettings
        val undoStack = ArrayDeque<UndoSnapshot>()
        val toasts = ArrayList<String>()
        var jevUndoCount = 0
        var jevOpenCount = 0

        override fun currentInputConnection(): InputConnection? = null
        override fun performKeyFeedback(strong: Boolean) {}
        override fun performHaptic(strong: Boolean) {}
        override fun showQuickActionToast(message: String) { toasts.add(message) }

        override fun commitDirectText(
            text: String,
            recordUndo: Boolean,
            markManualEdit: Boolean,
            playFeedback: Boolean
        ) {
            mockIc.beginBatchEdit()
            mockIc.commitText(text, 1)
            mockIc.endBatchEdit()
            if (recordUndo) {
                pushUndoSnapshot(ImeLogic.createUndoSnapshot(text, ""))
            }
            syncEditorTextFromMock()
        }

        fun syncEditorTextFromMock() {
            stateFlow.update { it.copy(currentEditorText = mockIc.buffer.toString()) }
        }

        override fun pushUndoSnapshot(snapshot: UndoSnapshot?) {
            if (snapshot != null) {
                undoStack.addFirst(snapshot)
                stateFlow.update { it.copy(undoSnapshot = undoStack.firstOrNull()) }
            }
        }

        override fun peekTopUndoSnapshot(): UndoSnapshot? = undoStack.firstOrNull()
        override fun recordManualEditTouch() {}
        override fun tryAutoLearnFromRecentEdit() {}
        override fun onDictationCommittedForAutoLearn(fullBufferAfterCommit: String) {}
        override fun clearAutoLearnBaseline() {}
        override fun onUserUndoForJev() { jevUndoCount++ }
        override fun readSelectedTextSafely(): String = ""
        override fun readSurroundingBeforeCursor(maxChars: Int): String = mockIc.buffer.toString().takeLast(maxChars)
        override fun readFullEditorTextSafely(): String = mockIc.buffer.toString()
        override fun readSystemClipboardText(): String = ""
        override fun syncSystemClipboardIfNeeded(allowFreshBanner: Boolean) {}
        override fun openJevCopilotFromCommand() { jevOpenCount++ }
        override fun archiveWavForHistory(wavFile: File, retention: HistoryRetention): File = wavFile
        override fun getLastRecordingWavFile(): File = File("dummy.wav")
        override fun hasRetryWavFile(): Boolean = false
    }

    private fun resolveXiaoheAssetsDir(): File? {
        return listOf(
            File("src/xiaohe/assets/rime"),
            File("app/src/xiaohe/assets/rime")
        ).firstOrNull { File(it, "flypy.schema.yaml").exists() }
    }

    private fun resolveMainRimeAssetsDir(): File? {
        return listOf(
            File("src/main/assets/rime"),
            File("app/src/main/assets/rime")
        ).firstOrNull { File(it, "default.yaml").exists() }
    }

    @Test
    fun `Host scheme switching preserves shared RimeBridge schema and isolation options (P0-2)`() {
        val tempRoot = Files.createTempDirectory("host_p02_").toFile()
        try {
            val sharedDir = File(tempRoot, "shared").apply { mkdirs() }
            val userDir = File(tempRoot, "user").apply { mkdirs() }
            File(sharedDir, "flypy.schema.yaml").writeText(
                """
                schema:
                  schema_id: flypy
                  name: 小鹤音形
                engine:
                  translators:
                    - table_translator
                speller:
                  alphabet: "abcdefghijklmnopqrstuvwxyz'"
                  max_code_length: 4
                translator:
                  dictionary: flypy
                  enable_user_dict: false
                """.trimIndent(),
                StandardCharsets.UTF_8
            )
            File(sharedDir, "flypy.dict.yaml").writeText(
                "---\nname: flypy\n...\n很\thf\n可能\tkn\n",
                StandardCharsets.UTF_8
            )

            val fakeBridge = FakeHostRimeClient()
            val dm = RimeDataManager(sharedDir, userDir, fakeBridge)
            val rimeInputEngine = RimeInputEngine(
                rimeBridge = fakeBridge,
                initialScheme = InputScheme.FLYPY_TABLE,
                externalEnhancedSchemePredicate = { dm.isSchemeHandledByEnhancedEngine(it) },
                customSchemaIdProvider = { dm.resolveCustomSchemaId() }
            )

            dm.deployDefaultSync(InputScheme.FLYPY_TABLE)
            assertTrue(dm.activateEnhancedSchemeSync(InputScheme.FLYPY_TABLE))
            val enhanced = CodetableEnhancedInputEngine(
                rimeClient = fakeBridge,
                initialProfile = dm.activeSchemaProfile,
                symbolTrie = dm.activeSymbolTrie,
                briefIndex = dm.activeBriefIndex,
                schemaDir = sharedDir
            )
            // Calling RimeInputEngine lifecycle methods while FLYPY_TABLE is active MUST NOT hijack bridge!
            rimeInputEngine.setInputScheme(InputScheme.FLYPY_TABLE)
            rimeInputEngine.setIncognitoMode(false)
            rimeInputEngine.setFixedCandidateOrder(false)

            assertEquals("flypy", fakeBridge.activeSchemaId)
            assertEquals(false, fakeBridge.getOption("enable_user_dict"))
            assertEquals(true, fakeBridge.getOption("_incognito"))
            assertEquals(true, fakeBridge.getOption("fixed_candidate_order"))

            // Switch to RIME_PINYIN -> RimeInputEngine owns bridge cleanly
            var activeEnhanced = false
            enhanced.isActiveEnginePredicate = { activeEnhanced }
            rimeInputEngine.setInputScheme(InputScheme.RIME_PINYIN)
            rimeInputEngine.setIncognitoMode(false)
            enhanced.setIncognitoMode(false) // must be a no-op when not active!
            assertEquals("rime_ice", fakeBridge.activeSchemaId)
            assertEquals(true, fakeBridge.getOption("enable_user_dict"))
            assertEquals(false, fakeBridge.getOption("_incognito"))

            // Import custom xkjd6 package -> RIME_CUSTOM resolves to "xkjd6", NOT hardcoded "custom"
            val xkjdZip = ByteArrayOutputStream().use { baos ->
                ZipOutputStream(baos, StandardCharsets.UTF_8).use { zos ->
                    zos.putNextEntry(ZipEntry("xkjd6.schema.yaml"))
                    zos.write(
                        """
                        schema:
                          schema_id: xkjd6
                          name: 星空键道6
                        engine:
                          translators:
                            - table_translator
                        speller:
                          alphabet: "abcdefghijklmnopqrstuvwxyz"
                          auto_select_pattern: "^[bcdfghjklmnpqrstwxyz]{2}[aiouv].*$"
                        translator:
                          dictionary: xkjd6
                        """.trimIndent().toByteArray(StandardCharsets.UTF_8)
                    )
                    zos.closeEntry()
                    zos.putNextEntry(ZipEntry("xkjd6.dict.yaml"))
                    zos.write("---\nname: xkjd6\n...\n一\ta\n".toByteArray(StandardCharsets.UTF_8))
                    zos.closeEntry()
                }
                baos.toByteArray()
            }
            val imported = dm.importEnhancedSchemaPackageSync(ByteArrayInputStream(xkjdZip))
            assertEquals("xkjd6", imported.schemaId)
            assertEquals("xkjd6", dm.resolveCustomSchemaId())

            activeEnhanced = true
            assertTrue(dm.activateEnhancedSchemeSync(InputScheme.RIME_CUSTOM))
            enhanced.refreshFromMetadata(
                dm.activeSchemaProfile,
                dm.activeSymbolTrie,
                dm.activeBriefIndex,
                dm.activeReverseLookupMap
            )
            rimeInputEngine.setInputScheme(InputScheme.RIME_CUSTOM)
            assertEquals("xkjd6", fakeBridge.activeSchemaId)
            assertEquals(SchemaArchetype.PUSH_TOPUP_CODE, enhanced.activeProfile.archetype)
            assertEquals(false, fakeBridge.getOption("enable_user_dict"))
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun `TypingController + CodetableEnhancedInputEngine + AndroidCommitExecutor syncs v360 Jev text, undo, and pagination`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val mockIc = MockBufferInputConnection()
            val host = TestImeHost(scope, mockIc, AppSettings(inputScheme = InputScheme.FLYPY_TABLE))
            val fakeBridge = FakeHostRimeClient().apply { selectSchema("flypy") }
            val briefIdx = BriefPrefixIndex.buildFromDictLines(
                mainDictLines = sequenceOf("---", "name: flypy", "...", "很\thf", "可能\tkn", "困难\tkn"),
                gt4GuardTwoPrefixes = setOf("ok", "os")
            )
            lateinit var controller: TypingController
            val executor = AndroidCommitExecutor(
                connectionProvider = { mockIc },
                onTextCommitted = { text, _ ->
                    controller.recordExternalCommit(text)
                    host.pushUndoSnapshot(ImeLogic.createUndoSnapshot(text, ""))
                    host.syncEditorTextFromMock()
                    controller.predictionCoordinator.onWordCommitted(
                        committedText = text,
                        isComposing = controller.isComposing
                    )
                },
                onUndoExecuted = {
                    host.undoStack.removeFirstOrNull()
                    host.stateFlow.update { it.copy(undoSnapshot = host.undoStack.firstOrNull()) }
                    host.onUserUndoForJev()
                    host.syncEditorTextFromMock()
                }
            )
            val enhanced = CodetableEnhancedInputEngine(
                rimeClient = fakeBridge,
                initialProfile = SchemaProfile.defaultXiaoheFlypy(),
                symbolTrie = SymbolTrie.defaultXiaohe(),
                briefIndex = briefIdx,
                commitExecutor = executor,
                onOpenJevCopilot = { host.openJevCopilotFromCommand() }
            )
            controller = TypingController(
                host = host,
                predictionStore = PredictionStore.inMemory(),
                staticPredictor = StaticPredictor(),
                settingsOverride = { host.currentSettings }
            ).apply {
                isRimeReady = true
                enhancedSchemePredicate = { it == InputScheme.FLYPY_TABLE || it == InputScheme.RIME_CUSTOM }
                codetableEnhancedEngine = enhanced
            }

            // 1) P0-3: resetComposing() clears codetableEnhancedEngine + UI preedit
            controller.commitQwertyChar("h")
            controller.commitQwertyChar("f")
            assertTrue(controller.isComposing)
            assertEquals("hf", host.stateFlow.value.flypyComposingCode)
            controller.resetComposing()
            assertFalse(controller.isComposing)
            assertEquals("", host.stateFlow.value.flypyComposingCode)

            // 2) P0-4 & v3.6.0 Jev sync: reverse-split `hfkn` commit syncs buffer, undoStack, and currentEditorText
            for (c in listOf("h", "f", "k", "n")) controller.commitQwertyChar(c)
            controller.selectFlypyCandidate(0)
            assertEquals("很可能", mockIc.buffer.toString())
            assertEquals("很可能", host.stateFlow.value.currentEditorText)
            assertEquals(1, host.undoStack.size)

            // 3) `;o` paired bracket + `;i` atomic undo updates Jev undo count & editor text
            controller.commitQwertyChar(";")
            controller.commitQwertyChar("o")
            assertEquals("很可能「」", mockIc.buffer.toString())
            assertEquals(4, mockIc.selectionStart)
            assertEquals("很可能「」", host.stateFlow.value.currentEditorText)

            controller.commitQwertyChar(";")
            controller.commitQwertyChar("i")
            assertEquals("很可能", mockIc.buffer.toString())
            assertEquals("很可能", host.stateFlow.value.currentEditorText)
            assertEquals(1, host.jevUndoCount)

            // 4) P2-1: requestMoreCandidates expands >24 candidates
            controller.commitQwertyChar("a")
            controller.commitQwertyChar("n")
            assertEquals(24, host.stateFlow.value.flypyCandidates.size)
            controller.requestMoreCandidates(60)
            assertTrue(fakeBridge.pageSize >= 60)
            assertEquals(40, host.stateFlow.value.flypyCandidates.size)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `Async custom-drawn editor stale cursor centering and external cursor change guard (P1-2)`() {
        var selectionSetTo: Pair<Int, Int>? = null
        var deletedBeforeAfter: Pair<Int, Int>? = null
        val staleEditorIc = object : EditorBatchConnection {
            val content = StringBuilder("hello")
            override fun beginBatchEdit(): Boolean = true
            override fun endBatchEdit(): Boolean = true
            override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
                content.append(text)
                return true
            }
            // Simulates WeChat/Feishu async editor returning stale pre-commit cursor `5` even inside batch edit
            override fun getExtractedSelectionStart(): Int = 5
            override fun setSelection(start: Int, end: Int): Boolean {
                selectionSetTo = start to end
                return true
            }
            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                deletedBeforeAfter = beforeLength to afterLength
                return true
            }
            override fun sendDpadLeftKeyEvent() {}
        }

        val executor = AndroidCommitExecutor(connectionProvider = { staleEditorIc })
        val profile = SchemaProfile.defaultXiaoheFlypy()
        executor.execute(CommitAction.PairedText("「", "」"), profile = profile)
        assertEquals("Cursor MUST center at index 6 (5 + 1), not shift left to 4", 6 to 6, selectionSetTo)
        assertEquals(6, executor.expectedCenteredCursor)

        // Normal onUpdateSelection matching centered cursor `6` preserves atomic `;i` undo
        executor.notifyExternalCursorChange(6, 6)
        executor.execute(CommitAction.UndoLastCommit, profile = profile)
        assertEquals(1 to 1, deletedBeforeAfter)

        // Moving cursor manually to index 2 invalidates centered undo so `;i` does NOT delete unrelated chars
        deletedBeforeAfter = null
        executor.execute(CommitAction.PairedText("「", "」"), profile = profile)
        executor.notifyExternalCursorChange(2, 2)
        executor.execute(CommitAction.UndoLastCommit, profile = profile)
        assertEquals(null, deletedBeforeAfter)
    }

    @Test
    fun `Real xiaohe_flypy_typefree_ready bundle end-to-end RimeDataManager import and oqm toggle`() {
        val xiaoheDir = resolveXiaoheAssetsDir() ?: return
        val tempRoot = Files.createTempDirectory("host_real_xiaohe_").toFile()
        try {
            val sharedDir = File(tempRoot, "shared")
            val userDir = File(tempRoot, "user")
            val zipBytes = ByteArrayOutputStream().use { baos ->
                ZipOutputStream(baos, StandardCharsets.UTF_8).use { zos ->
                    xiaoheDir.listFiles().orEmpty().filter { it.isFile }.forEach { f ->
                        zos.putNextEntry(ZipEntry(f.name))
                        zos.write(f.readBytes())
                        zos.closeEntry()
                    }
                }
                baos.toByteArray()
            }

            val fakeBridge = FakeHostRimeClient()
            val dm = RimeDataManager(sharedDir, userDir, fakeBridge)
            val result = dm.importEnhancedSchemaPackageSync(ByteArrayInputStream(zipBytes))

            assertEquals("flypy", result.schemaId)
            assertEquals(ImportMode.PLAIN_DICT_COMPILE, result.importMode)
            assertEquals(SchemaArchetype.FIXED_YINXING, result.profile.archetype)
            assertTrue(result.briefIndex.populatedBrief2Slots > 500)
            assertTrue(dm.activeReverseLookupMap.size > 5_000)

            var asyncDeployCount = 0
            val engine = CodetableEnhancedInputEngine(
                rimeClient = fakeBridge,
                initialProfile = dm.activeSchemaProfile,
                symbolTrie = dm.activeSymbolTrie,
                briefIndex = dm.activeBriefIndex,
                reverseLookupMap = dm.activeReverseLookupMap,
                schemaDir = sharedDir,
                onAsyncReloadSchema = { sid ->
                    asyncDeployCount++
                    fakeBridge.deploy(sid)
                }
            )
            val toggled = engine.subDictToggleManager?.toggleFullCodeDict("flypy")
            assertEquals(false, toggled)
            assertEquals(1, asyncDeployCount)
            assertTrue(File(sharedDir, "flypy.custom.yaml").exists())
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun `Real native librime deploys flypy, decodes n and okhguu, reverse-splits hfkn, and toggles ojf simplification`() {
        val xiaoheDir = resolveXiaoheAssetsDir() ?: return
        val mainDir = resolveMainRimeAssetsDir() ?: return
        val nativeDylibPresent = listOf(
            File("rime/build/host-macos/librime_jni.dylib"),
            File("../rime/build/host-macos/librime_jni.dylib")
        ).any { it.isFile }

        val bridge = RimeBridge()
        if (!bridge.isNativeLoaded) {
            if (nativeDylibPresent) {
                throw AssertionError("Host librime_jni.dylib exists but failed to load in RimeBridge")
            }
            return
        }

        val tempRoot = Files.createTempDirectory("native_librime_xiaohe_").toFile()
        try {
            val sharedDir = File(tempRoot, "shared").apply { mkdirs() }
            val userDir = File(tempRoot, "user").apply { mkdirs() }
            mainDir.copyRecursively(sharedDir, overwrite = true)
            xiaoheDir.copyRecursively(sharedDir, overwrite = true)

            val dm = RimeDataManager(sharedDir, userDir, bridge)
            val deployState = dm.deployDefaultSync(InputScheme.FLYPY_TABLE)
            assertTrue(
                "Expected native RimeDeploymentState.Ready, got $deployState",
                deployState is RimeDeploymentState.Ready
            )
            val ready = deployState as RimeDeploymentState.Ready
            assertEquals("flypy", ready.activeSchemaId)
            assertTrue(File(sharedDir, "default.custom.yaml").exists())
            assertTrue(File(sharedDir, "opencc/s2tw.json").exists())
            assertTrue(dm.activateEnhancedSchemeSync(InputScheme.FLYPY_TABLE))

            val engine = CodetableEnhancedInputEngine(
                rimeClient = bridge,
                initialProfile = dm.activeSchemaProfile,
                symbolTrie = dm.activeSymbolTrie,
                briefIndex = dm.activeBriefIndex,
                reverseLookupMap = dm.activeReverseLookupMap,
                schemaDir = sharedDir
            )

            // 1) Normal Xiaohe input `n` -> exact 2 candidates `["你", "内容"]` (no pinyin/completion leakage)
            engine.onKey(KeyInput.CharKey('n'))
            val nCandidates = engine.state.value.candidates.map { it.text }
            assertEquals("Expected exact Xiaohe 1-brief candidates for `n`, got $nCandidates", listOf("你", "内容"), nCandidates)
            engine.clear()

            // 2) >4-code `okhguu` -> `丁` (must NOT be truncated to `khguu`)
            for (ch in "okhguu") {
                engine.onKey(KeyInput.CharKey(ch))
            }
            val okCandidates = engine.state.value.candidates.map { it.text }
            assertTrue("Expected `丁` for `okhguu`, got $okCandidates", "丁" in okCandidates)
            engine.clear()

            // 3) Empty-code 2+2 reverse split `hfkn` -> `很可能` / `困难` (commitText=`很困难`)
            for (ch in "hfkn") {
                engine.onKey(KeyInput.CharKey(ch))
            }
            val hfknCandidates = engine.state.value.candidates
            assertTrue("Expected candidates for `hfkn`", hfknCandidates.isNotEmpty())
            assertEquals("很可能", hfknCandidates[0].text)
            assertTrue(
                "Expected `很困难` (displayText=`困难`) in `hfkn` secondary candidates, got $hfknCandidates",
                hfknCandidates.any { it.text == "很困难" && it.displayText == "困难" }
            )
            engine.clear()

            // 4) `ojf` toggles OpenCC simplification (`s2tw.json`), converting `内容` -> `內容`, `个` -> `個`, `国` -> `國`
            assertFalse(bridge.getOption("simplification"))
            for (ch in "ojf") {
                engine.onKey(KeyInput.CharKey(ch))
            }
            val ojfCands = engine.state.value.candidates
            assertTrue("Expected [简繁切换] candidate for `ojf`, got ${ojfCands.map { it.text }}", ojfCands.isNotEmpty())
            val toggleResult = engine.select(0)
            assertTrue(toggleResult is EngineResult.Consumed)
            assertTrue("`ojf` must enable native librime simplification option", bridge.getOption("simplification"))

            engine.onKey(KeyInput.CharKey('n'))
            val tradNCandidates = engine.state.value.candidates.map { it.text }
            assertTrue(
                "Expected OpenCC traditional `內容` for `n` when simplification=true, got $tradNCandidates",
                "內容" in tradNCandidates
            )
            engine.clear()

            engine.onKey(KeyInput.CharKey('g'))
            val tradGCandidates = engine.state.value.candidates.map { it.text }
            assertTrue(
                "Expected OpenCC traditional `個` for `g` when simplification=true, got $tradGCandidates",
                "個" in tradGCandidates
            )
            engine.clear()

            for (ch in "gok") {
                engine.onKey(KeyInput.CharKey(ch))
            }
            val tradGokCandidates = engine.state.value.candidates.map { it.text }
            assertTrue(
                "Expected OpenCC traditional `國` for `gok` when simplification=true, got $tradGokCandidates",
                "國" in tradGokCandidates
            )
            engine.clear()

            // 5) Quick symbol overlay (`;` / `；`) and inline math (`=1+2`)
            val semiEnter = engine.onKey(KeyInput.CharKey('；'))
            assertTrue(semiEnter is EngineResult.Consumed)
            assertTrue(engine.coordinator.overlayController.isActive)
            val semiA = engine.onKey(KeyInput.CharKey('a'))
            assertEquals(EngineResult.Commit("！", remainComposing = false), semiA)

            for (ch in "=1+2") {
                engine.onKey(KeyInput.CharKey(ch))
            }
            val mathCands = engine.state.value.candidates.map { it.text }
            assertTrue("Expected `3` for `=1+2`, got $mathCands", "3" in mathCands)
            engine.clear()

            // 6) Verify real native librime deploy catches missing/invalid schema instead of ignoring select_schema failure
            val invalidDeploy = bridge.deploy("nonexistent_schema_xyz")
            assertFalse("Deploying nonexistent schema must return false", invalidDeploy)

            // 7) Verify SchemaPackageImporter strips legacy `prefix: "o"` and compiles cleanly via real librime
            val legacyBrokenSchema = File(xiaoheDir, "flypy.schema.yaml")
                .readText(StandardCharsets.UTF_8)
                .replace("tag: ok_mode", "tag: ok_mode\n  prefix: \"o\"")
            val legacyZip = ByteArrayOutputStream().use { baos ->
                ZipOutputStream(baos, StandardCharsets.UTF_8).use { zos ->
                    xiaoheDir.listFiles().orEmpty().filter { it.isFile }.forEach { f ->
                        zos.putNextEntry(ZipEntry(f.name))
                        if (f.name == "flypy.schema.yaml") {
                            zos.write(legacyBrokenSchema.toByteArray(StandardCharsets.UTF_8))
                        } else {
                            zos.write(f.readBytes())
                        }
                        zos.closeEntry()
                    }
                }
                baos.toByteArray()
            }
            val importRoot = Files.createTempDirectory("native_import_xiaohe_").toFile()
            try {
                val impShared = File(importRoot, "shared").apply { mkdirs() }
                val impUser = File(importRoot, "user").apply { mkdirs() }
                mainDir.copyRecursively(impShared, overwrite = true)
                val impDm = RimeDataManager(impShared, impUser, bridge)
                val impRes = impDm.importEnhancedSchemaPackageSync(ByteArrayInputStream(legacyZip))
                assertEquals("flypy", impRes.schemaId)
                val importedSchemaText = File(impShared, "flypy.schema.yaml").readText(StandardCharsets.UTF_8)
                assertFalse("Importer must strip legacy ok_mode prefix: \"o\"", importedSchemaText.contains("prefix:"))

                val snapOk = bridge.processKeyText("okhguu")
                assertTrue(
                    "Imported flypy must decode `okhguu` -> `丁` in real librime, got ${snapOk.candidates.map { it.text }}",
                    snapOk.candidates.any { it.text == "丁" }
                )
            } finally {
                importRoot.deleteRecursively()
            }
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun `Overwrite-install on com_typefree_ime migrates xiaohe flavor once to FLYPY_TABLE while keeping manual overrides`() {
        // Pure migration matrix across both flavors
        assertEquals(
            InputScheme.FLYPY_TABLE,
            AppRepository.resolveUpgradedInputScheme(
                rawInputScheme = InputScheme.RIME_PINYIN,
                isXiaoheFlavor = true,
                alreadyMigratedXiaoheScheme = false
            )
        )
        assertEquals(
            InputScheme.FLYPY_TABLE,
            AppRepository.resolveUpgradedInputScheme(
                rawInputScheme = InputScheme.RIME_DOUBLE_PINYIN_FLYPY,
                isXiaoheFlavor = true,
                alreadyMigratedXiaoheScheme = false
            )
        )
        assertEquals(
            InputScheme.RIME_CUSTOM,
            AppRepository.resolveUpgradedInputScheme(
                rawInputScheme = InputScheme.RIME_CUSTOM,
                isXiaoheFlavor = true,
                alreadyMigratedXiaoheScheme = false
            )
        )
        assertEquals(
            InputScheme.RIME_DOUBLE_PINYIN_FLYPY,
            AppRepository.resolveUpgradedInputScheme(
                rawInputScheme = InputScheme.RIME_DOUBLE_PINYIN_FLYPY,
                isXiaoheFlavor = true,
                alreadyMigratedXiaoheScheme = true
            )
        )
        assertEquals(
            InputScheme.RIME_PINYIN,
            AppRepository.resolveUpgradedInputScheme(
                rawInputScheme = InputScheme.RIME_PINYIN,
                isXiaoheFlavor = false,
                alreadyMigratedXiaoheScheme = false
            )
        )

        val publicStore = InMemoryKeyValueStore()
        val secretStore = InMemoryKeyValueStore()
        val cipher = JvmAesGcmCipher()
        publicStore.putString(
            AppRepository.KEY_SETTINGS,
            """{"inputScheme":"RIME_PINYIN","fixedCandidateOrder":true}"""
        )

        val upgradedRepo = AppRepository(
            appContext = null,
            publicStore = publicStore,
            secretVault = SecretVault(secretStore, cipher)
        )
        assertEquals(true, upgradedRepo.settings.value.fixedCandidateOrder)
        if (com.typefree.ime.BuildConfig.IS_XIAOHE_FLAVOR) {
            assertEquals(InputScheme.FLYPY_TABLE, upgradedRepo.settings.value.inputScheme)
            assertTrue(publicStore.getBoolean(AppRepository.KEY_MIGRATED_V360_XIAOHE_SCHEME, false))

            // Subsequent manual switch after one-time migration must stick across reload
            upgradedRepo.updateSettings { it.copy(inputScheme = InputScheme.RIME_DOUBLE_PINYIN_FLYPY) }
            val reloadedRepo = AppRepository(
                appContext = null,
                publicStore = publicStore,
                secretVault = SecretVault(secretStore, cipher)
            )
            assertEquals(InputScheme.RIME_DOUBLE_PINYIN_FLYPY, reloadedRepo.settings.value.inputScheme)
            assertEquals(true, reloadedRepo.settings.value.fixedCandidateOrder)
        } else {
            assertEquals(InputScheme.RIME_PINYIN, upgradedRepo.settings.value.inputScheme)
        }
    }
}
