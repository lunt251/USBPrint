package com.usbprint.app.print

import android.content.Context
import com.usbprint.app.R
import com.usbprint.core.print.DriverMatcher
import com.usbprint.core.print.JobState
import com.usbprint.core.print.PrintDocument
import com.usbprint.core.print.PrintJob
import com.usbprint.core.print.PrintOptions
import com.usbprint.core.print.TestPageDocument
import com.usbprint.core.usb.UsbPrinter
import com.usbprint.core.usb.UsbPrinterManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** UI-facing view of the current (or last) print job. */
data class PrintUiState(
    val title: String,
    val printerName: String,
    val driverName: String,
    val job: JobState,
)

/** Owns the active print job so the print card survives navigation. */
class PrintController(private val context: Context, private val manager: UsbPrinterManager) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var current: PrintJob? = null
    private var collector: Job? = null
    private var lastRequest: (() -> Unit)? = null

    private val _ui = MutableStateFlow<PrintUiState?>(null)
    val ui: StateFlow<PrintUiState?> = _ui.asStateFlow()

    /** Returns null on success, or a user-facing reason why the job can't start. */
    fun print(printer: UsbPrinter, document: PrintDocument, title: String, options: PrintOptions = PrintOptions()): String? {
        if (current?.state?.value?.isFinished == false) return context.getString(R.string.print_busy)
        val match = DriverMatcher.match(printer) ?: return context.getString(R.string.print_no_driver)
        if (!match.driver.supports(printer)) return context.getString(R.string.print_needs_bidi)

        val job = PrintJob(manager, printer, match.driver, document, options, title)
        current = job
        lastRequest = { print(printer, document, title, options) }
        collector?.cancel()
        collector = scope.launch {
            job.state.collect { s ->
                _ui.value = PrintUiState(title, "${printer.displayBrand} ${printer.displayModel}", match.driver.displayName, s)
            }
        }
        job.start()
        return null
    }

    fun printTestPage(printer: UsbPrinter): String? {
        val match = DriverMatcher.match(printer)
        val lines = listOf(
            "Printer: ${printer.displayBrand} ${printer.displayModel}",
            "Driver: ${match?.driver?.displayName ?: "none"}" + (match?.note?.let { " ($it)" } ?: ""),
            "USB: %04X:%04X, %s".format(printer.vendorId, printer.productId, printer.protocolName),
            "Languages: ${printer.deviceId?.commandSet?.joinToString(", ").orEmpty()}",
        )
        return print(printer, TestPageDocument(lines), context.getString(R.string.printer_test_page))
    }

    fun cancel() {
        current?.cancel()
    }

    fun printAgain() {
        lastRequest?.invoke()
    }

    fun dismiss() {
        if (current?.state?.value?.isFinished != false) _ui.value = null
    }
}
