package com.typefree.ime.v26

import com.google.gson.JsonParser
import com.typefree.ime.audio.WavAudioRecorder
import com.typefree.ime.data.AppSettings
import com.typefree.ime.data.DefaultPresets
import com.typefree.ime.data.LlmConfig
import com.typefree.ime.data.MultimodalConfig
import com.typefree.ime.data.MultimodalProtocol
import com.typefree.ime.data.PipelineMode
import com.typefree.ime.data.ProviderCredential
import com.typefree.ime.data.SnippetEntry
import com.typefree.ime.data.SnippetMatcher
import com.typefree.ime.data.SttConfig
import com.typefree.ime.data.SttProtocol
import com.typefree.ime.data.ThinkingMode
import com.typefree.ime.data.UnifiedProviderCatalog
import com.typefree.ime.data.VoiceIntentMode
import com.typefree.ime.ime.flypy.FlypyAction
import com.typefree.ime.ime.flypy.FlypyDictionary
import com.typefree.ime.ime.flypy.FlypyEngine
import com.typefree.ime.network.AiPipelineEngine
import com.typefree.ime.network.EndpointResolver
import com.typefree.ime.network.asr.GeminiLiveTranscribeClient
import com.typefree.ime.network.asr.StreamingAsrClient
import com.typefree.ime.network.asr.StreamingAsrSession
import com.typefree.ime.ui.screens.ApiConfigBackupHelper
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.Buffer
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

class V26GeminiLiveVaultAndSnippetsTest {

    @Before
    fun clearCaches() {
        AiPipelineEngine.clearUnsupportedThinkingCache()
    }

    @Test
    fun `resolveGeminiLiveWsEndpoint handles official Google v1beta, custom HTTPS proxy, and wss URLs`() {
        val fakeKey = "AIzaFakeUnitTestKey123"

        val officialWs = EndpointResolver.resolveGeminiLiveWsEndpoint(
            rawBaseUrl = "https://generativelanguage.googleapis.com/v1beta",
            apiKey = fakeKey
        )
        assertEquals(
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$fakeKey",
            officialWs
        )

        val workerProxyWs = EndpointResolver.resolveGeminiLiveWsEndpoint(
            rawBaseUrl = "https://gemini-proxy.example.workers.dev/v1beta",
            apiKey = fakeKey
        )
        assertEquals(
            "wss://gemini-proxy.example.workers.dev/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$fakeKey",
            workerProxyWs
        )

        val explicitWss = EndpointResolver.resolveGeminiLiveWsEndpoint(
            rawBaseUrl = "wss://custom-live.example.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent",
            apiKey = fakeKey
        )
        assertEquals(
            "wss://custom-live.example.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$fakeKey",
            explicitWss
        )

        assertTrue(EndpointResolver.isGeminiNativeHttpUrl("https://generativelanguage.googleapis.com/v1beta"))
        assertTrue(EndpointResolver.isGeminiNativeHttpUrl("https://my-gateway.example.com/v1beta"))
        assertFalse(EndpointResolver.isGeminiNativeHttpUrl("https://generativelanguage.googleapis.com/v1beta/openai"))
        assertFalse(EndpointResolver.isGeminiNativeHttpUrl("https://api.deepseek.com/v1"))
    }

    @Test
    fun `GeminiLiveTranscribeClient builds SMART setup with 200-term customVocabulary and parses events`() {
        val hotwords = (1..240).map { "专有热词_$it" }
        val setupRaw = GeminiLiveTranscribeClient.buildSetupJson(
            model = "gemini-3.5-transcribe-live",
            language = "zh",
            hotwords = hotwords,
            smartMode = true
        )
        val root = JsonParser.parseString(setupRaw).asJsonObject
        val setup = root.getAsJsonObject("setup")
        assertEquals("models/gemini-3.5-transcribe-live", setup.get("model").asString)
        assertEquals(
            "TEXT",
            setup.getAsJsonObject("generationConfig")
                .getAsJsonArray("responseModalities")[0].asString
        )

        val transCfg = setup.getAsJsonObject("inputAudioTranscription")
        assertEquals("SMART", transCfg.get("mode").asString)
        assertEquals("zh-CN", transCfg.getAsJsonArray("languageCodes")[0].asString)
        val vocabArr = transCfg.getAsJsonArray("customVocabulary")
        assertEquals(200, vocabArr.size())
        assertEquals("专有热词_1", vocabArr[0].asString)
        assertEquals("专有热词_200", vocabArr[199].asString)

        // Fallback normalization if user accidentally left a non-live model ID
        assertEquals(
            "models/gemini-3.5-transcribe-live",
            GeminiLiveTranscribeClient.normalizeLiveTranscribeModel("whisper-1")
        )

        // Audio chunk & Hybrid VAD audioStreamEnd frame
        val chunkJson = JsonParser.parseString(
            GeminiLiveTranscribeClient.buildAudioChunkJson(byteArrayOf(1, 2, 3, 4))
        ).asJsonObject
        val rtAudio = chunkJson.getAsJsonObject("realtimeInput").getAsJsonObject("audio")
        assertEquals("audio/pcm;rate=16000", rtAudio.get("mimeType").asString)
        assertTrue(rtAudio.get("data").asString.isNotEmpty())

        val endJson = JsonParser.parseString(
            GeminiLiveTranscribeClient.buildAudioStreamEndJson()
        ).asJsonObject
        assertTrue(endJson.getAsJsonObject("realtimeInput").get("audioStreamEnd").asBoolean)

        // Server frame parsing (setupComplete, interim, final, turnComplete, error)
        assertEquals(
            GeminiLiveTranscribeClient.LiveServerEvent.SetupComplete,
            GeminiLiveTranscribeClient.parseServerMessage("""{"setupComplete":{}}""")
        )
        val interimEv = GeminiLiveTranscribeClient.parseServerMessage(
            """{"serverContent":{"interimInputTranscription":{"text":"今天下午四点"}}}"""
        ) as GeminiLiveTranscribeClient.LiveServerEvent.TranscriptionUpdate
        assertEquals("今天下午四点", interimEv.interimText)
        assertNull(interimEv.finalText)
        assertFalse(interimEv.turnComplete)

        val finalEv = GeminiLiveTranscribeClient.parseServerMessage(
            """{"serverContent":{"inputTranscription":{"text":"今天下午四点开产品评审会。"},"turnComplete":true}}"""
        ) as GeminiLiveTranscribeClient.LiveServerEvent.TranscriptionUpdate
        assertEquals("今天下午四点开产品评审会。", finalEv.finalText)
        assertTrue(finalEv.turnComplete)

        val errEv = GeminiLiveTranscribeClient.parseServerMessage(
            """{"error":{"code":403,"message":"API key invalid"}}"""
        ) as GeminiLiveTranscribeClient.LiveServerEvent.ErrorEvent
        assertEquals(403, errEv.code)
        assertEquals("API key invalid", errEv.message)
    }

    @Test
    fun `AiPipelineEngine runs single-stage Gemini Live SMART dictation, 0ms voice snippets, and single-key Flash-Lite fallback`() = runBlocking {
        var capturedUrl = ""
        var capturedRequestJson = ""
        var httpCallCount = 0

        val mockHttp = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                httpCallCount += 1
                val req = chain.request()
                capturedUrl = req.url.toString()
                val buf = Buffer()
                req.body?.writeTo(buf)
                capturedRequestJson = buf.readUtf8()

                val respJson = """
                    {
                      "candidates": [
                        {
                          "content": {
                            "parts": [
                              {"text": "尊敬的张总，麻烦确认一下本周五合同审批进度，非常感谢！"}
                            ]
                          }
                        }
                      ]
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

        val fakeGeminiLiveClient = object : StreamingAsrClient {
            override fun openSession(
                config: SttConfig,
                hotwords: List<String>
            ): StreamingAsrSession {
                return object : StreamingAsrSession {
                    override val partialText: StateFlow<String> = MutableStateFlow("")
                    override fun sendPcmFrame(pcm16Le: ByteArray) {}
                    override suspend fun finishAndAwaitFinal(timeoutMs: Long): Result<String> {
                        return Result.success("我们改到下午四点在飞书开会。")
                    }
                    override fun cancel() {}
                }
            }
        }

        val engine = AiPipelineEngine(
            httpClient = mockHttp,
            geminiLiveAsrClient = fakeGeminiLiveClient
        )

        val baseSettings = UnifiedProviderCatalog.applyQuickProviderSetup(
            settings = UnifiedProviderCatalog.updateProviderCredential(
                settings = AppSettings(
                    snippets = listOf(
                        SnippetEntry(
                            id = "work_email",
                            title = "工作邮箱",
                            voiceTriggers = "发我的邮箱, 我的工作邮箱",
                            flypyCode = ";yx",
                            content = "founder@typefree.ai"
                        )
                    )
                ),
                updated = ProviderCredential(
                    providerId = UnifiedProviderCatalog.ID_GOOGLE,
                    apiKey = "AIzaFakeUnitTestKey999"
                )
            ),
            providerId = UnifiedProviderCatalog.ID_GOOGLE
        ).copy(
            // Even if legacy llmConfig.apiKey was blanked, single-key vault fallback must recover!
            llmConfig = LlmConfig(enabled = false, apiKey = "")
        )

        val dummyWav = File.createTempFile("v26_test", ".wav").apply {
            writeBytes(WavAudioRecorder.buildWavHeader(3200) + ByteArray(3200))
            deleteOnExit()
        }

        // 1. Single-Stage Gemini Live SMART Dictation -> 0 HTTP LLM calls!
        val dictateRes = engine.processVoiceAudio(
            wavFile = dummyWav,
            settings = baseSettings,
            activeStyle = DefaultPresets.defaultStyles().first(),
            targetAppName = "飞书",
            selectedText = "",
            surroundingBeforeCursor = "",
            intentMode = VoiceIntentMode.DICTATE,
            preTranscribedText = "我们改到下午四点在飞书开会。",
            sttTailWaitMs = 480L
        ).getOrThrow()

        assertEquals("我们改到下午四点在飞书开会。", dictateRes.finalOutput)
        assertEquals("Gemini Live 智能流式", dictateRes.modeSummary)
        assertEquals(0, httpCallCount)

        // 2. 0ms Voice Snippet Match ("帮我发我的工作邮箱") -> 0 HTTP LLM calls!
        val snippetRes = engine.processVoiceAudio(
            wavFile = dummyWav,
            settings = baseSettings.copy(
                llmConfig = baseSettings.llmConfig.copy(enabled = true, apiKey = "AIzaFakeUnitTestKey999")
            ),
            activeStyle = DefaultPresets.defaultStyles().first(),
            targetAppName = "微信",
            selectedText = "",
            surroundingBeforeCursor = "",
            intentMode = VoiceIntentMode.DICTATE,
            preTranscribedText = "帮我发我的工作邮箱。",
            sttTailWaitMs = 320L
        ).getOrThrow()

        assertEquals("founder@typefree.ai", snippetRes.finalOutput)
        assertEquals("口令展开 (工作邮箱)", snippetRes.modeSummary)
        assertEquals(0L, snippetRes.llmLatencyMs)
        assertEquals(0, httpCallCount)

        // 3. Single-Key Generative Fallback on HELP_ME_WRITE -> auto-routes to gemini-3.5-flash-lite + MINIMAL thinking!
        val writeRes = engine.processVoiceAudio(
            wavFile = dummyWav,
            settings = baseSettings,
            activeStyle = DefaultPresets.defaultStyles().first(),
            targetAppName = "飞书",
            selectedText = "",
            surroundingBeforeCursor = "",
            intentMode = VoiceIntentMode.HELP_ME_WRITE,
            preTranscribedText = "礼貌催一下张总周五合同审批进度",
            sttTailWaitMs = 410L
        ).getOrThrow()

        assertEquals(1, httpCallCount)
        assertTrue(capturedUrl.contains("models/gemini-3.5-flash-lite:generateContent"))
        assertTrue(capturedRequestJson.contains("\"thinkingLevel\":\"MINIMAL\""))
        assertTrue(writeRes.finalOutput.contains("尊敬的张总"))
    }

    @Test
    fun `UnifiedProviderCatalog hydrates v2_5 keys and ApiConfigBackupHelper round-trips redacted vault and snippets`() {
        val legacySettings = AppSettings(
            pipelineMode = PipelineMode.DUAL_STAGE,
            sttConfig = SttConfig(
                presetId = "volcengine_sauc_ws",
                protocol = SttProtocol.VOLCENGINE_SAUC_WS,
                baseUrl = "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async",
                apiKey = "volc_speech_tok_legacy",
                appId = "12345678"
            ),
            llmConfig = LlmConfig(
                enabled = true,
                presetId = "deepseek",
                baseUrl = "https://api.deepseek.com/v1",
                apiKey = "sk-deepseek-legacy",
                model = "deepseek-chat"
            ),
            multimodalConfig = MultimodalConfig(
                presetId = "gemini",
                protocol = MultimodalProtocol.GEMINI_NATIVE,
                baseUrl = "https://generativelanguage.googleapis.com/v1beta",
                apiKey = "AIzaLegacyGeminiKey",
                model = "gemini-3.5-flash-lite"
            )
        )

        val hydrated = UnifiedProviderCatalog.hydrateVaultFromSettings(legacySettings)
        assertEquals("volc_speech_tok_legacy", hydrated[UnifiedProviderCatalog.ID_VOLCENGINE]?.sttApiKey)
        assertEquals("12345678", hydrated[UnifiedProviderCatalog.ID_VOLCENGINE]?.appId)
        assertEquals("sk-deepseek-legacy", hydrated[UnifiedProviderCatalog.ID_DEEPSEEK]?.apiKey)
        assertEquals("AIzaLegacyGeminiKey", hydrated[UnifiedProviderCatalog.ID_GOOGLE]?.apiKey)

        // 1-tap switch to Google Gemini primary workflow
        val googleActivated = UnifiedProviderCatalog.applyQuickProviderSetup(
            legacySettings,
            UnifiedProviderCatalog.ID_GOOGLE
        )
        assertEquals(SttProtocol.GEMINI_LIVE_WS, googleActivated.sttConfig.protocol)
        assertEquals("gemini-3.5-transcribe-live", googleActivated.sttConfig.model)
        assertEquals("AIzaLegacyGeminiKey", googleActivated.sttConfig.apiKey)
        assertFalse(googleActivated.llmConfig.enabled)
        assertEquals("gemini-3.5-flash-lite", googleActivated.llmConfig.model)

        // Redacted export + merge import preserves existing secrets while updating v2.6 preferences & snippets
        val customExportSource = googleActivated.copy(
            pauseMediaWhileRecording = false,
            whisperModeEnabled = true,
            equalEqualAskAiEnabled = false,
            snippets = listOf(
                SnippetEntry(
                    id = "addr_1",
                    title = "公司地址",
                    voiceTriggers = "发公司地址",
                    flypyCode = ";dz",
                    content = "北京市海淀区中关村软件园"
                )
            )
        )
        val redactedJson = ApiConfigBackupHelper.exportSettingsJson(customExportSource, includeSecrets = false)
        assertFalse(redactedJson.contains("AIzaLegacyGeminiKey"))
        assertFalse(redactedJson.contains("sk-deepseek-legacy"))

        val merged = ApiConfigBackupHelper.parseAndMergeConfigJson(
            rawJson = redactedJson,
            existingSettings = googleActivated
        ).getOrThrow()

        assertEquals("AIzaLegacyGeminiKey", merged.providerVault[UnifiedProviderCatalog.ID_GOOGLE]?.apiKey)
        assertEquals("sk-deepseek-legacy", merged.providerVault[UnifiedProviderCatalog.ID_DEEPSEEK]?.apiKey)
        assertFalse(merged.pauseMediaWhileRecording)
        assertTrue(merged.whisperModeEnabled)
        assertFalse(merged.equalEqualAskAiEnabled)
        assertEquals(1, merged.snippets.size)
        assertEquals("北京市海淀区中关村软件园", merged.snippets.first().content)
        assertEquals(";dz", merged.snippets.first().flypyCode)
    }

    @Test
    fun `SnippetMatcher and FlypyEngine expand semicolon snippets without breaking Xiaohe quick symbols or push-commit`() {
        // 1. `==` Direct Ask AI parser
        assertEquals(
            "用英文礼貌回复收到并明天反馈",
            SnippetMatcher.extractEqualEqualAskAiQuery("  ==用英文礼貌回复收到并明天反馈 ")
        )
        assertEquals(
            "帮我算一下 128 * 35",
            SnippetMatcher.extractEqualEqualAskAiQuery("＝＝帮我算一下 128 * 35")
        )
        assertNull(SnippetMatcher.extractEqualEqualAskAiQuery("==="))
        assertNull(SnippetMatcher.extractEqualEqualAskAiQuery("================"))
        assertNull(SnippetMatcher.extractEqualEqualAskAiQuery("=="))
        assertNull(SnippetMatcher.extractEqualEqualAskAiQuery("正常聊天内容 a == b"))

        // 2. Voice Snippet Matcher never hijacks normal conversational sentences
        val snippets = listOf(
            SnippetEntry(
                id = "s1",
                title = "收货地址",
                voiceTriggers = "我的收货地址, 发收货地址",
                flypyCode = ";sd",
                content = "上海市浦东新区张江高科技园区88号"
            )
        )
        assertNotNull(SnippetMatcher.matchVoiceTranscript("请帮我输入我的收货地址。", snippets))
        assertNull(SnippetMatcher.matchVoiceTranscript("请问我的收货地址改成功了吗？", snippets))

        // 3. FlypyEngine `;sd` 3-char snippet vs built-in 2-char `;s` (`……`) & `;q` (`：`)
        val dictFile = listOf(
            File("src/main/assets/flypy_dict.tsv"),
            File("app/src/main/assets/flypy_dict.tsv")
        ).first { it.exists() }
        val dzFile = listOf(
            File("src/main/assets/flypy_dz.tsv"),
            File("app/src/main/assets/flypy_dz.tsv")
        ).first { it.exists() }

        val dict = FileInputStream(dictFile).use { dIn ->
            FileInputStream(dzFile).use { zIn ->
                FlypyDictionary.loadFromStreams(dIn, zIn)
            }
        }
        val engine = FlypyEngine(dict)
        engine.updateSnippets(snippets)

        // `;q` has NO 3-char custom snippet continuation -> auto-commits `：“` on 2nd key!
        assertEquals(FlypyAction.ConsumedComposing, engine.onCharInput(';'))
        assertEquals(FlypyAction.CommitText("：“", remainComposing = false), engine.onCharInput('q'))

        // `;sd` -> holds on `;s` (showing `……` as #1 candidate), then auto-commits full address on `d`!
        assertEquals(FlypyAction.ConsumedComposing, engine.onCharInput(';'))
        assertEquals(FlypyAction.ConsumedComposing, engine.onCharInput('s'))
        assertEquals("……", engine.candidates.firstOrNull()?.text)
        assertEquals(
            FlypyAction.CommitText("上海市浦东新区张江高科技园区88号", remainComposing = false),
            engine.onCharInput('d')
        )

        // `;s` + Space still outputs `……` cleanly
        assertEquals(FlypyAction.ConsumedComposing, engine.onCharInput(';'))
        assertEquals(FlypyAction.ConsumedComposing, engine.onCharInput('s'))
        assertEquals(FlypyAction.CommitText("……", remainComposing = false), engine.onSpace())

        // `;sw` push-commits `……` and starts composing `w` (`我`) without breaking Xiaohe muscle memory!
        assertEquals(FlypyAction.ConsumedComposing, engine.onCharInput(';'))
        assertEquals(FlypyAction.ConsumedComposing, engine.onCharInput('s'))
        assertEquals(FlypyAction.CommitText("……", remainComposing = true), engine.onCharInput('w'))
        assertEquals("w", engine.composingCode)
        assertEquals("我", engine.candidates.firstOrNull()?.text)
    }

    @Test
    fun `WavAudioRecorder Whisper VAD and soft-knee AGC boost quiet whispers without amplifying hiss or clipping`() {
        // 1. Whisper VAD accepts soft library/late-night whisper (RMS ~75..118) that normal VAD rejects
        val softWhisperFrames = listOf(22.0, 74.0, 112.0, 118.0, 68.0, 20.0)
        assertFalse(WavAudioRecorder.scanFramesForSpeech(softWhisperFrames, whisperMode = false))
        assertTrue(WavAudioRecorder.scanFramesForSpeech(softWhisperFrames, whisperMode = true))

        // Single-frame mechanical tap click is still rejected even in Whisper mode!
        val singleTapFrames = listOf(190.0, 18.0, 16.0, 19.0)
        assertFalse(WavAudioRecorder.scanFramesForSpeech(singleTapFrames, whisperMode = true))

        // 2. Real-time streaming `boostWhisperPcm16Le` noise gate + 3.8x boost + soft-knee anti-clipping
        val silentFloor = shortsToPcm16Le(shortArrayOf(0, 25, -38, 12, -5))
        assertArrayEquals(silentFloor, WavAudioRecorder.boostWhisperPcm16Le(silentFloor))

        val quietWhisperPcm = shortsToPcm16Le(shortArrayOf(1000, -1200, 500))
        val boostedWhisper = pcm16LeToShorts(WavAudioRecorder.boostWhisperPcm16Le(quietWhisperPcm))
        assertEquals(3800, boostedWhisper[0].toInt())
        assertEquals(-4560, boostedWhisper[1].toInt())

        // Loud sudden speech (`10000` -> `38000` raw) is soft-knee compressed (< 32767) instead of hard square-wave clipping
        val loudMixPcm = shortsToPcm16Le(shortArrayOf(6000, 10000, -10000))
        val softKneed = pcm16LeToShorts(WavAudioRecorder.boostWhisperPcm16Le(loudMixPcm))
        assertTrue(softKneed[1] in 26000..30000)
        assertEquals(-softKneed[1].toInt(), softKneed[2].toInt())

        // 3. Offline WAV `normalizePcm16Le` boosts very soft whisper (`peak = 120`) by 9.0x in whisperMode
        val ultraSoftSamples = ShortArray(4000) { idx -> if (idx == 2000) 120 else 15 }
        val ultraSoftBytes = shortsToPcm16Le(ultraSoftSamples)
        // Normal mode leaves peak=120 (< 220) untouched
        assertArrayEquals(
            ultraSoftBytes,
            WavAudioRecorder.normalizePcm16Le(ultraSoftBytes, sampleRate = 16000, whisperMode = false)
        )
        // Whisper mode boosts peak=120 by 9.0x -> 1080
        val normalizedWhisper = pcm16LeToShorts(
            WavAudioRecorder.normalizePcm16Le(ultraSoftBytes, sampleRate = 16000, whisperMode = true)
        )
        assertTrue(abs(normalizedWhisper[2000].toInt() - 1080) <= 2)
    }

    @Test
    fun `v2_6_1 trailing silence frames after final inputTranscription return in 0ms without 3600ms deadlock`() = runBlocking {
        val sockets = mutableListOf<FakeLiveWebSocket>()
        val client = GeminiLiveTranscribeClient(
            httpClient = OkHttpClient(),
            webSocketFactory = { req, listener ->
                FakeLiveWebSocket(req, listener).also { sockets.add(it) }
            },
            interimQuiescenceMs = 400L,
            finalizedQuiescenceMs = 300L,
            postFinalCoalesceMs = 10L
        )
        val cfg = SttConfig(
            presetId = "gemini_live_ws",
            protocol = SttProtocol.GEMINI_LIVE_WS,
            baseUrl = "https://generativelanguage.googleapis.com/v1beta",
            apiKey = "AIzaFakeUnitTestKey123",
            model = "gemini-3.5-transcribe-live"
        )

        val session = client.openSession(cfg, listOf("拼多多"))
        val ws = sockets.single()
        ws.emitOpen()
        ws.emitServerBinaryJson("""{"setupComplete":{}}""")

        // User speaks "拼多多" (voiced PCM)
        val voicedChunk = shortsToPcm16Le(ShortArray(1600) { 1800 })
        assertTrue(GeminiLiveTranscribeClient.isVoicedPcmChunk(voicedChunk))
        session.sendPcmFrame(voicedChunk)

        // Server pushes inputTranscription BEFORE user's finger leaves the mic button
        ws.emitServerBinaryJson("""{"serverContent":{"inputTranscription":{"text":"拼多多"}}}""")
        assertEquals("拼多多", session.partialText.value)

        // 200-300ms of trailing ambient silence frames arrive before finger release
        val silentTailChunk = shortsToPcm16Le(ShortArray(1600) { 35 })
        assertFalse(GeminiLiveTranscribeClient.isVoicedPcmChunk(silentTailChunk))
        session.sendPcmFrame(silentTailChunk)
        session.sendPcmFrame(silentTailChunk)

        val t0 = System.currentTimeMillis()
        val finalOut = session.finishAndAwaitFinal(timeoutMs = 2400L).getOrThrow()
        val elapsedMs = System.currentTimeMillis() - t0

        assertEquals("拼多多", finalOut)
        assertTrue("Expected instant <50ms return on finalized + silent tail, got ${elapsedMs}ms", elapsedMs < 80L)
        assertTrue(ws.sentTexts.last().contains("\"audioStreamEnd\":true"))
    }

    @Test
    fun `v2_6_1 interim-only short utterance and generationComplete converge fast without 3600ms or 10000ms stall`() = runBlocking {
        val sockets = mutableListOf<FakeLiveWebSocket>()
        val client = GeminiLiveTranscribeClient(
            httpClient = OkHttpClient(),
            webSocketFactory = { req, listener ->
                FakeLiveWebSocket(req, listener).also { sockets.add(it) }
            },
            interimQuiescenceMs = 90L,
            finalizedQuiescenceMs = 70L,
            postEndInterimSettleMs = 60L,
            postFinalCoalesceMs = 10L,
            warmStreamFinishCapMs = 600L
        )
        val cfg = SttConfig(
            presetId = "gemini_live_ws",
            protocol = SttProtocol.GEMINI_LIVE_WS,
            baseUrl = "https://generativelanguage.googleapis.com/v1beta",
            apiKey = "AIzaFakeUnitTestKey123",
            model = "gemini-3.5-transcribe-live"
        )
        val voicedChunk = shortsToPcm16Le(ShortArray(1600) { 1500 })

        // Case A: Short word ("淘宝") where server ONLY emits interimInputTranscription and goes silent
        val s1 = client.openSession(cfg)
        val ws1 = sockets.last()
        ws1.emitOpen()
        ws1.emitServerBinaryJson("""{"setupComplete":{}}""")
        s1.sendPcmFrame(voicedChunk)
        ws1.emitServerBinaryJson("""{"serverContent":{"interimInputTranscription":{"text":"淘宝"}}}""")

        val t0 = System.currentTimeMillis()
        val res1 = s1.finishAndAwaitFinal(timeoutMs = 2400L).getOrThrow()
        val elapsed1 = System.currentTimeMillis() - t0
        assertEquals("淘宝", res1)
        assertTrue("Expected fast quiescence <260ms instead of 2400/3600ms timeout, got ${elapsed1}ms", elapsed1 < 260L)

        // Case B: Server emits generationComplete (without turnComplete) -> converges immediately!
        val evGen = GeminiLiveTranscribeClient.parseServerMessage(
            """{"serverContent":{"generationComplete":true}}"""
        ) as GeminiLiveTranscribeClient.LiveServerEvent.TranscriptionUpdate
        assertTrue(evGen.turnComplete)

        val s2 = client.openSession(cfg)
        val ws2 = sockets.last()
        ws2.emitOpen()
        ws2.emitServerBinaryJson("""{"setupComplete":{}}""")
        s2.sendPcmFrame(voicedChunk)
        ws2.emitServerBinaryJson("""{"serverContent":{"interimInputTranscription":{"text":"明天见"}}}""")

        val finishJob = async { s2.finishAndAwaitFinal(timeoutMs = 2400L).getOrThrow() }
        delay(15L)
        ws2.emitServerBinaryJson("""{"serverContent":{"generationComplete":true}}""")
        assertEquals("明天见", finishJob.await())
    }

    @Test
    fun `v2_6_1 cold handshake buffered frames flush and prewarmed session pool eliminates cold setup`() = runBlocking {
        var fakeNowMs = 10_000L
        val sockets = mutableListOf<FakeLiveWebSocket>()
        val client = GeminiLiveTranscribeClient(
            httpClient = OkHttpClient(),
            webSocketFactory = { req, listener ->
                FakeLiveWebSocket(req, listener).also { sockets.add(it) }
            },
            interimQuiescenceMs = 80L,
            postFinalCoalesceMs = 10L,
            maxPrewarmIdleMs = 45_000L,
            clockMs = { fakeNowMs }
        )
        val cfg = SttConfig(
            presetId = "gemini_live_ws",
            protocol = SttProtocol.GEMINI_LIVE_WS,
            baseUrl = "https://generativelanguage.googleapis.com/v1beta",
            apiKey = "AIzaFakeUnitTestKey123",
            model = "gemini-3.5-transcribe-live"
        )
        val voicedChunk = shortsToPcm16Le(ShortArray(1600) { 1600 })

        // 1. Cold handshake: user speaks 2 chunks & releases BEFORE SetupComplete arrives
        val coldSession = client.openSession(cfg)
        val coldWs = sockets.single()
        coldWs.emitOpen() // sends setup JSON only
        assertEquals(1, coldWs.sentTexts.size)
        coldSession.sendPcmFrame(voicedChunk)
        coldSession.sendPcmFrame(voicedChunk)
        // Still waiting for setupComplete -> chunks are buffered!
        assertEquals(1, coldWs.sentTexts.size)

        val coldFinishDeferred = async { coldSession.finishAndAwaitFinal(timeoutMs = 2000L).getOrThrow() }
        delay(15L)
        // Now setupComplete arrives -> flushes 2 audio chunks + audioStreamEnd immediately!
        coldWs.emitServerBinaryJson("""{"setupComplete":{}}""")
        assertEquals(4, coldWs.sentTexts.size)
        assertTrue(coldWs.sentTexts[1].contains("audio/pcm;rate=16000"))
        assertTrue(coldWs.sentTexts[2].contains("audio/pcm;rate=16000"))
        assertTrue(coldWs.sentTexts[3].contains("\"audioStreamEnd\":true"))

        coldWs.emitServerBinaryJson("""{"serverContent":{"inputTranscription":{"text":"冷启动缓冲成功"}}}""")
        assertEquals("冷启动缓冲成功", coldFinishDeferred.await())

        // 2. Prewarmed session pool: prewarm completes handshake ahead of time -> openSession reuses 0ms hot socket!
        client.prewarmSession(cfg, listOf("TypeFree"))
        assertEquals(2, sockets.size)
        val warmWs = sockets.last()
        warmWs.emitOpen()
        warmWs.emitServerBinaryJson("""{"setupComplete":{}}""")

        // Redundant prewarm call within 45s is idempotent (no extra socket created)
        fakeNowMs += 5_000L
        client.prewarmSession(cfg, listOf("TypeFree"))
        assertEquals(2, sockets.size)

        // User taps mic -> claims warmWs directly; first PCM chunk transmits immediately!
        val claimedSession = client.openSession(cfg, listOf("TypeFree"))
        assertEquals(2, sockets.size)
        claimedSession.sendPcmFrame(voicedChunk)
        assertEquals(2, warmWs.sentTexts.size) // [0]=setup, [1]=immediate audio chunk!
        warmWs.emitServerBinaryJson("""{"serverContent":{"inputTranscription":{"text":"热连接秒开"}}}""")
        assertEquals("热连接秒开", claimedSession.finishAndAwaitFinal().getOrThrow())

        // 3. Expired prewarm (>45s idle) is evicted and replaced cleanly
        client.prewarmSession(cfg, listOf("TypeFree"))
        assertEquals(3, sockets.size)
        val staleWs = sockets.last()
        fakeNowMs += 46_000L
        val freshAfterExpiry = client.openSession(cfg, listOf("TypeFree"))
        assertTrue(staleWs.cancelled)
        assertEquals(4, sockets.size)
        freshAfterExpiry.cancel()
    }

    @Test
    fun `v2_6_1 respects user manual gemini-3_8-flash selection even when Stage-2 dictation switch is off`() = runBlocking {
        // Explicit user model selection is 100% honored at runtime without special-case interception.
        val manual38Settings = AppSettings(
            pipelineMode = PipelineMode.DUAL_STAGE,
            sttConfig = SttConfig(
                presetId = "gemini_live_ws",
                protocol = SttProtocol.GEMINI_LIVE_WS,
                baseUrl = "https://generativelanguage.googleapis.com/v1beta",
                apiKey = "AIzaFakeKey",
                model = "gemini-3.5-transcribe-live"
            ),
            llmConfig = LlmConfig(
                enabled = false,
                presetId = "gemini_llm",
                baseUrl = "https://generativelanguage.googleapis.com/v1beta",
                apiKey = "AIzaFakeKey",
                model = "gemini-3.8-flash",
                thinkingMode = ThinkingMode.DISABLED
            )
        )

        var hitUrl = ""
        val mockHttp = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val req = chain.request()
                hitUrl = req.url.toString()
                val resp = """{"candidates":[{"content":{"parts":[{"text":"Pro适合中小户型，Max适合大客厅除醛。"}]}}]}"""
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(resp.toResponseBody("application/json".toMediaType()))
                    .build()
            })
            .build()

        val engine = AiPipelineEngine(httpClient = mockHttp)
        val dummyWav = File.createTempFile("v261_llm", ".wav").apply {
            writeBytes(WavAudioRecorder.buildWavHeader(3200) + ByteArray(3200))
            deleteOnExit()
        }
        val out = engine.processVoiceAudio(
            wavFile = dummyWav,
            settings = manual38Settings,
            activeStyle = DefaultPresets.defaultStyles().first(),
            targetAppName = "系统桌面",
            selectedText = "",
            surroundingBeforeCursor = "",
            intentMode = VoiceIntentMode.HELP_ME_WRITE,
            preTranscribedText = "小米6 Pro和6 Max空气净化器区别",
            sttTailWaitMs = 120L
        ).getOrThrow()

        assertTrue("Expected exact user-selected gemini-3.8-flash, got $hitUrl", hitUrl.contains("models/gemini-3.8-flash:generateContent"))
        assertTrue(out.finalOutput.contains("Max"))
    }

    private class FakeLiveWebSocket(
        private val req: Request,
        private val listener: WebSocketListener
    ) : WebSocket {
        val sentTexts = mutableListOf<String>()
        var closedCode: Int? = null
        var cancelled = false

        fun emitOpen() {
            val response = Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(101)
                .message("Switching Protocols")
                .body("".toResponseBody(null))
                .build()
            listener.onOpen(this, response)
        }

        fun emitServerBinaryJson(json: String) {
            listener.onMessage(this, json.encodeUtf8())
        }

        override fun request(): Request = req
        override fun queueSize(): Long = 0L
        override fun send(text: String): Boolean {
            sentTexts.add(text)
            return true
        }
        override fun send(bytes: ByteString): Boolean = true
        override fun close(code: Int, reason: String?): Boolean {
            closedCode = code
            return true
        }
        override fun cancel() {
            cancelled = true
        }
    }

    private fun shortsToPcm16Le(samples: ShortArray): ByteArray {
        val buf = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) buf.putShort(s)
        return buf.array()
    }

    private fun pcm16LeToShorts(bytes: ByteArray): ShortArray {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return ShortArray(bytes.size / 2) { buf.short }
    }
}
