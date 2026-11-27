package com.usbprint.app

import android.app.Application
import com.usbprint.app.print.PrintController
import com.usbprint.app.session.DocumentSession
import com.usbprint.app.session.HistoryStore
import com.usbprint.app.settings.AppSettings
import com.usbprint.app.settings.SettingsRepository
import com.usbprint.core.usb.UsbPrinterManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class USBPrintApp : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _current = MutableStateFlow(AppSettings())

    /** Latest settings, readable synchronously. */
    val currentSettings: StateFlow<AppSettings> get() = _current

    val printers: UsbPrinterManager by lazy { UsbPrinterManager(this) }
    val settings: SettingsRepository by lazy { SettingsRepository(this) }
    val history: HistoryStore by lazy { HistoryStore(this) }
    val printing: PrintController by lazy { PrintController(this, printers, history) { _current.value } }
    val session: DocumentSession by lazy {
        DocumentSession(this) { _current.value }.also { s -> s.inUse = { doc -> printing.isPrinting(doc) } }
    }

    override fun onCreate() {
        super.onCreate()
        appScope.launch { settings.settings.collect { _current.value = it } }
    }
}
