package com.usbprint.core.print

import com.usbprint.core.usb.PrintCancelledException
import com.usbprint.core.usb.UsbPrinter
import com.usbprint.core.usb.UsbPrinterConnection
import com.usbprint.core.usb.UsbPrinterManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.concurrent.thread

sealed interface JobState {
    data object Preparing : JobState
    data class Printing(val page: Int, val totalPages: Int, val fraction: Float) : JobState
    data object Cancelling : JobState
    data object Cancelled : JobState
    data object Done : JobState
    data class Failed(val message: String) : JobState

    val isFinished: Boolean get() = this is Cancelled || this is Done || this is Failed
}

/**
 * One print job on its own thread. [cancel] is safe from the UI thread and returns immediately:
 * the driver is stopped at its next USB call (within ~250 ms), gets [CLEANUP_BUDGET_MS] to end the
 * job cleanly, then the printer is soft-reset regardless.
 */
class PrintJob(
    private val manager: UsbPrinterManager,
    val printer: UsbPrinter,
    val driver: PrinterDriver,
    val document: PrintDocument,
    val options: PrintOptions,
    val title: String,
) {
    private val _state = MutableStateFlow<JobState>(JobState.Preparing)
    val state: StateFlow<JobState> = _state.asStateFlow()

    @Volatile private var connection: UsbPrinterConnection? = null
    @Volatile private var cancelRequested = false
    private var worker: Thread? = null

    fun start() {
        check(worker == null) { "Job already started" }
        worker = thread(name = "usbprint-job", isDaemon = true) { run() }
    }

    fun cancel() {
        if (_state.value.isFinished || cancelRequested) return
        cancelRequested = true
        _state.value = JobState.Cancelling
        val conn = connection ?: return // not connected yet: run() sees the flag
        conn.requestStop()
        // Watchdog: if the driver's cleanup doesn't finish quickly, hard-reset the printer.
        thread(name = "usbprint-cancel", isDaemon = true) {
            worker?.join(CLEANUP_BUDGET_MS)
            if (worker?.isAlive == true) conn.cancel(driver.cancelTrailer)
        }
    }

    private fun run() {
        var conn: UsbPrinterConnection? = null
        try {
            if (cancelRequested) throw PrintCancelledException()
            conn = manager.open(printer)
            connection = conn
            if (cancelRequested) conn.requestStop()
            val ctx = PrintJobContext(conn, document, options) { page, total, fraction ->
                if (!cancelRequested) _state.value = JobState.Printing(page, total, fraction)
            }
            try {
                driver.print(ctx)
                _state.value = JobState.Done
            } catch (e: PrintCancelledException) {
                if (!conn.isCancelled) {
                    conn.beginCleanup()
                    runCatching { driver.cleanupAfterStop(ctx) }
                }
                if (!conn.isCancelled) conn.cancel(driver.cancelTrailer)
                _state.value = JobState.Cancelled
            }
        } catch (e: PrintCancelledException) {
            _state.value = JobState.Cancelled
        } catch (e: Exception) {
            _state.value = if (cancelRequested) JobState.Cancelled
            else JobState.Failed(e.message ?: e.javaClass.simpleName)
        } finally {
            runCatching { conn?.close() }
            connection = null
        }
    }

    companion object {
        const val CLEANUP_BUDGET_MS = 3_000L
    }
}
