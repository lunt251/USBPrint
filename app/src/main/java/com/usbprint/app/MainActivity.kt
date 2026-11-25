package com.usbprint.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.usbprint.app.settings.AppSettings
import com.usbprint.app.ui.AppNavigation
import com.usbprint.app.ui.theme.USBPrintTheme

class MainActivity : ComponentActivity() {

    private val app get() = application as USBPrintApp

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val settings by app.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
            USBPrintTheme(mode = settings.themeMode, dynamicColor = settings.dynamicColor) {
                AppNavigation(app = app, settings = settings)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        app.printers.refresh()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Launched again by USB_DEVICE_ATTACHED: the system has already granted permission.
        app.printers.refresh()
    }
}
