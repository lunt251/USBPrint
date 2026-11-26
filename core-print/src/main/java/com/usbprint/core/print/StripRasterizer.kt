package com.usbprint.core.print

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color

/**
 * Renders a page strip by strip and hands out 1-bit packed rows.
 *
 * The page is laid out at [pageWidthPx] x [pageHeightPx]; output rows cover columns
 * [cropLeftPx, cropLeftPx + outWidthPx) and rows [0, outHeightPx). Columns outside the page
 * are white. Strips are [stripHeight] rows; memory use is width * stripHeight * 4 bytes.
 */
class StripRasterizer(
    private val document: PrintDocument,
    private val pageIndex: Int,
    private val pageWidthPx: Int,
    private val pageHeightPx: Int,
    val outWidthPx: Int,
    val outHeightPx: Int,
    private val cropLeftPx: Int = 0,
    private val stripHeight: Int = 256,
) {
    val bytesPerLine = MonoPacker.bytesPerLine(outWidthPx)

    /**
     * Calls [block] for each strip with (firstRow, rowCount, packedData). [packedData] is reused
     * between calls. Return false from [block] to stop early.
     */
    fun forEachStrip(block: (firstRow: Int, rows: Int, data: ByteArray) -> Boolean) {
        val bitmap = Bitmap.createBitmap(outWidthPx, stripHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val pixels = IntArray(outWidthPx * stripHeight)
        val lum = ByteArray(outWidthPx * stripHeight)
        val packed = ByteArray(bytesPerLine * stripHeight)
        try {
            var y = 0
            while (y < outHeightPx) {
                val rows = minOf(stripHeight, outHeightPx - y)
                bitmap.eraseColor(Color.WHITE)
                canvas.save()
                canvas.translate(-cropLeftPx.toFloat(), -y.toFloat())
                canvas.clipRect(0, 0, pageWidthPx, pageHeightPx)
                document.drawPage(pageIndex, canvas, pageWidthPx, pageHeightPx)
                canvas.restore()
                bitmap.getPixels(pixels, 0, outWidthPx, 0, 0, outWidthPx, rows)
                for (i in 0 until outWidthPx * rows) {
                    val p = pixels[i]
                    val r = (p shr 16) and 0xFF
                    val g = (p shr 8) and 0xFF
                    val b = p and 0xFF
                    lum[i] = ((r * 77 + g * 150 + b * 29) shr 8).toByte()
                }
                // Ordered dither for everything: anti-aliased text edges and greys both survive.
                MonoPacker.pack(lum, outWidthPx, rows, y, packed)
                if (!block(y, rows, packed)) return
                y += rows
            }
        } finally {
            bitmap.recycle()
        }
    }
}
