package com.usbprint.app.ui.common

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.usbprint.app.ui.theme.LocalLed

enum class Led { READY, BUSY, ERROR, IDLE }

/** Wraps text in Unicode first-strong isolates so Arabic/Hebrew names don't scramble ".pdf". */
fun bidi(text: String) = "\u2068$text\u2069"

/** The printer's status light, used wherever printer state is shown. Only blinks when something is live. */
@Composable
fun StatusLed(state: Led, modifier: Modifier = Modifier, size: Dp = 8.dp, live: Boolean = false) {
    val c = LocalLed.current
    val color = when (state) {
        Led.READY -> c.ready
        Led.BUSY -> c.busy
        Led.ERROR -> c.error
        Led.IDLE -> c.idle
    }
    val a = if (live) {
        val t = rememberInfiniteTransition(label = "led")
        t.animateFloat(1f, 0.35f, infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "led-a").value
    } else 1f
    Box(modifier.size(size).alpha(a)) {
        Canvas(Modifier.size(size)) { drawCircle(color) }
    }
}

/** Floating island: raised surface, hairline, soft neutral shadow. */
@Composable
fun Island(
    modifier: Modifier = Modifier,
    radius: Dp = 24.dp,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(radius),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
        shadowElevation = 8.dp,
        content = content,
    )
}

/**
 * The printer, drawn in code: a body with a soft top light, an output slot, the status LED,
 * and a sheet that feeds out while [feeding] is true.
 */
@Composable
fun PrinterArt(state: Led, feeding: Boolean, modifier: Modifier = Modifier, dimmed: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    val led = LocalLed.current
    val ledColor = when (state) {
        Led.READY -> led.ready
        Led.BUSY -> led.busy
        Led.ERROR -> led.error
        Led.IDLE -> led.idle
    }
    val feed by if (feeding) {
        rememberInfiniteTransition(label = "feed").animateFloat(
            0f, 1f, infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Restart), label = "feed-p",
        )
    } else {
        androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(0.55f) }
    }
    val blink by rememberInfiniteTransition(label = "blink").animateFloat(
        1f, if (state == Led.BUSY || dimmed) 0.3f else 1f,
        infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "blink-a",
    )
    val bodyTop = lerp(scheme.surfaceContainerHighest, Color.White, 0.22f)
    val bodyBottom = lerp(scheme.surfaceContainerHighest, Color.White, 0.06f)
    val slot = scheme.surfaceContainerLowest

    Canvas(modifier.alpha(if (dimmed) 0.8f else 1f)) {
        val w = size.width
        val h = size.height
        // Paper waiting in the rear tray.
        drawRoundRect(Color(0xFFF4F1EA), Offset(w * 0.24f, 0f), Size(w * 0.52f, h * 0.30f), CornerRadius(w * 0.02f))
        // Body.
        val bodyY = h * 0.18f
        val bodyH = h * 0.52f
        drawRoundRect(
            Brush.verticalGradient(listOf(bodyTop, bodyBottom), startY = bodyY, endY = bodyY + bodyH),
            Offset(0f, bodyY), Size(w, bodyH), CornerRadius(w * 0.08f),
        )
        // Hairline highlight on the top edge.
        drawRoundRect(Color.White.copy(alpha = 0.22f), Offset(w * 0.04f, bodyY + 1.5f), Size(w * 0.92f, 2f), CornerRadius(1f))
        // Output slot.
        val slotY = bodyY + bodyH * 0.62f
        drawRoundRect(slot, Offset(w * 0.18f, slotY), Size(w * 0.64f, bodyH * 0.12f), CornerRadius(4f))
        // LED.
        drawCircle(ledColor.copy(alpha = blink), radius = w * 0.028f, center = Offset(w * 0.86f, bodyY + bodyH * 0.28f))
        // Sheet coming out of the slot, clipped to below the slot.
        val sheetTop = slotY + bodyH * 0.06f
        clipRect(top = sheetTop, bottom = h) {
            val travel = h - sheetTop
            val y = sheetTop - travel + travel * feed
            drawRoundRect(Color(0xFFFBFAF7), Offset(w * 0.24f, y), Size(w * 0.52f, travel), CornerRadius(w * 0.015f))
            // A few printed lines on the sheet.
            for (i in 0 until 4) {
                val ly = y + travel * (0.25f + i * 0.17f)
                drawRoundRect(Color(0xFF2B2A27).copy(alpha = 0.35f), Offset(w * 0.30f, ly), Size(w * (if (i == 3) 0.22f else 0.40f), 3f), CornerRadius(1.5f))
            }
        }
    }
}
