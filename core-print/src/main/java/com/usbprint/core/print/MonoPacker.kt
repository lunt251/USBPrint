package com.usbprint.core.print

/**
 * Converts 8-bit luminance (0 = black, 255 = white) to packed 1-bit rows (MSB first,
 * 1 = black dot) using an 8x8 ordered dither, which stays consistent across strip boundaries.
 * Pure Kotlin so it can be unit tested on the JVM.
 */
object MonoPacker {

    private val BAYER8 = intArrayOf(
        0, 32, 8, 40, 2, 34, 10, 42,
        48, 16, 56, 24, 50, 18, 58, 26,
        12, 44, 4, 36, 14, 46, 6, 38,
        60, 28, 52, 20, 62, 30, 54, 22,
        3, 35, 11, 43, 1, 33, 9, 41,
        51, 19, 59, 27, 49, 17, 57, 25,
        15, 47, 7, 39, 13, 45, 5, 37,
        63, 31, 55, 23, 61, 29, 53, 21,
    )

    /** Threshold for each Bayer cell scaled to 0..255, offset so pure white and pure black stay pure. */
    private val THRESH = IntArray(64) { (BAYER8[it] * 4 + 2) }

    fun bytesPerLine(widthPx: Int) = (widthPx + 7) / 8

    /**
     * @param lum luminance for [rows] rows of [width] pixels
     * @param firstRow page row index of the first row (keeps the dither pattern aligned)
     * @param out destination, [bytesPerLine] * rows bytes
     * @param sharp when true, uses a plain 50% threshold (crisp text); else ordered dither
     */
    fun pack(lum: ByteArray, width: Int, rows: Int, firstRow: Int, out: ByteArray, sharp: Boolean = false) {
        val bpl = bytesPerLine(width)
        out.fill(0, 0, bpl * rows)
        for (r in 0 until rows) {
            val src = r * width
            val dst = r * bpl
            val trow = ((firstRow + r) and 7) * 8
            for (x in 0 until width) {
                val l = lum[src + x].toInt() and 0xFF
                val t = if (sharp) 128 else THRESH[trow + (x and 7)]
                if (l < t) {
                    val i = dst + (x ushr 3)
                    out[i] = (out[i].toInt() or (0x80 ushr (x and 7))).toByte()
                }
            }
        }
    }

    fun isBlank(data: ByteArray, offset: Int, length: Int): Boolean {
        for (i in offset until offset + length) if (data[i].toInt() != 0) return false
        return true
    }
}
