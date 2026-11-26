package com.usbprint.core.print.pcl

import com.usbprint.core.print.PaperSize
import com.usbprint.core.print.PrintJobContext
import com.usbprint.core.print.PrinterDriver
import com.usbprint.core.print.StripRasterizer
import java.io.ByteArrayOutputStream

/**
 * Monochrome PCL 5 / PCL 3 raster (TIFF/PackBits compressed rows). Understood by nearly every
 * PCL laser and many inkjets. Uses PJL job wrapping when the printer advertises PJL.
 */
class PclRasterDriver(private val dpi: Int = 600, private val usePjl: Boolean = true) : PrinterDriver {

    override val id = "pcl_raster"
    override val displayName = "PCL raster ($dpi dpi)"
    override val cancelTrailer: ByteArray = UEL + "\u001BE".toByteArray() + UEL

    override fun print(job: PrintJobContext) {
        val conn = job.connection
        val paper = job.options.paper
        val out = ByteArrayOutputStream(64 * 1024)

        if (usePjl) {
            out.write(UEL)
            out.write("@PJL JOB NAME=\"USBPrint\"\r\n".toByteArray())
            out.write("@PJL SET RESOLUTION=$dpi\r\n".toByteArray())
            if (job.options.tonerSave) out.write("@PJL SET ECONOMODE=ON\r\n".toByteArray())
            out.write("@PJL ENTER LANGUAGE=PCL\r\n".toByteArray())
        }
        esc(out, "E")
        esc(out, "&l${paperCode(paper)}A") // page size
        esc(out, "&l0O") // portrait
        esc(out, "&l0E") // top margin 0
        esc(out, "&u${dpi}D") // unit of measure
        esc(out, "*t${dpi}R") // raster resolution
        flush(conn, out)

        // The PCL logical page starts ~1/6" (A4: 1/4") inside the left edge; render the area under it.
        val pageW = paper.widthPx(dpi)
        val pageH = paper.heightPx(dpi)
        val leftOffset = dpi / 4
        val topOffset = dpi / 6
        val outW = pageW - 2 * leftOffset
        val outH = pageH - 2 * topOffset

        val copies = job.options.copies.coerceAtLeast(1)
        var sheet = 0
        val packBuf = ByteArray(MonoRowBytes.max(outW))
        for (copy in 0 until copies) {
            for (page in 0 until job.document.pageCount) {
                esc(out, "*p0x0Y") // cursor to top-left of logical page
                esc(out, "*r${outW}S") // raster width
                esc(out, "*r${outH}T") // raster height
                esc(out, "*b2M") // TIFF PackBits
                esc(out, "*r1A") // start raster at cursor
                val raster = StripRasterizer(job.document, page, pageW, pageH, outW, outH, leftOffset)
                val bpl = raster.bytesPerLine
                raster.forEachStrip { first, rows, data ->
                    for (r in 0 until rows) {
                        var len = bpl
                        val base = r * bpl
                        while (len > 0 && data[base + len - 1].toInt() == 0) len-- // trim trailing white
                        val n = if (len == 0) 0 else PackBits.encode(data, base, len, packBuf)
                        esc(out, "*b${n}W")
                        out.write(packBuf, 0, n)
                    }
                    flush(conn, out)
                    job.progress(sheet, (first + rows).toFloat() / outH)
                    true
                }
                esc(out, "*rC") // end raster
                out.write(0x0C) // form feed
                flush(conn, out)
                sheet++
            }
        }
        esc(out, "E")
        if (usePjl) {
            out.write(UEL)
            out.write("@PJL EOJ NAME=\"USBPrint\"\r\n".toByteArray())
            out.write(UEL)
        }
        flush(conn, out)
    }

    private fun esc(out: ByteArrayOutputStream, s: String) {
        out.write(0x1B)
        out.write(s.toByteArray(Charsets.US_ASCII))
    }

    private fun flush(conn: com.usbprint.core.usb.UsbPrinterConnection, out: ByteArrayOutputStream) {
        if (out.size() == 0) return
        conn.write(out.toByteArray())
        out.reset()
    }

    private fun paperCode(p: PaperSize) = when (p) {
        PaperSize.A4 -> 26
        PaperSize.LETTER -> 2
        PaperSize.LEGAL -> 3
        PaperSize.A5 -> 25
        PaperSize.EXECUTIVE -> 1
    }

    companion object {
        private val UEL = "\u001B%-12345X".toByteArray()
    }
}

internal object MonoRowBytes {
    /** Worst-case PackBits size for a row of [widthPx] pixels. */
    fun max(widthPx: Int): Int {
        val bpl = (widthPx + 7) / 8
        return bpl + bpl / 128 + 2
    }
}

/** TIFF PackBits (PCL compression mode 2). */
object PackBits {
    fun encode(src: ByteArray, offset: Int, length: Int, dst: ByteArray): Int {
        var i = offset
        val end = offset + length
        var o = 0
        while (i < end) {
            // run of identical bytes?
            var run = 1
            while (i + run < end && run < 128 && src[i + run] == src[i]) run++
            if (run >= 2) {
                dst[o++] = (1 - run).toByte()
                dst[o++] = src[i]
                i += run
                continue
            }
            // literal run until the next repeat of 2+
            val start = i
            var lit = 0
            while (i < end && lit < 128) {
                if (i + 1 < end && src[i] == src[i + 1]) break
                i++
                lit++
            }
            dst[o++] = (lit - 1).toByte()
            System.arraycopy(src, start, dst, o, lit)
            o += lit
        }
        return o
    }

    fun decode(src: ByteArray, length: Int): ByteArray {
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < length) {
            val n = src[i++].toInt()
            if (n >= 0) {
                out.write(src, i, n + 1); i += n + 1
            } else if (n != -128) {
                repeat(1 - n) { out.write(src[i].toInt()) }; i++
            }
        }
        return out.toByteArray()
    }
}
