package com.typefree.ime.v362

import com.typefree.core.ime.profile.SchemaArchetype
import com.typefree.ime.data.AppRepository
import com.typefree.ime.data.AppSettings
import com.typefree.ime.data.DefaultPresets
import com.typefree.ime.data.InMemoryKeyValueStore
import com.typefree.ime.data.InputScheme
import com.typefree.ime.data.JvmAesGcmCipher
import com.typefree.ime.data.KeycapBadgeMode
import com.typefree.ime.data.SecretVault
import com.typefree.ime.data.SnippetMatcher
import com.typefree.ime.ui.AppDestinations
import com.typefree.ime.ui.screens.KeyGestureActionKind
import com.typefree.ime.ui.screens.KeyGestureBindingSpec
import com.typefree.ime.ui.screens.KeyGestureConfigCodec
import com.typefree.ime.ui.screens.SchemeIsolationKind
import com.typefree.ime.ui.screens.XiaohePhraseCodeHelper
import com.typefree.ime.ui.screens.resolveSchemeIsolationKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class V362SettingsAndNavTest {

    @Test
    fun `AppSettings defaults match v362 spec and AppRepository migrates legacy JSON safely`() {
        val defaults = AppSettings()
        assertFalse(defaults.englishPrefixVetoEnabled)
        assertFalse(defaults.codetableAutoEncodeEnabled)
        assertFalse(defaults.codetableFiveKeyBottomRow)
        assertTrue(defaults.xiaoheDirectCommandsEnabled)
        assertTrue(defaults.xiaoheReverseSplitEnabled)
        assertTrue(defaults.xiaoheNaturalContinuationEnabled)
        assertTrue(defaults.passiveCalculatorEnabled)
        assertTrue(defaults.englishModeWordSuggestionEnabled)
        assertTrue(defaults.phraseSuggestionL2Enabled)
        assertEquals("", defaults.customKeyGesturesJson)
        assertEquals(KeycapBadgeMode.SWIPE_UP, defaults.keycapBadgeMode)

        val publicStore = InMemoryKeyValueStore()
        val secretStore = InMemoryKeyValueStore()
        val cipher = JvmAesGcmCipher()

        publicStore.putString(
            AppRepository.KEY_SETTINGS,
            """
            {
              "inputScheme": "FLYPY_TABLE",
              "whisperModeEnabled": true,
              "snippets": [
                {"id": "s1", "title": "收货", "voiceTriggers": "收货", "flypyCode": ";sd", "content": "上海市", "enabled": true}
              ]
            }
            """.trimIndent()
        )

        val repo = AppRepository(
            appContext = null,
            publicStore = publicStore,
            secretVault = SecretVault(secretStore, cipher)
        )
        val loaded = repo.settings.value
        assertFalse(loaded.englishPrefixVetoEnabled)
        assertFalse(loaded.codetableAutoEncodeEnabled)
        assertFalse(loaded.codetableFiveKeyBottomRow)
        assertTrue(loaded.xiaoheDirectCommandsEnabled)
        assertTrue(loaded.xiaoheReverseSplitEnabled)
        assertTrue(loaded.xiaoheNaturalContinuationEnabled)
        assertTrue(loaded.passiveCalculatorEnabled)
        assertTrue(loaded.englishModeWordSuggestionEnabled)
        assertTrue(loaded.phraseSuggestionL2Enabled)
        assertEquals("", loaded.customKeyGesturesJson)
        assertEquals(KeycapBadgeMode.SWIPE_UP, loaded.keycapBadgeMode)
        assertEquals("/sd", loaded.snippets.first().flypyCode)

        repo.updateSettings {
            it.copy(
                codetableFiveKeyBottomRow = true,
                xiaoheDirectCommandsEnabled = false,
                xiaoheReverseSplitEnabled = false,
                xiaoheNaturalContinuationEnabled = false,
                passiveCalculatorEnabled = false,
                englishModeWordSuggestionEnabled = false,
                phraseSuggestionL2Enabled = false,
                customKeyGesturesJson = """{"swipeUp":{},"longPress":{}}""",
                keycapBadgeMode = KeycapBadgeMode.LONG_PRESS
            )
        }

        val reloaded = AppRepository(
            appContext = null,
            publicStore = publicStore,
            secretVault = SecretVault(secretStore, cipher)
        ).settings.value
        assertTrue(reloaded.codetableFiveKeyBottomRow)
        assertFalse(reloaded.xiaoheDirectCommandsEnabled)
        assertFalse(reloaded.xiaoheReverseSplitEnabled)
        assertFalse(reloaded.xiaoheNaturalContinuationEnabled)
        assertFalse(reloaded.passiveCalculatorEnabled)
        assertFalse(reloaded.englishModeWordSuggestionEnabled)
        assertFalse(reloaded.phraseSuggestionL2Enabled)
        assertTrue(reloaded.customKeyGesturesJson.contains("swipeUp"))
        assertEquals(KeycapBadgeMode.LONG_PRESS, reloaded.keycapBadgeMode)
    }

    @Test
    fun `SnippetMatcher normalizes semicolon and bare codes to slash prefix`() {
        assertEquals("/sd", SnippetMatcher.normalizeFlypyCode(";sd"))
        assertEquals("/sd", SnippetMatcher.normalizeFlypyCode("sd"))
        assertEquals("/sd", SnippetMatcher.normalizeFlypyCode("/sd"))
        assertEquals("/yx", SnippetMatcher.normalizeFlypyCode("  ;YX "))
        assertEquals("/abcd", SnippetMatcher.normalizeFlypyCode("/abcdef"))
        assertEquals("", SnippetMatcher.normalizeFlypyCode("/"))
        assertEquals("", SnippetMatcher.normalizeFlypyCode(";"))
        assertEquals("", SnippetMatcher.normalizeFlypyCode("   "))
        assertEquals("sd", SnippetMatcher.extractSlashSnippetCode(";sd"))

        val defaultCodes = DefaultPresets.defaultSnippets().map { it.flypyCode }
        assertEquals(listOf("/sd", "/yx", "/dz"), defaultCodes)
    }

    @Test
    fun `AppDestinations preserves backward-compatible aliases and routes deep links accurately`() {
        assertEquals(AppDestinations.TAB_HOME, AppDestinations.TAB_HISTORY)
        assertEquals(AppDestinations.TAB_KEYBOARD, AppDestinations.TAB_DICTIONARY)
        assertEquals(AppDestinations.TAB_GENERAL, AppDestinations.TAB_SETTINGS)
        assertEquals(AppDestinations.TAB_VOICE_AI, AppDestinations.TAB_API)
        assertEquals(AppDestinations.TAB_VOICE_AI, AppDestinations.TAB_STYLES)

        // Clicking API banner in IME -> TAB_VOICE_AI + SUBTAB_VOICE_API
        val apiRoute = AppDestinations.resolveNavigationRoute(
            openTabExtra = AppDestinations.TAB_API,
            settingsSubTabExtra = null
        )
        assertEquals(AppDestinations.TAB_VOICE_AI, apiRoute.tab)
        assertEquals(AppDestinations.SUBTAB_VOICE_API, apiRoute.voiceAiSubTab)

        // Clicking Dictionary in IME toolbar -> TAB_KEYBOARD + SUBTAB_KB_DICT
        val dictRoute = AppDestinations.resolveNavigationRoute(
            openTabExtra = AppDestinations.TAB_DICTIONARY,
            settingsSubTabExtra = null
        )
        assertEquals(AppDestinations.TAB_KEYBOARD, dictRoute.tab)
        assertEquals(AppDestinations.SUBTAB_KB_DICT, dictRoute.keyboardSubTab)

        // Clicking Preferences in IME -> TAB_GENERAL
        val prefRoute = AppDestinations.resolveNavigationRoute(
            openTabExtra = AppDestinations.TAB_SETTINGS,
            settingsSubTabExtra = AppDestinations.SUBTAB_PREFERENCES
        )
        assertEquals(AppDestinations.TAB_GENERAL, prefRoute.tab)

        // Clicking Styles subtab -> TAB_VOICE_AI + SUBTAB_VOICE_AI_STYLE
        val stylesRoute = AppDestinations.resolveNavigationRoute(
            openTabExtra = AppDestinations.TAB_STYLES,
            settingsSubTabExtra = AppDestinations.SUBTAB_STYLES
        )
        assertEquals(AppDestinations.TAB_VOICE_AI, stylesRoute.tab)
        assertEquals(AppDestinations.SUBTAB_VOICE_AI_STYLE, stylesRoute.voiceAiSubTab)
    }

    @Test
    fun `resolveSchemeIsolationKind strictly isolates Xiaohe, Custom Codetable, and Pinyin`() {
        assertEquals(
            SchemeIsolationKind.XIAOHE_YINXING,
            resolveSchemeIsolationKind(InputScheme.FLYPY_TABLE, SchemaArchetype.FIXED_YINXING)
        )
        assertEquals(
            SchemeIsolationKind.CUSTOM_CODETABLE,
            resolveSchemeIsolationKind(InputScheme.RIME_CUSTOM, SchemaArchetype.FIXED_SHAPECODE, hasCustomSchema = true)
        )
        assertEquals(
            SchemeIsolationKind.CUSTOM_CODETABLE,
            resolveSchemeIsolationKind(InputScheme.RIME_CUSTOM, SchemaArchetype.PUSH_TOPUP_CODE, hasCustomSchema = true)
        )
        assertEquals(
            SchemeIsolationKind.PINYIN_OR_DOUBLE_PINYIN,
            resolveSchemeIsolationKind(InputScheme.RIME_PINYIN, SchemaArchetype.SENTENCE_PINYIN)
        )
        assertEquals(
            SchemeIsolationKind.PINYIN_OR_DOUBLE_PINYIN,
            resolveSchemeIsolationKind(InputScheme.RIME_DOUBLE_PINYIN_FLYPY, SchemaArchetype.SENTENCE_PINYIN)
        )
    }

    @Test
    fun `KeyGestureConfigCodec provides 26 defaults and round-trips custom JSON cleanly`() {
        val def = KeyGestureConfigCodec.defaultProfile()
        assertEquals(26, def.swipeUp.size)
        assertEquals(26, def.longPress.size)
        assertEquals("：“", def.swipeUp['q']?.text)
        assertEquals(KeyGestureActionKind.UNDO_LAST, def.swipeUp['i']?.type)
        assertEquals(KeyGestureActionKind.REPEAT_LAST, def.swipeUp['f']?.type)
        assertEquals(KeyGestureActionKind.COMMIT_PAIRED, def.swipeUp['y']?.type)
        assertEquals("《", def.swipeUp['y']?.text)
        assertEquals("》", def.swipeUp['y']?.rightText)
        assertEquals("SELECT_ALL", def.longPress['a']?.editorShortcut)
        assertEquals("1", def.longPress['q']?.text)
        assertFalse(KeyGestureConfigCodec.isCustomized(def))

        val modifiedSwipe = def.swipeUp.toMutableMap().apply {
            put('q', KeyGestureBindingSpec(KeyGestureActionKind.COMMIT_PAIRED, text = "【", rightText = "】", label = "【】"))
        }
        val modifiedLong = def.longPress.toMutableMap().apply {
            put('z', KeyGestureBindingSpec(KeyGestureActionKind.NONE))
        }
        val customProfile = def.copy(swipeUp = modifiedSwipe, longPress = modifiedLong)
        assertTrue(KeyGestureConfigCodec.isCustomized(customProfile))

        val json = KeyGestureConfigCodec.toJson(customProfile)
        val decoded = KeyGestureConfigCodec.fromJsonOrDefault(json)
        assertEquals("【", decoded.swipeUp['q']?.text)
        assertEquals("】", decoded.swipeUp['q']?.rightText)
        assertEquals(KeyGestureActionKind.NONE, decoded.longPress['z']?.type)
        assertEquals("1", decoded.longPress['q']?.text)
    }

    @Test
    fun `XiaohePhraseCodeHelper derives 4-code phrases for 2, 3, and 4+ Hanzi`() {
        val reverseMap = mapOf(
            "双" to listOf("ulvy", "ul"),
            "拼" to listOf("pbbf", "pb"),
            "输" to listOf("uuyj"),
            "入" to listOf("ruon"),
            "小" to listOf("xnbb"),
            "鹤" to listOf("hebn"),
            "音" to listOf("ybll"),
            "形" to listOf("xkaa"),
            "法" to listOf("faxs")
        )

        // 2 chars: AaAbBaBb -> ul + pb = ulpb
        assertEquals("ulpb", XiaohePhraseCodeHelper.deriveXiaohe4Code("双拼", reverseMap))
        // 3 chars: AaBaCaCb -> x + h + yb = xhyb
        assertEquals("xhyb", XiaohePhraseCodeHelper.deriveXiaohe4Code("小鹤音", reverseMap))
        // 4 chars: AaBaCaZa -> x + h + y + x = xhyx
        assertEquals("xhyx", XiaohePhraseCodeHelper.deriveXiaohe4Code("小鹤音形", reverseMap))
        // 5 chars: AaBaCaZa -> s/u + p + u + f -> upuf (first3 + last1: 双拼输入法 -> u + p + u + f)
        assertEquals("upuf", XiaohePhraseCodeHelper.deriveXiaohe4Code("双拼输入法", reverseMap))
        // Non-Hanzi or single char -> null
        assertNull(XiaohePhraseCodeHelper.deriveXiaohe4Code("双", reverseMap))
        assertNull(XiaohePhraseCodeHelper.deriveXiaohe4Code("TypeFree", reverseMap))
    }
}
