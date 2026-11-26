package com.usbprint.core.print.capt

import com.usbprint.core.usb.UsbPrinterConnection
import java.io.IOException

/** CAPT command codes (from captdriver capt-command.h). */
internal object CaptCmd {
    const val CHKJOBSTAT = 0xA0A1
    const val CHKXSTATUS = 0xA0A8
    const val IDENT = 0xA1A1
    const val JOB_BEGIN = 0xA2A0
    const val START_0 = 0xA3A2
    const val PRINT_DATA = 0xC0A0
    const val PRINT_DATA_END = 0xC0A4
    const val SET_PARM_PAGE = 0xD0A0
    const val SET_PARM_1 = 0xD0A1
    const val SET_PARM_2 = 0xD0A2
    const val SET_PARM_HISCOA = 0xD0A4
    const val SET_PARMS = 0xD0A9
    const val CHKSTATUS = 0xE0A0
    const val START_2 = 0xE0A2
    const val START_1 = 0xE0A3
    const val START_3 = 0xE0A4
    const val UPLOAD_2 = 0xE0A5
    const val LBP3000_SETUP_0 = 0xE0A6
    const val FIRE = 0xE0A7
    const val JOB_END = 0xE0A9
    const val LBP6000_SETUP_0 = 0xE0BA
    const val JOB_SETUP = 0xE1A1
    const val GPIO = 0xE1A2
}

/** Decoded CAPT status block (captdriver capt-status.c). */
internal class CaptStatus {
    val status = IntArray(7)
    var pageDecoding = 0
    var pagePrinting = 0
    var pageOut = 0
    var pageCompleted = 0
    var pageReceived = 0

    fun reset() {
        status.fill(0)
        pageDecoding = 0; pagePrinting = 0; pageOut = 0; pageCompleted = 0; pageReceived = 0
    }

    fun decode(s: ByteArray, size: Int) {
        fun w(i: Int) = word(s[i], s[i + 1])
        if (size < 2) return
        status[0] = w(0)
        if (size <= 2 || size < 10) return
        status[1] = w(8)
        if (size <= 10 || size < 40) return
        status[2] = w(10)
        status[3] = w(12)
        pageDecoding = w(14)
        pagePrinting = w(16)
        pageOut = w(18)
        pageCompleted = w(20)
        pageReceived = w(34)
        status[4] = w(24)
        status[5] = w(30)
        status[6] = w(38)
    }

    fun flag(word: Int, bit: Int) = status[word] and (1 shl bit) != 0

    val busy get() = flag(0, 7)
    val uninit get() = flag(0, 5) || flag(0, 4)
    val bufferFull get() = flag(0, 2)
    val noPaper get() = flag(0, 1) || flag(1, 14)
    val printing get() = flag(1, 2) || flag(1, 7)
}

internal fun word(lo: Byte, hi: Byte): Int = ((hi.toInt() and 0xFF) shl 8) or (lo.toInt() and 0xFF)

internal fun bcd(lo: Byte, hi: Byte): Int {
    val a = (hi.toInt() shr 4) and 0xF
    val b = hi.toInt() and 0xF
    val c = (lo.toInt() shr 4) and 0xF
    val d = lo.toInt() and 0xF
    if (a > 9 || b > 9 || c > 9 || d > 9) return word(lo, hi)
    return a * 1000 + b * 100 + c * 10 + d
}

/** CAPT packet framing over a USB printer connection: `cmd(LE16) len(LE16) payload`. */
internal class CaptChannel(private val conn: UsbPrinterConnection) {

    private val rx = ByteArray(0x10000)
    val status = CaptStatus()

    fun frame(cmd: Int, data: ByteArray?): ByteArray {
        val size = data?.size ?: 0
        val out = ByteArray(4 + size)
        out[0] = cmd.toByte(); out[1] = (cmd shr 8).toByte()
        out[2] = (size + 4).toByte(); out[3] = ((size + 4) shr 8).toByte()
        data?.copyInto(out, 4)
        return out
    }

    fun send(cmd: Int, data: ByteArray? = null) {
        val f = frame(cmd, data)
        conn.write(f)
        conn.flush()
    }

    /** Several sub-commands wrapped in one outer command (CAPT_SET_PARMS). */
    fun sendMulti(cmd: Int, parts: List<Pair<Int, ByteArray?>>) {
        val body = parts.map { (c, d) -> frame(c, d) }
        val total = 4 + body.sumOf { it.size }
        val out = ByteArray(total)
        out[0] = cmd.toByte(); out[1] = (cmd shr 8).toByte()
        out[2] = total.toByte(); out[3] = (total shr 8).toByte()
        var p = 4
        body.forEach { it.copyInto(out, p); p += it.size }
        conn.write(out)
        conn.flush()
    }

    /** Sends a command and returns the reply payload (without the 4-byte header). */
    fun sendRecv(cmd: Int, data: ByteArray? = null): ByteArray {
        send(cmd, data)
        var got = readSome(0)
        while (got < 4) got = readSome(got)
        if (word(rx[0], rx[1]) != cmd) {
            throw IOException("CAPT: bad reply to %04X: %02X %02X".format(cmd, rx[0], rx[1]))
        }
        while (true) {
            val declared = word(rx[2], rx[3])
            if (declared == got || bcd(rx[2], rx[3]) == got) break
            if (declared > got) {
                got = readSome(got)
                continue
            }
            throw IOException("CAPT: bad reply size for %04X: declared %d, got %d".format(cmd, declared, got))
        }
        return rx.copyOfRange(4, got)
    }

    private fun readSome(offset: Int): Int {
        val deadline = System.currentTimeMillis() + REPLY_TIMEOUT_MS
        while (true) {
            val n = conn.read(rx, offset, rx.size - offset, 500)
            if (n < 0) throw IOException("CAPT needs a bidirectional printer connection")
            if (n > 0) return offset + n
            if (System.currentTimeMillis() > deadline) throw IOException("Printer did not reply")
        }
    }

    fun xstatus(): CaptStatus {
        val r = sendRecv(CaptCmd.CHKXSTATUS)
        status.decode(r, r.size)
        return status
    }

    fun basicStatus(): CaptStatus {
        val r = sendRecv(CaptCmd.CHKSTATUS)
        status.decode(r, r.size)
        return status
    }

    fun waitXReady() {
        while (xstatus().busy) pause(POLL_MS)
    }

    fun waitReady() {
        while (basicStatus().busy) pause(POLL_MS)
    }

    /** Sleep that wakes up immediately when the job is cancelled. */
    fun pause(ms: Long) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            conn.ensureActive()
            Thread.sleep(minOf(50L, end - System.currentTimeMillis()).coerceAtLeast(1))
        }
    }

    companion object {
        const val POLL_MS = 400L
        private const val REPLY_TIMEOUT_MS = 15_000L
    }
}

/** Raised when the printer reports no paper during a page. */
class PaperOutException : IOException("Printer is out of paper")
