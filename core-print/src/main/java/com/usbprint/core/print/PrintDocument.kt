package com.usbprint.core.print

import android.graphics.Canvas

/** Physical paper sizes, in PostScript points (1/72 inch). */
enum class PaperSize(val displayName: String, val widthPt: Int, val heightPt: Int) {
    A4("A4", 595, 842),
    LETTER("Letter", 612, 792),
    LEGAL("Legal", 612, 1008),
    A5("A5", 420, 595),
    EXECUTIVE("Executive", 522, 756);

    fun widthPx(dpi: Int) = Math.round(widthPt * dpi / 72f)
    fun heightPx(dpi: Int) = Math.round(heightPt * dpi / 72f)
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
