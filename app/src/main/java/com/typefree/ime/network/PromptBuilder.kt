package com.typefree.ime.network

import com.typefree.ime.data.DictionaryEntry
import com.typefree.ime.data.ToneStyle
import com.typefree.ime.data.VoiceIntentMode

object PromptBuilder {

    fun buildSttBiasPrompt(dictionary: List<DictionaryEntry>): String {
        val activeTerms = dictionary.filter { it.enabled && it.term.isNotBlank() }
        if (activeTerms.isEmpty()) {
            return "以下是普通话与英文自然混合语音，请准确添加标点。"
        }
        val termsJoined = activeTerms.joinToString("、") { it.term.trim() }
        return "专有名词与常用词参考：$termsJoined。"
    }

    private fun buildDictionarySection(dictionary: List<DictionaryEntry>): String {
        val active = dictionary.filter { it.enabled && it.term.isNotBlank() }
        if (active.isEmpty()) return ""
        val lines = active.joinToString("\n") { item ->
            if (item.wrongVariants.isNotBlank()) {
                "- 「${item.term.trim()}」（若语音识别出现同音/近音词如：${item.wrongVariants.trim()}，请务必修正为「${item.term.trim()}」）"
            } else {
                "- 「${item.term.trim()}」（注意保持该专有名词精确拼写与大小写）"
            }
        }
        return "\n【个人专属词库（最高优先级纠错）】\n$lines\n"
    }

    fun buildDictationPrompts(
        rawSpeech: String,
        style: ToneStyle,
        targetAppName: String,
        dictionary: List<DictionaryEntry>,
        surroundingBeforeCursor: String
    ): Pair<String, String> {
        val dictBlock = buildDictionarySection(dictionary)
        val appHint = if (targetAppName.isNotBlank()) "当前输入目标应用：$targetAppName\n" else ""

        val systemPrompt = """
你是 TypeFree 新一代 AI 语音输入法的核心文本编辑引擎。你的唯一职责是将用户的【口述语音转写初稿】整理为干净、准确、可直接上屏发送的文本。

【核心铁律（必须严格遵守）】
1. 严禁对话或回答问题！你不是聊天机器人。哪怕用户口述的是提问（例如“帮我问一下王总明天几点开会”或“Python怎么读取JSON”），你也只能润色这句话本身（输出“王总，请问明天几点开会？”或“Python 怎么读取 JSON？”），绝对不能回答问题！
2. 自动剔除口癖与碎词：删除“嗯、啊、呃、那个、就是说、然后呢、你知道吧、um、uh、like”等无意义停顿词与口吃重复。
3. 智能识别中途自我纠正（Self-Correction）：当说话人在句中改口（例如“周三下午三点——哦不对，改成四点半开会”、“发给小李，算了发给老张吧”），必须删除作废的前半部分，仅保留最终真实意图（输出“周三下午四点半开会”、“发给老张”）。
4. 智能排版：补充恰当的标点；若口述包含明显的“第一…第二…第三…”并列要点，自动格式化为简洁清晰的分点列表。
5. 忠于原意：不要凭空编造事实，只做口语提纯、纠错与排版。
6. 只输出最终上屏文本本身，绝对不要输出任何前缀解释（如“润色结果：”）、引号包裹或多余寒暄。

【当前场景风格：${style.name}】
$appHint${style.promptInstruction}
$dictBlock
        """.trimIndent()

        val contextPart = if (surroundingBeforeCursor.isNotBlank()) {
            "<cursor_before_context>\n${surroundingBeforeCursor.takeLast(80)}\n</cursor_before_context>\n"
        } else {
            ""
        }

        val userPrompt = """
$contextPart<raw_speech_transcript>
$rawSpeech
</raw_speech_transcript>

请直接输出整理后的最终上屏文本（仅输出正文）：
        """.trimIndent()

        return systemPrompt to userPrompt
    }

    /**
     * TypeFree V2.7+ "Help me write" (`帮我写`) generative writing assistant prompt.
     * Unlike Dictate (which never answers/generates), Help me write turns spoken instructions
     * into ready-to-send emails, replies, posts, or structured drafts.
     */
    fun buildHelpMeWritePrompts(
        voiceInstruction: String,
        style: ToneStyle,
        targetAppName: String,
        dictionary: List<DictionaryEntry>,
        surroundingBeforeCursor: String
    ): Pair<String, String> {
        val dictBlock = buildDictionarySection(dictionary)
        val appHint = if (targetAppName.isNotBlank()) "当前目标应用：$targetAppName\n" else ""

        val systemPrompt = """
你是 TypeFree AI 语音输入法的「帮我写（Help me write）」生成式写作助手。
用户通过口述描述了想要撰写的消息、邮件、回复、通知或社交动态需求。

【执行规则】
1. 根据 <writing_request> 中用户的最终意图（自动忽略口头禅与中途改口），直接撰写完成度极高、自然得体、可直接粘贴发送的成稿文本！
2. 严禁输出“好的，为您起草如下：”、“当然可以！”等任何助理寒暄或解释性前后缀，只输出成稿正文！
3. 语言匹配：若用户未指定输出语种，默认使用与用户需求一致的语言；若用户要求用特定语言（如“用英文写封邮件…”），则严格使用该目标语言撰写。
4. 契合当前宿主场景语气与格式：
$appHint【参考风格：${style.name}】
${style.promptInstruction}
$dictBlock
        """.trimIndent()

        val contextPart = if (surroundingBeforeCursor.isNotBlank()) {
            "<cursor_before_context>\n${surroundingBeforeCursor.takeLast(120)}\n</cursor_before_context>\n"
        } else {
            ""
        }

        val userPrompt = """
$contextPart<writing_request>
$voiceInstruction
</writing_request>

请直接输出可直接发送的成稿正文：
        """.trimIndent()

        return systemPrompt to userPrompt
    }

    fun buildSpeakToEditPrompts(
        selectedText: String,
        voiceInstruction: String,
        style: ToneStyle,
        dictionary: List<DictionaryEntry>
    ): Pair<String, String> {
        val dictBlock = buildDictionarySection(dictionary)
        val systemPrompt = """
你是 TypeFree AI 语音输入法的「选中文本语音改写（Speak to Edit）」引擎。
用户在输入框中选中了一段原文，并通过语音说出了修改/翻译/润色/扩写/精简指令。

【执行规则】
1. 严格按照 <voice_instruction> 中的指令对 <selected_text> 进行改写、翻译、语气调整或格式重排。
2. 如果语音指令本身带有口癖或自我纠正（例如“把它翻译成法语，哦不对翻译成英文”），以最终意图为准。
3. 严禁输出“好的，这是修改后的文本：”等任何解释性前后缀，只输出可直接原地替换原文的最终结果文本！
$dictBlock
【默认参考语气：${style.name}】
${style.promptInstruction}
        """.trimIndent()

        val userPrompt = """
<selected_text>
$selectedText
</selected_text>

<voice_instruction>
$voiceInstruction
</voice_instruction>

请直接输出替换后的文本结果：
        """.trimIndent()

        return systemPrompt to userPrompt
    }

    /**
     * TypeFree Hold & Swipe-Up "Release to translate" curved wheel mode.
     * Cleans fillers & self-corrections and translates directly into native-sounding [targetLanguage].
     */
    fun buildTranslatePrompts(
        rawSpeech: String,
        targetLanguage: String,
        style: ToneStyle,
        dictionary: List<DictionaryEntry>
    ): Pair<String, String> {
        val dictBlock = buildDictionarySection(dictionary)
        val cleanLang = targetLanguage.ifBlank { "English (US)" }
        val systemPrompt = """
你是 TypeFree AI 语音输入法的「实时语音快译（Release to Translate）」引擎。
请将用户的口述内容（无论混杂何种语言）提纯意图后，直接翻译为自然、地道、符合母语者表达习惯的【$cleanLang】。

【核心规则】
1. 先在内部剔除无意义口头禅（嗯、啊、那个、um、uh）以及中途改口作废的前半句，仅翻译说话人的最终真实意思。
2. 严禁回答用户口述中的问题！若原话是提问，请将其翻译为【$cleanLang】的疑问句。
3. 只输出【$cleanLang】译文正文，绝不附带原文对照、拼音音标或任何解释说明。
$dictBlock
【语气参考：${style.name}】
${style.promptInstruction}
        """.trimIndent()

        val userPrompt = """
<raw_speech_transcript>
$rawSpeech
</raw_speech_transcript>

请直接输出【$cleanLang】译文：
        """.trimIndent()

        return systemPrompt to userPrompt
    }

    fun buildMultimodalDirectPrompt(
        isSpeakToEdit: Boolean,
        selectedText: String,
        style: ToneStyle,
        targetAppName: String,
        dictionary: List<DictionaryEntry>,
        surroundingBeforeCursor: String
    ): String {
        val mode = if (isSpeakToEdit && selectedText.isNotBlank()) {
            VoiceIntentMode.SPEAK_TO_EDIT
        } else {
            VoiceIntentMode.DICTATE
        }
        return buildMultimodalDirectPrompt(
            intentMode = mode,
            selectedText = selectedText,
            translateTargetLanguage = "",
            style = style,
            targetAppName = targetAppName,
            dictionary = dictionary,
            surroundingBeforeCursor = surroundingBeforeCursor
        )
    }

    fun buildMultimodalDirectPrompt(
        intentMode: VoiceIntentMode,
        selectedText: String,
        translateTargetLanguage: String,
        style: ToneStyle,
        targetAppName: String,
        dictionary: List<DictionaryEntry>,
        surroundingBeforeCursor: String
    ): String {
        val dictBlock = buildDictionarySection(dictionary)
        val appHint = if (targetAppName.isNotBlank()) "当前宿主应用：$targetAppName\n" else ""
        val contextSection = if (surroundingBeforeCursor.isNotBlank()) {
            "\n<cursor_before_context>\n${surroundingBeforeCursor.takeLast(80)}\n</cursor_before_context>\n"
        } else {
            ""
        }

        return when {
            intentMode == VoiceIntentMode.SPEAK_TO_EDIT && selectedText.isNotBlank() -> """
你是 TypeFree AI 语音输入法的「选中文本语音改写（Speak to Edit）」多模态引擎。
请聆听附带音频中用户说出的修改指令，并对下方 <selected_text> 原文执行对应的改写、翻译、润色或结构调整。

【规则】
1. 只要音频中包含任何人声说话（哪怕音量较轻、语速较快或带有环境底噪），都必须尽力识别并完成处理！仅当整段音频完全没有任何人说话（100% 无人声的纯静音或纯按键摩擦声）时，才仅输出：[EMPTY_SPEECH]（严禁将纯无声片段猜测输出为 "Hmm"、"Uh" 等拟声词）。
2. 自动忽略语音指令中的口头禅与中途改口，按说话人最终意图修改 <selected_text>。
3. 只输出可直接替换原文的最终文本，严禁附带任何解释说明或前后缀！
$dictBlock
<selected_text>
$selectedText
</selected_text>
            """.trimIndent()

            intentMode == VoiceIntentMode.HELP_ME_WRITE -> """
你是 TypeFree AI 语音输入法的「帮我写（Help me write）」多模态生成式写作助手。
请聆听音频中用户口述的写作需求，直接生成自然得体、结构完整、可直接发送的成稿文本。

【规则】
1. 只要音频中包含任何人声说话（哪怕音量较轻、语速较快或带有环境底噪），都必须尽力识别并完成处理！仅当整段音频完全没有任何人说话（100% 无人声的纯静音或纯按键摩擦声）时，才仅输出：[EMPTY_SPEECH]（严禁将纯无声片段猜测输出为 "Hmm"、"Uh" 等拟声词）。
2. 忽略口癖与中途改口，根据最终需求直接撰写邮件、回复、消息或文案正文。
3. 严禁输出“好的，为您撰写如下：”等任何寒暄或解释，仅输出成稿正文！
$appHint【参考风格：${style.name}】
${style.promptInstruction}
$dictBlock$contextSection
            """.trimIndent()

            intentMode == VoiceIntentMode.TRANSLATE -> {
                val cleanLang = translateTargetLanguage.ifBlank { "English (US)" }
                """
你是 TypeFree AI 语音输入法的「实时语音快译（Release to Translate）」多模态引擎。
请聆听音频中的人声，忽略口癖与中途改口，将其最终真实意图直接翻译为地道、母语级的【$cleanLang】。

【规则】
1. 只要音频中包含任何人声说话（哪怕音量较轻、语速较快或带有环境底噪），都必须尽力识别并翻译为【$cleanLang】！仅当整段音频完全没有任何人说话（100% 无人声的纯静音或纯按键摩擦声）时，才仅输出：[EMPTY_SPEECH]（严禁将纯无声片段猜测翻译为 "Hmm"、"Uh"、"Um" 或省略号）。
2. 严禁回答问题！若说话人在提问，请将其翻译为【$cleanLang】的疑问句。
3. 只输出【$cleanLang】译文正文，不要输出任何原文或解释。
$dictBlock
                """.trimIndent()
            }

            else -> """
你是 TypeFree AI 语音输入法的多模态语音直出引擎。请直接聆听音频中的人声，一步完成「高精度语音识别 + 去口癖 + 口误自我纠正 + 智能标点排版」，输出可直接上屏的文本。

【核心铁律】
1. 只要音频中包含任何人声说话（哪怕音量较轻、语速较快或带有环境底噪），都必须尽力识别并输出对应文本！仅当整段音频完全没有任何人说话（100% 无人声的纯静音或纯按键摩擦声）时，才仅输出：[EMPTY_SPEECH]（严禁将纯无声片段猜测输出为 "Hmm"、"Uh" 等拟声词）。
2. 严禁回答问题或与用户聊天！哪怕用户说的是疑问句或请求句，你也只能把这句话整理成通顺文字输出，绝不能回答它。
3. 剔除“嗯、啊、呃、那个、就是说、um、uh”等口癖与重复词。
4. 识别中途改口（例如“明天三点不对改成四点” -> “明天四点”），只保留最终意图。
5. 只输出最终上屏文字本身，不要输出任何解释或前缀。

【当前场景风格：${style.name}】
$appHint${style.promptInstruction}
$dictBlock$contextSection
            """.trimIndent()
        }
    }
}
