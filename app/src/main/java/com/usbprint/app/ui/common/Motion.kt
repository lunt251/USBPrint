package com.usbprint.app.ui.common

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.navigation.NavBackStackEntry

// M3 motion tokens (easing), used for screen-level transitions.
val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

/** M3 shared axis X for forward/back navigation between screens. */
val axisEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition =
    { slideInHorizontally(tween(400, easing = EmphasizedDecelerate)) { it / 6 } + fadeIn(tween(250, delayMillis = 80)) }
val axisExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition =
    { slideOutHorizontally(tween(250, easing = EmphasizedAccelerate)) { -it / 6 } + fadeOut(tween(150)) }
val axisPopEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition =
    { slideInHorizontally(tween(400, easing = EmphasizedDecelerate)) { -it / 6 } + fadeIn(tween(250, delayMillis = 80)) }
val axisPopExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition =
    { slideOutHorizontally(tween(250, easing = EmphasizedAccelerate)) { it / 6 } + fadeOut(tween(150)) }
