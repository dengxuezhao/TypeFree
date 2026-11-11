package com.typefree.ime.v320

import com.typefree.ime.bubble.ChatScreenContextExtractor
import com.typefree.ime.bubble.RawScreenTextNode
import com.typefree.ime.data.AppSettings
import com.typefree.ime.data.DefaultPresets
import com.typefree.ime.ime.KeyboardTabMode
import com.typefree.ime.ime.chrome.TopBarSlotPolicy
import com.typefree.ime.ime.jev.InMemoryDraftEditorTarget
import com.typefree.ime.ime.jev.JevDomainProfile
import com.typefree.ime.ime.jev.JevDraftSession
import com.typefree.ime.ime.jev.JevResponseParser
import com.typefree.ime.ime.jev.JevTensionTier
import com.typefree.ime.network.PromptBuilder
import com.typefree.ime.privacy.ImagePrivacyGuard
import com.typefree.ime.privacy.LocalPiiRedactor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class V320JevCopilotAndPrivacyTest {

    private fun resolveProjectRoot(): File {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        return if (File(cwd, "app/src/main").exists()) {
            cwd
        } else {
            cwd.parentFile ?: cwd
        }
    }

    @Test
    fun localPiiRedactor_gb11643IdCardAndLuhnBankCardValidation() {
        // Valid GB 11643-1999 18-digit ID card
        val validId = "11010519491231002X"
        // Invalid check digit (last char changed from X to 1)
        val invalidIdChecksum = "110105194912310021"
        assertTrue(LocalPiiRedactor.isValidCnIdCard18(validId))
        assertFalse(LocalPiiRedactor.isValidCnIdCard18(invalidIdChecksum))

        // Valid Luhn 19-digit bank card
        val validBankCard = "6222020200001234562"
        assertTrue("Expected valid Luhn bank card", LocalPiiRedactor.isValidLuhnBankCard(validBankCard))
        val invalidLuhnOrderNo = "6222020200001234560"
        assertFalse("Order number failing Luhn must not be treated as bank card", LocalPiiRedactor.isValidLuhnBankCard(invalidLuhnOrderNo))

        val input = "身份证 $validId，普通单号 $invalidIdChecksum，卡号 $validBankCard，订单 $invalidLuhnOrderNo"
        val session = LocalPiiRedactor.redactText(input, context = null)
        val redacted = session.redactedText

        assertTrue(redacted.contains("[证件号_1]"))
        assertTrue("Invalid checksum 18-digit number should not be redacted as ID card", redacted.contains(invalidIdChecksum))
        assertTrue(redacted.contains("[银行卡_1]"))
        assertTrue("Invalid Luhn number should not be redacted as bank card", redacted.contains(invalidLuhnOrderNo))
        assertTrue(session.hasHighRiskCredentials)

        // High-risk credentials must NEVER be restored by restoreSafePlaceholders
        val modelDraft = "已核对您的 [证件号_1] 与 [银行卡_1]"
        val restored = session.restoreSafePlaceholders(modelDraft)
        assertEquals(modelDraft, restored)
    }

    @Test
    fun localPiiRedactor_gitleaksSecretsPasswordsOtpAndReversibleContactPlaceholders() {
        val raw = """
            请联系我手机 13812345678，邮箱 founder@typefree.ai，
            收货地址：北京市海淀区中关村南大街1号院3号楼502室，取件码 3-2-1048。
            另外这是测试密钥 sk-proj-1234567890abcdefghij，登录密码是 MySecretPass99!，验证码 839201。
        """.trimIndent()

        val node = RawScreenTextNode(
            text = raw,
            boundsTop = 200,
            boundsBottom = 320,
            boundsLeft = 40,
            boundsRight = 680
        )
        val session = LocalPiiRedactor.redactText(raw, context = null, sourceNodes = listOf(node))
        val redacted = session.redactedText

        assertFalse(redacted.contains("13812345678"))
        assertFalse(redacted.contains("founder@typefree.ai"))
        assertFalse(redacted.contains("中关村南大街1号院3号楼502室"))
        assertFalse(redacted.contains("sk-proj-1234567890abcdefghij"))
        assertFalse(redacted.contains("MySecretPass99!"))
        assertFalse(redacted.contains("839201"))

        assertTrue(redacted.contains("[手机号_1]"))
        assertTrue(redacted.contains("[邮箱_1]"))
        assertTrue(redacted.contains("[地址_1]"))
        assertTrue(redacted.contains("[密钥_1]"))
        assertTrue(redacted.contains("[敏感口令_1]"))
        assertTrue(redacted.contains("[验证码_1]"))
        assertTrue(session.hasHighRiskCredentials)
        assertTrue(session.redactedNodeBounds.isNotEmpty())

        // Reversible contact info is restored locally before committing to editor,
        // while high-risk secrets/passwords/OTPs are NEVER restored!
        val llmReply = "好的，稍后发到您手机 [手机号_1] 或邮箱 [邮箱_1]，寄往 [地址_1]，绝不泄露 [密钥_1] [敏感口令_1] [验证码_1]"
        val restored = session.restoreSafePlaceholders(llmReply)
        assertTrue(restored.contains("13812345678"))
        assertTrue(restored.contains("founder@typefree.ai"))
        assertTrue(restored.contains("北京市海淀区中关村南大街1号院3号楼502室"))
        assertTrue("API key placeholder must remain redacted", restored.contains("[密钥_1]"))
        assertTrue("Password placeholder must remain redacted", restored.contains("[敏感口令_1]"))
        assertTrue("OTP placeholder must remain redacted", restored.contains("[验证码_1]"))
        assertFalse(restored.contains("sk-proj-1234567890abcdefghij"))
        assertFalse(restored.contains("MySecretPass99!"))
        assertFalse(restored.contains("839201"))
    }

    @Test
    fun imagePrivacyGuard_dropsOnHighRiskOrSettingDisabled_andComputesCharcoalMaskRects() {
        assertTrue(
            ImagePrivacyGuard.shouldDropScreenshot(
                chatContextSendScreenshot = false,
                hasHighRiskCredentials = false,
                hasPasswordNode = false,
                isIncognito = false,
                isBlacklistedPackage = false
            )
        )
        assertTrue(
            ImagePrivacyGuard.shouldDropScreenshot(
                chatContextSendScreenshot = true,
                hasHighRiskCredentials = true,
                hasPasswordNode = false,
                isIncognito = false,
                isBlacklistedPackage = false
            )
        )
        assertTrue(
            ImagePrivacyGuard.shouldDropScreenshot(
                chatContextSendScreenshot = true,
                hasHighRiskCredentials = false,
                hasPasswordNode = true,
                isIncognito = false,
                isBlacklistedPackage = false
            )
        )
        assertFalse(
            ImagePrivacyGuard.shouldDropScreenshot(
                chatContextSendScreenshot = true,
                hasHighRiskCredentials = false,
                hasPasswordNode = false,
                isIncognito = false,
                isBlacklistedPackage = false
            )
        )

        val rawNodeRect = intArrayOf(100, 300, 500, 360)
        val masked = ImagePrivacyGuard.computeMaskedRectsOnCroppedBitmap(
            rawScreenRects = listOf(rawNodeRect),
            cropTopPx = 100,
            cropHeightPx = 1000,
            sourceWidthPx = 1000,
            targetWidthPx = 500,
            targetHeightPx = 500,
            expandPaddingPx = 4
        )
        assertEquals(1, masked.size)
        val r = masked.first()
        // X: 100 * 0.5 - 4 = 46; Right: 500 * 0.5 + 4 = 254
        // Y: (300 - 100) * 0.5 - 4 = 96; Bottom: (360 - 100) * 0.5 + 4 = 134
        assertEquals(46, r[0])
        assertEquals(96, r[1])
        assertEquals(254, r[2])
        assertEquals(134, r[3])
        assertEquals(0xFF18181B.toInt(), ImagePrivacyGuard.CHARCOAL_MASK_COLOR)
    }

    @Test
    fun passwordNodeHardCircuitBreaker_blocksContextExtractionWhenAnyVisiblePasswordNodeExists() {
        val nodesWithPassword = listOf(
            RawScreenTextNode(
                text = "李总：下午三点签合同",
                boundsTop = 300,
                boundsBottom = 380,
                boundsLeft = 60,
                boundsRight = 600,
                className = "android.widget.TextView",
                isEditable = false,
                isPassword = false,
                isVisibleToUser = true
            ),
            RawScreenTextNode(
                text = "••••••",
                boundsTop = 500,
                boundsBottom = 580,
                boundsLeft = 60,
                boundsRight = 600,
                className = "android.widget.EditText",
                isEditable = true,
                isPassword = true,
                isVisibleToUser = true
            )
        )
        assertTrue(ChatScreenContextExtractor.hasAnyVisiblePasswordNode(nodesWithPassword))

        val extracted = ChatScreenContextExtractor.extractChatContext(
            packageName = "com.tencent.mm",
            appName = "微信",
            screenWidth = 1080,
            screenHeight = 2400,
            nodes = nodesWithPassword
        )
        assertFalse("Full-screen password node must trigger hard circuit-breaker", extracted.hasContext)
        assertTrue(extracted.messages.isEmpty())
    }

    @Test
    fun allScene5Domains_matchesPackageAndUnlocksJevCaptureOutsideFinancialBlacklist() {
        assertEquals(JevDomainProfile.IM_CHAT, JevDomainProfile.fromPackage("com.tencent.mm"))
        assertEquals(JevDomainProfile.IM_CHAT, JevDomainProfile.fromPackage("com.ss.android.lark"))
        assertEquals(JevDomainProfile.COMMERCE_BARGAIN, JevDomainProfile.fromPackage("com.taobao.idlefish"))
        assertEquals(JevDomainProfile.COMMERCE_BARGAIN, JevDomainProfile.fromPackage("com.xunmeng.pinduoduo"))
        assertEquals(JevDomainProfile.CAREER_NEGOTIATION, JevDomainProfile.fromPackage("com.hpbr.bosszhipin"))
        assertEquals(JevDomainProfile.CAREER_NEGOTIATION, JevDomainProfile.fromPackage("com.taou.maimai"))
        assertEquals(JevDomainProfile.SOCIAL_COMMENT, JevDomainProfile.fromPackage("com.xingin.xhs"))
        assertEquals(JevDomainProfile.SOCIAL_COMMENT, JevDomainProfile.fromPackage("com.zhihu.android"))
        assertEquals(JevDomainProfile.FORMAL_WORKFLOW, JevDomainProfile.fromPackage("com.google.android.gm"))
        assertEquals(JevDomainProfile.FORMAL_WORKFLOW, JevDomainProfile.fromPackage("com.android.chrome"))

        val activeSettings = AppSettings(
            jevCopilotEnabled = true,
            chatContextReplyEnabled = true,
            chatContextDisclosureAccepted = true
        )

        // Unlocked across Xianyu, BossZhipin, Xiaohongshu, Chrome, WeChat
        for (pkg in listOf(
            "com.taobao.idlefish",
            "com.hpbr.bosszhipin",
            "com.xingin.xhs",
            "com.android.chrome",
            "com.tencent.mm"
        )) {
            assertTrue(
                "Jev Copilot should allow on-demand capture in $pkg",
                ChatScreenContextExtractor.shouldAllowJevContextCapture(
                    settings = activeSettings,
                    isIncognitoMode = false,
                    isPasswordInput = false,
                    ignoreChatContextForTurn = false,
                    activeWindowPackage = pkg,
                    targetInputPackage = pkg,
                    hasVisiblePasswordNode = false
                )
            )
        }

        // Strictly blocked on Alipay, Bitwarden, CMB Bank, or when a password node is visible
        for (blackPkg in listOf(
            "com.eg.android.AlipayGphone",
            "com.x8bit.bitwarden",
            "cmb.pb"
        )) {
            assertFalse(
                "Jev Copilot must never capture in financial/password app $blackPkg",
                ChatScreenContextExtractor.shouldAllowJevContextCapture(
                    settings = activeSettings,
                    isIncognitoMode = false,
                    isPasswordInput = false,
                    ignoreChatContextForTurn = false,
                    activeWindowPackage = blackPkg,
                    targetInputPackage = blackPkg,
                    hasVisiblePasswordNode = false
                )
            )
        }
    }

    @Test
    fun jevResponseParser_threeTierResilientFallbackAndSafePlaceholderRestoration() {
        val redaction = LocalPiiRedactor.redactText("打我电话 13900001111，密钥 sk-abcdefghijkl1234567890", context = null)

        // Tier 1: XML with unescaped Chinese quotes
        val xmlRaw = """
            <jev>
            <lv>8</lv>
            <tag>连环画饼施压</tag>
            <subtext>对方用“下季度晋升”试探你能否免费接盘，实则预算未批</subtext>
            <opt label="温和守底线">王总，这周排期确实满了，您可以打我 [手机号_1] 沟通优先级，[密钥_1] 已归档</opt>
            <opt label="资源置换">如果接手专项，建议先把双周调休确认下来</opt>
            <opt label="破局反问">请问目前专项的HC和里程碑节点定了吗？</opt>
            </jev>
        """.trimIndent()
        val parsedXml = JevResponseParser.parse(xmlRaw, redaction)
        assertNotNull(parsedXml)
        assertEquals(8, parsedXml.level)
        assertEquals(JevTensionTier.DANGER_ROSE, parsedXml.tensionTier)
        assertEquals("连环画饼施压", parsedXml.tag)
        assertEquals(3, parsedXml.options.size)
        assertTrue(parsedXml.options[0].replyText.contains("13900001111"))
        assertTrue(parsedXml.options[0].replyText.contains("[密钥_1]"))

        // Tier 2: JSON fallback
        val jsonRaw = """
            ```json
            {
              "lv": 5,
              "tag": "闲鱼屠龙刀",
              "subtext": "买家试探底价但诚意尚可",
              "options": [
                {"label": "小刀包邮", "reply": "最低950顺丰到付，诚心要今晚发货"},
                {"label": "守住底价", "reply": "成色充新不议价哈，同城可自提"},
                {"label": "附赠配件", "reply": "价格到底了，但可以送你原装保护壳"}
              ]
            }
            ```
        """.trimIndent()
        val parsedJson = JevResponseParser.parse(jsonRaw)
        assertNotNull(parsedJson)
        assertEquals(5, parsedJson.level)
        assertEquals(JevTensionTier.PROBE_AMBER, parsedJson.tensionTier)
        assertEquals(3, parsedJson.options.size)
        assertEquals("小刀包邮", parsedJson.options[0].label)

        // Tier 3: Plain text numbered lines fallback
        val plainRaw = """
            1. 【温和回应】这周末已经有安排了，我们下周一早会直接对齐吧。
            2. 【折中同步】今晚我先把核心数据发群里，明早再详细过。
            3. 【明确边界】目前手头在赶A项目上线，B模块建议下周排期。
        """.trimIndent()
        val parsedPlain = JevResponseParser.parse(plainRaw)
        assertNotNull(parsedPlain)
        assertEquals(3, parsedPlain.options.size)
        assertEquals("温和回应", parsedPlain.options[0].label)
    }

    @Test
    fun jevDraftSession_tenConsecutiveCardSwitches_zeroStackingAndPreservesUserPrefix() {
        val redaction = LocalPiiRedactor.redactText("我的电话 13688889999，验证码 654321", context = null)

        val cards = listOf(
            "策略A：先稳住节奏，电联 [手机号_1]",
            "策略B：以退为进，交换核心资源条件",
            "策略C：破局反将一军，守住底线 [验证码_1]"
        )

        // Test both standard composing editor and quirky auto-committing editor
        for (autoCommitQuirk in listOf(false, true)) {
            val editor = InMemoryDraftEditorTarget(
                initialPrefixText = "王总，",
                simulateAutoCommitQuirk = autoCommitQuirk
            )
            val session = JevDraftSession()

            repeat(10) {
                for ((idx, cardText) in cards.withIndex()) {
                    session.applyStrategyCard(
                        target = editor,
                        cardIndex = idx,
                        rawStrategyText = cardText,
                        redactionSession = redaction
                    )
                    val expectedBody = cardText.replace("[手机号_1]", "13688889999")
                    assertEquals(
                        "Card switch must replace draft in-place with zero stacking (autoCommit=$autoCommitQuirk)",
                        "王总，$expectedBody",
                        editor.bufferText
                    )
                    assertEquals(idx, session.selectedCardIndex)
                }
            }

            val committed = session.commitAndFinishOnExit(editor)
            assertEquals("策略C：破局反将一军，守住底线 [验证码_1]", committed)
            assertEquals("王总，策略C：破局反将一军，守住底线 [验证码_1]", editor.bufferText)
            assertEquals(-1, editor.composingStart)
        }
    }

    @Test
    fun promptBuilder_staticPrefixCacheAndAntiInjectionSanitization() {
        assertTrue(PromptBuilder.JEV_COPILOT_SYSTEM_PROMPT.contains("<jev>"))
        assertTrue(PromptBuilder.JEV_COPILOT_SYSTEM_PROMPT.contains("<chat_context>"))

        val (systemPrompt, userPrompt) = PromptBuilder.buildJevCopilotPrompts(
            domainProfile = JevDomainProfile.COMMERCE_BARGAIN,
            targetAppName = "闲鱼",
            style = DefaultPresets.defaultStyles().first(),
            chatContextMessages = listOf("[对方]：学生党500包邮交个朋友"),
            userBottomLine = "最低850不包邮",
            excludedPreviousReplies = listOf("抱歉不刀"),
            hasChatScreenshot = true
        )
        assertEquals(PromptBuilder.JEV_COPILOT_SYSTEM_PROMPT, systemPrompt)
        assertTrue(userPrompt.contains("<domain_profile>"))
        assertTrue(userPrompt.contains("二手"))
        assertTrue(userPrompt.contains("<user_bottom_line>"))
        assertTrue(userPrompt.contains("最低850不包邮"))
        assertTrue(userPrompt.contains("<excluded_previous_replies>"))
    }

    @Test
    fun topBarSlotPolicy_preservesUndoPriorityAndExposesJevCopilotPill() {
        val slots = TopBarSlotPolicy.resolveLeftSlots(
            activeTab = KeyboardTabMode.QWERTY,
            isComposing = false,
            hasUndoSnapshot = true,
            hasFreshClipboardBanner = true,
            hasActiveSelection = false,
            jevCopilotEnabled = true
        )
        assertTrue("Undo button must always retain highest priority", slots.showUndoButton)
        assertTrue(slots.showVoicePenPill)
        assertTrue(slots.showJevCopilotPill)
    }

    @Test
    fun v320ArchitectureGuard_keyboardScreenUnder650Lines_andZeroAutoSendOrBackgroundScraping() {
        val root = resolveProjectRoot()
        val keyboardScreenFile = File(root, "app/src/main/java/com/typefree/ime/ime/KeyboardScreen.kt")
        val lineCount = keyboardScreenFile.readLines().size
        assertTrue(
            "KeyboardScreen.kt must remain <= 650 lines in v3.2.0, actual: $lineCount",
            lineCount <= 650
        )

        val jevDraftSource = File(root, "app/src/main/java/com/typefree/ime/ime/jev/JevDraftSession.kt").readText()
        val jevPanelSource = File(root, "app/src/main/java/com/typefree/ime/ime/panels/JevCopilotPanel.kt").readText()
        assertFalse("JevDraftSession must never call performEditorAction", jevDraftSource.contains("performEditorAction"))
        assertFalse("JevCopilotPanel must never call performEditorAction", jevPanelSource.contains("performEditorAction"))
        assertFalse("JevDraftSession must never send KEYCODE_ENTER", jevDraftSource.contains("KEYCODE_ENTER"))
    }
}
