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

/** Container transform: the element with this key morphs into the matching element on the next screen. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.containerTransform(key: String, shape: Shape): Modifier {
    val shared = LocalSharedScope.current ?: return this
    val anim = LocalNavAnimScope.current ?: return this
    return with(shared) {
        this@containerTransform.sharedBounds(
            sharedContentState = rememberSharedContentState(key),
            animatedVisibilityScope = anim,
            // Fade-through: the tile content leaves early, the screen arrives once the bounds are mostly there.
            enter = fadeIn(tween(CONTAINER_TRANSFORM_MS * 3 / 5, delayMillis = CONTAINER_TRANSFORM_MS / 4, easing = LinearOutSlowInEasing)),
            exit = fadeOut(tween(CONTAINER_TRANSFORM_MS / 4, easing = FastOutLinearInEasing)),
            // Position and size move together on one emphasized curve (centre travels while it scales).
            boundsTransform = { _, _ -> tween(CONTAINER_TRANSFORM_MS, easing = EmphasizedEasing) },
            // Scale, don't re-measure: the screen is laid out once at full size, so nothing reflows mid-animation.
            resizeMode = SharedTransitionScope.ResizeMode.ScaleToBounds(ContentScale.Crop, Alignment.Center),
            clipInOverlayDuringTransition = OverlayClip(shape),
        )
    }
}

/** Screen fades for destinations reached by a container transform (the bounds do the movement). */
val containerEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition =
    { fadeIn(tween(CONTAINER_TRANSFORM_MS)) }
val containerExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition =
    { fadeOut(tween(CONTAINER_TRANSFORM_MS)) }

/** M3 shared axis X for forward/back navigation between related screens. */
val axisEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition =
    { slideInHorizontally(tween(400, easing = EmphasizedDecelerate)) { it / 6 } + fadeIn(tween(250, delayMillis = 80)) }
val axisExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition =
    { slideOutHorizontally(tween(250, easing = EmphasizedAccelerate)) { -it / 6 } + fadeOut(tween(150)) }
val axisPopEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition =
    { slideInHorizontally(tween(400, easing = EmphasizedDecelerate)) { -it / 6 } + fadeIn(tween(250, delayMillis = 80)) }
val axisPopExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition =
    { slideOutHorizontally(tween(250, easing = EmphasizedAccelerate)) { it / 6 } + fadeOut(tween(150)) }
