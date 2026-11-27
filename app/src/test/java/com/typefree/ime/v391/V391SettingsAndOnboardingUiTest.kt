package com.typefree.ime.v391

import androidx.compose.ui.unit.dp
import com.typefree.ime.data.AppSettings
import com.typefree.ime.data.CandidateFontScale
import com.typefree.ime.data.KeyboardHeightMode
import com.typefree.ime.data.SpaceLongPressAction
import com.typefree.ime.diagnostics.ImeDiagnosticLogger
import com.typefree.ime.i18n.AppLang
import com.typefree.ime.ime.KeyboardMetrics
import com.typefree.ime.ui.screens.AboutDiagnosticsSnapshotHelper
import com.typefree.ime.ui.screens.KeyboardStudioV391Helper
import com.typefree.ime.ui.screens.OnboardingPinyinDictFlowHelper
import com.typefree.ime.ui.screens.OnboardingStage
import com.typefree.ime.ui.screens.OnboardingStep1FallbackHelper
import com.typefree.ime.ui.screens.OnboardingTypingChoice
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class V391SettingsAndOnboardingUiTest {

    private fun resolveProjectRoot(): File {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        return if (File(cwd, "app/src/main").exists()) {
            cwd
        } else {
            cwd.parentFile ?: cwd
        }
    }

    @Before
    fun setUp() {
        ImeDiagnosticLogger.clear()
    }

    @After
    fun tearDown() {
        ImeDiagnosticLogger.clear()
    }

    @Test
    fun `KeyboardStudioV391Helper decouples key height scale and 0_to_56dp bottom lift while preserving legacy lift`() {
        assertEquals(
            listOf(0, 8, 16, 24, 32, 40, 48, 56),
            KeyboardStudioV391Helper.BOTTOM_LIFT_STEP_OPTIONS_DP
        )

        val defaultSettings = AppSettings()
        assertFalse(KeyboardStudioV391Helper.isHeightOrBottomLiftCustomized(defaultSettings))
        assertEquals(252.dp, KeyboardMetrics.fromSettings(defaultSettings, isLandscape = false).frameHeight)

        // Legacy user upgrading with LIFT level 3 (24dp) and keyboardBottomLiftDp = -1:
        // Selecting Comfort (+2, 114%) MUST preserve the 24dp bottom cushion rather than zeroing it!
        val legacyLift3 = AppSettings(
            keyboardHeightMode = KeyboardHeightMode.LIFT,
            keyboardLiftLevel = 3,
            keyboardHeightLevel = 0,
            keyboardBottomLiftDp = -1
        )
        assertTrue(KeyboardStudioV391Helper.isHeightOrBottomLiftCustomized(legacyLift3))

        val comfortWithPreservedLift = KeyboardStudioV391Helper.applyKeyHeightLevel(legacyLift3, 2)
        assertEquals(2, comfortWithPreservedLift.keyboardHeightLevel)
        assertEquals(KeyboardHeightMode.STRETCH, comfortWithPreservedLift.keyboardHeightMode)
        assertEquals(24, comfortWithPreservedLift.keyboardBottomLiftDp)
        assertEquals(3, comfortWithPreservedLift.keyboardLiftLevel)
        val mComfort24 = KeyboardMetrics.fromSettings(comfortWithPreservedLift, isLandscape = false)
        assertEquals(1.14f, mComfort24.scale, 0.0001f)
        assertEquals(239.dp, mComfort24.keyAreaHeight)
        assertEquals(24.dp, mComfort24.liftHeight)
        assertEquals(305.dp, mComfort24.frameHeight) // 42 + 239 + 24

        // Raising bottom lift to 48dp keeps +2 key height scale intact and clamps legacy liftLevel to 4
        val comfortPlus48 = KeyboardStudioV391Helper.applyBottomLiftDp(comfortWithPreservedLift, 48)
        assertEquals(2, comfortPlus48.keyboardHeightLevel)
        assertEquals(48, comfortPlus48.keyboardBottomLiftDp)
        assertEquals(4, comfortPlus48.keyboardLiftLevel)
        val mComfort48 = KeyboardMetrics.fromSettings(comfortPlus48, isLandscape = false)
        assertEquals(329.dp, mComfort48.frameHeight) // 42 + 239 + 48

        // Maximum 56dp lift + Compact (-2, 88%) + Extra Large Candidate Font (52dp chrome)
        val compactMaxWithXlFont = KeyboardStudioV391Helper.applyBottomLiftDp(
            KeyboardStudioV391Helper.applyKeyHeightLevel(defaultSettings, -2),
            56
        ).copy(candidateFontScale = CandidateFontScale.EXTRA_LARGE)
        val mCompactMaxXl = KeyboardMetrics.fromSettings(compactMaxWithXlFont, isLandscape = false)
        assertEquals(52, mCompactMaxXl.fixedChromeDp)
        assertEquals(185.dp, mCompactMaxXl.keyAreaHeight)
        assertEquals(56.dp, mCompactMaxXl.liftHeight)
        assertEquals(293.dp, mCompactMaxXl.frameHeight) // 52 + 185 + 56

        // Reset restores exact 252dp standard state
        val reset = KeyboardStudioV391Helper.resetHeightAndBottomLift(comfortPlus48)
        assertFalse(KeyboardStudioV391Helper.isHeightOrBottomLiftCustomized(reset))
        assertEquals(0, reset.keyboardHeightLevel)
        assertEquals(0, reset.keyboardBottomLiftDp)
        assertEquals(0, reset.keyboardLiftLevel)
        assertEquals(KeyboardHeightMode.LIFT, reset.keyboardHeightMode)
        assertEquals(252.dp, KeyboardMetrics.fromSettings(reset, isLandscape = false).frameHeight)
    }

    @Test
    fun `KeyboardStudioV391Helper provides full trilingual labels and 5-key bottom row Shift_and_SwipeUp hint`() {
        // Key height option labels
        assertEquals("紧凑 88%", KeyboardStudioV391Helper.keyHeightOptionLabel(-2, AppLang.ZH_CN))
        assertEquals("偏低 94%", KeyboardStudioV391Helper.keyHeightOptionLabel(-1, AppLang.ZH_CN))
        assertEquals("标准 100%", KeyboardStudioV391Helper.keyHeightOptionLabel(0, AppLang.ZH_CN))
        assertEquals("偏高 107%", KeyboardStudioV391Helper.keyHeightOptionLabel(1, AppLang.ZH_CN))
        assertEquals("舒适 114%", KeyboardStudioV391Helper.keyHeightOptionLabel(2, AppLang.ZH_CN))
        assertEquals("Standard 100%", KeyboardStudioV391Helper.keyHeightOptionLabel(0, AppLang.EN))
        assertEquals("舒適 114%", KeyboardStudioV391Helper.keyHeightOptionLabel(2, AppLang.ZH_TW))

        // Candidate font scale option labels (Issue #6)
        assertEquals("标准 (15sp)", KeyboardStudioV391Helper.candidateFontScaleOptionLabel(CandidateFontScale.STANDARD, AppLang.ZH_CN))
        assertEquals("偏大 (17sp)", KeyboardStudioV391Helper.candidateFontScaleOptionLabel(CandidateFontScale.LARGE, AppLang.ZH_CN))
        assertEquals("超大 (19.5sp)", KeyboardStudioV391Helper.candidateFontScaleOptionLabel(CandidateFontScale.EXTRA_LARGE, AppLang.ZH_CN))
        assertEquals("Standard (15sp)", KeyboardStudioV391Helper.candidateFontScaleOptionLabel(CandidateFontScale.STANDARD, AppLang.EN))
        assertEquals("Large (17sp)", KeyboardStudioV391Helper.candidateFontScaleOptionLabel(CandidateFontScale.LARGE, AppLang.EN))
        assertEquals("Extra Large (19.5sp)", KeyboardStudioV391Helper.candidateFontScaleOptionLabel(CandidateFontScale.EXTRA_LARGE, AppLang.EN))
        assertEquals("標準 (15sp)", KeyboardStudioV391Helper.candidateFontScaleOptionLabel(CandidateFontScale.STANDARD, AppLang.ZH_TW))

        // Spacebar long-press option labels
        assertEquals("按住说话（松手上屏）", KeyboardStudioV391Helper.spaceLongPressOptionLabel(SpaceLongPressAction.VOICE_HOLD, AppLang.ZH_CN))
        assertEquals("Hold to Speak", KeyboardStudioV391Helper.spaceLongPressOptionLabel(SpaceLongPressAction.VOICE_HOLD, AppLang.EN))
        assertEquals("按住說話（放開上屏）", KeyboardStudioV391Helper.spaceLongPressOptionLabel(SpaceLongPressAction.VOICE_HOLD, AppLang.ZH_TW))
        assertEquals("切换中英文", KeyboardStudioV391Helper.spaceLongPressOptionLabel(SpaceLongPressAction.TOGGLE_LANGUAGE, AppLang.ZH_CN))
        assertEquals("Switch Language", KeyboardStudioV391Helper.spaceLongPressOptionLabel(SpaceLongPressAction.TOGGLE_LANGUAGE, AppLang.EN))
        assertEquals("切換中英文", KeyboardStudioV391Helper.spaceLongPressOptionLabel(SpaceLongPressAction.TOGGLE_LANGUAGE, AppLang.ZH_TW))

        // 5-key bottom row subtitle explicitly guides language switching in all 3 locales & both spacebar modes
        for (action in SpaceLongPressAction.entries) {
            val zhCn = KeyboardStudioV391Helper.fiveKeyBottomRowSubtitle(action, AppLang.ZH_CN)
            val en = KeyboardStudioV391Helper.fiveKeyBottomRowSubtitle(action, AppLang.EN)
            val zhTw = KeyboardStudioV391Helper.fiveKeyBottomRowSubtitle(action, AppLang.ZH_TW)

            assertTrue(zhCn.contains("隐藏独立「中/EN」键"))
            assertTrue(zhCn.contains("上滑空格键 ↑"))
            assertTrue(zhCn.contains("⇧ Shift"))

            assertTrue(en.contains("Hides the standalone '中/EN' key"))
            assertTrue(en.contains("swipe up ↑ on Space"))
            assertTrue(en.contains("⇧ Shift"))

            assertTrue(zhTw.contains("隱藏獨立「中/EN」鍵"))
            assertTrue(zhTw.contains("上滑空白鍵 ↑"))
            assertTrue(zhTw.contains("⇧ Shift"))
        }

        // Live height summary readout
        val sampleMetrics = KeyboardMetrics.resolve(
            level = 1,
            bottomLiftDp = 16,
            candidateFontScale = CandidateFontScale.LARGE
        )
        val summaryZh = KeyboardStudioV391Helper.formatLiveHeightSummary(sampleMetrics, AppLang.ZH_CN)
        val summaryEn = KeyboardStudioV391Helper.formatLiveHeightSummary(sampleMetrics, AppLang.EN)
        val summaryTw = KeyboardStudioV391Helper.formatLiveHeightSummary(sampleMetrics, AppLang.ZH_TW)
        assertTrue(summaryZh.contains("287dp") && summaryZh.contains("46dp") && summaryZh.contains("225dp") && summaryZh.contains("16dp"))
        assertTrue(summaryEn.contains("Total Height 287dp"))
        assertTrue(summaryTw.contains("即時總高度 287dp"))
    }

    @Test
    fun `OnboardingStep1FallbackHelper gates fallback links and avoids OriginOS 300ms bounce-back loop`() {
        // Fallback links show strictly when IME is enabled but not yet selected
        assertFalse(OnboardingStep1FallbackHelper.shouldShowStep1FallbackRow(isImeEnabled = false, isImeSelected = false))
        assertTrue(OnboardingStep1FallbackHelper.shouldShowStep1FallbackRow(isImeEnabled = true, isImeSelected = false))
        assertFalse(OnboardingStep1FallbackHelper.shouldShowStep1FallbackRow(isImeEnabled = true, isImeSelected = true))
        assertFalse(OnboardingStep1FallbackHelper.shouldShowStep1FallbackRow(isImeEnabled = false, isImeSelected = true))

        // Step 1A (IME disabled): arms 300ms foreground watcher + return auto-picker
        val enablePlan = OnboardingStep1FallbackHelper.resolveOpenImeSettingsPlan(isImeEnabled = false)
        assertTrue(enablePlan.armForegroundEnableWatcher)
        assertTrue(enablePlan.armAutoPickerOnReturn)

        // Step 1B fallback (IME already enabled on OriginOS): MUST NOT arm 300ms watcher or auto-picker loop
        val switchFallbackPlan = OnboardingStep1FallbackHelper.resolveOpenImeSettingsPlan(isImeEnabled = true)
        assertFalse(switchFallbackPlan.armForegroundEnableWatcher)
        assertFalse(switchFallbackPlan.armAutoPickerOnReturn)

        // Trilingual copy check
        assertEquals("未弹出菜单？去系统设置切换 ›", OnboardingStep1FallbackHelper.switchInSettingsFallbackLabel(AppLang.ZH_CN))
        assertEquals("Menu didn't appear? Switch in Settings ›", OnboardingStep1FallbackHelper.switchInSettingsFallbackLabel(AppLang.EN))
        assertEquals("未彈出選單？去系統設定切換 ›", OnboardingStep1FallbackHelper.switchInSettingsFallbackLabel(AppLang.ZH_TW))

        assertEquals("稍后切换，先进入应用 ›", OnboardingStep1FallbackHelper.skipForNowFallbackLabel(AppLang.ZH_CN))
        assertEquals("Skip for now ›", OnboardingStep1FallbackHelper.skipForNowFallbackLabel(AppLang.EN))
        assertEquals("稍後切換，先進入應用 ›", OnboardingStep1FallbackHelper.skipForNowFallbackLabel(AppLang.ZH_TW))
    }

    @Test
    fun `AboutDiagnosticsSnapshotHelper defaults collapsed and exports Zero-PII snapshot with runtime flags`() {
        assertFalse(
            "Diagnostics section in About card MUST be collapsed by default",
            AboutDiagnosticsSnapshotHelper.DEFAULT_EXPANDED
        )

        ImeDiagnosticLogger.record(
            category = "IME_WINDOW",
            event = "onWindowShown",
            detail = "pkg=com.tencent.mm liftDp=24 chat=\"机密聊天正文\""
        )
        val insetGeometry = KeyboardMetrics.resolveDecorOverlapBottomInset(
            rawDecorBottomInsetPx = 126,
            decorHeightPx = 2520,
            inputBottomInWindowPx = 2394,
            inputHeightPx = 880,
            inputLaidOut = true,
            decorBottomOnScreenPx = 2520,
            screenBottomPx = 2520
        )
        ImeDiagnosticLogger.updateRuntimeState(insetGeometry.toDiagnosticStateMap(composeSystemBarsBottomPx = 0))
        val settings = AppSettings(
            bubbleEnabled = false
        )
        val snapshot = AboutDiagnosticsSnapshotHelper.buildSnapshotText(
            settings = settings,
            imeEnabled = true,
            imeSelected = true,
            accessibilityEnabled = true,
            appVersion = "3.9.1 (77)",
            deviceSummary = "vivo X100s | Android 15 (SDK 35)"
        )

        assertTrue(snapshot.contains("=== TypeFree IME Diagnostic Snapshot (Zero-PII Memory Ring Buffer) ==="))
        assertTrue(snapshot.contains("appVersion: 3.9.1 (77)"))
        assertTrue(snapshot.contains("device: vivo X100s | Android 15 (SDK 35)"))
        assertTrue(snapshot.contains("imeEnabled=true"))
        assertTrue(snapshot.contains("imeSelected=true"))
        assertTrue(snapshot.contains("a11yEnabled=true"))
        assertTrue(snapshot.contains("bubbleEnabled=false"))
        assertTrue(snapshot.contains("imeInsetRawPx=126"))
        assertTrue(snapshot.contains("imeInsetReservedPx=126"))
        assertTrue(snapshot.contains("imeInsetEffPx=0"))
        assertTrue(snapshot.contains("imeInsetComposePx=0"))
        assertTrue(snapshot.contains("imeDecorH=2520"))
        assertTrue(snapshot.contains("imeInputBot=2394"))
        assertTrue(snapshot.contains("IME_WINDOW | onWindowShown"))
        assertFalse("Zero-PII guarantee must strip quoted user chat text", snapshot.contains("机密聊天正文"))

        // Trilingual labels
        assertEquals("高级排障与运行快照", AboutDiagnosticsSnapshotHelper.sectionHeaderLabel(AppLang.ZH_CN))
        assertEquals("Troubleshooting & Diagnostics", AboutDiagnosticsSnapshotHelper.sectionHeaderLabel(AppLang.EN))
        assertEquals("進階排障與執行快照", AboutDiagnosticsSnapshotHelper.sectionHeaderLabel(AppLang.ZH_TW))

        assertEquals("复制运行诊断快照", AboutDiagnosticsSnapshotHelper.copyActionLabel(copied = false, lang = AppLang.ZH_CN))
        assertEquals("✓ 已复制运行诊断快照", AboutDiagnosticsSnapshotHelper.copyActionLabel(copied = true, lang = AppLang.ZH_CN))
        assertEquals("Copy Diagnostic Snapshot", AboutDiagnosticsSnapshotHelper.copyActionLabel(copied = false, lang = AppLang.EN))
        assertEquals("✓ Diagnostic Snapshot Copied", AboutDiagnosticsSnapshotHelper.copyActionLabel(copied = true, lang = AppLang.EN))
        assertEquals("複製執行診斷快照", AboutDiagnosticsSnapshotHelper.copyActionLabel(copied = false, lang = AppLang.ZH_TW))

        assertTrue(AboutDiagnosticsSnapshotHelper.privacyNoticeLabel(AppLang.ZH_CN).contains("绝不记录任何打字或聊天内容"))
        assertTrue(AboutDiagnosticsSnapshotHelper.privacyNoticeLabel(AppLang.EN).contains("never records typed or chat text"))
        assertTrue(AboutDiagnosticsSnapshotHelper.privacyNoticeLabel(AppLang.ZH_TW).contains("絕不記錄任何打字或聊天內容"))
    }

    @Test
    fun `source guards verify deferred settings remain absent and v391 UI contracts stay intact`() {
        val root = resolveProjectRoot()

        val studioSrc = File(
            root,
            "app/src/main/java/com/typefree/ime/ui/screens/KeyboardStudioScreen.kt"
        ).readText()
        assertFalse("Deferred oneHandedMode must NOT appear in KeyboardStudioScreen", studioSrc.contains("oneHandedMode"))
        assertFalse("Deferred OneHandedMode must NOT appear in KeyboardStudioScreen", studioSrc.contains("OneHandedMode"))
        assertFalse("Deferred t9LeftHandedMirror must NOT appear in KeyboardStudioScreen", studioSrc.contains("t9LeftHandedMirror"))
        assertTrue(studioSrc.contains("KeyboardMetrics.fromSettings(settings, isLandscape = false)"))
        assertTrue(studioSrc.contains("settings.candidateFontScale"))
        assertTrue(studioSrc.contains("settings.spaceLongPressAction"))
        assertEquals(
            "Both Xiaohe and Custom Codetable 5-key bottom row toggles must use fiveKeyBottomRowSubtitle",
            2,
            Regex("KeyboardStudioV391Helper\\.fiveKeyBottomRowSubtitle").findAll(studioSrc).count()
        )

        val onboardingSrc = File(
            root,
            "app/src/main/java/com/typefree/ime/ui/screens/OnboardingFlowScreen.kt"
        ).readText()
        assertTrue(onboardingSrc.contains("object ImePickerFocusCoordinator"))
        assertTrue(onboardingSrc.contains("configureHiddenImePickerEditText"))
        assertTrue(onboardingSrc.contains("OnboardingStep1FallbackHelper.shouldShowStep1FallbackRow"))
        assertTrue(onboardingSrc.contains("OnboardingStep1FallbackHelper.switchInSettingsFallbackLabel"))
        assertTrue(onboardingSrc.contains("OnboardingStep1FallbackHelper.skipForNowFallbackLabel"))

        val generalSrc = File(
            root,
            "app/src/main/java/com/typefree/ime/ui/screens/GeneralSettingsScreen.kt"
        ).readText()
        assertTrue(generalSrc.contains("mutableStateOf(AboutDiagnosticsSnapshotHelper.DEFAULT_EXPANDED)"))
        assertTrue(generalSrc.contains("AnimatedVisibility(visible = showDiagnosticsSection)"))
        assertTrue(generalSrc.contains("AboutDiagnosticsSnapshotHelper.buildSnapshotText"))
    }

    @Test
    fun `onboarding pinyin dictionary step inserts 5th step only for pinyin choices and supports multi-select`() {
        assertTrue(OnboardingPinyinDictFlowHelper.isPinyinChoice(OnboardingTypingChoice.PINYIN_26))
        assertTrue(OnboardingPinyinDictFlowHelper.isPinyinChoice(OnboardingTypingChoice.PINYIN_T9))
        assertTrue(OnboardingPinyinDictFlowHelper.isPinyinChoice(OnboardingTypingChoice.DOUBLE_FLYPY))
        assertFalse(OnboardingPinyinDictFlowHelper.isPinyinChoice(OnboardingTypingChoice.ENGLISH_QWERTY))
        assertFalse(OnboardingPinyinDictFlowHelper.isPinyinChoice(OnboardingTypingChoice.CODETABLE))

        assertEquals(5, OnboardingPinyinDictFlowHelper.totalSteps(OnboardingTypingChoice.PINYIN_26))
        assertEquals(4, OnboardingPinyinDictFlowHelper.totalSteps(OnboardingTypingChoice.CODETABLE))

        assertEquals(
            OnboardingStage.PINYIN_DICT_PACKS,
            OnboardingPinyinDictFlowHelper.resolveStage(2, OnboardingTypingChoice.PINYIN_T9)
        )
        assertEquals(
            OnboardingStage.VOICE_AI_ROUTE,
            OnboardingPinyinDictFlowHelper.resolveStage(3, OnboardingTypingChoice.PINYIN_T9)
        )
        assertEquals(
            OnboardingStage.VOICE_AI_ROUTE,
            OnboardingPinyinDictFlowHelper.resolveStage(2, OnboardingTypingChoice.CODETABLE)
        )

        // Default selection: full rime-ice (15.7) + compact bgc grammar (10.0) = 25.7 MB
        val defaults = OnboardingPinyinDictFlowHelper.defaultSelectedIds()
        assertEquals(
            setOf(OnboardingPinyinDictFlowHelper.PACK_RIME_ICE_FULL, OnboardingPinyinDictFlowHelper.PACK_GRAM_BGC),
            defaults
        )
        assertEquals(25.7, OnboardingPinyinDictFlowHelper.totalSelectedMb(defaults), 0.01)

        // Multi-select dictionary add-on + mutually exclusive grammar switch
        val withTencent = OnboardingPinyinDictFlowHelper.togglePack(
            defaults,
            OnboardingPinyinDictFlowHelper.PACK_TENCENT_VEC
        )
        assertTrue(OnboardingPinyinDictFlowHelper.PACK_RIME_ICE_FULL in withTencent)
        assertTrue(OnboardingPinyinDictFlowHelper.PACK_TENCENT_VEC in withTencent)

        val switchedGram = OnboardingPinyinDictFlowHelper.togglePack(
            withTencent,
            OnboardingPinyinDictFlowHelper.PACK_GRAM_BGW
        )
        assertFalse(OnboardingPinyinDictFlowHelper.PACK_GRAM_BGC in switchedGram)
        assertTrue(OnboardingPinyinDictFlowHelper.PACK_GRAM_BGW in switchedGram)
        assertTrue(OnboardingPinyinDictFlowHelper.PACK_RIME_ICE_FULL in switchedGram)
        assertTrue(OnboardingPinyinDictFlowHelper.PACK_TENCENT_VEC in switchedGram)

        val plan = OnboardingPinyinDictFlowHelper.buildEnqueuePlan(switchedGram)
        assertTrue(plan.downloadFullRimeIce)
        assertTrue(plan.downloadTencentVec)
        assertEquals(OnboardingPinyinDictFlowHelper.PACK_GRAM_BGW, plan.grammarModelId)
    }
}
