package com.typefree.ime.v321

import com.typefree.ime.bubble.CapturedChatContext
import com.typefree.ime.bubble.ChatScreenContextExtractor
import com.typefree.ime.bubble.RawScreenTextNode
import com.typefree.ime.bubble.TypeFreeAccessibilityService
import com.typefree.ime.bubble.ViewportOcrExtractor
import com.typefree.ime.data.AppSettings
import com.typefree.ime.data.DefaultPresets
import com.typefree.ime.ime.jev.JevDomainProfile
import com.typefree.ime.ime.jev.JevResponseParser
import com.typefree.ime.ime.jev.JevStrategyOption
import com.typefree.ime.network.PromptBuilder
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class JevResponseParserTest {

    @Test
    fun fig2RealDeviceRegression_nestedOptTagsAndCurlyQuotes_neverLeakXmlIntoReplyText() {
        val rawModelOutput = """
            <jev>
            <lv>5</lv>
            <tag>排期追问协同</tag>
            <subtext>对方希望确认排查进展与明确时间节点</subtext>
            <opt label="同步安排">收到，下午我们先内部过一下。</opt>
            <opt><opt label="主动承接">好的，效果问题我们一起排查，今天下班前给结论。</opt></opt>
            <opt><opt label=“推进闭环” prob="18%">好的，我拉个小群把技术和产品对齐一下。</opt></opt>
            </jev>
        """.trimIndent()

        val result = JevResponseParser.parse(rawModelOutput)
        assertEquals(3, result.options.size)
        assertEquals(listOf("同步安排", "主动承接", "推进闭环"), result.options.map { it.label })

        for (opt in result.options) {
            assertFalse(
                "replyText must never contain <opt tag leak: ${opt.replyText}",
                opt.replyText.contains("<opt", ignoreCase = true)
            )
            assertFalse(
                "replyText must never contain </opt> tag leak: ${opt.replyText}",
                opt.replyText.contains("</opt", ignoreCase = true)
            )
            assertFalse(
                "replyText must never contain <jev> tag leak: ${opt.replyText}",
                opt.replyText.contains("<jev", ignoreCase = true)
            )
        }
        assertEquals("收到，下午我们先内部过一下。", result.options[0].replyText)
        assertEquals("好的，效果问题我们一起排查，今天下班前给结论。", result.options[1].replyText)
        assertEquals("好的，我拉个小群把技术和产品对齐一下。", result.options[2].replyText)
        assertEquals(100, result.options.sumOf { it.probability })
    }

    @Test
    fun cleanOptionText_stripsResidualMalformedOrUnclosedOptFragments() {
        val dirty = """<opt label="主动承接">“好的，我们马上跟进处理”</opt><opt label="残缺"""
        val cleaned = JevResponseParser.cleanOptionText(dirty)
        assertEquals("好的，我们马上跟进处理", cleaned)
        assertFalse(cleaned.contains("<opt"))
        assertFalse(cleaned.contains("</opt>"))
    }

    @Test
    fun probabilityNormalization_handlesNormal_OutOfOrder_HighFlatScores_andDefaultFallback() {
        // 1. Normal calibrated 60 / 25 / 15
        val normalXml = """
            <jev>
            <lv>4</lv><tag>日常对齐</tag><subtext>确认时间</subtext>
            <opt label="周全给台阶" prob="60">没问题，我们先按周三节点推进，有变动随时对齐。</opt>
            <opt label="干练直给" prob="25">好，周三前搞定。</opt>
            <opt label="严谨闭环" prob="15">收到，周三 18:00 前提交初版并群内同步。</opt>
            </jev>
        """.trimIndent()
        val normalRes = JevResponseParser.parse(normalXml)
        assertEquals(listOf(60, 25, 15), normalRes.options.map { it.probability })
        assertEquals(100, normalRes.options.sumOf { it.probability })

        // 2. Out-of-order 20 / 55 / 25 -> auto-sorted descending to 55 / 25 / 20
        val outOfOrderXml = """
            <jev>
            <lv>5</lv><tag>价格博弈</tag><subtext>试探底价</subtext>
            <opt label="让步备选" prob="20">那各退一步820顺丰到付。</opt>
            <opt label="首推稳拿" prob="55">诚心要的话850包顺丰，今晚就能寄出。</opt>
            <opt label="硬守底线" prob="25">实在价850不刀哈，成色充新。</opt>
            </jev>
        """.trimIndent()
        val sortedRes = JevResponseParser.parse(outOfOrderXml)
        assertEquals(listOf(55, 25, 20), sortedRes.options.map { it.probability })
        assertEquals(listOf("首推稳拿", "硬守底线", "让步备选"), sortedRes.options.map { it.label })
        assertEquals(100, sortedRes.options.sumOf { it.probability })

        // 3. Unnormalized high flat scores 95 / 90 / 85 -> sharpened & normalized, sum == 100
        val highFlatOptions = listOf(
            JevStrategyOption(label = "策略A", replyText = "回复A", probability = 95),
            JevStrategyOption(label = "策略B", replyText = "回复B", probability = 90),
            JevStrategyOption(label = "策略C", replyText = "回复C", probability = 85)
        )
        val sharpened = JevResponseParser.normalizeAndSortOptions(highFlatOptions)
        assertEquals(100, sharpened.sumOf { it.probability })
        assertTrue(
            "Sharpened top pick (${sharpened[0].probability}%) should clearly lead 3rd pick (${sharpened[2].probability}%)",
            sharpened[0].probability - sharpened[2].probability >= 20
        )
        assertTrue(sharpened[0].probability > sharpened[1].probability)
        assertTrue(sharpened[1].probability > sharpened[2].probability)

        // 4. Omitted prob fallback -> 56 / 28 / 16
        val omittedOptions = listOf(
            JevStrategyOption(label = "一", replyText = "A", probability = 0),
            JevStrategyOption(label = "二", replyText = "B", probability = 0),
            JevStrategyOption(label = "三", replyText = "C", probability = 0)
        )
        val fallbackRes = JevResponseParser.normalizeAndSortOptions(omittedOptions)
        assertEquals(listOf(56, 28, 16), fallbackRes.map { it.probability })
        assertEquals(100, fallbackRes.sumOf { it.probability })
    }
}

class ChatScreenContextExtractorTest {

    @After
    fun tearDown() {
        TypeFreeAccessibilityService.debugSimulatedNodesForTest = null
        TypeFreeAccessibilityService.debugSimulatedScreenshotBase64ForTest = null
        ViewportOcrExtractor.debugSimulatedOcrNodesForTest = null
    }

    @Test
    fun fig1RealDeviceRegression_filtersMobileQqAvatarCards_RoleBadges_andDingTalkGroupHeaders() {
        val noiseSamples = listOf(
            "[管理员]",
            "【管理员】",
            "玄冰是仓鼠 资料卡",
            "张三的头像",
            "李四 个人资料卡",
            "归属于 蚂蚁集团",
            "内部群",
            "外部群",
            "全员群",
            "部门群",
            "动画表情",
            "查看原图"
        )
        for (noise in noiseSamples) {
            assertTrue(
                "Expected '$noise' to be filtered as UI/avatar noise",
                ChatScreenContextExtractor.isNoiseText(noise)
            )
        }

        // Simulate Fig 1 Mobile QQ NT accessibility tree where bubble text is custom Canvas (not in A11y tree)
        // and only group header, avatar profile card, role badge, and sender nickname exist in A11y:
        val qqNoiseOnlyNodes = listOf(
            RawScreenTextNode(
                text = "玄冰是仓鼠 资料卡",
                boundsTop = 420,
                boundsBottom = 510,
                boundsLeft = 28,
                boundsRight = 118,
                className = "android.widget.ImageView"
            ),
            RawScreenTextNode(
                text = "[管理员]",
                boundsTop = 420,
                boundsBottom = 460,
                boundsLeft = 136,
                boundsRight = 236,
                className = "android.widget.TextView"
            ),
            RawScreenTextNode(
                text = "玄冰是仓鼠",
                boundsTop = 420,
                boundsBottom = 460,
                boundsLeft = 248,
                boundsRight = 420,
                className = "android.widget.TextView"
            ),
            RawScreenTextNode(
                text = "张三的头像",
                boundsTop = 640,
                boundsBottom = 730,
                boundsLeft = 28,
                boundsRight = 118,
                className = "android.widget.ImageView"
            ),
            RawScreenTextNode(
                text = "归属于 蚂蚁集团",
                boundsTop = 220,
                boundsBottom = 260,
                boundsLeft = 120,
                boundsRight = 420,
                className = "android.widget.TextView"
            ),
            RawScreenTextNode(
                text = "内部群",
                boundsTop = 220,
                boundsBottom = 260,
                boundsLeft = 440,
                boundsRight = 540,
                className = "android.widget.TextView"
            )
        )

        val extracted = ChatScreenContextExtractor.extractChatContext(
            nodes = qqNoiseOnlyNodes,
            screenWidth = 1080,
            screenHeight = 2400,
            packageName = "com.tencent.mobileqq",
            appName = "QQ"
        )
        assertTrue(
            "Mobile QQ avatar/role/nickname nodes must never be mistaken for chat messages: ${extracted.formattedMessages}",
            extracted.messages.isEmpty()
        )
    }

    @Test
    fun mergeAccessibilityAndOcrMessages_fillsZeroA11yFromOcr_andDeduplicatesOverlappingBubbles() {
        // Case 1: 0 A11y messages + 2 OCR messages (Mobile QQ NT custom Canvas bubbles)
        val ocrOnly = listOf(
            "[对方] 不对百度除了ocr之外是屎",
            "[对方] 豆包语音识别比它强太多了"
        )
        val mergedFromEmpty = ChatScreenContextExtractor.mergeAccessibilityAndOcrMessages(
            a11yMessages = emptyList(),
            ocrMessages = ocrOnly,
            maxMessages = 10
        )
        assertEquals(2, mergedFromEmpty.size)
        assertEquals("[对方] 不对百度除了ocr之外是屎", mergedFromEmpty[0])
        assertEquals("[对方] 豆包语音识别比它强太多了", mergedFromEmpty[1])

        // Case 2: Partial A11y + OCR with overlapping message (punctuation/space variance) + new custom bubble
        val a11yList = listOf(
            "[对方] 不对，百度除了 OCR 之外是屎！"
        )
        val ocrList = listOf(
            "[对方] 不对百度除了ocr之外是屎",
            "[我] 确实，我们直接上端侧离线OCR"
        )
        val mergedOverlap = ChatScreenContextExtractor.mergeAccessibilityAndOcrMessages(
            a11yMessages = a11yList,
            ocrMessages = ocrList,
            maxMessages = 10
        )
        assertEquals(2, mergedOverlap.size)
        assertEquals("[对方] 不对，百度除了 OCR 之外是屎！", mergedOverlap[0])
        assertEquals("[我] 确实，我们直接上端侧离线OCR", mergedOverlap[1])
    }

    @Test
    fun viewportOcrPipeline_runsEvenWhenSendScreenshotDisabled_andDropsScreenshotOnHighRiskOcr() = runBlocking {
        // 1. Inverse coordinate mapping verification
        val mapped = ViewportOcrExtractor.mapOcrLineToScreenNode(
            text = "不对百度除了ocr之外是屎",
            left = 96,
            top = 200,
            right = 460,
            bottom = 250,
            cropX = 0,
            cropY = 211,
            scaleRatio = 720f / 1080f
        )
        assertNotNull(mapped)
        assertEquals(144, mapped!!.boundsLeft)
        assertEquals(211 + 300, mapped.boundsTop)

        // 2. When chatContextSendScreenshot == false (or pure-text model), OCR still extracts custom Canvas text
        // and syncs redactionSession + messageCount back into CapturedChatContext, while screenshotJpegBase64 stays ""
        TypeFreeAccessibilityService.debugSimulatedNodesForTest = "com.tencent.mobileqq" to listOf(
            RawScreenTextNode(
                text = "玄冰是仓鼠 资料卡",
                boundsTop = 420,
                boundsBottom = 510,
                boundsLeft = 28,
                boundsRight = 118,
                className = "android.widget.ImageView"
            )
        )
        ViewportOcrExtractor.debugSimulatedOcrNodesForTest = listOf(
            RawScreenTextNode(
                text = "不对百度除了ocr之外是屎",
                boundsTop = 520,
                boundsBottom = 600,
                boundsLeft = 140,
                boundsRight = 680
            ),
            RawScreenTextNode(
                text = "有事打我电话 13812345678",
                boundsTop = 660,
                boundsBottom = 740,
                boundsLeft = 140,
                boundsRight = 660
            )
        )
        TypeFreeAccessibilityService.debugSimulatedScreenshotBase64ForTest = "SIMULATED_JPEG_BASE64"

        val textOnlySettings = AppSettings(
            jevCopilotEnabled = true,
            chatContextReplyEnabled = true,
            chatContextDisclosureAccepted = true,
            chatContextSendScreenshot = false
        )
        val outcomeTextOnly = TypeFreeAccessibilityService.captureChatViewportOutcomeIfAvailable(
            expectedPackage = "com.tencent.mobileqq",
            settings = textOnlySettings,
            isIncognitoMode = false,
            isPasswordInput = false,
            ignoreChatContextForTurn = false,
            forJevCopilot = true
        )
        assertNotNull(outcomeTextOnly)
        assertEquals("", outcomeTextOnly!!.screenshotJpegBase64)
        assertEquals(2, outcomeTextOnly.mergedMessages.size)
        assertEquals("[对方] 不对百度除了ocr之外是屎", outcomeTextOnly.mergedMessages[0])
        assertTrue(outcomeTextOnly.mergedMessages[1].contains("[手机号_1]"))
        assertEquals(1, outcomeTextOnly.redactionSession.redactionCount)

        val syncedCtx = CapturedChatContext(
            packageName = "com.tencent.mobileqq",
            appName = "QQ"
        ).withViewportOutcome(outcomeTextOnly)
        assertEquals(2, syncedCtx.messageCount)
        assertEquals(1, syncedCtx.redactionCount)
        assertEquals("", syncedCtx.screenshotJpegBase64)

        // 3. When chatContextSendScreenshot == true, but OCR detects a high-risk OTP credential in custom Canvas,
        // screenshotJpegBase64 is immediately circuit-broken to "" while redacted text context is preserved
        ViewportOcrExtractor.debugSimulatedOcrNodesForTest = listOf(
            RawScreenTextNode(
                text = "登录验证码是 849201 请勿泄露",
                boundsTop = 520,
                boundsBottom = 600,
                boundsLeft = 140,
                boundsRight = 680
            )
        )
        val screenshotEnabledSettings = textOnlySettings.copy(chatContextSendScreenshot = true)
        val outcomeHighRisk = TypeFreeAccessibilityService.captureChatViewportOutcomeIfAvailable(
            expectedPackage = "com.tencent.mobileqq",
            settings = screenshotEnabledSettings,
            isIncognitoMode = false,
            isPasswordInput = false,
            ignoreChatContextForTurn = false,
            forJevCopilot = true
        )
        assertNotNull(outcomeHighRisk)
        assertTrue(outcomeHighRisk!!.redactionSession.hasHighRiskCredentials)
        assertEquals("High-risk OCR credential must drop screenshot immediately", "", outcomeHighRisk.screenshotJpegBase64)
        assertTrue(outcomeHighRisk.mergedMessages.first().contains("[验证码_1]"))
    }

    @Test
    fun accessibilityConfigAndGradleVersion_matchV321Spec() {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        val root = if (File(cwd, "app/src/main").exists()) cwd else (cwd.parentFile ?: cwd)

        val a11yXml = File(root, "app/src/main/res/xml/accessibility_service_config.xml").readText()
        assertTrue(
            "accessibility_service_config.xml must declare android:canTakeScreenshot=\"true\"",
            a11yXml.contains("android:canTakeScreenshot=\"true\"")
        )

        val gradleKts = File(root, "app/build.gradle.kts").readText()
        assertTrue(gradleKts.contains("versionCode = 28"))
        assertTrue(gradleKts.contains("versionName = \"3.5.1\""))
        assertTrue(gradleKts.contains("com.google.mlkit:text-recognition-chinese:16.0.1"))

        assertEquals(JevDomainProfile.IM_CHAT, JevDomainProfile.fromPackage("com.alibaba.aliding"))
        assertTrue(ChatScreenContextExtractor.isSupportedMessagingApp("com.alibaba.aliding"))
    }
}

class PromptBuilderTest {

    @Test
    fun buildJevCopilotPrompts_removesTimesThreeShorthand_andEnforcesProbAndDualStateDifferentiation() {
        val (sysPrompt, userPrompt) = PromptBuilder.buildJevCopilotPrompts(
            domainProfile = JevDomainProfile.IM_CHAT,
            targetAppName = "QQ",
            chatContextMessages = listOf("[对方] 不对百度除了ocr之外是屎"),
            hasChatScreenshot = false,
            userBottomLine = "跟他说我们下午一起排查效果问题",
            style = DefaultPresets.defaultStyles().first()
        )

        assertFalse("System prompt must not contain ×3 shorthand", sysPrompt.contains("×3"))
        assertFalse("User prompt must not contain ×3 shorthand", userPrompt.contains("×3"))

        // Explicit 3-line <opt label="..."> template + post-hoc <weights>W1,W2,W3</weights> in both prompts
        assertEquals(3, Regex("""<opt\s+label=""").findAll(sysPrompt).count())
        assertEquals(3, Regex("""<opt\s+label=""").findAll(userPrompt).count())
        assertTrue(sysPrompt.contains("<weights>W1,W2,W3</weights>"))
        assertTrue(userPrompt.contains("<weights>W1,W2,W3</weights>"))
        assertTrue(sysPrompt.contains("相加严格等于 100"))

        // Dual-state differentiation iron law
        assertTrue(sysPrompt.contains("当未提供 <user_bottom_line>"))
        assertTrue(sysPrompt.contains("立场方向"))
        assertTrue(sysPrompt.contains("当已提供 <user_bottom_line>"))
        assertTrue(sysPrompt.contains("说话分寸与长短颗粒度"))
        assertTrue(sysPrompt.contains("严禁同义词复读"))
    }
}
