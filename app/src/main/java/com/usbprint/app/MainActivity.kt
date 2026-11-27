package com.usbprint.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.usbprint.app.settings.AppSettings
import com.usbprint.app.ui.AppNavigation
import com.usbprint.app.ui.theme.USBPrintTheme
import java.io.File

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
        if (savedInstanceState == null) handleOpenIntent(intent)
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
        handleOpenIntent(intent)
    }

    /** Share → USBPrint and "Open with" go straight to the preview. */
    private fun handleOpenIntent(intent: Intent?) {
        intent ?: return
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            Intent.ACTION_SEND -> {
                val stream = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                if (stream != null) listOf(stream)
                else intent.getStringExtra(Intent.EXTRA_TEXT)?.let { text -> listOf(textToFile(text, intent.getStringExtra(Intent.EXTRA_SUBJECT))) }.orEmpty()
            }
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> emptyList()
        }
        if (uris.isEmpty()) return
        // Only handle once (e.g. not again after rotation).
        intent.action = Intent.ACTION_MAIN
        app.session.open(uris)
    }

    /** Shared plain text (or a link) becomes a .txt document. */
    private fun textToFile(text: String, subject: String?): Uri {
        val dir = File(cacheDir, "shared").apply { mkdirs() }
        val name = (subject?.take(40)?.replace(Regex("""[\\/:*?"<>|]"""), "_") ?: "Shared text") + ".txt"
        val f = File(dir, name)
        f.writeText(text)
        return Uri.fromFile(f)
    }
}
