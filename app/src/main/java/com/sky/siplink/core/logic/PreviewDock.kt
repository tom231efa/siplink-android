package com.sky.siplink.core.logic

import com.sky.siplink.core.model.DockSide
import kotlin.math.abs

/**
 * Where the small picture of a video call stands. It is dragged with a finger, and once let go it
 * stands at the nearer side edge (or the one it was flung towards), never in the middle of the
 * screen; how far down it is stays as it was let go, within the band it may stand in.
 *
 * The band depends on the controls: while they are shown it lies between the row of buttons at
 * the top and the controls at the bottom (with the phone on its side, beside the controls at the
 * start); while they are hidden it reaches from under the status bar to above the navigation
 * bar. A picture in the upper part of the band moves up by the room the top row frees when the
 * controls go, and back down when they come; one in the lower part does the same at the bottom;
 * one in the middle stays where it is. In between the two it moves by part of that room, so that
 * every spot in one band has its own in the other.
 *
 * The user's place ([Place]) is how far down the band the picture stands while the controls are
 * shown, from 0 (top) to 1 (bottom), and the side: the same on a screen of any shape, so it
 * carries over turns of the phone and later calls.
 */
object PreviewDock {
    /** A part of the screen in pixels, where the small picture may stand. */
    data class Area(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val height: Float get() = bottom - top
    }

    data class Spot(val x: Float, val y: Float)

    data class Place(val side: DockSide, val fraction: Float)

    /** A place on its way from storage: a number, and between top and bottom. */
    fun placeOf(fraction: Float): Float = if (fraction.isNaN()) 0f else fraction.coerceIn(0f, 1f)

    /**
     * The top left corner of a picture of [width] by [height] at [place], while it may stand in
     * [now]; [shown] is the band while the controls are shown, which [now] is or contains.
     */
    fun spot(place: Place, width: Float, height: Float, shown: Area, now: Area): Spot {
        val x = if (place.side == DockSide.LEFT) now.left else maxOf(now.left, now.right - width)
        return Spot(x, clamp(yOf(placeOf(place.fraction), height, shown, now), height, now))
    }

    /**
     * Where a picture let go with its top left corner at ([x], [y]) stands from now on.
     *
     * @param velocityX how fast the finger went sideways when it let go, in pixels per second
     * @param fling from this speed on, the picture goes to the side it was flung towards
     */
    fun release(x: Float, y: Float, velocityX: Float, width: Float, height: Float, shown: Area, now: Area, fling: Float): Place {
        val side = when {
            abs(velocityX) >= fling -> if (velocityX < 0) DockSide.LEFT else DockSide.RIGHT
            x + width / 2 < (now.left + now.right) / 2 -> DockSide.LEFT
            else -> DockSide.RIGHT
        }
        return Place(side, fractionAt(clamp(y, height, now), height, shown, now))
    }

    /** One step up or down for a screen reader ("上移", "下移"). */
    fun moved(place: Place, up: Boolean): Place = place.copy(fraction = placeOf(place.fraction + if (up) -STEP else STEP))

    fun canMove(place: Place, up: Boolean): Boolean = if (up) place.fraction > 0f else place.fraction < 1f

    /** How far down [shown] a picture of [height] can go. */
    private fun span(shown: Area, height: Float): Float = (shown.height - height).coerceAtLeast(0f)

    private fun yOf(fraction: Float, height: Float, shown: Area, now: Area): Float {
        val freedAbove = (shown.top - now.top).coerceAtLeast(0f)
        val freedBelow = (now.bottom - shown.bottom).coerceAtLeast(0f)
        return shown.top + fraction * span(shown, height) - freedAbove * upperWeight(fraction) + freedBelow * lowerWeight(fraction)
    }

    /** The place a picture at [y] has: [yOf] only ever grows with the place, so halving finds it. */
    private fun fractionAt(y: Float, height: Float, shown: Area, now: Area): Float {
        if (y <= yOf(0f, height, shown, now)) return 0f
        if (y >= yOf(1f, height, shown, now)) return 1f
        var low = 0f
        var high = 1f
        repeat(SEARCH_STEPS) {
            val middle = (low + high) / 2
            if (yOf(middle, height, shown, now) < y) low = middle else high = middle
        }
        return placeOf((low + high) / 2)
    }

    private fun clamp(y: Float, height: Float, area: Area): Float = y.coerceIn(area.top, maxOf(area.top, area.bottom - height))

    /** How much of the room freed at the top a picture at [fraction] takes. */
    private fun upperWeight(fraction: Float): Float = ((UPPER_END - fraction) / (UPPER_END - UPPER_FULL)).coerceIn(0f, 1f)

    private fun lowerWeight(fraction: Float): Float = ((fraction - LOWER_START) / (LOWER_FULL - LOWER_START)).coerceIn(0f, 1f)

    /** Up to here a picture is in the upper part: it moves by all the room freed at the top. */
    private const val UPPER_FULL = 0.25f

    /** From here to [LOWER_START] it is in the middle and does not move. */
    private const val UPPER_END = 0.4f
    private const val LOWER_START = 0.6f
    private const val LOWER_FULL = 0.75f

    private const val STEP = 0.5f
    private const val SEARCH_STEPS = 30
}
