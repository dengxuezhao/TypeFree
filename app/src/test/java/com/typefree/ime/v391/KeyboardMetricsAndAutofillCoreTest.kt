package com.typefree.ime.v391

import android.os.Build
import android.text.InputType
import androidx.compose.ui.unit.dp
import com.typefree.ime.data.AppRepository
import com.typefree.ime.data.AppSettings
import com.typefree.ime.data.CandidateFontScale
import com.typefree.ime.data.InMemoryKeyValueStore
import com.typefree.ime.data.JvmAesGcmCipher
import com.typefree.ime.data.KeyboardHeightMode
import com.typefree.ime.data.OneHandedMode
import com.typefree.ime.data.SecretVault
import com.typefree.ime.data.SpaceLongPressAction
import com.typefree.ime.ime.KeyboardMetrics
import com.typefree.ime.ime.autofill.InlineAutofillController
import com.typefree.ime.ime.autofill.InlineAutofillPlatformAdapter
import com.typefree.ime.ime.autofill.InlineRequestPlan
import com.typefree.ime.ime.autofill.InlineSuggestionDescriptor
import com.typefree.ime.ime.autofill.InlineSuggestionKind
import com.typefree.ime.ime.autofill.InlineSuggestionSource
import com.typefree.ime.ime.autofill.JetpackInlineStyleSpec
import com.typefree.ime.ui.screens.ApiConfigBackupHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardMetricsAndAutofillCoreTest {

    @Test
    fun `legacy fromLevel preserves exact v3_9_0 pixel heights and stretch vs lift behavior`() {
        val expectedStretch4Row = listOf(227, 239, 252, 267, 281)
        KeyboardMetrics.ALL_LEVELS.forEachIndexed { idx, level ->
            val m = KeyboardMetrics.fromLevel(
                level = level,
                isLandscape = false,
                heightMode = KeyboardHeightMode.STRETCH,
                showNumberRow = false
            )
            assertEquals(expectedStretch4Row[idx].dp, m.frameHeight)
            assertEquals(34.dp, m.topBarHeight)
            assertEquals(29.dp, m.candidateChipHeight)
            assertEquals(15.5f, m.candidateFontSizeSp, 0.001f)
            assertEquals(42, m.fixedChromeDp)
            assertEquals(0.dp, m.liftHeight)
        }

        val expectedLift4Row = listOf(252, 260, 268, 276, 284)
        KeyboardMetrics.ALL_LIFT_LEVELS.forEachIndexed { idx, liftLevel ->
            val m = KeyboardMetrics.fromLevel(
                level = 2, // Legacy LIFT mode ignores stretch level when decoupledLiftDp is null
                isLandscape = false,
                heightMode = KeyboardHeightMode.LIFT,
                liftLevel = liftLevel,
                showNumberRow = false
            )
            assertEquals(1.00f, m.scale, 0.0001f)
            assertEquals(1.00f, m.keyFontScale, 0.0001f)
            assertEquals(210.dp, m.keyAreaHeight)
            assertEquals((liftLevel * 8).dp, m.liftHeight)
            assertEquals(expectedLift4Row[idx].dp, m.frameHeight)
        }

        val landscape = KeyboardMetrics.fromLevel(
            level = 2,
            isLandscape = true,
            heightMode = KeyboardHeightMode.LIFT,
            liftLevel = 4
        )
        assertEquals(0.dp, landscape.liftHeight)
        assertEquals(190.dp, landscape.frameHeight)
    }

    @Test
    fun `decoupled resolve combines key scale and 0_to_56dp bottom lift additively and zeroes lift in landscape`() {
        // Simultaneous level = +2 (1.14x -> 239.dp) + bottomLiftDp = 48 -> 42 + 239 + 48 = 329.dp
        val comfortPlusLift = KeyboardMetrics.resolve(
            level = 2,
            bottomLiftDp = 48,
            isLandscape = false,
            showNumberRow = false,
            candidateFontScale = CandidateFontScale.STANDARD
        )
        assertEquals(2, comfortPlusLift.level)
        assertEquals(1.14f, comfortPlusLift.scale, 0.0001f)
        assertEquals(1.00f, comfortPlusLift.elementScale, 0.0001f)
        assertEquals(1.08f, comfortPlusLift.keyFontScale, 0.0001f)
        assertEquals(239.dp, comfortPlusLift.keyAreaHeight)
        assertEquals(48.dp, comfortPlusLift.liftHeight)
        assertEquals(329.dp, comfortPlusLift.frameHeight)

        // Same result via optional decoupledLiftDp parameter on fromLevel
        val viaFromLevel = KeyboardMetrics.fromLevel(
            level = 2,
            isLandscape = false,
            decoupledLiftDp = 48,
            candidateFontScale = CandidateFontScale.STANDARD
        )
        assertEquals(comfortPlusLift, viaFromLevel)

        // Compact (-2 -> 0.88x -> 185.dp) + max 56dp bottom cushion -> 42 + 185 + 56 = 283.dp
        val compactMaxLift = KeyboardMetrics.resolve(
            level = -2,
            bottomLiftDp = 99, // Clamps to 56dp
            isLandscape = false,
            showNumberRow = false
        )
        assertEquals(0.88f, compactMaxLift.scale, 0.0001f)
        assertEquals(0.88f, compactMaxLift.elementScale, 0.0001f)
        assertEquals(0.94f, compactMaxLift.keyFontScale, 0.0001f)
        assertEquals(185.dp, compactMaxLift.keyAreaHeight)
        assertEquals(56.dp, compactMaxLift.liftHeight)
        assertEquals(283.dp, compactMaxLift.frameHeight)

        // Negative bottomLiftDp clamps to 0.dp
        val clampedZeroLift = KeyboardMetrics.resolve(level = 0, bottomLiftDp = -12)
        assertEquals(0.dp, clampedZeroLift.liftHeight)
        assertEquals(252.dp, clampedZeroLift.frameHeight)

        // Gentle keyFontScale table across -2..+2
        val expectedKeyFontScales = listOf(0.94f, 0.97f, 1.00f, 1.04f, 1.08f)
        KeyboardMetrics.ALL_LEVELS.forEachIndexed { idx, lvl ->
            val m = KeyboardMetrics.resolve(level = lvl, bottomLiftDp = 16)
            assertEquals(expectedKeyFontScales[idx], m.keyFontScale, 0.0001f)
        }

        // Landscape enforces 0.dp bottom lift regardless of portrait bottomLiftDp
        val landscapeComfortLift = KeyboardMetrics.resolve(
            level = 2,
            bottomLiftDp = 48,
            isLandscape = true,
            showNumberRow = false
        )
        assertEquals(0.dp, landscapeComfortLift.liftHeight)
        assertEquals(148.dp, landscapeComfortLift.keyAreaHeight)
        assertEquals(190.dp, landscapeComfortLift.frameHeight)
        assertEquals(0.90f, landscapeComfortLift.keyFontScale, 0.0001f)
    }

    @Test
    fun `CandidateFontScale links topBarHeight, candidateChipHeight, candidateFontSizeSp, and frameHeight invariants`() {
        val mStandard = KeyboardMetrics.resolve(
            level = 0,
            bottomLiftDp = 0,
            candidateFontScale = CandidateFontScale.STANDARD
        )
        assertEquals(0, CandidateFontScale.STANDARD.level)
        assertEquals(34.dp, mStandard.topBarHeight)
        assertEquals(29.dp, mStandard.candidateChipHeight)
        assertEquals(15.5f, mStandard.candidateFontSizeSp, 0.001f)
        assertEquals(42, mStandard.fixedChromeDp)
        assertEquals(252.dp, mStandard.frameHeight)

        val mLarge = KeyboardMetrics.resolve(
            level = 0,
            bottomLiftDp = 0,
            candidateFontScale = CandidateFontScale.LARGE
        )
        assertEquals(1, CandidateFontScale.LARGE.level)
        assertEquals(38.dp, mLarge.topBarHeight)
        assertEquals(33.dp, mLarge.candidateChipHeight)
        assertEquals(17.0f, mLarge.candidateFontSizeSp, 0.001f)
        assertEquals(46, mLarge.fixedChromeDp)
        assertEquals(256.dp, mLarge.frameHeight)

        val mExtraLarge = KeyboardMetrics.resolve(
            level = 0,
            bottomLiftDp = 0,
            candidateFontScale = CandidateFontScale.EXTRA_LARGE
        )
        assertEquals(2, CandidateFontScale.EXTRA_LARGE.level)
        assertEquals(44.dp, mExtraLarge.topBarHeight)
        assertEquals(38.dp, mExtraLarge.candidateChipHeight)
        assertEquals(19.5f, mExtraLarge.candidateFontSizeSp, 0.001f)
        assertEquals(52, mExtraLarge.fixedChromeDp)
        assertEquals(262.dp, mExtraLarge.frameHeight)

        // Exhaustive additive frameHeight invariant across scales, levels, lift values, number row, and orientation
        for (fontScale in CandidateFontScale.entries) {
            for (level in KeyboardMetrics.ALL_LEVELS) {
                for (lift in listOf(0, 16, 48, 56)) {
                    for (numRow in listOf(false, true)) {
                        for (landscape in listOf(false, true)) {
                            val m = KeyboardMetrics.resolve(
                                level = level,
                                bottomLiftDp = lift,
                                isLandscape = landscape,
                                showNumberRow = numRow,
                                candidateFontScale = fontScale
                            )
                            val expectedChrome = m.topBarHeight.value.toInt() + 8
                            assertEquals(expectedChrome, m.fixedChromeDp)
                            val expectedFrame = m.fixedChromeDp +
                                m.keyAreaHeight.value.toInt() +
                                m.liftHeight.value.toInt()
                            assertEquals(expectedFrame.dp, m.frameHeight)
                            if (landscape) {
                                assertEquals(0.dp, m.liftHeight)
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `AppSettings defaults, legacy lift migration, AppRepository hydration, and backup round-trip`() {
        val defaults = AppSettings()
        assertEquals(-1, defaults.keyboardBottomLiftDp)
        assertEquals(0, defaults.resolvedBottomLiftDp())
        assertEquals(CandidateFontScale.STANDARD, defaults.candidateFontScale)
        assertEquals(OneHandedMode.OFF, defaults.oneHandedMode)
        assertFalse(defaults.t9LeftHandedMirror)
        assertEquals(SpaceLongPressAction.VOICE_HOLD, defaults.spaceLongPressAction)
        assertEquals(252.dp, KeyboardMetrics.fromSettings(defaults).frameHeight)

        // Pure helper migration from legacy LIFT levels 0..4 -> 0/8/16/24/32dp
        for (lvl in 0..4) {
            val legacyLift = AppSettings(
                keyboardHeightMode = KeyboardHeightMode.LIFT,
                keyboardLiftLevel = lvl
            )
            assertEquals(lvl * 8, legacyLift.resolvedBottomLiftDp())
            assertEquals(
                lvl * 8,
                AppRepository.migrateSettingsToV391DecoupledLift(legacyLift).keyboardBottomLiftDp
            )
        }
        // Legacy STRETCH mode migrates to 0dp bottom lift while preserving keyboardHeightLevel
        val legacyStretch = AppSettings(
            keyboardHeightMode = KeyboardHeightMode.STRETCH,
            keyboardHeightLevel = 2,
            keyboardLiftLevel = 3
        )
        assertEquals(0, legacyStretch.resolvedBottomLiftDp())
        val migratedStretch = AppRepository.migrateSettingsToV391DecoupledLift(legacyStretch)
        assertEquals(0, migratedStretch.keyboardBottomLiftDp)
        assertEquals(281.dp, KeyboardMetrics.fromSettings(migratedStretch).frameHeight)

        // AppRepository loading v3.9.0 JSON (without v3.9.1 keys) migrates keyboardLiftLevel * 8 cleanly
        val publicStore = InMemoryKeyValueStore()
        val secretStore = InMemoryKeyValueStore()
        val cipher = JvmAesGcmCipher()
        publicStore.putBoolean(AppRepository.KEY_MIGRATED_V340_COMPACT_4ROW_AND_LIFT, true)
        publicStore.putString(
            AppRepository.KEY_SETTINGS,
            """
            {
              "keyboardHeightMode": "LIFT",
              "keyboardLiftLevel": 3,
              "keyboardHeightLevel": 0
            }
            """.trimIndent()
        )
        val repo = AppRepository(
            appContext = null,
            publicStore = publicStore,
            secretVault = SecretVault(secretStore, cipher)
        )
        val hydrated = repo.settings.value
        assertEquals(24, hydrated.keyboardBottomLiftDp)
        assertEquals(CandidateFontScale.STANDARD, hydrated.candidateFontScale)
        assertEquals(OneHandedMode.OFF, hydrated.oneHandedMode)
        assertFalse(hydrated.t9LeftHandedMirror)
        assertEquals(SpaceLongPressAction.VOICE_HOLD, hydrated.spaceLongPressAction)
        assertEquals(276.dp, KeyboardMetrics.fromSettings(hydrated).frameHeight)

        // Persist updated v3.9.1 fields and reload repository
        repo.updateSettings {
            it.copy(
                keyboardHeightLevel = 1,
                keyboardBottomLiftDp = 44,
                candidateFontScale = CandidateFontScale.LARGE,
                oneHandedMode = OneHandedMode.LEFT,
                t9LeftHandedMirror = true,
                spaceLongPressAction = SpaceLongPressAction.TOGGLE_LANGUAGE
            )
        }
        val reloaded = AppRepository(
            appContext = null,
            publicStore = publicStore,
            secretVault = SecretVault(secretStore, cipher)
        ).settings.value
        assertEquals(1, reloaded.keyboardHeightLevel)
        assertEquals(44, reloaded.keyboardBottomLiftDp)
        assertEquals(CandidateFontScale.LARGE, reloaded.candidateFontScale)
        assertEquals(OneHandedMode.LEFT, reloaded.oneHandedMode)
        assertTrue(reloaded.t9LeftHandedMirror)
        assertEquals(SpaceLongPressAction.TOGGLE_LANGUAGE, reloaded.spaceLongPressAction)
        // 46 (LARGE chrome) + 225 (1.07x 4-row) + 44 (lift) = 315.dp
        assertEquals(315.dp, KeyboardMetrics.fromSettings(reloaded).frameHeight)

        // ApiConfigBackupHelper round-trip and legacy backup migration
        val exportedJson = ApiConfigBackupHelper.exportSettingsJson(reloaded, includeSecrets = false)
        val imported = ApiConfigBackupHelper.parseAndMergeConfigJson(exportedJson, AppSettings()).getOrThrow()
        assertEquals(44, imported.keyboardBottomLiftDp)
        assertEquals(CandidateFontScale.LARGE, imported.candidateFontScale)
        assertEquals(OneHandedMode.LEFT, imported.oneHandedMode)
        assertTrue(imported.t9LeftHandedMirror)
        assertEquals(SpaceLongPressAction.TOGGLE_LANGUAGE, imported.spaceLongPressAction)

        val legacyBackupImported = ApiConfigBackupHelper.parseAndMergeConfigJson(
            """{"keyboardHeightMode": "LIFT", "keyboardLiftLevel": 4}""",
            AppSettings()
        ).getOrThrow()
        assertEquals(32, legacyBackupImported.keyboardBottomLiftDp)
    }

    @Test
    fun `InlineAutofillController scales request specs by CandidateFontScale and guards async inflation sessions`() {
        val fakeAdapter = object : InlineAutofillPlatformAdapter<InlineRequestPlan, List<InlineSuggestionDescriptor>> {
            override fun buildPlatformRequest(plan: InlineRequestPlan): InlineRequestPlan = plan
            override fun extractDescriptors(
                response: List<InlineSuggestionDescriptor>
            ): List<InlineSuggestionDescriptor> = response
        }

        val controller = InlineAutofillController()
        val normalPasswordInputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD

        // Below Android 11 (API 29) or TYPE_NULL -> no request
        assertNull(
            controller.createPlatformRequestWithAdapter(
                adapter = fakeAdapter,
                sdkInt = Build.VERSION_CODES.Q,
                inputType = normalPasswordInputType,
                metrics = KeyboardMetrics.resolve(),
                density = 3.0f,
                screenWidthPx = 1080
            )
        )
        assertNull(
            controller.createPlatformRequestWithAdapter(
                adapter = fakeAdapter,
                sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                inputType = InputType.TYPE_NULL,
                metrics = KeyboardMetrics.resolve(),
                density = 3.0f,
                screenWidthPx = 1080
            )
        )

        // API 30+ chip heights follow CandidateFontScale (29dp / 33dp / 38dp * 3.0x = 87px / 99px / 114px)
        val expectedHeightsAt3x = mapOf(
            CandidateFontScale.STANDARD to 87,
            CandidateFontScale.LARGE to 99,
            CandidateFontScale.EXTRA_LARGE to 114
        )
        for ((scale, expectedPx) in expectedHeightsAt3x) {
            val plan = controller.createPlatformRequestWithAdapter(
                adapter = fakeAdapter,
                sdkInt = Build.VERSION_CODES.R,
                inputType = normalPasswordInputType,
                metrics = KeyboardMetrics.resolve(candidateFontScale = scale),
                density = 3.0f,
                screenWidthPx = 1080,
                isDarkTheme = true
            )
            assertNotNull(plan)
            assertEquals(InlineAutofillController.DEFAULT_MAX_SUGGESTIONS, plan!!.maxSuggestionCount)
            assertEquals(expectedPx, plan.regularChipSpecs.first().heightPx)
            assertEquals(expectedPx, plan.regularChipSpecs.first().minWidthPx)
            assertEquals(778, plan.regularChipSpecs.first().maxWidthPx) // round(1080 * 0.72)
            assertEquals(expectedPx, plan.tooltipSpec.heightPx)
            val wire = plan.styleSpec.toWireMetadataMap()
            assertEquals(
                listOf(JetpackInlineStyleSpec.INLINE_UI_VERSION_V1),
                wire[JetpackInlineStyleSpec.KEY_INLINE_UI_VERSIONS]
            )
        }

        // Out-of-order async inflation + pinned action separation + stale session rejection
        controller.onStartInputSession(sdkInt = Build.VERSION_CODES.R, inputType = normalPasswordInputType)
        assertTrue(controller.state.value.isEligibleEditor)

        val d0 = InlineSuggestionDescriptor(
            index = 0,
            kind = InlineSuggestionKind.SUGGESTION,
            source = InlineSuggestionSource.AUTOFILL,
            isPinned = false,
            autofillHints = listOf("username")
        )
        val d1 = InlineSuggestionDescriptor(
            index = 1,
            kind = InlineSuggestionKind.SUGGESTION,
            source = InlineSuggestionSource.AUTOFILL,
            isPinned = false,
            autofillHints = listOf("password")
        )
        val d2Pinned = InlineSuggestionDescriptor(
            index = 2,
            kind = InlineSuggestionKind.ACTION,
            source = InlineSuggestionSource.AUTOFILL,
            isPinned = true
        )

        val session1 = controller.beginResponseInflation(listOf(d0, d1, d2Pinned))
        assertTrue(controller.state.value.hasPendingInflation)
        assertFalse(controller.state.value.hasVisibleSuggestions)

        // Inflate out-of-order: 2 -> 1 -> 0
        assertTrue(controller.onSuggestionInflated(session1, d2Pinned, widthPx = 99, heightPx = 87))
        assertTrue(controller.state.value.hasPendingInflation)
        assertEquals(1, controller.state.value.pinnedActionChips.size)

        assertTrue(controller.onSuggestionInflated(session1, d1, widthPx = 320, heightPx = 87))
        assertTrue(controller.onSuggestionInflated(session1, d0, widthPx = 280, heightPx = 87))
        assertFalse(controller.state.value.hasPendingInflation)
        assertEquals(listOf(0, 1), controller.state.value.regularChips.map { it.descriptor.index })
        assertEquals(listOf(2), controller.state.value.pinnedActionChips.map { it.descriptor.index })

        // Active preedit hides autofill strip; empty preedit shows it until dismissed
        assertFalse(controller.shouldShowInlineStrip(isComposing = true))
        assertTrue(controller.shouldShowInlineStrip(isComposing = false))
        controller.dismissCurrentSuggestions()
        assertFalse(controller.shouldShowInlineStrip(isComposing = false))

        // Finishing input invalidates session1 and rejects late callbacks
        controller.onFinishInputSession()
        assertFalse(controller.state.value.hasVisibleSuggestions)
        assertFalse(controller.onSuggestionInflated(session1, d0, widthPx = 280, heightPx = 87))
        assertEquals(0, controller.state.value.totalChipCount)
    }

    @Test
    fun `Cross-device screenWidthDp scaling anchors Xiaomi 15 at 375dp, scales Redmi K70 at 432dp, and caps tablets`() {
        val mi15 = KeyboardMetrics.resolve(
            screenWidthDp = 375,
            systemFontScale = 1.0f
        )
        assertEquals(1.0f, mi15.widthScale, 0.0001f)
        assertEquals(210.dp, mi15.keyAreaHeight)
        assertEquals(252.dp, mi15.frameHeight)
        assertEquals(15.5f, mi15.candidateFontSizeSp, 0.001f)
        assertEquals(13.5f, mi15.preeditFontSizeSp, 0.001f)

        val k70 = KeyboardMetrics.resolve(
            screenWidthDp = 432,
            systemFontScale = 1.18f,
            showNumberRow = true
        )
        assertEquals(432f / 375f, k70.widthScale, 0.0001f)
        assertTrue("Redmi K70 5-row key area should scale above fixed 252dp", k70.keyAreaHeight > 252.dp)
        assertTrue("Redmi K70 candidate font should reflect widthScale * dampedSysFont", k70.candidateFontSizeSp > 19.0f)
        assertTrue("Preedit capsule always fits inside 28dp overhang", k70.preeditCapsuleHeight <= 26.dp)
        assertEquals(
            k70.fixedChromeDp.dp + k70.keyAreaHeight + k70.liftHeight,
            k70.frameHeight
        )

        val tablet = KeyboardMetrics.resolve(
            screenWidthDp = 720,
            systemFontScale = 1.30f
        )
        assertEquals(KeyboardMetrics.TABLET_MAX_WIDTH_SCALE, tablet.widthScale, 0.0001f)
        assertEquals(
            tablet.fixedChromeDp.dp + tablet.keyAreaHeight + tablet.liftHeight,
            tablet.frameHeight
        )
    }

    @Test
    fun `ToolPanelAdjustHelper formats delta-only compact subtitles across all 90 combinations without truncation`() {
        assertEquals(
            "默认",
            com.typefree.ime.ime.ToolPanelAdjustHelper.formatCompactSubtitle(
                0,
                0,
                CandidateFontScale.STANDARD,
                com.typefree.ime.i18n.AppLang.ZH_CN
            )
        )
        assertEquals(
            "垫高 56dp",
            com.typefree.ime.ime.ToolPanelAdjustHelper.formatCompactSubtitle(
                0,
                56,
                CandidateFontScale.STANDARD,
                com.typefree.ime.i18n.AppLang.ZH_CN
            )
        )
        assertEquals(
            "偏高 +24 大",
            com.typefree.ime.ime.ToolPanelAdjustHelper.formatCompactSubtitle(
                1,
                24,
                CandidateFontScale.LARGE,
                com.typefree.ime.i18n.AppLang.ZH_CN
            )
        )
        for (lvl in KeyboardMetrics.ALL_LEVELS) {
            for (lift in com.typefree.ime.ime.ToolPanelAdjustHelper.LIFT_PRESET_DPS) {
                for (scale in CandidateFontScale.entries) {
                    val sub = com.typefree.ime.ime.ToolPanelAdjustHelper.formatCompactSubtitle(
                        lvl,
                        lift,
                        scale,
                        com.typefree.ime.i18n.AppLang.ZH_CN
                    )
                    assertTrue("Subtitle '$sub' must stay compact (<= 9 chars)", sub.length <= 9)
                }
            }
        }
    }

    @Test
    fun `resolveDecorOverlapBottomInset preserves OriginOS_MagicOS overlap and removes Samsung One UI double padding`() {
        // 1. vivo OriginOS 7 / MagicOS 11: ComposeView extends to decorView bottom (reserved == 0),
        //    so the full 126px (~48dp) earth/switcher bar inset MUST be preserved.
        val originOs = KeyboardMetrics.resolveDecorOverlapBottomInset(
            rawDecorBottomInsetPx = 126,
            decorHeightPx = 2400,
            inputBottomInWindowPx = 2400,
            inputHeightPx = 880,
            inputLaidOut = true,
            decorBottomOnScreenPx = 2400,
            screenBottomPx = 2400
        )
        assertEquals(0, originOs.reservedBelowInputPx)
        assertEquals(0, originOs.inWindowReservedPx)
        assertEquals(126, originOs.effectiveBottomInsetPx)
        assertEquals(0, KeyboardMetrics.resolveNavBarBackdropHeightPx(originOs, isFullScreenPeekCapturing = false))
        assertEquals(
            126,
            KeyboardMetrics.resolveEffectiveBottomInsetPx(
                composeSystemBarsBottomPx = 0,
                decorEffectiveBottomInsetPx = originOs.effectiveBottomInsetPx,
                reservedBelowInputPx = originOs.reservedBelowInputPx
            )
        )

        // 2. Samsung W25 (One UI 8 / Android 16) same-window nav bar reservation:
        //    decorView spans 2520px, while inputArea ends at 2394px (126px above bottom white nav bar).
        val samsungSameWindow = KeyboardMetrics.resolveDecorOverlapBottomInset(
            rawDecorBottomInsetPx = 126,
            decorHeightPx = 2520,
            inputBottomInWindowPx = 2394,
            inputHeightPx = 880,
            inputLaidOut = true,
            decorBottomOnScreenPx = 2520,
            screenBottomPx = 2520
        )
        assertEquals(126, samsungSameWindow.reservedBelowInputPx)
        assertEquals(126, samsungSameWindow.inWindowReservedPx)
        assertEquals(0, samsungSameWindow.effectiveBottomInsetPx)
        assertEquals(126, KeyboardMetrics.resolveNavBarBackdropHeightPx(samsungSameWindow, isFullScreenPeekCapturing = false))
        assertEquals(0, KeyboardMetrics.resolveNavBarBackdropHeightPx(samsungSameWindow, isFullScreenPeekCapturing = true))
        // Even if Compose's own WindowInsets.systemBars wasn't consumed by parent, reserved 126px is deducted.
        assertEquals(
            0,
            KeyboardMetrics.resolveEffectiveBottomInsetPx(
                composeSystemBarsBottomPx = 126,
                decorEffectiveBottomInsetPx = samsungSameWindow.effectiveBottomInsetPx,
                reservedBelowInputPx = samsungSameWindow.reservedBelowInputPx
            )
        )

        // 3. Whole SoftInputWindow shifted above system nav bar (decorBottomOnScreen = 2394 < screenBottom = 2520)
        val samsungWholeWindowShift = KeyboardMetrics.resolveDecorOverlapBottomInset(
            rawDecorBottomInsetPx = 126,
            decorHeightPx = 880,
            inputBottomInWindowPx = 880,
            inputHeightPx = 880,
            inputLaidOut = true,
            decorBottomOnScreenPx = 2394,
            screenBottomPx = 2520
        )
        assertEquals(126, samsungWholeWindowShift.reservedBelowInputPx)
        assertEquals(0, samsungWholeWindowShift.effectiveBottomInsetPx)

        // 4. Partial reservation (e.g. 42px gesture bar reserved, 110px switcher bar overlaid -> exact 68px delta)
        val partialOverlap = KeyboardMetrics.resolveDecorOverlapBottomInset(
            rawDecorBottomInsetPx = 110,
            decorHeightPx = 2400,
            inputBottomInWindowPx = 2358,
            inputHeightPx = 820,
            inputLaidOut = true,
            decorBottomOnScreenPx = 2400,
            screenBottomPx = 2400
        )
        assertEquals(42, partialOverlap.reservedBelowInputPx)
        assertEquals(68, partialOverlap.effectiveBottomInsetPx)

        // 5. Unsynced WRAP_CONTENT mWindowTop == 0 before WindowManager positions IME window:
        //    must NOT falsely subtract 1520px and wipe out OriginOS 7 protection.
        val unsyncedWrapContent = KeyboardMetrics.resolveDecorOverlapBottomInset(
            rawDecorBottomInsetPx = 126,
            decorHeightPx = 880,
            inputBottomInWindowPx = 880,
            inputHeightPx = 880,
            inputLaidOut = true,
            decorBottomOnScreenPx = 880,
            screenBottomPx = 2400
        )
        assertEquals(0, unsyncedWrapContent.reservedBelowInputPx)
        assertEquals(126, unsyncedWrapContent.effectiveBottomInsetPx)

        // 6. Pre-layout first frame holds previous effective inset clamped to raw (never falsely subtracts)
        val preLayoutCold = KeyboardMetrics.resolveDecorOverlapBottomInset(
            rawDecorBottomInsetPx = 126,
            decorHeightPx = 2400,
            inputBottomInWindowPx = 0,
            inputHeightPx = 0,
            inputLaidOut = false,
            previousEffectiveBottomInsetPx = 0
        )
        assertFalse(preLayoutCold.inputLaidOut)
        assertEquals(0, preLayoutCold.reservedBelowInputPx)
        assertEquals(0, preLayoutCold.effectiveBottomInsetPx)

        val preLayoutWarm = KeyboardMetrics.resolveDecorOverlapBottomInset(
            rawDecorBottomInsetPx = 126,
            decorHeightPx = 2400,
            inputBottomInWindowPx = 0,
            inputHeightPx = 0,
            inputLaidOut = false,
            previousEffectiveBottomInsetPx = 110
        )
        assertEquals(110, preLayoutWarm.effectiveBottomInsetPx)

        // 7. Dynamic shrink / landscape / hidden nav bar drops cleanly back to 0 (no monotonic latch)
        val hiddenNavBar = KeyboardMetrics.resolveDecorOverlapBottomInset(
            rawDecorBottomInsetPx = 0,
            decorHeightPx = 1080,
            inputBottomInWindowPx = 1080,
            inputHeightPx = 520,
            inputLaidOut = true,
            previousEffectiveBottomInsetPx = 126
        )
        assertEquals(0, hiddenNavBar.effectiveBottomInsetPx)
        assertEquals(0, hiddenNavBar.reservedBelowInputPx)
    }
}
