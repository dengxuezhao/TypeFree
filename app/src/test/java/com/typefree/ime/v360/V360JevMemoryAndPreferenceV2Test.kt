package com.typefree.ime.v360

import com.typefree.ime.bubble.RawScreenTextNode
import com.typefree.ime.data.AppSettings
import com.typefree.ime.ime.jev.ChatTopology
import com.typefree.ime.ime.jev.JevResponseParser
import com.typefree.ime.ime.jev.JevRoleMode
import com.typefree.ime.ime.jev.memory.AnchorResolution
import com.typefree.ime.ime.jev.memory.AxisPreferenceStats
import com.typefree.ime.ime.jev.memory.ConversationAnchorExtractor
import com.typefree.ime.ime.jev.memory.DraftDiffAnalyzer
import com.typefree.ime.ime.jev.memory.FeedbackLearningEvent
import com.typefree.ime.ime.jev.memory.GroupSceneTag
import com.typefree.ime.ime.jev.memory.InMemoryAnchorSaltProvider
import com.typefree.ime.ime.jev.memory.InMemoryJevMemoryBackend
import com.typefree.ime.ime.jev.memory.JevAxisGroup
import com.typefree.ime.ime.jev.memory.JevClock
import com.typefree.ime.ime.jev.memory.JevDraftFeedbackMonitor
import com.typefree.ime.ime.jev.memory.JevMemoryRepository
import com.typefree.ime.ime.jev.memory.JevPreferenceLearner
import com.typefree.ime.ime.jev.memory.PrivateRelationTag
import com.typefree.ime.ime.jev.memory.RelationPragmaticMapper
import com.typefree.ime.ime.jev.memory.StyleFeatureKey
import com.typefree.ime.ime.jev.memory.TopologyKind
import com.typefree.ime.ime.jev.memory.TrackedJevDraftContext
import com.typefree.ime.network.PromptBuilder
import com.typefree.ime.privacy.LocalPiiRedactor
import com.typefree.ime.ui.screens.ApiConfigBackupHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit

class V360JevMemoryAndPreferenceV2Test {

    private class MutableClock(var timeMs: Long = 1_700_000_000_000L) : JevClock {
        override fun nowMs(): Long = timeMs
    }

    private class DirectExecutorService : AbstractExecutorService() {
        private var stopped = false
        override fun shutdown() { stopped = true }
        override fun shutdownNow(): MutableList<Runnable> = mutableListOf<Runnable>().also { stopped = true }
        override fun isShutdown(): Boolean = stopped
        override fun isTerminated(): Boolean = stopped
        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = true
        override fun execute(command: Runnable) { command.run() }
    }

    private fun resolveProjectRoot(): File {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        return if (File(cwd, "app/src/main").exists()) cwd else cwd.parentFile ?: cwd
    }

    private fun headerNode(
        text: String,
        top: Int = 140,
        bottom: Int = 220,
        left: Int = 180,
        right: Int = 760
    ) = RawScreenTextNode(
        text = text,
        boundsTop = top,
        boundsBottom = bottom,
        boundsLeft = left,
        boundsRight = right
    )

    @Test
    fun anchorExtractor_handlesMultiLineHeaderSplit_countDrift_externalBadges_and_controlGlyphs() {
        val clock = MutableClock()
        val salt = InMemoryAnchorSaltProvider(ByteArray(32) { 0x11 })
        val extractor = ConversationAnchorExtractor(salt, clock)

        // TC-A01: Single-node multi-line "张三\n手机在线" == "张三\n离线"
        val resOnline = extractor.extractAnchor(
            nodes = listOf(headerNode("张三\n手机在线")),
            screenWidth = 1080,
            screenHeight = 2400,
            packageName = "com.tencent.mobileqq"
        ) as AnchorResolution.Resolved

        val resOffline = extractor.extractAnchor(
            nodes = listOf(headerNode("张三\n离线")),
            screenWidth = 1080,
            screenHeight = 2400,
            packageName = "com.tencent.mobileqq"
        ) as AnchorResolution.Resolved

        assertEquals(resOnline.anchorHash, resOffline.anchorHash)
        assertEquals(TopologyKind.PRIV, resOnline.topologyKind)
        assertEquals("张*", resOnline.maskedTitleHint)

        // TC-A02: Group count drift "产品研发群(48)" == "产品研发群 (49)"
        val grp48 = extractor.extractAnchor(
            nodes = listOf(headerNode("产品研发群(48)")),
            screenWidth = 1080,
            screenHeight = 2400,
            packageName = "com.tencent.mm"
        ) as AnchorResolution.Resolved

        val grp49 = extractor.extractAnchor(
            nodes = listOf(headerNode("产品研发群 (49)")),
            screenWidth = 1080,
            screenHeight = 2400,
            packageName = "com.tencent.mm"
        ) as AnchorResolution.Resolved

        assertEquals(grp48.anchorHash, grp49.anchorHash)
        assertEquals(TopologyKind.GRP, grp49.topologyKind)
        assertEquals(49, grp49.headerMemberCount)

        // TC-A03: Feishu multi-line external badge "李四\n@字节跳动" + unscoped fallback
        val feishuExt = extractor.extractAnchor(
            nodes = listOf(headerNode("李四\n@字节跳动")),
            screenWidth = 1080,
            screenHeight = 2400,
            packageName = "com.ss.android.lark"
        ) as AnchorResolution.Resolved
        val feishuPlain = extractor.extractAnchor(
            nodes = listOf(headerNode("李四")),
            screenWidth = 1080,
            screenHeight = 2400,
            packageName = "com.ss.android.lark"
        ) as AnchorResolution.Resolved

        assertTrue(feishuExt.hasExternalBadge)
        assertEquals(feishuPlain.anchorHash, feishuExt.fallbackUnscopedHash)

        // TC-A04: Unread badge & ear/mute glyph stripping
        val wechatNoisy = extractor.extractAnchor(
            nodes = listOf(
                headerNode("微信(99+)", top = 140, bottom = 200, left = 40, right = 180),
                headerNode("产品研发群(49)\uD83D\uDC42")
            ),
            screenWidth = 1080,
            screenHeight = 2400,
            packageName = "com.tencent.mm"
        ) as AnchorResolution.Resolved
        assertEquals(grp49.anchorHash, wechatNoisy.anchorHash)
    }

    @Test
    fun anchorExtractor_crossPlatformCanonicalHash_genericIsolation_and_sessionPinFallback() {
        val clock = MutableClock()
        val salt = InMemoryAnchorSaltProvider(ByteArray(32) { 0x22 })
        val extractor = ConversationAnchorExtractor(salt, clock)

        // Cross-platform canonical identity (Android WeChat vs macOS WeChat)
        val androidWechat = extractor.extractAnchor(
            nodes = listOf(headerNode("林总监")),
            screenWidth = 1080,
            screenHeight = 2400,
            packageName = "com.tencent.mm",
            inputSessionToken = 101
        ) as AnchorResolution.Resolved

        val macWechat = extractor.extractAnchor(
            nodes = listOf(headerNode("林总监")),
            screenWidth = 1080,
            screenHeight = 2400,
            packageName = "com.tencent.xinwechat"
        ) as AnchorResolution.Resolved

        assertEquals(androidWechat.canonicalApp, macWechat.canonicalApp)
        assertEquals(androidWechat.anchorHash, macWechat.anchorHash)

        // Transient "对方正在输入..." hits inputSessionToken pin first
        val typingPinned = extractor.extractAnchor(
            nodes = listOf(headerNode("对方正在输入...")),
            screenWidth = 1080,
            screenHeight = 2400,
            packageName = "com.tencent.mm",
            inputSessionToken = 101
        ) as AnchorResolution.Resolved
        assertTrue(typingPinned.fromPinOrCache)
        assertEquals(androidWechat.anchorHash, typingPinned.anchorHash)

        // Different session token within 90s hits volatile cache
        clock.timeMs += 5_000L
        val typingCached = extractor.extractAnchor(
            nodes = listOf(headerNode("对方正在输入...")),
            screenWidth = 1080,
            screenHeight = 2400,
            packageName = "com.tencent.mm",
            inputSessionToken = 102
        ) as AnchorResolution.Resolved
        assertTrue(typingCached.fromPinOrCache)
        assertEquals(androidWechat.anchorHash, typingCached.anchorHash)

        // After >90s TTL expired, transient header degrades safely to EphemeralUnanchored
        clock.timeMs += 100_000L
        val coldExpired = extractor.extractAnchor(
            nodes = listOf(headerNode("对方正在输入...")),
            screenWidth = 1080,
            screenHeight = 2400,
            packageName = "com.tencent.mm",
            inputSessionToken = 103
        )
        assertTrue(coldExpired is AnchorResolution.EphemeralUnanchored)

        // Generic ambiguous titles ("momo", "微信用户", "客服") never persist sessions
        val repo = JevMemoryRepository(InMemoryJevMemoryBackend(), salt, clock, DirectExecutorService())
        for (title in listOf("momo", "微信用户", "客服")) {
            val generic = extractor.extractAnchor(
                nodes = listOf(headerNode(title)),
                screenWidth = 1080,
                screenHeight = 2400,
                packageName = "com.tencent.mm"
            ) as AnchorResolution.Resolved
            assertTrue("Expected ambiguous for $title", generic.isAmbiguousGeneric)
            val saved = repo.saveExplicitRelation(
                anchor = generic,
                topology = ChatTopology(),
                privateTag = PrivateRelationTag.CLIENT_EXTERNAL,
                groupTag = GroupSceneTag.UNSET,
                redactedCustomNote = "测试备注"
            )
            assertNull("Generic ambiguous title $title must not persist", saved)
        }
    }

    @Test
    fun repository_volatileBubbleContinuityAutoRelinksRenamedSession_withoutPersistingBubbles() {
        val clock = MutableClock()
        val salt = InMemoryAnchorSaltProvider(ByteArray(32) { 0x33 })
        val extractor = ConversationAnchorExtractor(salt, clock)
        val backend = InMemoryJevMemoryBackend()
        val repo = JevMemoryRepository(backend, salt, clock, DirectExecutorService())

        val oldAnchor = extractor.extractAnchor(
            nodes = listOf(headerNode("老王")),
            screenWidth = 1080,
            screenHeight = 2400,
            packageName = "com.tencent.mm"
        ) as AnchorResolution.Resolved

        val saved = repo.saveExplicitRelation(
            anchor = oldAnchor,
            topology = ChatTopology(),
            privateTag = PrivateRelationTag.CLIENT_EXTERNAL,
            groupTag = GroupSceneTag.UNSET,
            redactedCustomNote = "忌催进度，周报同步即可"
        )!!

        val recentBubbles = listOf(
            "对方: 这周排期大概怎么安排",
            "我: 周三前给你一个初版里程碑",
            "对方: 行，记得同步给李工"
        )
        repo.resolveContext(oldAnchor, ChatTopology(), recentFormattedMessages = recentBubbles)

        // Contact renamed to "王总（华东）" -> new anchorHash with overlapping tail bubbles
        val renamedAnchor = extractor.extractAnchor(
            nodes = listOf(headerNode("王总（华东）")),
            screenWidth = 1080,
            screenHeight = 2400,
            packageName = "com.tencent.mm"
        ) as AnchorResolution.Resolved
        assertNotEquals(oldAnchor.anchorHash, renamedAnchor.anchorHash)

        val relinkedCtx = repo.resolveContext(
            anchor = renamedAnchor,
            topology = ChatTopology(),
            recentFormattedMessages = listOf(
                "我: 周三前给你一个初版里程碑",
                "对方: 行，记得同步给李工",
                "对方: 我下午先开会"
            )
        )

        assertTrue(relinkedCtx.autoRelinkedAlias)
        assertEquals(saved.sessionUid, relinkedCtx.session?.sessionUid)
        assertEquals(PrivateRelationTag.CLIENT_EXTERNAL, relinkedCtx.session?.privateTag)

        // Ensure backend persistence contains zero message bubble text
        val snapshot = backend.loadAllSnapshot()
        assertEquals(1, snapshot.sessions.size)
        assertEquals(2, snapshot.aliases.size)
        val persistedNotes = snapshot.sessions.joinToString { "${it.redactedCustomNote} ${it.maskedTitleHint}" }
        assertFalse(persistedNotes.contains("里程碑"))
        assertFalse(persistedNotes.contains("李工"))
    }

    @Test
    fun feedbackMonitor_capturesBothDirectPreviewSend_and_CommittedEdits_withDeleteAndRewriteGuards() {
        val clock = MutableClock()
        val events = mutableListOf<FeedbackLearningEvent>()
        var directPreviewClearedCount = 0
        val monitor = JevDraftFeedbackMonitor(
            clock = clock,
            onLearningEvent = { events.add(it) },
            onDirectSendDuringPreview = { directPreviewClearedCount++ }
        )

        val anchor = AnchorResolution.Resolved("h1", null, null, "wechat", TopologyKind.PRIV)

        // Stage 1: User taps strategy card -> presses host app's Send button directly while panel open
        monitor.onPreviewCardApplied(
            TrackedJevDraftContext(
                anchor = anchor,
                topology = ChatTopology(),
                ephemeralPrivateTag = PrivateRelationTag.PEER_COLLEAGUE,
                ephemeralGroupTag = GroupSceneTag.UNSET,
                axisGroup = JevAxisGroup.TKI_GAME,
                rawSlotIndex = 1,
                wasTopPinnedSlot = true,
                draftText = "收到，我五分钟内发你。",
                initialInputPrefix = "",
                activeStyleFeatures = emptySet(),
                includeMaskedHint = true,
                targetPackage = "com.tencent.mm",
                fieldId = 1,
                startedAtMs = clock.nowMs()
            )
        )
        clock.timeMs += 250L
        monitor.onSelectionOrTextUpdated(
            newSelStart = 0,
            newSelEnd = 0,
            candidatesStart = -1,
            candidatesEnd = -1,
            isComposing = false,
            editorTextSnapshot = ""
        )
        assertEquals(1, events.size)
        assertTrue(events[0].isZeroEditDirectSend)
        assertEquals(1, events[0].adoptedRawIndex)
        assertEquals(1, directPreviewClearedCount)

        // Stage 2: User closes panel -> strips trailing period -> taps Send
        monitor.onPreviewCardApplied(
            TrackedJevDraftContext(
                anchor = anchor,
                topology = ChatTopology(),
                ephemeralPrivateTag = PrivateRelationTag.PEER_COLLEAGUE,
                ephemeralGroupTag = GroupSceneTag.UNSET,
                axisGroup = JevAxisGroup.TKI_GAME,
                rawSlotIndex = 0,
                wasTopPinnedSlot = false,
                draftText = "收到，我五分钟内发你。",
                initialInputPrefix = "",
                activeStyleFeatures = emptySet(),
                includeMaskedHint = true,
                targetPackage = "com.tencent.mm",
                fieldId = 1,
                startedAtMs = clock.nowMs()
            )
        )
        monitor.onDraftCommittedOnExit("收到，我五分钟内发你。")
        clock.timeMs += 1_000L
        monitor.onSelectionOrTextUpdated(10, 10, -1, -1, isComposing = false, editorTextSnapshot = "收到，我五分钟内发你")
        clock.timeMs += 500L
        monitor.onSelectionOrTextUpdated(0, 0, -1, -1, isComposing = false, editorTextSnapshot = "")

        assertEquals(2, events.size)
        assertFalse(events[1].isZeroEditDirectSend)
        assertTrue(
            events[1].featureObservations.any {
                it.key == StyleFeatureKey.DROP_TRAILING_PERIOD && it.targetValue == 1.0 && !it.isReverseEscape
            }
        )

        // Guard A: Manual Backspace/Delete clearing editor to "" must NOT misclassify as Send
        monitor.onPreviewCardApplied(
            TrackedJevDraftContext(
                anchor = anchor,
                topology = ChatTopology(),
                ephemeralPrivateTag = PrivateRelationTag.UNSET,
                ephemeralGroupTag = GroupSceneTag.UNSET,
                axisGroup = JevAxisGroup.TKI_GAME,
                rawSlotIndex = 0,
                wasTopPinnedSlot = false,
                draftText = "这个方案可行。",
                initialInputPrefix = "",
                activeStyleFeatures = emptySet(),
                includeMaskedHint = true,
                targetPackage = "com.tencent.mm",
                fieldId = 1,
                startedAtMs = clock.nowMs()
            )
        )
        monitor.onDraftCommittedOnExit("这个方案可行。")
        monitor.onUserDeleteOrCutTriggered(remainingTextLengthHint = 0)
        clock.timeMs += 100L
        monitor.onSelectionOrTextUpdated(0, 0, -1, -1, isComposing = false, editorTextSnapshot = "")
        assertEquals(2, events.size)

        // Guard B: Full rewrite (LCSRatio < 0.25) skips learning completely
        assertNull(
            DraftDiffAnalyzer.extractFeatureObservations(
                committed = "我觉得我们可以先对齐一下下周的开发排期再定上线时间。",
                sent = "算了不买了"
            )
        )
    }

    @Test
    fun preferenceLearner_bidirectionalHysteresisUnwind_coldStart_decay_and_strongVeto() {
        var now = 1_700_000_000_000L
        var features = emptyMap<StyleFeatureKey, com.typefree.ime.ime.jev.memory.FeatureCounter>()

        // 5 consecutive trailing-period deletions activate DROP_TRAILING_PERIOD
        repeat(5) {
            now += 10_000L
            features = JevPreferenceLearner.updateFeatureCounters(
                current = features,
                observations = DraftDiffAnalyzer.extractFeatureObservations(
                    committed = "好的，我马上处理。",
                    sent = "好的，我马上处理",
                    activeFeatures = emptySet()
                )!!,
                nowMs = now
            )
        }
        assertTrue(features[StyleFeatureKey.DROP_TRAILING_PERIOD]!!.isActive)

        // 2 consecutive manual period restorations (isReverseEscape = true) unwind it cleanly!
        repeat(2) { idx ->
            now += 10_000L
            val activeSet = if (features[StyleFeatureKey.DROP_TRAILING_PERIOD]!!.isActive) {
                setOf(StyleFeatureKey.DROP_TRAILING_PERIOD)
            } else {
                emptySet()
            }
            val obs = DraftDiffAnalyzer.extractFeatureObservations(
                committed = "好的，我马上处理",
                sent = "好的，我马上处理。",
                activeFeatures = activeSet
            )!!
            assertTrue("Step $idx must emit reverse escape", obs.any { it.isReverseEscape })
            features = JevPreferenceLearner.updateFeatureCounters(features, obs, nowMs = now)
        }
        assertFalse(
            "Two consecutive manual periods must automatically deactivate DROP_TRAILING_PERIOD",
            features[StyleFeatureKey.DROP_TRAILING_PERIOD]!!.isActive
        )

        // Cold-start gate: n = 4 (< 5) keeps raw model weights unchanged
        val coldStats = AxisPreferenceStats(probs = listOf(0.05, 0.90, 0.05), sampleCount = 4, updatedAtMs = now)
        assertEquals(
            listOf(45, 35, 20),
            JevPreferenceLearner.fuseUtilityWeights(listOf(45, 35, 20), coldStats, null, now)
        )

        // Mild context tie-breaker with warm preference shifts order toward slot 1
        val warmStats = AxisPreferenceStats(probs = listOf(0.05, 0.90, 0.05), sampleCount = 10, updatedAtMs = now)
        val fusedTieBreak = JevPreferenceLearner.fuseUtilityWeights(listOf(36, 34, 30), warmStats, null, now)
        assertTrue(
            "Expected slot 1 (${fusedTieBreak[1]}) > slot 0 (${fusedTieBreak[0]})",
            fusedTieBreak[1] > fusedTieBreak[0]
        )

        // Strong context veto: [80, 12, 8] keeps slot 0 firmly on top even against strong slot-1 habit
        val fusedVeto = JevPreferenceLearner.fuseUtilityWeights(listOf(80, 12, 8), warmStats, null, now)
        assertTrue(
            "Strong context veto must keep slot 0 (${fusedVeto[0]}) > slot 1 (${fusedVeto[1]})",
            fusedVeto[0] > fusedVeto[1]
        )

        // Temporal decay: after 60d grace + 120d half-life (= 180d), probs decay halfway to 1/3
        val futureMs = now + 180L * 86_400_000L
        val decayed = JevPreferenceLearner.applyTemporalDecayToAxis(warmStats, futureMs)!!
        val expectedMid = 0.5 * 0.90 + 0.5 * (1.0 / 3.0)
        assertEquals(expectedMid, decayed.probs[1], 1e-6)
    }

    @Test
    fun responseParser_lockVisualOrderPreservesClickedSlotIndices_andExtractsRoute() {
        val raw = """
            <jev>
            <lv>6</lv>
            <tag>试探压价</tag>
            <subtext>对方借口对比竞品，想让你先松口报价。</subtext>
            <opt label="稳住锚点">我们这套包含了半年质保，成本确实卡在这里。</opt>
            <opt label="阶梯换量">如果您这周能定下来三台，我找主管申请个团购价。</opt>
            <opt label="幽默兜底">老板太会砍了，我这再降就得倒贴运费啦。</opt>
            <weights>15,70,15</weights>
            <route>P</route>
            </jev>
        """.trimIndent()

        // Unlocked: sorted descending -> weight 70 (raw slot 1) first with isTopRecommended = true
        val unlocked = JevResponseParser.parse(raw, lockVisualOrder = false)
        assertEquals("P", unlocked.routeCode)
        assertEquals(3, unlocked.options.size)
        assertEquals(1, unlocked.options[0].rawSlotIndex)
        assertEquals(70, unlocked.options[0].probability)
        assertTrue(unlocked.options[0].isTopRecommended)
        assertFalse(unlocked.options[1].isTopRecommended)

        // Locked: visual order stays [0, 1, 2], while raw slot 1 still carries weight 70 and isTopRecommended
        val locked = JevResponseParser.parse(raw, lockVisualOrder = true)
        assertEquals(listOf(0, 1, 2), locked.options.map { it.rawSlotIndex })
        assertFalse(locked.options[0].isTopRecommended)
        assertTrue(locked.options[1].isTopRecommended)
        assertEquals(70, locked.options[1].probability)
    }

    @Test
    fun promptBuilder_preservesStaticSystemPromptGuard_andRedactsNotes() {
        val field = PromptBuilder::class.java.getDeclaredField("JEV_COPILOT_SYSTEM_PROMPT")
        assertTrue(Modifier.isStatic(field.modifiers) && Modifier.isFinal(field.modifiers))
        assertTrue(PromptBuilder.JEV_COPILOT_SYSTEM_PROMPT.contains("<jev>"))
        assertTrue(PromptBuilder.JEV_COPILOT_SYSTEM_PROMPT.contains("<chat_context>"))
        assertTrue(PromptBuilder.JEV_COPILOT_SYSTEM_PROMPT.contains("极速精炼快思纪律"))

        val redacted = LocalPiiRedactor.redactRelationNote("甲方总监，别打 13812345678 也别发邮箱 a@b.com，最忌直接催进度，态度客气点")
        assertFalse(redacted.contains("13812345678"))
        assertFalse(redacted.contains("a@b.com"))
        assertTrue(redacted.codePointCount(0, redacted.length) <= 40)

        val payload = RelationPragmaticMapper.buildPromptPayload(
            topology = ChatTopology(isGroupChat = false, roleMode = JevRoleMode.PRIVATE_1V1),
            anchor = AnchorResolution.Resolved("x", null, null, "wechat", TopologyKind.PRIV, hasExternalBadge = true),
            session = null,
            ephemeralPrivateTag = PrivateRelationTag.CLIENT_EXTERNAL,
            ephemeralGroupTag = GroupSceneTag.UNSET,
            ephemeralCustomNote = redacted,
            bucketProfile = null,
            globalProfile = null,
            nowMs = 1_700_000_000_000L
        )
        val (sys1, usr1) = PromptBuilder.buildJevCopilotPrompts(
            domainProfile = com.typefree.ime.ime.jev.JevDomainProfile.IM_CHAT,
            memoryPayload = null
        )
        val (sys2, usr2) = PromptBuilder.buildJevCopilotPrompts(
            domainProfile = com.typefree.ime.ime.jev.JevDomainProfile.IM_CHAT,
            memoryPayload = payload
        )
        assertEquals("System prompt must remain 100% static for Prefix Cache", sys1, sys2)
        assertFalse(usr1.contains("<relationship_context>"))
        assertTrue(usr2.contains("<relationship_context>"))
        assertTrue(usr2.contains("<route>E或P或G或B</route>"))
        assertTrue(payload.toCompactXmlBlock().length <= 240)
    }

    @Test
    fun backupRules_andEncryptedJevMemoryExportImportRoundTrip() {
        val root = resolveProjectRoot()
        val backupXml = File(root, "app/src/main/res/xml/backup_rules.xml").readText()
        val dataExtractionXml = File(root, "app/src/main/res/xml/data_extraction_rules.xml").readText()
        assertTrue(backupXml.contains("jev_memory_v2.db"))
        assertTrue(dataExtractionXml.contains("jev_memory_v2.db"))

        val clock = MutableClock()
        val sourceSalt = InMemoryAnchorSaltProvider(ByteArray(32) { 0x55 })
        val sourceBackend = InMemoryJevMemoryBackend()
        val sourceRepo = JevMemoryRepository(sourceBackend, sourceSalt, clock, DirectExecutorService())
        val extractor = ConversationAnchorExtractor(sourceSalt, clock)
        val anchor = extractor.extractAnchor(
            nodes = listOf(headerNode("王总")),
            screenWidth = 1080,
            screenHeight = 2400,
            packageName = "com.tencent.mm"
        ) as AnchorResolution.Resolved
        sourceRepo.saveExplicitRelation(
            anchor = anchor,
            topology = ChatTopology(),
            privateTag = PrivateRelationTag.SUPERIOR_ELDER,
            groupTag = GroupSceneTag.UNSET,
            redactedCustomNote = "汇报先给结论",
            includeMaskedHint = true
        )
        sourceRepo.setBucketFeatureOverride(
            bucketKey = RelationPragmaticMapper.GLOBAL_BUCKET_KEY,
            key = StyleFeatureKey.DROP_TRAILING_PERIOD,
            overrideValue = true
        )

        val exportedJson = ApiConfigBackupHelper.exportBackupWithJevMemory(
            settings = AppSettings(),
            includeSecrets = true,
            memoryRepository = sourceRepo,
            memoryPassphrase = "strong-passphrase-2026".toCharArray()
        )
        assertTrue(ApiConfigBackupHelper.hasEncryptedJevMemoryArchive(exportedJson))
        assertFalse("Exported ciphertext JSON must never leak plaintext note", exportedJson.contains("汇报先给结论"))

        // Wrong passphrase must fail cleanly
        val destSalt = InMemoryAnchorSaltProvider(ByteArray(32) { 0x66 })
        val destRepo = JevMemoryRepository(InMemoryJevMemoryBackend(), destSalt, clock, DirectExecutorService())
        val wrongPassRes = ApiConfigBackupHelper.importBackupWithJevMemory(
            rawJson = exportedJson,
            existingSettings = AppSettings(),
            memoryRepository = destRepo,
            memoryPassphrase = "wrong-pass".toCharArray()
        )
        assertTrue(wrongPassRes.isFailure)

        // Correct passphrase decrypts sessions, aliases, buckets and clears maskedTitleHint on export
        val archive = com.google.gson.JsonParser.parseString(exportedJson)
            .asJsonObject
            .get("jevMemoryArchive")
            .let {
                com.google.gson.Gson().fromJson(
                    it,
                    com.typefree.ime.ime.jev.memory.EncryptedJevMemoryArchive::class.java
                )
            }
        var adoptedSalt: ByteArray? = null
        val directImport = destRepo.importEncryptedArchive(
            archive = archive,
            passphrase = "strong-passphrase-2026".toCharArray(),
            adoptDeviceSaltCallback = { adoptedSalt = it }
        )
        assertTrue(directImport.isSuccess)
        val summary = directImport.getOrThrow()
        assertEquals(1, summary.importedSessions)
        assertEquals(1, summary.importedAliases)
        assertTrue(summary.adoptedDeviceSalt)
        assertNotNull(adoptedSalt)

        val importedSession = destRepo.listAllSessionsSorted().single()
        assertEquals("汇报先给结论", importedSession.redactedCustomNote)
        assertNull("maskedTitleHint must be stripped before cross-device export", importedSession.maskedTitleHint)
    }
}
