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
) : Closeable {

    @Volatile
    var isCancelled: Boolean = false
        private set

    @Volatile
    private var closed = false

    /** Soft stop: blocking calls throw [PrintCancelledException] until [beginCleanup] is called. */
    @Volatile
    var isStopRequested: Boolean = false
        private set

    @Volatile
    private var cleanupMode = false

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
                // Timeout or NAK: the printer is busy. Keep trying unless cancelled or stalled too long.
                val now = System.currentTimeMillis()
                if (stalledSince == 0L) stalledSince = now
                if (now - stalledSince > stallTimeoutMs) throw IOException("Printer not accepting data")
            }
        }
    }

    /** Terminates the current bulk transfer if it ended on a packet boundary. */
    fun flush() {
        checkActive()
        if (needsZeroLengthPacket) {
            synchronized(lock) { connection.bulkTransfer(bulkOut, ByteArray(0), 0, SLICE_TIMEOUT_MS) }
            needsZeroLengthPacket = false
        }
    }

    /** Reads back-channel data (bidirectional printers only). Returns 0 on timeout, -1 if unsupported. */
    fun read(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int {
        val ep = bulkIn ?: return -1
        checkActive()
        val n = synchronized(lock) { connection.bulkTransfer(ep, buffer, offset, length, timeoutMs) }
        return if (n < 0) 0 else n
    }

    fun portStatus(): PortStatus? = if (closed) null else UsbPrinterClass.getPortStatus(connection, iface)

    fun deviceId(): DeviceId? = if (closed) null else UsbPrinterClass.getDeviceId(connection, iface)

    /**
     * Cancels the job from any thread: stops writes immediately, then soft-resets the printer
     * so it discards buffered data. [trailer] is an optional language-specific end/cancel sequence
     * (e.g. PJL UEL) sent after the reset.
     */
    fun cancel(trailer: ByteArray? = null) {
        if (closed) return
        isCancelled = true
        synchronized(lock) {
            UsbPrinterClass.softReset(connection, iface)
            if (trailer != null && trailer.isNotEmpty()) {
                connection.bulkTransfer(bulkOut, trailer, trailer.size, SLICE_TIMEOUT_MS)
            }
        }
    }

    /**
     * Asks the running driver to stop at its next USB call, so it can send its own
     * end-of-job sequence (via [beginCleanup]). Use [cancel] if it doesn't respond.
     */
    fun requestStop() {
        isStopRequested = true
    }

    /** Lets a stopped driver talk to the printer again to send cleanup commands. */
    fun beginCleanup() {
        cleanupMode = true
    }

    override fun close() {
        if (closed) return
        closed = true
        synchronized(lock) {
            connection.releaseInterface(iface)
            connection.close()
        }
    }

    /** Throws [PrintCancelledException] if the job was stopped or cancelled. */
    fun ensureActive() = checkActive()

    private fun checkActive() {
        if (isCancelled) throw PrintCancelledException()
        if (isStopRequested && !cleanupMode) throw PrintCancelledException()
        if (closed) throw IOException("Connection closed")
    }

    companion object {
        private const val CHUNK_SIZE = 16 * 1024
        private const val SLICE_TIMEOUT_MS = 250

        internal fun open(manager: UsbManager, device: UsbDevice, printer: UsbPrinter): UsbPrinterConnection {
            val iface = device.getInterface(printer.interfaceIndex)
            val conn = manager.openDevice(device) ?: throw IOException("Cannot open USB printer")
            if (!conn.claimInterface(iface, true)) {
                conn.close()
                throw IOException("Cannot claim USB printer interface")
            }
            if (iface.alternateSetting > 0) conn.setInterface(iface)
            val out = iface.getEndpoint(printer.bulkOutIndex)
            val inp = if (printer.bulkInIndex >= 0) iface.getEndpoint(printer.bulkInIndex) else null
            return UsbPrinterConnection(conn, iface, out, inp, printer)
        }
    }
}
