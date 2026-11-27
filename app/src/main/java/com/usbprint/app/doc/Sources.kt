package com.usbprint.app.doc

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.exifinterface.media.ExifInterface
import com.usbprint.app.edit.DocEdits
import com.usbprint.app.edit.Orientation
import com.usbprint.app.edit.TextAlign
import com.usbprint.app.edit.TextFont
import java.io.Closeable
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class DocKind { PDF, IMAGES, TEXT, WEB }

/** One source page; sizes are in points, only the aspect ratio matters for images. */
interface SourcePage {
    val widthPt: Float
    val heightPt: Float

    /** Renders the page on white, fitting inside [maxW]×[maxH] and keeping aspect. */
    fun render(maxW: Int, maxH: Int): Bitmap
}

interface SourceDocument : Closeable {
    val title: String
    val kind: DocKind

    /** Local copies of the inputs (used for history / reprint). */
    val files: List<File>

    /** Shown in the preview when the conversion is lossy (e.g. Office → plain text). */
    val notice: String? get() = null

    /** Pages for the current edits (text re-flows with paper and text options). */
    fun pages(edits: DocEdits): List<SourcePage>

    override fun close() {}
}

internal fun fitSize(w: Float, h: Float, maxW: Int, maxH: Int): Pair<Int, Int> {
    val s = min(maxW / w, maxH / h)
    return max(1, (w * s).roundToInt()) to max(1, (h * s).roundToInt())
}

class PdfSource(
    override val title: String,
    private val file: File,
    override val kind: DocKind = DocKind.PDF,
    override val files: List<File> = listOf(file),
) : SourceDocument {
    private val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(pfd)
    private val lock = Any()
    private val pageList: List<SourcePage>

    init {
        val count = renderer.pageCount
        pageList = List(count) { i -> PdfPage(i) }
    }

    private inner class PdfPage(private val index: Int) : SourcePage {
        private val size: Pair<Int, Int> by lazy {
            synchronized(lock) { renderer.openPage(index).use { it.width to it.height } }
        }
        override val widthPt get() = size.first.toFloat()
        override val heightPt get() = size.second.toFloat()

        override fun render(maxW: Int, maxH: Int): Bitmap {
            val (bw, bh) = fitSize(widthPt, heightPt, maxW, maxH)
            val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            synchronized(lock) {
                renderer.openPage(index).use { p ->
                    val m = Matrix().apply { setScale(bw / widthPt, bh / heightPt) }
                    p.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                }
            }
            return bmp
        }
    }

    override fun pages(edits: DocEdits) = pageList

    override fun close() {
        synchronized(lock) {
            runCatching { renderer.close() }
            runCatching { pfd.close() }
        }
    }
}

class ImageSource(override val title: String, override val files: List<File>) : SourceDocument {
    override val kind = DocKind.IMAGES
    private val pageList = files.map { ImagePage(it) }

    private class ImagePage(private val file: File) : SourcePage {
        private val info: Triple<Int, Int, Int> by lazy {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, o)
            val rot = runCatching { ExifInterface(file.path).rotationDegrees }.getOrDefault(0)
            Triple(max(1, o.outWidth), max(1, o.outHeight), rot)
        }
        private val swapped get() = info.third == 90 || info.third == 270
        override val widthPt get() = (if (swapped) info.second else info.first).toFloat()
        override val heightPt get() = (if (swapped) info.first else info.second).toFloat()

        override fun render(maxW: Int, maxH: Int): Bitmap {
            val (bw, bh) = fitSize(widthPt, heightPt, maxW, maxH)
            var sample = 1
            while (widthPt / (sample * 2) >= bw && heightPt / (sample * 2) >= bh) sample *= 2
            val decoded = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }) ?: return Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
            val out = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            out.eraseColor(Color.WHITE)
            val c = Canvas(out)
            val sw = if (swapped) decoded.height else decoded.width
            val sh = if (swapped) decoded.width else decoded.height
            val m = Matrix()
            m.postTranslate(-decoded.width / 2f, -decoded.height / 2f)
            m.postRotate(info.third.toFloat())
            m.postScale(bw.toFloat() / sw, bh.toFloat() / sh)
            m.postTranslate(bw / 2f, bh / 2f)
            c.drawBitmap(decoded, m, Paint(Paint.FILTER_BITMAP_FLAG))
            decoded.recycle()
            return out
        }
    }

    override fun pages(edits: DocEdits): List<SourcePage> = pageList
}

/** Plain text, re-flowed with [com.usbprint.app.edit.TextOptions] onto the selected paper. */
class TextSource(
    override val title: String,
    private val text: CharSequence,
    override val files: List<File>,
    override val notice: String? = null,
) : SourceDocument {
    override val kind = DocKind.TEXT
    private var cacheKey: Any? = null
    private var cached: List<SourcePage> = emptyList()

    @Synchronized
    override fun pages(edits: DocEdits): List<SourcePage> {
        val l = edits.layout
        val landscape = l.orientation == Orientation.LANDSCAPE
        val wPt = (if (landscape) l.paper.heightPt else l.paper.widthPt).toFloat()
        val hPt = (if (landscape) l.paper.widthPt else l.paper.heightPt).toFloat()
        val key = Triple(edits.text, wPt, hPt)
        if (key != cacheKey) {
            cached = paginate(edits, wPt, hPt)
            cacheKey = key
        }
        return cached
    }

    private fun paginate(edits: DocEdits, wPt: Float, hPt: Float): List<SourcePage> {
        val o = edits.text
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = o.sizePt * SCALE
            val family = when (o.font) {
                TextFont.SANS -> Typeface.SANS_SERIF
                TextFont.SERIF -> Typeface.SERIF
                TextFont.MONO -> Typeface.MONOSPACE
            }
            typeface = Typeface.create(family, if (o.bold) Typeface.BOLD else Typeface.NORMAL)
        }
        val margin = o.marginMm / 25.4f * 72f * SCALE
        val pageW = wPt * SCALE
        val pageH = hPt * SCALE
        val contentW = max(50f, pageW - 2 * margin).toInt()
        val contentH = max(50f, pageH - 2 * margin)
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, contentW)
            .setAlignment(
                when (o.align) {
                    TextAlign.CENTER -> Layout.Alignment.ALIGN_CENTER
                    TextAlign.END -> Layout.Alignment.ALIGN_OPPOSITE
                    else -> Layout.Alignment.ALIGN_NORMAL
                },
            )
            .setLineSpacing(0f, o.lineSpacing)
            .setIncludePad(false)
            .setJustificationMode(if (o.align == TextAlign.JUSTIFY) Layout.JUSTIFICATION_MODE_INTER_WORD else Layout.JUSTIFICATION_MODE_NONE)
            .build()

        val starts = ArrayList<Int>()
        var line = 0
        while (line < layout.lineCount) {
            starts += line
            val top = layout.getLineTop(line)
            var end = line
            while (end < layout.lineCount && layout.getLineBottom(end) - top <= contentH) {
                // Form feed forces a new page.
                val lineText = text.subSequence(layout.getLineStart(end), layout.getLineEnd(end))
                end++
                if (lineText.contains('\u000C')) break
            }
            line = if (end == line) line + 1 else end
        }
        if (starts.isEmpty()) starts += 0
        return starts.mapIndexed { idx, first ->
            val last = if (idx + 1 < starts.size) starts[idx + 1] else layout.lineCount
            TextPage(layout, first, last, wPt, hPt, margin)
        }
    }

    private class TextPage(
        private val layout: StaticLayout,
        private val first: Int,
        private val endLine: Int,
        override val widthPt: Float,
        override val heightPt: Float,
        private val margin: Float,
    ) : SourcePage {
        override fun render(maxW: Int, maxH: Int): Bitmap {
            val (bw, bh) = fitSize(widthPt, heightPt, maxW, maxH)
            val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            if (layout.lineCount == 0) return bmp
            val c = Canvas(bmp)
            c.scale(bw / (widthPt * SCALE), bh / (heightPt * SCALE))
            val top = layout.getLineTop(first).toFloat()
            val bottom = layout.getLineTop(endLine.coerceAtMost(layout.lineCount)).toFloat()
                .let { if (endLine >= layout.lineCount) layout.height.toFloat() else it }
            c.translate(margin, margin - top)
            c.clipRect(0f, top, layout.width.toFloat(), bottom)
            synchronized(layout) { layout.draw(c) }
            return bmp
        }
    }

    companion object {
        /** Layout units per point. */
        private const val SCALE = 2f
    }
}
