package com.typefree.ime.ime

import com.typefree.ime.ime.flypy.FlypyAction
import com.typefree.ime.ime.flypy.FlypyDictionary
import com.typefree.ime.ime.flypy.FlypyEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.io.FileInputStream

class FlypyEngineTest {

    companion object {
        private lateinit var sharedDict: FlypyDictionary

        @JvmStatic
        @BeforeClass
        fun loadRealAssetsOnce() {
            val dictFile = listOf(
                File("src/main/assets/flypy_dict.tsv"),
                File("app/src/main/assets/flypy_dict.tsv")
            ).firstOrNull { it.exists() }
            val dzFile = listOf(
                File("src/main/assets/flypy_dz.tsv"),
                File("app/src/main/assets/flypy_dz.tsv")
            ).firstOrNull { it.exists() }

            assertNotNull("Expected flypy_dict.tsv in assets", dictFile)
            assertNotNull("Expected flypy_dz.tsv in assets", dzFile)

            FileInputStream(dictFile!!).use { dictIn ->
                FileInputStream(dzFile!!).use { dzIn ->
                    sharedDict = FlypyDictionary.loadFromStreams(dictIn, dzIn)
                }
            }
            assertTrue(sharedDict.isLoaded)
            assertTrue("Expected >30k codes in expanded open dict", sharedDict.codeCount > 30_000)
            assertTrue("Expected >7k reverse-lookup chars", sharedDict.reverseCharCount > 7_000)
        }
    }

    @Test
    fun userCustomOneAndTwoCodeShortcuts_preserveExactMacRimePriority() {
        val engine = FlypyEngine(sharedDict)

        // 1-code custom secondary chars (`# 二重简码：一二简次选字`)
        assertEquals(FlypyAction.ConsumedComposing, engine.onCharInput('d'))
        assertEquals(listOf("的", "打"), engine.candidates.map { it.text }.take(2))
        assertEquals(FlypyAction.CommitText("的", false), engine.onSpace())

        // `,` secondary quick-select commits "打" directly!
        assertEquals(FlypyAction.ConsumedComposing, engine.onCharInput('d'))
        assertEquals(
            FlypyAction.CommitText("打", false),
            engine.onCommaOrPeriod(isComma = true, commaPeriodQuickSelect = true)
        )

        // 2-code custom secondary chars (`bc -> 1.报 2.宝`)
        engine.onCharInput('b')
        engine.onCharInput('c')
        assertEquals(listOf("报", "宝"), engine.candidates.map { it.text }.take(2))
        assertEquals(
            FlypyAction.CommitText("宝", false),
            engine.onCommaOrPeriod(isComma = true, commaPeriodQuickSelect = true)
        )

        // Custom `o`-prefix quick symbols (`oq -> ‰`, `oz -> +`)
        engine.onCharInput('o')
        engine.onCharInput('q')
        assertEquals("‰", engine.candidates.firstOrNull()?.text)
        assertEquals(FlypyAction.CommitText("‰", false), engine.onSpace())
    }

    @Test
    fun fourCodeUniqueAutoCommit_commitsImmediatelyOnFourthKey() {
        val engine = FlypyEngine(sharedDict)

        // `xhyx` -> `小鹤音形` (unique 4-code auto-commit!)
        assertEquals(FlypyAction.ConsumedComposing, engine.onCharInput('x'))
        assertEquals(FlypyAction.ConsumedComposing, engine.onCharInput('h'))
        assertEquals(FlypyAction.ConsumedComposing, engine.onCharInput('y'))
        val fourth = engine.onCharInput('x')
        assertEquals(FlypyAction.CommitText("小鹤音形", remainComposing = false), fourth)
        assertFalse(engine.isComposing)

        // `quts` -> `趋同` (unique 4-code auto-commit without flypy_full collision)
        engine.onCharInput('q')
        engine.onCharInput('u')
        engine.onCharInput('t')
        assertEquals(FlypyAction.CommitText("趋同", remainComposing = false), engine.onCharInput('s'))
    }

    @Test
    fun fourthCodeCollisionAndFifthCodePushCommit_pushesFirstAndComposesNext() {
        val engine = FlypyEngine(sharedDict)

        // `uijm` has collision: `1.时间 2.实践` -> stays composing on 4th key!
        engine.onCharInput('u')
        engine.onCharInput('i')
        engine.onCharInput('j')
        val fourth = engine.onCharInput('m')
        assertEquals(FlypyAction.ConsumedComposing, fourth)
        assertEquals("uijm", engine.composingCode)
        assertEquals(listOf("时间", "实践"), engine.candidates.map { it.text }.take(2))

        // 5th key `d` pushes `时间` and immediately composes `d` (`1.的 2.打`)!
        val fifth = engine.onCharInput('d')
        assertEquals(FlypyAction.CommitText("时间", remainComposing = true), fifth)
        assertEquals("d", engine.composingCode)
        assertEquals("的", engine.candidates.firstOrNull()?.text)
    }

    @Test
    fun commaPeriodQuickSelectToggle_supportsBothAndroidFlypyAndMacTopUpModes() {
        val engine = FlypyEngine(sharedDict)

        // Mode 1: Quick-select ON (`uijm` + `,` -> `实践`)
        "uijm".forEach { engine.onCharInput(it) }
        assertEquals(
            FlypyAction.CommitText("实践", false),
            engine.onCommaOrPeriod(isComma = true, commaPeriodQuickSelect = true)
        )

        // Mode 2: Quick-select OFF (`uijm` + `,` -> `时间，`, `uijm` + `.` -> `时间。`)
        "uijm".forEach { engine.onCharInput(it) }
        assertEquals(
            FlypyAction.CommitText("时间，", false),
            engine.onCommaOrPeriod(isComma = true, commaPeriodQuickSelect = false)
        )

        "uijm".forEach { engine.onCharInput(it) }
        assertEquals(
            FlypyAction.CommitText("时间。", false),
            engine.onCommaOrPeriod(isComma = false, commaPeriodQuickSelect = false)
        )
    }

    @Test
    fun semicolonQuickSymbolsAndRepeatHistory_autoCommitOnSecondChar() {
        val engine = FlypyEngine(sharedDict)

        // `;q` -> `：“`
        assertEquals(FlypyAction.ConsumedComposing, engine.onCharInput(';'))
        assertEquals(listOf("：", "；"), engine.candidates.map { it.text }.take(2))
        assertEquals(FlypyAction.CommitText("：“", false), engine.onCharInput('q'))

        // `;f` -> repeats last committed text (`：“`)!
        assertEquals(FlypyAction.ConsumedComposing, engine.onCharInput(';'))
        assertEquals(FlypyAction.CommitText("：“", false), engine.onCharInput('f'))

        // `;w` -> `？`
        engine.onCharInput(';')
        assertEquals(FlypyAction.CommitText("？", false), engine.onCharInput('w'))
    }

    @Test
    fun wildcardReverseLookup_supportsSoundToShapeAndShapeToSoundWithCodeHints() {
        val engine = FlypyEngine(sharedDict)

        // 1. 知音查形: `ji`` -> finds `ji..` chars including `瘠` with full 4-code hint `jibo`
        engine.onCharInput('j')
        engine.onCharInput('i')
        engine.onCharInput('`')
        assertTrue(engine.candidates.isNotEmpty())
        assertTrue(engine.candidates.all { it.codeHint.startsWith("ji") && it.codeHint.length == 4 })
        assertTrue(engine.candidates.any { it.text == "瘠" && it.codeHint == "jibo" })
        engine.clearComposing()

        // 2. 知形查音 (3-key Android Flypy style): `` `al `` -> finds `丁〔dkal〕`
        engine.onCharInput('`')
        engine.onCharInput('a')
        engine.onCharInput('l')
        assertTrue(engine.candidates.isNotEmpty())
        assertTrue(engine.candidates.any { it.text == "丁" && it.codeHint == "dkal" })
        engine.clearComposing()

        // 3. 知形查音 (4-slot wildcard): `` ``bo `` -> never auto-commits on 4th char so user can inspect hints!
        engine.onCharInput('`')
        engine.onCharInput('`')
        engine.onCharInput('b')
        val fourthWild = engine.onCharInput('o')
        assertEquals(FlypyAction.ConsumedComposing, fourthWild)
        assertTrue(engine.candidates.any { it.text == "瘠" && it.codeHint == "jibo" })
    }

    @Test
    fun dynamicDateTimeAndEmptyCodeMaxClear_workCleanly() {
        val engine = FlypyEngine(sharedDict)
        val fixedEpochMs = 1_762_340_400_000L // deterministic timestamp

        "orq".forEach { engine.onCharInput(it, fixedEpochMs) }
        assertTrue(engine.candidates.first().text.contains("年"))
        engine.clearComposing()

        "ouj".forEach { engine.onCharInput(it, fixedEpochMs) }
        assertTrue(engine.candidates.first().text.contains(":"))
        engine.clearComposing()

        // Empty 4-code (`zzzz` if empty or invalid stem) clears automatically on 5th key without committing garbage
        "zzzz".forEach { engine.onCharInput(it) }
        if (engine.candidates.isEmpty()) {
            val fifth = engine.onCharInput('d')
            assertEquals(FlypyAction.ConsumedComposing, fifth)
            assertEquals("d", engine.composingCode)
            assertEquals("的", engine.candidates.first().text)
        }
    }

    @Test
    fun customRimeTableHotMounting_loadsAndPrioritizesCustomWords() {
        val customTsv = """
            tfre	TypeFree	自由输入法
            wubi	五笔输入
            custom	自定义码表
        """.trimIndent()
        val customDz = """
            tfre	字
        """.trimIndent()

        val customDict = FlypyDictionary.loadFromStreams(
            customTsv.byteInputStream(Charsets.UTF_8),
            customDz.byteInputStream(Charsets.UTF_8)
        )

        val engine = FlypyEngine(customDict)
        assertEquals(3, customDict.codeCount)
        assertEquals(1, customDict.reverseCharCount)

        "tfre".forEach { engine.onCharInput(it) }
        val commit = engine.onSpace()
        assertEquals(FlypyAction.CommitText("TypeFree", false), commit)
    }

    @Test
    fun fourCodeCommonWordsContinuousTyping_worksFluently() {
        val engine = FlypyEngine(sharedDict)

        // 1. Unique 4-code `vsgo` auto-commits `中国` immediately on 4th key!
        engine.onCharInput('v')
        engine.onCharInput('s')
        engine.onCharInput('g')
        val fourthVsgo = engine.onCharInput('o')
        assertEquals(FlypyAction.CommitText("中国", false), fourthVsgo)
        assertFalse(engine.isComposing)

        // 2. Unique 4-code `nihc` auto-commits `你好` immediately on 4th key!
        engine.onCharInput('n')
        engine.onCharInput('i')
        engine.onCharInput('h')
        val fourthNihc = engine.onCharInput('c')
        assertEquals(FlypyAction.CommitText("你好", false), fourthNihc)
        assertFalse(engine.isComposing)

        // 3. Multi-candidate 4-code `ceui` stays composing and commits `测试` on space
        "ceui".forEach { engine.onCharInput(it) }
        assertTrue(engine.candidates.isNotEmpty())
        assertEquals("测试", engine.candidates.first().text)
        assertEquals(FlypyAction.CommitText("测试", false), engine.onSpace())
    }

    @Test
    fun topNumberRowDigits_flushFirstCandidateAndCommitDigitAtomically() {
        val engine = FlypyEngine(sharedDict)

        // 1. Open-source dict `di` (`1.地`) + top-row `'1'` -> atomically commits `"地1"` and clears composing
        "di".forEach { engine.onCharInput(it) }
        assertEquals("地", engine.candidates.first().text)
        assertEquals(FlypyAction.CommitText("地1", remainComposing = false), engine.onCharInput('1'))
        assertFalse(engine.isComposing)

        // 2. Custom priority dict `di` (`1.第 2.地`) + top-row `'1'` -> atomically commits `"第1"`
        val customDiDict = FlypyDictionary.loadFromStreams(
            "di\t第\t地".byteInputStream(Charsets.UTF_8),
            "".byteInputStream(Charsets.UTF_8)
        )
        val customEngine = FlypyEngine(customDiDict)
        "di".forEach { customEngine.onCharInput(it) }
        assertEquals("第", customEngine.candidates.first().text)
        assertEquals(FlypyAction.CommitText("第1", remainComposing = false), customEngine.onCharInput('1'))
        assertFalse(customEngine.isComposing)

        // 3. `uijm` (`1.时间 2.实践`) + `'2'` + `'0'` -> `"时间2"` then `"0"`
        "uijm".forEach { engine.onCharInput(it) }
        assertEquals("时间", engine.candidates.first().text)
        assertEquals(FlypyAction.CommitText("时间2", remainComposing = false), engine.onCharInput('2'))
        assertEquals(FlypyAction.CommitText("0", remainComposing = false), engine.onCharInput('0'))
        assertFalse(engine.isComposing)

        // 4. Empty 4-code `zzzz` + `'9'` -> discards empty code and commits `"9"` cleanly
        "zzzz".forEach { engine.onCharInput(it) }
        if (engine.candidates.isEmpty()) {
            assertEquals(FlypyAction.CommitText("9", remainComposing = false), engine.onCharInput('9'))
            assertFalse(engine.isComposing)
        }
    }
}
