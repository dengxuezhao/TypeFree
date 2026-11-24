package com.typefree.ime.v350

import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.typefree.ime.data.AppRepository
import com.typefree.ime.data.AppSettings
import com.typefree.ime.data.DefaultPresets
import com.typefree.ime.data.HistoryRetention
import com.typefree.ime.data.InMemoryKeyValueStore
import com.typefree.ime.data.JvmAesGcmCipher
import com.typefree.ime.data.SecretVault
import com.typefree.ime.ime.ImeHostCallbacks
import com.typefree.ime.ime.ImeUiState
import com.typefree.ime.ime.KeyboardTabMode
import com.typefree.ime.ime.UndoSnapshot
import com.typefree.ime.ime.VoiceController
import com.typefree.ime.ime.VoiceSubPanel
import com.typefree.ime.ime.chrome.TopBarSlotPolicy
import com.typefree.ime.ime.panels.HelpWriteChipFilter
import com.typefree.ime.network.AiPipelineEngine
import com.typefree.ime.network.PromptBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class V350PureHelpWriteAndAskAiTest {

    private fun resolveProjectRoot(): File {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        return if (File(cwd, "app/src/main").exists()) {
            cwd
        } else {
            cwd.parentFile ?: cwd
        }
    }

    private class FakeImeHost(
        override val aiEngine: AiPipelineEngine = AiPipelineEngine(),
        initialState: ImeUiState = ImeUiState(),
        var simulatedEditorText: String = ""
    ) : ImeHostCallbacks {
        override val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        override val repository: AppRepository = AppRepository(
            appContext = null,
            publicStore = InMemoryKeyValueStore(),
            secretVault = SecretVault(
                store = InMemoryKeyValueStore(),
                cipher = JvmAesGcmCipher()
            )
        )
        override val stateFlow: MutableStateFlow<ImeUiState> = MutableStateFlow(initialState)
        val toasts = mutableListOf<String>()
        val committedTexts = mutableListOf<String>()
        val undoSnapshots = mutableListOf<UndoSnapshot>()
        var clipboardCopiedText: String? = null

        override fun currentInputConnection(): InputConnection? = null
        override fun performKeyFeedback(strong: Boolean) {}
        override fun performHaptic(strong: Boolean) {}
        override fun showQuickActionToast(message: String) {
            toasts.add(message)
        }
        override fun commitDirectText(
            text: String,
            recordUndo: Boolean,
            markManualEdit: Boolean,
            playFeedback: Boolean
        ) {
            committedTexts.add(text)
        }
        override fun pushUndoSnapshot(snapshot: UndoSnapshot?) {
            if (snapshot != null) undoSnapshots.add(snapshot)
        }
        override fun peekTopUndoSnapshot(): UndoSnapshot? = undoSnapshots.lastOrNull()
        override fun recordManualEditTouch() {}
        override fun tryAutoLearnFromRecentEdit() {}
        override fun onDictationCommittedForAutoLearn(fullBufferAfterCommit: String) {}
        override fun clearAutoLearnBaseline() {}
        override fun readSelectedTextSafely(): String = ""
        override fun readSurroundingBeforeCursor(maxChars: Int): String = ""
        override fun readFullEditorTextSafely(): String = simulatedEditorText
        override fun readSystemClipboardText(): String = clipboardCopiedText.orEmpty()
        override fun syncSystemClipboardIfNeeded(allowFreshBanner: Boolean) {}
        override fun copyTextToClipboard(text: String) {
            clipboardCopiedText = text
        }
        override fun archiveWavForHistory(wavFile: File, retention: HistoryRetention): File = wavFile
        override fun getLastRecordingWavFile(): File = File("unused.wav")
        override fun hasRetryWavFile(): Boolean = false

        fun dispose() {
            scope.cancel()
        }
    }

    // =========================================================================
    // 升级 1 验证：帮写去回复化 + 7大创作体裁 + 静音草稿扩写
    // =========================================================================

    @Test
    fun upgrade1_helpWriteChipFilter_detectsSocialPackages_andRanksCreativeGenres() {
        val socialPackages = listOf(
            "com.xingin.xhs",
            "com.ss.android.ugc.aweme",
            "com.dianping.v1",
            "com.sina.weibo",
            "tv.danmaku.bili",
            "com.zhihu.android",
            "com.smile.gifmaker",
            "com.taobao.idlefish"
        )
        for (pkg in socialPackages) {
            assertTrue("Expected social content package: $pkg", HelpWriteChipFilter.isSocialContentPackage(pkg))
        }

        // 验证 7 大创作体裁
        assertEquals(7, HelpWriteChipFilter.creativeGenres.size)
        val genreLabels = HelpWriteChipFilter.creativeGenres.map { it.label }
        assertTrue(genreLabels.contains("🌱 种草笔记"))
        assertTrue(genreLabels.contains("✨ 朋友圈文案"))
        assertTrue(genreLabels.contains("💬 高赞神评"))
        assertTrue(genreLabels.contains("📢 群通知/公告"))
        assertTrue(genreLabels.contains("🙏 请假/求助"))
        assertTrue(genreLabels.contains("📝 周报总结"))
        assertTrue(genreLabels.contains("✉️ 商务邮件"))

        // 内容社区包自动置顶 🌱 种草笔记、💬 高赞神评
        val xhsGenres = HelpWriteChipFilter.resolveCreativeGenres("com.xingin.xhs")
        assertEquals("genre_seed_note", xhsGenres[0].id)
        assertEquals("genre_top_comment", xhsGenres[1].id)

        // IM/办公包自动置顶 ✨ 朋友圈文案、📢 群通知/公告
        val wechatGenres = HelpWriteChipFilter.resolveCreativeGenres("com.tencent.mm")
        assertEquals("genre_moments", wechatGenres[0].id)
        assertEquals("genre_group_announcement", wechatGenres[1].id)

        // 5 大免开口一键成稿动作芯片
        assertEquals(5, HelpWriteChipFilter.draftTransformPresets.size)
        val draftLabels = HelpWriteChipFilter.draftTransformPresets.map { it.label }
        assertTrue(draftLabels.contains("🌱 扩成种草文 ⚡"))
        assertTrue(draftLabels.contains("✨ 润色得体"))
        assertTrue(draftLabels.contains("👔 职场专业"))
        assertTrue(draftLabels.contains("✂️ 精简短句"))
        assertTrue(draftLabels.contains("🔥 生成吸睛标题"))

        // 旧版 API 兼容性保证
        val legacyVisible = HelpWriteChipFilter.resolveVisiblePresets(false, "com.google.android.gm", false)
        assertTrue(legacyVisible.any { it.first.contains("工作简报") })
    }

    @Test
    fun upgrade1_voiceController_pureHelpWrite_stripsChatContextCapture_andSyncsEditorDraft() {
        val host = FakeImeHost(
            initialState = ImeUiState(
                targetPackage = "com.xingin.xhs",
                tabMode = KeyboardTabMode.QWERTY
            ),
            simulatedEditorText = "富士新机真香，直出色彩绝了"
        )
        val controller = VoiceController(
            host = host,
            audioRecorderProvider = { throw UnsupportedOperationException() },
            onExternalCommitToEngine = {},
            onResetComposing = {}
        )

        controller.openOrRestoreVoicePenPanel()

        val state = host.stateFlow.value
        assertEquals(KeyboardTabMode.VOICE, state.tabMode)
        assertEquals(VoiceSubPanel.HELP_ME_WRITE, state.voiceSubPanel)
        assertEquals("富士新机真香，直出色彩绝了", state.currentEditorText)

        host.dispose()
    }

    @Test
    fun upgrade1_promptBuilder_buildHelpMeWritePrompts_injectsEditorDraftXml() {
        val (sysPrompt, userPrompt) = PromptBuilder.buildHelpMeWritePrompts(
            voiceInstruction = "扩写详细点，加几个标签",
            style = DefaultPresets.defaultStyles().first(),
            targetAppName = "小红书",
            dictionary = emptyList(),
            surroundingBeforeCursor = "",
            editorDraft = "草莓熊联名保温杯颜值天花板"
        )
        assertTrue(PromptBuilder.HELP_ME_WRITE_SYSTEM_PROMPT.contains("<editor_draft>"))
        assertEquals(PromptBuilder.HELP_ME_WRITE_SYSTEM_PROMPT, sysPrompt)
        assertTrue(userPrompt.contains("<editor_draft>"))
        assertTrue(userPrompt.contains("草莓熊联名保温杯颜值天花板"))
        assertTrue(userPrompt.contains("扩写详细点，加几个标签"))
    }

    // =========================================================================
    // 升级 2 验证：顶栏 [🔍 问AI] 胶囊 + AskAiCardPanel + 移除 == 回车拦截
    // =========================================================================

    @Test
    fun upgrade2_shouldShowTopBarAskAiPill_strictAntiDisturbanceRules() {
        val enabledSettings = AppSettings(equalEqualAskAiEnabled = true)
        val disabledSettings = AppSettings(equalEqualAskAiEnabled = false)

        // 1. 开关关闭时绝不显示
        assertFalse(
            TopBarSlotPolicy.shouldShowTopBarAskAiPill(
                settings = disabledSettings,
                activeTab = KeyboardTabMode.QWERTY,
                isComposing = false,
                isToolPanelOpen = false,
                isClipboardOpen = false,
                currentInputType = InputType.TYPE_CLASS_TEXT,
                currentEditorText = "怎么查社保"
            )
        )

        // 2. 字数小于 2 时不显示
        assertFalse(
            TopBarSlotPolicy.shouldShowTopBarAskAiPill(
                settings = enabledSettings,
                activeTab = KeyboardTabMode.QWERTY,
                isComposing = false,
                isToolPanelOpen = false,
                isClipboardOpen = false,
                currentInputType = InputType.TYPE_CLASS_TEXT,
                currentEditorText = "你"
            )
        )

        // 3. 字数 >= 2 且普通文本框时显示
        assertTrue(
            TopBarSlotPolicy.shouldShowTopBarAskAiPill(
                settings = enabledSettings,
                activeTab = KeyboardTabMode.QWERTY,
                isComposing = false,
                isToolPanelOpen = false,
                isClipboardOpen = false,
                currentInputType = InputType.TYPE_CLASS_TEXT,
                currentEditorText = "怎么查社保"
            )
        )

        // 4. 密码框防误弹
        assertFalse(
            TopBarSlotPolicy.shouldShowTopBarAskAiPill(
                settings = enabledSettings,
                activeTab = KeyboardTabMode.QWERTY,
                isComposing = false,
                isToolPanelOpen = false,
                isClipboardOpen = false,
                currentInputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
                currentEditorText = "myPassword123"
            )
        )

        // 5. 纯数字/电话框防误弹
        assertFalse(
            TopBarSlotPolicy.shouldShowTopBarAskAiPill(
                settings = enabledSettings,
                activeTab = KeyboardTabMode.QWERTY,
                isComposing = false,
                isToolPanelOpen = false,
                isClipboardOpen = false,
                currentInputType = InputType.TYPE_CLASS_PHONE,
                currentEditorText = "13800138000"
            )
        )
        assertFalse(
            TopBarSlotPolicy.shouldShowTopBarAskAiPill(
                settings = enabledSettings,
                activeTab = KeyboardTabMode.QWERTY,
                isComposing = false,
                isToolPanelOpen = false,
                isClipboardOpen = false,
                currentInputType = InputType.TYPE_CLASS_NUMBER,
                currentEditorText = "123456"
            )
        )

        // 6. 处于输入法打字拼写 composing / 工具面板展开 / 剪贴板展开时防遮挡
        assertFalse(
            TopBarSlotPolicy.shouldShowTopBarAskAiPill(
                settings = enabledSettings,
                activeTab = KeyboardTabMode.QWERTY,
                isComposing = true,
                isToolPanelOpen = false,
                isClipboardOpen = false,
                currentInputType = InputType.TYPE_CLASS_TEXT,
                currentEditorText = "打字中"
            )
        )
        assertFalse(
            TopBarSlotPolicy.shouldShowTopBarAskAiPill(
                settings = enabledSettings,
                activeTab = KeyboardTabMode.QWERTY,
                isComposing = false,
                isToolPanelOpen = true,
                isClipboardOpen = false,
                currentInputType = InputType.TYPE_CLASS_TEXT,
                currentEditorText = "打字中"
            )
        )

        // 7. 搜索框 / 前往框（如桌面搜索“硬盘”、浏览器地址栏）防误弹
        assertFalse(
            TopBarSlotPolicy.shouldShowTopBarAskAiPill(
                settings = enabledSettings,
                activeTab = KeyboardTabMode.QWERTY,
                isComposing = false,
                isToolPanelOpen = false,
                isClipboardOpen = false,
                currentInputType = InputType.TYPE_CLASS_TEXT,
                currentEditorText = "硬盘",
                currentImeOptions = EditorInfo.IME_ACTION_SEARCH
            )
        )
        assertFalse(
            TopBarSlotPolicy.shouldShowTopBarAskAiPill(
                settings = enabledSettings,
                activeTab = KeyboardTabMode.QWERTY,
                isComposing = false,
                isToolPanelOpen = false,
                isClipboardOpen = false,
                currentInputType = InputType.TYPE_CLASS_TEXT,
                currentEditorText = "example.com",
                currentImeOptions = EditorInfo.IME_ACTION_GO
            )
        )
    }

    @Test
    fun topBarLayoutPlan_transientBannerTakesOverAndNarrowScreenGuardsOverflow() {
        // 1. 常规 360dp 手机宽度 + 新剪贴板粘贴条：临时接管中段，收起右侧非核心 AI 胶囊和冗余剪贴板图标
        val clipPlan = TopBarSlotPolicy.resolveQwertyBarLayout(
            availableWidthDp = 352f,
            showUndoButton = true,
            showClipboardButton = true,
            isIncognito = false,
            hasPriorityBanner = true,
            isClipboardBannerActive = true,
            wantAskAi = true,
            wantVoicePen = true
        )
        assertTrue(clipPlan.bannerTakesOverMiddle)
        assertTrue(clipPlan.showLeftInlineBanner)
        assertFalse(clipPlan.showClipboardIconButton)
        assertFalse(clipPlan.showRightAskAi)
        assertFalse(clipPlan.showRightVoicePen)

        // 2. 极端窄分屏（仅 170dp）：剩余宽度不足 72dp 时隐藏内联横幅并保留剪贴板图标绿点，杜绝压成 `[·]`
        val ultraNarrowClip = TopBarSlotPolicy.resolveQwertyBarLayout(
            availableWidthDp = 170f,
            showUndoButton = true,
            showClipboardButton = true,
            isIncognito = true,
            hasPriorityBanner = true,
            isClipboardBannerActive = true,
            wantAskAi = false,
            wantVoicePen = true
        )
        assertFalse(ultraNarrowClip.showLeftInlineBanner)
        assertTrue(ultraNarrowClip.showClipboardIconButton)

        // 3. 常态 352dp（仅帮写 + 语音）：完整展示右侧胶囊 + 左侧全图标
        val steadyPlan = TopBarSlotPolicy.resolveQwertyBarLayout(
            availableWidthDp = 352f,
            showUndoButton = true,
            showClipboardButton = true,
            isIncognito = false,
            hasPriorityBanner = false,
            isClipboardBannerActive = false,
            wantAskAi = false,
            wantVoicePen = true
        )
        assertFalse(steadyPlan.bannerTakesOverMiddle)
        assertTrue(steadyPlan.showClipboardIconButton)
        assertTrue(steadyPlan.showRightVoicePen)
        assertFalse(steadyPlan.compactVoicePenIconOnly)

        // 4. 常态窄屏叠加无痕 + Ask AI + 帮写 + 语音时，采用自适应纯图标折叠（不隐藏入口，AskAI 自动折叠为 28dp 圆形图标）
        val crowdedSteady = TopBarSlotPolicy.resolveQwertyBarLayout(
            availableWidthDp = 300f,
            showUndoButton = true,
            showClipboardButton = true,
            isIncognito = true,
            hasPriorityBanner = false,
            isClipboardBannerActive = false,
            wantAskAi = true,
            wantVoicePen = true
        )
        assertTrue(crowdedSteady.showRightAskAi)
        assertTrue(crowdedSteady.compactAskAiIconOnly)
        assertTrue(crowdedSteady.showRightVoicePen)
        assertFalse(crowdedSteady.compactVoicePenIconOnly)
    }

    @Test
    fun upgrade2_askAiCardActions_clearEditorAndCopyAndCommit() {
        val host = FakeImeHost(
            initialState = ImeUiState(
                tabMode = KeyboardTabMode.VOICE,
                voiceSubPanel = VoiceSubPanel.ASK_AI_CARD,
                askAiQuery = "什么是微服务架构？",
                askAiAnswer = "微服务是一种将单体应用拆分为多个独立服务、通过轻量通信协同的架构风格。"
            )
        ).apply {
            repository.updateSettings { it.copy(appLanguage = "zh-CN") }
        }
        val controller = VoiceController(
            host = host,
            audioRecorderProvider = { throw UnsupportedOperationException() },
            onExternalCommitToEngine = {},
            onResetComposing = {}
        )

        // 1. 复制答案
        controller.copyAskAiAnswer()
        assertEquals("微服务是一种将单体应用拆分为多个独立服务、通过轻量通信协同的架构风格。", host.clipboardCopiedText)
        assertTrue(host.toasts.any { it.contains("已复制") })

        // 2. 知道了（清空输入框），压入撤回快照并切回 QWERTY
        controller.dismissAskAiAndClearEditor()
        assertEquals(KeyboardTabMode.QWERTY, host.stateFlow.value.tabMode)
        assertEquals(VoiceSubPanel.NONE, host.stateFlow.value.voiceSubPanel)
        assertEquals("", host.stateFlow.value.currentEditorText)
        assertEquals(1, host.undoSnapshots.size)
        assertEquals("什么是微服务架构？", host.undoSnapshots[0].replacedOriginalText)

        // 3. 填入输入框
        host.stateFlow.value = host.stateFlow.value.copy(
            tabMode = KeyboardTabMode.VOICE,
            voiceSubPanel = VoiceSubPanel.ASK_AI_CARD,
            askAiQuery = "什么是微服务？",
            askAiAnswer = "服务拆分与轻量通信架构"
        )
        controller.commitAskAiAnswerToEditor()
        assertEquals(KeyboardTabMode.QWERTY, host.stateFlow.value.tabMode)
        assertEquals("服务拆分与轻量通信架构", host.stateFlow.value.currentEditorText)

        host.dispose()
    }

    @Test
    fun upgrade2_promptBuilder_askAiCardSystemPrompt_is100PercentStatic() {
        val (sysPrompt, userPrompt) = PromptBuilder.buildAskAiCardPrompts(
            query = "中国空间站最新进展",
            targetAppName = "浏览器"
        )
        assertEquals(PromptBuilder.ASK_AI_CARD_SYSTEM_PROMPT, sysPrompt)
        assertTrue(sysPrompt.contains("180 字以内"))
        assertTrue(sysPrompt.contains("严禁输出 `**`、`###`"))
        assertTrue(userPrompt.contains("<user_query>"))
        assertTrue(userPrompt.contains("中国空间站最新进展"))
    }

    // =========================================================================
    // 架构质量红线守护：KeyboardScreen.kt <= 650 行
    // =========================================================================

    @Test
    fun architectureRedline_keyboardScreenLineCountStrictlyBounded() {
        val root = resolveProjectRoot()
        val screenFile = File(root, "app/src/main/java/com/typefree/ime/ime/KeyboardScreen.kt")
        assertTrue("KeyboardScreen.kt must exist", screenFile.exists())
        val lines = screenFile.readLines().size
        assertTrue(
            "KeyboardScreen.kt must be <= 650 lines (Architecture Contract), actual: $lines",
            lines <= 650
        )

        // 验证新增面板存在且独立
        val askAiPanel = File(root, "app/src/main/java/com/typefree/ime/ime/panels/AskAiCardPanel.kt")
        assertTrue("AskAiCardPanel.kt must exist", askAiPanel.exists() && askAiPanel.length() > 0)
    }
}
