package com.typefree.ime.v331

import com.typefree.ime.bubble.CapturedChatContext
import com.typefree.ime.bubble.ChatScreenContextExtractor
import com.typefree.ime.bubble.ChatSpeaker
import com.typefree.ime.bubble.RawScreenTextNode
import com.typefree.ime.bubble.TypeFreeAccessibilityService
import com.typefree.ime.bubble.ViewportOcrExtractor
import com.typefree.ime.data.AppSettings
import com.typefree.ime.data.DefaultPresets
import com.typefree.ime.ime.jev.JevDomainProfile
import com.typefree.ime.ime.jev.JevRoleMode
import com.typefree.ime.network.PromptBuilder
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class V331GroupChatRoleTopologyTest {

    @After
    fun tearDown() {
        TypeFreeAccessibilityService.debugSimulatedNodesForTest = null
        TypeFreeAccessibilityService.debugSimulatedScreenshotBase64ForTest = null
        ViewportOcrExtractor.debugSimulatedOcrNodesForTest = null
    }

    @Test
    fun daGeZiGroupChat_realDeviceRegression_pairs5NicknamesTo5Bubbles_andDetectsBystanderRole() {
        // Real-device reproduction from 《大鸽子交流群(8)》:
        // 5 sender nickname headers + 5 single-line short bubbles (even with identical 48px OCR box heights on 1.5K screen)
        val groupNodes = listOf(
            RawScreenTextNode(
                text = "大鸽子交流群(8)",
                boundsTop = 108,
                boundsBottom = 168,
                boundsLeft = 140,
                boundsRight = 520
            ),
            // Turn 1: 凉意 -> 不好玩
            RawScreenTextNode(
                text = "凉意",
                boundsTop = 320,
                boundsBottom = 368,
                boundsLeft = 160,
                boundsRight = 240
            ),
            RawScreenTextNode(
                text = "不好玩",
                boundsTop = 380,
                boundsBottom = 428,
                boundsLeft = 160,
                boundsRight = 310
            ),
            // Turn 2: 凉意 -> 反正不是我的菜吧
            RawScreenTextNode(
                text = "凉意",
                boundsTop = 480,
                boundsBottom = 528,
                boundsLeft = 160,
                boundsRight = 240
            ),
            RawScreenTextNode(
                text = "反正不是我的菜吧",
                boundsTop = 540,
                boundsBottom = 588,
                boundsLeft = 160,
                boundsRight = 520
            ),
            // Turn 3: 黄泽明 -> 你还不菜
            RawScreenTextNode(
                text = "黄泽明",
                boundsTop = 640,
                boundsBottom = 688,
                boundsLeft = 160,
                boundsRight = 270
            ),
            RawScreenTextNode(
                text = "你还不菜",
                boundsTop = 700,
                boundsBottom = 748,
                boundsLeft = 160,
                boundsRight = 360
            ),
            // Turn 4: 凉意 -> 再菜也是你爹
            RawScreenTextNode(
                text = "凉意",
                boundsTop = 800,
                boundsBottom = 848,
                boundsLeft = 160,
                boundsRight = 240
            ),
            RawScreenTextNode(
                text = "再菜也是你爹",
                boundsTop = 860,
                boundsBottom = 908,
                boundsLeft = 160,
                boundsRight = 450
            ),
            // Turn 5: 凉意 -> 子不嫌爹菜没听过？
            RawScreenTextNode(
                text = "凉意",
                boundsTop = 960,
                boundsBottom = 1008,
                boundsLeft = 160,
                boundsRight = 240
            ),
            RawScreenTextNode(
                text = "子不嫌爹菜没听过？",
                boundsTop = 1020,
                boundsBottom = 1068,
                boundsLeft = 160,
                boundsRight = 560
            )
        )

        val extracted = ChatScreenContextExtractor.extractChatContext(
            nodes = groupNodes,
            screenWidth = 1260,
            screenHeight = 2800,
            packageName = "com.tencent.mm",
            appName = "微信",
            maxMessages = 10
        )

        assertEquals("Must pair 5 nicknames with 5 bubbles into 5 messages (never 10)", 5, extracted.messageCount)
        assertEquals("群聊局势", extracted.effectiveBadgeTitle)
        assertTrue(extracted.topology.isGroupChat)
        assertEquals(8, extracted.topology.groupMemberCount)
        assertEquals(listOf("群友A(凉意)", "群友B(黄泽明)"), extracted.topology.distinctOtherSenders)
        assertEquals(0, extracted.topology.recentSelfTurns)
        assertFalse(extracted.topology.hasExplicitMentionMe)
        assertEquals(JevRoleMode.GROUP_BYSTANDER, extracted.topology.roleMode)

        assertEquals(
            listOf(
                "[群友A(凉意)] 不好玩",
                "[群友A(凉意)] 反正不是我的菜吧",
                "[群友B(黄泽明)] 你还不菜",
                "[群友A(凉意)] 再菜也是你爹",
                "[群友A(凉意)] 子不嫌爹菜没听过？"
            ),
            extracted.formattedMessages
        )
    }

    @Test
    fun twoConsecutiveShort1v1Bubbles_areNotMistakenlySwallowedAsNickname() {
        val private1v1Nodes = listOf(
            RawScreenTextNode(
                text = "林晓",
                boundsTop = 100,
                boundsBottom = 155,
                boundsLeft = 140,
                boundsRight = 280
            ),
            RawScreenTextNode(
                text = "在吗",
                boundsTop = 500,
                boundsBottom = 556,
                boundsLeft = 140,
                boundsRight = 260
            ),
            RawScreenTextNode(
                text = "今晚有空吗",
                boundsTop = 584,
                boundsBottom = 640,
                boundsLeft = 140,
                boundsRight = 380
            )
        )

        val extracted = ChatScreenContextExtractor.extractChatContext(
            nodes = private1v1Nodes,
            screenWidth = 1080,
            screenHeight = 2400,
            packageName = "com.tencent.mm",
            appName = "微信",
            maxMessages = 10
        )

        assertEquals(2, extracted.messageCount)
        assertEquals("私聊社交", extracted.effectiveBadgeTitle)
        assertFalse(extracted.topology.isGroupChat)
        assertEquals(JevRoleMode.PRIVATE_1V1, extracted.topology.roleMode)
        assertEquals(
            listOf(
                "[对方] 在吗",
                "[对方] 今晚有空吗"
            ),
            extracted.formattedMessages
        )
    }

    @Test
    fun hybridA11yNicknamesAndOcrCustomCanvasBubbles_fuseIn2DCoordinateSpace_andPreserveSenderAliasInWithViewportOutcome() = runBlocking {
        // Simulate WeChat / QQ NT where Accessibility only exposes the top group bar and sender nickname headers,
        // while the chat bubble text itself is rendered on a custom Canvas and recognized by on-device OCR.
        val a11yOnlyNodes = listOf(
            RawScreenTextNode(
                text = "大鸽子交流群(8)",
                boundsTop = 105,
                boundsBottom = 165,
                boundsLeft = 140,
                boundsRight = 500
            ),
            RawScreenTextNode(
                text = "凉意",
                boundsTop = 310,
                boundsBottom = 348,
                boundsLeft = 140,
                boundsRight = 220
            ),
            RawScreenTextNode(
                text = "黄泽明",
                boundsTop = 610,
                boundsBottom = 648,
                boundsLeft = 140,
                boundsRight = 250
            )
        )
        val initialA11yCtx = ChatScreenContextExtractor.extractChatContext(
            nodes = a11yOnlyNodes,
            screenWidth = 1080,
            screenHeight = 2400,
            packageName = "com.tencent.mm",
            appName = "微信"
        )
        assertTrue(
            "When A11y only captured short unpaired nickname labels, shouldTriggerOcrForCustomCanvas must be true",
            ChatScreenContextExtractor.shouldTriggerOcrForCustomCanvas(initialA11yCtx)
        )

        TypeFreeAccessibilityService.debugSimulatedNodesForTest = "com.tencent.mm" to a11yOnlyNodes
        ViewportOcrExtractor.debugSimulatedOcrNodesForTest = listOf(
            RawScreenTextNode(
                text = "不好玩",
                boundsTop = 358,
                boundsBottom = 410,
                boundsLeft = 140,
                boundsRight = 300
            ),
            RawScreenTextNode(
                text = "你还不菜，打我电话 13800138000",
                boundsTop = 658,
                boundsBottom = 710,
                boundsLeft = 140,
                boundsRight = 640
            )
        )
        TypeFreeAccessibilityService.debugSimulatedScreenshotBase64ForTest = "SIM_GROUP_JPEG"

        val settings = AppSettings(
            jevCopilotEnabled = true,
            chatContextReplyEnabled = true,
            chatContextDisclosureAccepted = true,
            chatContextSendScreenshot = false
        )
        val outcome = TypeFreeAccessibilityService.captureChatViewportOutcomeIfAvailable(
            expectedPackage = "com.tencent.mm",
            settings = settings,
            isIncognitoMode = false,
            isPasswordInput = false,
            ignoreChatContextForTurn = false,
            forJevCopilot = true,
            existingMessages = initialA11yCtx.formattedMessages,
            redactionSession = initialA11yCtx.redactionSession
        )
        assertNotNull(outcome)
        assertEquals(2, outcome!!.mergedMessages.size)
        assertEquals("[群友A(凉意)] 不好玩", outcome.mergedMessages[0])
        assertEquals("[群友B(黄泽明)] 你还不菜，打我电话 [手机号_1]", outcome.mergedMessages[1])

        val mergedCtx = initialA11yCtx.withViewportOutcome(
            outcome = outcome,
            fallbackPackage = "com.tencent.mm",
            fallbackAppName = "微信"
        )
        assertEquals(2, mergedCtx.messageCount)
        assertEquals("群聊局势", mergedCtx.effectiveBadgeTitle)
        assertTrue(mergedCtx.topology.isGroupChat)
        assertEquals(8, mergedCtx.topology.groupMemberCount)
        assertEquals(listOf("群友A(凉意)", "群友B(黄泽明)"), mergedCtx.topology.distinctOtherSenders)
        assertEquals(JevRoleMode.GROUP_BYSTANDER, mergedCtx.topology.roleMode)
        assertEquals("群友A(凉意)", mergedCtx.messages[0].senderAlias)
        assertEquals("群友B(黄泽明)", mergedCtx.messages[1].senderAlias)
        assertEquals(ChatSpeaker.OTHER, mergedCtx.messages[0].speaker)
    }

    @Test
    fun groupInvolvedParty_andPromptBuilder3StateTopology_injectsConversationTopologyAndMultiAvatarVisionGuide() {
        // 1. Group chat where user [我] is explicitly @mentioned or has recent self turns -> GROUP_INVOLVED_PARTY
        val involvedNodes = listOf(
            RawScreenTextNode(
                text = "项目冲刺群(12)",
                boundsTop = 100,
                boundsBottom = 160,
                boundsLeft = 140,
                boundsRight = 520
            ),
            RawScreenTextNode(
                text = "[有人@我]",
                boundsTop = 260,
                boundsBottom = 300,
                boundsLeft = 820,
                boundsRight = 1020
            ),
            RawScreenTextNode(
                text = "王工",
                boundsTop = 340,
                boundsBottom = 378,
                boundsLeft = 140,
                boundsRight = 230
            ),
            RawScreenTextNode(
                text = "今晚灰度包几点能发出来？",
                boundsTop = 388,
                boundsBottom = 448,
                boundsLeft = 140,
                boundsRight = 640
            ),
            RawScreenTextNode(
                text = "我正在跑最后一轮回归单测",
                boundsTop = 500,
                boundsBottom = 560,
                boundsLeft = 520,
                boundsRight = 960
            )
        )
        val involvedCtx = ChatScreenContextExtractor.extractChatContext(
            nodes = involvedNodes,
            screenWidth = 1080,
            screenHeight = 2400,
            packageName = "com.tencent.mm",
            appName = "微信"
        )
        assertTrue(involvedCtx.topology.isGroupChat)
        assertEquals(12, involvedCtx.topology.groupMemberCount)
        assertEquals(1, involvedCtx.topology.recentSelfTurns)
        assertTrue(involvedCtx.topology.hasExplicitMentionMe)
        assertEquals(JevRoleMode.GROUP_INVOLVED_PARTY, involvedCtx.topology.roleMode)

        // 2. Verify PromptBuilder injects <conversation_topology>, updates <domain_profile> to 群聊局势,
        // updates <chat_screen_vision_guide> for multi-avatar group chats, and includes 3-state routing in System Prompt
        val bystanderTopology = com.typefree.ime.ime.jev.ChatTopology(
            isGroupChat = true,
            groupMemberCount = 8,
            distinctOtherSenders = listOf("群友A(凉意)", "群友B(黄泽明)"),
            recentSelfTurns = 0,
            hasExplicitMentionMe = false,
            roleMode = JevRoleMode.GROUP_BYSTANDER
        )
        val (sysPrompt, userPrompt) = PromptBuilder.buildJevCopilotPrompts(
            domainProfile = JevDomainProfile.IM_CHAT,
            targetAppName = "微信",
            chatContextMessages = listOf(
                "[群友A(凉意)] 不好玩",
                "[群友B(黄泽明)] 你还不菜",
                "[群友A(凉意)] 再菜也是你爹"
            ),
            hasChatScreenshot = true,
            style = DefaultPresets.defaultStyles().first(),
            chatTopology = bystanderTopology
        )

        assertTrue(userPrompt.contains("战术域：群聊局势"))
        assertTrue(userPrompt.contains("<conversation_topology>"))
        assertTrue(userPrompt.contains("会话类型：多人聊天群（8人）"))
        assertTrue(userPrompt.contains("识别到的其他发言成员：2人（群友A(凉意)、群友B(黄泽明)）"))
        assertTrue(userPrompt.contains("机主[我]近期发言条数：0条（无 @我 提及）"))
        assertTrue(userPrompt.contains("机主当前角色定位：群聊第三方旁观者"))
        assertTrue(userPrompt.contains("[群友A]/[群友B]"))
        assertTrue(userPrompt.contains("绝不可将群友A对群友B的调侃误判为对机主[我]说话"))

        assertTrue(sysPrompt.contains("三态角色拓扑路由铁律"))
        assertTrue(sysPrompt.contains("态势 A【1v1私聊对象】"))
        assertTrue(sysPrompt.contains("态势 B【群聊当事人】"))
        assertTrue(sysPrompt.contains("态势 C【群聊第三方旁观者】"))
        assertTrue(sysPrompt.contains("再菜也是你爹"))
        assertTrue(sysPrompt.contains("场外吃瓜/扇风递梗"))
    }
}
