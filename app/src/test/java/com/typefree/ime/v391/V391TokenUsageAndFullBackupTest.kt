package com.typefree.ime.v391

import com.typefree.core.offline.ExecutionSource
import com.typefree.ime.data.AppRepository
import com.typefree.ime.data.AppSettings
import com.typefree.ime.data.ClipboardEntry
import com.typefree.ime.data.DefaultPresets
import com.typefree.ime.data.DictionaryEntry
import com.typefree.ime.data.HistoryRecord
import com.typefree.ime.data.InMemoryKeyValueStore
import com.typefree.ime.data.JvmAesGcmCipher
import com.typefree.ime.data.LlmConfig
import com.typefree.ime.data.MultimodalConfig
import com.typefree.ime.data.MultimodalProtocol
import com.typefree.ime.data.PipelineMode
import com.typefree.ime.data.SecretVault
import com.typefree.ime.data.SnippetEntry
import com.typefree.ime.data.ThemePreference
import com.typefree.ime.data.ThinkingMode
import com.typefree.ime.data.VoiceIntentMode
import com.typefree.ime.data.stats.BigramBackupItem
import com.typefree.ime.data.stats.DailyStatsRecord
import com.typefree.ime.data.stats.DailyStatsStore
import com.typefree.ime.data.stats.FullBackupOptions
import com.typefree.ime.data.stats.InMemoryDailyStatsBackend
import com.typefree.ime.data.stats.InsightsAggregator
import com.typefree.ime.data.stats.PredictionBackupArchive
import com.typefree.ime.data.stats.StatsTimeRange
import com.typefree.ime.data.stats.TokenFeatureKeys
import com.typefree.ime.data.stats.TokenUsage
import com.typefree.ime.data.stats.TrigramBackupItem
import com.typefree.ime.network.AiPipelineEngine
import com.typefree.ime.ui.screens.ApiConfigBackupHelper
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader
import java.util.Calendar
import java.util.TimeZone

class V391TokenUsageAndFullBackupTest {

    private val utc: TimeZone = TimeZone.getTimeZone("UTC")

    private fun utcMillis(year: Int, month: Int, day: Int, hour: Int = 10, minute: Int = 0): Long {
        return Calendar.getInstance(utc).apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month - 1)
            set(Calendar.DAY_OF_MONTH, day)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    private fun newInMemoryRepository(
        publicStore: InMemoryKeyValueStore = InMemoryKeyValueStore(),
        secretStore: InMemoryKeyValueStore = InMemoryKeyValueStore()
    ): AppRepository {
        val vault = SecretVault(secretStore, JvmAesGcmCipher())
        return AppRepository(appContext = null, publicStore = publicStore, secretVault = vault)
    }

    // =========================================================================
    // 1. Default ThemePreference.SYSTEM (preserves explicit saved LIGHT/DARK)
    // =========================================================================

    @Test
    fun `fresh AppSettings defaults to ThemePreference SYSTEM and preserves explicit saved LIGHT or DARK`() {
        assertEquals(ThemePreference.SYSTEM, AppSettings().themePreference)

        // Legacy saved settings JSON lacking themePreference hydrates to SYSTEM
        val legacyPublic = InMemoryKeyValueStore()
        legacyPublic.putString(AppRepository.KEY_SETTINGS, """{"pipelineMode":"DUAL_STAGE"}""")
        val legacyRepo = newInMemoryRepository(publicStore = legacyPublic)
        assertEquals(ThemePreference.SYSTEM, legacyRepo.settings.value.themePreference)

        // Explicit saved LIGHT is preserved untouched
        val explicitLightPublic = InMemoryKeyValueStore()
        explicitLightPublic.putString(AppRepository.KEY_SETTINGS, """{"themePreference":"LIGHT"}""")
        val lightRepo = newInMemoryRepository(publicStore = explicitLightPublic)
        assertEquals(ThemePreference.LIGHT, lightRepo.settings.value.themePreference)

        // Explicit saved DARK is preserved untouched
        val explicitDarkPublic = InMemoryKeyValueStore()
        explicitDarkPublic.putString(AppRepository.KEY_SETTINGS, """{"themePreference":"DARK"}""")
        val darkRepo = newInMemoryRepository(publicStore = explicitDarkPublic)
        assertEquals(ThemePreference.DARK, darkRepo.settings.value.themePreference)
    }

    // =========================================================================
    // 2. Structured TokenUsage Parsing (OpenAI / DeepSeek / Gemini / Stream / Fallback)
    // =========================================================================

    @Test
    fun `extractOpenAiTokenUsage normalizes reasoning tokens inside completion_tokens without double counting`() {
        val body = """
            {
              "choices": [{"message": {"content": "你好"}}],
              "usage": {
                "prompt_tokens": 1200,
                "prompt_tokens_details": {"cached_tokens": 400},
                "completion_tokens": 500,
                "completion_tokens_details": {"reasoning_tokens": 320},
                "total_tokens": 1700
              }
            }
        """.trimIndent()
        val usage = AiPipelineEngine.extractOpenAiTokenUsageFromBody(body)
        assertNotNull(usage)
        usage!!
        assertEquals(1200L, usage.safePromptTokens)
        assertEquals(400L, usage.safeCachedTokens)
        assertEquals(800L, usage.uncachedPromptTokens)
        assertEquals(180L, usage.visibleOutputTokens) // 500 - 320
        assertEquals(320L, usage.safeReasoningTokens)
        assertEquals(1700L, usage.totalTokens)
        assertFalse(usage.isEstimated)
    }

    @Test
    fun `extractOpenAiTokenUsage supports DeepSeek prompt_cache_hit_tokens`() {
        val body = """
            {
              "usage": {
                "prompt_tokens": 900,
                "prompt_cache_hit_tokens": 600,
                "completion_tokens": 120,
                "total_tokens": 1020
              }
            }
        """.trimIndent()
        val usage = AiPipelineEngine.extractOpenAiTokenUsageFromBody(body)!!
        assertEquals(900L, usage.safePromptTokens)
        assertEquals(600L, usage.safeCachedTokens)
        assertEquals(120L, usage.visibleOutputTokens)
        assertEquals(0L, usage.safeReasoningTokens)
        assertEquals(1020L, usage.totalTokens)
    }

    @Test
    fun `extractGeminiTokenUsage parses non-overlapping candidates and thoughts tokens plus cachedContentTokenCount`() {
        val body = """
            {
              "candidates": [{"content": {"parts": [{"text": "整理完毕"}]}}],
              "usageMetadata": {
                "promptTokenCount": 800,
                "cachedContentTokenCount": 250,
                "candidatesTokenCount": 140,
                "thoughtsTokenCount": 360,
                "totalTokenCount": 1300
              }
            }
        """.trimIndent()
        val usage = AiPipelineEngine.extractGeminiTokenUsageFromBody(body)!!
        assertEquals(800L, usage.safePromptTokens)
        assertEquals(250L, usage.safeCachedTokens)
        assertEquals(140L, usage.visibleOutputTokens)
        assertEquals(360L, usage.safeReasoningTokens)
        assertEquals(1300L, usage.totalTokens)
        assertFalse(usage.isEstimated)
    }

    @Test
    fun `readOpenAiStreamOrBufferedJson and readGeminiStreamOrBufferedJson extract trailing stream usage`() {
        val openAiSse = """
            data: {"choices":[{"delta":{"content":"你好"}}]}
            data: {"choices":[{"delta":{"content":"世界"}}]}
            data: {"choices":[],"usage":{"prompt_tokens":300,"prompt_tokens_details":{"cached_tokens":100},"completion_tokens":80,"completion_tokens_details":{"reasoning_tokens":30},"total_tokens":380}}
            data: [DONE]
        """.trimIndent()
        val oaOut = AiPipelineEngine.readOpenAiStreamOrBufferedJson(StringReader(openAiSse).buffered())
        assertEquals("你好世界", oaOut.text)
        assertNotNull(oaOut.tokenUsage)
        assertEquals(300L, oaOut.tokenUsage!!.safePromptTokens)
        assertEquals(100L, oaOut.tokenUsage!!.safeCachedTokens)
        assertEquals(50L, oaOut.tokenUsage!!.visibleOutputTokens)
        assertEquals(30L, oaOut.tokenUsage!!.safeReasoningTokens)
        assertEquals(380L, oaOut.tokenUsage!!.totalTokens)

        val geminiSse = """
            data: {"candidates":[{"content":{"parts":[{"text":"思考中","thought":true}]}}]}
            data: {"candidates":[{"content":{"parts":[{"text":"最终回复"}]}}],"usageMetadata":{"promptTokenCount":450,"cachedContentTokenCount":50,"candidatesTokenCount":60,"thoughtsTokenCount":120,"totalTokenCount":630}}
        """.trimIndent()
        val gemOut = AiPipelineEngine.readGeminiStreamOrBufferedJson(StringReader(geminiSse).buffered())
        assertEquals("最终回复", gemOut.text)
        assertNotNull(gemOut.tokenUsage)
        assertEquals(450L, gemOut.tokenUsage!!.safePromptTokens)
        assertEquals(50L, gemOut.tokenUsage!!.safeCachedTokens)
        assertEquals(60L, gemOut.tokenUsage!!.visibleOutputTokens)
        assertEquals(120L, gemOut.tokenUsage!!.safeReasoningTokens)
        assertEquals(630L, gemOut.tokenUsage!!.totalTokens)
    }

    @Test
    fun `OpenAI streaming sends stream_options include_usage and falls back cleanly on HTTP 400 with estimation`() = runBlocking {
        AiPipelineEngine.clearUnsupportedThinkingCache()
        val capturedBodies = mutableListOf<String>()
        var callCount = 0
        val mockHttp = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val req = chain.request()
                val buf = Buffer()
                req.body?.writeTo(buf)
                val reqStr = buf.readUtf8()
                capturedBodies.add(reqStr)
                callCount++
                if (callCount == 1 && reqStr.contains("stream_options")) {
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(400)
                        .message("Bad Request")
                        .body("""{"error":{"message":"unknown field stream_options"}}""".toResponseBody("application/json".toMediaType()))
                        .build()
                } else {
                    val sse = """
                        data: {"choices":[{"delta":{"content":"好的，下午三点见。"}}]}
                        data: [DONE]
                    """.trimIndent()
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(sse.toResponseBody("text/event-stream".toMediaType()))
                        .build()
                }
            })
            .build()

        val engine = AiPipelineEngine(httpClient = mockHttp)
        val settings = AppSettings(
            pipelineMode = PipelineMode.DUAL_STAGE,
            llmConfig = LlmConfig(
                enabled = true,
                baseUrl = "https://proxy.example.com/v1",
                apiKey = "sk-proxy-test",
                model = "custom-proxy-chat",
                thinkingMode = ThinkingMode.DISABLED
            )
        )
        val streamedChunks = mutableListOf<String>()
        val res = engine.runAskAiCardQuery(
            settings = settings,
            query = "下午三点聊一下方案？",
            targetAppName = "微信",
            onStreamingTextUpdate = { streamedChunks.add(it) }
        )
        assertTrue(res.isSuccess)
        assertEquals(2, capturedBodies.size)
        assertTrue(capturedBodies[0].contains("include_usage"))
        assertFalse(capturedBodies[1].contains("stream_options"))
        val askAiResult = res.getOrThrow()
        assertEquals("好的，下午三点见。", askAiResult.finalOutput)
        assertTrue(streamedChunks.isNotEmpty())
        assertNotNull(askAiResult.tokenUsage)
        assertTrue(askAiResult.tokenUsage!!.isEstimated)
        assertTrue(askAiResult.tokenUsage!!.totalTokens > 0L)
        assertEquals("custom-proxy-chat", askAiResult.modelName)
    }

    @Test
    fun `completeSentence invokes onTokenUsage callback with structured Gemini usage`() = runBlocking {
        val mockHttp = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val req = chain.request()
                val respJson = """
                    {
                      "candidates": [{"content": {"parts": [{"text": "明天早上同步进度"}]}}],
                      "usageMetadata": {
                        "promptTokenCount": 210,
                        "candidatesTokenCount": 18,
                        "totalTokenCount": 228
                      }
                    }
                """.trimIndent()
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(respJson.toResponseBody("application/json".toMediaType()))
                    .build()
            })
            .build()

        val engine = AiPipelineEngine(httpClient = mockHttp)
        val settings = AppSettings(
            pipelineMode = PipelineMode.MULTIMODAL_DIRECT,
            multimodalConfig = MultimodalConfig(
                protocol = MultimodalProtocol.GEMINI_NATIVE,
                baseUrl = "https://generativelanguage.googleapis.com/v1beta",
                apiKey = "AIza-test",
                model = "gemini-3.5-flash-lite",
                thinkingMode = ThinkingMode.DISABLED
            )
        )
        var observedUsage: TokenUsage? = null
        var observedModel = ""
        val res = engine.completeSentence(
            settings = settings,
            activeStyle = DefaultPresets.defaultStyles().first(),
            contextBeforeCursor = "我们计划",
            targetAppName = "飞书",
            onTokenUsage = { u, m ->
                observedUsage = u
                observedModel = m
            }
        )
        assertTrue(res.isSuccess)
        assertNotNull(observedUsage)
        assertEquals(228L, observedUsage!!.totalTokens)
        assertEquals("gemini-3.5-flash-lite", observedModel)
    }

    // =========================================================================
    // 3. DailyStatsStore v2 Token Tracking, Voice Duration Split & TokenUsageSnapshot
    // =========================================================================

    @Test
    fun `DailyStatsStore records tokens, enforces incognito zero-write, splits voice duration, and aggregates TokenUsageSnapshot`() {
        val nowMs = utcMillis(2026, 11, 23, 14, 0)
        val backend = InMemoryDailyStatsBackend()
        val store = DailyStatsStore(backend = backend, clock = { nowMs }, debounceMs = 0L, timeZone = utc)

        // Cloud voice polish
        store.recordVoiceAndAi(
            HistoryRecord(
                timestamp = nowMs,
                polishedText = "测试云端语音输出",
                durationMs = 12_000L,
                executionSource = ExecutionSource.CLOUD.name
            ),
            nowMs = nowMs
        )
        // Offline voice dictation
        store.recordVoiceAndAi(
            HistoryRecord(
                timestamp = nowMs,
                polishedText = "本机离线口述记录",
                durationMs = 18_000L,
                executionSource = ExecutionSource.LOCAL.name
            ),
            nowMs = nowMs
        )
        // Hybrid local ASR + cloud LLM -> ASR duration counts as offline voice duration!
        store.recordVoiceAndAi(
            HistoryRecord(
                timestamp = nowMs,
                polishedText = "端侧听音云端润色",
                durationMs = 10_000L,
                executionSource = ExecutionSource.HYBRID_LOCAL_ASR_CLOUD_LLM.name
            ),
            nowMs = nowMs
        )

        store.recordTokenUsage(
            usage = TokenUsage(promptTokens = 1000L, cachedTokens = 400L, completionTokens = 200L, reasoningTokens = 100L),
            modelName = "deepseek-ai/deepseek-chat",
            featureKey = TokenFeatureKeys.VOICE_POLISH,
            nowMs = nowMs
        )
        store.recordTokenUsage(
            usage = TokenUsage(promptTokens = 500L, cachedTokens = 0L, completionTokens = 150L, reasoningTokens = 0L, isEstimated = true),
            modelName = "gemini-3.5-flash-lite",
            featureKey = TokenFeatureKeys.HELP_ME_WRITE,
            nowMs = nowMs
        )
        // Incognito MUST short-circuit cleanly
        store.recordTokenUsage(
            usage = TokenUsage(promptTokens = 9999L, completionTokens = 9999L),
            modelName = "should-not-record",
            featureKey = TokenFeatureKeys.ASK_AI,
            nowMs = nowMs,
            incognito = true
        )

        val snap = InsightsAggregator.buildTokenUsageSnapshot(
            range = StatsTimeRange.DAYS_7,
            dailyRecords = store.listAllRecords(),
            nowMs = nowMs,
            timeZone = utc
        )
        assertTrue(snap.hasData)
        assertEquals(1500L, snap.promptTokens)
        assertEquals(400L, snap.cachedTokens)
        assertEquals(1100L, snap.uncachedPromptTokens)
        assertEquals(350L, snap.visibleOutputTokens)
        assertEquals(100L, snap.reasoningTokens)
        assertEquals(1950L, snap.totalTokens)
        assertEquals(27, snap.cacheHitPercent) // 400/1500 = 26.67% -> 27%
        assertEquals(1, snap.estimatedCallCount)
        assertEquals(12_000L, snap.cloudVoiceDurationMs)
        assertEquals(28_000L, snap.offlineVoiceDurationMs) // 18s local + 10s hybrid local ASR
        assertEquals(1, snap.offlineSavedCloudCalls)
        assertEquals(2, snap.byFeature.size)
        assertEquals(TokenFeatureKeys.VOICE_POLISH, snap.byFeature.first().key)
        assertEquals(2, snap.byModel.size)
        assertEquals("deepseek-chat", snap.byModel.first().key)
        assertEquals(100, snap.byFeature.sumOf { it.percent })
        assertEquals(100, snap.byModel.sumOf { it.percent })
    }

    // =========================================================================
    // 4. Full Lossless Backup & Idempotent Merge Migration (v2 + v1 Compatibility)
    // =========================================================================

    @Test
    fun `full backup export and lossless merge import preserves all user data without loss or duplication`() {
        val day1Ts = utcMillis(2026, 11, 20, 9, 0)
        val day2Ts = utcMillis(2026, 11, 22, 15, 0)
        val day3Ts = utcMillis(2026, 11, 23, 18, 0)

        // Source package repository (e.g. existing production TypeFree)
        val sourceRepo = newInMemoryRepository()
        sourceRepo.updateSettings { curr ->
            curr.copy(
                themePreference = ThemePreference.DARK,
                inputStatsEnabled = true,
                llmConfig = curr.llmConfig.copy(apiKey = "sk-source-secret", model = "deepseek-chat"),
                snippets = listOf(SnippetEntry(id = "s1", title = "邮箱", voiceTriggers = "发邮箱", content = "test@example.com")),
                dictionary = listOf(DictionaryEntry(id = "d1", term = "TypeFree", wrongVariants = "太普利斯"))
            )
        }
        sourceRepo.addHistory(
            HistoryRecord(
                id = "h-1",
                timestamp = day1Ts,
                targetAppName = "微信",
                rawTranscript = "明天开会",
                polishedText = "明天上午十点开会。",
                durationMs = 5_000L,
                audioFilePath = "/data/user/0/com.typefree.ime/files/history_audio/secret.wav",
                executionSource = ExecutionSource.CLOUD.name
            )
        )
        sourceRepo.addHistory(
            HistoryRecord(
                id = "h-2",
                timestamp = day2Ts,
                targetAppName = "飞书",
                rawTranscript = "同步进度",
                polishedText = "今天同步一下开发进度。",
                durationMs = 6_000L,
                audioFilePath = "/data/user/0/com.typefree.ime/files/history_audio/secret2.wav",
                executionSource = ExecutionSource.LOCAL.name
            )
        )
        sourceRepo.dailyStatsStore.recordTokenUsage(
            usage = TokenUsage(promptTokens = 2400L, cachedTokens = 800L, completionTokens = 450L, reasoningTokens = 150L),
            modelName = "deepseek-chat",
            featureKey = TokenFeatureKeys.VOICE_POLISH,
            nowMs = day2Ts
        )
        sourceRepo.fallbackPredictionStore.recordBigram("今天", "天气", nowMs = day2Ts)
        sourceRepo.fallbackPredictionStore.recordBigram("今天", "天气", nowMs = day2Ts + 1000L)
        sourceRepo.fallbackPredictionStore.recordTrigram("我们", "明天", "开会", nowMs = day2Ts)
        sourceRepo.recordClipboardText("常驻重要地址：北京市海淀区", pinned = true, nowMs = day2Ts)
        sourceRepo.recordClipboardText("普通临时复制不应被导出", pinned = false, nowMs = day2Ts + 500L)

        // Export full v2 backup
        val fullJson = ApiConfigBackupHelper.exportFullBackupJson(
            settings = sourceRepo.settings.value,
            options = FullBackupOptions(),
            history = sourceRepo.history.value,
            dailyStats = sourceRepo.dailyStatsStore.listAllRecords(),
            predictions = sourceRepo.exportPredictionsArchive(),
            pinnedClipboard = sourceRepo.clipboardItems.value
        )
        // Verify local audioFilePath is stripped & unpinned clipboard excluded
        assertFalse(fullJson.contains("secret.wav"))
        assertFalse(fullJson.contains("普通临时复制不应被导出"))
        assertTrue(fullJson.contains("常驻重要地址：北京市海淀区"))
        assertTrue(fullJson.contains("backupFormatVersion"))

        // Target package repository (e.g. coexist test APK that ALREADY has its own local data!)
        val targetRepo = newInMemoryRepository()
        targetRepo.addHistory(
            HistoryRecord(
                id = "h-local-only",
                timestamp = day3Ts,
                targetAppName = "备忘录",
                rawTranscript = "本地新笔记",
                polishedText = "这是新包里自己记录的笔记。",
                durationMs = 4_000L,
                executionSource = ExecutionSource.CLOUD.name
            )
        )
        targetRepo.fallbackPredictionStore.recordBigram("本地", "词链", nowMs = day3Ts)
        targetRepo.fallbackPredictionStore.recordBigram("今天", "天气", nowMs = day1Ts) // count=1 < source count=2
        targetRepo.recordClipboardText("本地已有置顶项", pinned = true, nowMs = day3Ts)

        // First import: merges all source data while preserving target local data
        val firstImport = ApiConfigBackupHelper.importFullBackupJson(
            rawJson = fullJson,
            existingSettings = targetRepo.settings.value,
            repository = targetRepo
        )
        assertTrue(firstImport.isSuccess)
        val firstOutcome = firstImport.getOrThrow()
        targetRepo.updateSettings { firstOutcome.settings }

        assertEquals(ThemePreference.DARK, targetRepo.settings.value.themePreference)
        assertEquals("sk-source-secret", targetRepo.settings.value.llmConfig.apiKey)
        assertEquals(3, targetRepo.history.value.size) // h-local-only + h-1 + h-2
        assertEquals(2, firstOutcome.mergeSummary.mergedHistoryCount)
        assertTrue(firstOutcome.mergeSummary.mergedDailyStatsDays >= 2)
        assertEquals(1, firstOutcome.mergeSummary.mergedPinnedClipboardCount)

        // Verify token stats survived cross-package migration intact
        val targetTokenSnap = targetRepo.computeTokenUsageSnapshot(StatsTimeRange.ALL, nowMs = day3Ts)
        assertEquals(2400L, targetTokenSnap.promptTokens)
        assertEquals(800L, targetTokenSnap.cachedTokens)
        assertEquals(450L, targetTokenSnap.visibleOutputTokens)
        assertEquals(150L, targetTokenSnap.reasoningTokens)
        assertEquals(3000L, targetTokenSnap.totalTokens)

        // Verify L2 predictions merged keeping local-only AND higher count for overlapping
        assertEquals(1, targetRepo.fallbackPredictionStore.getBigramCount("本地", "词链"))
        assertEquals(2, targetRepo.fallbackPredictionStore.getBigramCount("今天", "天气"))
        assertEquals(1, targetRepo.fallbackPredictionStore.getTrigramCount("我们", "明天", "开会"))

        // Verify pinned clipboard preserved both local & imported
        val pinnedTexts = targetRepo.clipboardItems.value.filter { it.isPinned }.map { it.text }.toSet()
        assertEquals(setOf("本地已有置顶项", "常驻重要地址：北京市海淀区"), pinnedTexts)

        // Second import of the EXACT same JSON MUST be idempotent (no duplicate history, no doubled stats/tokens!)
        val secondImport = ApiConfigBackupHelper.importFullBackupJson(
            rawJson = fullJson,
            existingSettings = targetRepo.settings.value,
            repository = targetRepo
        )
        assertTrue(secondImport.isSuccess)
        val secondOutcome = secondImport.getOrThrow()
        assertEquals(0, secondOutcome.mergeSummary.mergedHistoryCount)
        assertEquals(3, targetRepo.history.value.size)
        val afterSecondTokenSnap = targetRepo.computeTokenUsageSnapshot(StatsTimeRange.ALL, nowMs = day3Ts)
        assertEquals(3000L, afterSecondTokenSnap.totalTokens)
        assertEquals(2, targetRepo.fallbackPredictionStore.getBigramCount("今天", "天气"))
    }

    @Test
    fun `redacted backup strictly hides secrets and omits all personal history, clipboard, and predictions`() {
        val repo = newInMemoryRepository()
        repo.updateSettings { curr ->
            curr.copy(llmConfig = curr.llmConfig.copy(apiKey = "sk-top-secret"))
        }
        repo.addHistory(HistoryRecord(polishedText = "私密日记内容"))
        repo.recordClipboardText("私密置顶密码提示", pinned = true)
        repo.fallbackPredictionStore.recordBigram("私密", "连词")

        val redactedJson = ApiConfigBackupHelper.exportFullBackupJson(
            settings = repo.settings.value,
            options = FullBackupOptions(includeSecrets = false),
            history = repo.history.value,
            dailyStats = repo.dailyStatsStore.listAllRecords(),
            predictions = repo.exportPredictionsArchive(),
            pinnedClipboard = repo.clipboardItems.value
        )
        assertFalse(redactedJson.contains("sk-top-secret"))
        assertTrue(redactedJson.contains(ApiConfigBackupHelper.REDACTED_SECRET))
        assertFalse(redactedJson.contains("私密日记内容"))
        assertFalse(redactedJson.contains("私密置顶密码提示"))
        assertFalse(redactedJson.contains("historyRecords"))
        assertFalse(redactedJson.contains("predictionArchive"))
        assertFalse(redactedJson.contains("clipboardPinnedItems"))
    }

    @Test
    fun `importFullBackupJson remains 100 percent backward compatible with legacy v1 settings-only JSON`() {
        val targetRepo = newInMemoryRepository()
        val legacyJson = """
            {
              "pipelineMode": "DUAL_STAGE",
              "themePreference": "LIGHT",
              "llmConfig": {
                "enabled": true,
                "model": "qwen-turbo-latest",
                "apiKey": "sk-legacy-key"
              }
            }
        """.trimIndent()

        val res = ApiConfigBackupHelper.importFullBackupJson(
            rawJson = legacyJson,
            existingSettings = targetRepo.settings.value,
            repository = targetRepo
        )
        assertTrue(res.isSuccess)
        val outcome = res.getOrThrow()
        assertEquals(ThemePreference.LIGHT, outcome.settings.themePreference)
        assertEquals("qwen-turbo-latest", outcome.settings.llmConfig.model)
        assertEquals("sk-legacy-key", outcome.settings.llmConfig.apiKey)
        assertFalse(outcome.mergeSummary.hasExtendedData)
    }
}
