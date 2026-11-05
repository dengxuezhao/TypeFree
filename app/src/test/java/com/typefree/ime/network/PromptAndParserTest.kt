package com.typefree.ime.network

import com.typefree.ime.data.DefaultPresets
import com.typefree.ime.data.DictionaryEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptAndParserTest {

    @Test
    fun `buildSttBiasPrompt includes only enabled dictionary entries`() {
        val dict = listOf(
            DictionaryEntry(term = "Antigravity", enabled = true),
            DictionaryEntry(term = "DisabledTerm", enabled = false),
            DictionaryEntry(term = "DeepSeek", enabled = true)
        )
        val prompt = PromptBuilder.buildSttBiasPrompt(dict)
        assertTrue(prompt.contains("Antigravity"))
        assertTrue(prompt.contains("DeepSeek"))
        assertFalse(prompt.contains("DisabledTerm"))
    }

    @Test
    fun `buildDictationPrompts enforces non-chatbot guard and personal dictionary aliases`() {
        val style = DefaultPresets.defaultStyles().first { it.id == "work_collab" }
        val dict = listOf(
            DictionaryEntry(
                term = "TypeFree",
                wrongVariants = "太普利斯",
                enabled = true
            )
        )
        val (sys, usr) = PromptBuilder.buildDictationPrompts(
            rawSpeech = "嗯那个明天三点不对四点讨论太普利斯方案",
            style = style,
            targetAppName = "飞书",
            dictionary = dict,
            surroundingBeforeCursor = "各位同事，"
        )
        assertTrue(sys.contains("严禁对话或回答问题"))
        assertTrue(sys.contains("飞书"))
        assertTrue(sys.contains("太普利斯"))
        assertTrue(sys.contains("TypeFree"))
        assertTrue(usr.contains("<cursor_before_context>"))
        assertTrue(usr.contains("嗯那个明天三点不对四点讨论太普利斯方案"))
    }

    @Test
    fun `buildSpeakToEditPrompts wraps selected text and voice instruction`() {
        val style = DefaultPresets.defaultStyles().first()
        val (sys, usr) = PromptBuilder.buildSpeakToEditPrompts(
            selectedText = "今天没空开会，改天吧",
            voiceInstruction = "把语气改得更礼貌正式一点",
            style = style,
            dictionary = emptyList()
        )
        assertTrue(sys.contains("Speak to Edit"))
        assertTrue(usr.contains("<selected_text>\n今天没空开会，改天吧\n</selected_text>"))
        assertTrue(usr.contains("<voice_instruction>\n把语气改得更礼貌正式一点\n</voice_instruction>"))
    }

    @Test
    fun `parseSttResponse strips SenseVoice emotion and language special tokens`() {
        val senseVoiceJson = """{"text":"<|zh|><|NEUTRAL|><|Speech|><|withitn|>今天下午三点开会。"}"""
        assertEquals(
            "今天下午三点开会。",
            AiPipelineEngine.parseSttResponse(senseVoiceJson)
        )
    }

    @Test
    fun `parseOpenAiChatResponse strips reasoning think blocks cleanly`() {
        val r1Json = """
            {
              "choices": [
                {
                  "message": {
                    "role": "assistant",
                    "content": "<think>\n用户改口把两点改成了三点半，去掉嗯那个。\n</think>\n\n明天下午三点半开会。"
                  }
                }
              ]
            }
        """.trimIndent()
        assertEquals(
            "明天下午三点半开会。",
            AiPipelineEngine.parseOpenAiChatResponse(r1Json)
        )
    }

    @Test
    fun `parseGeminiResponse skips thought parts and extracts final text`() {
        val geminiJson = """
            {
              "candidates": [
                {
                  "content": {
                    "parts": [
                      { "thought": true, "text": "Internal reasoning step..." },
                      { "text": "王总，明天下午四点方便同步一下 API 方案吗？" }
                    ]
                  }
                }
              ]
            }
        """.trimIndent()
        assertEquals(
            "王总，明天下午四点方便同步一下 API 方案吗？",
            AiPipelineEngine.parseGeminiResponse(geminiJson)
        )
    }

    @Test
    fun `silence hallucination detector catches Whisper subtitle artifacts`() {
        assertTrue(AiPipelineEngine.isHallucinatedSilence("字幕由 Amara.org 社区提供"))
        assertTrue(AiPipelineEngine.isHallucinatedSilence("Thank you for watching!"))
        assertTrue(AiPipelineEngine.isHallucinatedSilence("   "))
        assertFalse(AiPipelineEngine.isHallucinatedSilence("帮我发一条消息给产品经理"))
    }

    @Test
    fun `local endpoint detection allows keyless LAN Ollama and whisper servers`() {
        assertTrue(AiPipelineEngine.isLocalEndpoint("http://192.168.1.100:11434/v1"))
        assertTrue(AiPipelineEngine.isLocalEndpoint("http://localhost:8080/v1"))
        assertTrue(AiPipelineEngine.isLocalEndpoint("http://10.0.2.2:11434/v1"))
        assertFalse(AiPipelineEngine.isLocalEndpoint("https://api.deepseek.com/v1"))
    }
}
