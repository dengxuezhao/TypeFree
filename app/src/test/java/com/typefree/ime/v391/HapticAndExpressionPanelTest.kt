package com.typefree.ime.v391

import android.os.VibrationEffect
import android.text.InputType
import com.typefree.ime.data.AppRepository
import com.typefree.ime.data.AppSettings
import com.typefree.ime.data.ExpressionTab
import com.typefree.ime.data.HapticPreset
import com.typefree.ime.data.HistoryRetention
import com.typefree.ime.data.InMemoryKeyValueStore
import com.typefree.ime.data.InputScheme
import com.typefree.ime.data.JvmAesGcmCipher
import com.typefree.ime.data.SecretVault
import com.typefree.ime.i18n.AppLang
import com.typefree.ime.ime.HapticFeedbackPlanner
import com.typefree.ime.ime.HapticHardwareCaps
import com.typefree.ime.ime.HapticPulsePlan
import com.typefree.ime.ime.HapticTier
import com.typefree.ime.ime.HapticTouchDeduper
import com.typefree.ime.ime.HardwarePrimitiveKind
import com.typefree.ime.ime.ImeHostCallbacks
import com.typefree.ime.ime.ImeLogic
import com.typefree.ime.ime.ImeUiState
import com.typefree.ime.ime.KeyboardTabMode
import com.typefree.ime.ime.SystemHapticConstantKind
import com.typefree.ime.ime.TypingController
import com.typefree.ime.ime.UndoSnapshot
import com.typefree.ime.ime.engine.EngineResult
import com.typefree.ime.ime.engine.InputEngine
import com.typefree.ime.ime.engine.EngineState
import com.typefree.ime.ime.engine.KeyInput
import com.typefree.ime.ime.expression.EmojiCategory
import com.typefree.ime.ime.expression.EmojiSuggestionEngine
import com.typefree.ime.ime.expression.ExpressionCatalog
import com.typefree.ime.ime.expression.KaomojiCategory
import com.typefree.ime.ime.expression.SymbolCategory
import com.typefree.ime.ime.flypy.FlypyCandidate
import com.typefree.ime.network.AiPipelineEngine
import com.typefree.ime.ui.screens.ApiConfigBackupHelper
import com.typefree.ime.ui.screens.HapticSettingsUiHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileInputStream

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

        assertEquals(0.00f, HapticFeedbackPlanner.resolveSoundVolumeFloat(-10), 0.0001f)
        assertEquals(0.00f, HapticFeedbackPlanner.resolveSoundVolumeFloat(0), 0.0001f)
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

        val zeroVolSummaryZh = HapticSettingsUiHelper.formatCollapsedSummary(
            AppSettings(hapticPreset = HapticPreset.CRISP, interactionSounds = true, keySoundVolume = 0),
            AppLang.ZH_CN
        )
        assertEquals("清脆 · 静音", zeroVolSummaryZh)
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

    // =========================================================================
    // 6. Expanded Emoji Catalog & Pinyin / Double-Pinyin Emoji Suggestion Engine
    // =========================================================================

    @Test
    fun `Expanded ExpressionCatalog covers 800+ unique mainstream emojis with valid ZWJ skin tones`() {
        val all = ExpressionCatalog.ALL_SEARCHABLE_EMOJIS
        assertTrue("Expected >= 800 curated emojis, actual=${all.size}", all.size >= 800)
        assertEquals("Catalog must contain zero duplicate base glyphs", all.size, all.map { it.glyph }.distinct().size)

        // Mainstream additions (facepalm, shrug, bow, burning heart, flags, travel)
        assertTrue(ExpressionCatalog.searchEmojis("捂脸").any { it.glyph == "🤦" })
        assertTrue(ExpressionCatalog.searchEmojis("摊手").any { it.glyph == "🤷" })
        assertTrue(ExpressionCatalog.searchEmojis("中国").any { it.glyph == "🇨🇳" })
        assertTrue(ExpressionCatalog.EMOJIS_BY_CATEGORY[EmojiCategory.TRAVEL_PLACES].orEmpty().any { it.glyph == "✈️" })

        // ZWJ skin tone modifier MUST sit before U+200D (e.g. 🧑🏽‍💻, never 🧑‍💻🏽)
        val coder = all.first { it.glyph == "🧑‍💻" }
        assertEquals(6, coder.skinVariants.size)
        assertTrue(
            "ZWJ skin variant must place tone modifier before ZWJ",
            coder.skinVariants.contains("🧑🏽\u200D💻")
        )
        assertEquals("🧑‍💻", ExpressionCatalog.resolveBaseGlyph("🧑🏽\u200D💻"))

        // Non-Emoji_Modifier_Base / Android 12L+ multi-person glyphs MUST NOT carry broken single skinVariants
        for (forbidden in listOf("👯", "💑", "🤝")) {
            val item = all.first { it.glyph == forbidden }
            assertTrue("$forbidden must not have single skinVariants", item.skinVariants.isEmpty())
        }
    }

    @Test
    fun `EmojiSuggestionEngine loads bundled CLDR Chinese lexicon and matches composing and post-commit accurately`() {
        val root = resolveProjectRoot()
        val lexiconFile = File(root, "app/src/main/assets/${EmojiSuggestionEngine.BUNDLED_LEXICON_ASSET_PATH}")
        assertTrue("Bundled emoji lexicon asset must exist", lexiconFile.isFile)

        // Cold start before IO load completes returns empty cleanly without touching ExpressionCatalog
        val unloaded = EmojiSuggestionEngine()
        assertTrue(unloaded.suggestForComposing(listOf(FlypyCandidate("哈哈"))).isEmpty())
        assertTrue(unloaded.suggestForCommittedText("哈哈").isEmpty())

        val engine = EmojiSuggestionEngine()
        val loaded = FileInputStream(lexiconFile).use { engine.loadFromTsvStream(it) }
        assertTrue("Expected >= 7000 SC+TC keywords loaded, actual=$loaded", loaded >= 7000)
        assertTrue("Expected >= 350 Tier-1 suffix-eligible phrases, actual=${engine.suffixWhitelistCount}", engine.suffixWhitelistCount >= 350)

        // Composing: exact match on short candidates works (Simplified & Traditional)
        val laughComposing = engine.suggestForComposing(
            candidates = listOf(FlypyCandidate("哈哈"), FlypyCandidate("哈")),
            preedit = "ha ha"
        )
        assertTrue(laughComposing.contains("😂"))
        assertTrue(engine.suggestForComposing(listOf(FlypyCandidate("捂脸")), preedit = "wu lian").contains("🤦"))
        assertTrue(engine.suggestForComposing(listOf(FlypyCandidate("狗头")), preedit = "gou tou").contains("🐶"))
        assertTrue(engine.suggestForComposing(listOf(FlypyCandidate("牛逼")), preedit = "niu bi").contains("👍"))
        assertTrue(engine.suggestForComposing(listOf(FlypyCandidate("謝謝")), preedit = "xie xie").contains("🙏"))
        assertTrue(engine.suggestForComposing(listOf(FlypyCandidate("開心")), preedit = "kai xin").contains("😄"))

        // Rime multi-step selection guard: uncommitted CJK (incl. Ext-B) or selected English prefix suppresses composing emojis
        assertTrue(EmojiSuggestionEngine.hasUncommittedSelectedPrefix("今天 ha ha", "jintianhaha"))
        assertTrue(EmojiSuggestionEngine.hasUncommittedSelectedPrefix("𠀀 ha ha", "haha"))
        assertTrue(EmojiSuggestionEngine.hasUncommittedSelectedPrefix("AI kai xin", "aikaixin"))
        assertFalse(EmojiSuggestionEngine.hasUncommittedSelectedPrefix("ha ha", "haha"))
        assertTrue(
            "Must suppress composing emoji when preedit holds uncommitted Chinese prefix",
            engine.suggestForComposing(
                candidates = listOf(FlypyCandidate("哈哈")),
                preedit = "今天 ha ha",
                rawCode = "jintianhaha"
            ).isEmpty()
        )
        assertTrue(
            "Must suppress composing emoji when preedit holds already-selected English prefix",
            engine.suggestForComposing(
                candidates = listOf(FlypyCandidate("开心")),
                preedit = "AI kai xin",
                rawCode = "aikaixin"
            ).isEmpty()
        )

        // Composing: long sentences & grammar stopwords MUST NOT trigger replacement emojis
        assertTrue(engine.suggestForComposing(listOf(FlypyCandidate("今天天气真开心"))).isEmpty())
        assertTrue(engine.suggestForComposing(listOf(FlypyCandidate("我们"))).isEmpty())
        assertTrue(engine.suggestForComposing(listOf(FlypyCandidate("什么"))).isEmpty())

        // Post-commit: exact match (SC + TC), Tier-1 modal-particle stripping, and Tier-1 suffix match
        assertTrue(engine.suggestForCommittedText("谢谢").contains("🙏"))
        assertTrue(engine.suggestForCommittedText("謝謝").contains("🙏"))
        assertTrue(engine.suggestForCommittedText("笑死了").any { it == "🤣" || it == "😂" })
        assertTrue(engine.suggestForCommittedText("收到啦").any { it == "🫡" || it == "👌" })
        assertTrue(engine.suggestForCommittedText("今天太开心了").any { it == "😄" || it == "😊" })
        assertTrue(engine.suggestForCommittedText("今天太開心了").any { it == "😄" || it == "😊" })
        assertTrue(engine.suggestForCommittedText("祝你生日快乐！").contains("🎂"))
        assertTrue(engine.suggestForCommittedText("祝你生日快樂！").contains("🎂"))
        // Broad CLDR Tier-2 words (like "不知道") work on exact match ONLY, never noisy suffix/modal-stripped sentences
        assertTrue(engine.suggestForCommittedText("不知道").contains("🤷"))
        assertTrue("Broad CLDR tag must NOT trigger via modal-stripped sentence", engine.suggestForCommittedText("我不知道呀").isEmpty())
        assertTrue("Must not strip 鸭/哈 from ordinary words", engine.suggestForCommittedText("烤鸭").isEmpty())
        // Exact negated CLDR entry ("不开心") suggests sad face (never smiley), while negated suffixes return empty
        val unhappyHits = engine.suggestForCommittedText("不开心")
        assertTrue("不开心 exact match should suggest sad face", unhappyHits.contains("🙁"))
        assertFalse("不开心 must not suggest smiley", unhappyHits.any { it == "😄" || it == "😊" })
        assertTrue("不太开心 must not suggest smiley", engine.suggestForCommittedText("不太开心").isEmpty())
        assertTrue("没开心 must not suggest smiley", engine.suggestForCommittedText("没开心").isEmpty())
        assertTrue("别生气 must not suggest angry suffix", engine.suggestForCommittedText("别生气").isEmpty())
        // Mid-clause comma should not interrupt with emoji bar
        assertTrue(engine.suggestForCommittedText("哈哈，").isEmpty())

        // Respects user skin-tone prefs and falls back to base raw glyph when skin-tone glyph is unsupported
        val skinResolved = engine.suggestForCommittedText(
            committedText = "点赞",
            skinTonePrefs = mapOf("👍" to "👍🏽")
        )
        assertEquals("👍🏽", skinResolved.first())

        val skinFallbackToRaw = engine.suggestForCommittedText(
            committedText = "点赞",
            skinTonePrefs = mapOf("👍" to "👍🏽"),
            isGlyphSupported = { it != "👍🏽" }
        )
        assertEquals("👍", skinFallbackToRaw.first())

        val tofuFiltered = engine.suggestForCommittedText(
            committedText = "哈哈",
            isGlyphSupported = { it != "😂" }
        )
        assertFalse(tofuFiltered.contains("😂"))
        assertTrue(tofuFiltered.contains("🤣"))
    }

    @Test
    fun `TypingController enables emoji suggestions ONLY on Pinyin and Double Pinyin, never on codetable or custom schemes`() {
        assertTrue(InputScheme.RIME_PINYIN.supportsEmojiSuggestions)
        assertTrue(InputScheme.RIME_T9_PINYIN.supportsEmojiSuggestions)
        assertTrue(InputScheme.RIME_DOUBLE_PINYIN_FLYPY.supportsEmojiSuggestions)
        assertFalse(InputScheme.FLYPY_TABLE.supportsEmojiSuggestions)
        assertFalse(InputScheme.RIME_CUSTOM.supportsEmojiSuggestions)

        val root = resolveProjectRoot()
        val lexiconFile = File(root, "app/src/main/assets/${EmojiSuggestionEngine.BUNDLED_LEXICON_ASSET_PATH}")
        val emojiEngine = EmojiSuggestionEngine().apply {
            FileInputStream(lexiconFile).use { loadFromTsvStream(it) }
        }

        val committedTexts = mutableListOf<String>()
        val repo = newInMemoryRepository()
        val stateFlow = MutableStateFlow(
            ImeUiState(
                tabMode = KeyboardTabMode.QWERTY,
                isFlypyChineseMode = true,
                activeInputScheme = InputScheme.RIME_PINYIN,
                currentInputType = InputType.TYPE_CLASS_TEXT
            )
        )
        var currentSettings = AppSettings(inputScheme = InputScheme.RIME_PINYIN, predictionEnabled = false)

        val fakeRimeState = MutableStateFlow(EngineState())
        val fakeRimeEngine = object : InputEngine {
            override val state: StateFlow<EngineState> = fakeRimeState
            override fun onKey(key: KeyInput): EngineResult = when (key) {
                is KeyInput.CharKey -> {
                    fakeRimeState.value = EngineState(
                        rawCode = "haha",
                        preedit = "ha ha",
                        candidates = listOf(FlypyCandidate("哈哈"), FlypyCandidate("哈")),
                        isComposing = true
                    )
                    EngineResult.Consumed
                }
                is KeyInput.Space -> {
                    fakeRimeState.value = EngineState()
                    EngineResult.Commit(text = "今天太开心了", remainComposing = false)
                }
                else -> EngineResult.PassThrough
            }
            override fun select(index: Int): EngineResult = EngineResult.PassThrough
            override fun flush(): String = ""
            override fun clear() {
                fakeRimeState.value = EngineState()
            }
        }

        val fakeHost = object : ImeHostCallbacks {
            override val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            override val repository: AppRepository = repo
            override val aiEngine: AiPipelineEngine = AiPipelineEngine()
            override val stateFlow: MutableStateFlow<ImeUiState> = stateFlow
            override fun currentInputConnection() = null
            override fun performKeyFeedback(strong: Boolean) {}
            override fun performHaptic(strong: Boolean) {}
            override fun showQuickActionToast(message: String) {}
            override fun commitDirectText(text: String, recordUndo: Boolean, markManualEdit: Boolean, playFeedback: Boolean) {
                committedTexts.add(text)
            }
            override fun pushUndoSnapshot(snapshot: UndoSnapshot?) {}
            override fun peekTopUndoSnapshot(): UndoSnapshot? = null
            override fun recordManualEditTouch() {}
            override fun tryAutoLearnFromRecentEdit() {}
            override fun onDictationCommittedForAutoLearn(fullBufferAfterCommit: String) {}
            override fun clearAutoLearnBaseline() {}
            override fun readSelectedTextSafely(): String = ""
            override fun readSurroundingBeforeCursor(maxChars: Int): String = ""
            override fun readFullEditorTextSafely(): String = ""
            override fun readSystemClipboardText(): String = ""
            override fun syncSystemClipboardIfNeeded(allowFreshBanner: Boolean) {}
            override fun archiveWavForHistory(wavFile: File, retention: HistoryRetention): File = wavFile
            override fun getLastRecordingWavFile(): File = File("dummy.wav")
            override fun hasRetryWavFile(): Boolean = false
        }

        val controller = TypingController(
            host = fakeHost,
            predictionIdleDismissMs = 0L,
            settingsOverride = { currentSettings },
            emojiSuggestionEngine = emojiEngine,
            emojiGlyphSupportOverride = { true }
        ).apply {
            rimeInputEngine = fakeRimeEngine
            isRimeReady = true
        }

        // 1. Pinyin composing shows emoji suggestions; tapping clears composing & commits emoji
        controller.commitQwertyChar("h")
        assertTrue(stateFlow.value.composingEmojiSuggestions.contains("😂"))
        controller.selectComposingEmoji("😂")
        assertEquals(listOf("😂"), committedTexts)
        assertFalse(stateFlow.value.isFlypyComposing)
        assertTrue(stateFlow.value.composingEmojiSuggestions.isEmpty())
        assertEquals("😂", repo.settings.value.recentEmojis.first())

        // 2. Double Pinyin post-commit shows emoji suggestions even when text prediction is off,
        //    and multi-tap keeps post-commit emojis alive until dismissed or next typing
        currentSettings = AppSettings(inputScheme = InputScheme.RIME_DOUBLE_PINYIN_FLYPY, predictionEnabled = false)
        controller.commitQwertyChar("h")
        controller.insertSpace()
        assertEquals("今天太开心了", committedTexts.last())
        assertTrue(stateFlow.value.postCommitEmojiSuggestions.any { it == "😄" || it == "😊" })
        assertTrue("Prediction bar must become visible for post-commit emojis", stateFlow.value.isPredictionBarVisible)
        controller.selectPostCommitEmoji("😄")
        assertEquals("😄", committedTexts.last())
        assertTrue("Multi-tap must keep post-commit emoji bar open", stateFlow.value.postCommitEmojiSuggestions.isNotEmpty())
        controller.selectPostCommitEmoji("😄")
        assertEquals(listOf("😂", "今天太开心了", "😄", "😄"), committedTexts)
        controller.dismissPredictions()
        assertTrue(stateFlow.value.postCommitEmojiSuggestions.isEmpty())

        // 2b. TYPE_NULL / URI / Email / Filter editors MUST suppress emoji suggestions even in Pinyin mode
        assertTrue(ImeLogic.isEmojiSuggestionBlockedEditor(InputType.TYPE_NULL))
        assertTrue(ImeLogic.isEmojiSuggestionBlockedEditor(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI))
        assertTrue(ImeLogic.isEmojiSuggestionBlockedEditor(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS))
        assertTrue(ImeLogic.isEmojiSuggestionBlockedEditor(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_FILTER))
        stateFlow.value = stateFlow.value.copy(
            currentInputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        )
        controller.commitQwertyChar("h")
        assertTrue(stateFlow.value.composingEmojiSuggestions.isEmpty())
        controller.insertSpace()
        assertTrue(stateFlow.value.postCommitEmojiSuggestions.isEmpty())
        stateFlow.value = stateFlow.value.copy(currentInputType = InputType.TYPE_CLASS_TEXT)

        // 3. Shape-based codetable (小鹤音形) and Custom Rime MUST NOT emit composing or post-commit emojis
        currentSettings = AppSettings(inputScheme = InputScheme.RIME_CUSTOM, predictionEnabled = false)
        controller.commitQwertyChar("h")
        assertTrue(stateFlow.value.composingEmojiSuggestions.isEmpty())
        controller.insertSpace()
        assertTrue(stateFlow.value.postCommitEmojiSuggestions.isEmpty())

        currentSettings = AppSettings(inputScheme = InputScheme.FLYPY_TABLE, predictionEnabled = false)
        controller.syncEngineStateToUi()
        assertTrue(stateFlow.value.composingEmojiSuggestions.isEmpty())
    }
}
