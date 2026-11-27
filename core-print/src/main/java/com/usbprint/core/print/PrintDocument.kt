package com.usbprint.core.print

import android.graphics.Canvas

/** A paper size in PostScript points (1/72 inch). Presets plus user-defined custom sizes. */
data class PaperSize(val name: String, val displayName: String, val widthPt: Int, val heightPt: Int) {

    fun widthPx(dpi: Int) = Math.round(widthPt * dpi / 72f)
    fun heightPx(dpi: Int) = Math.round(heightPt * dpi / 72f)

    val isCustom get() = name.startsWith(CUSTOM_PREFIX)

    /** Nearest preset by area, for drivers that only accept standard size codes. */
    fun nearestPreset(): PaperSize =
        if (!isCustom && this in entries) this
        else standard.minBy { kotlin.math.abs(it.widthPt * it.heightPt - widthPt * heightPt) }

    companion object {
        const val CUSTOM_PREFIX = "custom:"
        private fun mm(v: Float) = Math.round(v / 25.4f * 72f)

        val A3 = PaperSize("A3", "A3", 842, 1191)
        val A4 = PaperSize("A4", "A4", 595, 842)
        val A5 = PaperSize("A5", "A5", 420, 595)
        val A6 = PaperSize("A6", "A6", 298, 420)
        val B5 = PaperSize("B5", "B5", 499, 709)
        val LETTER = PaperSize("LETTER", "Letter", 612, 792)
        val LEGAL = PaperSize("LEGAL", "Legal", 612, 1008)
        val EXECUTIVE = PaperSize("EXECUTIVE", "Executive", 522, 756)
        val TABLOID = PaperSize("TABLOID", "Tabloid", 792, 1224)
        val PHOTO_4X6 = PaperSize("PHOTO_4X6", "4 Ã— 6 in", 288, 432)
        val PHOTO_5X7 = PaperSize("PHOTO_5X7", "5 Ã— 7 in", 360, 504)
        val PHOTO_10X15 = PaperSize("PHOTO_10X15", "10 Ã— 15 cm", mm(100f), mm(150f))
        val ENVELOPE_DL = PaperSize("ENVELOPE_DL", "DL envelope", mm(110f), mm(220f))

        /** Sizes the driver code tables know. */
        val standard = listOf(A4, A5, LETTER, LEGAL, EXECUTIVE)

        val entries = listOf(A4, LETTER, LEGAL, A5, A3, A6, B5, EXECUTIVE, TABLOID, PHOTO_4X6, PHOTO_5X7, PHOTO_10X15, ENVELOPE_DL)

        fun valueOf(name: String): PaperSize = entries.firstOrNull { it.name == name } ?: throw IllegalArgumentException(name)

        fun custom(label: String, widthPt: Int, heightPt: Int) =
            PaperSize("$CUSTOM_PREFIX$widthPt" + "x$heightPt", label, widthPt.coerceIn(72, 2000), heightPt.coerceIn(72, 3000))

        /** "name|label|w|h" for storage. */
        fun encode(p: PaperSize) = "${p.name}|${p.displayName.replace("|", " ")}|${p.widthPt}|${p.heightPt}"

        fun decode(s: String): PaperSize? = s.split('|').takeIf { it.size == 4 }?.let { f ->
            val w = f[2].toIntOrNull() ?: return null
            val h = f[3].toIntOrNull() ?: return null
            PaperSize(f[0], f[1], w, h)
        }
    }
}

/**
 * Something printable. Pages are drawn on demand (strip by strip) into a canvas whose
 * coordinate space is the full page in device pixels, so nothing is ever rendered at full
 * resolution in one bitmap.
 */
interface PrintDocument {
    val pageCount: Int

    /** Draws page [index] onto [canvas]; the page spans (0,0)-(widthPx,heightPx). Background is white. */
    fun drawPage(index: Int, canvas: Canvas, widthPx: Int, heightPx: Int)

    /** True for photos (error-diffusion dithering), false for text/graphics (sharper threshold). */
    val isPhotographic: Boolean get() = false
}

data class PrintOptions(
    val paper: PaperSize = PaperSize.A4,
    val copies: Int = 1,
    /** 0..15, printer-specific darkness; -1 = driver default. */
    val darkness: Int = -1,
    val tonerSave: Boolean = false,
)
