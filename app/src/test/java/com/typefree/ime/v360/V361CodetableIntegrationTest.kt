package com.typefree.ime.v360

import com.typefree.core.ime.coordinator.CommitAction
import com.typefree.core.ime.enhancer.BriefPrefixIndex
import com.typefree.core.ime.overlay.OverlaySymbolEntry
import com.typefree.core.ime.overlay.SymbolTrie
import com.typefree.core.ime.profile.SchemaArchetype
import com.typefree.core.ime.profile.SchemaProfile
import com.typefree.ime.data.AppRepository
import com.typefree.ime.data.InMemoryKeyValueStore
import com.typefree.ime.data.JvmAesGcmCipher
import com.typefree.ime.data.SecretVault
import com.typefree.ime.ime.ImeUiState
import com.typefree.ime.ime.engine.CodetableEnhancedInputEngine
import com.typefree.ime.ime.engine.EngineResult
import com.typefree.ime.ime.engine.KeyInput
import com.typefree.ime.ime.engine.PresetSchemaCatalog
import com.typefree.ime.ime.engine.RimeDataManager
import com.typefree.ime.ime.engine.RimeDeploymentState
import com.typefree.rime.RimeBridge
import com.typefree.rime.RimeCandidate
import com.typefree.rime.RimeContextSnapshot
import com.typefree.rime.RimeSessionClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class V361CodetableIntegrationTest {

    private class FakeCodetableRimeClient : RimeSessionClient {
        override var activeSchemaId: String = "flypy"
        override var pageSize: Int = 24
        override val isNativeLoaded: Boolean = false
        override var isDeployed: Boolean = true
        override var wordCount: Int = 1000

        val options = HashMap<String, Boolean>()
        private val rawBuf = StringBuilder()

        var tables: Map<String, Map<String, List<Pair<String, String>>>> = mapOf(
            "flypy" to mapOf(
                "n" to listOf("你" to "", "内容" to ""),
                "ni" to listOf("你" to "", "泥" to "", "你好" to ""),
                "hc" to listOf("好" to "", "号" to ""),
                "an" to listOf("安" to "", "按" to "", "安全" to "")
            )
        )

        override fun initialize(sharedDir: String, userDir: String): Boolean = true

        override fun deploy(schemaId: String): Boolean {
            if (schemaId.isNotBlank()) activeSchemaId = schemaId
            isDeployed = true
            return true
        }

        override fun selectSchema(schemaId: String): Boolean {
            activeSchemaId = schemaId
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
            val cands = tables[activeSchemaId]?.get(raw).orEmpty()
            return RimeContextSnapshot(
                preedit = raw,
                rawInput = raw,
                candidates = cands.take(maxCandidates).map { (text, comment) -> RimeCandidate(text, comment) },
                hasMore = cands.size > maxCandidates,
                isLastPage = cands.size <= maxCandidates
            )
        }

        override fun selectCandidate(index: Int): String? {
            val picked = getContext(pageSize).candidates.getOrNull(index)?.text
            rawBuf.clear()
            return picked
        }

        override fun commitComposition(): String? {
            val picked = getContext(pageSize).candidates.firstOrNull()?.text
            rawBuf.clear()
            return picked
        }

        override fun getCommit(): String? = null
        override fun clearComposition() { rawBuf.clear() }
        override fun setOption(option: String, value: Boolean) { options[option] = value }
        override fun getOption(option: String): Boolean = options[option] ?: false
        override fun syncUserData(): Boolean = true
        override fun getVersion(): String = "1.17.0-test"
    }

    @Test
    fun `AppRepository persists v361 codetable settings with safe backward-compatible defaults`() {
        val publicStore = InMemoryKeyValueStore()
        val secretStore = InMemoryKeyValueStore()
        val cipher = JvmAesGcmCipher()

        // Legacy JSON missing v3.6.1 fields
        publicStore.putString(
            AppRepository.KEY_SETTINGS,
            """{"inputScheme":"FLYPY_TABLE","whisperModeEnabled":true}"""
        )

        val legacyRepo = AppRepository(
            appContext = null,
            publicStore = publicStore,
            secretVault = SecretVault(secretStore, cipher)
        )
        assertTrue(legacyRepo.settings.value.englishPrefixVetoEnabled)
        assertFalse(legacyRepo.settings.value.codetableAutoEncodeEnabled)

        legacyRepo.updateSettings {
            it.copy(
                englishPrefixVetoEnabled = false,
                codetableAutoEncodeEnabled = true
            )
        }

        val reloadedRepo = AppRepository(
            appContext = null,
            publicStore = publicStore,
            secretVault = SecretVault(secretStore, cipher)
        )
        assertFalse(reloadedRepo.settings.value.englishPrefixVetoEnabled)
        assertTrue(reloadedRepo.settings.value.codetableAutoEncodeEnabled)
    }

    @Test
    fun `CodetableEnhancedInputEngine wires ShadowDictManager pin-block and ImeUiState sync`() {
        val tempDir = Files.createTempDirectory("v361_shadow_").toFile()
        try {
            val fakeBridge = FakeCodetableRimeClient()
            var uiState = ImeUiState(codetableEnhancerActive = true)
            val engine = CodetableEnhancedInputEngine(
                rimeClient = fakeBridge,
                initialProfile = SchemaProfile.defaultXiaoheFlypy(),
                schemaDir = tempDir,
                onUiStateSynced = { _, singleChar, pinnedWords ->
                    uiState = uiState.copy(
                        singleCharModeEnabled = singleChar,
                        pinnedWordsForCurrentCode = pinnedWords
                    )
                }
            )

            engine.onKey(KeyInput.CharKey('a'))
            engine.onKey(KeyInput.CharKey('n'))
            assertEquals(listOf("安", "按", "安全"), engine.state.value.candidates.map { it.text })
            assertTrue(uiState.pinnedWordsForCurrentCode.isEmpty())

            // Long-press Pin "安全" -> moves to index 0, persisted to flypy.shadow.yaml, reflected in UI state
            engine.pinCandidate("安全")
            assertEquals(listOf("安全", "安", "按"), engine.state.value.candidates.map { it.text })
            assertEquals(setOf("安全"), uiState.pinnedWordsForCurrentCode)
            assertTrue(File(tempDir, "flypy.shadow.yaml").exists())

            // Long-press Block "按" -> removed from candidates
            engine.blockCandidate("按")
            assertEquals(listOf("安全", "安"), engine.state.value.candidates.map { it.text })

            // Long-press Unpin "安全" -> restores natural order (still excluding blocked "按")
            engine.unpinCandidate("安全")
            assertEquals(listOf("安", "安全"), engine.state.value.candidates.map { it.text })
            assertTrue(uiState.pinnedWordsForCurrentCode.isEmpty())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `CodetableEnhancedInputEngine wires CodetableAutoEncoder opt-in and English prefix veto live`() {
        val tempDir = Files.createTempDirectory("v361_autoenc_").toFile()
        try {
            val fakeBridge = FakeCodetableRimeClient()
            val briefIdx = BriefPrefixIndex.buildFromDictLines(
                mainDictLines = sequenceOf("---", "name: flypy", "...", "你\tni", "好\thc"),
                gt4GuardTwoPrefixes = setOf("ok", "os")
            )
            val engine = CodetableEnhancedInputEngine(
                rimeClient = fakeBridge,
                initialProfile = SchemaProfile.defaultXiaoheFlypy(),
                briefIndex = briefIdx,
                schemaDir = tempDir
            )

            // 1) English prefix veto ON by default: "gith" -> suggests "github" and does NOT commit/split
            for (c in "gith") {
                val res = engine.onKey(KeyInput.CharKey(c))
                assertTrue(res is EngineResult.Consumed)
            }
            assertTrue(engine.state.value.candidates.any { it.text == "github" })
            engine.clear()

            // Toggle English prefix veto OFF live -> "gith" no longer suggests "github"
            engine.setEnglishPrefixVetoEnabled(false)
            for (c in "gith") {
                engine.onKey(KeyInput.CharKey(c))
            }
            assertFalse(engine.state.value.candidates.any { it.text == "github" })
            engine.clear()

            // 2) CodetableAutoEncoder is strictly OFF by default
            assertFalse(engine.coordinator.autoEncodeEnabled)
            engine.onKey(KeyInput.CharKey('n'))
            engine.onKey(KeyInput.CharKey('i'))
            engine.select(0) // "你"
            engine.onKey(KeyInput.CharKey('h'))
            engine.onKey(KeyInput.CharKey('c'))
            engine.select(0) // "好"
            val userTxt = File(tempDir, "flypy_user.txt")
            assertFalse(userTxt.exists() && userTxt.readText().contains("你好\tnihc"))

            // Enable auto-encode live -> "你"(ni) + "好"(hc) creates "你好\tnihc\t1"
            engine.setAutoEncodeEnabled(true)
            engine.onKey(KeyInput.CharKey('n'))
            engine.onKey(KeyInput.CharKey('i'))
            engine.select(0)
            engine.onKey(KeyInput.CharKey('h'))
            engine.onKey(KeyInput.CharKey('c'))
            engine.select(0)
            assertTrue(userTxt.exists())
            assertTrue(userTxt.readText(StandardCharsets.UTF_8).contains("你好\tnihc\t1"))

            for (c in "nih") engine.onKey(KeyInput.CharKey(c))
            val fourthRes = engine.onKey(KeyInput.CharKey('c'))
            assertEquals(EngineResult.Commit("你好", remainComposing = false), fourthRes)

            // Also verify refreshUserDictAutoEncoder reloads phrases written via CustomUserPhraseFileEditor
            val editor = com.typefree.core.ime.importer.CustomUserPhraseFileEditor(tempDir, "flypy")
            editor.addPhrase("测试词", "cs", isTopPinned = false)
            engine.refreshUserDictAutoEncoder()
            engine.onKey(KeyInput.CharKey('c'))
            engine.onKey(KeyInput.CharKey('s'))
            assertTrue(engine.state.value.candidates.any { it.text == "测试词" })
            engine.clear()
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `odz direct command and ToolPanel ToggleSingleCharMode sync ImeUiState cleanly`() {
        val fakeBridge = FakeCodetableRimeClient()
        val singleCharEvents = ArrayList<Boolean>()
        val toasts = ArrayList<String>()
        var uiState = ImeUiState(codetableEnhancerActive = true)

        val engine = CodetableEnhancedInputEngine(
            rimeClient = fakeBridge,
            initialProfile = SchemaProfile.defaultXiaoheFlypy(),
            onToast = { toasts.add(it) },
            onSingleCharModeChanged = { enabled ->
                singleCharEvents.add(enabled)
                uiState = uiState.copy(singleCharModeEnabled = enabled)
            }
        )

        // Initially字词混排
        engine.onKey(KeyInput.CharKey('a'))
        engine.onKey(KeyInput.CharKey('n'))
        assertEquals(listOf("安", "按", "安全"), engine.state.value.candidates.map { it.text })
        engine.clear()

        // Type `odz` and select -> enables single-char mode (no double-flip)
        for (c in "odz") engine.onKey(KeyInput.CharKey(c))
        assertEquals("开启单字模式", engine.state.value.candidates.firstOrNull()?.displayText)
        val resOn = engine.select(0)
        assertTrue(resOn is EngineResult.Consumed)
        assertTrue(engine.singleCharModeEnabled)
        assertTrue(uiState.singleCharModeEnabled)
        assertEquals(listOf(true), singleCharEvents)

        // Multi-char word "安全" filtered out while single-char mode is ON
        engine.onKey(KeyInput.CharKey('a'))
        engine.onKey(KeyInput.CharKey('n'))
        assertEquals(listOf("安", "按"), engine.state.value.candidates.map { it.text })
        engine.clear()

        // Toggle via ToolPanel action helper -> disables single-char mode
        val next = engine.toggleSingleCharMode()
        assertFalse(next)
        assertFalse(uiState.singleCharModeEnabled)
        assertEquals(listOf(true, false), singleCharEvents)
    }

    @Test
    fun `RimeDataManager exposes Contract C APIs, preserves Lua for xkjd6, and merges ext reverse lookup`() = runBlocking {
        val tempRoot = Files.createTempDirectory("v361_dm_").toFile()
        try {
            val sharedDir = File(tempRoot, "shared").apply { mkdirs() }
            val userDir = File(tempRoot, "user").apply { mkdirs() }
            val fakeBridge = FakeCodetableRimeClient()
            val dm = RimeDataManager(sharedDir, userDir, fakeBridge)

            // 1) Import xkjd6 package with rime.lua + lua/topup_processor.lua -> Lua preserved
            val xkjdZip = ByteArrayOutputStream().use { baos ->
                ZipOutputStream(baos, StandardCharsets.UTF_8).use { zos ->
                    zos.putNextEntry(ZipEntry("xkjd6.schema.yaml"))
                    zos.write(
                        """
                        schema:
                          schema_id: xkjd6
                          name: 星空键道6
                        engine:
                          processors:
                            - lua_processor@topup_processor
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
                    zos.write("---\nname: xkjd6\n...\n星\txgoi\n".toByteArray(StandardCharsets.UTF_8))
                    zos.closeEntry()
                    zos.putNextEntry(ZipEntry("rime.lua"))
                    zos.write("topup_processor = require('topup_processor')\n".toByteArray(StandardCharsets.UTF_8))
                    zos.closeEntry()
                    zos.putNextEntry(ZipEntry("lua/topup_processor.lua"))
                    zos.write("return {}\n".toByteArray(StandardCharsets.UTF_8))
                    zos.closeEntry()
                }
                baos.toByteArray()
            }
            val imported = dm.importEnhancedSchemaPackage(ByteArrayInputStream(xkjdZip))
            assertEquals("xkjd6", imported.schemaId)
            assertEquals(SchemaArchetype.PUSH_TOPUP_CODE, imported.profile.archetype)
            assertTrue(File(sharedDir, "rime.lua").isFile)
            assertTrue(File(sharedDir, "lua/topup_processor.lua").isFile)
            assertTrue(PresetSchemaCatalog.BUILT_IN_PRESETS.any { it.id == "xkjd6" })

            // 2) ShadowDictManager & CustomUserPhraseFileEditor via Contract C
            val shadowMgr = dm.getShadowDictManager("xkjd6")
            shadowMgr.pinWord("xgoi", "星")
            assertEquals(listOf("星"), shadowMgr.loadRules().pinnedByCode["xgoi"])

            val phraseEditor = dm.getUserPhraseEditor("xkjd6")
            phraseEditor.addPhrase("星空键道", "xkjd", isTopPinned = false)
            assertEquals(1, phraseEditor.listPhrases(isTopPinned = false).size)

            // 3) QuickSymbols save + reset
            val customTrie = SymbolTrie(
                entries = listOf(OverlaySymbolEntry("q", "★", action = CommitAction.PlainText("★")))
            )
            dm.saveQuickSymbolsTrie(customTrie, "xkjd6")
            assertEquals("★", dm.activeSymbolTrie.findNode("q")?.entry?.displayText)
            val resetTrie = dm.resetQuickSymbolsToDefault("xkjd6")
            assertEquals("：“", resetTrie.findNode("q")?.entry?.displayText)

            // 4) importExtSubDictionary + reloadActiveEnhancedSchema + activeReverseLookupMap merge
            val extContent = "编程词典\tbcde\n键道\tjd\n"
            val desc = dm.importExtSubDictionary(
                fileName = "tech_terms.tsv",
                inputStream = ByteArrayInputStream(extContent.toByteArray(StandardCharsets.UTF_8)),
                schemaId = "xkjd6"
            )
            assertEquals("ext/tech_terms", desc.name)
            assertTrue(desc.enabled)
            assertTrue(dm.getSubDictToggleManager().listAvailableSubDicts("xkjd6").any { it.name == "ext/tech_terms" })
            assertEquals(listOf("bcde"), dm.activeReverseLookupMap["编程词典"])

            val reloadState = dm.reloadActiveEnhancedSchema("xkjd6")
            assertTrue(reloadState is RimeDeploymentState.Ready)
            assertEquals(listOf("bcde"), dm.activeReverseLookupMap["编程词典"])
        } finally {
            tempRoot.deleteRecursively()
        }
    }
}
