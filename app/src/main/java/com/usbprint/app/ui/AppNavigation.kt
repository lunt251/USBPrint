package com.usbprint.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.usbprint.app.R
import com.usbprint.app.USBPrintApp
import com.usbprint.app.print.PrintUiState
import com.usbprint.app.settings.AppSettings
import com.usbprint.app.ui.common.PlaceholderScreen
import com.usbprint.app.ui.home.HomeScreen
import com.usbprint.app.ui.print.PrintCard
import com.usbprint.app.ui.settings.SettingsScreen
import com.usbprint.core.print.DriverMatcher
import kotlinx.coroutines.launch

object Routes {
    const val HOME = "home"
    const val DOCUMENTS = "documents"
    const val PHOTOS = "photos"
    const val WEB = "web"
    const val SETTINGS = "settings"
}

@Composable
fun AppNavigation(app: USBPrintApp, settings: AppSettings) {
    val nav = rememberNavController()
    val scope = rememberCoroutineScope()
    val printerState by app.printers.state.collectAsStateWithLifecycle()
    val printUi by app.printing.ui.collectAsStateWithLifecycle()

    Box(Modifier.fillMaxSize()) {
        NavHost(navController = nav, startDestination = Routes.HOME) {
            composable(Routes.HOME) {
                HomeScreen(
                    printerState = printerState,
                    onAllowAccess = app.printers::requestPermission,
                    onTestPage = app.printing::printTestPage,
                    driverNameFor = { p -> DriverMatcher.match(p)?.let { m -> m.driver.displayName + (m.note?.let { " · $it" } ?: "") } },
                    onOpen = { route -> nav.navigate(route) { launchSingleTop = true } },
                )
            }
            composable(Routes.DOCUMENTS) { PlaceholderScreen(R.string.tile_documents) { nav.popBackStack() } }
            composable(Routes.PHOTOS) { PlaceholderScreen(R.string.tile_photos) { nav.popBackStack() } }
            composable(Routes.WEB) { PlaceholderScreen(R.string.tile_web) { nav.popBackStack() } }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    settings = settings,
                    onBack = { nav.popBackStack() },
                    onThemeMode = { scope.launch { app.settings.setThemeMode(it) } },
                    onDynamicColor = { scope.launch { app.settings.setDynamicColor(it) } },
                    onAutoOpen = { scope.launch { app.settings.setAutoOpen(it) } },
                    onRememberEdits = { scope.launch { app.settings.setRememberEdits(it) } },
                )
            }
        }

        // Keep showing the last state while the card animates out.
        val lastUi = remember { LastValue<PrintUiState>() }
        printUi?.let { lastUi.value = it }
        AnimatedVisibility(
            visible = printUi != null,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            lastUi.value?.let { ui ->
                PrintCard(
                    ui = ui,
                    onCancel = app.printing::cancel,
                    onPrintAgain = app.printing::printAgain,
                    onClose = app.printing::dismiss,
                )
            }
        }
    }
}

private class LastValue<T> {
    var value: T? = null
}
