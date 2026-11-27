package com.usbprint.app.ui.common

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.ContentScale
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.navigation.NavBackStackEntry

// M3 motion tokens (easing and duration), used for screen-level transitions.
val EmphasizedEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
const val CONTAINER_TRANSFORM_MS = 450

@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedScope = compositionLocalOf<SharedTransitionScope?> { null }
val LocalNavAnimScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

/**
 * M3 container transform. Both ends share bounds on one emphasized curve.
 * - The small end ([isSource], the tile) re-measures: its icon and label stay at their real size and are
 *   only visible near the small end of the motion, so they never blow up to screen size.
 * - The large end (the screen) is laid out once at full size and scaled, so nothing reflows mid-flight.
 * Contents fade through: the outgoing content leaves in the first ~20%, the incoming fades in after it.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.containerTransform(key: String, shape: Shape, isSource: Boolean = false): Modifier {
    val shared = LocalSharedScope.current ?: return this
    val anim = LocalNavAnimScope.current ?: return this
    val outMs = CONTAINER_TRANSFORM_MS / 5
    return with(shared) {
        this@containerTransform.sharedBounds(
            sharedContentState = rememberSharedContentState(key),
            animatedVisibilityScope = anim,
            enter = if (isSource) {
                // Closing: the tile's container returns at once so the shape never disappears; its icon
                // and label are held back separately with Modifier.lateContent().
                fadeIn(tween(outMs, delayMillis = outMs / 2, easing = LinearOutSlowInEasing))
            } else {
                fadeIn(tween(CONTAINER_TRANSFORM_MS * 3 / 5, delayMillis = outMs, easing = LinearOutSlowInEasing))
            },
            exit = fadeOut(tween(outMs, easing = FastOutLinearInEasing)),
            boundsTransform = { _, _ -> tween(CONTAINER_TRANSFORM_MS, easing = EmphasizedEasing) },
            resizeMode = if (isSource) SharedTransitionScope.ResizeMode.RemeasureToBounds
            else SharedTransitionScope.ResizeMode.ScaleToBounds(ContentScale.Crop, Alignment.Center),
            clipInOverlayDuringTransition = OverlayClip(shape),
        )
    }
}

/**
 * Screen-level transitions around a container transform: the shared bounds carry all the motion, the
 * screen underneath stays put (M3: the outgoing screen remains static beneath the container).
 */
val containerEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition =
    { EnterTransition.None }
val containerExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition =
    { ExitTransition.KeepUntilTransitionsFinished }

/** M3 shared axis X for forward/back navigation between related screens. */
val axisEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition =
    { slideInHorizontally(tween(400, easing = EmphasizedDecelerate)) { it / 6 } + fadeIn(tween(250, delayMillis = 80)) }
val axisExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition =
    { slideOutHorizontally(tween(250, easing = EmphasizedAccelerate)) { -it / 6 } + fadeOut(tween(150)) }
val axisPopEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition =
    { slideInHorizontally(tween(400, easing = EmphasizedDecelerate)) { -it / 6 } + fadeIn(tween(250, delayMillis = 80)) }
val axisPopExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition =
    { slideOutHorizontally(tween(250, easing = EmphasizedAccelerate)) { it / 6 } + fadeOut(tween(150)) }

/** Tile content inside a container transform: appears only near the end of the close, at real size. */
@Composable
fun Modifier.lateContent(): Modifier {
    val anim = LocalNavAnimScope.current ?: return this
    return with(anim) {
        this@lateContent.animateEnterExit(
            enter = fadeIn(tween(CONTAINER_TRANSFORM_MS / 3, delayMillis = CONTAINER_TRANSFORM_MS * 2 / 3, easing = LinearOutSlowInEasing)),
            exit = fadeOut(tween(CONTAINER_TRANSFORM_MS / 5, easing = FastOutLinearInEasing)),
        )
    }
}
