package com.typefree.ime.v391

import android.os.VibrationEffect
import com.typefree.ime.data.AppRepository
import com.typefree.ime.data.AppSettings
import com.typefree.ime.data.ExpressionTab
import com.typefree.ime.data.HapticPreset
import com.typefree.ime.data.InMemoryKeyValueStore
import com.typefree.ime.data.JvmAesGcmCipher
import com.typefree.ime.data.SecretVault
import com.typefree.ime.i18n.AppLang
import com.typefree.ime.ime.HapticFeedbackPlanner
import com.typefree.ime.ime.HapticHardwareCaps
import com.typefree.ime.ime.HapticPulsePlan
import com.typefree.ime.ime.HapticTier
import com.typefree.ime.ime.HapticTouchDeduper
import com.typefree.ime.ime.HardwarePrimitiveKind
import com.typefree.ime.ime.SystemHapticConstantKind
import com.typefree.ime.ime.expression.EmojiCategory
import com.typefree.ime.ime.expression.ExpressionCatalog
import com.typefree.ime.ime.expression.KaomojiCategory
import com.typefree.ime.ime.expression.SymbolCategory
import com.typefree.ime.ui.screens.ApiConfigBackupHelper
import com.typefree.ime.ui.screens.HapticSettingsUiHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HapticAndExpressionPanelTest {

    private fun resolveProjectRoot(): File {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        return if (File(cwd, "app/src/main").exists()) cwd else cwd.parentFile ?: cwd
    }

    private fun newInMemoryRepository(
        publicStore: InMemoryKeyValueStore = InMemoryKeyValueStore(),
        secretStore: InMemoryKeyValueStore = InMemoryKeyValueStore()
    ): AppRepository {
        val vault = SecretVault(secretStore, JvmAesGcmCipher())
        return AppRepository(appContext = null, publicStore = publicStore, secretVault = vault)
    }

    // =========================================================================
    // 1. HapticFeedbackPlanner — 3-Tier Adaptive Waveform & Preset Matrix
    // =========================================================================

    @Test
    fun `HapticFeedbackPlanner resolves OFF and legacy hapticFeedback false to None`() {
        val caps = HapticHardwareCaps(
            sdkInt = 34,
            hasVibrator = true,
            hasAmplitudeControl = true,
            supportsLowTick = true,
            supportsTick = true,
            supportsClick = true
        )
        assertEquals(
            HapticPreset.OFF,
            HapticFeedbackPlanner.resolveEffectivePreset(AppSettings(hapticFeedback = false, hapticPreset = HapticPreset.CRISP))
        )
        assertEquals(
            HapticPreset.OFF,
            HapticFeedbackPlanner.resolveEffectivePreset(AppSettings(hapticFeedback = true, hapticPreset = HapticPreset.OFF))
        )
        assertEquals(
            HapticPreset.SYSTEM,
            HapticFeedbackPlanner.resolveEffectivePreset(AppSettings())
        )
        assertEquals(
            HapticPulsePlan.None,
            HapticFeedbackPlanner.resolvePulsePlan(HapticPreset.OFF, 50, HapticTier.ALPHA_KEY, caps, hasAttachedView = true)
        )
        assertEquals(
            HapticPulsePlan.None,
            HapticFeedbackPlanner.resolvePulsePlan(
                HapticPreset.CRISP,
                50,
                HapticTier.ALPHA_KEY,
                caps.copy(hasVibrator = false),
                hasAttachedView = true
            )
        )
    }

    @Test
    fun `HapticFeedbackPlanner SYSTEM preset maps tiers to native View constants with fallback`() {
        val modernLra = HapticHardwareCaps(
            sdkInt = 34,
            hasVibrator = true,
            hasAmplitudeControl = true,
            supportsLowTick = true,
            supportsTick = true,
            supportsClick = true
        )

        val alphaPlan = HapticFeedbackPlanner.resolvePulsePlan(
            preset = HapticPreset.SYSTEM,
            strengthPercent = 38,
            tier = HapticTier.ALPHA_KEY,
            caps = modernLra,
            hasAttachedView = true
        ) as HapticPulsePlan.SystemViewFeedback
        assertEquals(SystemHapticConstantKind.KEYBOARD_PRESS, alphaPlan.constantKind)
        assertTrue(alphaPlan.fallbackPlan is HapticPulsePlan.PrimitiveComposition)

        val cursorPlan = HapticFeedbackPlanner.resolvePulsePlan(
            preset = HapticPreset.SYSTEM,
            strengthPercent = 38,
            tier = HapticTier.CURSOR_TICK,
            caps = modernLra,
            hasAttachedView = true
        ) as HapticPulsePlan.SystemViewFeedback
        assertEquals(SystemHapticConstantKind.CLOCK_TICK, cursorPlan.constantKind)

        val gesturePlan = HapticFeedbackPlanner.resolvePulsePlan(
            preset = HapticPreset.SYSTEM,
            strengthPercent = 38,
            tier = HapticTier.GESTURE_CONFIRM,
            caps = modernLra,
            hasAttachedView = true
        ) as HapticPulsePlan.SystemViewFeedback
        assertEquals(SystemHapticConstantKind.CONFIRM_LONG_PRESS, gesturePlan.constantKind)

        // When no attached View is available, SYSTEM gracefully downgrades to hardware primitive
        val headlessPlan = HapticFeedbackPlanner.resolvePulsePlan(
            preset = HapticPreset.SYSTEM,
            strengthPercent = 38,
            tier = HapticTier.ALPHA_KEY,
            caps = modernLra,
            hasAttachedView = false
        )
        assertTrue(headlessPlan is HapticPulsePlan.PrimitiveComposition)
    }

    @Test
    fun `HapticFeedbackPlanner Android 12+ LRA selects active-braked primitives with monotonic tier scaling`() {
        val modernLra = HapticHardwareCaps(
            sdkInt = 33,
            hasVibrator = true,
            hasAmplitudeControl = true,
            supportsLowTick = true,
            supportsTick = true,
            supportsClick = true
        )

        val cursor = HapticFeedbackPlanner.resolvePulsePlan(
            HapticPreset.CRISP, 48, HapticTier.CURSOR_TICK, modernLra, true
        ) as HapticPulsePlan.PrimitiveComposition
        val alpha = HapticFeedbackPlanner.resolvePulsePlan(
            HapticPreset.CRISP, 48, HapticTier.ALPHA_KEY, modernLra, true
        ) as HapticPulsePlan.PrimitiveComposition
        val func = HapticFeedbackPlanner.resolvePulsePlan(
            HapticPreset.CRISP, 48, HapticTier.FUNCTION_KEY, modernLra, true
        ) as HapticPulsePlan.PrimitiveComposition
        val gesture = HapticFeedbackPlanner.resolvePulsePlan(
            HapticPreset.CRISP, 48, HapticTier.GESTURE_CONFIRM, modernLra, true
        ) as HapticPulsePlan.PrimitiveComposition

        assertEquals(HardwarePrimitiveKind.TICK, cursor.primitive)
        assertEquals(HardwarePrimitiveKind.LOW_TICK, alpha.primitive)
        assertEquals(HardwarePrimitiveKind.CLICK, func.primitive)
        assertEquals(HardwarePrimitiveKind.CLICK, gesture.primitive)

        assertTrue("cursor scale < alpha scale", cursor.scale < alpha.scale)
        assertTrue("alpha scale < func scale", alpha.scale < func.scale)
        assertTrue("func scale < gesture scale", func.scale < gesture.scale)

        // Preset ordering: SOFT < CRISP < STRONG
        val softAlpha = HapticFeedbackPlanner.resolvePulsePlan(
            HapticPreset.SOFT, 24, HapticTier.ALPHA_KEY, modernLra, true
        ) as HapticPulsePlan.PrimitiveComposition
        val strongAlpha = HapticFeedbackPlanner.resolvePulsePlan(
            HapticPreset.STRONG, 76, HapticTier.ALPHA_KEY, modernLra, true
        ) as HapticPulsePlan.PrimitiveComposition
        assertTrue(softAlpha.scale < alpha.scale)
        assertTrue(alpha.scale < strongAlpha.scale)
    }

    @Test
    fun `HapticFeedbackPlanner legacy devices produce short crisp 4_to_13ms OneShot pulses`() {
        val legacyWithAmp = HapticHardwareCaps(
            sdkInt = 29,
            hasVibrator = true,
            hasAmplitudeControl = true,
            supportsLowTick = false,
            supportsTick = false,
            supportsClick = false
        )
        for (tier in HapticTier.entries) {
            val plan = HapticFeedbackPlanner.resolvePulsePlan(
                HapticPreset.CRISP, 48, tier, legacyWithAmp, true
            ) as HapticPulsePlan.ShortOneShot
            assertTrue("duration ${plan.durationMs}ms out of 4..13ms for $tier", plan.durationMs in 4L..13L)
            assertTrue("amplitude ${plan.amplitude} out of 28..255", plan.amplitude in 28..255)
        }

        val legacyNoAmp = legacyWithAmp.copy(hasAmplitudeControl = false)
        val fallback = HapticFeedbackPlanner.resolvePulsePlan(
            HapticPreset.CRISP, 48, HapticTier.ALPHA_KEY, legacyNoAmp, true
        ) as HapticPulsePlan.ShortOneShot
        assertEquals(VibrationEffect.DEFAULT_AMPLITUDE, fallback.amplitude)

        assertEquals(0.05f, HapticFeedbackPlanner.resolveSoundVolumeFloat(0), 0.0001f)
        assertEquals(0.50f, HapticFeedbackPlanner.resolveSoundVolumeFloat(50), 0.0001f)
        assertEquals(1.00f, HapticFeedbackPlanner.resolveSoundVolumeFloat(140), 0.0001f)
    }

    // =========================================================================
    // 2. HapticTouchDeduper — Multi-Touch Rapid Typing & TTL Expiry
    // =========================================================================

    @Test
    fun `HapticTouchDeduper swallows matching UP acks cleanly across two-thumb overlap and expires stale tokens`() {
        var fakeNow = 1000L
        val deduper = HapticTouchDeduper(clockMs = { fakeNow }, ackTtlMs = 450L)

        // Programmatic call without prior touch-down is NOT swallowed
        assertFalse(deduper.consumeMatchingUpAck())

        // Rapid two-thumb overlap: Finger A down, Finger B down, Finger A up, Finger B up
        deduper.recordTouchDownFeedback()
        fakeNow += 35L
        deduper.recordTouchDownFeedback()
        fakeNow += 20L
        assertTrue(deduper.consumeMatchingUpAck())
        fakeNow += 25L
        assertTrue(deduper.consumeMatchingUpAck())
        // Queue is now empty: next programmatic call passes through
        assertFalse(deduper.consumeMatchingUpAck())

        // Stale token beyond TTL expires cleanly
        deduper.recordTouchDownFeedback()
        fakeNow += 500L
        assertFalse(deduper.consumeMatchingUpAck())
    }

    // =========================================================================
    // 3. ExpressionCatalog — Emoji, Kaomoji, Symbols, Recents & Skin Tones
    // =========================================================================

    @Test
    fun `ExpressionCatalog populates all categories and supports recent FIFO, skin-tone overrides, mood search, and tofu filtering`() {
        for (cat in EmojiCategory.entries) {
            if (cat != EmojiCategory.RECENT) {
                assertTrue("Emoji category $cat must not be empty", ExpressionCatalog.EMOJIS_BY_CATEGORY[cat].orEmpty().isNotEmpty())
            }
        }
        for (cat in KaomojiCategory.entries) {
            assertTrue("Kaomoji category $cat must not be empty", ExpressionCatalog.KAOMOJI_BY_CATEGORY[cat].orEmpty().isNotEmpty())
        }
        for (cat in SymbolCategory.entries) {
            assertTrue("Symbol category $cat must not be empty", ExpressionCatalog.SYMBOLS_BY_CATEGORY[cat].orEmpty().isNotEmpty())
        }

        // Default fallback recents when empty
        val fallbackRecents = ExpressionCatalog.resolveRecentEmojis(emptyList())
        assertTrue(fallbackRecents.size >= 12)

        // FIFO recent recording with dedupe & cap at 24
        var recents = emptyList<String>()
        for (i in 1..30) {
            recents = ExpressionCatalog.recordRecentEmoji(recents, "E$i")
        }
        assertEquals(ExpressionCatalog.MAX_RECENT_EMOJIS, recents.size)
        assertEquals("E30", recents.first())
        val bumped = ExpressionCatalog.recordRecentEmoji(recents, "E20")
        assertEquals("E20", bumped.first())
        assertEquals(ExpressionCatalog.MAX_RECENT_EMOJIS, bumped.size)

        // Skin tone resolution
        val thumbsUp = ExpressionCatalog.ALL_SEARCHABLE_EMOJIS.first { it.glyph == "👍" }
        assertTrue(thumbsUp.skinVariants.size >= 5)
        assertEquals("👍", ExpressionCatalog.resolveDisplayGlyph(thumbsUp, emptyMap()))
        assertEquals("👍🏽", ExpressionCatalog.resolveDisplayGlyph(thumbsUp, mapOf("👍" to "👍🏽")))
        assertEquals("👍", ExpressionCatalog.resolveDisplayGlyph(thumbsUp, mapOf("👍" to "INVALID")))

        // Mood / keyword search (Chinese + English)
        val laughHits = ExpressionCatalog.searchEmojis("笑")
        assertTrue(laughHits.any { it.glyph == "😂" })
        val thumbsHits = ExpressionCatalog.searchEmojis("thumbs")
        assertTrue(thumbsHits.any { it.glyph == "👍" })

        // Tofu filter strips unsupported glyphs
        val filtered = ExpressionCatalog.filterSupportedEmojis(laughHits) { glyph -> glyph != "😂" }
        assertFalse(filtered.any { it.glyph == "😂" })

        // Paired symbols have explicit open/close markers
        val commonCn = ExpressionCatalog.SYMBOLS_BY_CATEGORY[SymbolCategory.COMMON_CN].orEmpty()
        val bookQuotes = commonCn.firstOrNull { it.display == "《》" }
        assertNotNull(bookQuotes)
        assertTrue(bookQuotes!!.isPaired)
        assertEquals("《", bookQuotes.pairedOpen)
        assertEquals("》", bookQuotes.pairedClose)
    }

    // =========================================================================
    // 4. AppRepository Migration, Recent Emoji Persistence & Backup Round-Trip
    // =========================================================================

    @Test
    fun `AppRepository migrates legacy hapticFeedback bool and persists new haptic and emoji fields`() {
        val legacyOffStore = InMemoryKeyValueStore()
        legacyOffStore.putString(AppRepository.KEY_SETTINGS, """{"hapticFeedback":false,"interactionSounds":true}""")
        val repoOff = newInMemoryRepository(publicStore = legacyOffStore)
        assertEquals(HapticPreset.OFF, repoOff.settings.value.hapticPreset)
        assertFalse(repoOff.settings.value.hapticFeedback)

        val legacyOnStore = InMemoryKeyValueStore()
        legacyOnStore.putString(AppRepository.KEY_SETTINGS, """{"hapticFeedback":true,"interactionSounds":false}""")
        val repoOn = newInMemoryRepository(publicStore = legacyOnStore)
        assertEquals(HapticPreset.SYSTEM, repoOn.settings.value.hapticPreset)
        assertEquals(38, repoOn.settings.value.hapticStrength)
        assertEquals(50, repoOn.settings.value.keySoundVolume)

        // Update settings + record recent emoji with skin-tone override and verify persistence across reload
        repoOn.updateSettings {
            it.copy(
                hapticPreset = HapticPreset.CRISP,
                hapticStrength = 62,
                keySoundVolume = 75
            )
        }
        repoOn.recordRecentEmoji(glyph = "👍🏽", baseGlyph = "👍")
        repoOn.recordRecentEmoji(glyph = "🔥", baseGlyph = null)

        val reloaded = newInMemoryRepository(publicStore = legacyOnStore)
        assertEquals(HapticPreset.CRISP, reloaded.settings.value.hapticPreset)
        assertEquals(62, reloaded.settings.value.hapticStrength)
        assertEquals(75, reloaded.settings.value.keySoundVolume)
        assertEquals(listOf("🔥", "👍🏽"), reloaded.settings.value.recentEmojis)
        assertEquals("👍🏽", reloaded.settings.value.emojiSkinTonePrefs["👍"])

        // Selecting default yellow base glyph clears the skin-tone override
        reloaded.recordRecentEmoji(glyph = "👍", baseGlyph = "👍")
        assertFalse(reloaded.settings.value.emojiSkinTonePrefs.containsKey("👍"))
        assertEquals("👍", reloaded.settings.value.recentEmojis.first())
    }

    @Test
    fun `ApiConfigBackupHelper round-trips haptic tuning and emoji preferences`() {
        val original = AppSettings(
            hapticFeedback = true,
            hapticPreset = HapticPreset.CUSTOM,
            hapticStrength = 72,
            interactionSounds = true,
            keySoundVolume = 85,
            recentEmojis = listOf("😂", "👍🏽", "🔥"),
            emojiSkinTonePrefs = mapOf("👍" to "👍🏽")
        )
        val exported = ApiConfigBackupHelper.exportSettingsJson(original, includeSecrets = false)
        val merged = ApiConfigBackupHelper.parseAndMergeConfigJson(exported, AppSettings()).getOrThrow()

        assertEquals(HapticPreset.CUSTOM, merged.hapticPreset)
        assertEquals(72, merged.hapticStrength)
        assertEquals(85, merged.keySoundVolume)
        assertEquals(listOf("😂", "👍🏽", "🔥"), merged.recentEmojis)
        assertEquals(mapOf("👍" to "👍🏽"), merged.emojiSkinTonePrefs)
    }

    // =========================================================================
    // 5. Default-Collapsed Settings UI & Architecture Source Guards
    // =========================================================================

    @Test
    fun `HapticSettingsUiHelper defaults collapsed and formats trilingual summaries accurately`() {
        assertFalse(
            "Haptic & sound fine-tuning section MUST be collapsed by default",
            HapticSettingsUiHelper.DEFAULT_EXPANDED
        )
        assertEquals(
            listOf(HapticPreset.OFF, HapticPreset.SYSTEM, HapticPreset.SOFT, HapticPreset.CRISP, HapticPreset.STRONG),
            HapticSettingsUiHelper.SELECTABLE_PRESETS
        )

        val defaultSummaryZh = HapticSettingsUiHelper.formatCollapsedSummary(AppSettings(), AppLang.ZH_CN)
        assertEquals("跟随系统 · 音效 50%", defaultSummaryZh)

        val customMutedEn = HapticSettingsUiHelper.formatCollapsedSummary(
            AppSettings(hapticPreset = HapticPreset.CUSTOM, hapticStrength = 64, interactionSounds = false),
            AppLang.EN
        )
        assertEquals("Custom 64% · Muted", customMutedEn)

        val offSummaryTw = HapticSettingsUiHelper.formatCollapsedSummary(
            AppSettings(hapticFeedback = false, interactionSounds = true, keySoundVolume = 30),
            AppLang.ZH_TW
        )
        assertEquals("關閉 · 音效 30%", offSummaryTw)
    }

    @Test
    fun `Architecture guards hold for KeyboardScreen line budget, default-collapsed settings, and 123 swipe-up entry`() {
        val root = resolveProjectRoot()

        val keyboardScreenFile = File(root, "app/src/main/java/com/typefree/ime/ime/KeyboardScreen.kt")
        val keyboardScreenLines = keyboardScreenFile.readLines()
        assertTrue(
            "KeyboardScreen.kt must remain <= 650 lines (actual: ${keyboardScreenLines.size})",
            keyboardScreenLines.size <= 650
        )
        val keyboardScreenSrc = keyboardScreenLines.joinToString("\n")
        assertTrue(keyboardScreenSrc.contains("ExpressionSymbolPanelHost"))
        assertTrue(keyboardScreenSrc.contains("uiState.isExpressionPanelOpen"))

        val generalSettingsSrc = File(root, "app/src/main/java/com/typefree/ime/ui/screens/GeneralSettingsScreen.kt").readText()
        assertTrue(generalSettingsSrc.contains("HapticSettingsUiHelper.DEFAULT_EXPANDED"))
        assertTrue(generalSettingsSrc.contains("AnimatedVisibility(visible = showHapticTuning)"))

        val qwertySrc = File(root, "app/src/main/java/com/typefree/ime/ime/BuiltInQwertyKeyboard.kt").readText()
        assertTrue(qwertySrc.contains("QwertySymbolEmojiKey"))
        assertTrue(qwertySrc.contains("LocalKeyFeedbackTrigger.current"))
        assertTrue(qwertySrc.contains("ExpressionTab.EMOJI"))

        val t9Src = File(root, "app/src/main/java/com/typefree/ime/ime/BuiltInT9Keyboard.kt").readText()
        assertTrue(t9Src.contains("T9SymbolEmojiKey"))
        assertTrue(t9Src.contains("LocalKeyFeedbackTrigger.current"))
        assertTrue(t9Src.contains("ExpressionTab.SYMBOL"))
    }
}
