package com.typefree.core.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalPromptTemplatesGoldenTest {

    @Test
    fun frozenConstantsMatchContract() {
        assertEquals(1, LocalPromptTemplates.PROMPT_TEMPLATE_VERSION)
        assertEquals(12, LocalPromptTemplates.COMPLETION_MAX_TOKENS)
        assertEquals(90, LocalPromptTemplates.COMPLETION_CONTEXT_MAX_CHARS)
        assertEquals(12, LocalPromptTemplates.MAX_LOCAL_DICT_TERMS)
    }

    @Test
    fun dictatePromptGoldenSnapshotAndTop12DictionaryCap() {
        val rawTerms = (1..16).map { "词条$it" } + listOf("词条1", "   ", "")
        val pair = LocalPromptTemplates.buildDictatePrompt(
            rawTranscript = "  嗯那个周三三点不对改成四点半开会  ",
            dictionaryTerms = rawTerms,
            toneHint = "专业简洁"
        )

        val expectedSystem = "TypeFree端侧引擎 | 任务:dictate | 仅输出净写正文，禁回答与前缀。"

        val expectedUser =
            "<scene_and_tone>\n目标应用：通用\n风格：专业简洁\n</scene_and_tone>\n" +
                "<personal_dictionary>\n" +
                (1..12).joinToString("\n") { "词条$it" } +
                "\n</personal_dictionary>\n" +
                "<raw_speech_transcript>\n嗯那个周三三点不对改成四点半开会\n</raw_speech_transcript>\n\n请直接输出整理后的最终上屏文本（仅输出正文）："

        assertEquals(1, pair.templateVersion)
        assertEquals(256, pair.maxTokens)
        assertEquals(expectedSystem, pair.systemPrompt)
        assertEquals(expectedUser, pair.userPrompt)
        assertFalse(pair.userPrompt.contains("词条13"))
    }

    @Test
    fun sentenceCompletionPromptGoldenSnapshotClipsTailTo90CharsAndNoDictionary() {
        val prefix = "甲".repeat(40)
        val tail = "乙".repeat(85) + "今天下班后"
        assertEquals(130, (prefix + tail).length)

        val pair = LocalPromptTemplates.buildSentenceCompletionPrompt(
            cursorBeforeContext = prefix + tail,
            toneHint = "微信口语"
        )

        val expectedSystem = "TypeFree端侧引擎 | 任务:sentence_completion | 仅输出2-20字续写，禁复读前文与引号。"

        val expectedUser =
            "<scene_and_tone>\n目标应用：通用\n风格：微信口语\n</scene_and_tone>\n" +
                "<cursor_before_context>\n$tail\n</cursor_before_context>\n\n" +
                "请直接输出自然续写的后半句（最多 20 字，不重复前文，不加引号）："

        assertEquals(1, pair.templateVersion)
        assertEquals(LocalPromptTemplates.COMPLETION_MAX_TOKENS, pair.maxTokens)
        assertEquals(expectedSystem, pair.systemPrompt)
        assertEquals(expectedUser, pair.userPrompt)
        assertEquals(90, tail.length)
        assertFalse(pair.userPrompt.contains("甲"))
        assertFalse(pair.userPrompt.contains("<personal_dictionary>"))
    }

    @Test
    fun speakToEditPromptGoldenSnapshot() {
        val pair = LocalPromptTemplates.buildSpeakToEditPrompt(
            selectedText = "明天的会议改到下午三点",
            voiceInstruction = "帮我改成更礼貌一点的英文",
            dictionaryTerms = listOf("TypeFree", "Q3"),
            toneHint = "商务邮件"
        )

        val expectedSystem = "TypeFree端侧引擎 | 任务:speak_to_edit | 仅输出替换正文，禁解释前缀。"

        val expectedUser =
            "<scene_and_tone>\n风格：商务邮件\n</scene_and_tone>\n" +
                "<personal_dictionary>\nTypeFree\nQ3\n</personal_dictionary>\n" +
                "<selected_text>\n明天的会议改到下午三点\n</selected_text>\n\n" +
                "<voice_instruction>\n帮我改成更礼貌一点的英文\n</voice_instruction>\n\n请直接输出替换后的文本结果："

        assertEquals(1, pair.templateVersion)
        assertEquals(384, pair.maxTokens)
        assertEquals(expectedSystem, pair.systemPrompt)
        assertEquals(expectedUser, pair.userPrompt)
    }

    @Test
    fun translatePromptGoldenSnapshot() {
        val pair = LocalPromptTemplates.buildTranslatePrompt(
            rawTranscript = "请问这个接口支持流式返回吗",
            targetLanguage = "English",
            dictionaryTerms = listOf("TypeFree")
        )

        val expectedSystem = "TypeFree端侧引擎 | 任务:translate | 仅输出目标语译文，禁回答与解释。"

        val expectedUser =
            "<target_language>English</target_language>\n" +
                "<scene_and_tone>\n风格：自然地道 — 母语级自然表达，符合对话语境。\n</scene_and_tone>\n" +
                "<personal_dictionary>\nTypeFree\n</personal_dictionary>\n" +
                "<raw_speech_transcript>\n请问这个接口支持流式返回吗\n</raw_speech_transcript>\n\n请直接输出【English】译文："

        assertEquals(1, pair.templateVersion)
        assertEquals(256, pair.maxTokens)
        assertEquals(expectedSystem, pair.systemPrompt)
        assertEquals(expectedUser, pair.userPrompt)
    }

    @Test
    fun helpMeWritePromptGoldenSnapshot() {
        val pair = LocalPromptTemplates.buildHelpMeWritePrompt(
            writingRequest = "礼貌拒绝周末加班，说下周一上午十点前交付",
            editorDraft = "不好意思周末有事",
            chatContext = "[对方] 周末能顺手把这个需求发了吗？",
            dictionaryTerms = listOf("TypeFree"),
            toneHint = "温和坚定"
        )

        val expectedSystem = "TypeFree端侧引擎 | 任务:help_me_write | 仅输出可直接发送的成稿正文，禁寒暄。"

        val expectedUser =
            "<scene_and_tone>\n目标应用：通用\n风格：温和坚定\n</scene_and_tone>\n" +
                "<personal_dictionary>\nTypeFree\n</personal_dictionary>\n" +
                "<editor_draft>\n不好意思周末有事\n</editor_draft>\n" +
                "<chat_context>\n[对方] 周末能顺手把这个需求发了吗？\n</chat_context>\n" +
                "<writing_request>\n礼貌拒绝周末加班，说下周一上午十点前交付\n</writing_request>\n\n请直接输出可直接发送的成稿正文："

        assertEquals(1, pair.templateVersion)
        assertEquals(384, pair.maxTokens)
        assertEquals(expectedSystem, pair.systemPrompt)
        assertEquals(expectedUser, pair.userPrompt)
    }
}
