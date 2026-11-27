package com.usbprint.app.edit

import com.usbprint.core.print.PaperSize
import org.json.JSONArray
import org.json.JSONObject

/** Four corners in normalized source coordinates (0..1), clockwise from top-left. */
data class Quad(val pts: FloatArray = FULL_PTS) {
    val isFull get() = pts.contentEquals(FULL_PTS)
    val isRect get() = pts[0] == pts[6] && pts[1] == pts[3] && pts[2] == pts[4] && pts[5] == pts[7]
    override fun equals(other: Any?) = other is Quad && other.pts.contentEquals(pts)
    override fun hashCode() = pts.contentHashCode()

    companion object {
        val FULL_PTS = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)
        val FULL = Quad()
    }
}

/** Per-page edits. All tone values are -1..1 (0 = unchanged), clean-up values 0..1. */
data class PageEdit(
    val crop: Quad = Quad.FULL,
    val rotation: Int = 0,
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val warmth: Float = 0f,
    val grayscale: Boolean = false,
    val whiten: Float = 0f,
    val deshadow: Float = 0f,
    val sharpen: Float = 0f,
    val denoise: Float = 0f,
    val bwScan: Boolean = false,
    /** Extra zoom on top of fit/fill, and offset as a fraction of the cell size. */
    val scale: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
) {
    val hasPixelEdits get() = this.copy(scale = 1f, offsetX = 0f, offsetY = 0f) != DEFAULT
    val hasCleanup get() = whiten > 0f || deshadow > 0f || sharpen > 0f || denoise > 0f || bwScan

    /** Copy everything except the page-specific crop/placement. */
    fun styleFrom(o: PageEdit) = copy(
        brightness = o.brightness, contrast = o.contrast, saturation = o.saturation, warmth = o.warmth,
        grayscale = o.grayscale, whiten = o.whiten, deshadow = o.deshadow, sharpen = o.sharpen,
        denoise = o.denoise, bwScan = o.bwScan, rotation = o.rotation, scale = o.scale,
        offsetX = o.offsetX, offsetY = o.offsetY,
    )

    companion object {
        val DEFAULT = PageEdit()
        val ENHANCE_SCAN = DEFAULT.copy(whiten = 0.5f, deshadow = 0.7f, sharpen = 0.3f, contrast = 0.1f)
    }
}

enum class Orientation { AUTO, PORTRAIT, LANDSCAPE }
enum class FitMode { FIT, FILL }
enum class PhotoMode { ONE, GRID, ID }

enum class IdSize(val label: String, val widthMm: Float, val heightMm: Float) {
    PASSPORT_EU("35 × 45 mm", 35f, 45f),
    US_2X2("2 × 2 in", 50.8f, 50.8f),
    SMALL("25 × 35 mm", 25f, 35f),
}

data class LayoutSettings(
    val paper: PaperSize = PaperSize.A4,
    val orientation: Orientation = Orientation.AUTO,
    val fit: FitMode = FitMode.FIT,
    val marginMm: Float = 5f,
    val pagesPerSheet: Int = 1,
    val borderless: Boolean = false,
    val photoMode: PhotoMode = PhotoMode.ONE,
    val gridCount: Int = 4,
    val idSize: IdSize = IdSize.PASSPORT_EU,
)

data class PrintSettings(
    val copies: Int = 1,
    val pageRange: String = "",
    /** -1 = printer default. */
    val darkness: Int = -1,
    val tonerSave: Boolean = false,
)

enum class TextFont { SANS, SERIF, MONO }
enum class TextAlign { START, CENTER, END, JUSTIFY }

data class TextOptions(
    val font: TextFont = TextFont.SANS,
    val sizePt: Float = 11f,
    val lineSpacing: Float = 1.2f,
    val marginMm: Float = 20f,
    val align: TextAlign = TextAlign.START,
    val bold: Boolean = false,
)

data class DocEdits(
    val layout: LayoutSettings = LayoutSettings(),
    val print: PrintSettings = PrintSettings(),
    val text: TextOptions = TextOptions(),
    val pages: Map<Int, PageEdit> = emptyMap(),
) {
    fun page(i: Int) = pages[i] ?: PageEdit.DEFAULT
    fun withPage(i: Int, e: PageEdit) = copy(pages = if (e == PageEdit.DEFAULT) pages - i else pages + (i to e))

    fun toJson(): JSONObject = JSONObject().apply {
        put("layout", JSONObject().apply {
            put("paper", layout.paper.name); put("orientation", layout.orientation.name); put("fit", layout.fit.name)
            put("margin", layout.marginMm.toDouble()); put("pps", layout.pagesPerSheet); put("borderless", layout.borderless)
            put("photoMode", layout.photoMode.name); put("grid", layout.gridCount); put("id", layout.idSize.name)
        })
        put("print", JSONObject().apply {
            put("copies", print.copies); put("range", print.pageRange); put("darkness", print.darkness); put("tonerSave", print.tonerSave)
        })
        put("text", JSONObject().apply {
            put("font", text.font.name); put("size", text.sizePt.toDouble()); put("spacing", text.lineSpacing.toDouble())
            put("margin", text.marginMm.toDouble()); put("align", text.align.name); put("bold", text.bold)
        })
        put("pages", JSONArray().apply {
            pages.forEach { (i, e) ->
                put(JSONObject().apply {
                    put("i", i)
                    put("crop", JSONArray().apply { e.crop.pts.forEach { put(it.toDouble()) } })
                    put("rot", e.rotation)
                    put("b", e.brightness.toDouble()); put("c", e.contrast.toDouble()); put("s", e.saturation.toDouble())
                    put("w", e.warmth.toDouble()); put("gray", e.grayscale); put("wh", e.whiten.toDouble())
                    put("ds", e.deshadow.toDouble()); put("sh", e.sharpen.toDouble()); put("dn", e.denoise.toDouble())
                    put("bw", e.bwScan); put("sc", e.scale.toDouble()); put("ox", e.offsetX.toDouble()); put("oy", e.offsetY.toDouble())
                })
            }
        })
    }

    companion object {
        private inline fun <reified T : Enum<T>> JSONObject.enum(key: String, def: T): T =
            optString(key).let { s -> enumValues<T>().firstOrNull { it.name == s } } ?: def

        private fun JSONObject.f(key: String, def: Float) = optDouble(key, def.toDouble()).toFloat()

        fun fromJson(o: JSONObject): DocEdits {
            val d = DocEdits()
            val l = o.optJSONObject("layout") ?: JSONObject()
            val p = o.optJSONObject("print") ?: JSONObject()
            val t = o.optJSONObject("text") ?: JSONObject()
            val pages = HashMap<Int, PageEdit>()
            o.optJSONArray("pages")?.let { arr ->
                for (k in 0 until arr.length()) {
                    val e = arr.getJSONObject(k)
                    val c = e.optJSONArray("crop")
                    val quad = if (c != null && c.length() == 8) Quad(FloatArray(8) { c.getDouble(it).toFloat() }) else Quad.FULL
                    pages[e.getInt("i")] = PageEdit(
                        crop = quad, rotation = e.optInt("rot"), brightness = e.f("b", 0f), contrast = e.f("c", 0f),
                        saturation = e.f("s", 0f), warmth = e.f("w", 0f), grayscale = e.optBoolean("gray"),
                        whiten = e.f("wh", 0f), deshadow = e.f("ds", 0f), sharpen = e.f("sh", 0f), denoise = e.f("dn", 0f),
                        bwScan = e.optBoolean("bw"), scale = e.f("sc", 1f), offsetX = e.f("ox", 0f), offsetY = e.f("oy", 0f),
                    )
                }
            }
            return DocEdits(
                layout = LayoutSettings(
                    paper = l.enum("paper", d.layout.paper), orientation = l.enum("orientation", d.layout.orientation),
                    fit = l.enum("fit", d.layout.fit), marginMm = l.f("margin", d.layout.marginMm),
                    pagesPerSheet = l.optInt("pps", 1), borderless = l.optBoolean("borderless"),
                    photoMode = l.enum("photoMode", d.layout.photoMode), gridCount = l.optInt("grid", 4),
                    idSize = l.enum("id", d.layout.idSize),
                ),
                print = PrintSettings(
                    copies = p.optInt("copies", 1), pageRange = p.optString("range"),
                    darkness = p.optInt("darkness", -1), tonerSave = p.optBoolean("tonerSave"),
                ),
                text = TextOptions(
                    font = t.enum("font", d.text.font), sizePt = t.f("size", d.text.sizePt),
                    lineSpacing = t.f("spacing", d.text.lineSpacing), marginMm = t.f("margin", d.text.marginMm),
                    align = t.enum("align", d.text.align), bold = t.optBoolean("bold"),
                ),
                pages = pages,
            )
        }
    }
}

/** Parses "1-3, 5, 8-" into zero-based indices; empty means all pages. */
fun parsePageRange(spec: String, pageCount: Int): List<Int> {
    if (spec.isBlank()) return (0 until pageCount).toList()
    if (spec.trim() == "0") return emptyList()
    val out = LinkedHashSet<Int>()
    for (part in spec.split(',', ';', ' ').map { it.trim() }.filter { it.isNotEmpty() }) {
        val dash = part.indexOf('-')
        if (dash < 0) {
            part.toIntOrNull()?.let { if (it in 1..pageCount) out += it - 1 }
        } else {
            val a = part.substring(0, dash).trim().toIntOrNull() ?: 1
            val b = part.substring(dash + 1).trim().toIntOrNull() ?: pageCount
            for (n in a.coerceAtLeast(1)..b.coerceAtMost(pageCount)) out += n - 1
        }
    }
    return if (out.isEmpty()) (0 until pageCount).toList() else out.toList()
}
