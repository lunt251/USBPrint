package com.usbprint.core.print

import com.usbprint.core.usb.PrintCancelledException
import com.usbprint.core.usb.PrinterDisconnectedException
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

/** A print worker owns the USB connection until any cleanup is complete. */
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

    private val lock = Any()
    @Volatile private var cancelRequested = false
    private var connection: UsbPrinterConnection? = null
    private var started = false

    fun start() {
        check(!started) { "Job already started" }
        started = true
        thread(name = "usbprint-job", isDaemon = true) { run() }
    }

    fun cancel() {
        synchronized(lock) {
            if (_state.value.isFinished || cancelRequested) return
            cancelRequested = true
            _state.value = JobState.Cancelling
            connection?.requestStop()
        }
    }

    private fun run() {
        var conn: UsbPrinterConnection? = null
        val result = try {
            if (cancelRequested) throw PrintCancelledException()
            conn = manager.open(printer)
            synchronized(lock) {
                connection = conn
                if (cancelRequested) conn.requestStop()
            }
            val ctx = PrintJobContext(conn, document, options) { page, total, fraction ->
                synchronized(lock) {
                    if (!cancelRequested) _state.value = JobState.Printing(page, total, fraction)
                }
            }
            conn.ensureActive()
            driver.print(ctx)
            conn.ensureAttached()
            conn.ensureActive()
            JobState.Done
        } catch (e: Throwable) {
            val failure = if (e is PrinterDisconnectedException) e else {
                // A cable can disappear between the last I/O call and failure handling.
                try {
                    conn?.ensureAttached()
                    if (conn != null) {
                        conn.beginCleanup(CLEANUP_BUDGET_MS)
                        val stopped = runCatching { driver.abort() }.getOrDefault(false)
                        if (!stopped) runCatching { conn.cancel(driver.cancelTrailer) }
                        conn.ensureAttached()
                    }
                    e
                } catch (detached: PrinterDisconnectedException) {
                    detached
                }
            }
            when {
                failure is PrinterDisconnectedException -> JobState.Failed(failure.message.orEmpty())
                failure is PrintCancelledException || cancelRequested -> JobState.Cancelled
                else -> JobState.Failed(failure.message ?: failure.javaClass.simpleName)
            }
        } finally {
            runCatching { conn?.close() }
        }
        // Do not offer retry until the failed job has released the claimed interface.
        synchronized(lock) {
            connection = null
            _state.value = result
        }
    }

    companion object {
        private const val CLEANUP_BUDGET_MS = 3_000L
    }
}
