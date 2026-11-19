package com.typefree.core.ime

import com.typefree.core.ime.coordinator.CommitAction
import com.typefree.core.ime.coordinator.CoordinatorKeyOutcome
import com.typefree.core.ime.coordinator.CoordinatorModeBadge
import com.typefree.core.ime.coordinator.RimeCoreSessionPort
import com.typefree.core.ime.coordinator.RimeDecodeFrame
import com.typefree.core.ime.coordinator.SlashSnippetEntry
import com.typefree.core.ime.coordinator.TypeFreeKeyCoordinator
import com.typefree.core.ime.enhancer.BriefPrefixIndex
import com.typefree.core.ime.enhancer.CodetableCodeDeriver
import com.typefree.core.ime.enhancer.DirectCommandEngine
import com.typefree.core.ime.enhancer.EnglishWordTrie
import com.typefree.core.ime.enhancer.PassiveMathSuggestion
import com.typefree.core.ime.enhancer.PhraseSuggestion
import com.typefree.core.ime.enhancer.PhraseSuggestionTracker
import com.typefree.core.ime.enhancer.ReverseSplitEngine
import com.typefree.core.ime.enhancer.SafeMathEvaluator
import com.typefree.core.ime.enhancer.ShadowDictManager
import com.typefree.core.ime.profile.SchemaInspector
import com.typefree.core.ime.profile.SchemaProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class CodetableV362EnhancementsTest {

    private class ScriptedRimePort(
        private val frameProvider: (String) -> RimeDecodeFrame
    ) : RimeCoreSessionPort {
        private val buf = StringBuilder()
        private val options = HashMap<String, Boolean>()
        val charHistory = ArrayList<Char>()

        override fun sendChar(ch: Char): RimeDecodeFrame {
            charHistory.add(ch)
            buf.append(ch)
            val frame = frameProvider(buf.toString())
            if (frame.autoCommittedText != null) {
                buf.clear()
                buf.append(frame.rawInput)
            }
            return frame
        }

        override fun sendBackspace(): RimeDecodeFrame {
            if (buf.isNotEmpty()) buf.deleteCharAt(buf.length - 1)
            return currentFrame()
        }

        override fun selectCandidate(index: Int): Pair<String?, RimeDecodeFrame> {
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
            val f = frameProvider(raw)
            return f.copy(candidates = f.candidates.take(maxCandidates))
        }

        override fun setOption(option: String, value: Boolean) {
            options[option] = value
        }

        override fun getOption(option: String): Boolean = options[option] ?: false
    }

    @Test
    fun `1_schema_inspector_direct_commands_only_flypy_and_ouj_preserves_rime_candidates`() {
        val flypyProfile = SchemaInspector.inspect(
            """
            schema:
              schema_id: flypy
              name: 小鹤音形
            engine:
              translators:
                - table_translator
            speller:
              alphabet: "abcdefghijklmnopqrstuvwxyz;'"
            translator:
              dictionary: flypy
            """.trimIndent()
        )
        assertTrue(flypyProfile.directCommandsEnabled)

        val wubiProfile = SchemaInspector.inspect(
            """
            schema:
              schema_id: wubi86
              name: 五笔86
            engine:
              translators:
                - table_translator
            speller:
              alphabet: "abcdefghijklmnopqrstuvwxy"
            translator:
              dictionary: wubi86
            """.trimIndent()
        )
        assertFalse(wubiProfile.directCommandsEnabled)

        val xkjdProfile = SchemaInspector.inspect(
            """
            schema:
              schema_id: xkjd6
              name: 星空键道6
            engine:
              translators:
                - table_translator
            speller:
              alphabet: "abcdefghijklmnopqrstuvwxyz"
              auto_select_pattern: "^[a-z][a-z][aiouv]+$"
            translator:
              dictionary: xkjd6
            """.trimIndent()
        )
        assertFalse(xkjdProfile.directCommandsEnabled)
        assertFalse(SchemaProfile.defaultSentencePinyin().directCommandsEnabled)

        // `ouj` keeps Rime candidate `偶见` after time candidates & allows 4th char `oujm`
        val port = ScriptedRimePort { raw ->
            when (raw) {
                "o" -> RimeDecodeFrame(raw, raw, listOf("哦" to ""))
                "ou" -> RimeDecodeFrame(raw, raw, listOf("偶" to ""))
                "ouj" -> RimeDecodeFrame(raw, raw, listOf("偶见" to ""))
                "oujm" -> RimeDecodeFrame(raw, raw, listOf("偶见面" to "", "偶见吗" to ""))
                else -> RimeDecodeFrame(raw, raw, emptyList())
            }
        }
        val fixedMillis = Instant.parse("2026-11-19T04:05:06Z").toEpochMilli()
        val coord = TypeFreeKeyCoordinator(
            profile = flypyProfile,
            rimePort = port,
            directCommandEngine = DirectCommandEngine(zoneId = ZoneId.of("Asia/Shanghai"))
        )

        coord.onCharKey('o', fixedMillis)
        coord.onCharKey('u', fixedMillis)
        val oujOut = coord.onCharKey('j', fixedMillis) as CoordinatorKeyOutcome.Consumed
        val texts = oujOut.ui.candidates.map { it.commitText }
        assertEquals(4, texts.size)
        assertEquals("12:05", texts[0])
        assertEquals("12时05分06秒", texts[1])
        assertEquals((fixedMillis / 1000L).toString(), texts[2])
        assertEquals("偶见", texts[3])

        // Selecting index 3 (`偶见`) commits `偶见` cleanly
        val pickOujian = coord.selectCandidate(3, fixedMillis) as CoordinatorKeyOutcome.Commit
        assertEquals("偶见", (pickOujian.action as CommitAction.PlainText).text)
        assertFalse(coord.isComposing)

        // Typing `oujm` continues into Rime 4th-code candidates
        coord.onCharKey('o', fixedMillis)
        coord.onCharKey('u', fixedMillis)
        coord.onCharKey('j', fixedMillis)
        val oujmOut = coord.onCharKey('m', fixedMillis) as CoordinatorKeyOutcome.Consumed
        assertEquals(listOf("偶见面", "偶见吗"), oujmOut.ui.candidates.map { it.commitText })

        // Backspace from `oujm` -> `ouj` restores time + `偶见`, then Backspace -> `ou` restores `偶`
        val backToOuj = coord.onBackspace(fixedMillis) as CoordinatorKeyOutcome.Consumed
        assertEquals("ouj", backToOuj.ui.rawCode)
        assertEquals("偶见", backToOuj.ui.candidates.last().commitText)
        val backToOu = coord.onBackspace(fixedMillis) as CoordinatorKeyOutcome.Consumed
        assertEquals("ou", backToOu.ui.rawCode)
        assertEquals(listOf("偶"), backToOu.ui.candidates.map { it.commitText })
    }

    @Test
    fun `2_passive_math_detection_extracts_valid_expressions_and_rejects_bare_numbers`() {
        assertEquals(
            PassiveMathSuggestion("125*8", "1000", endsWithEquals = true),
            SafeMathEvaluator.detectTrailingExpression("125*8=")
        )
        assertEquals(
            PassiveMathSuggestion("(35+15)*2", "100", endsWithEquals = true),
            SafeMathEvaluator.detectTrailingExpression("(35+15)*2=")
        )
        assertEquals(
            PassiveMathSuggestion("12+8", "20", endsWithEquals = true),
            SafeMathEvaluator.detectTrailingExpression("价格=12+8=")
        )
        assertEquals(
            PassiveMathSuggestion("(35+15)*2", "100", endsWithEquals = false),
            SafeMathEvaluator.detectTrailingExpression("价格(35+15)*2")
        )
        assertEquals(
            PassiveMathSuggestion("12÷4+2^3", "11", endsWithEquals = false),
            SafeMathEvaluator.detectTrailingExpression("总计: 12÷4+2^3")
        )
        assertEquals(
            PassiveMathSuggestion("10%3", "1", endsWithEquals = true),
            SafeMathEvaluator.detectTrailingExpression("10%3=")
        )

        assertNull(SafeMathEvaluator.detectTrailingExpression(""))
        assertNull(SafeMathEvaluator.detectTrailingExpression("=50"))
        assertNull(SafeMathEvaluator.detectTrailingExpression("a=50"))
        assertNull(SafeMathEvaluator.detectTrailingExpression("100="))
        assertNull(SafeMathEvaluator.detectTrailingExpression("125*8=1000"))
        assertNull(SafeMathEvaluator.detectTrailingExpression("-50="))
        assertNull(SafeMathEvaluator.detectTrailingExpression("(-50)"))
        assertNull(SafeMathEvaluator.detectTrailingExpression("12+"))
        assertNull(SafeMathEvaluator.detectTrailingExpression("12+8 "))
        assertNull(SafeMathEvaluator.detectTrailingExpression("10/0="))
    }

    @Test
    fun `3_single_char_mode_4th_key_autocommit_interception_and_backspace_replay`() {
        // Simulates real librime speller: 3 chars `abc` has single-char candidates;
        // 4th char `d` triggers multi-char autoCommit ("可能") and leaves leftover rawInput="d"
        val port = ScriptedRimePort { raw ->
            when (raw) {
                "a" -> RimeDecodeFrame("a", "a", listOf("啊" to ""))
                "ab" -> RimeDecodeFrame("ab", "ab", listOf("阿" to "", "阿伯" to ""))
                "abc" -> RimeDecodeFrame("abc", "abc", listOf("安" to "", "安康" to ""))
                "abcd" -> RimeDecodeFrame(
                    rawInput = "d",
                    preedit = "d",
                    candidates = listOf("的" to ""),
                    autoCommittedText = "可能"
                )
                else -> RimeDecodeFrame(raw, raw, emptyList())
            }
        }
        val coord = TypeFreeKeyCoordinator(
            profile = SchemaProfile.defaultXiaoheFlypy(),
            rimePort = port
        )
        coord.setSingleCharModeEnabled(true)

        coord.onCharKey('a')
        coord.onCharKey('b')
        coord.onCharKey('c')
        assertEquals(listOf("安"), coord.snapshotUi().candidates.map { it.commitText })

        // 4th key `d`: multi-char `可能` is intercepted; rawCode preserved as `abcd`, no leftover `d` candidates!
        val fourth = coord.onCharKey('d') as CoordinatorKeyOutcome.Consumed
        assertEquals("abcd", fourth.ui.rawCode)
        assertTrue(fourth.ui.candidates.isEmpty())

        // Backspace MUST replay `abc` into Rime and restore single-char candidate `安` (without `安康`)
        val back = coord.onBackspace() as CoordinatorKeyOutcome.Consumed
        assertEquals("abc", back.ui.rawCode)
        assertEquals(listOf("安"), back.ui.candidates.map { it.commitText })
    }

    @Test
    fun `4_orq_date_offset_stepping_bounds_and_reset_on_exit`() {
        val fixedDate = LocalDate.of(2026, 11, 19)
        val port = ScriptedRimePort { raw -> RimeDecodeFrame(raw, raw, listOf("字$raw" to "")) }
        val coord = TypeFreeKeyCoordinator(
            profile = SchemaProfile.defaultXiaoheFlypy(),
            rimePort = port,
            directCommandEngine = DirectCommandEngine(dateProvider = { fixedDate })
        )

        for (c in "orq") coord.onCharKey(c)
        val initSnap = coord.snapshotUi()
        assertTrue(initSnap.isOrqDateActive)
        assertEquals(0, initSnap.orqDateOffsetDays)
        assertEquals(6, initSnap.candidates.size)
        assertEquals("明天（11月20日）", initSnap.candidates[4].displayText)
        assertEquals("昨天（11月18日）", initSnap.candidates[5].displayText)

        // Step -1 day
        val prevSnap = coord.adjustOrqDateOffset(-1)
        assertTrue(prevSnap.isOrqDateActive)
        assertEquals(-1, prevSnap.orqDateOffsetDays)
        assertEquals(4, prevSnap.candidates.size)
        assertEquals("2026年11月18日", prevSnap.candidates[0].commitText)
        assertEquals("2026-11-18", prevSnap.candidates[1].commitText)
        assertEquals("星期三", prevSnap.candidates[2].commitText)

        // Bounds clamping -3650..3650
        val maxSnap = coord.adjustOrqDateOffset(10_000)
        assertEquals(3650, maxSnap.orqDateOffsetDays)
        val minSnap = coord.adjustOrqDateOffset(-20_000)
        assertEquals(-3650, minSnap.orqDateOffsetDays)

        // Typing 4th key leaves `orq` and resets orqDateOffsetDays to 0
        val fourth = coord.onCharKey('a')
        assertTrue(fourth is CoordinatorKeyOutcome.Commit)
        assertEquals(0, coord.orqDateOffsetDays)
        assertFalse(coord.snapshotUi().isOrqDateActive)

        // Backspace from `orq` resets orqDateOffsetDays to 0
        coord.reset()
        for (c in "orq") coord.onCharKey(c)
        coord.adjustOrqDateOffset(5)
        assertEquals(5, coord.orqDateOffsetDays)
        val backFromOrq = coord.onBackspace() as CoordinatorKeyOutcome.Consumed
        assertEquals("or", backFromOrq.ui.rawCode)
        assertFalse(backFromOrq.ui.isOrqDateActive)
        assertEquals(0, coord.orqDateOffsetDays)

        // Legacy `=` does NOT trigger math direct mode; `=` when idle commits directly
        coord.reset()
        val eqOut = coord.onCharKey('=') as CoordinatorKeyOutcome.Commit
        assertEquals("=", (eqOut.action as CommitAction.PlainText).text)
        assertFalse(coord.isComposing)
    }

    @Test
    fun `5_slash_snippet_mode_filtering_double_slash_space_enter_and_backspace`() {
        val port = ScriptedRimePort { raw -> RimeDecodeFrame(raw, raw, listOf("字$raw" to "")) }
        val coord = TypeFreeKeyCoordinator(
            profile = SchemaProfile.defaultXiaoheFlypy(),
            rimePort = port
        )
        coord.updateSlashSnippets(
            listOf(
                SlashSnippetEntry("/sdx", "收单地址前缀", "prefix-sdx"),
                SlashSnippetEntry(";sd", "收单地址", "北京市朝阳区1号"),
                SlashSnippetEntry("yx", "工作邮箱", "dev@example.com")
            )
        )

        // 1) Idle `/` enters SLASH_SNIPPET mode and immediately shows all snippets
        val enterSlash = coord.onCharKey('/') as CoordinatorKeyOutcome.Consumed
        assertEquals(CoordinatorModeBadge.SLASH_SNIPPET, enterSlash.ui.badge)
        assertEquals("/", enterSlash.ui.rawCode)
        assertEquals(3, enterSlash.ui.candidates.size)

        // 2) Typing `s` filters to s* (`sdx`, `sd`)
        val afterS = coord.onCharKey('s') as CoordinatorKeyOutcome.Consumed
        assertEquals("/s", afterS.ui.rawCode)
        assertEquals(listOf("prefix-sdx", "北京市朝阳区1号"), afterS.ui.candidates.map { it.commitText })

        // 3) Typing `d` matches exact `sd` pinned at top ahead of prefix `sdx`
        val afterD = coord.onCharKey('d') as CoordinatorKeyOutcome.Consumed
        assertEquals("/sd", afterD.ui.rawCode)
        assertEquals(listOf("北京市朝阳区1号", "prefix-sdx"), afterD.ui.candidates.map { it.commitText })

        // 4) Backspace returns to `/s`, then `/`, then exits slash mode
        assertEquals("/s", (coord.onBackspace() as CoordinatorKeyOutcome.Consumed).ui.rawCode)
        assertEquals("/", (coord.onBackspace() as CoordinatorKeyOutcome.Consumed).ui.rawCode)
        val exited = coord.onBackspace() as CoordinatorKeyOutcome.Consumed
        assertFalse(exited.ui.isComposing)

        // 5) `//` commits `/` and exits
        coord.onCharKey('/')
        val doubleSlash = coord.onCharKey('/') as CoordinatorKeyOutcome.Commit
        assertEquals("/", (doubleSlash.action as CommitAction.PlainText).text)
        assertFalse(coord.isComposing)

        // 6) `/` + Space commits `/` and exits
        coord.onCharKey('/')
        val slashSpace = coord.onSpace() as CoordinatorKeyOutcome.Commit
        assertEquals("/", (slashSpace.action as CommitAction.PlainText).text)
        assertFalse(coord.isComposing)

        // 7) `/` + Enter commits `/` and exits
        coord.onCharKey('/')
        val slashEnter = coord.onEnter() as CoordinatorKeyOutcome.Commit
        assertEquals("/", (slashEnter.action as CommitAction.PlainText).text)
        assertFalse(coord.isComposing)

        // 8) Selecting snippet candidate commits content and exits
        coord.onCharKey('/')
        coord.onCharKey('y')
        coord.onCharKey('x')
        val pickEmail = coord.selectCandidate(0) as CoordinatorKeyOutcome.Commit
        assertEquals("dev@example.com", (pickEmail.action as CommitAction.PlainText).text)
        assertFalse(coord.isComposing)
    }

    @Test
    fun `6_brief_prefix_index_v3_multi_continuations_and_coordinator_toggle`() {
        val dictLines = sequenceOf(
            "---",
            "name: flypy",
            "...",
            "一词\taaaa",
            "二词\taaab",
            "三词\taaac",
            "四词\taaad",
            "五词\taaae",
            "六词\taaaf",
            "七词\taaag",
            "八词\taaah",
            "九词\taaai",
            "单\tabc",
            "双字\tabcd"
        )
        val index = BriefPrefixIndex.buildFromDictLines(mainDictLines = dictLines)
        val nats = index.lookupNaturalContinuations("aaa")
        assertEquals(8, nats.size)
        assertEquals(
            listOf("一词", "二词", "三词", "四词", "五词", "六词", "七词", "八词"),
            nats.map { it.word }
        )
        assertEquals(listOf("一词", "二词"), index.lookupNaturalContinuations("aaa", limit = 2).map { it.word })

        val tempDir = Files.createTempDirectory("brief_v3_multi_").toFile()
        try {
            val snap = File(tempDir, "test.brief.idx")
            index.writeSnapshot(snap)
            val reloaded = BriefPrefixIndex.readSnapshot(snap)
            assertEquals(nats, reloaded.lookupNaturalContinuations("aaa"))

            val shadowMgr = ShadowDictManager(tempDir, "flypy")
            shadowMgr.blockWord("二词")

            val port = ScriptedRimePort { raw ->
                when (raw) {
                    "aaa" -> RimeDecodeFrame("aaa", "aaa", listOf("八词" to "~h", "九词" to "~i"))
                    else -> RimeDecodeFrame(raw, raw, emptyList())
                }
            }
            val coord = TypeFreeKeyCoordinator(
                profile = SchemaProfile.defaultXiaoheFlypy(),
                rimePort = port,
                reverseSplitEngine = ReverseSplitEngine(reloaded),
                shadowDictManager = shadowMgr
            )

            // Enabled: hoists remaining 7 continuations (二词 blocked), deduplicates 八词, keeps 九词 from Rime
            for (c in "aaa") coord.onCharKey(c)
            assertEquals(
                listOf("一词", "三词", "四词", "五词", "六词", "七词", "八词", "九词"),
                coord.snapshotUi().candidates.map { it.commitText }
            )

            // Single-char mode filters multi-char continuations
            coord.reset()
            coord.setSingleCharModeEnabled(true)
            for (c in "ab") coord.onCharKey(c)
            assertEquals(listOf("单"), coord.snapshotUi().candidates.map { it.commitText })

            // Disabling naturalContinuationEnabled falls back to Rime candidates only
            coord.reset()
            coord.setSingleCharModeEnabled(false)
            coord.setNaturalContinuationEnabled(false)
            for (c in "aaa") coord.onCharKey(c)
            assertEquals(listOf("八词", "九词"), coord.snapshotUi().candidates.map { it.commitText })
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `7_l1_codetable_code_deriver_and_l2_phrase_suggestion_tracker`() {
        val extBFish = "\uD867\uDF7E" // 𩽾 -> anan
        val reverseMap = mapOf(
            "林" to listOf("lb", "lbmm"),
            "致" to listOf("vi", "viwz"),
            "远" to listOf("yr", "yrze"),
            "北" to listOf("bk", "bkbx"),
            "京" to listOf("jk", "jkdx"),
            "大" to listOf("d", "dada"),
            "学" to listOf("xp", "xpzi"),
            "中" to listOf("v", "vsvl"),
            "华" to listOf("hx", "hxrh"),
            "人" to listOf("r", "rfpf"),
            "民" to listOf("mb", "mbui"),
            "国" to listOf("go", "gowd"),
            extBFish to listOf("anan")
        )

        // L1: 2-char AaAbBaBb, 3-char AaBaCaCb, 4-char AaBaCaZa, 5-char AaBaCaZa, CJK Ext-B, invalid
        assertEquals("bkjk", CodetableCodeDeriver.deriveXiaohe4Code("北京", reverseMap))
        assertEquals("lvyr", CodetableCodeDeriver.deriveXiaohe4Code("林致远", reverseMap))
        assertEquals("bjdx", CodetableCodeDeriver.deriveXiaohe4Code("北京大学", reverseMap))
        assertEquals("vhrg", CodetableCodeDeriver.deriveXiaohe4Code("中华人民国", reverseMap))
        assertEquals("${extBFish}林".let { "anlb" }, CodetableCodeDeriver.deriveXiaohe4Code("${extBFish}林", reverseMap))
        assertNull(CodetableCodeDeriver.deriveXiaohe4Code("林", reverseMap))
        assertNull(CodetableCodeDeriver.deriveXiaohe4Code("林A", reverseMap))
        assertNull(CodetableCodeDeriver.deriveXiaohe4Code("林无", reverseMap))

        // L2: PhraseSuggestionTracker 4 gates & bounded LRU / dismissed sets
        val tracker = PhraseSuggestionTracker(maxTrackedEntries = 4, maxDismissedEntries = 3, minRepeatCount = 3)
        val existingDict = setOf("北京", "大学", "致远")
        val uniqueSingleCodes = setOf("aaaa")

        // Gate 4: unique single-char code `aaaa` is never suggested
        repeat(3) {
            tracker.resetWindow()
            assertNull(tracker.recordCommittedUnit("阿", "aa", { it in existingDict }, { it in uniqueSingleCodes }))
            assertNull(tracker.recordCommittedUnit("啊", "aa", { it in existingDict }, { it in uniqueSingleCodes }))
        }

        // Gate 1..3: `林致远` repeated 3 times triggers PhraseSuggestion("林致远", "lvyr") on 3rd pass
        repeat(2) {
            tracker.resetWindow()
            assertNull(tracker.recordCommittedUnit("林", "lb", { it in existingDict }, { it in uniqueSingleCodes }))
            assertNull(tracker.recordCommittedUnit("致", "vi", { it in existingDict }, { it in uniqueSingleCodes }))
            assertNull(tracker.recordCommittedUnit("远", "yr", { it in existingDict }, { it in uniqueSingleCodes }))
        }
        tracker.resetWindow()
        assertNull(tracker.recordCommittedUnit("林", "lb", { it in existingDict }, { it in uniqueSingleCodes }))
        // Note: on 3rd `致`, `林致` also reaches 3; verify `林致` or `林致远` works cleanly
        val midSuggestion = tracker.recordCommittedUnit("致", "vi", { it in existingDict }, { it in uniqueSingleCodes })
        assertEquals(PhraseSuggestion("林致", "lbvi"), midSuggestion)
        val fullSuggestion = tracker.recordCommittedUnit("远", "yr", { it in existingDict }, { it in uniqueSingleCodes })
        assertEquals(PhraseSuggestion("林致远", "lvyr"), fullSuggestion)

        // Dismissing prevents re-suggestion
        tracker.markDismissed("林致远")
        tracker.markAccepted("林致")
        tracker.resetWindow()
        tracker.recordCommittedUnit("林", "lb", { it in existingDict }, { it in uniqueSingleCodes })
        assertNull(tracker.recordCommittedUnit("致", "vi", { it in existingDict }, { it in uniqueSingleCodes }))
        assertNull(tracker.recordCommittedUnit("远", "yr", { it in existingDict }, { it in uniqueSingleCodes }))

        // LRU cap bounded at maxTrackedEntries = 4
        val pairs = listOf("甲乙", "丙丁", "戊己", "庚辛", "壬癸", "子丑")
        for (p in pairs) {
            tracker.resetWindow()
            tracker.recordCommittedUnit(p.substring(0, 1), "aa", { false }, { false })
            tracker.recordCommittedUnit(p.substring(1, 2), "bb", { false }, { false })
        }
        assertTrue("trackedCount must stay <= 4, was ${tracker.trackedCount()}", tracker.trackedCount() <= 4)
    }

    @Test
    fun `8_english_word_trie_prioritizes_high_frequency_short_words`() {
        val trie = EnglishWordTrie.defaultBuiltIn()
        val thSuggestions = trie.suggestWords("th", limit = 6)
        assertTrue("Expected 'the' in top suggestions for 'th', got $thSuggestions", "the" in thSuggestions)
        assertEquals("the", thSuggestions.first())

        val apSuggestions = trie.suggestWords("ap", limit = 6)
        assertTrue("Expected 'app' and 'api' in top suggestions for 'ap', got $apSuggestions", "app" in apSuggestions && "api" in apSuggestions)
    }
}
