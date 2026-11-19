package com.typefree.ime.ui

object AppDestinations {
    const val EXTRA_OPEN_TAB = "extra_open_tab"
    const val EXTRA_SETTINGS_SUBTAB = "extra_settings_subtab"

    // 方案 A: 底部 4 栏导航（首页 / 键盘 / 语音与 AI / 通用）
    const val TAB_HOME = 0
    const val TAB_KEYBOARD = 1
    const val TAB_VOICE_AI = 2
    const val TAB_GENERAL = 3

    // 「键盘」中心顶部 3 段子 Tab
    const val SUBTAB_KB_SCHEME = 0
    const val SUBTAB_KB_GESTURE = 1
    const val SUBTAB_KB_DICT = 2

    // 「语音与 AI」中心顶部 3 段子 Tab
    const val SUBTAB_VOICE_MAIN = 0
    const val SUBTAB_VOICE_AI_STYLE = 1
    const val SUBTAB_VOICE_API = 2

    // 向后兼容旧常量别名（供 ime/** 工具栏深链与旧测试无缝路由）
    const val TAB_HISTORY = TAB_HOME
    const val TAB_DICTIONARY = TAB_KEYBOARD
    const val TAB_SETTINGS = TAB_GENERAL
    const val TAB_API = TAB_VOICE_AI
    const val TAB_STYLES = TAB_VOICE_AI

    const val SUBTAB_API = SUBTAB_VOICE_API
    const val SUBTAB_STYLES = SUBTAB_VOICE_AI_STYLE
    const val SUBTAB_PREFERENCES = 3

    data class NavigationRoute(
        val tab: Int,
        val keyboardSubTab: Int,
        val voiceAiSubTab: Int
    )

    fun resolveNavigationRoute(
        openTabExtra: Int?,
        settingsSubTabExtra: Int?,
        currentTab: Int = TAB_HOME,
        currentKeyboardSubTab: Int = SUBTAB_KB_SCHEME,
        currentVoiceAiSubTab: Int = SUBTAB_VOICE_MAIN
    ): NavigationRoute {
        var nextKbSub = currentKeyboardSubTab.coerceIn(SUBTAB_KB_SCHEME, SUBTAB_KB_DICT)
        var nextVoiceSub = currentVoiceAiSubTab.coerceIn(SUBTAB_VOICE_MAIN, SUBTAB_VOICE_API)

        val resolvedTab = when {
            settingsSubTabExtra != null -> {
                if (settingsSubTabExtra == SUBTAB_PREFERENCES) {
                    (openTabExtra ?: TAB_GENERAL).coerceIn(TAB_HOME, TAB_GENERAL)
                } else {
                    nextVoiceSub = settingsSubTabExtra.coerceIn(SUBTAB_VOICE_MAIN, SUBTAB_VOICE_API)
                    (openTabExtra ?: TAB_VOICE_AI).coerceIn(TAB_HOME, TAB_GENERAL)
                }
            }
            openTabExtra != null -> {
                val target = openTabExtra.coerceIn(TAB_HOME, TAB_GENERAL)
                if (target == TAB_KEYBOARD) {
                    nextKbSub = SUBTAB_KB_DICT
                } else if (target == TAB_VOICE_AI) {
                    nextVoiceSub = SUBTAB_VOICE_API
                }
                target
            }
            else -> currentTab.coerceIn(TAB_HOME, TAB_GENERAL)
        }

        return NavigationRoute(
            tab = resolvedTab,
            keyboardSubTab = nextKbSub,
            voiceAiSubTab = nextVoiceSub
        )
    }
}
