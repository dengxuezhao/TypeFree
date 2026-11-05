package com.typefree.ime

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.typefree.ime.bubble.TypeFreeBubbleController
import com.typefree.ime.data.AppRepository
import com.typefree.ime.network.AiPipelineEngine
import com.typefree.ime.ui.AppDestinations
import com.typefree.ime.ui.components.TypeFreeIcons
import com.typefree.ime.ui.screens.AccountSettingsScreen
import com.typefree.ime.ui.screens.DictionaryScreen
import com.typefree.ime.ui.screens.HistoryScreen
import com.typefree.ime.ui.screens.HomeSetupScreen
import com.typefree.ime.ui.theme.TypeFreeTheme
import com.typefree.ime.ui.theme.resolveDarkTheme

private data class NavTabItem(
    val index: Int,
    val label: String,
    val icon: ImageVector
)

class MainActivity : ComponentActivity() {

    private val selectedTabState = mutableIntStateOf(AppDestinations.TAB_HOME)
    private val settingsSubTabState = mutableIntStateOf(AppDestinations.SUBTAB_API)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        applyNavigationIntent(intent = intent, savedInstanceState = savedInstanceState)

        val app = application as TypeFreeApp
        val repository = app.repository
        val aiEngine = app.aiEngine

        setContent {
            val settings by repository.settings.collectAsState()
            val isDark = resolveDarkTheme(settings.themePreference)
            val view = LocalView.current

            if (!view.isInEditMode) {
                SideEffect {
                    WindowCompat.getInsetsController(window, view).apply {
                        isAppearanceLightStatusBars = !isDark
                        isAppearanceLightNavigationBars = !isDark
                    }
                }
            }

            TypeFreeTheme(themePreference = settings.themePreference) {
                val currentTab by selectedTabState
                val currentSettingsSubTab by settingsSubTabState

                TypeFreeMainScaffold(
                    repository = repository,
                    aiEngine = aiEngine,
                    selectedTab = currentTab,
                    settingsSubTab = currentSettingsSubTab,
                    onSelectTab = { newTab ->
                        selectedTabState.intValue = newTab.coerceIn(
                            AppDestinations.TAB_HOME,
                            AppDestinations.TAB_SETTINGS
                        )
                    },
                    onNavigateToSettingsSubTab = { subTab ->
                        settingsSubTabState.intValue = subTab.coerceIn(
                            AppDestinations.SUBTAB_API,
                            AppDestinations.SUBTAB_PREFERENCES
                        )
                        selectedTabState.intValue = AppDestinations.TAB_SETTINGS
                    },
                    onSettingsSubTabChanged = { subTab ->
                        settingsSubTabState.intValue = subTab.coerceIn(
                            AppDestinations.SUBTAB_API,
                            AppDestinations.SUBTAB_PREFERENCES
                        )
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        TypeFreeBubbleController.syncBubbleService(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyNavigationIntent(intent = intent, savedInstanceState = null)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_SELECTED_TAB, selectedTabState.intValue)
        outState.putInt(KEY_SETTINGS_SUBTAB, settingsSubTabState.intValue)
    }

    private fun applyNavigationIntent(intent: Intent?, savedInstanceState: Bundle?) {
        val hasSubTabExtra = intent?.hasExtra(AppDestinations.EXTRA_SETTINGS_SUBTAB) == true
        val hasTabExtra = intent?.hasExtra(AppDestinations.EXTRA_OPEN_TAB) == true

        if (hasSubTabExtra) {
            val subTab = intent?.getIntExtra(
                AppDestinations.EXTRA_SETTINGS_SUBTAB,
                AppDestinations.SUBTAB_API
            ) ?: AppDestinations.SUBTAB_API
            intent?.removeExtra(AppDestinations.EXTRA_SETTINGS_SUBTAB)
            settingsSubTabState.intValue = subTab.coerceIn(
                AppDestinations.SUBTAB_API,
                AppDestinations.SUBTAB_PREFERENCES
            )
        } else if (savedInstanceState != null) {
            settingsSubTabState.intValue = savedInstanceState.getInt(
                KEY_SETTINGS_SUBTAB,
                AppDestinations.SUBTAB_API
            ).coerceIn(
                AppDestinations.SUBTAB_API,
                AppDestinations.SUBTAB_PREFERENCES
            )
        }

        val resolvedTab = when {
            hasTabExtra -> {
                val tab = intent?.getIntExtra(
                    AppDestinations.EXTRA_OPEN_TAB,
                    AppDestinations.TAB_HOME
                ) ?: AppDestinations.TAB_HOME
                intent?.removeExtra(AppDestinations.EXTRA_OPEN_TAB)
                tab
            }
            hasSubTabExtra -> AppDestinations.TAB_SETTINGS
            savedInstanceState != null -> {
                savedInstanceState.getInt(KEY_SELECTED_TAB, AppDestinations.TAB_HOME)
            }
            else -> selectedTabState.intValue
        }

        selectedTabState.intValue = resolvedTab.coerceIn(
            AppDestinations.TAB_HOME,
            AppDestinations.TAB_SETTINGS
        )
    }

    companion object {
        private const val KEY_SELECTED_TAB = "state_selected_tab"
        private const val KEY_SETTINGS_SUBTAB = "state_settings_subtab"
    }
}

@Composable
private fun TypeFreeMainScaffold(
    repository: AppRepository,
    aiEngine: AiPipelineEngine,
    selectedTab: Int,
    settingsSubTab: Int,
    onSelectTab: (Int) -> Unit,
    onNavigateToSettingsSubTab: (Int) -> Unit,
    onSettingsSubTabChanged: (Int) -> Unit
) {
    val navItems = listOf(
        NavTabItem(
            index = AppDestinations.TAB_HOME,
            label = "首页",
            icon = Icons.Default.Home
        ),
        NavTabItem(
            index = AppDestinations.TAB_HISTORY,
            label = "历史",
            icon = TypeFreeIcons.History
        ),
        NavTabItem(
            index = AppDestinations.TAB_DICTIONARY,
            label = "词典",
            icon = TypeFreeIcons.Book
        ),
        NavTabItem(
            index = AppDestinations.TAB_SETTINGS,
            label = "设置 / API",
            icon = TypeFreeIcons.Tune
        )
    )

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            Column {
                HorizontalDivider(
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)
                )
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 0.dp
                ) {
                    navItems.forEach { item ->
                        val selected = selectedTab == item.index
                        NavigationBarItem(
                            selected = selected,
                            onClick = { onSelectTab(item.index) },
                            icon = {
                                Icon(
                                    imageVector = item.icon,
                                    contentDescription = item.label,
                                    modifier = Modifier.size(22.dp)
                                )
                            },
                            label = {
                                Text(
                                    text = item.label,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                                )
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f),
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (selectedTab) {
                AppDestinations.TAB_HOME -> HomeSetupScreen(
                    repository = repository,
                    aiEngine = aiEngine,
                    onNavigateToTab = onSelectTab,
                    onNavigateToSettingsSubTab = onNavigateToSettingsSubTab
                )
                AppDestinations.TAB_HISTORY -> HistoryScreen(
                    repository = repository,
                    aiEngine = aiEngine
                )
                AppDestinations.TAB_DICTIONARY -> DictionaryScreen(
                    repository = repository
                )
                AppDestinations.TAB_SETTINGS -> AccountSettingsScreen(
                    repository = repository,
                    aiEngine = aiEngine,
                    initialSubTab = settingsSubTab,
                    onSubTabChanged = onSettingsSubTabChanged
                )
                else -> HomeSetupScreen(
                    repository = repository,
                    aiEngine = aiEngine,
                    onNavigateToTab = onSelectTab,
                    onNavigateToSettingsSubTab = onNavigateToSettingsSubTab
                )
            }
        }
    }
}
