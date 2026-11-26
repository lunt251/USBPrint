package com.usbprint.core.print.escpos

import com.usbprint.core.print.PrintJobContext
import com.usbprint.core.print.PrinterDriver
import com.usbprint.core.print.StripRasterizer
import java.io.ByteArrayOutputStream

/**
 * ESC/POS thermal receipt printers: GS v 0 raster images. [dotsWide] is 384 for 58 mm
 * paper and 576 for 80 mm at 203 dpi. Pages are scaled to the roll width.
 */
class EscPosDriver(private val dotsWide: Int = 576, private val cut: Boolean = true) : PrinterDriver {

    override val id = "escpos_${dotsWide}"
    override val displayName = "ESC/POS receipt (${if (dotsWide >= 576) "80" else "58"} mm)"
    override val cancelTrailer: ByteArray = byteArrayOf(0x1B, 0x40)

    override fun print(job: PrintJobContext) {
        val conn = job.connection
        val paper = job.options.paper
        val w = dotsWide
        val h = Math.round(w.toFloat() * paper.heightPt / paper.widthPt)
        val out = ByteArrayOutputStream()
        conn.write(byteArrayOf(0x1B, 0x40)) // initialise

        val copies = job.options.copies.coerceAtLeast(1)
        var sheet = 0
        for (copy in 0 until copies) {
            for (page in 0 until job.document.pageCount) {
                val raster = StripRasterizer(job.document, page, w, h, w, h, stripHeight = 128)
                val bpl = raster.bytesPerLine
                raster.forEachStrip { first, rows, data ->
                    out.reset()
                    out.write(byteArrayOf(0x1D, 0x76, 0x30, 0x00))
                    out.write(byteArrayOf((bpl and 0xFF).toByte(), (bpl shr 8).toByte()))
                    out.write(byteArrayOf((rows and 0xFF).toByte(), (rows shr 8).toByte()))
                    out.write(data, 0, bpl * rows)
                    conn.write(out.toByteArray())
                    job.progress(sheet, (first + rows).toFloat() / h)
                    true
                }
                conn.write(byteArrayOf(0x1B, 0x64, 4)) // feed
                if (cut) conn.write(byteArrayOf(0x1D, 0x56, 0x42, 0x00)) // partial cut
                sheet++
            }
        }
        conn.flush()
    }
}
