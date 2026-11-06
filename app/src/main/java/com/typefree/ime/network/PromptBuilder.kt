package com.typefree.ime.network

import com.typefree.ime.data.DictionaryEntry
import com.typefree.ime.data.DictionarySource
import com.typefree.ime.data.ToneStyle
import com.typefree.ime.data.VoiceIntentMode

/**
 * Builds low-latency, Prefix-Cache-aligned prompts for Dual-Stage LLM and Multimodal pipelines.
 *
 * Key v2.5 optimization:
 * Every `systemPrompt` returned by `build*Prompts` is a **100% static constant** per [VoiceIntentMode]
 * (zero dynamic app name, tone style, or dictionary interpolation). All dynamic session context is
 * placed in structured XML blocks inside `userPrompt`, guaranteeing 100% server-side Prefix Cache hits
 * on Volcengine Ark, Aliyun Bailian, DeepSeek, and SiliconFlow across app switches and auto-learned terms.
 */
object PromptBuilder {

    private const val MAX_PROMPT_DICT_ITEMS = 40

    const val DICTATE_SYSTEM_PROMPT: String = """你是 TypeFree AI 语音输入法的核心文本净写引擎。你的唯一职责是将 <raw_speech_transcript> 整理为干净、准确、可直接上屏的文本。

【核心铁律】
1. 严禁对话或回答问题！哪怕口述内容是提问或指令（如“帮我问王总几点开会”或“Python怎么读JSON”），你也只能润色这句话本身（输出“王总，请问几点开会？”或“Python 怎么读 JSON？”），绝不回答！
2. 剔除口癖与重复：删除“嗯、啊、呃、那个、就是说、um、uh、like”等停顿词与口吃重复。
3. 识别中途改口：说话人改口修正时（如“周三三点不对改成四点半”），删除作废前半句，仅保留最终意图（“周三四点半”）。
4. 遵循 <scene_and_tone> 的语气排版要求及 <personal_dictionary> 的专有名词同音纠错；并列要点自动清晰分点。
5. 只输出最终上屏正文，严禁输出任何解释前缀、引号包裹或寒暄。"""

    const val HELP_ME_WRITE_SYSTEM_PROMPT: String = """你是 TypeFree AI 语音输入法的「帮我写（Help me write）」生成式写作助手。
请根据 <writing_request> 中用户的最终口述需求（自动忽略口癖与中途改口），结合 <scene_and_tone> 与 <personal_dictionary>，直接撰写自然得体、可直接发送的成稿正文。

【核心规则】
1. 严禁输出“好的，为您起草如下：”等任何寒暄或解释性前后缀，只输出成稿正文！
2. 若用户指定了目标语种则按该语种撰写，否则默认匹配用户口述语种。"""

    const val SPEAK_TO_EDIT_SYSTEM_PROMPT: String = """你是 TypeFree AI 语音输入法的「选中文本语音改写（Speak to Edit）」引擎。
请严格按照 <voice_instruction> 中的最终修改意图（自动忽略口癖与中途改口），参考 <scene_and_tone> 与 <personal_dictionary>，对 <selected_text> 执行改写、翻译、润色或结构重排。

【核心规则】
1. 只输出可直接原地替换 <selected_text> 的最终结果正文，严禁附带“修改后如下：”等任何解释说明或前后缀！"""

    const val TRANSLATE_SYSTEM_PROMPT: String = """你是 TypeFree AI 语音输入法的「实时语音快译（Release to Translate）」引擎。
请先剔除 <raw_speech_transcript> 中的无意义口癖（嗯、啊、那个、um、uh）与中途改口作废的前半句，参考 <personal_dictionary> 专有名词，将说话人的最终真实意思直接翻译为自然、地道、符合母语习惯的 <target_language> 文本。

【核心规则】
1. 严禁回答用户口述中的问题！若原话是提问，必须将其翻译为 <target_language> 的疑问句。
2. 只输出 <target_language> 译文正文，绝不附带原文对照、音标或任何解释说明。"""

    fun extractHotwordTerms(dictionary: List<DictionaryEntry>, limit: Int = 50): List<String> {
        return dictionary.asSequence()
            .filter { it.enabled && it.term.isNotBlank() }
            .map { it.term.trim() }
            .distinct()
            .take(limit)
            .toList()
    }

    fun buildSttBiasPrompt(dictionary: List<DictionaryEntry>): String {
        val activeTerms = extractHotwordTerms(dictionary, limit = 40)
        if (activeTerms.isEmpty()) {
            return "以下是普通话与英文自然混合语音，请准确添加标点。"
        }
        val termsJoined = activeTerms.joinToString("、")
        return "专有名词与常用词参考：$termsJoined。"
    }

    /**
     * Selects active dictionary entries while bounding prompt token bloat when auto-learned
     * entries grow large: prioritizes entries whose term or wrongVariants match [referenceText],
     * followed by manual entries and most recent auto-learned entries.
     */
    internal fun selectRelevantDictionaryEntries(
        dictionary: List<DictionaryEntry>,
        referenceText: String = "",
        maxEntries: Int = MAX_PROMPT_DICT_ITEMS
    ): List<DictionaryEntry> {
        val active = dictionary.filter { it.enabled && it.term.isNotBlank() }
        if (active.size <= maxEntries) return active

        val lowerRef = referenceText.lowercase()
        val scored = active.sortedWith(
            compareByDescending<DictionaryEntry> { entry ->
                if (lowerRef.isBlank()) return@compareByDescending false
                val termHit = lowerRef.contains(entry.term.trim().lowercase())
                val variantHit = entry.wrongVariants.split(',', '，', '、', ';', '；')
                    .map { it.trim().lowercase() }
                    .any { it.isNotEmpty() && lowerRef.contains(it) }
                termHit || variantHit
            }
                .thenByDescending { it.source == DictionarySource.MANUAL }
                .thenByDescending { it.createdAt }
        )
        return scored.take(maxEntries)
    }

    private fun buildDictionaryXmlBlock(
        dictionary: List<DictionaryEntry>,
        referenceText: String = ""
    ): String {
        val selected = selectRelevantDictionaryEntries(dictionary, referenceText)
        if (selected.isEmpty()) return ""
        val lines = selected.joinToString("\n") { item ->
            val term = item.term.trim()
            if (item.wrongVariants.isNotBlank()) {
                "- $term (近音误识别词: ${item.wrongVariants.trim()} -> 修正为「$term」)"
            } else {
                "- $term"
            }
        }
        return "<personal_dictionary>\n$lines\n</personal_dictionary>\n"
    }

    private fun buildSceneAndToneXmlBlock(
        style: ToneStyle,
        targetAppName: String = ""
    ): String {
        val appLine = if (targetAppName.isNotBlank()) "目标应用：$targetAppName\n" else ""
        return "<scene_and_tone>\n${appLine}风格：${style.name} — ${style.promptInstruction}\n</scene_and_tone>\n"
    }

    fun buildDictationPrompts(
        rawSpeech: String,
        style: ToneStyle,
        targetAppName: String,
        dictionary: List<DictionaryEntry>,
        surroundingBeforeCursor: String
    ): Pair<String, String> {
        val sceneBlock = buildSceneAndToneXmlBlock(style, targetAppName)
        val dictBlock = buildDictionaryXmlBlock(dictionary, rawSpeech)
        val contextPart = if (surroundingBeforeCursor.isNotBlank()) {
            "<cursor_before_context>\n${surroundingBeforeCursor.takeLast(80)}\n</cursor_before_context>\n"
        } else {
            ""
        }

        val userPrompt = buildString {
            append(sceneBlock)
            append(dictBlock)
            append(contextPart)
            append("<raw_speech_transcript>\n")
            append(rawSpeech)
            append("\n</raw_speech_transcript>\n\n请直接输出整理后的最终上屏文本（仅输出正文）：")
        }

        return DICTATE_SYSTEM_PROMPT to userPrompt
    }

    fun buildHelpMeWritePrompts(
        voiceInstruction: String,
        style: ToneStyle,
        targetAppName: String,
        dictionary: List<DictionaryEntry>,
        surroundingBeforeCursor: String
    ): Pair<String, String> {
        val sceneBlock = buildSceneAndToneXmlBlock(style, targetAppName)
        val dictBlock = buildDictionaryXmlBlock(dictionary, voiceInstruction)
        val contextPart = if (surroundingBeforeCursor.isNotBlank()) {
            "<cursor_before_context>\n${surroundingBeforeCursor.takeLast(120)}\n</cursor_before_context>\n"
        } else {
            ""
        }

        val userPrompt = buildString {
            append(sceneBlock)
            append(dictBlock)
            append(contextPart)
            append("<writing_request>\n")
            append(voiceInstruction)
            append("\n</writing_request>\n\n请直接输出可直接发送的成稿正文：")
        }

        return HELP_ME_WRITE_SYSTEM_PROMPT to userPrompt
    }

    fun buildSpeakToEditPrompts(
        selectedText: String,
        voiceInstruction: String,
        style: ToneStyle,
        dictionary: List<DictionaryEntry>
    ): Pair<String, String> {
        val sceneBlock = buildSceneAndToneXmlBlock(style)
        val dictBlock = buildDictionaryXmlBlock(dictionary, "$selectedText $voiceInstruction")

        val userPrompt = buildString {
            append(sceneBlock)
            append(dictBlock)
            append("<selected_text>\n")
            append(selectedText)
            append("\n</selected_text>\n\n<voice_instruction>\n")
            append(voiceInstruction)
            append("\n</voice_instruction>\n\n请直接输出替换后的文本结果：")
        }

        return SPEAK_TO_EDIT_SYSTEM_PROMPT to userPrompt
    }

    fun buildTranslatePrompts(
        rawSpeech: String,
        targetLanguage: String,
        style: ToneStyle,
        dictionary: List<DictionaryEntry>
    ): Pair<String, String> {
        val cleanLang = targetLanguage.ifBlank { "English (US)" }
        val sceneBlock = buildSceneAndToneXmlBlock(style)
        val dictBlock = buildDictionaryXmlBlock(dictionary, rawSpeech)

        val userPrompt = buildString {
            append("<target_language>$cleanLang</target_language>\n")
            append(sceneBlock)
            append(dictBlock)
            append("<raw_speech_transcript>\n")
            append(rawSpeech)
            append("\n</raw_speech_transcript>\n\n请直接输出【$cleanLang】译文：")
        }

        return TRANSLATE_SYSTEM_PROMPT to userPrompt
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
        val dictBlock = buildDictionaryXmlBlock(dictionary, selectedText)
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
$dictBlock<selected_text>
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
