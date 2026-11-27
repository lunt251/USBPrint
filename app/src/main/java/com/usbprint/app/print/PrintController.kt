package com.usbprint.app.print

import android.content.Context
import com.usbprint.app.R
import com.usbprint.app.doc.SourceDocument
import com.usbprint.app.render.SheetComposer
import com.usbprint.app.session.HistoryStore
import com.usbprint.app.settings.AppSettings
import com.usbprint.core.print.DriverMatcher
import com.usbprint.core.print.DriverRegistry
import com.usbprint.core.print.JobState
import com.usbprint.core.print.PrintDocument
import com.usbprint.core.print.PrintJob
import com.usbprint.core.print.PrintOptions
import com.usbprint.core.print.PrinterDriver
import com.usbprint.core.print.TestPageDocument
import com.usbprint.core.usb.PrinterState
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
    val retryPrinterKey: String?,
)

data class ResolvedDriver(val driver: PrinterDriver, val note: String?, val manual: Boolean)

/** Owns the active print job so the print card survives navigation. */
class PrintController(
    private val context: Context,
    private val manager: UsbPrinterManager,
    private val history: HistoryStore,
    private val settings: () -> AppSettings,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var current: PrintJob? = null
    private var collector: Job? = null
    private var lastRequest: (() -> String?)? = null

    private val _ui = MutableStateFlow<PrintUiState?>(null)
    val ui: StateFlow<PrintUiState?> = _ui.asStateFlow()

    val isBusy: Boolean get() = current?.state?.value?.isFinished == false

    fun resolve(printer: UsbPrinter): ResolvedDriver? {
        settings().driverOverrides[printer.key]?.let { id ->
            DriverRegistry.create(id)?.let { return ResolvedDriver(it, null, manual = true) }
        }
        return DriverMatcher.match(printer)?.let { ResolvedDriver(it.driver, it.note, manual = false) }
    }

    fun driverLabel(printer: UsbPrinter): String? = resolve(printer)?.let { r ->
        r.driver.displayName + when {
            r.manual -> " · manual"
            r.note != null -> " · ${r.note}"
            else -> ""
        }
    }

    fun isPrinting(doc: SourceDocument): Boolean =
        isBusy && (current?.document as? SheetComposer)?.source === doc

    /** Returns null on success, or a user-facing reason why the job can't start. */
    fun print(
        printer: UsbPrinter,
        document: PrintDocument,
        title: String,
        options: PrintOptions = PrintOptions(),
        historyId: Long? = null,
        onFinished: ((JobState) -> Unit)? = null,
    ): String? {
        if (isBusy) return context.getString(R.string.print_busy)
        val resolved = resolve(printer) ?: return context.getString(R.string.print_no_driver)
        val driver = resolved.driver
        if (!driver.supports(printer)) return context.getString(R.string.print_needs_bidi)

        val job = PrintJob(manager, printer, driver, document, options, title)
        current = job
        val retryKey = printer.key.takeIf { onFinished == null }
        // System print jobs must be retried from Android's print spooler, which owns their PDF.
        lastRequest = retryKey?.let { key ->
            {
                val connected = (manager.state.value as? PrinterState.Connected)?.printer
                if (connected?.key == key) print(connected, document, title, options)
                else context.getString(R.string.printer_disconnected)
            }
        }
        collector?.cancel()
        collector = scope.launch {
            job.state.collect { s ->
                _ui.value = PrintUiState(title, "${printer.displayBrand} ${printer.displayModel}", driver.displayName, s, retryKey)
                if (s.isFinished) {
                    historyId?.let { history.setStatus(it, statusLabel(s)) }
                    onFinished?.invoke(s)
                }
            }
        }
        job.start()
        PrintNotificationService.start(context)
        return null
    }

    /** Prints a composed document and records it in history. */
    fun printComposed(printer: UsbPrinter, composer: SheetComposer, onFinished: ((JobState) -> Unit)? = null): String? {
        if (isBusy) return context.getString(R.string.print_busy)
        val e = composer.edits
        val options = PrintOptions(
            paper = e.layout.paper,
            copies = e.print.copies.coerceIn(1, 99),
            darkness = e.print.darkness,
            tonerSave = e.print.tonerSave,
        )
        val title = composer.source.title
        val id = if (settings().keepHistory) {
            history.add(title, "${printer.displayBrand} ${printer.displayModel}", composer.sheetCount * options.copies, composer.source.files, e.toJson().toString())
        } else null
        val err = print(printer, composer, title, options, id, onFinished)
        if (err != null && id != null) history.delete(id)
        return err
    }

    fun printTestPage(printer: UsbPrinter): String? {
        val r = resolve(printer)
        val lines = listOf(
            "Printer: ${printer.displayBrand} ${printer.displayModel}",
            "Driver: ${r?.driver?.displayName ?: "none"}" + (r?.note?.let { " ($it)" } ?: "") + (if (r?.manual == true) " (manual)" else ""),
            "USB: %04X:%04X, %s".format(printer.vendorId, printer.productId, printer.protocolName),
            "Languages: ${printer.deviceId?.commandSet?.joinToString(", ").orEmpty()}",
        )
        return print(printer, TestPageDocument(lines), context.getString(R.string.printer_test_page))
    }

    private fun statusLabel(s: JobState) = when (s) {
        JobState.Done -> "Printed"
        JobState.Cancelled -> "Cancelled"
        is JobState.Failed -> "Failed: ${s.message}"
        else -> "Printing"
    }

    fun cancel() {
        current?.cancel()
    }

    fun printAgain(): String? = lastRequest?.invoke()

    fun dismiss() {
        if (!isBusy) _ui.value = null
    }
}
