package com.typefree.ime.v341

import com.typefree.ime.bubble.CapturedChatContext
import com.typefree.ime.bubble.ChatScreenContextExtractor
import com.typefree.ime.bubble.ChatSpeaker
import com.typefree.ime.bubble.RawScreenTextNode
import com.typefree.ime.data.DefaultPresets
import com.typefree.ime.ime.KeyboardMetrics
import com.typefree.ime.ime.jev.ChatTopology
import com.typefree.ime.ime.jev.JevDomainProfile
import com.typefree.ime.ime.jev.JevResponseParser
import com.typefree.ime.ime.jev.JevRoleMode
import com.typefree.ime.network.PromptBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class V341RealDeviceBugfixTest {

    private fun resolveProjectRoot(): File {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        return if (File(cwd, "app/src/main").exists()) {
            cwd
        } else {
            cwd.parentFile ?: cwd
        }
    }

    @Test
    fun realDeviceScreenshot3_qqGroup19CharCommaNickname_andRightSelfNickname_arePairedAndNeverLeakedAsMessages() {
        // Exact geometric reproduction of user's Screenshot 3 (`工具软件爱好者折腾群-综合讨论(1971)` on 1080x2400):
        // - Top group title bar: `工具软件爱好者折腾群-综合讨论(1971)`
        // - Turn 1 (Right, SELF): Right avatar + self group nickname `Balthazar` + blue bubble `122万块，这数据量级，是分布式存储还是单机啊?`
        // - Turn 2 (Left, OTHER): Left avatar + `[活跃]` badge + `花猫` + white bubble `单机`
        // - Turn 3 (Left, OTHER): Left avatar + `[群主]` badge + 19-char comma-containing nickname
        //   `我一直在寻找，有下一顿饭钱的世界在哪里` (spanning `left=242..888`, `centerX=565 > 540`!) + white bubble `单机,这是思源啊`
        val qqGroupNodes = listOf(
            RawScreenTextNode(
                text = "工具软件爱好者折腾群-综合讨论(1971)",
                boundsTop = 112,
                boundsBottom = 168,
                boundsLeft = 132,
                boundsRight = 920
            ),
            // Turn 1: Right avatar + Balthazar + [我] bubble
            RawScreenTextNode(
                text = "",
                boundsTop = 495,
                boundsBottom = 585,
                boundsLeft = 948,
                boundsRight = 1038,
                className = "android.widget.ImageView"
            ),
            RawScreenTextNode(
                text = "Balthazar",
                boundsTop = 495,
                boundsBottom = 530,
                boundsLeft = 785,
                boundsRight = 928,
                className = "android.widget.TextView"
            ),
            RawScreenTextNode(
                text = "122万块，这数据量级，是分布式存储还是单机啊?",
                boundsTop = 542,
                boundsBottom = 668,
                boundsLeft = 212,
                boundsRight = 928,
                className = "android.widget.TextView"
            ),
            // Turn 2: Left avatar + [活跃] + 花猫 + 单机
            RawScreenTextNode(
                text = "",
                boundsTop = 708,
                boundsBottom = 798,
                boundsLeft = 28,
                boundsRight = 118,
                className = "android.widget.ImageView"
            ),
            RawScreenTextNode(
                text = "活跃",
                boundsTop = 708,
                boundsBottom = 744,
                boundsLeft = 148,
                boundsRight = 226,
                className = "android.widget.TextView"
            ),
            RawScreenTextNode(
                text = "花猫",
                boundsTop = 708,
                boundsBottom = 744,
                boundsLeft = 238,
                boundsRight = 316,
                className = "android.widget.TextView"
            ),
            RawScreenTextNode(
                text = "单机",
                boundsTop = 758,
                boundsBottom = 818,
                boundsLeft = 148,
                boundsRight = 288,
                className = "android.widget.TextView"
            ),
            // Turn 3: Left avatar + [群主] + 19-char punctuated nickname extending to X=888 + 单机,这是思源啊
            RawScreenTextNode(
                text = "",
                boundsTop = 860,
                boundsBottom = 950,
                boundsLeft = 28,
                boundsRight = 118,
                className = "android.widget.ImageView"
            ),
            RawScreenTextNode(
                text = "群主",
                boundsTop = 860,
                boundsBottom = 896,
                boundsLeft = 148,
                boundsRight = 228,
                className = "android.widget.TextView"
            ),
            RawScreenTextNode(
                text = "我一直在寻找，有下一顿饭钱的世界在哪里",
                boundsTop = 860,
                boundsBottom = 896,
                boundsLeft = 242,
                boundsRight = 888,
                className = "android.widget.TextView"
            ),
            RawScreenTextNode(
                text = "单机,这是思源啊",
                boundsTop = 910,
                boundsBottom = 970,
                boundsLeft = 148,
                boundsRight = 480,
                className = "android.widget.TextView"
            )
        )

        val extracted = ChatScreenContextExtractor.extractChatContext(
            nodes = qqGroupNodes,
            screenWidth = 1080,
            screenHeight = 2400,
            packageName = "com.tencent.mobileqq",
            appName = "QQ",
            maxMessages = 10
        )

        assertEquals("Must extract exactly 3 spoken bubbles and 0 leaked nickname lines", 3, extracted.messageCount)
        assertEquals(
            listOf(
                "[我] 122万块，这数据量级，是分布式存储还是单机啊?",
                "[群友A] 单机",
                "[群友B] 单机,这是思源啊"
            ),
            extracted.formattedMessages
        )
        for (line in extracted.formattedMessages) {
            assertFalse("Must never leak '寻找' from group nickname into messages: $line", line.contains("寻找"))
            assertFalse("Must never leak '饭钱' from group nickname into messages: $line", line.contains("饭钱"))
            assertFalse("Must never leak self nickname 'Balthazar' into messages: $line", line.contains("Balthazar"))
            assertFalse("Must never leak role badge '群主' into messages: $line", line.contains("群主"))
        }
        assertTrue(extracted.topology.isGroupChat)
        assertEquals(1971, extracted.topology.groupMemberCount)
        assertEquals(listOf("群友A", "群友B"), extracted.topology.distinctOtherSenders)
        assertEquals(1, extracted.topology.recentSelfTurns)
        assertEquals(JevRoleMode.GROUP_INVOLVED_PARTY, extracted.topology.roleMode)
        assertEquals("群聊局势", extracted.effectiveBadgeTitle)
    }

    @Test
    fun pureOcrGroupNicknames_mergedRolePrefixes_standaloneStickerHeaders_andConsecutiveBubbles_behaveCleanly() {
        // 1. Pure ML Kit OCR mode (no ImageView nodes): OCR merges `[群主]` or misreads `喦主` with long comma nickname,
        // plus right-side caption-font `Balthazar` (`height=34`) and trailing standalone `[管理员] 玄冰是仓鼠` above a dog photo.
        val ocrNodes = listOf(
            RawScreenTextNode(
                text = "工具软件爱好者折腾群-综合讨论(1971)",
                boundsTop = 115,
                boundsBottom = 165,
                boundsLeft = 135,
                boundsRight = 910
            ),
            RawScreenTextNode(
                text = "Balthazar",
                boundsTop = 498,
                boundsBottom = 532, // height = 34px <= captionFontMaxHeight (45px)
                boundsLeft = 790,
                boundsRight = 928
            ),
            RawScreenTextNode(
                text = "122万块，这数据量级，是分布式存储还是单机啊?",
                boundsTop = 546,
                boundsBottom = 664,
                boundsLeft = 215,
                boundsRight = 928
            ),
            RawScreenTextNode(
                text = "喦主 我一直在寻找，有下一顿饭钱的世界在哪里",
                boundsTop = 862,
                boundsBottom = 898,
                boundsLeft = 148,
                boundsRight = 886
            ),
            RawScreenTextNode(
                text = "单机,这是思源啊",
                boundsTop = 912,
                boundsBottom = 964,
                boundsLeft = 148,
                boundsRight = 478
            ),
            // Trailing member sent only an image/sticker below their role+nickname header (no text bubble)
            RawScreenTextNode(
                text = "[管理员]玄冰是仓鼠",
                boundsTop = 1040,
                boundsBottom = 1076,
                boundsLeft = 148,
                boundsRight = 430
            )
        )

        val ocrExtracted = ChatScreenContextExtractor.extractChatContext(
            nodes = ocrNodes,
            screenWidth = 1080,
            screenHeight = 2400,
            packageName = "com.tencent.mobileqq",
            appName = "QQ",
            maxMessages = 10
        )

        assertEquals(2, ocrExtracted.messageCount)
        assertEquals(
            listOf(
                "[我] 122万块，这数据量级，是分布式存储还是单机啊?",
                "[群友A] 单机,这是思源啊"
            ),
            ocrExtracted.formattedMessages
        )

        // 2. Verify consecutive 1v1 private bubbles (`CrazyPudge`) and consecutive group bubbles (1-line 56px -> 2-line 108px)
        // are NEVER falsely swallowed as nicknames.
        val private1v1 = listOf(
            RawScreenTextNode("CrazyPudge", boundsTop = 110, boundsBottom = 160, boundsLeft = 410, boundsRight = 670),
            RawScreenTextNode("组里突然线上出了个故障，今晚电影我可能赶不上了", boundsTop = 520, boundsBottom = 630, boundsLeft = 224, boundsRight = 936),
            RawScreenTextNode("没事呀，工作重要嘛🙂", boundsTop = 670, boundsBottom = 728, boundsLeft = 150, boundsRight = 560),
            RawScreenTextNode("你忙你的不用管我，反正票我也退不掉了，我自己去看完早点睡啦，别太累哦。", boundsTop = 756, boundsBottom = 910, boundsLeft = 150, boundsRight = 890)
        )
        val extracted1v1 = ChatScreenContextExtractor.extractChatContext(
            nodes = private1v1,
            screenWidth = 1080,
            screenHeight = 2400,
            packageName = "com.tencent.mm",
            appName = "微信",
            maxMessages = 10
        )
        assertEquals(3, extracted1v1.messageCount)
        assertEquals(ChatSpeaker.SELF, extracted1v1.messages[0].speaker)
        assertEquals("没事呀，工作重要嘛🙂", extracted1v1.messages[1].text)
        assertTrue(extracted1v1.messages[2].text.startsWith("你忙你的不用管我"))

        // Consecutive group bubbles where first bubble has nickname `凉意` (`height=36`) + Bubble 1 (`height=56`) +
        // continuation Bubble 2 (`height=56`) + continuation Bubble 3 (`height=112`):
        val groupMultiBubble = listOf(
            RawScreenTextNode("技术讨论群(42)", boundsTop = 110, boundsBottom = 165, boundsLeft = 140, boundsRight = 520),
            RawScreenTextNode("凉意", boundsTop = 400, boundsBottom = 436, boundsLeft = 150, boundsRight = 230),
            RawScreenTextNode("这个方案本地跑很稳", boundsTop = 448, boundsBottom = 504, boundsLeft = 150, boundsRight = 560),
            RawScreenTextNode("不对，同步是要单独配对象存储的", boundsTop = 528, boundsBottom = 584, boundsLeft = 150, boundsRight = 720),
            RawScreenTextNode("如果你只用单机版，百万级块走本地 SQLite 索引完全够用，不需要起集群。", boundsTop = 608, boundsBottom = 720, boundsLeft = 150, boundsRight = 890)
        )
        val extractedMulti = ChatScreenContextExtractor.extractChatContext(
            nodes = groupMultiBubble,
            screenWidth = 1080,
            screenHeight = 2400,
            packageName = "com.tencent.mm",
            appName = "微信",
            maxMessages = 10
        )
        assertEquals("All 3 bubbles must be preserved without Bubble 2 getting swallowed into Bubble 3", 3, extractedMulti.messageCount)
        assertEquals("[群友A(凉意)] 这个方案本地跑很稳", extractedMulti.formattedMessages[0])
        assertEquals("[对方] 不对，同步是要单独配对象存储的", extractedMulti.formattedMessages[1])
    }

    @Test
    fun feishuAndEnterpriseWorkplaceApps_resolveToBusinessAndGroupCollabBadges_andInjectAntiNicknamePromptGuards() {
        val feishu1v1 = CapturedChatContext(
            packageName = "com.ss.android.lark",
            appName = "飞书",
            topology = ChatTopology(isGroupChat = false)
        )
        val feishuGroup = CapturedChatContext(
            packageName = "com.ss.android.lark",
            appName = "飞书",
            topology = ChatTopology(isGroupChat = true, groupMemberCount = 18)
        )
        val dingTalk1v1 = CapturedChatContext(
            packageName = "com.alibaba.android.rimet",
            appName = "钉钉",
            topology = ChatTopology(isGroupChat = false)
        )
        val weChat1v1 = CapturedChatContext(
            packageName = "com.tencent.mm",
            appName = "微信",
            topology = ChatTopology(isGroupChat = false)
        )
        val weChatGroup = CapturedChatContext(
            packageName = "com.tencent.mm",
            appName = "微信",
            topology = ChatTopology(isGroupChat = true, groupMemberCount = 8)
        )

        assertEquals("商务协同", feishu1v1.effectiveBadgeTitle)
        assertEquals("群聊协同", feishuGroup.effectiveBadgeTitle)
        assertEquals("商务协同", dingTalk1v1.effectiveBadgeTitle)
        assertEquals("私聊社交", weChat1v1.effectiveBadgeTitle)
        assertEquals("群聊局势", weChatGroup.effectiveBadgeTitle)

        val (sysPrompt, feishuUserPrompt) = PromptBuilder.buildJevCopilotPrompts(
            domainProfile = JevDomainProfile.fromPackage("com.ss.android.lark"),
            targetAppName = "飞书",
            targetPackageName = "com.ss.android.lark",
            chatContextMessages = listOf("[对方] 周一前把排期对齐一下"),
            hasChatScreenshot = true,
            style = DefaultPresets.defaultStyles().first(),
            chatTopology = ChatTopology(isGroupChat = false)
        )
        assertTrue(feishuUserPrompt.contains("战术域：商务协同"))
        assertTrue(feishuUserPrompt.contains("权责归属、排期推进"))
        assertTrue(feishuUserPrompt.contains("严禁将长句式群昵称误当成聊天台词"))
        assertTrue(sysPrompt.contains("严禁把任何人的昵称当作聊天内容去回复或接梗"))
    }

    @Test
    fun jevResponseParser_preservesFullSubtextUpTo96Chars_andJevPanelFixesInlineBadgeGlyphClipping() {
        val fullSubtext = "对方多次在会话中追问具体问题卡点但始终未获正面回应，当前沟通耐心已逼近临界阈值，需先精准回溯遗漏问题再推进后续排期闭环。"
        assertTrue("Test subtext should exceed legacy 46-char limit (${fullSubtext.length})", fullSubtext.length > 46)
        val rawXml = """
            <jev>
            <lv>6</lv>
            <tag>追问未果显焦躁</tag>
            <subtext>$fullSubtext</subtext>
            <opt label="回溯重点">刚漏看上面那条了，卡点主要在离线索引同步这块，我把复现步骤整理发你。</opt>
            <opt label="顺势接球">抱歉刚在处理线上单子，你是说思源单机版那122万块数据的导入耗时吗？</opt>
            <opt label="策略追问">你那边现在报错的是本地检索卡顿，还是跨端同步超时？</opt>
            <weights>39,33,28</weights>
            </jev>
        """.trimIndent()

        val parsed = JevResponseParser.parse(rawXml)
        assertEquals(fullSubtext, parsed.subtext)
        assertEquals("追问未果显焦躁", parsed.tag)
        assertEquals(39, parsed.options[0].probability)

        val root = resolveProjectRoot()
        val jevPanelSource = File(root, "app/src/main/java/com/typefree/ime/ime/panels/JevCopilotPanel.kt").readText()
        assertTrue(
            "JevCopilotPanel must measure exact badge width via rememberTextMeasurer",
            jevPanelSource.contains("rememberTextMeasurer()")
        )
        assertTrue(
            "JevCopilotPanel must disable Android font padding inside inline badges to prevent bottom glyph clipping",
            jevPanelSource.contains("PlatformTextStyle(includeFontPadding = false)")
        )
        assertTrue(
            "JevCopilotPanel must use compact '⛶ 深读' pill so 360dp headers never crush",
            jevPanelSource.contains("\"⛶ 深读\"")
        )
        assertFalse(
            "JevCopilotPanel must never use TextOverflow.Ellipsis",
            jevPanelSource.contains("TextOverflow.Ellipsis")
        )
    }

    @Test
    fun floatingPreeditOverhangMetrics_onComputeInsetsShift_andSpaciousSingleRowCandidateStrip_matchV341Spec() {
        assertEquals(28, KeyboardMetrics.FLOATING_PREEDIT_OVERHANG_DP)

        // When SoftInputWindow mInputFrame top is at Y=1566 on a 2400px screen and floating overhang is 84px (28dp @ 3x),
        // contentTopInsets shifts down to Y=1650 (leaving the 252dp = 756px keyboard frame intact for the host app).
        val shiftedInset = KeyboardMetrics.computeFloatingOverhangContentTopInset(
            baseContentTopInsets = 1566,
            floatingOverhangPx = 84,
            decorHeight = 2400
        )
        assertEquals(1650, shiftedInset)

        // Safety clamp: never exceed decorHeight
        val clampedInset = KeyboardMetrics.computeFloatingOverhangContentTopInset(
            baseContentTopInsets = 2360,
            floatingOverhangPx = 84,
            decorHeight = 2400
        )
        assertEquals(2400, clampedInset)

        val root = resolveProjectRoot()
        val candidateStripsSource = File(root, "app/src/main/java/com/typefree/ime/ime/candidates/CandidateStrips.kt").readText()
        assertTrue(
            "CandidateStrips.kt must define FloatingPreeditCapsule",
            candidateStripsSource.contains("fun FloatingPreeditCapsule(")
        )
        assertTrue(
            "CandidateStrip must be restored to spacious single-row topBarHeight",
            candidateStripsSource.contains(".height(metrics.topBarHeight)")
        )

        val keyboardScreenFile = File(root, "app/src/main/java/com/typefree/ime/ime/KeyboardScreen.kt")
        val keyboardScreenSource = keyboardScreenFile.readText()
        val keyboardScreenLines = keyboardScreenFile.readLines().size
        assertTrue(
            "KeyboardScreen.kt must render FloatingPreeditCapsule in the top overhang",
            keyboardScreenSource.contains("FloatingPreeditCapsule(")
        )
        assertTrue(
            "KeyboardScreen.kt must stay <= 650 lines (actual: $keyboardScreenLines)",
            keyboardScreenLines <= 650
        )

        val imsSource = File(root, "app/src/main/java/com/typefree/ime/ime/TypeFreeInputMethodService.kt").readText()
        assertTrue(
            "TypeFreeInputMethodService.kt must shift contentTopInsets via computeFloatingOverhangContentTopInset",
            imsSource.contains("computeFloatingOverhangContentTopInset")
        )
        assertTrue(
            "TypeFreeInputMethodService.kt must set TOUCHABLE_INSETS_CONTENT so overhang touches pass through",
            imsSource.contains("Insets.TOUCHABLE_INSETS_CONTENT")
        )
    }
}
