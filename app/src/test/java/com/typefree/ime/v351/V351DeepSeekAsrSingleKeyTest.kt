package com.typefree.ime.v351

import com.typefree.ime.audio.WavAudioRecorder
import com.typefree.ime.data.AppRepository
import com.typefree.ime.data.AppSettings
import com.typefree.ime.data.DefaultPresets
import com.typefree.ime.data.InMemoryKeyValueStore
import com.typefree.ime.data.JvmAesGcmCipher
import com.typefree.ime.data.ModelCapabilitySlot
import com.typefree.ime.data.PipelineMode
import com.typefree.ime.data.ProviderCredential
import com.typefree.ime.data.SecretVault
import com.typefree.ime.data.SttConfig
import com.typefree.ime.data.SttProtocol
import com.typefree.ime.data.UnifiedProviderCatalog
import com.typefree.ime.ime.jev.JevDomainProfile
import com.typefree.ime.network.AiPipelineEngine
import com.typefree.ime.network.EndpointResolver
import com.typefree.ime.ui.screens.ApiConfigBackupHelper
import com.typefree.ime.ui.screens.SetupStatsHelper
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class V351DeepSeekAsrSingleKeyTest {

    @Test
    fun endpointResolver_resolvesOfficialDeepSeekAndCustomToolsAsrEndpoints() {
        assertEquals(
            "https://api.deepseek.com/v1/tools/asr",
            EndpointResolver.resolveSttEndpoint("https://api.deepseek.com")
        )
        assertEquals(
            "https://api.deepseek.com/v1/tools/asr",
            EndpointResolver.resolveSttEndpoint("https://api.deepseek.com/v1")
        )
        assertEquals(
            "https://api.deepseek.com/v1/tools/asr",
            EndpointResolver.resolveSttEndpoint("https://api.deepseek.com/v1/tools/asr/")
        )
        assertEquals(
            "https://custom-gateway.example.com/deepseek/v1/tools/asr",
            EndpointResolver.resolveSttEndpoint("https://custom-gateway.example.com/deepseek/v1/tools/asr/")
        )
        assertTrue(EndpointResolver.isDeepSeekAsrEndpoint("https://api.deepseek.com/v1"))
        assertTrue(EndpointResolver.isDeepSeekAsrEndpoint("https://custom-gateway.example.com/v1/tools/asr"))
        assertFalse(EndpointResolver.isDeepSeekAsrEndpoint("https://api.siliconflow.cn/v1"))

        // Chat & Models endpoints strip /tools/asr cleanly when sharing a base URL
        assertEquals(
            "https://api.deepseek.com/v1/chat/completions",
            EndpointResolver.resolveChatEndpoint("https://api.deepseek.com/v1/tools/asr")
        )
        assertEquals(
            "https://api.deepseek.com/v1/models",
            EndpointResolver.resolveOpenAiModelsEndpoint("https://api.deepseek.com/v1/tools/asr")
        )
    }

    @Test
    fun deepSeekAsrMultipart_containsOnlyWavFilePart_andNeverSendsModelOrLanguage() = runBlocking {
        val pcmBytes = ByteArray(3200) { (it % 64).toByte() }
        val wavBytes = WavAudioRecorder.buildWavHeader(pcmBytes.size) + pcmBytes
        val tempWav = File.createTempFile("deepseek_asr_sample_", ".wav").apply {
            writeBytes(wavBytes)
            deleteOnExit()
        }

        // 1. Direct inspection of buildDeepSeekAsrMultipartBody
        val multipart = AiPipelineEngine.buildDeepSeekAsrMultipartBody(tempWav)
        assertEquals(MultipartBody.FORM, multipart.type)
        assertEquals(1, multipart.parts.size)
        val singlePart = multipart.parts.first()
        val disposition = singlePart.headers?.get("Content-Disposition").orEmpty()
        assertTrue(disposition.contains("name=\"file\""))
        assertTrue(disposition.contains(tempWav.name))
        assertEquals("audio/wav".toMediaType(), singlePart.body.contentType())

        val rawMultipartBuffer = Buffer()
        multipart.writeTo(rawMultipartBuffer)
        val rawMultipartText = rawMultipartBuffer.readUtf8()
        assertFalse("Must NOT contain model field", rawMultipartText.contains("name=\"model\""))
        assertFalse("Must NOT contain language field", rawMultipartText.contains("name=\"language\""))
        assertFalse("Must NOT contain prompt field", rawMultipartText.contains("name=\"prompt\""))
        assertFalse("Must NOT contain response_format field", rawMultipartText.contains("name=\"response_format\""))

        // 2. End-to-end HTTP interception through AiPipelineEngine.transcribeWithOpenAiStt & probeModelLatency
        val capturedRequests = mutableListOf<Pair<Request, String>>()
        val mockClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                val buf = Buffer()
                req.body?.writeTo(buf)
                capturedRequests.add(req to buf.readUtf8())
                val jsonResp = """{"request_id":"req_ds_998","text":"DeepSeek官方语音识别测试通过","duration_ms":3200}"""
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(jsonResp.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val engine = AiPipelineEngine(httpClient = mockClient)
        val sttConfig = SttConfig(
            presetId = "deepseek_asr",
            protocol = SttProtocol.OPENAI_HTTP,
            baseUrl = "https://api.deepseek.com/v1",
            apiKey = "sk-deepseek-single-key",
            model = "deepseek-asr",
            language = "zh" // Even if user selected a language preference, /v1/tools/asr must NOT send it
        )

        val transcript = engine.transcribeWithOpenAiStt(
            wavFile = tempWav,
            config = sttConfig,
            biasPrompt = "TypeFree, DeepSeek"
        )
        assertEquals("DeepSeek官方语音识别测试通过", transcript)
        assertEquals(1, capturedRequests.size)

        val (sentReq, sentBodyStr) = capturedRequests.first()
        assertEquals("https://api.deepseek.com/v1/tools/asr", sentReq.url.toString())
        assertEquals("Bearer sk-deepseek-single-key", sentReq.header("Authorization"))
        assertTrue(sentBodyStr.contains("name=\"file\""))
        assertFalse("HTTP request to /v1/tools/asr must never include model", sentBodyStr.contains("name=\"model\""))
        assertFalse("HTTP request to /v1/tools/asr must never include language", sentBodyStr.contains("name=\"language\""))
        assertFalse("HTTP request to /v1/tools/asr must never include prompt", sentBodyStr.contains("name=\"prompt\""))
        assertFalse("HTTP request to /v1/tools/asr must never include response_format", sentBodyStr.contains("name=\"response_format\""))

        // Also verify probeModelLatency on STT slot for DeepSeek omits model/response_format
        val probeResult = engine.probeModelLatency(
            slot = ModelCapabilitySlot.STT,
            baseUrl = "https://api.deepseek.com/v1",
            apiKey = "sk-deepseek-single-key",
            modelId = "deepseek-asr"
        )
        assertTrue(probeResult.isSuccess)
        val (probeReq, probeBodyStr) = capturedRequests.last()
        assertEquals("https://api.deepseek.com/v1/tools/asr", probeReq.url.toString())
        assertFalse(probeBodyStr.contains("name=\"model\""))
        assertFalse(probeBodyStr.contains("name=\"response_format\""))
    }

    @Test
    fun singleDeepSeekKey_drivesFullLoop_asrPolishAskAiAndJevCopilot_andSupportsThirdPartySttRetention() = runBlocking {
        // 1. User enters 1 DeepSeek Key and clicks 1-tap activate -> full loop ready
        val initialWithKey = UnifiedProviderCatalog.updateProviderCredential(
            AppSettings(),
            ProviderCredential(
                providerId = UnifiedProviderCatalog.ID_DEEPSEEK,
                apiKey = "sk-ds-all-in-one"
            )
        )
        val fullLoopSettings = UnifiedProviderCatalog.applyQuickProviderSetup(
            settings = initialWithKey,
            providerId = UnifiedProviderCatalog.ID_DEEPSEEK,
            keepExistingThirdPartyStt = false
        )

        assertEquals(PipelineMode.DUAL_STAGE, fullLoopSettings.pipelineMode)
        assertEquals(UnifiedProviderCatalog.ID_DEEPSEEK, UnifiedProviderCatalog.inferSttProviderId(fullLoopSettings.sttConfig))
        assertEquals("https://api.deepseek.com/v1/tools/asr", fullLoopSettings.sttConfig.baseUrl)
        assertEquals("sk-ds-all-in-one", fullLoopSettings.sttConfig.apiKey)
        assertEquals("https://api.deepseek.com/v1", fullLoopSettings.llmConfig.baseUrl)
        assertEquals("sk-ds-all-in-one", fullLoopSettings.llmConfig.apiKey)
        assertEquals("deepseek-chat", fullLoopSettings.llmConfig.model)
        assertTrue(SetupStatsHelper.isApiConfigured(fullLoopSettings))
        assertTrue(SetupStatsHelper.formatActivePipelineBadge(fullLoopSettings).contains("DeepSeek ASR"))

        // 2. Verify full voice pipeline (Stage 1 /v1/tools/asr + Stage 2 /v1/chat/completions), Ask AI, and Jev Copilot
        val visitedPaths = mutableListOf<String>()
        val mockClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                val path = req.url.encodedPath
                visitedPaths.add(path)
                assertEquals("Bearer sk-ds-all-in-one", req.header("Authorization"))
                val respJson = when {
                    path.endsWith("/v1/tools/asr") -> {
                        val buf = Buffer()
                        req.body?.writeTo(buf)
                        val bodyText = buf.readUtf8()
                        assertFalse(bodyText.contains("name=\"model\""))
                        assertFalse(bodyText.contains("name=\"language\""))
                        """{"request_id":"req_asr_1","text":"嗯那个帮我跟王总确认明天下午三点的评审会","duration_ms":2800}"""
                    }
                    else -> {
                        val buf = Buffer()
                        req.body?.writeTo(buf)
                        val reqText = buf.readUtf8()
                        val content = if (reqText.contains("<subtext>")) {
                            """
                            <subtext>对方在确认会议时间，需干脆敲定。</subtext>
                            <opt tag="稳妥推进" risk="low">王总，明天下午三点的评审会我这边没问题，准时参会。</opt>
                            <opt tag="确认议程" risk="low">好的王总，明天下午三点见，相关材料我会提前发群里。</opt>
                            <opt tag="改期协商" risk="mid">王总，明天下午三点略微冲突，看能否顺延半小时到三点半？</opt>
                            <weights>0.5,0.35,0.15</weights>
                            """.trimIndent()
                        } else {
                            "王总，跟您确认一下明天下午三点的评审会。"
                        }
                        val escaped = content.replace("\n", "\\n").replace("\"", "\\\"")
                        """{"choices":[{"message":{"role":"assistant","content":"$escaped"}}]}"""
                    }
                }
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(respJson.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val engine = AiPipelineEngine(httpClient = mockClient)
        val pcmBytes = ByteArray(1600) { 2 }
        val tempWav = File.createTempFile("ds_loop_", ".wav").apply {
            writeBytes(WavAudioRecorder.buildWavHeader(pcmBytes.size) + pcmBytes)
            deleteOnExit()
        }

        val voiceResult = engine.processVoiceAudio(
            wavFile = tempWav,
            settings = fullLoopSettings,
            activeStyle = DefaultPresets.defaultStyles().first(),
            targetAppName = "飞书",
            selectedText = "",
            surroundingBeforeCursor = ""
        ).getOrThrow()

        assertEquals("嗯那个帮我跟王总确认明天下午三点的评审会", voiceResult.rawTranscript)
        assertEquals("王总，跟您确认一下明天下午三点的评审会。", voiceResult.finalOutput)
        assertTrue(visitedPaths.contains("/v1/tools/asr"))
        assertTrue(visitedPaths.contains("/v1/chat/completions"))

        val askAiResult = engine.runAskAiCardQuery(
            settings = fullLoopSettings,
            query = "用一句话总结",
            targetAppName = "飞书"
        ).getOrThrow()
        assertEquals("王总，跟您确认一下明天下午三点的评审会。", askAiResult.finalOutput)

        val jevResult = engine.runJevCopilot(
            settings = fullLoopSettings,
            activeStyle = DefaultPresets.defaultStyles().first(),
            domainProfile = JevDomainProfile.FORMAL_WORKFLOW,
            targetAppName = "飞书",
            chatContextMessages = listOf("对方: 明天下午三点评审会能参加吗？"),
            wavFile = tempWav
        ).getOrThrow()
        assertEquals(3, jevResult.first.options.size)
        assertEquals("嗯那个帮我跟王总确认明天下午三点的评审会", jevResult.second)

        // 3. Verify switching between third-party streaming STT retention and DeepSeek single-Key full loop
        val withGeminiLiveStt = UnifiedProviderCatalog.selectSttProvider(
            settings = UnifiedProviderCatalog.updateProviderCredential(
                fullLoopSettings,
                ProviderCredential(
                    providerId = UnifiedProviderCatalog.ID_GOOGLE,
                    apiKey = "AIzaGoogleLiveKey"
                )
            ),
            providerId = UnifiedProviderCatalog.ID_GOOGLE
        )
        assertEquals(SttProtocol.GEMINI_LIVE_WS, withGeminiLiveStt.sttConfig.protocol)

        val retainedThirdParty = UnifiedProviderCatalog.applyQuickProviderSetup(
            settings = withGeminiLiveStt,
            providerId = UnifiedProviderCatalog.ID_DEEPSEEK,
            keepExistingThirdPartyStt = true
        )
        assertEquals(SttProtocol.GEMINI_LIVE_WS, retainedThirdParty.sttConfig.protocol)
        assertEquals("AIzaGoogleLiveKey", retainedThirdParty.sttConfig.apiKey)
        assertEquals("deepseek-chat", retainedThirdParty.llmConfig.model)
        assertEquals("sk-ds-all-in-one", retainedThirdParty.llmConfig.apiKey)
    }

    @Test
    fun configBackupAndAppRepository_preserveAndNormalizeDeepSeekAsrSettings() {
        val configured = UnifiedProviderCatalog.applyQuickProviderSetup(
            settings = UnifiedProviderCatalog.updateProviderCredential(
                AppSettings(),
                ProviderCredential(
                    providerId = UnifiedProviderCatalog.ID_DEEPSEEK,
                    apiKey = "sk-ds-backup-777"
                )
            ),
            providerId = UnifiedProviderCatalog.ID_DEEPSEEK
        )

        val exportedJson = ApiConfigBackupHelper.exportSettingsJson(configured, includeSecrets = true)
        val restored = ApiConfigBackupHelper.parseAndMergeConfigJson(exportedJson, AppSettings()).getOrThrow()

        assertEquals("deepseek_asr", restored.sttConfig.presetId)
        assertEquals("https://api.deepseek.com/v1/tools/asr", restored.sttConfig.baseUrl)
        assertEquals("sk-ds-backup-777", restored.sttConfig.apiKey)
        assertEquals("deepseek-asr", restored.sttConfig.model)
        assertEquals("sk-ds-backup-777", restored.llmConfig.apiKey)
        assertEquals("sk-ds-backup-777", restored.providerVault[UnifiedProviderCatalog.ID_DEEPSEEK]?.apiKey)

        // Also test importing minimal DeepSeek STT JSON where baseUrl is https://api.deepseek.com/v1 and model is omitted
        val minimalJson = """
            {
              "pipelineMode": "DUAL_STAGE",
              "sttConfig": {
                "baseUrl": "https://api.deepseek.com/v1",
                "apiKey": "sk-ds-minimal"
              },
              "llmConfig": {
                "presetId": "deepseek",
                "baseUrl": "https://api.deepseek.com/v1",
                "apiKey": "sk-ds-minimal",
                "model": "deepseek-chat"
              }
            }
        """.trimIndent()
        val mergedMinimal = ApiConfigBackupHelper.parseAndMergeConfigJson(minimalJson, AppSettings()).getOrThrow()
        assertEquals("deepseek_asr", mergedMinimal.sttConfig.presetId)
        assertEquals("https://api.deepseek.com/v1/tools/asr", mergedMinimal.sttConfig.baseUrl)
        assertEquals("deepseek-asr", mergedMinimal.sttConfig.model)
        assertEquals("sk-ds-minimal", mergedMinimal.sttConfig.apiKey)

        // Verify AppRepository persistence round-trip with SecretVault
        val publicStore = InMemoryKeyValueStore()
        val secretStore = InMemoryKeyValueStore()
        val cipher = JvmAesGcmCipher()
        val repo1 = AppRepository(
            appContext = null,
            publicStore = publicStore,
            secretVault = SecretVault(secretStore, cipher)
        )
        repo1.updateSettings { mergedMinimal }

        val repo2 = AppRepository(
            appContext = null,
            publicStore = publicStore,
            secretVault = SecretVault(secretStore, cipher)
        )
        assertEquals("https://api.deepseek.com/v1/tools/asr", repo2.settings.value.sttConfig.baseUrl)
        assertEquals("deepseek-asr", repo2.settings.value.sttConfig.model)
        assertEquals("sk-ds-minimal", repo2.settings.value.sttConfig.apiKey)
        assertEquals("sk-ds-minimal", repo2.settings.value.llmConfig.apiKey)
    }
}
