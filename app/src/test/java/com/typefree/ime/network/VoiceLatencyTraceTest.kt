package com.typefree.ime.network

import com.google.gson.Gson
import com.typefree.core.offline.ExecutionSource
import com.typefree.ime.data.HistoryRecord
import com.typefree.ime.data.stats.TokenUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceLatencyTraceTest {

    private val gson = Gson()

    @Test
    fun `assemble populates dynamic Gemini Live and flash stage labels with tonal steps`() {
        val exec = PipelineExecutionResult(
            rawTranscript = "uh ship the play bundle",
            finalOutput = "Ship the Play bundle.",
            latencyMs = 510L,
            modeSummary = "Gemini Live + gemini-3.8-flash",
            sttProviderLabel = "Gemini Live",
            usedStreamingAsr = true,
            sttLatencyMs = 118L,
            llmLatencyMs = 391L,
            llmTtftMs = 215L,
            llmGenMs = 176L,
            executionSource = ExecutionSource.CLOUD,
            modelName = "models/gemini-3.8-flash"
        )

        val trace = VoiceLatencyTraceAssembler.assemble(
            micInitMs = 24L,
            recordingDurationMs = 3650L,
            firstPartialMs = 310L,
            hadStreamingSession = true,
            stopIoMs = 16L,
            commitMs = 13L,
            measuredPostReleaseMs = 538L,
            exec = exec,
            hostAppName = "Telegram"
        )

        assertEquals(24L, trace.micInitMs)
        assertEquals(3650L, trace.recordingDurationMs)
        assertEquals(538L, trace.postReleaseTotalMs)
        assertFalse(trace.isSlowWarning)
        assertEquals("链路顺畅", trace.summaryBadgeZh)
        assertEquals("Gemini Live 流式", trace.wsSetupZh)
        assertEquals("首字 @ 310ms", trace.streamHintZh)

        assertEquals(5, trace.stages.size)
        assertEquals(listOf(0, 1, 2, 3, 4), trace.stages.map { it.toneStep })
        assertEquals("收音落盘", trace.stages[0].labelZh)
        assertEquals("Gemini Live 尾包", trace.stages[1].labelZh)
        assertEquals("gemini-3.8-flash 首包", trace.stages[2].labelZh)
        assertEquals("gemini-3.8-flash 吐字", trace.stages[3].labelZh)
        assertEquals("Telegram上屏", trace.stages[4].labelZh)
        assertEquals("Gemini Live Tail", trace.stages[1].labelEn)
        assertEquals("gemini-3.8-flash TTFT", trace.stages[2].labelEn)
    }

    @Test
    fun `assemble splits multimodal audio encode and single-pass audio LLM`() {
        val exec = PipelineExecutionResult(
            rawTranscript = "",
            finalOutput = "明天下午三点开会。",
            latencyMs = 690L,
            modeSummary = "多模态 (gemini-3.5-flash-lite)",
            sttProviderLabel = "多模态直传",
            audioEncodeMs = 44L,
            sttLatencyMs = 0L,
            llmLatencyMs = 632L,
            executionSource = ExecutionSource.CLOUD,
            modelName = "gemini-3.5-flash-lite"
        )

        val trace = VoiceLatencyTraceAssembler.assemble(
            micInitMs = 22L,
            recordingDurationMs = 2800L,
            stopIoMs = 14L,
            commitMs = 12L,
            measuredPostReleaseMs = 702L,
            exec = exec,
            hostAppName = "微信"
        )

        assertEquals("多模态直传", trace.wsSetupZh)
        assertEquals(3, trace.stages.size)
        assertEquals("encode", trace.stages[0].key)
        assertEquals(58L, trace.stages[0].durationMs)
        assertEquals("gemini-3.5-flash-lite 音频理解", trace.stages[1].labelZh)
        assertEquals("微信上屏", trace.stages[2].labelZh)
    }

    @Test
    fun `assemble handles on-device SenseVoice and Qwen local metrics`() {
        val exec = PipelineExecutionResult(
            rawTranscript = "买两盒鲜牛奶",
            finalOutput = "买两盒鲜牛奶。",
            latencyMs = 460L,
            modeSummary = "SenseVoice + Qwen 0.8B",
            sttProviderLabel = "SenseVoice",
            sttLatencyMs = 168L,
            llmLatencyMs = 291L,
            llmTtftMs = 92L,
            llmGenMs = 199L,
            executionSource = ExecutionSource.LOCAL,
            modelName = "Qwen 0.8B",
            localMetricsSummary = "90.4 tok/s"
        )

        val trace = VoiceLatencyTraceAssembler.assemble(
            micInitMs = 19L,
            recordingDurationMs = 2400L,
            stopIoMs = 15L,
            commitMs = 11L,
            measuredPostReleaseMs = 485L,
            exec = exec,
            hostAppName = "备忘录"
        )

        assertEquals("本机 90.4 tok/s", trace.summaryBadgeZh)
        assertEquals(":local_ai 常驻", trace.wsSetupZh)
        assertEquals("PCM 传本机进程", trace.stages[0].labelZh)
        assertEquals("SenseVoice 识别", trace.stages[1].labelZh)
        assertEquals("Qwen 0.8B 预填充", trace.stages[2].labelZh)
        assertEquals("Qwen 0.8B 净写", trace.stages[3].labelZh)
        assertTrue(trace.stages[3].detailZh.contains("90.4 tok/s"))
    }

    @Test
    fun `clipboard diagnostic contains timing breakdown and excludes user text`() {
        val secretRaw = "我的私有密码是 9527"
        val exec = PipelineExecutionResult(
            rawTranscript = secretRaw,
            finalOutput = secretRaw,
            latencyMs = 600L,
            modeSummary = "豆包流式 + deepseek-chat",
            sttProviderLabel = "豆包流式",
            usedStreamingAsr = true,
            sttLatencyMs = 140L,
            llmLatencyMs = 430L,
            llmTtftMs = 240L,
            llmGenMs = 190L,
            tokenUsage = TokenUsage(promptTokens = 500L, cachedTokens = 400L, completionTokens = 20L),
            modelName = "deepseek-chat"
        )
        val trace = VoiceLatencyTraceAssembler.assemble(
            micInitMs = 25L,
            recordingDurationMs = 3100L,
            firstPartialMs = 340L,
            hadStreamingSession = true,
            stopIoMs = 18L,
            commitMs = 14L,
            measuredPostReleaseMs = 612L,
            exec = exec,
            hostAppName = "微信"
        )

        assertTrue(trace.stages[3].detailZh.contains("Prompt 缓存命中 80%"))
        val diag = trace.formatClipboardDiagnostic(
            clockTime = "11:42",
            hostApp = "微信",
            routeSummary = "豆包流式 + deepseek-chat"
        )
        assertTrue(diag.contains("[TypeFree Voice Trace] 11:42"))
        assertTrue(diag.contains("Post-Release: E2E=612ms = io:18ms + stt:140ms + ttft:240ms + gen:190ms + commit:14ms"))
        assertFalse(diag.contains("9527"))
    }

    @Test
    fun `HistoryRecord gson round-trip and legacy json compatibility`() {
        val legacyJson = """
            {"id":"legacy-1","timestamp":1700000000000,"targetAppName":"微信","rawTranscript":"旧原稿","polishedText":"旧成稿","durationMs":2100,"latencyMs":520}
        """.trimIndent()
        val decodedLegacy = gson.fromJson(legacyJson, HistoryRecord::class.java)
        assertNull(decodedLegacy.voiceTrace)
        assertEquals("旧成稿", decodedLegacy.polishedText)

        val exec = PipelineExecutionResult(
            rawTranscript = "测试",
            finalOutput = "测试。",
            latencyMs = 320L,
            modeSummary = "Gemini Live + gemini-3.5-flash-lite",
            sttProviderLabel = "Gemini Live",
            usedStreamingAsr = true,
            sttLatencyMs = 90L,
            llmLatencyMs = 210L,
            modelName = "gemini-3.5-flash-lite"
        )
        val recordWithTrace = HistoryRecord(
            targetAppName = "Slack",
            rawTranscript = "测试",
            polishedText = "测试。",
            durationMs = 1800L,
            latencyMs = 335L,
            modeLabel = exec.modeSummary,
            voiceTrace = VoiceLatencyTraceAssembler.assemble(
                micInitMs = 20L,
                recordingDurationMs = 1800L,
                stopIoMs = 12L,
                commitMs = 10L,
                measuredPostReleaseMs = 335L,
                exec = exec,
                hostAppName = "Slack"
            )
        )
        val encoded = gson.toJson(recordWithTrace)
        val roundTripped = gson.fromJson(encoded, HistoryRecord::class.java)
        assertNotNull(roundTripped.voiceTrace)
        assertEquals(335L, roundTripped.voiceTrace?.postReleaseTotalMs)
        assertEquals("Gemini Live 尾包", roundTripped.voiceTrace?.stages?.get(1)?.labelZh)
    }

    @Test
    fun `deduplicates ASR suffix and localizes on-device and hybrid labels in English`() {
        val deepSeekExec = PipelineExecutionResult(
            rawTranscript = "测试",
            finalOutput = "测试。",
            latencyMs = 2500L,
            modeSummary = "STT (DeepSeek ASR) + deepseek-chat",
            sttProviderLabel = "DeepSeek ASR",
            sttLatencyMs = 1869L,
            llmLatencyMs = 651L,
            llmTtftMs = 478L,
            llmGenMs = 173L,
            executionSource = ExecutionSource.CLOUD,
            modelName = "deepseek-chat"
        )
        val deepSeekTrace = VoiceLatencyTraceAssembler.assemble(
            micInitMs = 41L,
            recordingDurationMs = 11840L,
            stopIoMs = 77L,
            commitMs = 4L,
            measuredPostReleaseMs = 2605L,
            exec = deepSeekExec,
            hostAppName = "Launcher"
        )
        assertEquals("DeepSeek ASR", deepSeekTrace.stages[1].labelEn)
        assertEquals("Main: DeepSeek ASR", deepSeekTrace.summaryBadgeEn)

        val localExec = PipelineExecutionResult(
            rawTranscript = "测试",
            finalOutput = "测试。",
            latencyMs = 1950L,
            modeSummary = "本机 SenseVoice + 本机 0.8B",
            sttProviderLabel = "本机 SenseVoice",
            usedStreamingAsr = true,
            sttLatencyMs = 268L,
            llmLatencyMs = 1488L,
            llmTtftMs = 987L,
            llmGenMs = 501L,
            executionSource = ExecutionSource.LOCAL,
            modelName = "0.8B"
        )
        val localTrace = VoiceLatencyTraceAssembler.assemble(
            micInitMs = 23L,
            recordingDurationMs = 8240L,
            stopIoMs = 74L,
            commitMs = 14L,
            measuredPostReleaseMs = 1969L,
            exec = localExec,
            hostAppName = "QQ"
        )
        assertEquals("Local SenseVoice Tail", localTrace.stages[1].labelEn)
        assertEquals("Local 0.8B Prefill", localTrace.stages[2].labelEn)
        assertEquals("本机 0.8B 预填充", localTrace.stages[2].labelZh)

        val hybridExec = PipelineExecutionResult(
            rawTranscript = "测试",
            finalOutput = "测试。",
            latencyMs = 820L,
            modeSummary = "本机 SenseVoice + deepseek-chat",
            sttProviderLabel = "本机 SenseVoice",
            usedStreamingAsr = true,
            sttLatencyMs = 240L,
            llmLatencyMs = 560L,
            llmTtftMs = 410L,
            llmGenMs = 150L,
            executionSource = ExecutionSource.HYBRID_LOCAL_ASR_CLOUD_LLM,
            modelName = "deepseek-chat"
        )
        val hybridTrace = VoiceLatencyTraceAssembler.assemble(
            micInitMs = 21L,
            recordingDurationMs = 6000L,
            stopIoMs = 60L,
            commitMs = 10L,
            measuredPostReleaseMs = 870L,
            exec = hybridExec,
            hostAppName = "QQ"
        )
        assertEquals("PCM 传本机进程", hybridTrace.stages[0].labelZh)
        assertEquals("Local SenseVoice Tail", hybridTrace.stages[1].labelEn)
        assertEquals("deepseek-chat TTFT", hybridTrace.stages[2].labelEn)
    }
}
