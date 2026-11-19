package com.sky.siplink.core.logic

import com.sky.siplink.core.model.DeviceReadings
import com.sky.siplink.core.model.LinkReadings
import com.sky.siplink.core.model.RoomWarning

/**
 * When the conference room warns that the phone or a connection may not keep up. The room is
 * looked at every [INTERVAL_MS]; a warning stands once its condition held [SAMPLES_TO_WARN] looks
 * in a row (one spike says nothing), and goes once it failed [SAMPLES_TO_CLEAR] looks in a row.
 */
object RoomHealth {
    /** How often the room is looked at while it is open. */
    const val INTERVAL_MS = 2_000L

    /** About six seconds over a threshold before a warning shows. */
    const val SAMPLES_TO_WARN = 3

    /** About six seconds back under it before the warning goes, so that it does not flicker. */
    const val SAMPLES_TO_CLEAR = 3

    /**
     * The app's process uses this much of all cores together. Above it the system starts to
     * starve the audio threads of their time, and the other apps with them.
     */
    const val CPU_PERCENT = 70f

    /**
     * The busiest thread of the process uses this much of one core. The SDK mixes the whole room,
     * the coding of every member's sound included, in a single thread that has to finish its work
     * every 10 ms; close to a full core it misses that beat and the sound breaks up for everyone,
     * long before the process as a whole looks busy on a phone with eight cores.
     */
    const val THREAD_PERCENT = 80f

    /**
     * PowerManager.THERMAL_STATUS_MODERATE: the system has begun to slow the processor down to
     * cool it, which takes time away from the mixer just when it needs it.
     */
    const val HOT_THERMAL_STATUS = 2

    /** The SDK rates a call from 0 to 5; below 3 it calls the quality poor (linphone_call_get_current_quality). */
    const val POOR_QUALITY = 3f

    /** From 4 the SDK calls the quality good. */
    const val GOOD_QUALITY = 4f

    /**
     * Lost packets, either way. G.711 has no repair of its own: from about 5 % the gaps are heard
     * as clicks and missing syllables.
     */
    const val LOSS_PERCENT = 5f

    /** Jitter of what arrives: past this the SDK's jitter buffer grows and adds delay, or drops late packets. */
    const val JITTER_MS = 60f

    /** Round trip: 400 ms is about 200 ms each way, past the 150 ms ITU-T G.114 finds fine for talking. */
    const val ROUND_TRIP_MS = 400f

    /** Whether one look at the phone finds it working too hard. */
    fun overloaded(device: DeviceReadings): Boolean =
        device.cpuPercent >= CPU_PERCENT || device.busiestThreadPercent >= THREAD_PERCENT

    fun hot(device: DeviceReadings): Boolean = (device.thermalStatus ?: 0) >= HOT_THERMAL_STATUS

    /** Whether one look at the connection to a member finds it poor. */
    fun poor(link: LinkReadings): Boolean =
        (link.quality != null && link.quality < POOR_QUALITY) ||
            link.lossUp >= LOSS_PERCENT || link.lossDown >= LOSS_PERCENT ||
            link.jitterMs >= JITTER_MS || link.roundTripMs >= ROUND_TRIP_MS

    /** The quality the SDK rates a call with as bars: 3 good, 2 average, 1 poor; null while it has none. */
    fun signalBars(quality: Float?): Int? = when {
        quality == null -> null
        quality >= GOOD_QUALITY -> 3
        quality >= POOR_QUALITY -> 2
        else -> 1
    }

    /** How often in a row a condition held or failed, and whether its warning stands. */
    data class Streak(val over: Int = 0, val under: Int = 0, val raised: Boolean = false) {
        fun next(crossed: Boolean): Streak {
            val over = if (crossed) over + 1 else 0
            val under = if (crossed) 0 else under + 1
            val raised = when {
                over >= SAMPLES_TO_WARN -> true
                under >= SAMPLES_TO_CLEAR -> false
                else -> raised
            }
            return Streak(over, under, raised)
        }
    }

    /** What the looks so far add up to. [links] is by member. */
    data class State(
        val load: Streak = Streak(),
        val heat: Streak = Streak(),
        val memory: Streak = Streak(),
        val links: Map<Long, Streak> = emptyMap(),
    ) {
        /** The members whose connection has been poor long enough to say so. */
        val poorMembers: Set<Long> get() = links.filterValues { it.raised }.keys

        val warnings: Set<RoomWarning>
            get() = buildSet {
                if (load.raised) add(RoomWarning.LOAD)
                if (heat.raised) add(RoomWarning.HEAT)
                if (memory.raised) add(RoomWarning.MEMORY)
                if (poorMembers.isNotEmpty()) add(RoomWarning.NETWORK)
            }
    }

    /**
     * The state after one more look. A look at the phone may be missing (the first one has no
     * time to measure against); members that are no longer measured are dropped.
     */
    fun next(state: State, device: DeviceReadings?, links: Map<Long, LinkReadings>): State = State(
        load = if (device != null) state.load.next(overloaded(device)) else state.load,
        heat = if (device != null) state.heat.next(hot(device)) else state.heat,
        memory = if (device != null) state.memory.next(device.lowMemory) else state.memory,
        links = links.mapValues { (member, link) -> (state.links[member] ?: Streak()).next(poor(link)) },
    )

    /**
     * The CPU a process or a thread used between two looks, in percent of [cores] cores.
     *
     * @param ticks CPU time used in between, in clock ticks
     * @param ticksPerSecond the clock ticks of a second (sysconf _SC_CLK_TCK)
     */
    fun cpuPercent(ticks: Long, ticksPerSecond: Long, elapsedMs: Long, cores: Int = 1): Float {
        if (ticks <= 0 || ticksPerSecond <= 0 || elapsedMs <= 0 || cores <= 0) return 0f
        return ticks * 1000f * 100f / (ticksPerSecond * elapsedMs * cores)
    }

    /**
     * The CPU time a line of /proc/…/stat reports, user and system together, in clock ticks;
     * null for a line that is not one. The name in parentheses may hold spaces and parentheses
     * itself, so the fields are counted from the last ")".
     */
    fun cpuTicks(statLine: String): Long? {
        val end = statLine.lastIndexOf(')')
        if (end < 0) return null
        // After the name: state (field 3) ... utime (14), stime (15).
        val fields = statLine.substring(end + 1).trim().split(' ')
        val user = fields.getOrNull(UTIME_AFTER_NAME)?.toLongOrNull() ?: return null
        val system = fields.getOrNull(UTIME_AFTER_NAME + 1)?.toLongOrNull() ?: return null
        return user + system
    }

    /** utime is field 14 of the line; the fields after the name start with field 3. */
    private const val UTIME_AFTER_NAME = 14 - 3
}
