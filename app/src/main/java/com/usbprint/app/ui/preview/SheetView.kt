package com.usbprint.app.ui.preview

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
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

/**
 * One sheet, centered, with a paper shadow. Pinch (two fingers, or one finger when [singleFingerPan])
 * scales/moves the first page on the sheet, snapping to "fit" and to the center.
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
) {
    BoxWithConstraints(modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
        val (sw, sh) = composer.sheetSize(sheet)
        val maxWpx = constraints.maxWidth.toFloat()
        val maxHpx = constraints.maxHeight.toFloat()
        val s = min(maxWpx / sw, maxHpx / sh)
        val wPx = (sw * s).toInt().coerceAtLeast(1)
        val hPx = (sh * s).toInt().coerceAtLeast(1)
        val density = LocalDensity.current

        // Keep the last frame on screen while the next one renders.
        val holder = remember { arrayOfNulls<Bitmap>(1) }
        val bmp by produceState(holder[0], composer, sheet, compare, wPx, hPx) {
            value = withContext(Dispatchers.Default) { composer.renderSheet(sheet, wPx, hPx, compare) }
            holder[0] = value
        }

        val haptics = LocalHapticFeedback.current
        val currentComposer by rememberUpdatedState(composer)
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
                                onGestureStart()
                            }
                            raw = raw.copy(
                                scale = (raw.scale * zoom).coerceIn(0.2f, 5f),
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
            }
        }

        Surface(
            modifier = Modifier.size(with(density) { wPx.toDp() }, with(density) { hPx.toDp() }).then(gestureModifier),
            shape = RoundedCornerShape(2.dp),
            shadowElevation = 6.dp,
            color = Color.White,
        ) {
            bmp?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize()) }
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
