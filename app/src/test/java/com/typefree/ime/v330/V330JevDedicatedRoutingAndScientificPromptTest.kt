package com.typefree.ime.v330

import com.google.gson.JsonParser
import com.typefree.ime.data.AppSettings
import com.typefree.ime.data.DefaultPresets
import com.typefree.ime.data.InMemoryKeyValueStore
import com.typefree.ime.data.JvmAesGcmCipher
import com.typefree.ime.data.LlmConfig
import com.typefree.ime.data.ModelCapabilitySlot
import com.typefree.ime.data.MultimodalConfig
import com.typefree.ime.data.MultimodalProtocol
import com.typefree.ime.data.PipelineMode
import com.typefree.ime.data.ProviderCredential
import com.typefree.ime.data.SecretVault
import com.typefree.ime.data.SttConfig
import com.typefree.ime.data.SttProtocol
import com.typefree.ime.data.ThinkingMode
import com.typefree.ime.data.ToneStyle
import com.typefree.ime.data.UnifiedProviderCatalog
import com.typefree.ime.ime.jev.JevDomainProfile
import com.typefree.ime.ime.jev.JevResponseParser
import com.typefree.ime.network.AiPipelineEngine
import com.typefree.ime.network.PromptBuilder
import com.typefree.ime.ui.screens.ApiConfigBackupHelper
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class V330JevDedicatedRoutingAndScientificPromptTest {

    private val capturedRequests = mutableListOf<Pair<Request, String>>()

    private val mockClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val req = chain.request()
                val bodyBuf = Buffer()
                req.body?.writeTo(bodyBuf)
                val bodyString = bodyBuf.readUtf8()
                synchronized(capturedRequests) {
                    capturedRequests.add(req to bodyString)
                }

                val path = req.url.encodedPath
                val responseJson = when {
                    path.endsWith("/audio/transcriptions") -> {
                        """{"text": "最低八百包邮，今天就能顺丰发出"}"""
                    }
                    path.endsWith("/chat/completions") -> {
                        """
                        {
                          "choices": [
                            {
                              "message": {
                                "role": "assistant",
                                "content": "<jev><lv>6</lv><tag>隐性委屈试探</tag><subtext>对方并非真让你别管，而是期待你主动哄一句</subtext><opt label=\"周全给面\" prob=\"68\">这周确实忙昏头了，周六下午去接你喝咖啡</opt><opt label=\"松弛直给\" prob=\"21\">今晚赶毕设，周六下午请你喝咖啡</opt><opt label=\"锚定闭环\" prob=\"11\">今晚先把终稿交了，周六下午三点老地方见</opt></jev>"
                              }
                            }
                          ]
                        }
                        """.trimIndent()
                    }
                    path.contains(":generateContent") -> {
                        if (bodyString.contains("\"maxOutputTokens\":2")) {
                            """{"candidates":[{"content":{"parts":[{"text":"1"}]}}]}"""
                        } else {
                            """
                            {
                              "candidates": [
                                {
                                  "content": {
                                    "parts": [
                                      {
                                        "text": "<jev><lv>5</lv><tag>探底压价拉扯</tag><subtext>买家试探你的心理底价，想顺便免掉运费</subtext><opt label=\"周全给面\" prob=\"64\">看你也是爽快人，最低八百包顺丰，今天下班前给你寄出</opt><opt label=\"松弛直给\" prob=\"24\">实在价八百包顺丰，拍下今天发</opt><opt label=\"锚定闭环\" prob=\"12\">八百包顺丰底价不刀了，可以的话我现在改价，傍晚六点前出单号</opt></jev>"
                                      }
                                    ]
                                  }
                                }
                              ]
                            }
                            """.trimIndent()
                        }
                    }
                    path.endsWith("/models") -> {
                        """
                        {
                          "models": [
                            {"name": "models/gemini-3.5-flash-lite", "supportedGenerationMethods": ["generateContent"]},
                            {"name": "models/gemini-3.8-flash", "supportedGenerationMethods": ["generateContent"]},
                            {"name": "models/gemini-3.1-pro-preview", "supportedGenerationMethods": ["generateContent"]}
                          ]
                        }
                        """.trimIndent()
                    }
                    else -> "{}"
                }

                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(responseJson.toResponseBody("application/json".toMediaType()))
                    .build()
            })
            .build()
    }

    private lateinit var engine: AiPipelineEngine

    private val testStyle: ToneStyle = DefaultPresets.defaultStyles().first()

    @Before
    fun setUp() {
        AiPipelineEngine.clearUnsupportedThinkingCache()
        synchronized(capturedRequests) {
            capturedRequests.clear()
        }
        engine = AiPipelineEngine(httpClient = mockClient)
    }

    @Test
    fun promptBuilder_eliminatesNumericAnchors_andEnforcesScientific3AxisAndAntiClicheRules() {
        val (sysPrompt, userPrompt) = PromptBuilder.buildJevCopilotPrompts(
            domainProfile = JevDomainProfile.IM_CHAT,
            targetAppName = "微信",
            chatContextMessages = listOf(
                "[对方] 注意分寸，那件事翻篇了",
                "[对方] 没事呀，不用管我，我自己去就行 [微笑]"
            ),
            hasChatScreenshot = true,
            userBottomLine = "今晚真得改毕设去不了，周六下午请她喝咖啡",
            style = testStyle
        )

        // 1. Zero fixed numeric probability anchors or pre-hoc prob= attributes in both prompts
        assertFalse(sysPrompt.contains("prob="))
        assertFalse(userPrompt.contains("prob="))
        assertFalse(sysPrompt.contains("55/30/15"))
        assertFalse(sysPrompt.contains("58/27/15"))

        // Post-hoc <weights>W1,W2,W3</weights> present in both system and user prompts
        assertTrue(sysPrompt.contains("<weights>W1,W2,W3</weights>"))
        assertTrue(userPrompt.contains("<weights>W1,W2,W3</weights>"))

        // 2. Post-hoc causal scoring, non-monotonic W1/W2/W3 freedom, and fresh 4-char label rule
        assertTrue(sysPrompt.contains("严禁强制 W1 > W2 > W3"))
        assertTrue(sysPrompt.contains("严禁照抄提示词中的理论轴名称"))
        assertTrue(userPrompt.contains("严禁照抄理论轴名"))
        assertTrue(sysPrompt.contains("66–78"))
        assertTrue(sysPrompt.contains("41–49"))
        assertTrue(sysPrompt.contains("32–39"))

        // 3. TKI 3-axis orthogonal strategies (no bottom-line state)
        assertTrue(sysPrompt.contains("协同破局"))
        assertTrue(sysPrompt.contains("温和设限"))
        assertTrue(sysPrompt.contains("升维重构"))

        // 4. Brown & Levinson 3 face-work registers (locked bottom-line state)
        assertTrue(sysPrompt.contains("周全给面"))
        assertTrue(sysPrompt.contains("松弛直给"))
        assertTrue(sysPrompt.contains("锚定闭环"))

        // 5. Chinese IM micro-expression / passive-aggression & multi-turn boundary rules
        assertTrue(sysPrompt.contains("[微笑]"))
        assertTrue(sysPrompt.contains("没事呀"))
        assertTrue(sysPrompt.contains("不用管我"))
        assertTrue(sysPrompt.contains("Lv.5–7"))
        assertTrue(sysPrompt.contains("注意分寸"))

        // 6. Anti-AI customer service cliché blacklist
        assertTrue(sysPrompt.contains("真的太抱歉啦"))
        assertTrue(sysPrompt.contains("赔罪"))
        assertTrue(sysPrompt.contains("害你白跑一趟"))
        assertTrue(sysPrompt.contains("千万别气馁"))
        assertTrue(sysPrompt.contains("一定不让你失望"))
    }

    @Test
    fun jevResponseParser_handlesPostHocWeights_dynamicPromotion_weakModelFallback_andMisplacedWeightsTag() {
        // 1. Dynamic promotion: Option 2 ("体面守界") wins highest weight (50) in <weights>34,50,16</weights>
        val nonMonotonicXml = """
            <jev>
            <lv>7</lv>
            <tag>划界防御降温</tag>
            <subtext>对方前文已明确要求注意分寸，此刻属于礼貌退场而非撒娇索哄</subtext>
            <opt label="顺水给梯">那你路上注意安全，到家给我发个消息</opt>
            <opt label="体面守界">好，那你先忙，周末愉快</opt>
            <opt label="轻松换题">行，剛好我也得回去改论文，回头聊</opt>
            <weights>34,50,16</weights>
            </jev>
        """.trimIndent()

        val promoted = JevResponseParser.parse(nonMonotonicXml)
        assertEquals(7, promoted.clampedLevel)
        assertEquals(3, promoted.options.size)
        assertEquals("体面守界", promoted.options[0].label)
        assertEquals(50, promoted.options[0].probability)
        assertEquals("顺水给梯", promoted.options[1].label)
        assertEquals(34, promoted.options[1].probability)
        assertEquals("轻松换题", promoted.options[2].label)
        assertEquals(16, promoted.options[2].probability)

        // 2. Weak model echoing literal <weights>W1,W2,W3</weights> falls back cleanly to 56, 28, 16
        val echoedPlaceholderXml = """
            <jev>
            <lv>6</lv>
            <tag>被动攻击试探</tag>
            <subtext>对方嘴上说没事但带微笑表情，实则委屈试探你的重视程度</subtext>
            <opt label="台阶补票">今晚导师催毕设实在走不开，周六下午我去接你喝咖啡</opt>
            <opt label="干练定档">今晚得死磕毕设，周六下午请你喝咖啡</opt>
            <opt label="记账破冰">今晚改完终稿，周六下午三点老地方见，给你带那家拿铁</opt>
            <weights>W1,W2,W3</weights>
            </jev>
        """.trimIndent()

        val fallbackParsed = JevResponseParser.parse(echoedPlaceholderXml)
        assertEquals(6, fallbackParsed.clampedLevel)
        assertEquals(3, fallbackParsed.options.size)
        assertEquals(100, fallbackParsed.options.sumOf { it.probability })
        assertEquals(listOf(56, 28, 16), fallbackParsed.options.map { it.probability })

        // 3. Small model accidentally placing <weights>34,50,16</weights> inside the 3rd <opt> before </opt>
        val misplacedWeightsXml = """
            <jev>
            <lv>5</lv>
            <tag>探底压价拉扯</tag>
            <subtext>买家试探底价</subtext>
            <opt label="顺势包邮">八百五给您包顺丰</opt>
            <opt label="锁死底价">实在价八百包顺丰，不刀了</opt>
            <opt label="自提让利">自提可以七百八<weights>34,50,16</weights></opt>
            </jev>
        """.trimIndent()

        val cleanedParsed = JevResponseParser.parse(misplacedWeightsXml)
        assertEquals(3, cleanedParsed.options.size)
        assertEquals("锁死底价", cleanedParsed.options[0].label)
        assertEquals(50, cleanedParsed.options[0].probability)
        cleanedParsed.options.forEach { opt ->
            assertFalse("replyText must not leak <weights>", opt.replyText.contains("weights"))
            assertFalse("replyText must not leak 34,50,16", opt.replyText.contains("34,50,16"))
        }
        assertEquals("自提可以七百八", cleanedParsed.options[2].replyText)
    }

    @Test
    fun secretVault_andCatalog_andBackupHelper_supportJevDedicatedModelAndKeyIsolation() {
        val defaults = AppSettings()
        assertFalse(defaults.jevUseDedicatedModel)
        assertEquals("gemini-3.8-flash", defaults.jevLlmConfig.model)
        assertEquals(ThinkingMode.AUTO, defaults.jevLlmConfig.thinkingMode)
        assertEquals(0.35f, defaults.jevLlmConfig.temperature, 0.001f)

        // 1. SecretVault encryption & public stripping
        val secureStore = InMemoryKeyValueStore()
        val vault = SecretVault(
            store = secureStore,
            cipher = JvmAesGcmCipher()
        )

        val configured = defaults.copy(
            jevUseDedicatedModel = true,
            jevLlmConfig = defaults.jevLlmConfig.copy(
                presetId = "deepseek",
                baseUrl = "https://api.deepseek.com/v1",
                apiKey = "sk-jev-secret-999",
                model = "deepseek-chat",
                thinkingMode = ThinkingMode.DISABLED
            )
        )
        assertTrue(SecretVault.containsPlaintextSecrets(configured))
        vault.saveSecretsFromSettings(configured)
        assertEquals("sk-jev-secret-999", vault.getSecret(SecretVault.KEY_JEV_API_KEY))
        assertTrue(secureStore.getString(SecretVault.KEY_JEV_API_KEY)!!.startsWith("v1:"))

        val stripped = vault.stripSecretsForPublicPersistence(configured)
        assertEquals("", stripped.jevLlmConfig.apiKey)
        assertFalse(SecretVault.containsPlaintextSecrets(stripped))

        val hydrated = vault.hydrateSettingsWithSecrets(stripped)
        assertEquals("sk-jev-secret-999", hydrated.jevLlmConfig.apiKey)
        assertTrue(hydrated.jevUseDedicatedModel)

        // 2. UnifiedProviderCatalog.selectJevProvider & credential sync
        val withGoogleVault = UnifiedProviderCatalog.updateProviderCredential(
            defaults,
            ProviderCredential(
                providerId = UnifiedProviderCatalog.ID_GOOGLE,
                apiKey = "AIza-google-unified-key"
            )
        )
        assertEquals("AIza-google-unified-key", withGoogleVault.jevLlmConfig.apiKey)

        val switchedToVolc = UnifiedProviderCatalog.selectJevProvider(
            withGoogleVault.copy(
                providerVault = withGoogleVault.providerVault + (
                    UnifiedProviderCatalog.ID_VOLCENGINE to ProviderCredential(
                        providerId = UnifiedProviderCatalog.ID_VOLCENGINE,
                        apiKey = "ark-volc-key-123"
                    )
                )
            ),
            UnifiedProviderCatalog.ID_VOLCENGINE
        )
        assertTrue(switchedToVolc.jevUseDedicatedModel)
        assertEquals("doubao", switchedToVolc.jevLlmConfig.presetId)
        assertEquals("doubao-seed-2-1-pro-260915", switchedToVolc.jevLlmConfig.model)
        assertEquals("ark-volc-key-123", switchedToVolc.jevLlmConfig.apiKey)
        assertEquals(ThinkingMode.AUTO, switchedToVolc.jevLlmConfig.thinkingMode)

        // 3. ApiConfigBackupHelper export redaction & import merge
        val redactedJson = ApiConfigBackupHelper.exportSettingsJson(switchedToVolc, includeSecrets = false)
        assertFalse(redactedJson.contains("ark-volc-key-123"))
        assertTrue(redactedJson.contains("\"jevUseDedicatedModel\": true"))
        assertTrue(redactedJson.contains("doubao-seed-2-1-pro-260915"))

        val mergedFromRedacted = ApiConfigBackupHelper.parseAndMergeConfigJson(
            redactedJson,
            switchedToVolc
        ).getOrThrow()
        assertTrue(mergedFromRedacted.jevUseDedicatedModel)
        assertEquals("doubao-seed-2-1-pro-260915", mergedFromRedacted.jevLlmConfig.model)
        // Preserves existing local key when importing redacted JSON
        assertEquals("ark-volc-key-123", mergedFromRedacted.jevLlmConfig.apiKey)
    }

    @Test
    fun runJevCopilot_routesToDedicatedModelRegardlessOfPipelineMode_andAutoHydratesVaultKey() = runBlocking {
        // Global pipeline is MULTIMODAL_DIRECT with Gemini Flash-Lite,
        // but Jev Dedicated Model is enabled and set to DeepSeek with key in providerVault!
        val settings = AppSettings(
            pipelineMode = PipelineMode.MULTIMODAL_DIRECT,
            multimodalConfig = MultimodalConfig(
                presetId = "gemini",
                protocol = MultimodalProtocol.GEMINI_NATIVE,
                baseUrl = "https://generativelanguage.googleapis.com/v1beta",
                apiKey = "AIza-global-mm-key",
                model = "gemini-3.5-flash-lite"
            ),
            jevUseDedicatedModel = true,
            jevLlmConfig = LlmConfig(
                enabled = true,
                presetId = "deepseek",
                baseUrl = "https://api.deepseek.com/v1",
                apiKey = "", // Empty direct key -> must auto-hydrate from providerVault!
                model = "deepseek-chat",
                temperature = 0.35f,
                thinkingMode = ThinkingMode.DISABLED
            ),
            providerVault = mapOf(
                UnifiedProviderCatalog.ID_DEEPSEEK to ProviderCredential(
                    providerId = UnifiedProviderCatalog.ID_DEEPSEEK,
                    apiKey = "sk-vault-deepseek-777"
                )
            )
        )

        val res = engine.runJevCopilot(
            settings = settings,
            activeStyle = testStyle,
            domainProfile = JevDomainProfile.IM_CHAT,
            targetAppName = "微信",
            chatContextMessages = listOf("[对方] 没事呀，不用管我 [微笑]"),
            chatContextScreenshotBase64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
            userBottomLine = "今晚赶毕设，周六请喝咖啡"
        ).getOrThrow()

        assertEquals(6, res.first.clampedLevel)
        assertEquals(listOf(68, 21, 11), res.first.options.map { it.probability })
        // Pure-text dedicated model strips screenshot instead of hijacking to global Gemini Flash-Lite!
        assertFalse(res.first.usedVision)

        assertEquals(1, capturedRequests.size)
        val (recorded, bodyText) = capturedRequests.first()
        assertEquals("/v1/chat/completions", recorded.url.encodedPath)
        assertEquals("api.deepseek.com", recorded.url.host)
        assertEquals("Bearer sk-vault-deepseek-777", recorded.header("Authorization"))
        val bodyJson = JsonParser.parseString(bodyText).asJsonObject
        assertEquals("deepseek-chat", bodyJson.get("model").asString)
    }

    @Test
    fun runJevCopilot_supportsDedicatedGeminiNativeVision_andKeepsBottomLineAudioOnFastStt() = runBlocking {
        val tempWav = File.createTempFile("jev_bottom_line_", ".wav").apply {
            writeBytes(ByteArray(128) { 1 })
            deleteOnExit()
        }

        val settings = AppSettings(
            pipelineMode = PipelineMode.DUAL_STAGE,
            sttConfig = SttConfig(
                presetId = "siliconflow",
                protocol = SttProtocol.OPENAI_HTTP,
                baseUrl = "https://api.siliconflow.cn/v1",
                apiKey = "sk-fast-stt-key",
                model = "FunAudioLLM/SenseVoiceSmall"
            ),
            llmConfig = LlmConfig(
                enabled = true,
                presetId = "doubao_llm",
                baseUrl = "https://ark.cn-beijing.volces.com/api/v3",
                apiKey = "ark-unused-key",
                model = "doubao-seed-2-0-lite-260215"
            ),
            jevUseDedicatedModel = true,
            jevLlmConfig = LlmConfig(
                enabled = true,
                presetId = "gemini_llm",
                baseUrl = "https://generativelanguage.googleapis.com/v1beta",
                apiKey = "AIza-dedicated-jev-gemini",
                model = "gemini-3.8-flash",
                temperature = 0.35f,
                thinkingMode = ThinkingMode.AUTO
            )
        )

        val (result, transcript) = engine.runJevCopilot(
            settings = settings,
            activeStyle = testStyle,
            domainProfile = JevDomainProfile.COMMERCE_BARGAIN,
            targetAppName = "闲鱼",
            chatContextMessages = listOf("[对方] 六百五能出吗？诚心要"),
            chatContextScreenshotBase64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
            wavFile = tempWav
        ).getOrThrow()

        assertEquals("最低八百包邮，今天就能顺丰发出", transcript)
        assertTrue(result.usedVision)
        assertEquals(listOf(64, 24, 12), result.options.map { it.probability })

        assertEquals(2, capturedRequests.size)
        val (sttReq, _) = capturedRequests[0]
        assertEquals("/v1/audio/transcriptions", sttReq.url.encodedPath)
        assertEquals("Bearer sk-fast-stt-key", sttReq.header("Authorization"))

        val (geminiReq, geminiBody) = capturedRequests[1]
        assertTrue(geminiReq.url.encodedPath.startsWith("/v1beta/models/gemini-3.8-flash:generateContent"))
        assertEquals("AIza-dedicated-jev-gemini", geminiReq.header("x-goog-api-key"))
        assertTrue("Gemini Native Jev call must include inline_data screenshot", geminiBody.contains("inline_data"))
    }

    @Test
    fun modelDiscovery_andLatencyProbe_supportJevCopilotSlot() = runBlocking {
        val discovered = engine.fetchAvailableModels(
            slot = ModelCapabilitySlot.JEV_COPILOT,
            baseUrl = "https://generativelanguage.googleapis.com/v1beta",
            apiKey = "AIza-probe-key"
        ).getOrThrow()

        assertEquals(3, discovered.size)
        // For JEV_COPILOT, gemini-3.8-flash and gemini-3.1-pro-preview outrank gemini-3.5-flash-lite
        assertEquals("gemini-3.8-flash", discovered[0].id)
        assertEquals("🧠 军师推荐", discovered[0].badge)
        assertEquals("gemini-3.1-pro-preview", discovered[1].id)
        assertEquals("🧠 军师推荐", discovered[1].badge)

        val probe = engine.probeModelLatency(
            slot = ModelCapabilitySlot.JEV_COPILOT,
            baseUrl = "https://generativelanguage.googleapis.com/v1beta",
            apiKey = "AIza-probe-key",
            modelId = "gemini-3.8-flash",
            thinkingMode = ThinkingMode.AUTO
        )
        assertTrue(probe.isSuccess)
        assertNotNull(probe.latencyMs)
        assertNull(probe.errorSummary)
    }
}
