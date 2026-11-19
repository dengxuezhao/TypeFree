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

        // Snapshot v3 roundtrip + rejection of legacy v1/v2
        val tempDir = Files.createTempDirectory("brief_v3_").toFile()
        try {
            val snapV3 = File(tempDir, "flypy.brief.idx")
            idx.writeSnapshot(snapV3)
            val reloaded = BriefPrefixIndex.readSnapshot(snapV3)
            assertEquals(NaturalContinuation("丁", "uu", "okhguu"), reloaded.lookupNaturalFirstContinuation("okhg"))
            assertEquals(
                listOf("丁", "百", "下", "𠀢"),
                reloaded.lookupNaturalContinuations("okhg").map { it.word }
            )
            assertEquals(NaturalContinuation("清风徐来", "l", "qfxl"), reloaded.lookupNaturalFirstContinuation("qfx"))

            for (legacyVer in listOf(1, 2)) {
                val snapLegacy = File(tempDir, "legacy_v$legacyVer.brief.idx")
                DataOutputStream(BufferedOutputStream(FileOutputStream(snapLegacy))).use { out ->
                    out.writeInt(0x54464249) // SNAPSHOT_MAGIC
                    out.writeInt(legacyVer)
                    repeat(26) { out.writeShort(0) }
                    repeat(676) { out.writeShort(0) }
                    out.writeInt(0) // bitLen
                    out.writeInt(0) // gt4Count
                    out.writeShort(0) // guardCount
                }
                var rejected = false
                try {
                    BriefPrefixIndex.readSnapshot(snapLegacy)
                } catch (_: IllegalArgumentException) {
                    rejected = true
                }
                assertTrue("Snapshot v$legacyVer must be rejected so index rebuilds as v3", rejected)
            }
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

        // a) okhg hoists [丁, 百, 下, 𠀢] in original dictionary order without duplicating
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
    fun `2_english_word_trie_expanded_and_chinese_mode_has_no_english_veto`() {
        val trie = EnglishWordTrie.defaultBuiltIn()
        val requiredWords = listOf(
            "github", "kotlin", "python", "android", "google", "gradle", "studio", "docker",
            "gemini", "claude", "typescript", "javascript", "flutter", "chrome", "apple",
            "iphone", "macbook", "keyboard", "shadow", "phrase", "config", "commit",
            "branch", "merge", "deploy", "search", "import", "export",
            "the", "and", "for", "you", "are", "not", "can", "app", "api", "git",
            "url", "sql", "code", "work", "time", "good", "make", "test", "user",
            "data", "file", "line", "list", "name", "plan", "send", "show", "take",
            "text", "view", "week", "year"
        )
        for (w in requiredWords) {
            assertTrue("Missing required English word: $w", trie.containsWord(w))
        }
        assertTrue(trie.hasLongerWordPrefix("gith"))
        assertTrue(trie.hasLongerWordPrefix("githu"))
        assertTrue(trie.suggestWords("gith", 6).contains("github"))
        assertTrue(trie.suggestWords("ap", 8).contains("app"))
        assertTrue(trie.suggestWords("co", 8).contains("code"))

        // Verify >= 2,500 embedded words via suggestWords across a..z (length >= 2)
        val allEmbedded = ('a'..'z').flatMap { c -> trie.suggestWords(c.toString(), 500) }.distinct()
        assertTrue("Expected >= 2500 built-in English words, got ${allEmbedded.size}", allEmbedded.size >= 2500)
        assertTrue(allEmbedded.all { it.length >= 2 && it.all { ch -> ch in 'a'..'z' } })

        // SchemaProfile & TypeFreeTomlParser roundtrip (legacy english_prefix_veto_enabled ignored cleanly)
        val defaultProfile = SchemaProfile.defaultXiaoheFlypy()
        val customized = defaultProfile.copy(directCommandsEnabled = false, autoEncodePhrasesEnabled = true)
        val toml = TypeFreeTomlParser.serialize(customized) + "\nenglish_prefix_veto_enabled = true\n"
        val parsed = TypeFreeTomlParser.parse(toml)
        assertFalse(parsed.directCommandsEnabled)
        assertTrue(parsed.autoEncodePhrasesEnabled)

        // Chinese mode no longer hijacks 4-char code with English veto; `gith` falls through to reverse split
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
        val coord = TypeFreeKeyCoordinator(
            profile = SchemaProfile.defaultXiaoheFlypy(),
            rimePort = StubRimePort(),
            reverseSplitEngine = ReverseSplitEngine(idx)
        )
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
    fun `4_codetable_auto_encoder_standalone_and_coordinator_does_not_auto_write_user_txt`() {
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

            // Coordinator never silently creates or writes `_user.txt`
            val port = StubRimePort(
                mapOf(
                    "tm" to listOf("天" to ""),
                    "xx" to listOf("下" to "")
                )
            )
            val coord = TypeFreeKeyCoordinator(
                profile = SchemaProfile.defaultXiaoheFlypy().copy(autoCommitUniqueFull = false),
                rimePort = port
            )
            coord.onCharKey('t'); coord.onCharKey('m'); coord.onSpace()
            coord.onCharKey('x'); coord.onCharKey('x'); coord.onSpace()
            assertFalse(File(tempDir, "coord_user.txt").exists())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `5_direct_commands_orq_offset_odz_and_strict_flypy_only_restriction`() {
        val fixedDate = LocalDate.of(2026, 11, 18)
        var currentSchema = "flypy"
        var singleChar = false
        val engine = DirectCommandEngine(
            activeSchemaIdProvider = { currentSchema },
            singleCharModeStateProvider = { singleChar },
            dateProvider = { fixedDate }
        )

        // orq with default offset 0 includes tomorrow & yesterday shortcuts
        val today = engine.evaluate("orq")
        assertEquals("2026年11月18日", today[0].commitText)
        assertEquals("2026-11-18", today[1].commitText)
        assertEquals("星期三", today[2].commitText)
        assertEquals("农历", today[3].codeHint)
        assertEquals("明天（11月19日）", today[4].displayText)
        assertEquals("2026年11月19日", today[4].commitText)
        assertEquals("昨天（11月17日）", today[5].displayText)
        assertEquals("2026年11月17日", today[5].commitText)

        // orq with explicit offset omits tomorrow/yesterday shortcuts
        val tomorrow = engine.evaluate("orq", dateOffsetDays = 1)
        assertEquals(4, tomorrow.size)
        assertEquals("2026年11月19日", tomorrow[0].commitText)
        assertEquals("2026-11-19", tomorrow[1].commitText)
        assertEquals("星期四", tomorrow[2].commitText)

        // Legacy zz / zza..zzf / date / time / oxq / orq+1 / =1+1 are completely removed
        for (removed in listOf("zz", "zza", "zzb", "date", "time", "oxq", "orq+1", "date+1", "=1+1")) {
            assertTrue("Removed command '$removed' must return empty", engine.evaluate(removed).isEmpty())
            assertFalse("Removed command '$removed' must not match prefix", engine.isCommandPrefixOrMatch(removed))
        }

        // Strict flypy-only restriction across all commands
        for (nonFlypy in listOf("xkjd6", "wubi86", "rime_ice")) {
            currentSchema = nonFlypy
            for (cmd in listOf("orq", "ouj", "ofi", "oqm", "ovy", "ojf", "oai", "odz")) {
                assertTrue("$nonFlypy must return empty for $cmd", engine.evaluate(cmd).isEmpty())
                assertFalse("$nonFlypy must not match $cmd", engine.isCommandPrefixOrMatch(cmd))
            }
        }
        currentSchema = "flypy"

        // odz toggle candidate text
        val odzOn = engine.evaluate("odz")
        assertEquals("开启单字模式", odzOn[0].displayText)
        assertEquals(CommitAction.Command(DirectCommandType.TOGGLE_SINGLE_CHAR_MODE), odzOn[0].action)
        singleChar = true
        assertEquals("关闭单字模式", engine.evaluate("odz")[0].displayText)

        // Coordinator end-to-end: orq + adjustOrqDateOffset + odz + xkjd6 isolation
        val port = StubRimePort(
            mapOf(
                "oqm" to listOf("全码词" to ""),
                "orq" to listOf("哦日期" to "")
            )
        )
        val coord = TypeFreeKeyCoordinator(
            profile = SchemaProfile.defaultXiaoheFlypy(),
            rimePort = port,
            directCommandEngine = DirectCommandEngine(dateProvider = { fixedDate })
        )

        for (c in "orq") coord.onCharKey(c)
        assertTrue(coord.snapshotUi().isOrqDateActive)
        assertEquals(0, coord.snapshotUi().orqDateOffsetDays)
        val plus1Snap = coord.adjustOrqDateOffset(1)
        assertEquals(1, plus1Snap.orqDateOffsetDays)
        assertEquals("2026年11月19日", plus1Snap.candidates[0].commitText)
        val dateCommit = coord.onSpace() as CoordinatorKeyOutcome.Commit
        assertEquals("2026年11月19日", (dateCommit.action as CommitAction.PlainText).text)
        assertEquals(0, coord.orqDateOffsetDays)

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

        // xkjd6 does not steal oqm or orq even if directCommandsEnabled=true
        coord.updateProfile(SchemaProfile.defaultXiaoheFlypy().copy(schemaId = "xkjd6", archetype = SchemaArchetype.PUSH_TOPUP_CODE))
        for (c in "oqm") coord.onCharKey(c)
        assertEquals("全码词", coord.snapshotUi().candidates[0].commitText)
        coord.reset()
        for (c in "orq") coord.onCharKey(c)
        assertEquals("哦日期", coord.snapshotUi().candidates[0].commitText)
    }
}
