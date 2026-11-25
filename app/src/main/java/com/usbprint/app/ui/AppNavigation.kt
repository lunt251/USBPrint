package com.usbprint.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.usbprint.app.R
import com.usbprint.app.USBPrintApp
import com.usbprint.app.settings.AppSettings
import com.usbprint.app.ui.common.PlaceholderScreen
import com.usbprint.app.ui.home.HomeScreen
import com.usbprint.app.ui.settings.SettingsScreen
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

    NavHost(navController = nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                printerState = printerState,
                printerManager = app.printers,
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
}
