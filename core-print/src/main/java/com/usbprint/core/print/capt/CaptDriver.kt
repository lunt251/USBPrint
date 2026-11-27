package com.usbprint.core.print.capt

import com.usbprint.core.print.PaperSize
import com.usbprint.core.print.PrintJobContext
import com.usbprint.core.print.PrinterDriver
import com.usbprint.core.print.StripRasterizer
import com.usbprint.core.usb.UsbPrinter
import java.io.IOException
import java.util.Calendar

/**
 * Canon CAPT printers (LBP2900/3000/3010/3100/6000 families).
 * Protocol logic ported from captdriver (GPL-3.0) prn_lbp2900.c / rastertocapt.c.
 */
class CaptDriver(private val profile: CaptProfile) : PrinterDriver {

    override val id = "capt_${profile.name.lowercase()}"
    override val displayName = "Canon CAPT (${profile.label})"

    private var session: CaptSession? = null

    override fun print(job: PrintJobContext) {
        job.connection.stallTimeoutMs = 15_000
        val capt = CaptSession(profile, CaptChannel(job.connection))
        session = capt
        val copies = job.options.copies.coerceAtLeast(1)
        var sheet = 0
        repeat(copies) {
            for (page in 0 until job.document.pageCount) {
                val prepared = preparePage(job, page) { job.progress(sheet, 0.4f * it) }
                capt.printPage(prepared.params, prepared.bands) { job.progress(sheet, it) }
                sheet++
            }
        }
        capt.finish()
        session = null
    }

    override fun abort(): Boolean = session?.abort() ?: true

    // ---- page preparation (render + compress, before talking to the printer) ----

    private class PreparedPage(val params: ByteArray, val bands: List<ByteArray>)

    private fun preparePage(job: PrintJobContext, page: Int, onProgress: (Float) -> Unit): PreparedPage {
        val paper = job.options.paper
        val pageW = paper.widthPx(DPI)
        val pageH = paper.heightPx(DPI)
        // captdriver uses the page width in points as the line size in bytes (e.g. A4: 595 bytes = 4760 dots).
        val lineSize = paper.widthPt
        val outW = lineSize * 8
        val cropLeft = ((pageW - outW) / 2).coerceAtLeast(0)
        val bandSize = profile.bandLines

        val raster = StripRasterizer(job.document, page, pageW, pageH, outW, pageH, cropLeft, stripHeight = bandSize)
        check(raster.bytesPerLine == lineSize)
        val comp = ByteArray(2 * lineSize * bandSize)
        val bands = ArrayList<ByteArray>()
        raster.forEachStrip { first, rows, data ->
            job.connection.ensureActive()
            val n = HiscoaCompressor.compressBand(data, lineSize, rows, comp)
            bands += comp.copyOf(n)
            onProgress((first + rows).toFloat() / pageH)
            true
        }
        return PreparedPage(pageParams(job, paper, lineSize, pageW, pageH), bands)
    }

    private fun pageParams(job: PrintJobContext, paper: PaperSize, lineSize: Int, paperW: Int, paperH: Int): ByteArray {
        val sz = when (paper.nearestPreset()) {
            PaperSize.A4 -> 0x02
            PaperSize.A5 -> 0x03
            PaperSize.EXECUTIVE -> 0x0A
            PaperSize.LEGAL -> 0x0C
            PaperSize.LETTER -> 0x0D
            else -> 0x02
        }
        val darkness = if (job.options.darkness in 0..15) job.options.darkness else 7
        val inkK = darkness shl 2
        val save = if (job.options.tonerSave) 1 else 0
        val mediaType = profile.plainMediaType
        val fm = 0x01 // plain paper fuser mode
        val mediaAdapt = 0x11 // 600 dpi
        val air = 0x02 // automatic image refinement
        val marginH = 0
        val marginW = 0
        val numLines = paperH
        return byteArrayOf(
            0x00, 0x00, 0x30, 0x2A, sz.toByte(), 0x00, 0x00, 0x00,
            inkK.toByte(), 0x1C, 0x1C, 0x1C, mediaType.toByte(), mediaAdapt.toByte(), 0x04, 0x00,
            0x01, 0x01, air.toByte(), save.toByte(), 0x00, 0x00,
            lo(marginH), hi(marginH), lo(marginW), hi(marginW),
            lo(lineSize), hi(lineSize), lo(numLines), hi(numLines),
            lo(paperW), hi(paperW), lo(paperH), hi(paperH),
            0x00, 0x00, fm.toByte(), 0x00, 0x00, 0x00,
        )
    }

    override fun supports(printer: UsbPrinter) = printer.bulkInIndex >= 0

    companion object {
        const val DPI = 600
        private fun lo(v: Int) = (v and 0xFF).toByte()
        private fun hi(v: Int) = ((v shr 8) and 0xFF).toByte()
    }
}

/** One CAPT job on an open channel, including the abort sequence for a partially started job. */
internal class CaptSession(
    private val profile: CaptProfile,
    private val ch: CaptTransport,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val currentStatus = CaptStatus()
    private var beginSent = false
    private var jobId: Int? = null

    fun printPage(params: ByteArray, bands: List<ByteArray>, progress: (Float) -> Unit) {
        if (!beginSent) jobPrologue()
        pagePrologue(params)
        sendBands(bands) { progress(0.4f + 0.3f * it) }
        pageEpilogue { progress(0.7f + 0.3f * it) }
    }

    private fun status(forReadyCheck: Boolean = false): CaptStatus {
        fun read(cmd: Int) {
            val reply = ch.sendRecv(cmd)
            if (reply.size < 2) throw IOException("CAPT: missing printer status")
            currentStatus.decode(reply, reply.size)
        }
        if (profile.xStatusOnly) read(CaptCmd.CHKXSTATUS)
        else {
            read(CaptCmd.CHKSTATUS)
            if (!forReadyCheck && currentStatus.flag(0, 8)) read(CaptCmd.CHKXSTATUS)
        }
        return currentStatus
    }

    private fun awaitStatus(
        timeoutMs: Long = READY_TIMEOUT_MS,
        forReadyCheck: Boolean = false,
        reached: (CaptStatus) -> Boolean,
    ): CaptStatus {
        val deadline = nanoTime() + timeoutMs * 1_000_000
        var emptySince: Long? = null
        while (true) {
            val s = status(forReadyCheck)
            // A completed page wins over an empty tray. Some models leave the paper bit set
            // after a refill until the next job advances, so don't reject a ready printer up front.
            if (reached(s)) return s
            val now = nanoTime()
            if (s.noPaper) {
                val since = emptySince ?: now.also { emptySince = it }
                if (!s.printing || now - since >= PAPER_OUT_GRACE_MS * 1_000_000 || now - deadline >= 0) {
                    throw PaperOutException()
                }
            } else {
                emptySince = null
            }
            if (now - deadline >= 0) throw IOException("Printer stopped responding")
            ch.pause(POLL_MS)
        }
    }

    private fun waitReady() {
        awaitStatus(forReadyCheck = true) { !it.busy }
    }

    private fun jobPrologue() {
        ch.sendRecv(CaptCmd.IDENT)
        ch.pause(1000)
        currentStatus.reset()
        status()

        ch.sendRecv(CaptCmd.START_0)
        beginSent = true
        val reply = ch.sendRecv(CaptCmd.JOB_BEGIN, MAGIC_0)
        if (reply.size < 4) throw IOException("CAPT: missing job ID")
        jobId = word(reply[2], reply[3])

        if (profile.gpioInPrologue) {
            ch.sendRecv(CaptCmd.GPIO, GPIO_INIT_3010)
            waitReady()
        }
        if (profile == CaptProfile.LBP6000) {
            ch.sendRecv(CaptCmd.LBP6000_SETUP_0, byteArrayOf(0x01, 0x00))
            waitReady()
        }
        sendJobStart(1, 0)
        if (profile == CaptProfile.LBP3000) {
            ch.sendRecv(CaptCmd.LBP3000_SETUP_0, byteArrayOf(0x00, 0x00))
        }
        waitReady()
    }

    private fun pagePrologue(params: ByteArray) {
        if (status().uninit) {
            ch.sendRecv(CaptCmd.START_1)
            ch.sendRecv(CaptCmd.START_2)
            ch.sendRecv(CaptCmd.START_3)
            waitReady()
            ch.sendRecv(CaptCmd.UPLOAD_2, MAGIC_2)
            waitReady()
        }
        awaitStatus { !it.bufferFull }

        ch.sendMulti(
            CaptCmd.SET_PARMS,
            listOf(
                CaptCmd.SET_PARM_PAGE to params,
                CaptCmd.SET_PARM_HISCOA to HiscoaParams.DEFAULT.format(),
                CaptCmd.SET_PARM_1 to null,
                CaptCmd.SET_PARM_2 to null,
            ),
        )
    }

    private fun sendBands(bands: List<ByteArray>, onProgress: (Float) -> Unit) {
        var sends = 0
        bands.forEachIndexed { i, band ->
            var off = 0
            while (off < band.size) {
                val n = minOf(0xFF00, band.size - off)
                sends++
                if (sends % 16 == 0) waitReady()
                ch.send(CaptCmd.PRINT_DATA, band.copyOfRange(off, off + n))
                off += n
            }
            onProgress((i + 1).toFloat() / bands.size)
        }
    }

    private fun pageEpilogue(onProgress: (Float) -> Unit) {
        ch.send(CaptCmd.PRINT_DATA_END)
        ch.pause(POLL_MS)
        val page = awaitStatus { it.pageReceived == it.pageDecoding }.pageDecoding
        onProgress(0.2f)
        sendJobStart(2, page)
        waitReady()
        ch.sendRecv(CaptCmd.FIRE, le16(page))
        waitReady()
        sendJobStart(6, page)
        onProgress(0.4f)

        awaitStatus(timeoutMs = PAGE_TIMEOUT_MS) { it.pageOut == page }
        onProgress(1f)
    }

    fun finish() {
        if (!beginSent) return
        val completed = awaitStatus { it.pageCompleted == it.pageDecoding }.pageCompleted
        endJob(completed)
    }

    fun abort(): Boolean {
        if (!beginSent) return true
        if (jobId == null) return false // JOB_BEGIN may have reached the printer without a reply.
        val completed = status().pageCompleted
        // captdriver lbp3010_cancel_cleanup / lbp2900_cancel_cleanup, without a busy wait.
        ch.sendRecv(CaptCmd.GPIO, if (profile.gpio3010) GPIO_INIT_3010 else GPIO_INIT_2900)
        endJob(completed)
        return true
    }

    private fun endJob(completed: Int) {
        sendJobStart(4, completed)
        ch.sendRecv(CaptCmd.JOB_END, le16(checkNotNull(jobId)))
        beginSent = false
        jobId = null
    }

    private fun sendJobStart(fg: Int, page: Int) {
        val id = checkNotNull(jobId)
        val c = Calendar.getInstance()
        val year = c.get(Calendar.YEAR) - 1900
        val buf = ByteArray(32 + 40)
        val head = byteArrayOf(
            0x00, 0x00, 0x00, 0x00, lo(page), hi(page), 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            fg.toByte(), 0x01, lo(id), hi(id),
            0xC4.toByte(), 0xFF.toByte(), // -60
            0x88.toByte(), 0xFF.toByte(), // -120
            lo(year), hi(year), c.get(Calendar.MONTH).toByte(), c.get(Calendar.DAY_OF_MONTH).toByte(),
            c.get(Calendar.HOUR_OF_DAY).toByte(), c.get(Calendar.MINUTE).toByte(), c.get(Calendar.SECOND).toByte(),
            0x01,
        )
        head.copyInto(buf)
        ch.sendRecv(CaptCmd.JOB_SETUP, buf)
    }

    companion object {
        private const val POLL_MS = 400L
        private const val READY_TIMEOUT_MS = 30_000L
        private const val PAGE_TIMEOUT_MS = 120_000L
        private const val PAPER_OUT_GRACE_MS = 2_000L

        private val MAGIC_0 = byteArrayOf(0x00, 0x00, 0x1E, 0x00, 0x00, 0x00, 0x00, 0x00)
        private val MAGIC_2 = byteArrayOf(
            0xEE.toByte(), 0xDB.toByte(), 0xEA.toByte(), 0xAD.toByte(), 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0,
        )
        private val GPIO_INIT_2900 = ByteArray(12)
        private val GPIO_INIT_3010 = byteArrayOf(0x13, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)

        private fun lo(v: Int) = (v and 0xFF).toByte()
        private fun hi(v: Int) = ((v shr 8) and 0xFF).toByte()
        private fun le16(v: Int) = byteArrayOf(lo(v), hi(v))
    }
}

/** captdriver printer families. */
enum class CaptProfile(
    val label: String,
    val bandLines: Int,
    val plainMediaType: Int,
    val xStatusOnly: Boolean,
    val gpioInPrologue: Boolean,
    val gpio3010: Boolean,
    /** Confirmed working in captdriver; others are experimental there. */
    val verified: Boolean,
) {
    LBP2900("LBP2900", 70, 0x00, xStatusOnly = false, gpioInPrologue = true, gpio3010 = false, verified = true),
    LBP3000("LBP3000", 70, 0x00, xStatusOnly = false, gpioInPrologue = false, gpio3010 = false, verified = false),
    LBP3010("LBP3010/3018/3050", 160, 0x01, xStatusOnly = true, gpioInPrologue = true, gpio3010 = true, verified = true),
    LBP3100("LBP3100/3108/3150", 160, 0x01, xStatusOnly = true, gpioInPrologue = true, gpio3010 = true, verified = false),
    LBP6000("LBP6000/6018", 160, 0x01, xStatusOnly = true, gpioInPrologue = true, gpio3010 = true, verified = false);

    companion object {
        fun forModel(model: String?): CaptProfile? {
            val m = model?.uppercase()?.replace(" ", "") ?: return null
            return when {
                "LBP2900" in m -> LBP2900
                "LBP3000" in m -> LBP3000
                "LBP3010" in m || "LBP3018" in m || "LBP3050" in m -> LBP3010
                "LBP3100" in m || "LBP3108" in m || "LBP3150" in m -> LBP3100
                "LBP6000" in m || "LBP6018" in m -> LBP6000
                else -> null
            }
        }
    }
}
