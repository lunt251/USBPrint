package com.usbprint.app.ui.common

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
    radius: Dp = 28.dp,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(radius),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 3.dp,
        shadowElevation = 3.dp,
        content = content,
    )
}
