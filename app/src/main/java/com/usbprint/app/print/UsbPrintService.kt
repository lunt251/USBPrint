package com.usbprint.app.print

import android.os.ParcelFileDescriptor
import android.print.PrintAttributes
import android.print.PrinterCapabilitiesInfo
import android.print.PrinterId
import android.print.PrinterInfo
import android.printservice.PrintJob
import android.printservice.PrintService
import android.printservice.PrinterDiscoverySession
import com.usbprint.app.USBPrintApp
import com.usbprint.app.doc.PdfSource
import com.usbprint.app.edit.DocEdits
import com.usbprint.app.edit.FitMode
import com.usbprint.app.render.SheetComposer
import com.usbprint.core.print.JobState
import com.usbprint.core.print.PaperSize
import com.usbprint.core.usb.PrinterState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

/**
 * System print service: lets any app (Word, Docs, Chrome, Gallery…) print to the USB printer
 * through Android's print dialog. The app sends a PDF, which goes through the normal pipeline.
 */
class UsbPrintService : PrintService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val app get() = application as USBPrintApp

    override fun onCreatePrinterDiscoverySession(): PrinterDiscoverySession = object : PrinterDiscoverySession() {
        private var watching: kotlinx.coroutines.Job? = null

        override fun onStartPrinterDiscovery(priorityList: MutableList<PrinterId>) {
            app.printers.refresh()
            watching?.cancel()
            watching = scope.launch {
                app.printers.state.collect { s ->
                    val current = printers.map { it.id }
                    if (s is PrinterState.Connected) {
                        addPrinters(listOf(info(s.printer.key, "${s.printer.displayBrand} ${s.printer.displayModel}")))
                        removePrinters(current.filter { it.localId != s.printer.key })
                    } else if (current.isNotEmpty()) {
                        removePrinters(current)
                    }
                }
            }
        }

        override fun onStopPrinterDiscovery() {
            watching?.cancel()
        }

        override fun onValidatePrinters(printerIds: MutableList<PrinterId>) {}
        override fun onStartPrinterStateTracking(printerId: PrinterId) {}
        override fun onStopPrinterStateTracking(printerId: PrinterId) {}
        override fun onDestroy() {
            watching?.cancel()
        }
    }

    private fun info(key: String, name: String): PrinterInfo {
        val id = generatePrinterId(key)
        val caps = PrinterCapabilitiesInfo.Builder(id)
            .addMediaSize(PrintAttributes.MediaSize.ISO_A4, true)
            .addMediaSize(PrintAttributes.MediaSize.NA_LETTER, false)
            .addMediaSize(PrintAttributes.MediaSize.NA_LEGAL, false)
            .addMediaSize(PrintAttributes.MediaSize.ISO_A5, false)
            .addResolution(PrintAttributes.Resolution("600", "600 dpi", 600, 600), true)
            .setColorModes(PrintAttributes.COLOR_MODE_MONOCHROME, PrintAttributes.COLOR_MODE_MONOCHROME)
            .setMinMargins(PrintAttributes.Margins(0, 0, 0, 0))
            .build()
        return PrinterInfo.Builder(id, name, PrinterInfo.STATUS_IDLE).setCapabilities(caps).build()
    }

    override fun onPrintJobQueued(job: PrintJob) {
        val printer = (app.printers.state.value as? PrinterState.Connected)?.printer
        if (printer == null) {
            job.fail("Printer not connected")
            return
        }
        job.start()
        scope.launch {
            val title = job.info.label
            val file = try {
                withContext(Dispatchers.IO) {
                    val out = File(cacheDir, "printservice/${System.nanoTime()}.pdf").apply { parentFile?.mkdirs() }
                    val pfd: ParcelFileDescriptor = job.document.data ?: error("No data")
                    FileInputStream(pfd.fileDescriptor).use { i -> out.outputStream().use { o -> i.copyTo(o) } }
                    pfd.close()
                    out
                }
            } catch (e: Exception) {
                job.fail(e.message ?: "Couldn't read the document")
                return@launch
            }
            val source = runCatching { PdfSource(title, file) }.getOrElse {
                job.fail("Couldn't open the document"); return@launch
            }
            val attrs = job.info.attributes
            val paper = when (attrs.mediaSize?.id) {
                PrintAttributes.MediaSize.NA_LETTER.id -> PaperSize.LETTER
                PrintAttributes.MediaSize.NA_LEGAL.id -> PaperSize.LEGAL
                PrintAttributes.MediaSize.ISO_A5.id -> PaperSize.A5
                else -> PaperSize.A4
            }
            val base = DocEdits()
            val edits = base.copy(
                layout = base.layout.copy(paper = paper, marginMm = 0f, fit = FitMode.FIT),
                print = base.print.copy(copies = job.info.copies.coerceAtLeast(1)),
            )
            val composer = SheetComposer(source, edits)
            val err = app.printing.printComposed(printer, composer) { s ->
                when (s) {
                    JobState.Done -> job.complete()
                    JobState.Cancelled -> job.cancel()
                    is JobState.Failed -> job.fail(s.message)
                    else -> {}
                }
                source.close()
            }
            if (err != null) {
                job.fail(err)
                source.close()
            }
        }
    }

    override fun onRequestCancelPrintJob(job: PrintJob) {
        app.printing.cancel()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
