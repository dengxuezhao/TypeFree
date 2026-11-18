package com.typefree.core.ime

import com.typefree.core.ime.coordinator.CommitAction
import com.typefree.core.ime.coordinator.CoordinatorKeyOutcome
import com.typefree.core.ime.coordinator.DirectCommandType
import com.typefree.core.ime.coordinator.RimeCoreSessionPort
import com.typefree.core.ime.coordinator.RimeDecodeFrame
import com.typefree.core.ime.coordinator.TypeFreeKeyCoordinator
import com.typefree.core.ime.enhancer.BriefPrefixIndex
import com.typefree.core.ime.enhancer.CodetableAutoEncoder
import com.typefree.core.ime.enhancer.DirectCommandEngine
import com.typefree.core.ime.enhancer.EncodedPhrase
import com.typefree.core.ime.enhancer.EnglishWordTrie
import com.typefree.core.ime.enhancer.NaturalContinuation
import com.typefree.core.ime.enhancer.ReverseSplitEngine
import com.typefree.core.ime.enhancer.ShadowDictManager
import com.typefree.core.ime.profile.SchemaArchetype
import com.typefree.core.ime.profile.SchemaProfile
import com.typefree.core.ime.profile.TypeFreeTomlParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.LocalDate
import java.util.BitSet

class CodetableV361EnhancementsTest {

    private class StubRimePort(
        private val frameMap: Map<String, List<Pair<String, String>>> = emptyMap()
    ) : RimeCoreSessionPort {
        private val buf = StringBuilder()
        private val options = HashMap<String, Boolean>()
        var selectCalls = 0
        var lastSelectedRimeIndex: Int = -1

        override fun sendChar(ch: Char): RimeDecodeFrame {
            buf.append(ch)
            return currentFrame()
        }

        override fun sendBackspace(): RimeDecodeFrame {
            if (buf.isNotEmpty()) buf.deleteCharAt(buf.length - 1)
            return currentFrame()
        }

        override fun selectCandidate(index: Int): Pair<String?, RimeDecodeFrame> {
            selectCalls++
            lastSelectedRimeIndex = index
            val picked = currentFrame().candidates.getOrNull(index)?.first
            buf.clear()
            return picked to currentFrame()
        }

        override fun commitCurrentComposition(): String? {
            val top = currentFrame().candidates.firstOrNull()?.first
            buf.clear()
            return top
        }

        override fun clearComposition() {
            buf.clear()
        }

        override fun currentFrame(maxCandidates: Int): RimeDecodeFrame {
            val raw = buf.toString()
            if (raw.isEmpty()) return RimeDecodeFrame()
            return RimeDecodeFrame(
                rawInput = raw,
                preedit = raw,
                candidates = frameMap[raw].orEmpty().take(maxCandidates)
            )
        }

        override fun setOption(option: String, value: Boolean) {
            options[option] = value
        }

        override fun getOption(option: String): Boolean = options[option] ?: false
    }

    @Test
    fun `1_natural_continuation_hoists_okhg_and_qfx_without_breaking_n_okhguu_or_hfkn`() {
        val dictLines = listOf(
            "---",
            "name: flypy",
            "...",
            "你\tn",
            "内容\tn",
            "很\thf",
            "可能\tkn",
            "困难\tkn",
            "丁\tokhguu",
            "百\tokhgbd",
            "下\tokhgbu",
            "𠀢\tokhgci",
            "清风徐来\tqfxl",
            "八码长词\tabcdefgh"
        )
        val idx = BriefPrefixIndex.buildFromDictLines(
            mainDictLines = dictLines.asSequence(),
            gt4GuardTwoPrefixes = setOf("ok", "os")
        )

        // Check natural first continuations for lengths 2..7
        assertEquals(NaturalContinuation("丁", "hguu", "okhguu"), idx.lookupNaturalFirstContinuation("ok"))
        assertEquals(NaturalContinuation("丁", "guu", "okhguu"), idx.lookupNaturalFirstContinuation("okh"))
        assertEquals(NaturalContinuation("丁", "uu", "okhguu"), idx.lookupNaturalFirstContinuation("okhg"))
        assertEquals(NaturalContinuation("丁", "u", "okhguu"), idx.lookupNaturalFirstContinuation("okhgu"))
        assertNull(idx.lookupNaturalFirstContinuation("okhguu"))
        assertEquals(NaturalContinuation("清风徐来", "l", "qfxl"), idx.lookupNaturalFirstContinuation("qfx"))
        assertEquals(NaturalContinuation("八码长词", "h", "abcdefgh"), idx.lookupNaturalFirstContinuation("abcdefg"))
        assertNull(idx.lookupNaturalFirstContinuation("a"))

        // Snapshot v2 roundtrip
        val tempDir = Files.createTempDirectory("brief_v2_").toFile()
        try {
            val snapV2 = File(tempDir, "flypy.brief.idx")
            idx.writeSnapshot(snapV2)
            val reloaded = BriefPrefixIndex.readSnapshot(snapV2)
            assertEquals(NaturalContinuation("丁", "uu", "okhguu"), reloaded.lookupNaturalFirstContinuation("okhg"))
            assertEquals(NaturalContinuation("清风徐来", "l", "qfxl"), reloaded.lookupNaturalFirstContinuation("qfx"))

            // Snapshot v1 backwards compatibility (must not crash)
            val snapV1 = File(tempDir, "legacy_v1.brief.idx")
            DataOutputStream(BufferedOutputStream(FileOutputStream(snapV1))).use { out ->
                out.writeInt(0x54464249) // SNAPSHOT_MAGIC
                out.writeInt(1) // v1
                repeat(26) { out.writeShort(0) }
                repeat(676) { out.writeShort(0) }
                out.writeInt(0) // bitLen
                out.writeInt(0) // gt4Count
                out.writeShort(0) // guardCount
            }
            val v1Idx = BriefPrefixIndex.readSnapshot(snapV1)
            assertNull(v1Idx.lookupNaturalFirstContinuation("okhg"))
        } finally {
            tempDir.deleteRecursively()
        }

        // Coordinator integration
        val port = StubRimePort(
            mapOf(
                "n" to listOf("你" to "", "内容" to ""),
                "okhg" to listOf("百" to "~bd", "下" to "~bu", "𠀢" to "~ci", "丁" to "~uu"),
                "okhguu" to listOf("丁" to ""),
                "qfx" to emptyList()
            )
        )
        val coord = TypeFreeKeyCoordinator(
            profile = SchemaProfile.defaultXiaoheFlypy(),
            rimePort = port,
            reverseSplitEngine = ReverseSplitEngine(idx)
        )

        // a) okhg hoists 丁 (~uu) to index 0 ahead of abc-sorted completions without duplicating 丁
        for (c in "okhg") coord.onCharKey(c)
        val okhgCands = coord.snapshotUi().candidates
        assertEquals(listOf("丁", "百", "下", "𠀢"), okhgCands.map { it.commitText })
        assertEquals("~uu", okhgCands[0].codeHint)
        val pickDing = coord.selectCandidate(0) as CoordinatorKeyOutcome.Commit
        assertEquals("丁", (pickDing.action as CommitAction.PlainText).text)
        assertFalse(coord.isComposing)

        // b) okhguu exact match stays intact (not hijacked)
        for (c in "okhgu") coord.onCharKey(c)
        val okhguuOut = coord.onCharKey('u') as CoordinatorKeyOutcome.Consumed
        assertEquals("丁", okhguuOut.ui.candidates[0].commitText)
        assertEquals("", okhguuOut.ui.candidates[0].codeHint)
        coord.reset()

        // c) qfx emits 清风徐来 (~l) and selecting commits and clears composing buffer
        for (c in "qfx") coord.onCharKey(c)
        val qfxCands = coord.snapshotUi().candidates
        assertEquals("清风徐来", qfxCands[0].commitText)
        assertEquals("~l", qfxCands[0].codeHint)
        val pickQfx = coord.selectCandidate(0) as CoordinatorKeyOutcome.Commit
        assertEquals("清风徐来", (pickQfx.action as CommitAction.PlainText).text)
        assertFalse(coord.isComposing)

        // d) Regression: n -> [你, 内容] and hfkn -> 4-code reverse split [很可能, 很困难]
        coord.onCharKey('n')
        assertEquals(listOf("你", "内容"), coord.snapshotUi().candidates.map { it.commitText })
        coord.reset()

        for (c in "hfkn") coord.onCharKey(c)
        val hfknUi = coord.snapshotUi()
        assertTrue(hfknUi.isReverseSplitActive)
        assertEquals("很可能", hfknUi.candidates[0].commitText)
        assertEquals("很困难", hfknUi.candidates[1].commitText)
    }

    @Test
    fun `2_english_word_trie_veto_and_schema_toml_roundtrip`() {
        val trie = EnglishWordTrie.defaultBuiltIn()
        val requiredWords = listOf(
            "github", "kotlin", "python", "android", "google", "gradle", "studio", "docker",
            "gemini", "claude", "typescript", "javascript", "flutter", "chrome", "apple",
            "iphone", "macbook", "keyboard", "shadow", "phrase", "config", "commit",
            "branch", "merge", "deploy", "search", "import", "export"
        )
        for (w in requiredWords) {
            assertTrue("Missing required English word: $w", trie.containsWord(w))
        }
        assertTrue(trie.hasLongerWordPrefix("gith"))
        assertTrue(trie.hasLongerWordPrefix("githu"))
        assertTrue(trie.suggestWords("gith", 6).contains("github"))

        // Verify >= 600 embedded words via suggestWords across a..z
        val allEmbedded = ('a'..'z').flatMap { c -> trie.suggestWords(c.toString(), 500) }.distinct()
        assertTrue("Expected >= 600 built-in English words, got ${allEmbedded.size}", allEmbedded.size >= 600)
        assertTrue(allEmbedded.all { it.length >= 5 && it.all { ch -> ch in 'a'..'z' } })

        // SchemaProfile & TypeFreeTomlParser roundtrip
        val defaultProfile = SchemaProfile.defaultXiaoheFlypy()
        assertTrue(defaultProfile.englishPrefixVetoEnabled)
        assertFalse(defaultProfile.autoEncodePhrasesEnabled)

        val customized = defaultProfile.copy(
            englishPrefixVetoEnabled = false,
            autoEncodePhrasesEnabled = true
        )
        val toml = TypeFreeTomlParser.serialize(customized)
        assertTrue(toml.contains("english_prefix_veto_enabled = false"))
        assertTrue(toml.contains("auto_encode_phrases_enabled = true"))
        val parsed = TypeFreeTomlParser.parse(toml)
        assertFalse(parsed.englishPrefixVetoEnabled)
        assertTrue(parsed.autoEncodePhrasesEnabled)

        // Coordinator English veto flow: gith -> githu -> github (vetoes split & 5th-key commit)
        val idx = BriefPrefixIndex.buildFromDictLines(
            mainDictLines = sequenceOf(
                "---",
                "name: flypy",
                "...",
                "给\tgi",
                "他\tth",
                "她\tth",
                "丁\tokhguu"
            ),
            gt4GuardTwoPrefixes = setOf("ok", "os")
        )
        val port = StubRimePort(
            mapOf(
                "git" to listOf("给" to "~i"),
                "word" to listOf("我日" to "") // exact Chinese match on 4-char buffer
            )
        )
        val customTrie = EnglishWordTrie(listOf("github", "typescript", "implementation"))
        val coord = TypeFreeKeyCoordinator(
            profile = SchemaProfile.defaultXiaoheFlypy(),
            rimePort = port,
            reverseSplitEngine = ReverseSplitEngine(idx),
            englishWordTrie = customTrie
        )

        for (c in "gith") {
            assertTrue(coord.onCharKey(c) is CoordinatorKeyOutcome.Consumed)
        }
        assertFalse("gith must veto reverse split", coord.snapshotUi().isReverseSplitActive)
        assertEquals(listOf("github"), coord.snapshotUi().candidates.map { it.commitText })

        // 5th key 'u' and 6th key 'b' extend buffer cleanly without 5th-key auto-commit
        val fifth = coord.onCharKey('u')
        assertTrue(fifth is CoordinatorKeyOutcome.Consumed)
        assertEquals("githu", (fifth as CoordinatorKeyOutcome.Consumed).ui.rawCode)
        assertEquals(listOf("github"), fifth.ui.candidates.map { it.commitText })

        val sixth = coord.onCharKey('b')
        assertTrue(sixth is CoordinatorKeyOutcome.Consumed)
        assertEquals("github", (sixth as CoordinatorKeyOutcome.Consumed).ui.rawCode)

        val spaceOut = coord.onSpace() as CoordinatorKeyOutcome.Commit
        assertEquals("github", (spaceOut.action as CommitAction.PlainText).text)
        assertFalse(coord.isComposing)

        // Long 14-char English word stays extensible up to 14 letters
        for (c in "implementation") {
            assertTrue(coord.onCharKey(c) is CoordinatorKeyOutcome.Consumed)
        }
        assertEquals("implementation", coord.snapshotUi().rawCode)
        assertEquals("implementation", ((coord.onSpace() as CoordinatorKeyOutcome.Commit).action as CommitAction.PlainText).text)

        // Backspace from githu -> gith -> git restores Rime candidates
        for (c in "githu") coord.onCharKey(c)
        coord.onBackspace()
        assertEquals("gith", coord.snapshotUi().rawCode)
        coord.onBackspace()
        assertEquals("git", coord.snapshotUi().rawCode)
        assertEquals("给", coord.snapshotUi().candidates.first().commitText)
        coord.reset()

        // Exact Rime Chinese match on 4-char code is NOT hijacked by English veto
        val chinesePort = StubRimePort(mapOf("word" to listOf("字一" to "", "字二" to "")))
        val coordWithExact = TypeFreeKeyCoordinator(
            profile = SchemaProfile.defaultXiaoheFlypy(),
            rimePort = chinesePort,
            reverseSplitEngine = ReverseSplitEngine(idx),
            englishWordTrie = EnglishWordTrie(listOf("words"))
        )
        for (c in "word") coordWithExact.onCharKey(c)
        assertEquals(listOf("字一", "字二"), coordWithExact.snapshotUi().candidates.map { it.commitText })
        coordWithExact.reset()

        // setEnglishPrefixVetoEnabled(false) allows reverse split on gith again
        coord.setEnglishPrefixVetoEnabled(false)
        for (c in "gith") coord.onCharKey(c)
        assertTrue(coord.snapshotUi().isReverseSplitActive)
        assertEquals("给他", coord.snapshotUi().candidates.first().commitText)
    }

    @Test
    fun `3_shadow_dict_manager_pin_block_persistence_and_selection_consistency`() {
        val tempDir = Files.createTempDirectory("shadow_dict_").toFile()
        try {
            val extB = "\uD867\uDF7E" // 𩽾
            val mgr = ShadowDictManager(tempDir, "flypy")
            mgr.pinWord("an", "暗")
            mgr.pinWord("an", extB)
            mgr.blockWord("安")

            val shadowFile = File(tempDir, "flypy.shadow.yaml")
            assertTrue(shadowFile.exists())

            val mgrReloaded = ShadowDictManager(tempDir, "flypy")
            val state = mgrReloaded.loadRules()
            assertEquals(listOf(extB, "暗"), state.pinnedByCode["an"])
            assertTrue("安" in state.blockedWords)

            // Unpin and unblock
            mgrReloaded.unpinWord("an", extB)
            mgrReloaded.unblockWord("安")
            val afterUn = ShadowDictManager(tempDir, "flypy").loadRules()
            assertEquals(listOf("暗"), afterUn.pinnedByCode["an"])
            assertFalse("安" in afterUn.blockedWords)

            // Re-block 安 and pin both Rime-resident 暗 and virtual 自定义
            mgrReloaded.blockWord("安")
            mgrReloaded.pinWord("an", "自定义")

            val port = StubRimePort(
                mapOf(
                    "an" to listOf("安" to "", "按" to "", "暗" to "")
                )
            )
            // Test with lockNaturalOrder = false to verify selectCandidate handles virtual/reordered indices accurately!
            val coord = TypeFreeKeyCoordinator(
                profile = SchemaProfile.defaultXiaoheFlypy().copy(lockNaturalOrder = false),
                rimePort = port,
                shadowDictManager = mgrReloaded
            )

            coord.onCharKey('a')
            coord.onCharKey('n')
            assertEquals(listOf("自定义", "暗"), coord.currentPinnedWordsForBuffer())
            val cands = coord.snapshotUi().candidates
            assertEquals(listOf("自定义", "暗", "按"), cands.map { it.commitText })

            // Selecting index 0 (virtually injected 自定义) must commit 自定义 without calling Rime selectCandidate
            val pickVirtual = coord.selectCandidate(0) as CoordinatorKeyOutcome.Commit
            assertEquals("自定义", (pickVirtual.action as CommitAction.PlainText).text)
            assertEquals(0, port.selectCalls)

            // Selecting index 1 (promoted 暗 from Rime index 2) must commit 暗 and pass original Rime index 2!
            coord.onCharKey('a')
            coord.onCharKey('n')
            val pickPromoted = coord.selectCandidate(1) as CoordinatorKeyOutcome.Commit
            assertEquals("暗", (pickPromoted.action as CommitAction.PlainText).text)
            assertEquals(2, port.lastSelectedRimeIndex)

            // reloadShadowRules reflects clearAll
            mgrReloaded.clearAll()
            coord.reloadShadowRules()
            coord.onCharKey('a')
            coord.onCharKey('n')
            assertEquals(listOf("安", "按", "暗"), coord.snapshotUi().candidates.map { it.commitText })
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `4_codetable_auto_encoder_disabled_by_default_and_encodes_2_3_4_char_phrases`() {
        val tempDir = Files.createTempDirectory("auto_enc_").toFile()
        try {
            val userDictFile = File(tempDir, "flypy_user.txt")
            val encoder = CodetableAutoEncoder(userDictFile, "flypy")

            // 2-char phrase: AaAbBaBb
            assertNull(encoder.recordCommittedUnit("天", "tm"))
            val p2 = encoder.recordCommittedUnit("下", "xx")
            assertEquals(EncodedPhrase("天下", "tmxx"), p2)
            assertEquals(listOf("天下"), encoder.lookupExact("tmxx"))

            // 3-char phrase: AaBaCaCb
            encoder.resetSession()
            assertNull(encoder.recordCommittedUnit("张", "vh"))
            assertNotNull(encoder.recordCommittedUnit("三", "sj"))
            val p3 = encoder.recordCommittedUnit("丰", "fg")
            assertEquals(EncodedPhrase("张三丰", "vsfg"), p3)
            assertEquals(listOf("张三丰"), encoder.lookupExact("vsfg"))

            // 4-char phrase: AaBaCaZa
            encoder.resetSession()
            encoder.recordCommittedUnit("一", "yg")
            encoder.recordCommittedUnit("心", "xw")
            encoder.recordCommittedUnit("一", "yc")
            val p4 = encoder.recordCommittedUnit("意", "yi")
            assertEquals(EncodedPhrase("一心一意", "yxyy"), p4)
            assertEquals(listOf("一心一意"), encoder.lookupExact("yxyy"))

            // Verify Rime tabledb header & word\tcode\t1 persistence across reload
            val text = userDictFile.readText(StandardCharsets.UTF_8)
            assertTrue(text.contains("# Rime table"))
            assertTrue(text.contains("#@/db_type\ttabledb"))
            assertTrue(text.contains("天下\ttmxx\t1"))
            assertTrue(text.contains("张三丰\tvsfg\t1"))
            assertTrue(text.contains("一心一意\tyxyy\t1"))

            val reloaded = CodetableAutoEncoder(userDictFile, "flypy")
            assertEquals(listOf("天下"), reloaded.lookupExact("tmxx"))
            assertTrue(reloaded.removePhrase("天下", "tmxx"))
            assertTrue(reloaded.lookupExact("tmxx").isEmpty())

            // Coordinator: strictly disabled by default!
            val coordUserFile = File(tempDir, "coord_user.txt")
            val coordEncoder = CodetableAutoEncoder(coordUserFile, "flypy")
            val port = StubRimePort(
                mapOf(
                    "tm" to listOf("天" to ""),
                    "xx" to listOf("下" to ""),
                    "tmxx" to listOf("同心" to "")
                )
            )
            val coord = TypeFreeKeyCoordinator(
                profile = SchemaProfile.defaultXiaoheFlypy().copy(autoCommitUniqueFull = false),
                rimePort = port,
                autoEncoder = coordEncoder
            )
            assertFalse(coord.autoEncodeEnabled)

            // Disabled: committing 天 then 下 must NOT encode tmxx
            coord.onCharKey('t'); coord.onCharKey('m'); coord.onSpace()
            coord.onCharKey('x'); coord.onCharKey('x'); coord.onSpace()
            assertTrue(coordEncoder.lookupExact("tmxx").isEmpty())

            // Enabled explicitly: committing 天 then 下 encodes tmxx and serves as Layer +2!
            coord.setAutoEncodeEnabled(true)
            coord.onCharKey('t'); coord.onCharKey('m'); coord.onSpace()
            coord.onCharKey('x'); coord.onCharKey('x'); coord.onSpace()
            assertEquals(listOf("天下"), coordEncoder.lookupExact("tmxx"))

            for (c in "tmxx") coord.onCharKey(c)
            val cands = coord.snapshotUi().candidates
            assertEquals(listOf("同心", "天下"), cands.map { it.commitText })
            assertEquals("造词", cands[1].codeHint)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `5_direct_commands_orq_offset_odz_oqm_restriction_and_zz_symbol_groups`() {
        val fixedDate = LocalDate.of(2026, 11, 18)
        var currentSchema = "flypy"
        var singleChar = false
        val engine = DirectCommandEngine(
            activeSchemaIdProvider = { currentSchema },
            singleCharModeStateProvider = { singleChar },
            dateProvider = { fixedDate }
        )

        // orq+N / orq-N / date+N / date-N
        val tomorrow = engine.evaluate("orq+1")
        assertEquals("2026年11月19日", tomorrow[0].commitText)
        assertEquals("2026-11-19", tomorrow[1].commitText)
        assertEquals("农历", tomorrow[2].codeHint)

        val weekAgo = engine.evaluate("orq-7")
        assertEquals("2026年11月11日", weekAgo[0].commitText)
        assertEquals("2026-11-11", weekAgo[1].commitText)

        val datePlus30 = engine.evaluate("date+30")
        assertEquals("2026-12-18", datePlus30[1].commitText)
        val dateMinus0 = engine.evaluate("date-0")
        assertEquals("2026-11-18", dateMinus0[1].commitText)
        assertEquals(3, engine.evaluate("orq+3650").size)
        assertTrue(engine.evaluate("orq+3651").isEmpty())

        // oqm strictly restricted to flypy
        assertTrue(engine.evaluate("oqm").isNotEmpty())
        currentSchema = "xkjd6"
        assertTrue(engine.evaluate("oqm").isEmpty())
        assertFalse(engine.isCommandPrefixOrMatch("oqm"))
        currentSchema = "wubi86"
        assertTrue(engine.evaluate("oqm").isEmpty())
        currentSchema = "flypy"

        // odz toggle candidate text
        val odzOn = engine.evaluate("odz")
        assertEquals("开启单字模式", odzOn[0].displayText)
        assertEquals(CommitAction.Command(DirectCommandType.TOGGLE_SINGLE_CHAR_MODE), odzOn[0].action)
        singleChar = true
        assertEquals("关闭单字模式", engine.evaluate("odz")[0].displayText)

        // zz overview & zza..zzf groups
        val zz = engine.evaluate("zz")
        val zzDisplay = zz.map { it.displayText }
        assertTrue(zzDisplay.contains("zza 常用符号"))
        assertTrue(zzDisplay.contains("zzb 数学/单位"))
        assertTrue(zzDisplay.contains("zzc 序号/编号"))
        assertTrue(zzDisplay.contains("zzd 箭头/方向"))
        assertTrue(zzDisplay.contains("zze 制表/几何"))
        assertTrue(zzDisplay.contains("zzf 标点/括号"))
        assertTrue(zz.any { it.commitText == "★" })

        for (group in listOf("zza", "zzb", "zzc", "zzd", "zze", "zzf")) {
            val groupCands = engine.evaluate(group)
            assertTrue("Expected rich symbols for $group", groupCands.size >= 20)
            assertTrue(groupCands.all { it.commitText.isNotEmpty() })
        }

        // Coordinator end-to-end: orq+1 typing + odz toggling singleCharMode + zzba fallback to Rime
        val port = StubRimePort(
            mapOf(
                "zzba" to listOf("走吧" to ""),
                "oqm" to listOf("全码词" to "")
            )
        )
        val coord = TypeFreeKeyCoordinator(
            profile = SchemaProfile.defaultXiaoheFlypy(),
            rimePort = port,
            directCommandEngine = DirectCommandEngine(dateProvider = { fixedDate })
        )

        for (c in "orq+1") coord.onCharKey(c)
        val dateCommit = coord.onSpace() as CoordinatorKeyOutcome.Commit
        assertEquals("2026年11月19日", (dateCommit.action as CommitAction.PlainText).text)

        // odz toggles singleCharMode ON then OFF
        assertFalse(coord.singleCharMode)
        for (c in "odz") coord.onCharKey(c)
        assertEquals("开启单字模式", coord.snapshotUi().candidates[0].displayText)
        val toggleOn = coord.onSpace() as CoordinatorKeyOutcome.Commit
        assertEquals(CommitAction.Command(DirectCommandType.TOGGLE_SINGLE_CHAR_MODE), toggleOn.action)
        assertTrue(coord.singleCharMode)

        for (c in "odz") coord.onCharKey(c)
        assertEquals("关闭单字模式", coord.snapshotUi().candidates[0].displayText)
        coord.onSpace()
        assertFalse(coord.singleCharMode)

        // zz -> zzb (direct symbol) -> zzba (replays into Rime and hits 走吧)
        for (c in "zzb") coord.onCharKey(c)
        assertTrue(coord.snapshotUi().candidates.any { it.commitText == "℃" })
        val zzbaOut = coord.onCharKey('a') as CoordinatorKeyOutcome.Commit
        assertEquals("走吧", (zzbaOut.action as CommitAction.PlainText).text)

        // xkjd6 does not steal oqm
        coord.updateProfile(SchemaProfile.defaultXiaoheFlypy().copy(schemaId = "xkjd6", archetype = SchemaArchetype.PUSH_TOPUP_CODE))
        for (c in "oqm") coord.onCharKey(c)
        assertEquals("全码词", coord.snapshotUi().candidates[0].commitText)
    }
}
