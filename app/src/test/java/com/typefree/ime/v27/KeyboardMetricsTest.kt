package com.typefree.ime.v27

import androidx.compose.ui.unit.dp
import com.typefree.ime.data.AppSettings
import com.typefree.ime.data.InputScheme
import com.typefree.ime.ime.KeyboardMetrics
import com.typefree.ime.ime.nextCycledInputScheme
import com.typefree.ime.ime.shortInputSchemeLabel
import com.typefree.ime.ui.screens.ApiConfigBackupHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardMetricsTest {

    @Test
    fun `portrait 5 levels compute exact scale, frameHeight 268-283-298-316-333dp, and elementScale capped at 1f`() {
        val mCompact = KeyboardMetrics.fromLevel(level = -2, isLandscape = false)
        assertEquals(-2, mCompact.level)
        assertEquals(0.88f, mCompact.scale, 0.0001f)
        assertEquals(0.88f, mCompact.elementScale, 0.0001f)
        assertEquals(268.dp, mCompact.frameHeight)
        assertEquals(220.dp, mCompact.keyAreaHeight)
        assertEquals(34.dp, mCompact.topBarHeight)
        assertEquals(1.dp, mCompact.dividerHeight)

        val mLow = KeyboardMetrics.fromLevel(level = -1, isLandscape = false)
        assertEquals(-1, mLow.level)
        assertEquals(0.94f, mLow.scale, 0.0001f)
        assertEquals(0.94f, mLow.elementScale, 0.0001f)
        assertEquals(283.dp, mLow.frameHeight)
        assertEquals(235.dp, mLow.keyAreaHeight)
        assertEquals(34.dp, mLow.topBarHeight)
        assertEquals(1.dp, mLow.dividerHeight)

        val mStandard = KeyboardMetrics.fromLevel(level = 0, isLandscape = false)
        assertEquals(0, mStandard.level)
        assertEquals(1.00f, mStandard.scale, 0.0001f)
        assertEquals(1.00f, mStandard.elementScale, 0.0001f)
        assertEquals(298.dp, mStandard.frameHeight)
        assertEquals(250.dp, mStandard.keyAreaHeight)
        assertEquals(34.dp, mStandard.topBarHeight)
        assertEquals(1.dp, mStandard.dividerHeight)

        val mTall = KeyboardMetrics.fromLevel(level = 1, isLandscape = false)
        assertEquals(1, mTall.level)
        assertEquals(1.07f, mTall.scale, 0.0001f)
        assertEquals(1.00f, mTall.elementScale, 0.0001f)
        assertTrue(mTall.elementScale <= 1.0f)
        assertEquals(316.dp, mTall.frameHeight)
        assertEquals(268.dp, mTall.keyAreaHeight)
        assertEquals(34.dp, mTall.topBarHeight)
        assertEquals(1.dp, mTall.dividerHeight)

        val mComfort = KeyboardMetrics.fromLevel(level = 2, isLandscape = false)
        assertEquals(2, mComfort.level)
        assertEquals(1.14f, mComfort.scale, 0.0001f)
        assertEquals(1.00f, mComfort.elementScale, 0.0001f)
        assertTrue(mComfort.elementScale <= 1.0f)
        assertEquals(333.dp, mComfort.frameHeight)
        assertEquals(285.dp, mComfort.keyAreaHeight)
        assertEquals(34.dp, mComfort.topBarHeight)
        assertEquals(1.dp, mComfort.dividerHeight)
    }

    @Test
    fun `landscape mode scales by 0_88 times 0_8`() {
        for (inputLevel in KeyboardMetrics.ALL_LEVELS) {
            val landscapeMetrics = KeyboardMetrics.fromLevel(level = inputLevel, isLandscape = true)
            assertTrue(landscapeMetrics.isLandscape)
            assertEquals(inputLevel, landscapeMetrics.level)
            assertEquals(0.88f * 0.8f, landscapeMetrics.scale, 0.0001f)
            assertEquals(0.88f * 0.8f, landscapeMetrics.elementScale, 0.0001f)
            assertTrue(landscapeMetrics.elementScale <= 1.0f)
            assertEquals(34.dp, landscapeMetrics.topBarHeight)
            assertEquals(1.dp, landscapeMetrics.dividerHeight)
            // 48 + round(250 * 0.704) = 48 + 176 = 224.dp
            assertEquals(224.dp, landscapeMetrics.frameHeight)
        }
    }

    @Test
    fun `out-of-range levels clamp safely and level labels match specification`() {
        assertEquals(-2, KeyboardMetrics.fromLevel(-99).level)
        assertEquals(2, KeyboardMetrics.fromLevel(99).level)

        assertEquals("紧凑", KeyboardMetrics.labelForLevel(-2))
        assertEquals("偏低", KeyboardMetrics.labelForLevel(-1))
        assertEquals("标准", KeyboardMetrics.labelForLevel(0))
        assertEquals("偏高", KeyboardMetrics.labelForLevel(1))
        assertEquals("舒适", KeyboardMetrics.labelForLevel(2))
    }

    @Test
    fun `v27 AppSettings new fields round-trip and tool panel scheme cycle works`() {
        val custom = AppSettings(
            keyboardHeightLevel = -1,
            liveWriteToEditor = false,
            inputScheme = InputScheme.RIME_PINYIN,
            fixedCandidateOrder = true,
            predictionEnabled = true,
            aiCompletionEnabled = true
        )
        val json = ApiConfigBackupHelper.exportSettingsJson(custom, includeSecrets = true)
        val restored = ApiConfigBackupHelper.parseAndMergeConfigJson(json, AppSettings()).getOrThrow()

        assertEquals(-1, restored.keyboardHeightLevel)
        assertFalse(restored.liveWriteToEditor)
        assertEquals(InputScheme.RIME_PINYIN, restored.inputScheme)
        assertEquals(true, restored.fixedCandidateOrder)
        assertTrue(restored.predictionEnabled)
        assertTrue(restored.aiCompletionEnabled)

        // Default null fixedCandidateOrder round-trip and keyboardHeightLevel clamping
        val defaultOrderJson = ApiConfigBackupHelper.exportSettingsJson(
            AppSettings(fixedCandidateOrder = null, keyboardHeightLevel = 99),
            includeSecrets = false
        )
        val restoredDefault = ApiConfigBackupHelper.parseAndMergeConfigJson(
            defaultOrderJson,
            AppSettings()
        ).getOrThrow()
        assertNull(restoredDefault.fixedCandidateOrder)
        assertEquals(2, restoredDefault.keyboardHeightLevel)

        // Explicit null fixedCandidateOrder in JSON resets existing non-null value to null
        val explicitNullJson = """{"fixedCandidateOrder": null, "keyboardHeightLevel": -99}"""
        val restoredExplicitNull = ApiConfigBackupHelper.parseAndMergeConfigJson(
            explicitNullJson,
            AppSettings(fixedCandidateOrder = false)
        ).getOrThrow()
        assertNull(restoredExplicitNull.fixedCandidateOrder)
        assertEquals(-2, restoredExplicitNull.keyboardHeightLevel)

        // Input scheme cycle in tool panel
        assertEquals(InputScheme.RIME_DOUBLE_PINYIN_FLYPY, nextCycledInputScheme(InputScheme.RIME_PINYIN))
        assertEquals(InputScheme.FLYPY_TABLE, nextCycledInputScheme(InputScheme.RIME_DOUBLE_PINYIN_FLYPY))
        assertEquals(InputScheme.RIME_PINYIN, nextCycledInputScheme(InputScheme.FLYPY_TABLE))
        assertEquals("雾凇全拼", shortInputSchemeLabel(InputScheme.RIME_PINYIN))
        assertEquals("小鹤双拼", shortInputSchemeLabel(InputScheme.RIME_DOUBLE_PINYIN_FLYPY))
        assertEquals("小鹤音形", shortInputSchemeLabel(InputScheme.FLYPY_TABLE))
    }
}
