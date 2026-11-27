package com.usbprint.app.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.LruCache
import com.usbprint.app.doc.DocKind
import com.usbprint.app.doc.SourceDocument
import com.usbprint.app.doc.SourcePage
import com.usbprint.app.edit.DocEdits
import com.usbprint.app.edit.FitMode
import com.usbprint.app.edit.Filters
import com.usbprint.app.edit.Orientation
import com.usbprint.app.edit.PageEdit
import com.usbprint.app.edit.PhotoMode
import com.usbprint.app.edit.parsePageRange
import com.usbprint.core.print.PrintDocument
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

private const val MM = 72f / 25.4f

/** Bitmaps shared between preview and print so slider moves don't re-render the source. */
object PageCache {
    private val maxKb = (Runtime.getRuntime().maxMemory() / 1024 / 5).toInt()
    private val cache = object : LruCache<String, Bitmap>(maxKb) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }

    fun get(key: String): Bitmap? = cache.get(key)
    fun put(key: String, bmp: Bitmap) { cache.put(key, bmp) }
    fun clear() = cache.evictAll()
}

/**
 * Lays the source pages out on sheets according to [DocEdits] and draws them.
 * Implements [PrintDocument] so the very same code path prints what the preview shows.
 */
class SheetComposer(
    val source: SourceDocument,
    val edits: DocEdits,
    private val maxSourcePx: Int = 3000,
) : PrintDocument {

    data class Cell(val rect: RectF, val page: Int, val forceFill: Boolean)

    val pages: List<SourcePage> = source.pages(edits)
    val selected: List<Int> = parsePageRange(edits.print.pageRange, pages.size)
    private val layout = edits.layout
    private val isPhoto = source.kind == DocKind.IMAGES
    private val photoMode = if (isPhoto) layout.photoMode else PhotoMode.ONE
    private val perSheet = when {
        photoMode == PhotoMode.GRID -> layout.gridCount
        photoMode == PhotoMode.ID -> 1
        isPhoto -> 1
        else -> layout.pagesPerSheet.coerceIn(1, 16)
    }
    val sheetCount: Int = max(1, ceil(selected.size / perSheet.toFloat()).toInt())

    override val pageCount get() = sheetCount
    override val isPhotographic get() = isPhoto

    fun sheetPages(sheet: Int): List<Int> =
        selected.subList((sheet * perSheet).coerceAtMost(selected.size), ((sheet + 1) * perSheet).coerceAtMost(selected.size))

    fun isLandscape(sheet: Int): Boolean = when (layout.orientation) {
        Orientation.LANDSCAPE -> true
        Orientation.PORTRAIT -> false
        Orientation.AUTO -> when {
            source.kind == DocKind.TEXT -> false
            photoMode != PhotoMode.ONE -> false
            perSheet == 2 -> true
            perSheet > 2 -> false
            else -> sheetPages(sheet).firstOrNull()?.let { p ->
                val e = edits.page(p)
                val sw = pages[p].widthPt
                val sh = pages[p].heightPt
                val rotated = e.rotation % 180 != 0
                (if (rotated) sh else sw) > (if (rotated) sw else sh) * 1.05f
            } ?: false
        }
    }

    /** Sheet size in points, as seen by the user (landscape already swapped). */
    fun sheetSize(sheet: Int): Pair<Float, Float> {
        val w = layout.paper.widthPt.toFloat()
        val h = layout.paper.heightPt.toFloat()
        return if (isLandscape(sheet)) h to w else w to h
    }

    fun cells(sheet: Int): List<Cell> {
        val (sw, sh) = sheetSize(sheet)
        val margin = if (layout.borderless) 0f else layout.marginMm * MM
        val area = RectF(margin, margin, sw - margin, sh - margin)
        val onSheet = sheetPages(sheet)
        if (onSheet.isEmpty()) return emptyList()

        if (photoMode == PhotoMode.ID) {
            val cw = layout.idSize.widthMm * MM
            val ch = layout.idSize.heightMm * MM
            val gap = 3f * MM
            val cols = max(1, floor((area.width() + gap) / (cw + gap)).toInt())
            val rows = max(1, floor((area.height() + gap) / (ch + gap)).toInt())
            val left = area.left + (area.width() - (cols * cw + (cols - 1) * gap)) / 2f
            val top = area.top
            return buildList {
                for (r in 0 until rows) for (c in 0 until cols) {
                    val x = left + c * (cw + gap)
                    val y = top + r * (ch + gap)
                    add(Cell(RectF(x, y, x + cw, y + ch), onSheet[0], forceFill = true))
                }
            }
        }

        val n = perSheet
        val (cols, rows) = when (n) {
            1 -> 1 to 1
            2 -> if (sw > sh) 2 to 1 else 1 to 2
            3 -> if (sw > sh) 3 to 1 else 1 to 3
            4 -> 2 to 2
            6 -> if (sw > sh) 3 to 2 else 2 to 3
            9 -> 3 to 3
            else -> ceil(kotlin.math.sqrt(n.toFloat())).toInt().let { it to ceil(n / it.toFloat()).toInt() }
        }
        val gap = if (n == 1) 0f else if (isPhoto) 4f * MM else 3f * MM
        val cw = (area.width() - gap * (cols - 1)) / cols
        val ch = (area.height() - gap * (rows - 1)) / rows
        return onSheet.mapIndexed { k, page ->
            val c = k % cols
            val r = k / cols
            val x = area.left + c * (cw + gap)
            val y = area.top + r * (ch + gap)
            Cell(RectF(x, y, x + cw, y + ch), page, forceFill = photoMode == PhotoMode.GRID)
        }
    }

    /** Draws sheet [sheet] in user orientation onto a canvas of [wPx]×[hPx]. */
    fun drawSheet(sheet: Int, canvas: Canvas, wPx: Int, hPx: Int, compare: Boolean = false) {
        val (sw, sh) = sheetSize(sheet)
        canvas.drawColor(Color.WHITE)
        val s = min(wPx / sw, hPx / sh)
        canvas.save()
        canvas.scale(s, s)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        for (cell in cells(sheet)) {
            val e = if (compare) PageEdit.DEFAULT.copy(scale = edits.page(cell.page).scale, offsetX = edits.page(cell.page).offsetX, offsetY = edits.page(cell.page).offsetY) else edits.page(cell.page)
            val cwPx = cell.rect.width() * s
            val chPx = cell.rect.height() * s
            val want = (max(cwPx, chPx) * max(1f, e.scale)).roundToInt()
            val bmp = processed(cell.page, e, want)
            val fill = cell.forceFill || layout.fit == FitMode.FILL
            val k0 = if (fill) max(cell.rect.width() / bmp.width, cell.rect.height() / bmp.height)
            else min(cell.rect.width() / bmp.width, cell.rect.height() / bmp.height)
            val k = k0 * e.scale
            val dw = bmp.width * k
            val dh = bmp.height * k
            val cx = cell.rect.centerX() + e.offsetX * cell.rect.width()
            val cy = cell.rect.centerY() + e.offsetY * cell.rect.height()
            canvas.save()
            canvas.clipRect(cell.rect)
            canvas.drawBitmap(bmp, null, RectF(cx - dw / 2, cy - dh / 2, cx + dw / 2, cy + dh / 2), paint)
            canvas.restore()
        }
        canvas.restore()
    }

    /** Renders a sheet for display at most [maxW]×[maxH] pixels. */
    fun renderSheet(sheet: Int, maxW: Int, maxH: Int, compare: Boolean = false): Bitmap {
        val (sw, sh) = sheetSize(sheet)
        val s = min(maxW / sw, maxH / sh)
        val bmp = Bitmap.createBitmap(max(1, (sw * s).roundToInt()), max(1, (sh * s).roundToInt()), Bitmap.Config.ARGB_8888)
        drawSheet(sheet, Canvas(bmp), bmp.width, bmp.height, compare)
        return bmp
    }

    /** PrintDocument: paper is always portrait at the driver; landscape sheets are rotated. */
    override fun drawPage(index: Int, canvas: Canvas, widthPx: Int, heightPx: Int) {
        if (isLandscape(index)) {
            canvas.save()
            canvas.translate(widthPx.toFloat(), 0f)
            canvas.rotate(90f)
            drawSheet(index, canvas, heightPx, widthPx)
            canvas.restore()
        } else {
            drawSheet(index, canvas, widthPx, heightPx)
        }
    }

    /** Source page with pixel edits applied; [maxPx] is the longest side wanted. */
    fun processed(page: Int, e: PageEdit, maxPx: Int): Bitmap {
        val bucket = bucket(maxPx)
        val src = pages[page]
        val pixel = e.copy(scale = 1f, offsetX = 0f, offsetY = 0f)
        val base = "${System.identityHashCode(src)}/$bucket"
        val key = "$base/${pixel.hashCode()}"
        PageCache.get(key)?.let { return it }
        val raw = PageCache.get(base) ?: src.render(bucket, bucket).also { PageCache.put(base, it) }
        if (pixel == PageEdit.DEFAULT) return raw
        return Filters.apply(raw, pixel).also { PageCache.put(key, it) }
    }

    /** Snap requested sizes to a few buckets so the cache is reused. */
    private fun bucket(px: Int): Int {
        val p = px.coerceIn(64, maxSourcePx)
        val step = 1.25
        val n = ceil(kotlin.math.ln(p / 64.0) / kotlin.math.ln(step)).toInt()
        return (64 * step.pow(n)).roundToInt().coerceAtMost(maxSourcePx)
    }
}
