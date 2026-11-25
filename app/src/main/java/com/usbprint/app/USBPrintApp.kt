package com.usbprint.app

import android.app.Application
import com.usbprint.app.settings.SettingsRepository
import com.usbprint.core.usb.UsbPrinterManager

class USBPrintApp : Application() {
    val printers: UsbPrinterManager by lazy { UsbPrinterManager(this) }
    val settings: SettingsRepository by lazy { SettingsRepository(this) }
}
