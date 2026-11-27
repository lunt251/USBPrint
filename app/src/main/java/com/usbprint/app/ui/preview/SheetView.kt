package com.usbprint.app.ui.preview

import android.graphics.Bitmap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import com.usbprint.app.settings.Units
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.usbprint.app.edit.Filters
import com.usbprint.app.edit.PageEdit
import com.usbprint.app.edit.Quad
import com.usbprint.app.render.SheetComposer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/** Formats a length in points as cm or inches. */
fun formatLength(pt: Float, units: Units): String = when (units) {
    Units.CM -> "%.1f cm".format(pt / 72f * 2.54f)
    Units.INCH -> "%.2f in".format(pt / 72f)
}

/**
 * One sheet, centered, with a paper shadow. Pinch (two fingers, or one finger when [singleFingerPan])
 * scales/moves the first page on the sheet, snapping to "fit" and to the center. While moving, the
 * content gets a dotted outline; [showDimensions] draws dimension lines for paper and content.
 */
@Composable
fun SheetView(
    composer: SheetComposer,
    sheet: Int,
    compare: Boolean,
    singleFingerPan: Boolean,
    onGestureStart: () -> Unit,
    onTransform: (PageEdit) -> Unit,
    modifier: Modifier = Modifier,
    showDimensions: Boolean = false,
    units: Units = Units.CM,
    outline: Boolean = false,
) {
    val dimPad = if (showDimensions) 40.dp else 16.dp
    BoxWithConstraints(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val density = LocalDensity.current
        val padPx = with(density) { dimPad.toPx() }
        val (sw, sh) = composer.sheetSize(sheet)
        val maxWpx = constraints.maxWidth - 2 * padPx
        val maxHpx = constraints.maxHeight - 2 * padPx
        val s = min(maxWpx / sw, maxHpx / sh).coerceAtLeast(0.01f)
        val wPx = (sw * s).toInt().coerceAtLeast(1)
        val hPx = (sh * s).toInt().coerceAtLeast(1)

        // Keep the last frame on screen while the next one renders.
        val holder = remember { arrayOfNulls<Bitmap>(1) }
        val bmp by produceState(holder[0], composer, sheet, compare, wPx, hPx) {
            value = withContext(Dispatchers.Default) { composer.renderSheet(sheet, wPx, hPx, compare) }
            holder[0] = value
        }

        val haptics = LocalHapticFeedback.current
        val currentComposer by rememberUpdatedState(composer)
        var moving by remember { mutableStateOf(false) }
        val outlineAlpha by animateFloatAsState(
            if (moving || outline) 1f else 0f,
            animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
            label = "outline",
        )
        val pageIndex = composer.sheetPages(sheet).firstOrNull()
        val cellFrac = composer.cells(sheet).firstOrNull()?.let { it.rect.width() / sw to it.rect.height() / sh } ?: (1f to 1f)

        val gestureModifier = if (pageIndex == null) Modifier else Modifier.pointerInput(sheet, singleFingerPan) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                var started = false
                var raw = currentComposer.edits.page(pageIndex)
                var snappedScale = abs(raw.scale - 1f) < 0.001f
                var snappedCenter = raw.offsetX == 0f && raw.offsetY == 0f
                do {
                    val event = awaitPointerEvent()
                    val fingers = event.changes.count { it.pressed }
                    if (fingers >= 2 || (singleFingerPan && fingers == 1)) {
                        val zoom = event.calculateZoom()
                        val pan = event.calculatePan()
                        if (zoom != 1f || pan != Offset.Zero) {
                            if (!started) {
                                started = true
                                moving = true
                                onGestureStart()
                            }
                            raw = raw.copy(
                                scale = (raw.scale * zoom).coerceIn(0.1f, 5f),
                                offsetX = (raw.offsetX + pan.x / (wPx * cellFrac.first)).coerceIn(-1.5f, 1.5f),
                                offsetY = (raw.offsetY + pan.y / (hPx * cellFrac.second)).coerceIn(-1.5f, 1.5f),
                            )
                            var shown = raw
                            val nearFit = abs(raw.scale - 1f) < 0.06f
                            if (nearFit) shown = shown.copy(scale = 1f)
                            val nearCenter = abs(raw.offsetX) < 0.025f && abs(raw.offsetY) < 0.025f
                            if (nearCenter) shown = shown.copy(offsetX = 0f, offsetY = 0f)
                            if ((nearFit && !snappedScale) || (nearCenter && !snappedCenter)) {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                            snappedScale = nearFit
                            snappedCenter = nearCenter
                            onTransform(shown)
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    }
                } while (event.changes.any { it.pressed })
                moving = false
            }
        }

        Surface(
            modifier = Modifier.size(with(density) { wPx.toDp() }, with(density) { hPx.toDp() }).then(gestureModifier),
            shape = RoundedCornerShape(2.dp),
            shadowElevation = 3.dp,
            color = Color.White,
        ) {
            bmp?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize()) }
        }

        val placement = composer.placement(sheet)
        val primary = MaterialTheme.colorScheme.primary
        val lineColor = MaterialTheme.colorScheme.onSurfaceVariant
        val measurer = rememberTextMeasurer()
        val labelStyle = MaterialTheme.typography.labelSmall
        // Labels sit on the preview backdrop (surfaceContainer) and mask the line behind them.
        val labelBg = MaterialTheme.colorScheme.surfaceContainer
        Canvas(Modifier.fillMaxSize()) {
            val ox = (size.width - wPx) / 2f
            val oy = (size.height - hPx) / 2f
            val content = placement?.second?.let { r -> Rect(ox + r.left * s, oy + r.top * s, ox + r.right * s, oy + r.bottom * s) }

            if (content != null && outlineAlpha > 0f) {
                drawRect(
                    primary.copy(alpha = outlineAlpha),
                    topLeft = content.topLeft, size = content.size,
                    style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))),
                )
            }
            if (!showDimensions) return@Canvas
            val gap = 14.dp.toPx()
            val ext = 4.dp.toPx()
            fun label(text: String, center: Offset, vertical: Boolean, color: Color) {
                val m = measurer.measure(text, labelStyle.copy(color = color))
                val pad = 4.dp.toPx()
                rotate(if (vertical) -90f else 0f, pivot = center) {
                    drawRect(
                        labelBg,
                        topLeft = Offset(center.x - m.size.width / 2f - pad, center.y - m.size.height / 2f),
                        size = Size(m.size.width + 2 * pad, m.size.height.toFloat()),
                    )
                    drawText(m, topLeft = Offset(center.x - m.size.width / 2f, center.y - m.size.height / 2f))
                }
            }
            fun hDim(x1: Float, x2: Float, y: Float, fromY: Float, text: String, color: Color) {
                val w = 1.dp.toPx()
                drawLine(color, Offset(x1, fromY), Offset(x1, y + if (y > fromY) ext else -ext), w)
                drawLine(color, Offset(x2, fromY), Offset(x2, y + if (y > fromY) ext else -ext), w)
                drawLine(color, Offset(x1, y), Offset(x2, y), w)
                val t = 3.dp.toPx()
                drawLine(color, Offset(x1 - t, y + t), Offset(x1 + t, y - t), w * 1.5f)
                drawLine(color, Offset(x2 - t, y + t), Offset(x2 + t, y - t), w * 1.5f)
                label(text, Offset((x1 + x2) / 2f, y), false, color)
            }
            fun vDim(y1: Float, y2: Float, x: Float, fromX: Float, text: String, color: Color) {
                val w = 1.dp.toPx()
                drawLine(color, Offset(fromX, y1), Offset(x + if (x > fromX) ext else -ext, y1), w)
                drawLine(color, Offset(fromX, y2), Offset(x + if (x > fromX) ext else -ext, y2), w)
                drawLine(color, Offset(x, y1), Offset(x, y2), w)
                val t = 3.dp.toPx()
                drawLine(color, Offset(x - t, y1 + t), Offset(x + t, y1 - t), w * 1.5f)
                drawLine(color, Offset(x - t, y2 + t), Offset(x + t, y2 - t), w * 1.5f)
                label(text, Offset(x, (y1 + y2) / 2f), true, color)
            }
            // Paper: top and left.
            hDim(ox, ox + wPx, oy - gap, oy - 2.dp.toPx(), formatLength(sw, units), lineColor)
            vDim(oy, oy + hPx, ox - gap, ox - 2.dp.toPx(), formatLength(sh, units), lineColor)
            // Content: bottom and right, measured from the content edges.
            if (content != null && placement != null) {
                val r = placement.second
                hDim(content.left, content.right, oy + hPx + gap, minOf(content.bottom, oy + hPx) + 2.dp.toPx(), formatLength(r.width(), units), primary)
                vDim(content.top, content.bottom, ox + wPx + gap, minOf(content.right, ox + wPx) + 2.dp.toPx(), formatLength(r.height(), units), primary)
            }
        }
    }
}

/** Crop / perspective editor: the page without crop, with four draggable corners. */
@Composable
fun CropView(
    composer: SheetComposer,
    page: Int,
    edit: PageEdit,
    perspective: Boolean,
    onStart: () -> Unit,
    onQuad: (Quad) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
        val maxW = constraints.maxWidth
        val maxH = constraints.maxHeight
        val geometryOnly = edit.copy(crop = Quad.FULL)
        val bmp by produceState<Bitmap?>(null, composer, page, geometryOnly.rotation, maxW, maxH) {
            value = withContext(Dispatchers.Default) {
                val src = composer.processed(page, PageEdit.DEFAULT, maxOf(maxW, maxH))
                Filters.geometry(src, PageEdit.DEFAULT.copy(rotation = edit.rotation))
            }
        }
        val b = bmp ?: return@BoxWithConstraints
        val s = min(maxW / b.width.toFloat(), maxH / b.height.toFloat())
        val w = b.width * s
        val h = b.height * s
        val density = LocalDensity.current
        val primary = MaterialTheme.colorScheme.primary
        val quad by rememberUpdatedState(edit.crop)
        var active by remember { mutableStateOf(-1) }

        Box(Modifier.size(with(density) { w.toDp() }, with(density) { h.toDp() })) {
            Image(b.asImageBitmap(), null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
            Canvas(
                Modifier.fillMaxSize().pointerInput(perspective) {
                    detectDragGestures(
                        onDragStart = { pos ->
                            val p = quad.pts
                            var best = -1
                            var bestD = Float.MAX_VALUE
                            for (k in 0 until 4) {
                                val d = hypot(p[k * 2] * w - pos.x, p[k * 2 + 1] * h - pos.y)
                                if (d < bestD) { bestD = d; best = k }
                            }
                            active = if (bestD < 56.dp.toPx()) best else -1
                            if (active >= 0) onStart()
                        },
                        onDragEnd = { active = -1 },
                        onDragCancel = { active = -1 },
                    ) { change, drag ->
                        val k = active
                        if (k < 0) return@detectDragGestures
                        change.consume()
                        val p = quad.pts.copyOf()
                        val nx = (p[k * 2] + drag.x / w).coerceIn(0f, 1f)
                        val ny = (p[k * 2 + 1] + drag.y / h).coerceIn(0f, 1f)
                        if (perspective) {
                            p[k * 2] = nx; p[k * 2 + 1] = ny
                        } else {
                            // Keep a rectangle: corners share x/y with their neighbours.
                            var l = p[0]; var t = p[1]; var r = p[2]; var bt = p[5]
                            when (k) {
                                0 -> { l = nx; t = ny }
                                1 -> { r = nx; t = ny }
                                2 -> { r = nx; bt = ny }
                                3 -> { l = nx; bt = ny }
                            }
                            if (r - l < 0.05f || bt - t < 0.05f) return@detectDragGestures
                            p[0] = l; p[1] = t; p[2] = r; p[3] = t; p[4] = r; p[5] = bt; p[6] = l; p[7] = bt
                        }
                        onQuad(Quad(p))
                    }
                },
            ) {
                val p = quad.pts
                val path = Path().apply {
                    moveTo(p[0] * w, p[1] * h)
                    for (k in 1 until 4) lineTo(p[k * 2] * w, p[k * 2 + 1] * h)
                    close()
                }
                // Dim outside the crop.
                val outside = Path().apply {
                    addRect(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height))
                    addPath(path)
                    fillType = androidx.compose.ui.graphics.PathFillType.EvenOdd
                }
                drawPath(outside, Color.Black.copy(alpha = 0.45f))
                drawPath(path, primary, style = Stroke(width = 2.dp.toPx()))
                for (k in 0 until 4) {
                    val c = Offset(p[k * 2] * w, p[k * 2 + 1] * h)
                    drawCircle(Color.White, radius = 11.dp.toPx(), center = c)
                    drawCircle(primary, radius = 8.dp.toPx(), center = c)
                }
            }
        }
    }
}
