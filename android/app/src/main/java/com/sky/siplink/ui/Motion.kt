package com.sky.siplink.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.ui.unit.LayoutDirection

/**
 * How the app moves between screens: with some depth, but quick. Nothing here takes longer
 * than 280 ms, and the back transitions are the ones the predictive back gesture scrubs through.
 */
internal object Motion {
    /** Starts fast and settles gently: for things that arrive. */
    private val arrive = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** Speeds up on the way out: for things that leave. */
    private val leave = CubicBezierEasing(0.3f, 0f, 1f, 1f)

    /** For sizes and positions: a stiff spring without visible bounce. */
    fun <T> spatial(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.9f, stiffness = 700f)

    fun fade(millis: Int): FiniteAnimationSpec<Float> = tween(millis, easing = LinearEasing)

    /**
     * A new screen slides in from the end of the screen (the right, the left in a right-to-left
     * language) over the old one, which backs away a little. The slides take pixels as they are,
     * so the side is chosen here.
     */
    fun pushEnter(direction: LayoutDirection): EnterTransition =
        slideInHorizontally(tween(280, easing = arrive)) { forward(direction) * it / 4 } + fadeIn(tween(160))
    fun pushExit(direction: LayoutDirection): ExitTransition = slideOutHorizontally(tween(280, easing = arrive)) { -forward(direction) * it / 10 } +
        scaleOut(tween(280, easing = arrive), targetScale = 0.96f) + fadeOut(tween(140))

    /** Going back: the screen leaves to the end and shrinks, the one below comes forward again. */
    fun popEnter(direction: LayoutDirection): EnterTransition = slideInHorizontally(tween(260, easing = arrive)) { -forward(direction) * it / 10 } +
        scaleIn(tween(260, easing = arrive), initialScale = 0.96f) + fadeIn(tween(160))
    fun popExit(direction: LayoutDirection): ExitTransition = slideOutHorizontally(tween(240, easing = leave)) { forward(direction) * it / 3 } +
        scaleOut(tween(240, easing = leave), targetScale = 0.94f) + fadeOut(tween(180, delayMillis = 40))

    /** 1 where the screen runs to the right, -1 where it runs to the left. */
    fun forward(direction: LayoutDirection): Int = if (direction == LayoutDirection.Rtl) -1 else 1

    /** Between tabs nothing travels: the old one fades out first, the new one fades in growing slightly. */
    val tabEnter: EnterTransition = fadeIn(tween(180, delayMillis = 50)) + scaleIn(tween(230, easing = arrive), initialScale = 0.97f)
    val tabExit: ExitTransition = fadeOut(tween(70))
}
