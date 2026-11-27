package com.usbprint.core.print.ps

import com.usbprint.core.print.PrintJobContext
import com.usbprint.core.print.PrinterDriver
import com.usbprint.core.print.StripRasterizer
import com.usbprint.core.print.pcl.PackBits
import java.io.ByteArrayOutputStream

/**
 * PostScript Level 2: each page is one 1-bit image, RunLength + ASCII85 encoded, streamed
 * strip by strip. Works on any PostScript printer (and BR-Script / KPDL emulations).
 */
class PostScriptDriver(private val dpi: Int = 300) : PrinterDriver {

    override val id = "postscript_$dpi"
    override val displayName = "PostScript ($dpi dpi)"
    override val cancelTrailer: ByteArray = byteArrayOf(0x04) // Ctrl-D: end of job

    override fun print(job: PrintJobContext) {
        val conn = job.connection
        val paper = job.options.paper
        val w = paper.widthPx(dpi)
        val h = paper.heightPx(dpi)
        val out = ByteArrayOutputStream(64 * 1024)
        val pages = job.document.pageCount
        val copies = job.options.copies.coerceAtLeast(1)

        out.write(
            ("%!PS-Adobe-3.0\n%%Creator: USBPrint\n%%Pages: ${pages * copies}\n%%EndComments\n" +
                "<< /PageSize [${paper.widthPt} ${paper.heightPt}] >> setpagedevice\n").toByteArray(),
        )
        var sheet = 0
        val rowBuf = ByteArray((w + 7) / 8 + (w + 7) / 8 / 128 + 2)
        for (copy in 0 until copies) {
            for (page in 0 until pages) {
                out.write(
                    ("%%Page: ${sheet + 1} ${sheet + 1}\nsave\n" +
                        "0 0 moveto ${paper.widthPt} ${paper.heightPt} scale\n" +
                        "/DeviceGray setcolorspace\n" +
                        "<< /ImageType 1 /Width $w /Height $h /BitsPerComponent 1 /Decode [1 0]\n" +
                        "   /ImageMatrix [$w 0 0 -$h 0 $h]\n" +
                        "   /DataSource currentfile /ASCII85Decode filter /RunLengthDecode filter >> image\n").toByteArray(),
                )
                val a85 = Ascii85Encoder(out)
                val raster = StripRasterizer(job.document, page, w, h, w, h)
                val bpl = raster.bytesPerLine
                raster.forEachStrip { first, rows, data ->
                    for (r in 0 until rows) {
                        val n = PackBits.encode(data, r * bpl, bpl, rowBuf)
                        a85.write(rowBuf, 0, n)
                    }
                    conn.write(out.toByteArray()); out.reset()
                    job.progress(sheet, (first + rows).toFloat() / h)
                    true
                }
                a85.write(byteArrayOf(0x80.toByte()), 0, 1) // RunLength EOD
                a85.finish()
                out.write("\nrestore\nshowpage\n".toByteArray())
                conn.write(out.toByteArray()); out.reset()
                sheet++
            }
        }
        out.write("%%EOF\n".toByteArray())
        out.write(0x04)
        conn.write(out.toByteArray())
        conn.flush()
    }
}

/** Streaming ASCII85 encoder (writes `~>` on finish). */
internal class Ascii85Encoder(private val out: ByteArrayOutputStream) {
    private val tuple = IntArray(4)
    private var count = 0
    private var column = 0

    fun write(b: ByteArray, off: Int, len: Int) {
        for (i in off until off + len) {
            tuple[count++] = b[i].toInt() and 0xFF
            if (count == 4) {
                encode(4)
                count = 0
            }
        }
    }

    private fun encode(n: Int) {
        var v = 0L
        for (i in 0 until 4) v = (v shl 8) or (if (i < n) tuple[i].toLong() else 0L)
        if (n == 4 && v == 0L) {
            put('z'.code)
            return
        }
        val chars = IntArray(5)
        for (i in 4 downTo 0) {
            chars[i] = (v % 85).toInt() + 33
            v /= 85
        }
        for (i in 0 until n + 1) put(chars[i])
    }

    private fun put(c: Int) {
        out.write(c)
        if (++column >= 72) {
            out.write('\n'.code)
            column = 0
        }
    }

    fun finish() {
        if (count > 0) {
            encode(count)
            count = 0
        }
        out.write("~>".toByteArray())
    }
}
