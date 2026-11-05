package com.typefree.ime.data

import com.typefree.ime.network.AiPipelineEngine
import com.typefree.ime.network.PromptBuilder
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class V2RepositoryAndPromptsTest {

    private val defaultStyle = DefaultPresets.defaultStyles().first()
    private val sampleDict = DefaultPresets.defaultDictionary()

    @Test
    fun `extractAutoLearnedReplacement detects Chinese homophone correction`() {
        val diff = AppRepository.extractAutoLearnedReplacement(
            beforeText = "今天我们开始测试太普利斯语音输入法。",
            afterText = "今天我们开始测试TypeFree语音输入法。"
        )
        assertNotNull(diff)
        assertEquals("太普利斯", diff!!.first)
        assertEquals("TypeFree", diff.second)
    }

    @Test
    fun `extractAutoLearnedReplacement expands to ASCII word boundaries`() {
        val diff = AppRepository.extractAutoLearnedReplacement(
            beforeText = "Please connect to deep seek endpoint now.",
            afterText = "Please connect to DeepSeek endpoint now."
        )
        assertNotNull(diff)
        assertEquals("deep seek", diff!!.first)
        assertEquals("DeepSeek", diff.second)
    }

    @Test
    fun `extractAutoLearnedReplacement ignores punctuation-only or massive rewrites`() {
        val punctOnly = AppRepository.extractAutoLearnedReplacement(
            beforeText = "你好世界，",
            afterText = "你好世界。"
        )
        assertNull(punctOnly)

        val massiveRewrite = AppRepository.extractAutoLearnedReplacement(
            beforeText = "短句",
            afterText = "这是一整段完全重写的超长内容已经远远超过了单个专有名词纠错的长度上限范围"
        )
        assertNull(massiveRewrite)
    }

    @Test
    fun `pruneHistoryByRetention filters records accurately across policies`() {
        val now = 1_700_000_000_000L
        val hourMs = 3600L * 1000L
        val dayMs = 24L * hourMs
        val records = listOf(
            HistoryRecord(id = "r_2h", timestamp = now - 2 * hourMs, polishedText = "2h ago"),
            HistoryRecord(id = "r_3d", timestamp = now - 3 * dayMs, polishedText = "3d ago"),
            HistoryRecord(id = "r_15d", timestamp = now - 15 * dayMs, polishedText = "15d ago"),
            HistoryRecord(id = "r_45d", timestamp = now - 45 * dayMs, polishedText = "45d ago")
        )

        assertEquals(0, AppRepository.pruneHistoryByRetention(records, HistoryRetention.NEVER, now).size)
        assertEquals(4, AppRepository.pruneHistoryByRetention(records, HistoryRetention.FOREVER, now).size)
        assertEquals(listOf("r_2h"), AppRepository.pruneHistoryByRetention(records, HistoryRetention.HOURS_24, now).map { it.id })
        assertEquals(listOf("r_2h", "r_3d"), AppRepository.pruneHistoryByRetention(records, HistoryRetention.DAYS_7, now).map { it.id })
        assertEquals(listOf("r_2h", "r_3d", "r_15d"), AppRepository.pruneHistoryByRetention(records, HistoryRetention.DAYS_30, now).map { it.id })
    }

    @Test
    fun `buildHelpMeWritePrompts and buildTranslatePrompts contain distinct mode instructions`() {
        val (hmwSys, hmwUsr) = PromptBuilder.buildHelpMeWritePrompts(
            voiceInstruction = "帮我写一封邮件问王总周五下午三点有没有空讨论预算",
            style = defaultStyle,
            targetAppName = "Gmail",
            dictionary = sampleDict,
            surroundingBeforeCursor = "Hi Wang,"
        )
        assertTrue(hmwSys.contains("Help me write"))
        assertTrue(hmwSys.contains("Gmail"))
        assertTrue(hmwUsr.contains("<writing_request>"))
        assertTrue(hmwUsr.contains("Hi Wang,"))

        val (trSys, trUsr) = PromptBuilder.buildTranslatePrompts(
            rawSpeech = "嗯明天下午四点，哦不对五点见吧",
            targetLanguage = "日本語",
            style = defaultStyle,
            dictionary = sampleDict
        )
        assertTrue(trSys.contains("日本語"))
        assertTrue(trSys.contains("严禁回答用户口述中的问题"))
        assertTrue(trUsr.contains("日本語"))

        val mmTranslate = PromptBuilder.buildMultimodalDirectPrompt(
            intentMode = VoiceIntentMode.TRANSLATE,
            selectedText = "",
            translateTargetLanguage = "Español",
            style = defaultStyle,
            targetAppName = "WhatsApp",
            dictionary = sampleDict,
            surroundingBeforeCursor = ""
        )
        assertTrue(mmTranslate.contains("Español"))
        assertTrue(mmTranslate.contains("[EMPTY_SPEECH]"))
    }

    @Test
    fun `AiPipelineEngine executes HELP_ME_WRITE and TRANSLATE modes end-to-end`() = runBlocking {
        val tempWav = File.createTempFile("v2_test_", ".wav").apply {
            writeBytes(ByteArray(128) { 1 })
            deleteOnExit()
        }
        var capturedLlmBody = ""
        val mockClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val req = chain.request()
                val path = req.url.encodedPath
                val jsonMedia = "application/json; charset=utf-8".toMediaType()
                val bodyStr = if (path.endsWith("/audio/transcriptions")) {
                    """{"text":"写封简短邮件确认周五下午三点评审"}"""
                } else {
                    val buf = okio.Buffer()
                    req.body?.writeTo(buf)
                    capturedLlmBody = buf.readUtf8()
                    """{"choices":[{"message":{"role":"assistant","content":"王总您好，跟您确认本周五下午 15:00 进行项目评审，届时准时参会。"}}]}"""
                }
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(bodyStr.toResponseBody(jsonMedia))
                    .build()
            })
            .build()

        val engine = AiPipelineEngine(mockClient)
        val settings = AppSettings(
            pipelineMode = PipelineMode.DUAL_STAGE,
            sttConfig = SttConfig(apiKey = "sk-test-stt"),
            llmConfig = LlmConfig(enabled = false, apiKey = "sk-test-llm") // Even with enabled=false, HELP_ME_WRITE forces generative LLM!
        )

        val hmwRes = engine.processVoiceAudio(
            wavFile = tempWav,
            settings = settings,
            activeStyle = defaultStyle,
            targetAppName = "Outlook",
            selectedText = "",
            surroundingBeforeCursor = "",
            intentMode = VoiceIntentMode.HELP_ME_WRITE
        )
        assertTrue(hmwRes.isSuccess)
        assertTrue(hmwRes.getOrThrow().finalOutput.contains("15:00"))
        assertTrue(capturedLlmBody.contains("Help me write"))

        val trRes = engine.processVoiceAudio(
            wavFile = tempWav,
            settings = settings,
            activeStyle = defaultStyle,
            targetAppName = "Telegram",
            selectedText = "",
            surroundingBeforeCursor = "",
            intentMode = VoiceIntentMode.TRANSLATE,
            translateTargetLanguage = "English (US)"
        )
        assertTrue(trRes.isSuccess)
        assertTrue(capturedLlmBody.contains("Release to Translate"))
        assertTrue(capturedLlmBody.contains("English (US)"))
    }

    @Test
    fun `computeUsageStats produces realistic WPM and personalization metrics`() {
        val settings = AppSettings()
        val history = listOf(
            HistoryRecord(polishedText = "这是一段测试语音整理后的高质量文本内容，大约三十多个字左右。", durationMs = 6000L, styleName = "社交聊天"),
            HistoryRecord(polishedText = "第二条职场协作消息，已经清理了所有口头禅与停顿。", durationMs = 5000L, styleName = "职场干练")
        )
        val stats = AppRepository.computeUsageStats(settings, history)
        assertEquals(2, stats.totalDictations)
        assertTrue(stats.averageWpm in 135..280)
        assertTrue(stats.savedMinutes >= 1)
        assertTrue(stats.overallPersonalizationPercent in 18..100)
        assertEquals(4, stats.categoryProgress.size)
    }
}
