package com.usbprint.core.print

import com.usbprint.core.usb.UsbPrinter
import com.usbprint.core.usb.UsbPrinterConnection

/** Progress callback: [page] is 1-based, [fraction] is overall job progress 0..1. */
fun interface ProgressListener {
    fun onProgress(page: Int, totalPages: Int, fraction: Float)
}

class PrintJobContext(
    val connection: UsbPrinterConnection,
    val document: PrintDocument,
    val options: PrintOptions,
    private val listener: ProgressListener,
) {
    val totalSheets: Int get() = document.pageCount * options.copies.coerceAtLeast(1)

    /** Reports progress within sheet [sheetIndex] (0-based), [within] 0..1. */
    fun progress(sheetIndex: Int, within: Float) {
        val total = totalSheets
        listener.onProgress(
            (sheetIndex + 1).coerceAtMost(total),
            total,
            ((sheetIndex + within.coerceIn(0f, 1f)) / total).coerceIn(0f, 1f),
        )
    }
}

/** A printer language implementation. Runs on a background thread. */
interface PrinterDriver {
    val id: String
    val displayName: String

    /** Sends the whole job. Must only use [PrintJobContext.connection] for I/O so cancel works. */
    fun print(job: PrintJobContext)

    /**
     * Ends an interrupted job within the connection's cleanup budget. Return true if no reset
     * is needed, or false if the transport should fall back to a USB soft reset.
     */
    fun abort(): Boolean = false

    /** Bytes sent after the USB soft reset on a hard cancel (e.g. PJL UEL). */
    val cancelTrailer: ByteArray? get() = null

    /** Whether this driver can talk to [printer] without extra files. */
    fun supports(printer: UsbPrinter): Boolean = true
}
