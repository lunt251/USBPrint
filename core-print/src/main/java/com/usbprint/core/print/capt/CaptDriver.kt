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

    private var channel: CaptChannel? = null
    private var jobId = 0
    private var inJob = false

    override fun print(job: PrintJobContext) {
        val ch = CaptChannel(job.connection)
        channel = ch
        inJob = false
        val copies = job.options.copies.coerceAtLeast(1)
        var sheet = 0
        for (copy in 0 until copies) {
            for (page in 0 until job.document.pageCount) {
                val prepared = preparePage(job, page) { job.progress(sheet, 0.4f * it) }
                if (!inJob) {
                    jobPrologue(ch)
                    inJob = true
                }
                pagePrologue(ch, prepared.params)
                sendBands(ch, prepared.bands) { job.progress(sheet, 0.4f + 0.3f * it) }
                pageEpilogue(ch) { job.progress(sheet, 0.7f + 0.3f * it) }
                sheet++
            }
        }
        jobEpilogue(ch)
        inJob = false
    }

    override fun cleanupAfterStop(job: PrintJobContext) {
        val ch = channel ?: return
        if (!inJob) return
        // lbp3010_cancel_cleanup / lbp2900_cancel_cleanup
        val st = status(ch)
        ch.sendRecv(CaptCmd.GPIO, if (profile.gpio3010) GPIO_INIT_3010 else GPIO_INIT_2900)
        sendJobStart(ch, 4, st.pageCompleted)
        ch.sendRecv(CaptCmd.JOB_END, le16(jobId))
        inJob = false
    }

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

    // ---- protocol steps ----

    private fun status(ch: CaptChannel): CaptStatus =
        if (profile.xStatusOnly) ch.xstatus()
        else ch.basicStatus().also { if (it.flag(0, 8)) ch.xstatus() }

    private fun waitReady(ch: CaptChannel) {
        if (profile.xStatusOnly) {
            val deadline = deadline()
            while (ch.xstatus().busy) { checkDeadline(deadline); ch.pause(CaptChannel.POLL_MS) }
        } else {
            val deadline = deadline()
            while (ch.basicStatus().busy) { checkDeadline(deadline); ch.pause(CaptChannel.POLL_MS) }
        }
    }

    private fun jobPrologue(ch: CaptChannel) {
        ch.sendRecv(CaptCmd.IDENT)
        ch.pause(1000)
        ch.status.reset()
        status(ch)

        ch.sendRecv(CaptCmd.START_0)
        val reply = ch.sendRecv(CaptCmd.JOB_BEGIN, MAGIC_0)
        jobId = if (reply.size >= 4) word(reply[2], reply[3]) else 0

        if (profile.gpioInPrologue) {
            ch.sendRecv(CaptCmd.GPIO, GPIO_INIT_3010)
            waitReady(ch)
        }
        if (profile == CaptProfile.LBP6000) {
            ch.sendRecv(CaptCmd.LBP6000_SETUP_0, byteArrayOf(0x01, 0x00))
            waitReady(ch)
        }
        sendJobStart(ch, 1, 0)
        if (profile == CaptProfile.LBP3000) {
            ch.sendRecv(CaptCmd.LBP3000_SETUP_0, byteArrayOf(0x00, 0x00))
        }
        waitReady(ch)
    }

    private fun pagePrologue(ch: CaptChannel, params: ByteArray) {
        val st = status(ch)
        if (st.uninit) {
            ch.sendRecv(CaptCmd.START_1)
            ch.sendRecv(CaptCmd.START_2)
            ch.sendRecv(CaptCmd.START_3)
            waitReady(ch)
            ch.sendRecv(CaptCmd.UPLOAD_2, MAGIC_2)
            waitReady(ch)
        }
        val deadline = deadline()
        while (status(ch).bufferFull) { checkDeadline(deadline); ch.pause(CaptChannel.POLL_MS) }

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

    private fun sendBands(ch: CaptChannel, bands: List<ByteArray>, onProgress: (Float) -> Unit) {
        var sends = 0
        bands.forEachIndexed { i, band ->
            var off = 0
            while (off < band.size) {
                val n = minOf(0xFF00, band.size - off)
                sends++
                if (sends % 16 == 0) ch.waitReady()
                ch.send(CaptCmd.PRINT_DATA, band.copyOfRange(off, off + n))
                off += n
            }
            onProgress((i + 1).toFloat() / bands.size)
        }
    }

    private fun pageEpilogue(ch: CaptChannel, onProgress: (Float) -> Unit) {
        ch.send(CaptCmd.PRINT_DATA_END)

        var deadline = deadline()
        var st: CaptStatus
        while (true) {
            ch.pause(CaptChannel.POLL_MS)
            st = status(ch)
            if (st.pageReceived == st.pageDecoding) break
            checkDeadline(deadline)
        }
        val page = st.pageDecoding
        onProgress(0.2f)
        sendJobStart(ch, 2, page)
        waitReady(ch)
        ch.sendRecv(CaptCmd.FIRE, le16(page))
        waitReady(ch)
        sendJobStart(ch, 6, page)
        onProgress(0.4f)

        deadline = deadline()
        while (true) {
            val s = status(ch)
            if (s.pageOut == page) break
            if (s.noPaper && !s.printing) throw PaperOutException()
            checkDeadline(deadline)
            ch.pause(CaptChannel.POLL_MS)
        }
        onProgress(1f)
    }

    private fun jobEpilogue(ch: CaptChannel) {
        val deadline = deadline()
        while (true) {
            val st = status(ch)
            if (st.pageCompleted == st.pageDecoding) {
                sendJobStart(ch, 4, st.pageCompleted)
                break
            }
            checkDeadline(deadline)
            ch.pause(CaptChannel.POLL_MS)
        }
        ch.sendRecv(CaptCmd.JOB_END, le16(jobId))
    }

    private fun sendJobStart(ch: CaptChannel, fg: Int, page: Int) {
        val c = Calendar.getInstance()
        val year = c.get(Calendar.YEAR) - 1900
        val buf = ByteArray(32 + 40)
        val head = byteArrayOf(
            0x00, 0x00, 0x00, 0x00, lo(page), hi(page), 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            fg.toByte(), 0x01, lo(jobId), hi(jobId),
            0xC4.toByte(), 0xFF.toByte(), // -60
            0x88.toByte(), 0xFF.toByte(), // -120
            lo(year), hi(year), c.get(Calendar.MONTH).toByte(), c.get(Calendar.DAY_OF_MONTH).toByte(),
            c.get(Calendar.HOUR_OF_DAY).toByte(), c.get(Calendar.MINUTE).toByte(), c.get(Calendar.SECOND).toByte(),
            0x01,
        )
        head.copyInto(buf)
        ch.sendRecv(CaptCmd.JOB_SETUP, buf)
    }

    private fun deadline() = System.currentTimeMillis() + WAIT_TIMEOUT_MS

    private fun checkDeadline(deadline: Long) {
        if (System.currentTimeMillis() > deadline) throw IOException("Printer stopped responding")
    }

    override fun supports(printer: UsbPrinter) = printer.bulkInIndex >= 0

    companion object {
        const val DPI = 600
        private const val WAIT_TIMEOUT_MS = 180_000L

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
