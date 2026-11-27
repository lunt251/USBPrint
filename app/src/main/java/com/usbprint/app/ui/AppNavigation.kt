package com.usbprint.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.usbprint.app.USBPrintApp
import com.usbprint.app.edit.DocEdits
import com.usbprint.app.print.PrintUiState
import com.usbprint.app.settings.AppSettings
import com.usbprint.app.ui.files.FilesScreen
import com.usbprint.app.ui.history.HistoryScreen
import com.usbprint.app.ui.home.HomeScreen
import com.usbprint.app.ui.preview.PreviewScreen
import com.usbprint.app.ui.print.PrintCard
import com.usbprint.app.ui.settings.SettingsScreen
import com.usbprint.app.ui.web.WebScreen
import com.usbprint.core.print.DriverRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.usbprint.app.session.LoadState
import org.json.JSONObject
import java.io.File

object Routes {
    const val HOME = "home"
    const val DOCUMENTS = "documents"
    const val PHOTOS = "photos"
    const val WEB = "web"
    const val SETTINGS = "settings"
    const val PREVIEW = "preview"
    const val HISTORY = "history"
}

@Composable
fun AppNavigation(app: USBPrintApp, settings: AppSettings) {
    val nav = rememberNavController()
    val scope = rememberCoroutineScope()
    val printerState by app.printers.state.collectAsStateWithLifecycle()
    val printUi by app.printing.ui.collectAsStateWithLifecycle()
    val historyEntries by app.history.entries.collectAsStateWithLifecycle()
    val driverChoices = remember { DriverRegistry.all.map { it.id to it.name } }

    // Anything that opens a document (file tap, share intent, web print, reprint) lands in the preview.
    val openToken by app.session.openToken.collectAsStateWithLifecycle()
    var consumedToken by rememberSaveable { mutableLongStateOf(0L) }
    LaunchedEffect(openToken) {
        if (openToken != consumedToken) {
            consumedToken = openToken
            if (nav.currentDestination?.route != Routes.PREVIEW) nav.navigate(Routes.PREVIEW) { launchSingleTop = true }
        }
    }

    Box(Modifier.fillMaxSize()) {
        NavHost(navController = nav, startDestination = Routes.HOME) {
            composable(Routes.HOME) {
                HomeScreen(
                    printerState = printerState,
                    onAllowAccess = app.printers::requestPermission,
                    onTestPage = app.printing::printTestPage,
                    driverNameFor = { p -> settings.driverOverrides.let { app.printing.driverLabel(p) } },
                    driverChoices = driverChoices,
                    onSelectDriver = { p, id -> scope.launch { app.settings.setDriverOverride(p.key, id) } },
                    onOpen = { route -> nav.navigate(route) { launchSingleTop = true } },
                )
            }
            composable(Routes.DOCUMENTS) {
                FilesScreen(
                    photos = false,
                    showHidden = settings.showHiddenFiles,
                    onShowHidden = { scope.launch { app.settings.setShowHidden(it) } },
                    onOpen = app.session::open,
                    onBack = { nav.popBackStack() },
                )
            }
            composable(Routes.PHOTOS) {
                FilesScreen(
                    photos = true,
                    showHidden = settings.showHiddenFiles,
                    onShowHidden = { scope.launch { app.settings.setShowHidden(it) } },
                    onOpen = app.session::open,
                    onBack = { nav.popBackStack() },
                )
            }
            composable(Routes.WEB) {
                WebScreen(
                    readerDefault = settings.readerModeDefault,
                    onBack = { nav.popBackStack() },
                    onPrintPdf = { file, title -> app.session.openPdf(file, title) },
                )
            }
            composable(Routes.PREVIEW) {
                PreviewScreen(app = app, onBack = { nav.popBackStack() })
            }
            composable(Routes.HISTORY) {
                HistoryScreen(
                    entries = historyEntries,
                    onBack = { nav.popBackStack() },
                    onReprint = { e ->
                        app.session.openFiles(e.files.map(::File))
                        // Restore the edits used for that job once the document is ready.
                        e.editsJson?.let { json ->
                            scope.launch {
                                app.session.state.first { it is LoadState.Ready }
                                runCatching { app.session.update(DocEdits.fromJson(JSONObject(json)), record = false) }
                            }
                        }
                    },
                    onDelete = { app.history.delete(it.id) },
                    onClear = { app.history.clear() },
                )
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    settings = settings,
                    onBack = { nav.popBackStack() },
                    onThemeMode = { scope.launch { app.settings.setThemeMode(it) } },
                    onDynamicColor = { scope.launch { app.settings.setDynamicColor(it) } },
                    onAutoOpen = { scope.launch { app.settings.setAutoOpen(it) } },
                    onRememberEdits = { scope.launch { app.settings.setRememberEdits(it) } },
                    onDefaultPaper = { scope.launch { app.settings.setDefaultPaper(it) } },
                    onKeepHistory = { scope.launch { app.settings.setKeepHistory(it) } },
                    onReaderDefault = { scope.launch { app.settings.setReaderDefault(it) } },
                    onShowHidden = { scope.launch { app.settings.setShowHidden(it) } },
                    onOpenHistory = { nav.navigate(Routes.HISTORY) { launchSingleTop = true } },
                    onClearEdits = app.session::clearRememberedEdits,
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
                    onPrintAgain = { app.printing.printAgain() },
                    onClose = app.printing::dismiss,
                )
            }
        }
    }
}

private class LastValue<T> {
    var value: T? = null
}
