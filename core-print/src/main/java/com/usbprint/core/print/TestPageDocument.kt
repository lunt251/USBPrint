package com.usbprint.core.print

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface

/** A one-page diagnostic page: text, grey ramp, fine lines, circles and a border. */
class TestPageDocument(private val lines: List<String>) : PrintDocument {

    override val pageCount = 1

    override fun drawPage(index: Int, canvas: Canvas, widthPx: Int, heightPx: Int) {
        val u = widthPx / 100f // 1% of page width
        val margin = 6 * u
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }

        // border
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 0.4f * u
        canvas.drawRect(margin, margin, widthPx - margin, heightPx - margin, paint)

        // title
        paint.style = Paint.Style.FILL
        paint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        paint.textSize = 6 * u
        var y = margin + 10 * u
        canvas.drawText("USBPrint test page", margin + 4 * u, y, paint)

        // info lines
        paint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        paint.textSize = 2.6f * u
        y += 6 * u
        for (line in lines) {
            canvas.drawText(line, margin + 4 * u, y, paint)
            y += 4 * u
        }

        // grey ramp
        y += 4 * u
        val rampTop = y
        val rampBottom = y + 8 * u
        val left = margin + 4 * u
        val right = widthPx - margin - 4 * u
        val ramp = Paint().apply {
            shader = LinearGradient(left, 0f, right, 0f, Color.BLACK, Color.WHITE, Shader.TileMode.CLAMP)
        }
        canvas.drawRect(left, rampTop, right, rampBottom, ramp)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 0.2f * u
        canvas.drawRect(left, rampTop, right, rampBottom, paint)

        // grey steps
        y = rampBottom + 3 * u
        val steps = 10
        val stepW = (right - left) / steps
        paint.style = Paint.Style.FILL
        for (i in 0 until steps) {
            val v = (255 * i / (steps - 1))
            paint.color = Color.rgb(v, v, v)
            canvas.drawRect(left + i * stepW, y, left + (i + 1) * stepW, y + 6 * u, paint)
        }
        paint.color = Color.BLACK

        // fine lines
        y += 10 * u
        paint.style = Paint.Style.STROKE
        for (i in 0 until 12) {
            paint.strokeWidth = (i + 1) * 0.05f * u
            val x = left + i * 3 * u
            canvas.drawLine(x, y, x, y + 14 * u, paint)
        }

        // circles
        val cx = right - 14 * u
        val cy = y + 7 * u
        for (i in 1..5) {
            paint.strokeWidth = 0.25f * u
            canvas.drawCircle(cx, cy, i * 2.6f * u, paint)
        }

        // corner marks
        paint.strokeWidth = 0.3f * u
        val m = margin + 2 * u
        val l = 4 * u
        for ((x, yy) in listOf(m to m, widthPx - m to m, m to heightPx - m, widthPx - m to heightPx - m)) {
            canvas.drawLine(x - l, yy, x + l, yy, paint)
            canvas.drawLine(x, yy - l, x, yy + l, paint)
        }

        paint.style = Paint.Style.FILL
        paint.textSize = 2.4f * u
        canvas.drawText("If everything above looks right, USB printing works.", left, heightPx - margin - 6 * u, paint)
    }
}
