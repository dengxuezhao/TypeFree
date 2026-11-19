package com.typefree.ime.v362

import com.typefree.core.ime.enhancer.EnglishWordTrie
import com.typefree.ime.ime.EditorShortcutAction
import com.typefree.ime.ime.KeyGestureActionType
import com.typefree.ime.ime.KeyGestureBinding
import com.typefree.ime.ime.KeyGestureConfig
import com.typefree.ime.ime.predict.EnglishWordSuggester
import com.typefree.ime.ime.predict.PassiveCalculatorHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class V362KeyboardFeaturesTest {

    @Test
    fun keyGestureConfig_defaultProfileCovers26SwipeUpQuickSymbolsAnd26LongPressSecondaryKeys() {
        val cnProfile = KeyGestureConfig.defaultProfile(isChinese = true, showNumberRow = false)
        assertEquals(26, cnProfile.swipeUpMap.size)
        assertEquals(26, cnProfile.longPressMap.size)

        // Verify Xiaohe quick_symbols.yaml 1-to-1 swipe-up mapping
        assertEquals("：“", cnProfile.resolveSwipeUp('q')?.text)
        assertEquals("？", cnProfile.resolveSwipeUp('w')?.text)
        assertEquals("（", cnProfile.resolveSwipeUp('e')?.text)
        assertEquals("）", cnProfile.resolveSwipeUp('r')?.text)
        assertEquals("\t", cnProfile.resolveSwipeUp('t')?.text)
        assertEquals("Tab", cnProfile.resolveSwipeUp('t')?.displayBadge())

        val ySwipe = cnProfile.resolveSwipeUp('y')!!
        assertEquals(KeyGestureActionType.COMMIT_PAIRED, ySwipe.type)
        assertEquals("《", ySwipe.text)
        assertEquals("》", ySwipe.rightText)

        assertEquals("『", cnProfile.resolveSwipeUp('u')?.text)
        assertEquals(KeyGestureActionType.UNDO_LAST, cnProfile.resolveSwipeUp('i')?.type)
        assertEquals(KeyGestureActionType.COMMIT_PAIRED, cnProfile.resolveSwipeUp('o')?.type)
        assertEquals("「", cnProfile.resolveSwipeUp('o')?.text)
        assertEquals("」", cnProfile.resolveSwipeUp('o')?.rightText)
        assertEquals(KeyGestureActionType.COMMIT_PAIRED, cnProfile.resolveSwipeUp('p')?.type)
        assertEquals("『", cnProfile.resolveSwipeUp('p')?.text)
        assertEquals("』", cnProfile.resolveSwipeUp('p')?.rightText)

        assertEquals("！", cnProfile.resolveSwipeUp('a')?.text)
        assertEquals("……", cnProfile.resolveSwipeUp('s')?.text)
        assertEquals("、", cnProfile.resolveSwipeUp('d')?.text)
        assertEquals(KeyGestureActionType.REPEAT_LAST, cnProfile.resolveSwipeUp('f')?.type)
        assertEquals("·", cnProfile.resolveSwipeUp('g')?.text)
        assertEquals("[", cnProfile.resolveSwipeUp('h')?.text)
        assertEquals("]", cnProfile.resolveSwipeUp('h')?.rightText)
        assertEquals("“", cnProfile.resolveSwipeUp('j')?.text)
        assertEquals("”", cnProfile.resolveSwipeUp('j')?.rightText)
        assertEquals("（", cnProfile.resolveSwipeUp('k')?.text)
        assertEquals("）", cnProfile.resolveSwipeUp('k')?.rightText)
        assertEquals("〔", cnProfile.resolveSwipeUp('l')?.text)
        assertEquals("〕", cnProfile.resolveSwipeUp('l')?.rightText)

        assertEquals("”", cnProfile.resolveSwipeUp('z')?.text)
        assertEquals("→", cnProfile.resolveSwipeUp('x')?.text)
        assertEquals("’", cnProfile.resolveSwipeUp('c')?.text)
        assertEquals("——", cnProfile.resolveSwipeUp('v')?.text)
        assertEquals("\u3000", cnProfile.resolveSwipeUp('b')?.text)
        assertEquals("=", cnProfile.resolveSwipeUp('n')?.text)
        assertEquals("@", cnProfile.resolveSwipeUp('m')?.text)

        // Verify long-press secondary map in Chinese vs English modes
        val topRow = "qwertyuiop"
        val digits = "1234567890"
        topRow.forEachIndexed { i, ch ->
            assertEquals(digits[i].toString(), cnProfile.resolveLongPress(ch)?.text)
        }
        assertEquals(EditorShortcutAction.SELECT_ALL, cnProfile.resolveLongPress('a')?.editorShortcut)
        assertEquals(EditorShortcutAction.CUT, cnProfile.resolveLongPress('x')?.editorShortcut)
        assertEquals(EditorShortcutAction.COPY, cnProfile.resolveLongPress('c')?.editorShortcut)
        assertEquals(EditorShortcutAction.PASTE, cnProfile.resolveLongPress('v')?.editorShortcut)
        assertEquals("¥", cnProfile.resolveLongPress('f')?.text)
        assertEquals("：", cnProfile.resolveLongPress('l')?.text)
        assertEquals("、", cnProfile.resolveLongPress('b')?.text)
        assertEquals("！", cnProfile.resolveLongPress('n')?.text)
        assertEquals("？", cnProfile.resolveLongPress('m')?.text)

        val enProfile = KeyGestureConfig.defaultProfile(isChinese = false, showNumberRow = true)
        assertEquals("$", enProfile.resolveLongPress('f')?.text)
        assertEquals(":", enProfile.resolveLongPress('l')?.text)
        assertEquals("/", enProfile.resolveLongPress('b')?.text)
        assertEquals("!", enProfile.resolveLongPress('n')?.text)
        assertEquals("?", enProfile.resolveLongPress('m')?.text)
        assertEquals("\"", enProfile.resolveLongPress('j')?.text)
        assertEquals("(", enProfile.resolveLongPress('k')?.text)
        assertEquals(")", enProfile.resolveLongPress('k')?.rightText)
        // Top row remains customizable / populated even when showNumberRow = true
        assertEquals("1", enProfile.resolveLongPress('q')?.text)
    }

    @Test
    fun keyGestureConfig_jsonRoundTripPartialOverridesAndFallbackAreSafe() {
        val original = KeyGestureConfig.defaultProfile(isChinese = true, showNumberRow = false)
        val json = KeyGestureConfig.toJson(original)
        val restored = KeyGestureConfig.fromJson(json, original)
        assertEquals(original, restored)

        // Partial override JSON with NONE disable + custom paired + escaped tab
        val partialJson = """
            {
              "swipeUp": {
                "q": {"type": "COMMIT_TEXT", "text": "【", "rightText": "", "label": "【"},
                "w": {"type": "NONE"},
                "y": {"type": "COMMIT_PAIRED", "text": "<", "rightText": ">", "label": "<>"}
              },
              "longPress": {
                "a": {"type": "EDITOR_SHORTCUT", "text": "", "rightText": "", "label": "全", "editorShortcut": "SELECT_ALL"}
              }
            }
        """.trimIndent()
        val merged = KeyGestureConfig.fromJson(partialJson, original)
        assertEquals("【", merged.resolveSwipeUp('q')?.text)
        assertNull("Disabled NONE binding must resolve to null", merged.resolveSwipeUp('w'))
        assertEquals(KeyGestureActionType.NONE, merged.swipeUpMap['w']?.type)
        assertEquals("<", merged.resolveSwipeUp('y')?.text)
        assertEquals(">", merged.resolveSwipeUp('y')?.rightText)
        // Untouched keys retain fallback defaults
        assertEquals("（", merged.resolveSwipeUp('e')?.text)
        assertEquals(EditorShortcutAction.SELECT_ALL, merged.resolveLongPress('a')?.editorShortcut)

        // Malformed or blank JSON safely returns fallback
        assertEquals(original, KeyGestureConfig.fromJson("", original))
        assertEquals(original, KeyGestureConfig.fromJson("{broken json", original))
    }

    @Test
    fun passiveCalculatorHelper_evaluatesTrailingExpressionsAndFiltersNonMath() {
        val mulEq = PassiveCalculatorHelper.evaluateTrailing("125*8=")
        assertNotNull(mulEq)
        assertEquals("125*8", mulEq!!.expression)
        assertEquals("1000", mulEq.formattedResult)
        assertTrue(mulEq.endsWithEquals)

        val cjkPrefixParen = PassiveCalculatorHelper.evaluateTrailing("价格(35+15)*2=")
        assertNotNull(cjkPrefixParen)
        assertEquals("(35+15)*2", cjkPrefixParen!!.expression)
        assertEquals("100", cjkPrefixParen.formattedResult)
        assertTrue(cjkPrefixParen.endsWithEquals)

        val withoutEquals = PassiveCalculatorHelper.evaluateTrailing("100+25")
        assertNotNull(withoutEquals)
        assertEquals("100+25", withoutEquals!!.expression)
        assertEquals("125", withoutEquals.formattedResult)
        assertFalse(withoutEquals.endsWithEquals)

        val unicodeOps = PassiveCalculatorHelper.evaluateTrailing("金额＝（12×3）÷4＝")
        assertNotNull(unicodeOps)
        assertEquals("9", unicodeOps!!.formattedResult)
        assertTrue(unicodeOps.endsWithEquals)

        val powerAndMod = PassiveCalculatorHelper.evaluateTrailing("2^3+10%4")
        assertNotNull(powerAndMod)
        assertEquals("10", powerAndMod!!.formattedResult)

        val negativePlus = PassiveCalculatorHelper.evaluateTrailing("-50+15")
        assertNotNull(negativePlus)
        assertEquals("-35", negativePlus!!.formattedResult)

        // Lookback window capped at 64 chars
        val longPrefix = "x".repeat(80) + " 12+8="
        val capped = PassiveCalculatorHelper.evaluateTrailing(longPrefix)
        assertNotNull(capped)
        assertEquals("20", capped!!.formattedResult)

        // Ignored cases
        assertNull("Bare assignment a=50 must be ignored", PassiveCalculatorHelper.evaluateTrailing("a=50"))
        assertNull("Bare number must be ignored", PassiveCalculatorHelper.evaluateTrailing("12345"))
        assertNull("Bare number with equals must be ignored", PassiveCalculatorHelper.evaluateTrailing("123="))
        assertNull("Unary-only negative must be ignored", PassiveCalculatorHelper.evaluateTrailing("-50"))
        assertNull("Identifier token v1+2 must be ignored", PassiveCalculatorHelper.evaluateTrailing("v1+2"))
        assertNull("Completed result 2+2=4 must be ignored", PassiveCalculatorHelper.evaluateTrailing("2+2=4"))
        assertNull("Trailing whitespace stops evaluation", PassiveCalculatorHelper.evaluateTrailing("10+20 "))
        assertNull("Incomplete operator 10+ must be ignored", PassiveCalculatorHelper.evaluateTrailing("10+"))
        assertNull("Division by zero must return null", PassiveCalculatorHelper.evaluateTrailing("10/0="))
    }

    @Test
    fun englishWordSuggester_extractsPrefixAdaptsCaseAndSuggestsFromTrie() {
        val suggester = EnglishWordSuggester()

        // Short high-freq 2-letter prefix -> lowercase completions
        val resLower = suggester.suggestForBeforeCursor("hello th")
        assertNotNull(resLower)
        assertEquals("th", resLower!!.prefix)
        assertEquals(2, resLower.replaceLength)
        assertTrue(resLower.suggestions.isNotEmpty())
        assertTrue(resLower.suggestions.all { it.startsWith("th") && it == it.lowercase() })
        assertTrue("the" in resLower.suggestions)

        // TitleCase adaptation
        val resTitle = suggester.suggestForBeforeCursor("Greeting: And")
        assertNotNull(resTitle)
        assertEquals("And", resTitle!!.prefix)
        assertEquals(3, resTitle.replaceLength)
        assertTrue(resTitle.suggestions.all { it[0].isUpperCase() && it.drop(1).all(Char::isLowerCase) })
        assertTrue("Android" in resTitle.suggestions)

        // ALL CAPS adaptation
        val resUpper = suggester.suggestForBeforeCursor("(GIT")
        assertNotNull(resUpper)
        assertEquals("GIT", resUpper!!.prefix)
        assertEquals(3, resUpper.replaceLength)
        assertTrue(resUpper.suggestions.all { it.all(Char::isUpperCase) })
        assertTrue("GITHUB" in resUpper.suggestions)

        // Boundary rules
        assertNull("Single char too short", suggester.suggestForBeforeCursor("a"))
        assertNull("Over 24 chars rejected", suggester.suggestForBeforeCursor("a".repeat(25)))
        assertNull("Digit before prefix rejected", suggester.suggestForBeforeCursor("v2git"))
        assertNull("Underscore before prefix rejected", suggester.suggestForBeforeCursor("my_git"))
        assertNull("Trailing space has no active prefix", suggester.suggestForBeforeCursor("git "))

        // Custom trie injection
        val customSuggester = EnglishWordSuggester(
            customTrie = EnglishWordTrie(listOf("typefree", "typescript", "typing"))
        )
        val customRes = customSuggester.suggestForBeforeCursor("Use Ty")
        assertNotNull(customRes)
        assertEquals("Ty", customRes!!.prefix)
        assertTrue("Typefree" in customRes.suggestions)
        assertTrue("Typing" in customRes.suggestions)
    }
}
