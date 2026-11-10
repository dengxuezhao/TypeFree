package com.typefree.ime.ui

import com.typefree.ime.data.ApiPresets
import com.typefree.ime.data.AppSettings
import com.typefree.ime.data.DictionaryEntry
import com.typefree.ime.data.HistoryRecord
import com.typefree.ime.data.InputScheme
import com.typefree.ime.data.LlmConfig
import com.typefree.ime.data.MultimodalConfig
import com.typefree.ime.data.MultimodalProtocol
import com.typefree.ime.data.PipelineMode
import com.typefree.ime.data.SttConfig
import com.typefree.ime.ui.screens.ApiConfigBackupHelper
import com.typefree.ime.ui.screens.SetupStatsHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigImportExportTest {

    @Test
    fun `export and import full settings round-trip preserves all fields`() {
        val original = AppSettings(
            pipelineMode = PipelineMode.MULTIMODAL_DIRECT,
            sttConfig = SttConfig(
                presetId = "siliconflow",
                baseUrl = "https://api.siliconflow.cn/v1",
                apiKey = "sk-stt-secret-123",
                model = "FunAudioLLM/SenseVoiceSmall",
                language = "zh"
            ),
            llmConfig = LlmConfig(
                enabled = true,
                presetId = "deepseek",
                baseUrl = "https://api.deepseek.com/v1",
                apiKey = "sk-llm-secret-456",
                model = "deepseek-chat",
                temperature = 0.35f
            ),
            multimodalConfig = MultimodalConfig(
                presetId = "gemini",
                protocol = MultimodalProtocol.GEMINI_NATIVE,
                baseUrl = "https://generativelanguage.googleapis.com/v1beta",
                apiKey = "AIzaSySecret789",
                model = "gemini-2.5-flash",
                temperature = 0.15f
            ),
            hapticFeedback = false,
            useSurroundingContext = false,
            keyboardHeightLevel = 2,
            liveWriteToEditor = false,
            inputScheme = InputScheme.RIME_DOUBLE_PINYIN_FLYPY,
            fixedCandidateOrder = false,
            predictionEnabled = false,
            aiCompletionEnabled = true,
            dictionary = listOf(
                DictionaryEntry(term = "TypeFree", wrongVariants = "太普利斯", note = "产品名")
            )
        )

        val json = ApiConfigBackupHelper.exportSettingsJson(original, includeSecrets = true)
        assertTrue(json.contains("sk-stt-secret-123"))
        assertTrue(json.contains("AIzaSySecret789"))

        val imported = ApiConfigBackupHelper.parseAndMergeConfigJson(json, AppSettings()).getOrThrow()
        assertEquals(PipelineMode.MULTIMODAL_DIRECT, imported.pipelineMode)
        assertEquals("sk-stt-secret-123", imported.sttConfig.apiKey)
        assertEquals("zh", imported.sttConfig.language)
        assertEquals("sk-llm-secret-456", imported.llmConfig.apiKey)
        assertEquals(0.35f, imported.llmConfig.temperature, 0.001f)
        assertEquals("AIzaSySecret789", imported.multimodalConfig.apiKey)
        assertFalse(imported.hapticFeedback)
        assertFalse(imported.useSurroundingContext)
        assertEquals(2, imported.keyboardHeightLevel)
        assertFalse(imported.liveWriteToEditor)
        assertEquals(InputScheme.RIME_DOUBLE_PINYIN_FLYPY, imported.inputScheme)
        assertEquals(false, imported.fixedCandidateOrder)
        assertFalse(imported.predictionEnabled)
        assertTrue(imported.aiCompletionEnabled)
        assertEquals(1, imported.dictionary.size)
        assertEquals("TypeFree", imported.dictionary.first().term)
    }

    @Test
    fun `redacted export hides keys and import preserves existing local keys`() {
        val senderSettings = AppSettings(
            sttConfig = SttConfig(apiKey = "sk-sender-stt"),
            llmConfig = LlmConfig(
                presetId = "siliconflow_llm",
                baseUrl = "https://api.siliconflow.cn/v1",
                apiKey = "sk-sender-llm",
                model = "Qwen/Qwen2.5-32B-Instruct"
            ),
            multimodalConfig = MultimodalConfig(apiKey = "AIzaSender")
        )

        val redactedJson = ApiConfigBackupHelper.exportSettingsJson(senderSettings, includeSecrets = false)
        assertFalse(redactedJson.contains("sk-sender-stt"))
        assertFalse(redactedJson.contains("sk-sender-llm"))
        assertFalse(redactedJson.contains("AIzaSender"))
        assertTrue(redactedJson.contains(ApiConfigBackupHelper.REDACTED_SECRET))

        val receiverLocal = AppSettings(
            sttConfig = SttConfig(apiKey = "sk-local-keep-stt"),
            llmConfig = LlmConfig(apiKey = "sk-local-keep-llm"),
            multimodalConfig = MultimodalConfig(apiKey = "AIzaLocalKeep")
        )

        val wrappedInMarkdownFence = "```json\n$redactedJson\n```"
        val merged = ApiConfigBackupHelper.parseAndMergeConfigJson(
            rawJson = wrappedInMarkdownFence,
            existingSettings = receiverLocal
        ).getOrThrow()

        // Imported model & baseUrl applied, while local keys remain intact
        assertEquals("Qwen/Qwen2.5-32B-Instruct", merged.llmConfig.model)
        assertEquals("https://api.siliconflow.cn/v1", merged.llmConfig.baseUrl)
        assertEquals("sk-local-keep-stt", merged.sttConfig.apiKey)
        assertEquals("sk-local-keep-llm", merged.llmConfig.apiKey)
        assertEquals("AIzaLocalKeep", merged.multimodalConfig.apiKey)
    }

    @Test
    fun `partial json import clamps temperature and rejects unrelated or malformed payloads`() {
        val partialJson = """
            {
              "llmConfig": {
                "model": "glm-4-flash",
                "temperature": 3.5
              }
            }
        """.trimIndent()

        val merged = ApiConfigBackupHelper.parseAndMergeConfigJson(partialJson, AppSettings()).getOrThrow()
        assertEquals("glm-4-flash", merged.llmConfig.model)
        assertEquals(1.0f, merged.llmConfig.temperature, 0.001f)
        assertTrue(merged.styles.isNotEmpty())

        assertTrue(ApiConfigBackupHelper.parseAndMergeConfigJson("", AppSettings()).isFailure)
        assertTrue(ApiConfigBackupHelper.parseAndMergeConfigJson("not json", AppSettings()).isFailure)
        assertTrue(ApiConfigBackupHelper.parseAndMergeConfigJson("{\"foo\": 123}", AppSettings()).isFailure)
    }

    @Test
    fun `reuseSttCredentialsForLlm and preset helpers preserve keys and match provider models`() {
        val stt = SttConfig(
            presetId = "siliconflow",
            baseUrl = "https://api.siliconflow.cn/v1",
            apiKey = "sk-shared-sf",
            model = "FunAudioLLM/SenseVoiceSmall"
        )
        val defaultLlm = LlmConfig(
            enabled = false,
            presetId = "deepseek",
            baseUrl = "https://api.deepseek.com/v1",
            apiKey = "",
            model = "deepseek-chat"
        )

        val syncedLlm = ApiConfigBackupHelper.reuseSttCredentialsForLlm(stt, defaultLlm)
        assertTrue(syncedLlm.enabled)
        assertEquals("siliconflow_llm", syncedLlm.presetId)
        assertEquals("https://api.siliconflow.cn/v1", syncedLlm.baseUrl)
        assertEquals("sk-shared-sf", syncedLlm.apiKey)
        assertEquals("Qwen/Qwen2.5-7B-Instruct", syncedLlm.model)

        // Switching preset preserves already-entered API key
        val groqPreset = ApiPresets.sttPresets.first { it.id == "groq" }
        val switchedStt = ApiConfigBackupHelper.applyPresetToStt(stt, groqPreset)
        assertEquals("groq", switchedStt.presetId)
        assertEquals("whisper-large-v3-turbo", switchedStt.model)
        assertEquals("sk-shared-sf", switchedStt.apiKey)

        // Multimodal endpoint preview masks real API key
        val mmPreview = ApiConfigBackupHelper.previewMultimodalEndpoint(
            MultimodalConfig(
                protocol = MultimodalProtocol.GEMINI_NATIVE,
                baseUrl = "https://generativelanguage.googleapis.com/v1beta",
                apiKey = "AIzaRealSecret",
                model = "gemini-2.5-flash"
            )
        )
        assertFalse(mmPreview.contains("AIzaRealSecret"))
        assertTrue(mmPreview.contains("key=***"))
    }

    @Test
    fun `SetupStatsHelper computes stats, API readiness, and sandbox Speak-to-Edit replacement`() {
        val emptyStats = SetupStatsHelper.computeProductivityStats(emptyList())
        assertEquals(0, emptyStats.totalPolishedChars)
        assertEquals(0, emptyStats.voiceInputCount)
        assertEquals(0L, emptyStats.avgLatencyMs)

        val records = listOf(
            HistoryRecord(
                polishedText = "这是一段四十个字符左右的语音润色输出测试文本用来验证统计耗时节省计算逻辑准确无误", // 40 chars
                durationMs = 15_000L, // 0.25 min voice vs 1.0 min typing => 0.75 min saved
                latencyMs = 420L
            ),
            HistoryRecord(
                polishedText = "第二条二十字左右的润色测试文本内容验证！", // 20 chars
                durationMs = 6_000L,  // 0.10 min voice vs 0.5 min typing => 0.40 min saved
                latencyMs = 580L
            )
        )
        val stats = SetupStatsHelper.computeProductivityStats(records)
        assertEquals(60, stats.totalPolishedChars)
        assertEquals(2, stats.voiceInputCount)
        assertEquals(500L, stats.avgLatencyMs)
        assertEquals(1.15, stats.savedMinutes, 0.05)

        // API Readiness checks
        assertFalse(SetupStatsHelper.isApiConfigured(AppSettings()))
        assertTrue(
            SetupStatsHelper.isApiConfigured(
                AppSettings(
                    pipelineMode = PipelineMode.DUAL_STAGE,
                    sttConfig = SttConfig(apiKey = "sk-stt"),
                    llmConfig = LlmConfig(enabled = false, apiKey = "")
                )
            )
        )
        assertTrue(
            SetupStatsHelper.isApiConfigured(
                AppSettings(
                    pipelineMode = PipelineMode.DUAL_STAGE,
                    sttConfig = SttConfig(baseUrl = "http://192.168.1.20:8080/v1", apiKey = ""),
                    llmConfig = LlmConfig(baseUrl = "http://localhost:11434/v1", apiKey = "")
                )
            )
        )

        // Sandbox Speak-to-Edit replacement with reversed selection indices
        val (replaced, cursor) = SetupStatsHelper.applySandboxInsertionOrReplacement(
            currentText = "你好，明天下午两点开会。",
            selectionStart = 9,
            selectionEnd = 3, // reversed selection covering "明天下午两点"
            outputText = "周四上午十点"
        )
        assertEquals("你好，周四上午十点开会。", replaced)
        assertEquals(9, cursor)
    }
}
