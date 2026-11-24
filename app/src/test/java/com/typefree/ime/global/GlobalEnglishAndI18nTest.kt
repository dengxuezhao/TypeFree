package com.typefree.ime.global

import android.text.InputType
import android.view.inputmethod.EditorInfo
import com.typefree.ime.data.AppRepository
import com.typefree.ime.data.AppSettings
import com.typefree.ime.data.DefaultPresets
import com.typefree.ime.data.DictionaryEntry
import com.typefree.ime.data.InMemoryKeyValueStore
import com.typefree.ime.data.JvmAesGcmCipher
import com.typefree.ime.data.SecretVault
import com.typefree.ime.i18n.AppLang
import com.typefree.ime.i18n.AppStrings
import com.typefree.ime.ime.EnglishSmartTyper
import com.typefree.ime.ime.ImeLogic
import com.typefree.ime.ime.jev.ChatTopology
import com.typefree.ime.ime.jev.JevDomainProfile
import com.typefree.ime.ime.jev.JevResponseParser
import com.typefree.ime.ime.jev.JevRoleMode
import com.typefree.ime.network.PromptBuilder
import com.typefree.ime.ui.screens.ApiConfigBackupHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier
import java.util.Locale

class GlobalEnglishAndI18nTest {

    private val normalProseInputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL

    @Test
    fun `EnglishSmartTyper expands unambiguous contractions and preserves capitalization`() {
        val typer = EnglishSmartTyper()

        val expectedContractions = mapOf(
            "i" to "I",
            "im" to "I'm",
            "ive" to "I've",
            "dont" to "don't",
            "cant" to "can't",
            "wont" to "won't",
            "didnt" to "didn't",
            "doesnt" to "doesn't",
            "isnt" to "isn't",
            "arent" to "aren't",
            "wasnt" to "wasn't",
            "werent" to "weren't",
            "hasnt" to "hasn't",
            "havent" to "haven't",
            "hadnt" to "hadn't",
            "wouldnt" to "wouldn't",
            "couldnt" to "couldn't",
            "shouldnt" to "shouldn't",
            "thats" to "that's",
            "whats" to "what's",
            "heres" to "here's",
            "theres" to "there's",
            "youre" to "you're",
            "theyre" to "they're"
        )

        for ((raw, expected) in expectedContractions) {
            val commit = typer.onCommitDelimiter(
                textBeforeCursor = "Hello $raw",
                delimiter = " ",
                inputType = normalProseInputType,
                imeOptions = 0,
                isChineseMode = false,
                englishAutoCorrectEnabled = true
            )
            assertNotNull("Expected contraction fix for '$raw'", commit)
            assertEquals(raw.length, commit!!.deleteBeforeLength)
            assertEquals("$expected ", commit.textToCommit)
        }

        // Initial-cap and ALL-CAPS preservation
        assertEquals("Don't", EnglishSmartTyper.resolveReplacement("Dont"))
        assertEquals("DON'T", EnglishSmartTyper.resolveReplacement("DONT"))
        assertEquals("That's", EnglishSmartTyper.resolveReplacement("Thats"))
        assertEquals("You're", EnglishSmartTyper.resolveReplacement("Youre"))
        // Uppercase "IM" (Instant Messaging) and mixed-case identifiers must stay untouched
        assertNull(EnglishSmartTyper.resolveReplacement("IM"))
        assertNull(EnglishSmartTyper.resolveReplacement("I"))
        assertNull(EnglishSmartTyper.resolveReplacement("dOnT"))
    }

    @Test
    fun `EnglishSmartTyper never corrupts valid English words`() {
        val typer = EnglishSmartTyper()
        val realWords = listOf(
            "ill", "Ill",
            "were", "Were",
            "well", "Well",
            "hell", "Hell",
            "shell", "Shell",
            "id", "Id", "ID",
            "its", "Its",
            "wed", "Wed",
            "lets", "Lets",
            "hed", "shed"
        )

        for (word in realWords) {
            assertNull("Must NOT replace valid word '$word'", EnglishSmartTyper.resolveReplacement(word))
            val commit = typer.onCommitDelimiter(
                textBeforeCursor = word,
                delimiter = " ",
                inputType = normalProseInputType,
                imeOptions = 0,
                isChineseMode = false,
                englishAutoCorrectEnabled = true
            )
            assertNull("Must NOT auto-replace valid word '$word' on Space", commit)
        }
    }

    @Test
    fun `EnglishSmartTyper fixes high-confidence typos and supports 1-tap Backspace undo with suppression`() {
        val typer = EnglishSmartTyper()

        val expectedTypos = mapOf(
            "teh" to "the",
            "recieve" to "receive",
            "becuase" to "because",
            "definately" to "definitely",
            "seperate" to "separate",
            "occured" to "occurred",
            "adress" to "address",
            "thier" to "their",
            "wierd" to "weird",
            "untill" to "until",
            "alot" to "a lot",
            "wich" to "which",
            "tommorow" to "tomorrow",
            "accomodate" to "accommodate",
            "Teh" to "The",
            "Recieve" to "Receive"
        )

        for ((typo, fixed) in expectedTypos) {
            assertEquals(fixed, EnglishSmartTyper.resolveReplacement(typo))
        }

        // Step 1: User types "dont" + Space -> commits "don't "
        val firstSpace = typer.onCommitDelimiter(
            textBeforeCursor = "I dont",
            delimiter = " ",
            inputType = normalProseInputType,
            imeOptions = 0,
            isChineseMode = false,
            englishAutoCorrectEnabled = true
        )
        assertNotNull(firstSpace)
        assertEquals(4, firstSpace!!.deleteBeforeLength)
        assertEquals("don't ", firstSpace.textToCommit)

        // Step 2: User immediately presses Backspace once -> reverts "don't " back to "dont"
        val revert = typer.onBackspace("I don't ")
        assertNotNull(revert)
        assertEquals(6, revert!!.deleteBeforeLength)
        assertEquals("dont", revert.restoredToken)

        // Step 3: User presses Space again on "I dont" -> suppressed so user is never trapped!
        val secondSpace = typer.onCommitDelimiter(
            textBeforeCursor = "I dont",
            delimiter = " ",
            inputType = normalProseInputType,
            imeOptions = 0,
            isChineseMode = false,
            englishAutoCorrectEnabled = true
        )
        assertNull("Re-pressing Space immediately after Backspace undo must stay suppressed", secondSpace)

        // Step 4: Typing the word again later works normally
        val laterSpace = typer.onCommitDelimiter(
            textBeforeCursor = "I dont dont",
            delimiter = " ",
            inputType = normalProseInputType,
            imeOptions = 0,
            isChineseMode = false,
            englishAutoCorrectEnabled = true
        )
        assertNotNull(laterSpace)
        assertEquals("don't ", laterSpace!!.textToCommit)

        // Punctuation delimiter ("im" + "," -> "I'm,") + Backspace revert
        val commaCommit = typer.onCommitDelimiter(
            textBeforeCursor = "Hey im",
            delimiter = ",",
            inputType = normalProseInputType,
            imeOptions = 0,
            isChineseMode = false,
            englishAutoCorrectEnabled = true
        )
        assertNotNull(commaCommit)
        assertEquals("I'm,", commaCommit!!.textToCommit)
        val commaRevert = typer.onBackspace("Hey I'm,")
        assertNotNull(commaRevert)
        assertEquals(4, commaRevert!!.deleteBeforeLength)
        assertEquals("im", commaRevert.restoredToken)
    }

    @Test
    fun `EnglishSmartTyper smart auto-cap and strict code URL email password bypass`() {
        // Auto-cap triggers on empty field, newline, and sentence-ending punctuation + space
        assertTrue(EnglishSmartTyper.shouldAutoCapitalize("", normalProseInputType, 0, isChineseMode = false, englishAutoCapEnabled = true))
        assertTrue(EnglishSmartTyper.shouldAutoCapitalize("   ", normalProseInputType, 0, isChineseMode = false, englishAutoCapEnabled = true))
        assertTrue(EnglishSmartTyper.shouldAutoCapitalize("First line\n", normalProseInputType, 0, isChineseMode = false, englishAutoCapEnabled = true))
        assertTrue(EnglishSmartTyper.shouldAutoCapitalize("Hello world. ", normalProseInputType, 0, isChineseMode = false, englishAutoCapEnabled = true))
        assertTrue(EnglishSmartTyper.shouldAutoCapitalize("Really?! ", normalProseInputType, 0, isChineseMode = false, englishAutoCapEnabled = true))
        assertTrue(EnglishSmartTyper.shouldAutoCapitalize("Done.\" ", normalProseInputType, 0, isChineseMode = false, englishAutoCapEnabled = true))

        // Must NOT auto-cap mid-sentence, inside domain names, or when disabled / in Chinese mode
        assertFalse(EnglishSmartTyper.shouldAutoCapitalize("Hello ", normalProseInputType, 0, isChineseMode = false, englishAutoCapEnabled = true))
        assertFalse(EnglishSmartTyper.shouldAutoCapitalize("example.", normalProseInputType, 0, isChineseMode = false, englishAutoCapEnabled = true))
        assertFalse(EnglishSmartTyper.shouldAutoCapitalize("Hello, ", normalProseInputType, 0, isChineseMode = false, englishAutoCapEnabled = true))
        assertFalse(EnglishSmartTyper.shouldAutoCapitalize("", normalProseInputType, 0, isChineseMode = true, englishAutoCapEnabled = true))
        assertFalse(EnglishSmartTyper.shouldAutoCapitalize("", normalProseInputType, 0, isChineseMode = false, englishAutoCapEnabled = false))

        // Field-level bypasses: password, URI, email, web email, NO_SUGGESTIONS, FORCE_ASCII
        val bypassedFields = listOf(
            (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD) to 0,
            (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD) to 0,
            (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI) to 0,
            (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS) to 0,
            (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS) to 0,
            (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_FILTER) to 0,
            normalProseInputType to EditorInfo.IME_FLAG_FORCE_ASCII,
            InputType.TYPE_CLASS_NUMBER to 0
        )

        val typer = EnglishSmartTyper()
        for ((fieldType, imeOpts) in bypassedFields) {
            assertFalse(
                EnglishSmartTyper.shouldAutoCapitalize("", fieldType, imeOpts, isChineseMode = false, englishAutoCapEnabled = true)
            )
            assertNull(
                typer.onCommitDelimiter("dont", " ", fieldType, imeOpts, isChineseMode = false, englishAutoCorrectEnabled = true)
            )
        }

        // Token-level URL / code / email / identifier prefixes in prose fields must also bypass
        val nonProseContexts = listOf(
            "https://dont",
            "www.teh",
            "user@dont",
            "snake_dont",
            "kebab-teh",
            "path/to/im",
            "v2im"
        )
        for (ctx in nonProseContexts) {
            assertNull(
                "Must not autocorrect URL/code token '$ctx'",
                typer.onCommitDelimiter(ctx, " ", normalProseInputType, 0, isChineseMode = false, englishAutoCorrectEnabled = true)
            )
        }
    }

    @Test
    fun `Non-Chinese first-run defaults and AppSettings persistence + backup round-trip`() {
        val usFirstRun = AppRepository.resolveFirstRunSettings(Locale.US)
        assertFalse("US English first-run should start in EN QWERTY, not Flypy", usFirstRun.flypyEnabled)
        assertFalse("US English first-run should default lastQwertyIsChinese=false", usFirstRun.lastQwertyIsChinese)
        assertTrue(usFirstRun.englishAutoCap)
        assertTrue(usFirstRun.englishAutoCorrect)
        assertEquals(AppStrings.LANG_SYSTEM, usFirstRun.appLanguage)

        val deFirstRun = AppRepository.resolveFirstRunSettings(Locale.GERMANY)
        assertFalse(deFirstRun.flypyEnabled)
        assertFalse(deFirstRun.lastQwertyIsChinese)

        val zhCnFirstRun = AppRepository.resolveFirstRunSettings(Locale.SIMPLIFIED_CHINESE)
        assertTrue("zh-CN first-run must preserve Chinese QWERTY default", zhCnFirstRun.flypyEnabled)
        assertTrue(zhCnFirstRun.lastQwertyIsChinese)

        val zhTwFirstRun = AppRepository.resolveFirstRunSettings(Locale.TRADITIONAL_CHINESE)
        assertTrue("zh-TW first-run must preserve Chinese QWERTY default", zhTwFirstRun.flypyEnabled)
        assertTrue(zhTwFirstRun.lastQwertyIsChinese)

        // Persistence & backup whitelist round-trip
        val publicStore = InMemoryKeyValueStore()
        val secretStore = InMemoryKeyValueStore()
        val cipher = JvmAesGcmCipher()
        val repo1 = AppRepository(
            appContext = null,
            publicStore = publicStore,
            secretVault = SecretVault(secretStore, cipher)
        )
        repo1.updateSettings {
            it.copy(
                appLanguage = AppStrings.LANG_EN,
                englishAutoCap = false,
                englishAutoCorrect = false
            )
        }

        val repo2 = AppRepository(
            appContext = null,
            publicStore = publicStore,
            secretVault = SecretVault(secretStore, cipher)
        )
        assertEquals(AppStrings.LANG_EN, repo2.settings.value.appLanguage)
        assertFalse(repo2.settings.value.englishAutoCap)
        assertFalse(repo2.settings.value.englishAutoCorrect)

        val exportedJson = ApiConfigBackupHelper.exportSettingsJson(repo2.settings.value, includeSecrets = false)
        val imported = ApiConfigBackupHelper.parseAndMergeConfigJson(exportedJson, AppSettings()).getOrThrow()
        assertEquals(AppStrings.LANG_EN, imported.appLanguage)
        assertFalse(imported.englishAutoCap)
        assertFalse(imported.englishAutoCorrect)
    }

    @Test
    fun `AppStrings language resolution and ImeLogic localized Enter action labels`() {
        assertEquals(AppLang.EN, AppStrings.resolveLang("system", Locale.US))
        assertEquals(AppLang.EN, AppStrings.resolveLang("system", Locale.JAPAN))
        assertEquals(AppLang.ZH_CN, AppStrings.resolveLang("system", Locale.SIMPLIFIED_CHINESE))
        assertEquals(AppLang.ZH_TW, AppStrings.resolveLang("system", Locale.TAIWAN))
        assertEquals(AppLang.ZH_TW, AppStrings.resolveLang("system", Locale("zh", "HK")))

        // Explicit user choice overrides system locale
        assertEquals(AppLang.EN, AppStrings.resolveLang("en", Locale.SIMPLIFIED_CHINESE))
        assertEquals(AppLang.ZH_CN, AppStrings.resolveLang("zh-CN", Locale.US))
        assertEquals(AppLang.ZH_TW, AppStrings.resolveLang("zh-TW", Locale.US))

        assertFalse(AppStrings.isChinese("en", Locale.SIMPLIFIED_CHINESE))
        assertTrue(AppStrings.isChinese("zh-CN", Locale.US))
        assertTrue(AppStrings.isChinese("zh-TW", Locale.US))

        // Localized Enter key labels
        assertEquals("Send", ImeLogic.resolveEnterAction(EditorInfo.IME_ACTION_SEND, normalProseInputType, isChineseLocale = false).label)
        assertEquals("Search", ImeLogic.resolveEnterAction(EditorInfo.IME_ACTION_SEARCH, normalProseInputType, isChineseLocale = false).label)
        assertEquals("Go", ImeLogic.resolveEnterAction(EditorInfo.IME_ACTION_GO, normalProseInputType, isChineseLocale = false).label)
        assertEquals("Next", ImeLogic.resolveEnterAction(EditorInfo.IME_ACTION_NEXT, normalProseInputType, isChineseLocale = false).label)
        assertEquals("Done", ImeLogic.resolveEnterAction(EditorInfo.IME_ACTION_DONE, normalProseInputType, isChineseLocale = false).label)
        assertEquals(
            "Return",
            ImeLogic.resolveEnterAction(
                EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_ENTER_ACTION,
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE,
                isChineseLocale = false
            ).label
        )
        assertEquals(
            "Return",
            ImeLogic.resolveEnterAction(
                EditorInfo.IME_ACTION_NONE,
                normalProseInputType,
                isChineseLocale = false
            ).label
        )

        // Default Chinese backward compatibility
        assertEquals("发送", ImeLogic.resolveEnterAction(EditorInfo.IME_ACTION_SEND, normalProseInputType).label)
        assertEquals("搜索", ImeLogic.resolveEnterAction(EditorInfo.IME_ACTION_SEARCH, normalProseInputType).label)
    }

    @Test
    fun `PromptBuilder English static constants, selector parity, and JevResponseParser English XML compatibility`() {
        val enPromptFields = listOf(
            "DICTATE_SYSTEM_PROMPT_EN",
            "HELP_ME_WRITE_SYSTEM_PROMPT_EN",
            "ASK_AI_CARD_SYSTEM_PROMPT_EN",
            "SPEAK_TO_EDIT_SYSTEM_PROMPT_EN",
            "TRANSLATE_SYSTEM_PROMPT_EN",
            "SENTENCE_COMPLETION_SYSTEM_PROMPT_EN",
            "JEV_COPILOT_SYSTEM_PROMPT_EN"
        )
        for (fieldName in enPromptFields) {
            val field = PromptBuilder::class.java.getDeclaredField(fieldName)
            assertTrue("$fieldName must be static final const val for Prefix Cache", Modifier.isStatic(field.modifiers) && Modifier.isFinal(field.modifiers))
        }

        // STT Bias Prompt
        assertEquals(
            "Natural spoken English with accurate punctuation and capitalization.",
            PromptBuilder.buildSttBiasPrompt(emptyList(), isChineseLocale = false)
        )
        assertEquals(
            "以下是普通话与英文自然混合语音，请准确添加标点。",
            PromptBuilder.buildSttBiasPrompt(emptyList())
        )
        val dict = listOf(DictionaryEntry(term = "TypeFree", wrongVariants = "type three"))
        assertTrue(PromptBuilder.buildSttBiasPrompt(dict, isChineseLocale = false).contains("Proper nouns and vocabulary reference: TypeFree."))

        val defaultStyle = DefaultPresets.defaultStyles().first()

        val (dictSysEn, dictUsrEn) = PromptBuilder.buildDictationPrompts(
            rawSpeech = "um lets meet tomorrow at 4",
            style = defaultStyle,
            targetAppName = "Slack",
            dictionary = dict,
            surroundingBeforeCursor = "Hey team, ",
            isChineseLocale = false
        )
        assertSame(PromptBuilder.DICTATE_SYSTEM_PROMPT_EN, dictSysEn)
        assertTrue(dictUsrEn.contains("Target App: Slack"))
        assertTrue(dictUsrEn.contains("common mishearings: type three"))

        val (askSysEn, askUsrEn) = PromptBuilder.buildAskAiCardPrompts(
            query = "HTTP 429 meaning",
            targetAppName = "Chrome",
            dictionary = emptyList(),
            isChineseLocale = false
        )
        assertSame(PromptBuilder.ASK_AI_CARD_SYSTEM_PROMPT_EN, askSysEn)
        assertTrue(askSysEn.contains("60 words"))
        assertTrue(askUsrEn.contains("<user_query>\nHTTP 429 meaning\n</user_query>"))

        val (jevSysEn, jevUsrEn) = PromptBuilder.buildJevCopilotPrompts(
            domainProfile = JevDomainProfile.IM_CHAT,
            targetAppName = "WhatsApp",
            chatContextMessages = listOf("[Them]: Can you also squeeze the iOS redesign into Friday's sprint?"),
            userBottomLine = "No capacity this week, earliest is next Wednesday",
            chatTopology = ChatTopology(isGroupChat = false, roleMode = JevRoleMode.PRIVATE_1V1),
            isChineseLocale = false
        )
        assertSame(PromptBuilder.JEV_COPILOT_SYSTEM_PROMPT_EN, jevSysEn)
        assertTrue(jevSysEn.contains("<jev>"))
        assertTrue(jevSysEn.contains("<weights>W1,W2,W3</weights>"))
        assertTrue(jevSysEn.contains("<route>"))
        assertTrue(jevUsrEn.contains("Host App: WhatsApp"))
        assertTrue(jevUsrEn.contains("<user_bottom_line>"))

        // Verify JevResponseParser parses English multi-word labels, tag, subtext, weights & route without CJK 8-char truncation
        val simulatedEnglishXml = """
            <think>1. 1v1 Direct -> 2. Route B -> 3. Lv.6 scope creep -> 4. Warm cushion / Firm boundary / Action lock-in</think>
            <jev>
            <lv>6</lv>
            <tag>Scope Creep Probe</tag>
            <subtext>Testing if you'll absorb extra iOS redesign work into Friday's deadline without pushing back on timeline.</subtext>
            <opt label="Graceful Pivot">Friday's sprint is fully locked on our end, so I can't squeeze the iOS redesign in this week — happy to kick it off first thing Wednesday.</opt>
            <opt label="Firm Boundary">No bandwidth left for Friday's cut, earliest I can take on the iOS redesign is next Wednesday.</opt>
            <opt label="Dry Wit">Only if we invent a 36-hour Thursday — Friday is maxed out, let's slot iOS redesign for Wednesday morning?</opt>
            <weights>28,54,18</weights>
            <route>B</route>
            </jev>
        """.trimIndent()

        val parsed = JevResponseParser.parse(simulatedEnglishXml)
        assertEquals(6, parsed.level)
        assertEquals("Scope Creep Probe", parsed.tag)
        assertTrue(parsed.subtext.contains("without pushing back on timeline."))
        assertEquals("B", parsed.routeCode)
        assertEquals(3, parsed.options.size)
        assertEquals("Firm Boundary", parsed.options[0].label)
        assertEquals(54, parsed.options[0].probability)
        assertTrue(parsed.options[0].isTopRecommended)
        assertEquals("Graceful Pivot", parsed.options[1].label)
        assertEquals("Dry Wit", parsed.options[2].label)
        assertEquals(100, parsed.options.sumOf { it.probability })

        val preview = JevResponseParser.parseStreamingPreview(simulatedEnglishXml)
        assertNotNull(preview)
        assertEquals("Firm Boundary", preview!!.options[0].label)
    }

    @Test
    fun `regression - web and IM chat fields do not auto-cap mid-sentence, NO_SUGGESTIONS chat boxes trigger contractions, and Ask AI anchors local date`() {
        val webEditType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT
        val shortMsgType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE
        val wechatNoSugType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        val personNameType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PERSON_NAME

        // Mid-sentence spaces in web inputs and IM chat boxes MUST NOT auto-capitalize every word
        assertFalse(EnglishSmartTyper.shouldAutoCapitalize("I'm ", webEditType, 0, isChineseMode = false, englishAutoCapEnabled = true))
        assertFalse(EnglishSmartTyper.shouldAutoCapitalize("I'm don't ", shortMsgType, 0, isChineseMode = false, englishAutoCapEnabled = true))
        assertTrue(EnglishSmartTyper.shouldAutoCapitalize("", webEditType, 0, isChineseMode = false, englishAutoCapEnabled = true))
        assertTrue(EnglishSmartTyper.shouldAutoCapitalize("Done. ", shortMsgType, 0, isChineseMode = false, englishAutoCapEnabled = true))
        assertTrue(EnglishSmartTyper.shouldAutoCapitalize("John ", personNameType, 0, isChineseMode = false, englishAutoCapEnabled = true))

        // WeChat/QQ chat boxes with TYPE_TEXT_FLAG_NO_SUGGESTIONS MUST still trigger contractions & suggestion previews
        val typer = EnglishSmartTyper()
        val dontCommit = typer.onCommitDelimiter(
            textBeforeCursor = "dont",
            delimiter = " ",
            inputType = wechatNoSugType,
            imeOptions = 0,
            isChineseMode = false,
            englishAutoCorrectEnabled = true
        )
        assertNotNull(dontCommit)
        assertEquals("don't ", dontCommit!!.textToCommit)

        val suggester = com.typefree.ime.ime.predict.EnglishWordSuggester()
        assertEquals("don't", suggester.suggestWords("dont").firstOrNull())
        assertEquals("I'm", suggester.suggestWords("im").firstOrNull())
        assertEquals("the", suggester.suggestWords("teh").firstOrNull())

        // Ask AI prompt injects <current_date> anchor in both locales
        val fixedDate = java.time.LocalDate.of(2026, 11, 24)
        val (_, askUsrZh) = PromptBuilder.buildAskAiCardPrompts(
            query = "今天是什么节日",
            isChineseLocale = true,
            currentDate = fixedDate
        )
        assertTrue(askUsrZh.contains("<current_date>2026年11月24日（星期二）</current_date>"))

        val (_, askUsrEn) = PromptBuilder.buildAskAiCardPrompts(
            query = "What day is today?",
            isChineseLocale = false,
            currentDate = fixedDate
        )
        assertTrue(askUsrEn.contains("<current_date>2026-11-24 (Tuesday)</current_date>"))

        // English QWERTY top bar drops Ask AI on 360dp width instead of overlapping left icons
        val enPlan360 = com.typefree.ime.ime.chrome.TopBarSlotPolicy.resolveQwertyBarLayout(
            availableWidthDp = 360f,
            showUndoButton = false,
            showClipboardButton = true,
            isIncognito = false,
            hasPriorityBanner = false,
            isClipboardBannerActive = false,
            wantAskAi = true,
            wantJevCopilot = true,
            wantVoicePen = true,
            lang = AppLang.EN
        )
        assertFalse(enPlan360.showRightAskAi)
    }
}
