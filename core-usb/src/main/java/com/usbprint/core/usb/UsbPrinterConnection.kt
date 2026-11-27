package com.usbprint.core.usb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream

/** Thrown from any blocking call once [UsbPrinterConnection.cancel] has been called. */
class PrintCancelledException : InterruptedIOException("Print cancelled")

class PrinterDisconnectedException : IOException("USB printer disconnected. Reconnect it before retrying.")

/**
 * A claimed printer interface. Data is written with short-timeout bulk transfers so that
 * [cancel] takes effect within one transfer slice (~[SLICE_TIMEOUT_MS]) instead of waiting
 * for the printer to drain its buffer.
 */
class UsbPrinterConnection private constructor(
    private val connection: UsbDeviceConnection,
    private val iface: UsbInterface,
    private val bulkOut: UsbEndpoint,
    private val bulkIn: UsbEndpoint?,
    val printer: UsbPrinter,
    private val isAttached: () -> Boolean,
) : Closeable {

    @Volatile private var disconnected = false

    @Volatile
    var isCancelled: Boolean = false
        private set

    @Volatile
    private var closed = false

    /** Soft stop: blocking calls throw [PrintCancelledException] outside the cleanup window. */
    @Volatile
    private var stopRequested = false

    private var cleanupDeadlineNanos: Long? = null

    private val lock = Any()
    private var needsZeroLengthPacket = false

    /** Max time the printer may refuse data (busy / out of paper) before the job fails. */
    var stallTimeoutMs: Long = 120_000

    val outputStream: OutputStream = object : OutputStream() {
        private val one = ByteArray(1)
        override fun write(b: Int) {
            one[0] = b.toByte()
            write(one, 0, 1)
        }
        override fun write(b: ByteArray, off: Int, len: Int) = this@UsbPrinterConnection.write(b, off, len)
        override fun flush() = this@UsbPrinterConnection.flush()
    }

    val inputStream: InputStream? = bulkIn?.let {
        object : InputStream() {
            override fun read(): Int {
                val b = ByteArray(1)
                return if (read(b, 0, 1) == 1) b[0].toInt() and 0xFF else -1
            }
            override fun read(b: ByteArray, off: Int, len: Int): Int = this@UsbPrinterConnection.read(b, off, len, 1000)
        }
    }

    fun write(data: ByteArray, offset: Int = 0, length: Int = data.size) {
        var off = offset
        var remaining = length
        var stalledSince = 0L
        while (remaining > 0) {
            checkActive()
            val n = minOf(remaining, CHUNK_SIZE)
            val sent = synchronized(lock) { connection.bulkTransfer(bulkOut, data, off, n, SLICE_TIMEOUT_MS) }
            if (sent > 0) {
                off += sent
                remaining -= sent
                stalledSince = 0L
                needsZeroLengthPacket = sent % bulkOut.maxPacketSize == 0
            } else {
                // Android returns the same error for a timeout and a removed device.
                ensureAttached()
                val now = System.nanoTime()
                if (stalledSince == 0L) stalledSince = now
                if (now - stalledSince >= stallTimeoutMs * 1_000_000) throw IOException("Printer not accepting data")
            }
        }
        checkActive()
    }

    /** Terminates the current bulk transfer if it ended on a packet boundary. */
    fun flush() {
        checkActive()
        if (needsZeroLengthPacket) {
            val sent = synchronized(lock) { connection.bulkTransfer(bulkOut, ByteArray(0), 0, SLICE_TIMEOUT_MS) }
            if (sent < 0) ensureAttached()
            needsZeroLengthPacket = false
        }
        checkActive()
    }

    /** Reads back-channel data. Returns 0 on timeout, -1 if unsupported; throws on detach. */
    fun read(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int {
        checkActive()
        val ep = bulkIn ?: return -1
        val n = synchronized(lock) { connection.bulkTransfer(ep, buffer, offset, length, timeoutMs) }
        if (n < 0) ensureAttached()
        checkActive()
        return n.coerceAtLeast(0)
    }

    fun portStatus(): PortStatus? = if (closed) null else UsbPrinterClass.getPortStatus(connection, iface)

    fun deviceId(): DeviceId? = if (closed) null else UsbPrinterClass.getDeviceId(connection, iface)

    /**
     * Cancels the job from any thread: stops writes immediately, then soft-resets the printer
     * so it discards buffered data. [trailer] is an optional language-specific end/cancel sequence
     * (e.g. PJL UEL) sent after the reset.
     */
    fun cancel(trailer: ByteArray? = null) {
        synchronized(lock) {
            if (closed || disconnected || isCancelled || !isAttached()) return
            isCancelled = true
            UsbPrinterClass.softReset(connection, iface)
            if (!disconnected && trailer != null && trailer.isNotEmpty()) {
                connection.bulkTransfer(bulkOut, trailer, trailer.size, SLICE_TIMEOUT_MS)
            }
        }
    }

    /**
     * Asks the running driver to stop at its next USB call, so it can send its own
     * end-of-job sequence (via [beginCleanup]). Use [cancel] if it doesn't respond.
     */
    fun requestStop() {
        stopRequested = true
    }

    /** Lets the print worker attempt cleanup without getting stuck on a stalled printer. */
    fun beginCleanup(timeoutMs: Long) {
        cleanupDeadlineNanos = System.nanoTime() + timeoutMs * 1_000_000
    }

    internal fun markDisconnected() {
        disconnected = true
    }

    /** Rechecks enumeration after a failed transfer, or before reporting a job complete. */
    fun ensureAttached() {
        if (disconnected || !isAttached()) {
            markDisconnected()
            throw PrinterDisconnectedException()
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            try {
                connection.releaseInterface(iface)
            } finally {
                connection.close()
            }
        }
    }

    fun ensureActive() = checkActive()

    private fun checkActive() {
        if (disconnected) throw PrinterDisconnectedException()
        if (isCancelled) throw PrintCancelledException()
        val deadline = cleanupDeadlineNanos
        if (deadline != null) {
            if (System.nanoTime() - deadline >= 0) throw InterruptedIOException("Printer cleanup timed out")
        } else if (stopRequested) {
            throw PrintCancelledException()
        }
        if (closed) throw IOException("Connection closed")
    }

    companion object {
        private const val CHUNK_SIZE = 16 * 1024
        private const val SLICE_TIMEOUT_MS = 250

        internal fun open(
            manager: UsbManager, device: UsbDevice, printer: UsbPrinter, isAttached: () -> Boolean,
        ): UsbPrinterConnection {
            val iface = device.getInterface(printer.interfaceIndex)
            fun openFailure(message: String): IOException =
                if (isAttached()) IOException(message) else PrinterDisconnectedException()
            val conn = manager.openDevice(device) ?: throw openFailure("Cannot open USB printer")
            if (!conn.claimInterface(iface, true)) {
                conn.close()
                throw openFailure("Cannot claim USB printer interface")
            }
            if (iface.alternateSetting > 0) conn.setInterface(iface)
            val out = iface.getEndpoint(printer.bulkOutIndex)
            val inp = if (printer.bulkInIndex >= 0) iface.getEndpoint(printer.bulkInIndex) else null
            return UsbPrinterConnection(conn, iface, out, inp, printer, isAttached)
        }
    }
}
