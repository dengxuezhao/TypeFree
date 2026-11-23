package com.typefree.core.offline

data class LocalPromptPair(
    val systemPrompt: String,
    val userPrompt: String,
    val maxTokens: Int,
    val templateVersion: Int = LocalPromptTemplates.PROMPT_TEMPLATE_VERSION
)

/**
 * Frozen prompt templates for on-device fine-tuned SLMs (enable_thinking = false).
 *
 * Never reuse the evolving cloud PromptBuilder here; any wording change must bump
 * [PROMPT_TEMPLATE_VERSION] and update the corresponding golden snapshot tests.
 */
object LocalPromptTemplates {

    const val PROMPT_TEMPLATE_VERSION: Int = 1
    const val COMPLETION_MAX_TOKENS: Int = 12
    const val COMPLETION_CONTEXT_MAX_CHARS: Int = 90
    const val MAX_LOCAL_DICT_TERMS: Int = 12

    private const val DICTATE_MAX_TOKENS: Int = 256
    private const val SPEAK_TO_EDIT_MAX_TOKENS: Int = 384
    private const val TRANSLATE_MAX_TOKENS: Int = 256
    private const val HELP_ME_WRITE_MAX_TOKENS: Int = 384
    private const val JEV_COPILOT_MAX_TOKENS: Int = 320
    private const val MAX_HELP_CHAT_CONTEXT_CHARS: Int = 600
    private const val MAX_JEV_CHAT_CONTEXT_CHARS: Int = 1200

    private const val DICTATE_SYSTEM_PROMPT: String =
        "TypeFree端侧引擎 | 任务:dictate | 仅输出净写正文，禁回答与前缀。"

    private const val SPEAK_TO_EDIT_SYSTEM_PROMPT: String =
        "TypeFree端侧引擎 | 任务:speak_to_edit | 仅输出替换正文，禁解释前缀。"

    private const val COMPLETION_SYSTEM_PROMPT: String =
        "TypeFree端侧引擎 | 任务:sentence_completion | 仅输出2-20字续写，禁复读前文与引号。"

    private const val TRANSLATE_SYSTEM_PROMPT: String =
        "TypeFree端侧引擎 | 任务:translate | 仅输出目标语译文，禁回答与解释。"

    private const val HELP_ME_WRITE_SYSTEM_PROMPT: String =
        "TypeFree端侧引擎 | 任务:help_me_write | 仅输出可直接发送的成稿正文，禁寒暄。"

    private const val JEV_COPILOT_SYSTEM_PROMPT: String =
        "TypeFree端侧引擎 | 任务:jev_copilot | 仅输出合规<jev>XML，含3策与求和100权重。"

    fun buildDictatePrompt(
        rawTranscript: String,
        dictionaryTerms: List<String> = emptyList(),
        toneHint: String = "",
        appName: String = "通用"
    ): LocalPromptPair {
        val sb = StringBuilder()
        val cleanTone = toneHint.trim().ifEmpty { "自然口语 — 保持自然通顺、简洁干净，适合日常即时通讯直接发送。" }
        val cleanApp = appName.trim().ifEmpty { "通用" }
        sb.append("<scene_and_tone>\n目标应用：").append(cleanApp).append("\n风格：").append(cleanTone).append("\n</scene_and_tone>\n")
        val topDict = normalizeTopDictionary(dictionaryTerms)
        if (topDict.isNotEmpty()) {
            sb.append("<personal_dictionary>\n").append(topDict.joinToString("\n")).append("\n</personal_dictionary>\n")
        }
        sb.append("<raw_speech_transcript>\n").append(rawTranscript.trim()).append("\n</raw_speech_transcript>\n\n请直接输出整理后的最终上屏文本（仅输出正文）：")
        return LocalPromptPair(
            systemPrompt = DICTATE_SYSTEM_PROMPT,
            userPrompt = sb.toString(),
            maxTokens = DICTATE_MAX_TOKENS,
            templateVersion = PROMPT_TEMPLATE_VERSION
        )
    }

    fun buildSpeakToEditPrompt(
        selectedText: String,
        voiceInstruction: String,
        dictionaryTerms: List<String> = emptyList(),
        toneHint: String = ""
    ): LocalPromptPair {
        val sb = StringBuilder()
        val cleanTone = toneHint.trim().ifEmpty { "清晰精炼 — 直奔主题，无废话。" }
        sb.append("<scene_and_tone>\n风格：").append(cleanTone).append("\n</scene_and_tone>\n")
        val topDict = normalizeTopDictionary(dictionaryTerms)
        if (topDict.isNotEmpty()) {
            sb.append("<personal_dictionary>\n").append(topDict.joinToString("\n")).append("\n</personal_dictionary>\n")
        }
        sb.append("<selected_text>\n").append(selectedText.trim()).append("\n</selected_text>\n\n")
            .append("<voice_instruction>\n").append(voiceInstruction.trim()).append("\n</voice_instruction>\n\n请直接输出替换后的文本结果：")
        return LocalPromptPair(
            systemPrompt = SPEAK_TO_EDIT_SYSTEM_PROMPT,
            userPrompt = sb.toString(),
            maxTokens = SPEAK_TO_EDIT_MAX_TOKENS,
            templateVersion = PROMPT_TEMPLATE_VERSION
        )
    }

    fun buildSentenceCompletionPrompt(
        cursorBeforeContext: String,
        toneHint: String = "",
        appName: String = "通用"
    ): LocalPromptPair {
        val clippedContext = cursorBeforeContext.takeLast(COMPLETION_CONTEXT_MAX_CHARS)
        val cleanTone = toneHint.trim().ifEmpty { "日常简洁 — 自然流畅，贴合聊天或办公习惯。" }
        val cleanApp = appName.trim().ifEmpty { "通用" }
        val userPrompt =
            "<scene_and_tone>\n目标应用：$cleanApp\n风格：$cleanTone\n</scene_and_tone>\n" +
                "<cursor_before_context>\n$clippedContext\n</cursor_before_context>\n\n" +
                "请直接输出自然续写的后半句（最多 20 字，不重复前文，不加引号）："
        return LocalPromptPair(
            systemPrompt = COMPLETION_SYSTEM_PROMPT,
            userPrompt = userPrompt,
            maxTokens = COMPLETION_MAX_TOKENS,
            templateVersion = PROMPT_TEMPLATE_VERSION
        )
    }

    fun buildTranslatePrompt(
        rawTranscript: String,
        targetLanguage: String,
        dictionaryTerms: List<String> = emptyList()
    ): LocalPromptPair {
        val cleanLang = targetLanguage.trim()
        val sb = StringBuilder()
        sb.append("<target_language>").append(cleanLang).append("</target_language>\n")
        sb.append("<scene_and_tone>\n风格：自然地道 — 母语级自然表达，符合对话语境。\n</scene_and_tone>\n")
        val topDict = normalizeTopDictionary(dictionaryTerms)
        if (topDict.isNotEmpty()) {
            sb.append("<personal_dictionary>\n").append(topDict.joinToString("\n")).append("\n</personal_dictionary>\n")
        }
        sb.append("<raw_speech_transcript>\n").append(rawTranscript.trim()).append("\n</raw_speech_transcript>\n\n请直接输出【").append(cleanLang).append("】译文：")
        return LocalPromptPair(
            systemPrompt = TRANSLATE_SYSTEM_PROMPT,
            userPrompt = sb.toString(),
            maxTokens = TRANSLATE_MAX_TOKENS,
            templateVersion = PROMPT_TEMPLATE_VERSION
        )
    }

    fun buildHelpMeWritePrompt(
        writingRequest: String,
        editorDraft: String = "",
        chatContext: String = "",
        dictionaryTerms: List<String> = emptyList(),
        toneHint: String = "",
        appName: String = "通用"
    ): LocalPromptPair {
        val sb = StringBuilder()
        val cleanTone = toneHint.trim().ifEmpty { "得体职场 — 礼貌平视、不卑微、简洁有条理。" }
        val cleanApp = appName.trim().ifEmpty { "通用" }
        sb.append("<scene_and_tone>\n目标应用：").append(cleanApp).append("\n风格：").append(cleanTone).append("\n</scene_and_tone>\n")
        val topDict = normalizeTopDictionary(dictionaryTerms)
        if (topDict.isNotEmpty()) {
            sb.append("<personal_dictionary>\n").append(topDict.joinToString("\n")).append("\n</personal_dictionary>\n")
        }
        val cleanDraft = editorDraft.trim()
        if (cleanDraft.isNotEmpty()) {
            sb.append("<editor_draft>\n").append(cleanDraft).append("\n</editor_draft>\n")
        }
        val cleanChat = chatContext.trim().takeLast(MAX_HELP_CHAT_CONTEXT_CHARS)
        if (cleanChat.isNotEmpty()) {
            sb.append("<chat_context>\n").append(cleanChat).append("\n</chat_context>\n")
        }
        sb.append("<writing_request>\n").append(writingRequest.trim()).append("\n</writing_request>\n\n请直接输出可直接发送的成稿正文：")
        return LocalPromptPair(
            systemPrompt = HELP_ME_WRITE_SYSTEM_PROMPT,
            userPrompt = sb.toString(),
            maxTokens = HELP_ME_WRITE_MAX_TOKENS,
            templateVersion = PROMPT_TEMPLATE_VERSION
        )
    }

    fun buildJevCopilotPrompt(
        chatContext: String,
        userBottomLine: String = "",
        domainHint: String = ""
    ): LocalPromptPair {
        val sb = StringBuilder()
        val cleanDomain = domainHint.trim().ifEmpty { "日常沟通" }
        sb.append("<domain_profile>\n战术域：").append(cleanDomain).append("\n</domain_profile>\n")
        val cleanChat = chatContext.trim().takeLast(MAX_JEV_CHAT_CONTEXT_CHARS)
        sb.append("<chat_context>\n").append(cleanChat).append("\n</chat_context>\n")
        val cleanBottomLine = userBottomLine.trim()
        if (cleanBottomLine.isNotEmpty()) {
            sb.append("<user_bottom_line>\n").append(cleanBottomLine).append("\n</user_bottom_line>\n")
        }
        sb.append(
            "\n请严格按以下 <jev> XML 结构输出：\n" +
                "<jev>\n<lv>1-9</lv>\n<tag>4-8字定性</tag>\n<subtext>38字内潜台词</subtext>\n" +
                "<opt label=\"4字鲜活战术词1\">策略1纯文本回复正文</opt>\n" +
                "<opt label=\"4字鲜活战术词2\">策略2纯文本回复正文</opt>\n" +
                "<opt label=\"4字鲜活战术词3\">策略3纯文本回复正文</opt>\n" +
                "<weights>W1,W2,W3</weights>\n</jev>"
        )
        return LocalPromptPair(
            systemPrompt = JEV_COPILOT_SYSTEM_PROMPT,
            userPrompt = sb.toString(),
            maxTokens = JEV_COPILOT_MAX_TOKENS,
            templateVersion = PROMPT_TEMPLATE_VERSION
        )
    }

    private fun normalizeTopDictionary(terms: List<String>): List<String> {
        if (terms.isEmpty()) return emptyList()
        return terms
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(MAX_LOCAL_DICT_TERMS)
            .toList()
    }
}
