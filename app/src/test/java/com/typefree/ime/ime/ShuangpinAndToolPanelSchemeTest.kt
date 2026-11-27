package com.typefree.ime.ime

import com.typefree.ime.data.AppSettings
import com.typefree.ime.data.DoublePinyinLayout
import com.typefree.ime.data.InputScheme
import com.typefree.ime.i18n.AppLang
import com.typefree.ime.i18n.AppStrings
import com.typefree.ime.ime.engine.EngineResult
import com.typefree.ime.ime.engine.KeyInput
import com.typefree.ime.ime.engine.RimeDataManager
import com.typefree.ime.ime.engine.RimeInputEngine
import com.typefree.ime.ui.screens.ApiConfigBackupHelper
import com.typefree.rime.RimeBridge
import com.typefree.rime.RimeCandidate
import com.typefree.rime.RimeContextSnapshot
import com.typefree.rime.RimeSessionClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ShuangpinAndToolPanelSchemeTest {

    private class SemicolonAwareFakeRimeClient : RimeSessionClient {
        override val isNativeLoaded: Boolean = false
        override var isDeployed: Boolean = true
        override var activeSchemaId: String = "rime_ice"
            private set
        override val wordCount: Int = 1000
        override var pageSize: Int = 9

        val deployedSchemas = mutableSetOf("rime_ice", "t9", "double_pinyin_flypy")
        val rawBuffer = StringBuilder()

        override fun initialize(sharedDataDir: String, userDataDir: String): Boolean = true

        override fun deploy(primarySchemaId: String): Boolean {
            deployedSchemas.add(primarySchemaId)
            activeSchemaId = primarySchemaId
            isDeployed = true
            return true
        }

        override fun selectSchema(schemaId: String): Boolean {
            if (schemaId !in deployedSchemas) return false
            activeSchemaId = schemaId
            rawBuffer.clear()
            return true
        }

        override fun processKey(keyCode: Int, mask: Int): Boolean {
            if (keyCode == RimeBridge.XK_BackSpace) {
                if (rawBuffer.isNotEmpty()) {
                    rawBuffer.deleteCharAt(rawBuffer.length - 1)
                    return true
                }
                return false
            }
            val ch = keyCode.toChar()
            if (ch in 'a'..'z' || ch == '\'' || ch == ';') {
                rawBuffer.append(ch)
                return true
            }
            return false
        }

        override fun processKeyText(keys: String): RimeContextSnapshot {
            rawBuffer.clear()
            keys.forEach { processKey(it.code, 0) }
            return getContext(pageSize)
        }

        override fun getContext(maxCandidates: Int): RimeContextSnapshot {
            val raw = rawBuffer.toString()
            if (raw.isEmpty()) return RimeContextSnapshot()
            val cands = when (raw) {
                "q" -> listOf(RimeCandidate("去"), RimeCandidate("起"), RimeCandidate("全"))
                "q;" -> listOf(RimeCandidate("请"), RimeCandidate("轻"), RimeCandidate("清"))
                "qk" -> listOf(RimeCandidate("请"), RimeCandidate("轻"))
                else -> listOf(RimeCandidate("词$raw"), RimeCandidate("次$raw"))
            }
            val preedit = if (raw == "q;") "qing" else raw
            return RimeContextSnapshot(
                preedit = preedit,
                rawInput = raw,
                candidates = cands.take(maxCandidates),
                hasMore = cands.size > maxCandidates
            )
        }

        override fun selectCandidate(index: Int): String? {
            val picked = getContext(pageSize).candidates.getOrNull(index)?.text ?: return null
            rawBuffer.clear()
            return picked
        }

        override fun commitComposition(): String? = selectCandidate(0)
        override fun getCommit(): String? = null
        override fun clearComposition() {
            rawBuffer.clear()
        }

        override fun setOption(option: String, value: Boolean) {}
        override fun getOption(option: String): Boolean = false
        override fun syncUserData(): Boolean = true
        override fun getVersion(): String = "1.17.0-test"
    }

    @Test
    fun `All 6 built-in Shuangpin schemas and cn_en tables exist with valid speller alphabet`() {
        val assetRoot = listOf(
            File("src/main/assets/rime"),
            File("app/src/main/assets/rime")
        ).first { it.isDirectory }

        assertEquals(6, DoublePinyinLayout.entries.size)
        for (layout in DoublePinyinLayout.entries) {
            val schemaFile = File(assetRoot, layout.schemaFileName)
            val cnEnFile = File(assetRoot, layout.cnEnDictAsset)
            assertTrue("Missing schema ${schemaFile.path}", schemaFile.isFile && schemaFile.length() > 2000L)
            assertTrue("Missing cn_en ${cnEnFile.path}", cnEnFile.isFile && cnEnFile.length() > 5000L)

            val text = schemaFile.readText(Charsets.UTF_8)
            assertTrue(text.contains("schema_id: ${layout.rimeSchemaId}"))
            assertTrue(text.contains("prism: ${layout.rimeSchemaId}"))
            assertTrue(text.contains("dictionary: rime_ice"))

            val alphabetLine = text.lineSequence().first { it.trim().startsWith("alphabet:") }
            val initialsLine = text.lineSequence().first { it.trim().startsWith("initials:") }
            assertEquals(
                "Semicolon mismatch in ${layout.name}",
                layout.usesSemicolonKey,
                alphabetLine.contains(";")
            )
            assertFalse("Semicolon must never be in initials for ${layout.name}", initialsLine.contains(";"))
        }
    }

    @Test
    fun `MSPY Sogou Ziguang route semicolon as phonetic rhyme while 26-key layouts keep semicolon second-select`() {
        val fakeClient = SemicolonAwareFakeRimeClient()
        val ensureCalls = mutableListOf<String>()
        val engine = RimeInputEngine(
            rimeBridge = fakeClient,
            initialScheme = InputScheme.RIME_DOUBLE_PINYIN_FLYPY,
            initialDoublePinyinLayout = DoublePinyinLayout.MSPY,
            onEnsureSchemaDeployed = { schemaId ->
                ensureCalls += schemaId
                fakeClient.deploy(schemaId)
                fakeClient.selectSchema(schemaId)
            }
        )

        assertEquals("double_pinyin_mspy", fakeClient.activeSchemaId)
        assertEquals(listOf("double_pinyin_mspy"), ensureCalls)

        // Idle `;` still enters quick-symbol mode (`；` / `：`)
        engine.onKey(KeyInput.CharKey(';'))
        assertEquals(";", engine.state.value.rawCode)
        assertEquals("；", engine.state.value.candidates.first().text)
        engine.clear()

        // Composing `q` + `;` in MSPY spells `qing` (`请`) instead of secondary-candidate select (`起`)
        engine.onKey(KeyInput.CharKey('q'))
        val resMspy = engine.onKey(KeyInput.CharKey(';'))
        assertEquals(EngineResult.Consumed, resMspy)
        assertEquals("q;", engine.state.value.rawCode)
        assertEquals("qing", engine.state.value.preedit)
        assertEquals("请", engine.state.value.candidates.first().text)

        // Switch to 26-key Flypy: `q` + `;` triggers secondary select (`起`)
        engine.setDoublePinyinLayout(DoublePinyinLayout.FLYPY)
        assertEquals("double_pinyin_flypy", fakeClient.activeSchemaId)
        engine.onKey(KeyInput.CharKey('q'))
        val resFlypy = engine.onKey(KeyInput.CharKey(';'))
        assertEquals(EngineResult.Commit("起", remainComposing = false), resFlypy)
    }

    @Test
    fun `27-key semicolon visibility turns on for MSPY Sogou Ziguang in Chinese mode and restores 26-key in English`() {
        for (layout in listOf(DoublePinyinLayout.MSPY, DoublePinyinLayout.SOGOU, DoublePinyinLayout.ZIGUANG)) {
            assertTrue(
                BuiltInQwertyHelper.shouldShowSemicolonKey(
                    isSymbolLayer = false,
                    isFlypyChineseMode = true,
                    activeInputScheme = InputScheme.RIME_DOUBLE_PINYIN_FLYPY,
                    doublePinyinLayout = layout
                )
            )
            // English mode automatically restores standard 26-key Row 2
            assertFalse(
                BuiltInQwertyHelper.shouldShowSemicolonKey(
                    isSymbolLayer = false,
                    isFlypyChineseMode = false,
                    activeInputScheme = InputScheme.RIME_DOUBLE_PINYIN_FLYPY,
                    doublePinyinLayout = layout
                )
            )
        }
        for (layout in listOf(DoublePinyinLayout.FLYPY, DoublePinyinLayout.NATURAL, DoublePinyinLayout.ABC)) {
            assertFalse(
                BuiltInQwertyHelper.shouldShowSemicolonKey(
                    isSymbolLayer = false,
                    isFlypyChineseMode = true,
                    activeInputScheme = InputScheme.RIME_DOUBLE_PINYIN_FLYPY,
                    doublePinyinLayout = layout
                )
            )
        }
        assertFalse(
            BuiltInQwertyHelper.shouldShowSemicolonKey(
                isSymbolLayer = false,
                isFlypyChineseMode = true,
                activeInputScheme = InputScheme.RIME_PINYIN,
                doublePinyinLayout = DoublePinyinLayout.MSPY
            )
        )
    }

    @Test
    fun `Tool panel Scheme A omits redundant built-in FLYPY_TABLE and surfaces custom schema and Shuangpin names`() {
        assertEquals(
            listOf(InputScheme.RIME_PINYIN, InputScheme.RIME_T9_PINYIN, InputScheme.RIME_DOUBLE_PINYIN_FLYPY),
            ToolPanelSchemeHelper.visiblePrimarySchemes(hasCustomSchema = false, includeBuiltinFlypyTable = false)
        )
        assertEquals(
            listOf(
                InputScheme.RIME_PINYIN,
                InputScheme.RIME_T9_PINYIN,
                InputScheme.RIME_DOUBLE_PINYIN_FLYPY,
                InputScheme.RIME_CUSTOM
            ),
            ToolPanelSchemeHelper.visiblePrimarySchemes(hasCustomSchema = true, includeBuiltinFlypyTable = false)
        )

        assertEquals("微软双拼", shortInputSchemeLabel(InputScheme.RIME_DOUBLE_PINYIN_FLYPY, DoublePinyinLayout.MSPY))
        assertEquals("搜狗双拼", shortInputSchemeLabel(InputScheme.RIME_DOUBLE_PINYIN_FLYPY, DoublePinyinLayout.SOGOU))
        assertEquals("虎码官方字词", shortInputSchemeLabel(InputScheme.RIME_CUSTOM, DoublePinyinLayout.FLYPY, "虎码官方字词"))
        assertEquals(
            "Double Pinyin (Microsoft)",
            AppStrings.inputSchemeLabel(InputScheme.RIME_DOUBLE_PINYIN_FLYPY, AppLang.EN, DoublePinyinLayout.MSPY)
        )

        // Backup & restore round-trip preserves doublePinyinLayout
        val exported = """{"inputScheme":"RIME_DOUBLE_PINYIN_FLYPY","doublePinyinLayout":"SOGOU"}"""
        val merged = ApiConfigBackupHelper.parseAndMergeConfigJson(exported, AppSettings()).getOrThrow()
        assertEquals(InputScheme.RIME_DOUBLE_PINYIN_FLYPY, merged.inputScheme)
        assertEquals(DoublePinyinLayout.SOGOU, merged.doublePinyinLayout)
    }

    @Test
    fun `RimeDataManager extracts all 6 Shuangpin schemas without polluting installed custom schemas or cold deploy list`() {
        val tempRoot = Files.createTempDirectory("typefree_sp6_test").toFile()
        try {
            val sharedDir = File(tempRoot, "shared")
            val userDir = File(tempRoot, "user")
            val fakeClient = SemicolonAwareFakeRimeClient()
            val dm = RimeDataManager(
                sharedDir = sharedDir,
                userDir = userDir,
                rimeBridge = fakeClient
            )

            dm.deployDefaultSync(InputScheme.RIME_PINYIN, DoublePinyinLayout.FLYPY)

            // All 6 schemas extracted, zero misclassified as custom codetables
            for (layout in DoublePinyinLayout.entries) {
                assertTrue(File(sharedDir, layout.schemaFileName).isFile)
            }
            assertTrue(dm.listInstalledCustomSchemas().isEmpty())

            // Cold default.custom.yaml only activates default Flypy Shuangpin, not all 5 uncompiled prisms
            val coldDefaultCustom = File(sharedDir, "default.custom.yaml").readText(Charsets.UTF_8)
            assertTrue(coldDefaultCustom.contains("schema: double_pinyin_flypy"))
            assertFalse(coldDefaultCustom.contains("schema: double_pinyin_mspy"))

            // On-demand deploy when user selects Microsoft Shuangpin adds it cleanly
            assertTrue(dm.ensureBuiltInSchemaDeployedSync(DoublePinyinLayout.MSPY.rimeSchemaId))
            val updatedDefaultCustom = File(sharedDir, "default.custom.yaml").readText(Charsets.UTF_8)
            assertTrue(updatedDefaultCustom.contains("schema: double_pinyin_mspy"))
            assertEquals("double_pinyin_mspy", fakeClient.activeSchemaId)
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun `Real native librime compiles and types qing and zhong across all 6 Shuangpin layouts`() {
        val bridge = RimeBridge()
        if (!bridge.isNativeLoaded) return

        val tempRoot = Files.createTempDirectory("native_sp6_e2e_").toFile()
        try {
            val sharedDir = File(tempRoot, "shared")
            val userDir = File(tempRoot, "user")
            val dm = RimeDataManager(sharedDir = sharedDir, userDir = userDir, rimeBridge = bridge)
            dm.deployDefaultSync(InputScheme.RIME_DOUBLE_PINYIN_FLYPY, DoublePinyinLayout.FLYPY)

            val engine = RimeInputEngine(
                rimeBridge = bridge,
                initialScheme = InputScheme.RIME_DOUBLE_PINYIN_FLYPY,
                initialDoublePinyinLayout = DoublePinyinLayout.FLYPY,
                onEnsureSchemaDeployed = { dm.ensureBuiltInSchemaDeployedSync(it) }
            )

            val cases = listOf(
                Triple(DoublePinyinLayout.FLYPY, "qk", "vs"),
                Triple(DoublePinyinLayout.NATURAL, "qy", "vs"),
                Triple(DoublePinyinLayout.MSPY, "q;", "vs"),
                Triple(DoublePinyinLayout.SOGOU, "q;", "vs"),
                Triple(DoublePinyinLayout.ABC, "qy", "as"),
                Triple(DoublePinyinLayout.ZIGUANG, "q;", "uh")
            )

            for ((layout, qingKeys, zhongKeys) in cases) {
                engine.setDoublePinyinLayout(layout)
                assertEquals(layout.rimeSchemaId, bridge.activeSchemaId)

                for (ch in qingKeys) {
                    engine.onKey(KeyInput.CharKey(ch))
                }
                val qingTexts = engine.state.value.candidates.map { it.text }
                assertTrue(
                    "Expected qing candidates (请/轻/清) for ${layout.name}($qingKeys), got $qingTexts (preedit=${engine.state.value.preedit})",
                    qingTexts.any { it in setOf("请", "轻", "清", "情") }
                )
                assertTrue(
                    "Expected preedit containing qing for ${layout.name}, got ${engine.state.value.preedit}",
                    engine.state.value.preedit.contains("qing")
                )
                engine.clear()

                for (ch in zhongKeys) {
                    engine.onKey(KeyInput.CharKey(ch))
                }
                val zhongTexts = engine.state.value.candidates.map { it.text }
                assertTrue(
                    "Expected zhong candidates (中/种/重) for ${layout.name}($zhongKeys), got $zhongTexts",
                    zhongTexts.any { it in setOf("中", "种", "重", "终") }
                )
                engine.clear()
            }
        } finally {
            tempRoot.deleteRecursively()
        }
    }
}
