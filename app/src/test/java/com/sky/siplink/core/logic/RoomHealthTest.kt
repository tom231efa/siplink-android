package com.sky.siplink.core.logic

import com.sky.siplink.core.model.DeviceReadings
import com.sky.siplink.core.model.LinkReadings
import com.sky.siplink.core.model.RoomWarning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomHealthTest {
    private val calm = DeviceReadings(cpuPercent = 12f, busiestThreadPercent = 30f, thermalStatus = 0, lowMemory = false)
    private val good = LinkReadings(quality = 4.6f, lossUp = 0f, lossDown = 0f, jitterMs = 3f, roundTripMs = 20f)

    private fun run(vararg looks: Pair<DeviceReadings?, Map<Long, LinkReadings>>): RoomHealth.State =
        looks.fold(RoomHealth.State()) { state, (device, links) -> RoomHealth.next(state, device, links) }

    @Test
    fun `nothing is said while all is well`() {
        val state = run(*Array(10) { calm to mapOf(1L to good) })
        assertTrue(state.warnings.isEmpty())
        assertTrue(state.poorMembers.isEmpty())
    }

    @Test
    fun `one spike is no warning`() {
        val busy = calm.copy(cpuPercent = 95f)
        val state = run(calm to emptyMap(), busy to emptyMap(), calm to emptyMap(), busy to emptyMap(), calm to emptyMap())
        assertFalse(RoomWarning.LOAD in state.warnings)
    }

    @Test
    fun `a load that lasts is a warning, and it goes once the load has gone a while`() {
        val busy = calm.copy(cpuPercent = RoomHealth.CPU_PERCENT)
        var state = RoomHealth.State()
        repeat(RoomHealth.SAMPLES_TO_WARN - 1) { state = RoomHealth.next(state, busy, emptyMap()) }
        assertFalse(RoomWarning.LOAD in state.warnings)
        state = RoomHealth.next(state, busy, emptyMap())
        assertTrue(RoomWarning.LOAD in state.warnings)

        repeat(RoomHealth.SAMPLES_TO_CLEAR - 1) { state = RoomHealth.next(state, calm, emptyMap()) }
        assertTrue("A warning that flickers off at once", RoomWarning.LOAD in state.warnings)
        state = RoomHealth.next(state, calm, emptyMap())
        assertFalse(RoomWarning.LOAD in state.warnings)
    }

    @Test
    fun `a busy mixer thread is a load although the process as a whole is not`() {
        assertTrue(RoomHealth.overloaded(calm.copy(cpuPercent = 15f, busiestThreadPercent = RoomHealth.THREAD_PERCENT)))
        assertFalse(RoomHealth.overloaded(calm.copy(busiestThreadPercent = RoomHealth.THREAD_PERCENT - 1f)))
    }

    @Test
    fun `heat counts from the system's moderate throttling, low memory as the system says`() {
        assertFalse(RoomHealth.hot(calm.copy(thermalStatus = 1)))
        assertTrue(RoomHealth.hot(calm.copy(thermalStatus = RoomHealth.HOT_THERMAL_STATUS)))
        assertFalse(RoomHealth.hot(calm.copy(thermalStatus = null)))
        val state = run(*Array(RoomHealth.SAMPLES_TO_WARN) { calm.copy(thermalStatus = 3, lowMemory = true) to emptyMap() })
        assertEquals(setOf(RoomWarning.HEAT, RoomWarning.MEMORY), state.warnings)
    }

    @Test
    fun `a look without the device keeps what the device looks said`() {
        val hot = calm.copy(thermalStatus = 3)
        var state = run(*Array(RoomHealth.SAMPLES_TO_WARN) { hot to emptyMap() })
        state = RoomHealth.next(state, null, emptyMap())
        assertTrue(RoomWarning.HEAT in state.warnings)
    }

    @Test
    fun `each threshold of a connection makes it poor`() {
        assertFalse(RoomHealth.poor(good))
        assertTrue(RoomHealth.poor(good.copy(quality = 2.9f)))
        assertFalse("No quality measured yet", RoomHealth.poor(good.copy(quality = null)))
        assertTrue(RoomHealth.poor(good.copy(lossUp = RoomHealth.LOSS_PERCENT)))
        assertTrue(RoomHealth.poor(good.copy(lossDown = RoomHealth.LOSS_PERCENT)))
        assertTrue(RoomHealth.poor(good.copy(jitterMs = RoomHealth.JITTER_MS)))
        assertTrue(RoomHealth.poor(good.copy(roundTripMs = RoomHealth.ROUND_TRIP_MS)))
    }

    @Test
    fun `a member whose connection stays poor is named, and the room warns of the network`() {
        val lossy = good.copy(lossDown = 12f)
        val state = run(*Array(RoomHealth.SAMPLES_TO_WARN) { calm to mapOf(1L to lossy, 2L to good) })
        assertEquals(setOf(1L), state.poorMembers)
        assertEquals(setOf(RoomWarning.NETWORK), state.warnings)
    }

    @Test
    fun `a member that is no longer measured is forgotten`() {
        val lossy = good.copy(lossUp = 20f)
        var state = run(*Array(RoomHealth.SAMPLES_TO_WARN) { calm to mapOf(1L to lossy) })
        state = RoomHealth.next(state, calm, emptyMap())
        assertTrue(state.poorMembers.isEmpty())
        assertTrue(state.warnings.isEmpty())
    }

    @Test
    fun `cpu time is a share of the cores in the time between two looks`() {
        // 100 ticks a second: 160 ticks in 2 s are 80 % of one core, 10 % of eight.
        assertEquals(80f, RoomHealth.cpuPercent(160, 100, 2_000), 0.001f)
        assertEquals(10f, RoomHealth.cpuPercent(160, 100, 2_000, cores = 8), 0.001f)
        assertEquals(0f, RoomHealth.cpuPercent(160, 100, 0), 0f)
        assertEquals(0f, RoomHealth.cpuPercent(-5, 100, 2_000), 0f)
    }

    @Test
    fun `the cpu time of a stat line is user and system time, whatever the name holds`() {
        val line = "4242 (Audio conf) ticker) S 1 2 3 4 5 6 7 8 9 10 1500 230 0 0 20 0 50 0 100 0"
        assertEquals(1730L, RoomHealth.cpuTicks(line))
        assertNull(RoomHealth.cpuTicks("garbage"))
        assertNull(RoomHealth.cpuTicks("1 (x) S 1 2"))
    }
}
