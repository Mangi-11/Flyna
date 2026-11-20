package io.github.mangi.flyna

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mangi.flyna.ui.FlynaNavHost
import io.github.mangi.flyna.ui.theme.FlynaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        window.isNavigationBarContrastEnforced = false

        val repository =
            (application as FlynaApplication).frameworkConnectionRepository
        val appRepository =
            (application as FlynaApplication).launcherAppRepository
        val sidebarRepository = (application as FlynaApplication).sidebarEntryRepository
        setContent {
            val state = repository.state.collectAsStateWithLifecycle().value
            val sidebarCatalog = sidebarRepository.state.collectAsStateWithLifecycle().value
            val apps = appRepository.apps.collectAsStateWithLifecycle().value
            FlynaTheme {
                FlynaNavHost(
                    state = state,
                    apps = apps,
                    sidebarCatalog = sidebarCatalog,
                    onRefreshCatalog = sidebarRepository::refresh,
                    onCancelCatalog = sidebarRepository::cancel,
                    onModuleEnabledChange = repository::setModuleEnabled,
                    onCornerSidesChange = repository::setCornerSides,
                    onCornerArcLengthChange = repository::setCornerArcLengthDp,
                    onCornerArcWidthChange = repository::setCornerArcWidthDp,
                    onOutsideTapCloseModeChange = repository::setOutsideTapCloseMode,
                    onHandleSwipeUpToMiniEnabledChange =
                        repository::setHandleSwipeUpToMiniEnabled,
                    onPauseInLandscapeChange = repository::setPauseInLandscape,
                    onPauseInGameModeChange = repository::setPauseInGameMode,
                    onRequestScopes = repository::requestMissingScopes,
                    onPinnedTargetsChange = repository::setPinnedTargets,
                )
            }
        }
    }
}
