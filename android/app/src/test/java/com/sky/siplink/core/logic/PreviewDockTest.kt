package com.sky.siplink.core.logic

import com.sky.siplink.core.logic.PreviewDock.Area
import com.sky.siplink.core.logic.PreviewDock.Place
import com.sky.siplink.core.model.DockSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewDockTest {
    // An upright screen of 1000 by 2000: the status bar ends at 100, the top row at 250, the
    // controls begin at 1400, the navigation bar at 1950. The picture is 300 by 400.
    private val shown = Area(40f, 250f, 960f, 1400f)
    private val hidden = Area(40f, 100f, 960f, 1950f)
    private val width = 300f
    private val height = 400f
    private val fling = 1000f

    private fun y(fraction: Float, now: Area) = PreviewDock.spot(Place(DockSide.RIGHT, fraction), width, height, shown, now).y

    private fun release(x: Float, y: Float, velocityX: Float = 0f, now: Area = shown) =
        PreviewDock.release(x, y, velocityX, width, height, shown, now, fling)

    @Test
    fun `it stands at the side edge it was let go nearer to`() {
        assertEquals(DockSide.LEFT, release(x = 100f, y = 600f).side)
        assertEquals(DockSide.RIGHT, release(x = 400f, y = 600f).side)
        assertEquals(shown.left, PreviewDock.spot(Place(DockSide.LEFT, 0.5f), width, height, shown, shown).x)
        assertEquals(shown.right - width, PreviewDock.spot(Place(DockSide.RIGHT, 0.5f), width, height, shown, shown).x)
    }

    @Test
    fun `a fling takes it to the side it went towards`() {
        assertEquals(DockSide.RIGHT, release(x = 100f, y = 600f, velocityX = 1500f).side)
        assertEquals(DockSide.LEFT, release(x = 600f, y = 600f, velocityX = -1500f).side)
        // Too slow for a fling: the nearer side.
        assertEquals(DockSide.LEFT, release(x = 100f, y = 600f, velocityX = 900f).side)
    }

    @Test
    fun `how far down it was let go is kept, within the band`() {
        val place = release(x = 600f, y = 700f)
        assertEquals(700f, y(place.fraction, shown), 0.5f)
        // Above the band, under the top row; below it, over the controls.
        assertEquals(0f, release(x = 600f, y = 120f).fraction)
        assertEquals(1f, release(x = 600f, y = 1500f).fraction)
        assertEquals(shown.top, y(0f, shown))
        assertEquals(shown.bottom - height, y(1f, shown))
    }

    @Test
    fun `at the top it goes up by the top row when the controls go and back when they come`() {
        assertEquals(shown.top, y(0f, shown))
        assertEquals(hidden.top, y(0f, hidden))
        assertEquals(shown.top - hidden.top, y(0.1f, shown) - y(0.1f, hidden), 0.5f)
    }

    @Test
    fun `at the bottom it goes down by the controls when they go`() {
        assertEquals(shown.bottom - height, y(1f, shown))
        assertEquals(hidden.bottom - height, y(1f, hidden))
        assertEquals(hidden.bottom - shown.bottom, y(0.9f, hidden) - y(0.9f, shown), 0.5f)
    }

    @Test
    fun `in the middle it stays where it is`() {
        for (fraction in listOf(0.4f, 0.5f, 0.6f)) assertEquals("$fraction", y(fraction, shown), y(fraction, hidden), 0.01f)
    }

    @Test
    fun `every spot of one band has its own in the other`() {
        var before = Float.NEGATIVE_INFINITY
        for (step in 0..100) {
            val now = y(step / 100f, hidden)
            assertTrue("Step $step goes back up", now >= before)
            before = now
        }
        // Let go while the controls are hidden, it is found where it was let go.
        for (spot in listOf(100f, 180f, 300f, 800f, 1300f, 1500f)) {
            val place = release(x = 600f, y = spot, now = hidden)
            assertEquals("$spot", spot, y(place.fraction, hidden), 1f)
        }
    }

    @Test
    fun `the place carries over a turn of the phone`() {
        // On its side: 2000 by 1000, the controls at the start up to 900, the top row to 200.
        val wideShown = Area(940f, 200f, 1960f, 950f)
        val wideHidden = Area(140f, 60f, 1960f, 950f)
        val place = release(x = 600f, y = shown.top + (shown.height - height) / 2)
        assertEquals(0.5f, place.fraction, 0.01f)
        val turned = PreviewDock.spot(place, 400f, 300f, wideShown, wideShown)
        assertEquals(wideShown.top + (wideShown.height - 300f) / 2, turned.y, 0.5f)
        assertEquals(wideShown.right - 400f, turned.x)
        // On the left of a wide screen it stands beside the controls while they are shown.
        assertEquals(wideShown.left, PreviewDock.spot(Place(DockSide.LEFT, 0f), 400f, 300f, wideShown, wideShown).x)
        assertEquals(wideHidden.left, PreviewDock.spot(Place(DockSide.LEFT, 0f), 400f, 300f, wideShown, wideHidden).x)
    }

    @Test
    fun `a screen reader moves it a step at a time`() {
        val top = Place(DockSide.RIGHT, 0f)
        assertFalse(PreviewDock.canMove(top, up = true))
        assertTrue(PreviewDock.canMove(top, up = false))
        assertEquals(0.5f, PreviewDock.moved(top, up = false).fraction)
        assertEquals(1f, PreviewDock.moved(PreviewDock.moved(top, up = false), up = false).fraction)
        assertFalse(PreviewDock.canMove(Place(DockSide.RIGHT, 1f), up = false))
        assertEquals(0.2f, PreviewDock.moved(Place(DockSide.LEFT, 0.7f), up = true).fraction, 0.001f)
    }

    @Test
    fun `a stored place is a number between top and bottom`() {
        assertEquals(0f, PreviewDock.placeOf(Float.NaN))
        assertEquals(0f, PreviewDock.placeOf(-2f))
        assertEquals(1f, PreviewDock.placeOf(7f))
        assertEquals(0.3f, PreviewDock.placeOf(0.3f))
    }

    @Test
    fun `a picture taller than the band stands at its top`() {
        val low = Area(0f, 100f, 800f, 300f)
        assertEquals(100f, PreviewDock.spot(Place(DockSide.RIGHT, 1f), width, height, low, low).y)
        assertEquals(0f, PreviewDock.release(500f, 250f, 0f, width, height, low, low, fling).fraction)
    }
}
