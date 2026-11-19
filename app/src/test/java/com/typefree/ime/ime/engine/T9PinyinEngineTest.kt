package com.typefree.ime.ime.engine

import com.typefree.ime.data.InputScheme
import com.typefree.ime.ime.nextCycledInputScheme
import com.typefree.ime.ime.shortInputSchemeLabel
import com.typefree.rime.RimeBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class T9PinyinEngineTest {

    @Test
    fun syllableMatcher_prioritizesPreferredAndValidMandarinSyllables() {
        val for64426 = T9PinyinHelper.matchLeadingSyllables("64426", preferredLeadingSyllable = "ni")
        assertEquals("ni", for64426.first())
        assertTrue("Expected 'mi' in $for64426", "mi" in for64426)
        assertFalse("Invalid consonant-only 'oh' must not appear", "oh" in for64426)

        val for426 = T9PinyinHelper.matchLeadingSyllables("426")
        assertTrue(for426.containsAll(listOf("hao", "gao", "gan", "han")))
        assertFalse("Impossible initial 'i' must not appear for digit 4", "i" in for426)

        val for8 = T9PinyinHelper.matchLeadingSyllables("8")
        assertEquals(listOf("t"), for8)
    }

    @Test
    fun formatT9Preedit_rendersReadablePinyinWithPartialPrefix() {
        assertEquals(
            "ni hao",
            T9PinyinHelper.formatT9Preedit(rimePreedit = "64 426", topCandidateComment = "ni hao")
        )
        assertEquals(
            "今天 tian qi",
            T9PinyinHelper.formatT9Preedit(rimePreedit = "今天8426 74", topCandidateComment = "tian qi")
        )
        assertEquals(
            "今天 tian qi",
            T9PinyinHelper.formatT9Preedit(
                rimePreedit = "tian'74",
                topCandidateComment = "tian qi",
                pendingCommittedPrefix = "今天"
            )
        )
        assertEquals(
            "mi'gao",
            T9PinyinHelper.formatT9Preedit(rimePreedit = "mi'gao", topCandidateComment = "")
        )
    }

    @Test
    fun schemeCycling_includesT9AndReturnsToPinyin() {
        assertEquals("九键拼音", shortInputSchemeLabel(InputScheme.RIME_T9_PINYIN))
        assertEquals(InputScheme.RIME_T9_PINYIN, nextCycledInputScheme(InputScheme.RIME_PINYIN))
        assertEquals(InputScheme.RIME_DOUBLE_PINYIN_FLYPY, nextCycledInputScheme(InputScheme.RIME_T9_PINYIN))
    }

    @Test
    fun nativeLibrime_t9FullSentenceSyllableLockBackspaceAndPartialSelect() {
        val bridge = RimeBridge()
        assumeTrue("Host native librime_jni must be available", bridge.isNativeLoaded)

        val root = Files.createTempDirectory("typefree_t9_native_test_").toFile()
        try {
            val dataManager = RimeDataManager(
                sharedDir = File(root, "shared"),
                userDir = File(root, "user"),
                rimeBridge = bridge,
                appContext = null
            )
            val deployState = dataManager.deployDefaultSync(InputScheme.RIME_T9_PINYIN)
            assertTrue("Expected Ready state, got $deployState", deployState is RimeDeploymentState.Ready)
            assertTrue(
                "Built-in t9 schema must not be reported as custom schema",
                dataManager.listInstalledCustomSchemas().none { it.schemaId == "t9" }
            )

            val engine = RimeInputEngine(
                rimeBridge = bridge,
                initialScheme = InputScheme.RIME_T9_PINYIN,
                externalEnhancedSchemePredicate = { false }
            )

            // 1. Type `64426` (ni hao)
            for (ch in "64426") {
                engine.onKey(KeyInput.CharKey(ch))
            }
            val s1 = engine.state.value
            assertTrue(s1.isComposing)
            assertEquals("你好", s1.candidates.firstOrNull()?.text)
            assertEquals("ni hao", s1.preedit)
            assertEquals("ni", s1.t9Syllables.firstOrNull())
            assertTrue("mi" in s1.t9Syllables)
            assertEquals("Candidate chip codeHint stays clean in T9", "", s1.candidates.first().codeHint)

            // 2. Tap left-rail syllable `mi` -> locks `64` to `mi'`, advances rail to `426`
            engine.lockT9Syllable("mi")
            val s2 = engine.state.value
            assertEquals("mi'426", s2.rawCode)
            assertTrue(s2.t9Syllables.any { it in setOf("hao", "gao", "gan", "han") })
            assertTrue("Candidates after locking 'mi' should not start with 你", s2.candidates.firstOrNull()?.text != "你好")

            // 3. Lock second syllable `gao` -> `mi'gao`, then Backspace unlocks `gao` back to `mi'426`
            engine.lockT9Syllable("gao")
            assertEquals("mi'gao", engine.state.value.rawCode)
            engine.onKey(KeyInput.Backspace())
            assertEquals("mi'426", engine.state.value.rawCode)

            // 4. Backspace deletes trailing digit `6` -> `mi'42`
            engine.onKey(KeyInput.Backspace())
            assertEquals("mi'42", engine.state.value.rawCode)

            // 5. Clear and test long sentence `5468426842674` (今天天气) + partial select + left-rail lock
            engine.clear()
            for (ch in "5468426842674") {
                engine.onKey(KeyInput.CharKey(ch))
            }
            val sLong = engine.state.value
            assertEquals("今天天气", sLong.candidates.firstOrNull()?.text)
            val jintianIdx = sLong.candidates.indexOfFirst { it.text == "今天" }
            assertTrue("Expected partial candidate '今天' in ${sLong.candidates.map { it.text }}", jintianIdx > 0)

            val partialRes = engine.select(jintianIdx)
            assertEquals(EngineResult.Consumed, partialRes)
            val sAfterPartial = engine.state.value
            assertTrue("Preedit should keep selected prefix '今天': ${sAfterPartial.preedit}", sAfterPartial.preedit.startsWith("今天"))
            assertTrue("Left rail should advance to 'tian': ${sAfterPartial.t9Syllables}", "tian" in sAfterPartial.t9Syllables)

            // Lock 'tian' after partial select, then commit full sentence via Space
            engine.lockT9Syllable("tian")
            val sAfterLock = engine.state.value
            assertTrue(sAfterLock.preedit.startsWith("今天"))
            val commitRes = engine.onKey(KeyInput.Space) as? EngineResult.Commit
            assertEquals("今天天气", commitRes?.text)
            assertFalse(engine.state.value.isComposing)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun nativeLibrime_t9ToXiaoheSwitchAndRestorePreservesPrecompiledFlypy() {
        val bridge = RimeBridge()
        assumeTrue("Host native librime_jni must be available", bridge.isNativeLoaded)
        assumeTrue("Only runs when xiaohe flavor assets are staged", com.typefree.ime.BuildConfig.IS_XIAOHE_FLAVOR)

        val root = Files.createTempDirectory("typefree_t9_xiaohe_regression_").toFile()
        try {
            val shared = File(root, "shared")
            val user = File(root, "user")
            val dm = RimeDataManager(sharedDir = shared, userDir = user, rimeBridge = bridge, appContext = null)

            // 1. Deploy starting on T9, then switch to FLYPY_TABLE
            assertTrue(dm.deployDefaultSync(InputScheme.RIME_T9_PINYIN) is RimeDeploymentState.Ready)
            assertTrue(dm.activateEnhancedSchemeSync(InputScheme.FLYPY_TABLE))
            assertEquals("flypy", bridge.activeSchemaId)
            assertTrue(
                "Brief index must be probed on flypy (not polluted by t9), got brief1=${dm.activeBriefIndex.totalBrief1Entries}",
                dm.activeBriefIndex.totalBrief1Entries in 20..80
            )

            val enh = CodetableEnhancedInputEngine(
                rimeClient = bridge,
                initialProfile = dm.activeSchemaProfile,
                briefIndex = dm.activeBriefIndex,
                schemaDir = shared
            )
            enh.onKey(KeyInput.CharKey('h'))
            enh.onKey(KeyInput.CharKey('v'))
            assertEquals("会", enh.state.value.candidates.firstOrNull()?.text)

            // 2. Pruned duplicate 3-code `hvz` should trigger live 4-code continuation `汇总(~s)`, then `s` commits `汇总`
            enh.onKey(KeyInput.CharKey('z'))
            assertTrue(
                "Expected 汇总 in hvz live continuations: ${enh.state.value.candidates}",
                enh.state.value.candidates.any { it.text == "汇总" && it.codeHint == "~s" }
            )
            val commit4 = enh.onKey(KeyInput.CharKey('s')) as? EngineResult.Commit
            assertEquals("汇总", commit4?.text)

            // 3. restoreDefaultRimeDataSync must preserve precompiled flypy.table.bin
            assertTrue(dm.restoreDefaultRimeDataSync(InputScheme.FLYPY_TABLE) is RimeDeploymentState.Ready)
            assertTrue(File(user, "build/flypy.table.bin").length() > 100_000L)
            enh.clear()
            enh.onKey(KeyInput.CharKey('h'))
            enh.onKey(KeyInput.CharKey('v'))
            assertEquals("会", enh.state.value.candidates.firstOrNull()?.text)
        } finally {
            root.deleteRecursively()
        }
    }
}
