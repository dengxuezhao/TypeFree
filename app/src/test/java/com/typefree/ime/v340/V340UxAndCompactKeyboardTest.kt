package com.typefree.ime.v340

import androidx.compose.ui.unit.dp
import com.typefree.ime.data.AppRepository
import com.typefree.ime.data.AppSettings
import com.typefree.ime.data.InputScheme
import com.typefree.ime.data.KeyboardHeightMode
import com.typefree.ime.ime.EditorShortcutAction
import com.typefree.ime.ime.ImeLogic
import com.typefree.ime.ime.ImeUiAction
import com.typefree.ime.ime.KeyboardMetrics
import com.typefree.ime.ime.KeyboardTabMode
import com.typefree.ime.ime.QwertySecondaryKeyMap
import com.typefree.ime.ime.SecondaryKeyAction
import com.typefree.ime.ime.chrome.TopBarSlotPolicy
import com.typefree.ime.ui.screens.ApiConfigBackupHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class V340UxAndCompactKeyboardTest {

    private fun resolveProjectRoot(): File {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        return if (File(cwd, "app/src/main").exists()) {
            cwd
        } else {
            cwd.parentFile ?: cwd
        }
    }

    @Test
    fun keyboardMetrics_4RowCompact252dpAnd5Row294dpWithLiftLevels_conserveHeightAcrossModes() {
        val expected4Row = listOf(252, 260, 268, 276, 284)
        for ((idx, liftLevel) in KeyboardMetrics.ALL_LIFT_LEVELS.withIndex()) {
            val m4 = KeyboardMetrics.fromLevel(
                level = 0,
                isLandscape = false,
                heightMode = KeyboardHeightMode.LIFT,
                liftLevel = liftLevel,
                showNumberRow = false
            )
            assertEquals(KeyboardHeightMode.LIFT, m4.heightMode)
            assertEquals(210.dp, m4.keyAreaHeight)
            assertEquals(34.dp, m4.topBarHeight)
            assertEquals(1.dp, m4.dividerHeight)
            assertEquals((liftLevel * 8).dp, m4.liftHeight)
            assertEquals(expected4Row[idx].dp, m4.frameHeight)
        }

        val expected5Row = listOf(294, 302, 310, 318, 326)
        for ((idx, liftLevel) in KeyboardMetrics.ALL_LIFT_LEVELS.withIndex()) {
            val m5 = KeyboardMetrics.fromLevel(
                level = 0,
                isLandscape = false,
                heightMode = KeyboardHeightMode.LIFT,
                liftLevel = liftLevel,
                showNumberRow = true
            )
            assertEquals(KeyboardHeightMode.LIFT, m5.heightMode)
            assertEquals(252.dp, m5.keyAreaHeight)
            assertEquals((liftLevel * 8).dp, m5.liftHeight)
            assertEquals(expected5Row[idx].dp, m5.frameHeight)
        }

        // Default call uses 4-row LIFT 0dp = 252.dp
        val defaultMetrics = KeyboardMetrics.fromLevel(level = 0)
        assertEquals(252.dp, defaultMetrics.frameHeight)
        assertEquals(210.dp, defaultMetrics.keyAreaHeight)
        assertEquals(0.dp, defaultMetrics.liftHeight)
    }

    @Test
    fun appSettingsDefaults_configBackupAndV340Migration_preserveUserToggleAfterSmoothUpgrade() {
        val defaults = AppSettings()
        assertFalse("v3.4.0 default must hide number row for 252.dp compact 4-row layout", defaults.qwertyShowNumberRow)
        assertEquals("v3.4.0 default height mode must be LIFT", KeyboardHeightMode.LIFT, defaults.keyboardHeightMode)
        assertEquals(0, defaults.keyboardLiftLevel)

        // Smooth one-time migration from legacy v3.3.1 STRETCH + 5-row settings
        val legacyStretch0 = AppSettings(
            qwertyShowNumberRow = true,
            keyboardHeightMode = KeyboardHeightMode.STRETCH,
            keyboardHeightLevel = 0,
            keyboardLiftLevel = 0
        )
        val migrated0 = AppRepository.migrateSettingsToV340CompactLift(legacyStretch0, alreadyMigrated = false)
        assertFalse(migrated0.qwertyShowNumberRow)
        assertEquals(KeyboardHeightMode.LIFT, migrated0.keyboardHeightMode)
        assertEquals(0, migrated0.keyboardLiftLevel)

        val legacyStretch1 = legacyStretch0.copy(keyboardHeightLevel = 1)
        val migrated1 = AppRepository.migrateSettingsToV340CompactLift(legacyStretch1, alreadyMigrated = false)
        assertEquals(2, migrated1.keyboardLiftLevel)

        val legacyStretch2 = legacyStretch0.copy(keyboardHeightLevel = 2)
        val migrated2 = AppRepository.migrateSettingsToV340CompactLift(legacyStretch2, alreadyMigrated = false)
        assertEquals(3, migrated2.keyboardLiftLevel)

        // Once alreadyMigrated = true, user manual preference (e.g. turning number row back on) is respected
        val userReEnabledNumRow = AppRepository.migrateSettingsToV340CompactLift(
            migrated2.copy(qwertyShowNumberRow = true),
            alreadyMigrated = true
        )
        assertTrue(userReEnabledNumRow.qwertyShowNumberRow)

        // Config export/import round-trip for qwertyShowNumberRow
        val exported = ApiConfigBackupHelper.exportSettingsJson(
            defaults.copy(qwertyShowNumberRow = true, keyboardLiftLevel = 2),
            includeSecrets = false
        )
        assertTrue(exported.contains("\"qwertyShowNumberRow\": true"))
        val imported = ApiConfigBackupHelper.parseAndMergeConfigJson(exported, defaults).getOrThrow()
        assertTrue(imported.qwertyShowNumberRow)
        assertEquals(2, imported.keyboardLiftLevel)
    }

    @Test
    fun qwertySecondaryKeyMap_coversAll26KeysInChineseAndEnglishModes_andGuardsEditShortcutsFromSwipeUp() {
        val topRow = listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p")
        val expectedDigits = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")

        // When showNumberRow = false, top row maps to 1..0 and allows both long-press and swipe-up
        topRow.forEachIndexed { idx, key ->
            val specCn = QwertySecondaryKeyMap.resolve(key, isChinese = true, showNumberRow = false)
            assertNotNull("Expected digit spec for $key when showNumberRow=false", specCn)
            assertEquals(expectedDigits[idx], specCn!!.displayBadge)
            assertTrue(specCn.allowSwipeUp)
            assertEquals(SecondaryKeyAction.CommitSymbol(expectedDigits[idx]), specCn.action)

            // When showNumberRow = true, top row does not duplicate digit badges
            val specWithNumRow = QwertySecondaryKeyMap.resolve(key, isChinese = true, showNumberRow = true)
            assertNull("Top row should not duplicate digit badge when number row is visible", specWithNumRow)
        }

        // Edit shortcut keys A / X / C / V must NEVER allow swipe-up (only 250ms long-press)
        val shortcutKeys = mapOf(
            "a" to ("全" to EditorShortcutAction.SELECT_ALL),
            "x" to ("剪" to EditorShortcutAction.CUT),
            "c" to ("复" to EditorShortcutAction.COPY),
            "v" to ("粘" to EditorShortcutAction.PASTE)
        )
        for ((key, pair) in shortcutKeys) {
            val spec = QwertySecondaryKeyMap.resolve(key, isChinese = true, showNumberRow = false)!!
            assertEquals(pair.first, spec.displayBadge)
            assertFalse("Edit shortcut key '$key' must forbid swipe-up to prevent accidental cut/select-all", spec.allowSwipeUp)
            assertEquals(SecondaryKeyAction.EditorShortcut(pair.second), spec.action)
            assertFalse(
                QwertySecondaryKeyMap.shouldTriggerSwipeUp(
                    spec = spec,
                    dxPx = 0f,
                    dyPx = -80f,
                    thresholdPx = 36f
                )
            )
        }

        // Bilingual punctuation & paired symbol keys in Chinese vs English modes
        val fCn = QwertySecondaryKeyMap.resolve("f", isChinese = true, showNumberRow = false)!!
        val fEn = QwertySecondaryKeyMap.resolve("f", isChinese = false, showNumberRow = false)!!
        assertEquals("¥", fCn.displayBadge)
        assertEquals("$", fEn.displayBadge)

        val jCn = QwertySecondaryKeyMap.resolve("j", isChinese = true, showNumberRow = false)!!
        val jEn = QwertySecondaryKeyMap.resolve("j", isChinese = false, showNumberRow = false)!!
        assertEquals("“”", jCn.displayBadge)
        assertEquals(SecondaryKeyAction.CommitPaired("“", "”"), jCn.action)
        assertEquals("\"\"", jEn.displayBadge)
        assertEquals(SecondaryKeyAction.CommitPaired("\"", "\""), jEn.action)

        val kCn = QwertySecondaryKeyMap.resolve("k", isChinese = true, showNumberRow = false)!!
        val kEn = QwertySecondaryKeyMap.resolve("k", isChinese = false, showNumberRow = false)!!
        assertEquals("()", kCn.displayBadge)
        assertEquals(SecondaryKeyAction.CommitPaired("（", "）"), kCn.action)
        assertEquals(SecondaryKeyAction.CommitPaired("(", ")"), kEn.action)

        val lCn = QwertySecondaryKeyMap.resolve("l", isChinese = true, showNumberRow = false)!!
        val lEn = QwertySecondaryKeyMap.resolve("l", isChinese = false, showNumberRow = false)!!
        assertEquals("：", lCn.displayBadge)
        assertEquals(":", lEn.displayBadge)

        val bCn = QwertySecondaryKeyMap.resolve("b", isChinese = true, showNumberRow = false)!!
        val bEn = QwertySecondaryKeyMap.resolve("b", isChinese = false, showNumberRow = false)!!
        assertEquals("、", bCn.displayBadge)
        assertEquals("/", bEn.displayBadge)

        val nCn = QwertySecondaryKeyMap.resolve("n", isChinese = true, showNumberRow = false)!!
        val nEn = QwertySecondaryKeyMap.resolve("n", isChinese = false, showNumberRow = false)!!
        assertEquals("！", nCn.displayBadge)
        assertEquals("!", nEn.displayBadge)

        val mCn = QwertySecondaryKeyMap.resolve("m", isChinese = true, showNumberRow = false)!!
        val mEn = QwertySecondaryKeyMap.resolve("m", isChinese = false, showNumberRow = false)!!
        assertEquals("？", mCn.displayBadge)
        assertEquals("?", mEn.displayBadge)
        assertTrue(
            QwertySecondaryKeyMap.shouldTriggerSwipeUp(
                spec = mCn,
                dxPx = 5f,
                dyPx = -40f,
                thresholdPx = 36f
            )
        )

        // Paired symbol insertion: empty selection centers cursor; non-empty selection wraps text
        val emptyQuote = QwertySecondaryKeyMap.formatPairedInsertion("“", "”", selectedText = "")
        assertEquals("“”", emptyQuote.first)
        assertEquals(1, emptyQuote.second)

        val wrappedQuote = QwertySecondaryKeyMap.formatPairedInsertion("“", "”", selectedText = "重点内容")
        assertEquals("“重点内容”", wrappedQuote.first)
        assertEquals(6, wrappedQuote.second)

        // Z key (`) preserves Xiaohe Yinxing stroke reverse-lookup when composing in FLYPY_TABLE
        assertTrue(
            QwertySecondaryKeyMap.shouldRouteBacktickToComposingEngine(
                symbol = "`",
                inputScheme = InputScheme.FLYPY_TABLE,
                isComposing = true
            )
        )
        assertFalse(
            QwertySecondaryKeyMap.shouldRouteBacktickToComposingEngine(
                symbol = "`",
                inputScheme = InputScheme.RIME_PINYIN,
                isComposing = true
            )
        )
        assertFalse(
            QwertySecondaryKeyMap.shouldRouteBacktickToComposingEngine(
                symbol = "`",
                inputScheme = InputScheme.FLYPY_TABLE,
                isComposing = false
            )
        )
    }

    @Test
    fun preeditDisplay38Chars_spaceCursorSlideSteps_andTopBarSlotPolicy_matchV340Spec() {
        val longPinyin = "wo'men'ming'tian'xia'wu'san'dian"
        val displayed = ImeLogic.formatPreeditDisplay(
            preedit = longPinyin,
            rawCode = "womenmingtianxiawusandian",
            scheme = InputScheme.RIME_PINYIN
        )
        assertEquals("Full 32-char segmented pinyin must not be truncated under 38-char limit", longPinyin, displayed)

        // Spacebar cursor trackpad step calculation (11dp step)
        val stepPx = 22f
        val step1 = ImeLogic.computeSpaceCursorSlideSteps(accumulatedDxPx = 25f, stepPx = stepPx)
        assertEquals(1, step1.first)
        assertEquals(3f, step1.second, 0.001f)

        val stepNeg2 = ImeLogic.computeSpaceCursorSlideSteps(accumulatedDxPx = -48f, stepPx = stepPx)
        assertEquals(-2, stepNeg2.first)
        assertEquals(-4f, stepNeg2.second, 0.001f)

        // TopBarSlotPolicy: QWERTY hides style badge, shows 1-tap clipboard button and hold-to-talk voice capsule
        val qwertySlots = TopBarSlotPolicy.resolveLeftSlots(
            activeTab = KeyboardTabMode.QWERTY,
            isComposing = false,
            hasUndoSnapshot = true,
            hasFreshClipboardBanner = true,
            hasActiveSelection = false,
            jevCopilotEnabled = true
        )
        assertTrue(qwertySlots.showUndoButton)
        assertTrue(qwertySlots.showClipboardButton)
        assertTrue(qwertySlots.showClipboardQuickPaste)
        assertFalse("QWERTY mode must hide the AI Tone Style badge", qwertySlots.showStyleBadge)
        assertTrue("QWERTY mode must enable the hold-to-talk voice capsule", qwertySlots.showQwertyHoldVoiceCapsule)

        // VOICE mode shows the AI Tone Style badge and 1-tap clipboard button
        val voiceSlots = TopBarSlotPolicy.resolveLeftSlots(
            activeTab = KeyboardTabMode.VOICE,
            isComposing = false,
            hasUndoSnapshot = false,
            hasFreshClipboardBanner = false,
            hasActiveSelection = false,
            jevCopilotEnabled = true
        )
        assertTrue(voiceSlots.showClipboardButton)
        assertTrue("VOICE mode must show the AI Tone Style badge", voiceSlots.showStyleBadge)
        assertFalse(voiceSlots.showQwertyHoldVoiceCapsule)

        // New ImeUiActions for secondary symbol and cursor movement
        val moveAction = ImeUiAction.MoveCursorByChars(-3)
        val symAction = ImeUiAction.CommitSecondarySymbol("？")
        assertEquals(-3, moveAction.delta)
        assertEquals("？", symAction.symbol)
    }

    @Test
    fun v340SourceArchitectureGuards_jevZeroEllipsis_voiceNoDuplicateJev_andKeyboardScreenUnder650Lines() {
        val root = resolveProjectRoot()

        // 1. JevCopilotPanel.kt must not truncate replyText with TextOverflow.Ellipsis or render BottomCornerUtilityIcons
        val jevFile = File(root, "app/src/main/java/com/typefree/ime/ime/panels/JevCopilotPanel.kt")
        val jevSource = jevFile.readText()
        assertFalse(
            "JevCopilotPanel.kt must not contain TextOverflow.Ellipsis",
            jevSource.contains("TextOverflow.Ellipsis")
        )
        assertFalse(
            "JevCopilotPanel.kt must not contain BottomCornerUtilityIcons",
            jevSource.contains("BottomCornerUtilityIcons")
        )
        assertTrue(
            "JevCopilotPanel.kt must use InlineTextContent for first-line inline strategy pill wrapping",
            jevSource.contains("InlineTextContent")
        )
        assertTrue(
            "JevCopilotPanel.kt must wrap strategy cards in verticalScroll",
            jevSource.contains("verticalScroll(rememberScrollState())")
        )

        // 2. VoiceMainPanel.kt must not duplicate the Jev Copilot pill in the bottom-left cluster
        val voiceFile = File(root, "app/src/main/java/com/typefree/ime/ime/panels/VoiceMainPanel.kt")
        val voiceSource = voiceFile.readText()
        assertFalse(
            "VoiceMainPanel.kt must not render duplicate CopilotStar pill in bottom-left key cluster",
            voiceSource.contains("TypeFreeIcons.CopilotStar")
        )

        // 3. BuiltInQwertyKeyboard.kt must have cursor sliding hint and no 44.dp fake bottom tray
        val qwertyFile = File(root, "app/src/main/java/com/typefree/ime/ime/BuiltInQwertyKeyboard.kt")
        val qwertySource = qwertyFile.readText()
        assertTrue(
            "BuiltInQwertyKeyboard.kt must display '‹ 滑动移光标 ›' when sliding spacebar",
            qwertySource.contains("‹ 滑动移光标 ›")
        )
        assertFalse(
            "BuiltInQwertyKeyboard.kt must not contain the legacy 44.dp fake bottom utility tray",
            qwertySource.contains("height(44.dp)")
        )

        // 4. KeyboardScreen.kt must stay <= 650 lines
        val keyboardScreenFile = File(root, "app/src/main/java/com/typefree/ime/ime/KeyboardScreen.kt")
        val lineCount = keyboardScreenFile.readLines().size
        assertTrue(
            "KeyboardScreen.kt must stay <= 650 lines (actual: $lineCount)",
            lineCount <= 650
        )
    }
}
