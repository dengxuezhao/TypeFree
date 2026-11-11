package com.typefree.ime.v310

import com.typefree.ime.ime.ImeUiAction
import com.typefree.ime.ime.KeyboardTabMode
import com.typefree.ime.ime.chrome.TopBarSlotPolicy
import com.typefree.ime.ime.panels.HelpWriteChipFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class V310UiArchitectureAndFilterTest {

    private fun resolveProjectRoot(): File {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        return if (File(cwd, "app/src/main").exists()) {
            cwd
        } else {
            cwd.parentFile ?: cwd
        }
    }

    @Test
    fun topBarSlotPolicy_neverSwallowsUndoWhenClipboardBannerPresent() {
        val bothPresent = TopBarSlotPolicy.resolveLeftSlots(
            activeTab = KeyboardTabMode.VOICE,
            isComposing = false,
            hasUndoSnapshot = true,
            hasFreshClipboardBanner = true,
            hasActiveSelection = false
        )
        assertTrue("Undo button must stay visible even when quick-paste banner is active", bothPresent.showUndoButton)
        assertTrue("Clipboard quick-paste pill should also be visible", bothPresent.showClipboardQuickPaste)
        assertFalse("Voice mode does not need duplicate VoicePen pill in top bar", bothPresent.showVoicePenPill)
    }

    @Test
    fun topBarSlotPolicy_exposesVoicePenPillInQwertyIdleAndHidesDuringComposing() {
        val qwertyIdle = TopBarSlotPolicy.resolveLeftSlots(
            activeTab = KeyboardTabMode.QWERTY,
            isComposing = false,
            hasUndoSnapshot = false,
            hasFreshClipboardBanner = false,
            hasActiveSelection = false
        )
        assertTrue("QWERTY idle state must expose compact VoicePen pill", qwertyIdle.showVoicePenPill)
        assertEquals("帮写", qwertyIdle.voicePenLabel)

        val qwertyWithSelection = TopBarSlotPolicy.resolveLeftSlots(
            activeTab = KeyboardTabMode.QWERTY,
            isComposing = false,
            hasUndoSnapshot = true,
            hasFreshClipboardBanner = true,
            hasActiveSelection = true
        )
        assertTrue(qwertyWithSelection.showUndoButton)
        assertTrue(qwertyWithSelection.showClipboardQuickPaste)
        assertTrue(qwertyWithSelection.showVoicePenPill)
        assertEquals("改写", qwertyWithSelection.voicePenLabel)

        val qwertyComposing = TopBarSlotPolicy.resolveLeftSlots(
            activeTab = KeyboardTabMode.QWERTY,
            isComposing = true,
            hasUndoSnapshot = true,
            hasFreshClipboardBanner = false,
            hasActiveSelection = false
        )
        assertFalse("Composing state should yield top bar to composing strip", qwertyComposing.showVoicePenPill)
    }

    @Test
    fun helpWriteChipFilter_hidesLongFormPresetsInImAppsOrChatContext() {
        val imPackages = listOf(
            "com.tencent.mm",
            "com.tencent.mobileqq",
            "com.ss.android.lark",
            "com.alibaba.android.rimet",
            "com.tencent.wework",
            "org.telegram.messenger",
            "com.whatsapp",
            "com.discord"
        )
        for (pkg in imPackages) {
            assertFalse(
                "Long-form office presets should be hidden in IM package: $pkg",
                HelpWriteChipFilter.shouldShowLongFormPresets(targetPackage = pkg, chatContextActive = false)
            )
        }

        assertFalse(
            "Long-form presets should be hidden when chatContextActive is true regardless of package",
            HelpWriteChipFilter.shouldShowLongFormPresets(
                targetPackage = "com.google.android.gm",
                chatContextActive = true
            )
        )

        assertTrue(
            "Long-form presets should be shown in email/document apps when chatContextActive is false",
            HelpWriteChipFilter.shouldShowLongFormPresets(
                targetPackage = "com.google.android.gm",
                chatContextActive = false
            )
        )

        val chatChips = HelpWriteChipFilter.resolveVisiblePresets(
            hasSelection = false,
            targetPackage = "com.tencent.mm",
            chatContextActive = true
        )
        assertFalse(chatChips.any { it.first.contains("工作简报") || it.first.contains("正式邮件") })

        val docChips = HelpWriteChipFilter.resolveVisiblePresets(
            hasSelection = false,
            targetPackage = "com.google.android.gm",
            chatContextActive = false
        )
        assertTrue(docChips.any { it.first.contains("工作简报") })
        assertTrue(docChips.any { it.first.contains("正式邮件") })
    }

    @Test
    fun imeUiAction_supportsUnifiedDispatchAndReservedJevCopilotAction() {
        val actions: List<ImeUiAction> = listOf(
            ImeUiAction.StartVoiceRecording,
            ImeUiAction.StopVoiceRecording,
            ImeUiAction.CancelVoiceRecording,
            ImeUiAction.TriggerUndo,
            ImeUiAction.OpenHelpMeWrite,
            ImeUiAction.ToggleWhisperMode,
            ImeUiAction.SelectTab(KeyboardTabMode.QWERTY),
            ImeUiAction.OpenJevCopilot
        )
        assertTrue(actions.contains(ImeUiAction.OpenJevCopilot))
        val selectQwerty = actions.filterIsInstance<ImeUiAction.SelectTab>().first()
        assertEquals(KeyboardTabMode.QWERTY, selectQwerty.tab)
    }

    @Test
    fun v310ArchitectureGuard_keyboardScreenLineCountAndModularFilesExist() {
        val root = resolveProjectRoot()
        val keyboardScreenFile = File(root, "app/src/main/java/com/typefree/ime/ime/KeyboardScreen.kt")
        assertTrue("KeyboardScreen.kt must exist", keyboardScreenFile.exists())
        val lineCount = keyboardScreenFile.readLines().size
        assertTrue(
            "KeyboardScreen.kt must be <= 650 lines after v3.1.0 modular split, actual: $lineCount",
            lineCount <= 650
        )

        val requiredFiles = listOf(
            "app/src/main/java/com/typefree/ime/ime/ImeUiAction.kt",
            "app/src/main/java/com/typefree/ime/ime/chrome/TypeFreeTopBar.kt",
            "app/src/main/java/com/typefree/ime/ime/chrome/SubPanelChrome.kt",
            "app/src/main/java/com/typefree/ime/ime/candidates/CandidateStrips.kt",
            "app/src/main/java/com/typefree/ime/ime/panels/VoiceMainPanel.kt",
            "app/src/main/java/com/typefree/ime/ime/panels/HelpMeWritePanel.kt",
            "app/src/main/java/com/typefree/ime/ime/panels/SpeakToEditPanel.kt",
            "app/src/main/java/com/typefree/ime/ime/panels/RealtimeTranslatePanel.kt",
            "app/src/main/java/com/typefree/ime/ui/screens/home/PlaygroundBottomSheet.kt",
            "app/src/main/java/com/typefree/ime/ui/screens/ApiConfigBackupHelper.kt"
        )
        for (relPath in requiredFiles) {
            val file = File(root, relPath)
            assertTrue("Expected modular file to exist: $relPath", file.exists() && file.length() > 0L)
        }

        val homeScreenFile = File(root, "app/src/main/java/com/typefree/ime/ui/screens/HomeSetupScreen.kt")
        val homeSource = homeScreenFile.readText()
        assertFalse(
            "HomeSetupScreen.kt must not use 1s while(isActive) polling loop",
            homeSource.contains("while (isActive)") || homeSource.contains("while(isActive)")
        )
    }
}
