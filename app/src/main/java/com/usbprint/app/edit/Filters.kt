package com.usbprint.app.edit

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Applies a [PageEdit] (except placement) to a rendered page bitmap. */
object Filters {

    fun apply(src: Bitmap, e: PageEdit): Bitmap {
        var bmp = geometry(src, e)
        bmp = tone(bmp, e)
        if (e.hasCleanup) bmp = cleanup(bmp, e)
        return bmp
    }

    /** Rotation then crop / perspective correction. */
    fun geometry(src: Bitmap, e: PageEdit): Bitmap {
        var bmp = src
        val rot = ((e.rotation % 360) + 360) % 360
        if (rot != 0) {
            val m = Matrix().apply { setRotate(rot.toFloat()) }
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        }
        if (!e.crop.isFull) bmp = warp(bmp, e.crop)
        return bmp
    }

    fun warp(src: Bitmap, q: Quad): Bitmap {
        val w = src.width.toFloat()
        val h = src.height.toFloat()
        val p = FloatArray(8) { i -> q.pts[i] * if (i % 2 == 0) w else h }
        val outW = max(8f, (dist(p, 0, 1) + dist(p, 3, 2)) / 2f).roundToInt()
        val outH = max(8f, (dist(p, 0, 3) + dist(p, 1, 2)) / 2f).roundToInt()
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        out.eraseColor(Color.WHITE)
        val m = Matrix()
        val dst = floatArrayOf(0f, 0f, outW.toFloat(), 0f, outW.toFloat(), outH.toFloat(), 0f, outH.toFloat())
        if (!m.setPolyToPoly(p, 0, dst, 0, 4)) return src
        Canvas(out).drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return out
    }

    private fun dist(p: FloatArray, a: Int, b: Int) = hypot(p[a * 2] - p[b * 2], p[a * 2 + 1] - p[b * 2 + 1])

    fun toneMatrix(e: PageEdit): ColorMatrix? {
        if (e.brightness == 0f && e.contrast == 0f && e.saturation == 0f && e.warmth == 0f && !e.grayscale) return null
        val cm = ColorMatrix()
        cm.setSaturation(if (e.grayscale) 0f else 1f + e.saturation)
        if (e.warmth != 0f) {
            val r = 1f + e.warmth * 0.18f
            val b = 1f - e.warmth * 0.18f
            cm.postConcat(ColorMatrix(floatArrayOf(r, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, b, 0f, 0f, 0f, 0f, 0f, 1f, 0f)))
        }
        val c = if (e.contrast >= 0f) 1f + e.contrast * 1.5f else 1f + e.contrast * 0.8f
        val t = 128f * (1f - c) + e.brightness * 100f
        cm.postConcat(ColorMatrix(floatArrayOf(c, 0f, 0f, 0f, t, 0f, c, 0f, 0f, t, 0f, 0f, c, 0f, t, 0f, 0f, 0f, 1f, 0f)))
        return cm
    }

    private fun tone(src: Bitmap, e: PageEdit): Bitmap {
        val cm = toneMatrix(e) ?: return src
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(cm) })
        return out
    }

    /** Document clean-up: background flattening (shadows, wrinkles), whitening, sharpening, denoise, B&W. */
    private fun cleanup(src: Bitmap, e: PageEdit): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)

        val needBg = e.deshadow > 0f || e.bwScan
        val bg = if (needBg) background(src, w, h, e.deshadow) else null

        if (e.denoise > 0f) blend(px, boxBlur(px, w, h), e.denoise * 0.8f)

        val wp = 255f - e.whiten * 70f
        val nearWhite = 255 - (e.whiten * 50f).toInt()
        for (i in px.indices) {
            val c = px[i]
            var r = (c shr 16) and 0xFF
            var g = (c shr 8) and 0xFF
            var b = c and 0xFF
            if (bg != null && e.deshadow > 0f) {
                val l = max(24, bg[i])
                val k = e.deshadow
                r = lerp(r, min(255, r * 255 / l), k)
                g = lerp(g, min(255, g * 255 / l), k)
                b = lerp(b, min(255, b * 255 / l), k)
            }
            if (e.whiten > 0f) {
                r = min(255, (r * 255f / wp).toInt())
                g = min(255, (g * 255f / wp).toInt())
                b = min(255, (b * 255f / wp).toInt())
                if (r > nearWhite && g > nearWhite && b > nearWhite) { r = 255; g = 255; b = 255 }
            }
            px[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }

        if (e.sharpen > 0f) {
            val blur = boxBlur(px, w, h)
            val amt = e.sharpen * 1.6f
            for (i in px.indices) {
                val c = px[i]
                val d = blur[i]
                val r = clamp(((c shr 16) and 0xFF) + (amt * (((c shr 16) and 0xFF) - ((d shr 16) and 0xFF))).toInt())
                val g = clamp(((c shr 8) and 0xFF) + (amt * (((c shr 8) and 0xFF) - ((d shr 8) and 0xFF))).toInt())
                val b = clamp((c and 0xFF) + (amt * ((c and 0xFF) - (d and 0xFF))).toInt())
                px[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        if (e.bwScan) {
            // Adaptive threshold against the local background.
            val local = background(Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888), w, h, 1f)
            for (i in px.indices) {
                val c = px[i]
                val l = (((c shr 16) and 0xFF) * 77 + ((c shr 8) and 0xFF) * 151 + (c and 0xFF) * 28) shr 8
                px[i] = if (l < local[i] * 0.82f) Color.BLACK else Color.WHITE
            }
        }

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    /** Estimates the paper brightness per pixel: downscale, dilate (drop text), blur, upscale. */
    private fun background(src: Bitmap, w: Int, h: Int, strength: Float): IntArray {
        val target = (40 + 40 * (1f - strength)).toInt()
        val s = max(1f, max(w, h) / target.toFloat())
        val sw = max(1, (w / s).roundToInt())
        val sh = max(1, (h / s).roundToInt())
        val small = Bitmap.createScaledBitmap(src, sw, sh, true)
        val sp = IntArray(sw * sh)
        small.getPixels(sp, 0, sw, 0, 0, sw, sh)
        var lum = IntArray(sw * sh) { i ->
            val c = sp[i]
            (((c shr 16) and 0xFF) * 77 + ((c shr 8) and 0xFF) * 151 + (c and 0xFF) * 28) shr 8
        }
        repeat(2) { lum = dilate(lum, sw, sh) }
        repeat(2) { lum = blurGray(lum, sw, sh) }
        val bmp = Bitmap.createBitmap(IntArray(sw * sh) { i -> val v = lum[i]; (0xFF shl 24) or (v shl 16) or (v shl 8) or v }, sw, sh, Bitmap.Config.ARGB_8888)
        val big = Bitmap.createScaledBitmap(bmp, w, h, true)
        val bp = IntArray(w * h)
        big.getPixels(bp, 0, w, 0, 0, w, h)
        for (i in bp.indices) bp[i] = bp[i] and 0xFF
        return bp
    }

    private fun dilate(a: IntArray, w: Int, h: Int): IntArray {
        val o = IntArray(a.size)
        for (y in 0 until h) for (x in 0 until w) {
            var m = 0
            for (dy in -1..1) for (dx in -1..1) {
                val xx = (x + dx).coerceIn(0, w - 1)
                val yy = (y + dy).coerceIn(0, h - 1)
                m = max(m, a[yy * w + xx])
            }
            o[y * w + x] = m
        }
        return o
    }

    private fun blurGray(a: IntArray, w: Int, h: Int): IntArray {
        val o = IntArray(a.size)
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0
            for (dy in -1..1) for (dx in -1..1) s += a[(y + dy).coerceIn(0, h - 1) * w + (x + dx).coerceIn(0, w - 1)]
            o[y * w + x] = s / 9
        }
        return o
    }

    /** 3×3 box blur on ARGB pixels. */
    private fun boxBlur(px: IntArray, w: Int, h: Int): IntArray {
        val o = IntArray(px.size)
        for (y in 0 until h) {
            val y0 = max(0, y - 1) * w
            val y1 = y * w
            val y2 = min(h - 1, y + 1) * w
            for (x in 0 until w) {
                val x0 = max(0, x - 1)
                val x2 = min(w - 1, x + 1)
                var r = 0
                var g = 0
                var b = 0
                for (ri in 0..2) {
                    val row = if (ri == 0) y0 else if (ri == 1) y1 else y2
                    for (ci in 0..2) {
                        val c = px[row + if (ci == 0) x0 else if (ci == 1) x else x2]
                        r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF
                    }
                }
                o[y1 + x] = (0xFF shl 24) or ((r / 9) shl 16) or ((g / 9) shl 8) or (b / 9)
            }
        }
        return o
    }

    private fun blend(dst: IntArray, src: IntArray, k: Float) {
        for (i in dst.indices) {
            val a = dst[i]
            val b = src[i]
            dst[i] = (0xFF shl 24) or
                (lerp((a shr 16) and 0xFF, (b shr 16) and 0xFF, k) shl 16) or
                (lerp((a shr 8) and 0xFF, (b shr 8) and 0xFF, k) shl 8) or
                lerp(a and 0xFF, b and 0xFF, k)
        }
    }

    private fun lerp(a: Int, b: Int, k: Float) = (a + (b - a) * k).toInt()
    private fun clamp(v: Int) = if (v < 0) 0 else if (v > 255) 255 else v

    /** Heuristic: does this look like a photographed document that would benefit from clean-up? */
    fun looksLikeScan(bmp: Bitmap): Boolean {
        val s = Bitmap.createScaledBitmap(bmp, 48, 48, true)
        val p = IntArray(48 * 48)
        s.getPixels(p, 0, 48, 0, 0, 48, 48)
        var sat = 0L
        var minL = 255
        var maxL = 0
        for (c in p) {
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            sat += max(r, max(g, b)) - min(r, min(g, b))
            val l = (r * 77 + g * 151 + b * 28) shr 8
            minL = min(minL, l); maxL = max(maxL, l)
        }
        // Low colour, and the "white" paper isn't actually white (shadows / grey cast).
        return sat / p.size < 40 && maxL < 235 && maxL - minL > 60
    }
}
