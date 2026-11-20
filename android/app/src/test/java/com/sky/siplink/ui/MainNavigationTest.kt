package com.sky.siplink.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainNavigationTest {
    @Test
    fun theRailBeginsWhereMaterialsMediumWidthBegins() {
        // WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND of androidx.window.
        assertEquals(600.dp, RailFromWidth)
        assertFalse(usesRail(599.9.dp))
        assertTrue(usesRail(600.dp))
        assertTrue(usesRail(1600.dp))
        assertFalse(usesRail(0.dp))
    }

    @Test
    fun theTestPhoneKeepsTheBarUprightAndGetsTheRailOnItsSide() {
        // Galaxy Z Flip6, main screen: 1080 x 2640 px at density 3 (480 dpi), 360 x 880 dp.
        assertFalse(usesRail((1080 / 3).dp))
        assertTrue(usesRail((2640 / 3).dp))
        // Its cover screen, 748 x 720 px at density 2.125 (340 dpi): about 352 dp either way.
        assertFalse(usesRail((748 / 2.125f).dp))
        // Half of the main screen on its side in split screen: 440 dp.
        assertFalse(usesRail((2640 / 3 / 2).dp))
    }

    @Test
    fun otherPhonesAndTabletsFollowTheirWidth() {
        // A 16:9 phone 360 dp wide upright is 640 dp wide on its side.
        assertFalse(usesRail(360.dp))
        assertTrue(usesRail(640.dp))
        // A tablet of 800 x 1280 dp has the rail upright too, as Material has it from 600 dp.
        assertTrue(usesRail(800.dp))
    }
}
