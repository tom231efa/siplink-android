package com.sky.siplink.core

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.util.Log
import com.sky.siplink.BuildConfig
import com.sky.siplink.core.logic.RoomHealth
import com.sky.siplink.core.model.DeviceReadings
import com.sky.siplink.core.model.LinkReadings
import org.linphone.core.Call
import java.io.File
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Looks at the phone and at the connection to every member while a conference room is open,
 * every [RoomHealth.INTERVAL_MS], and does nothing while there is none. The CPU time of the
 * process and of its busiest thread come from /proc, the temperature from [PowerManager], the
 * memory from [ActivityManager]; the connections from the SDK's statistics of each call.
 *
 * The SDK is asked on the thread of the core, which owns it; /proc is read on a thread of its own.
 * A debug build writes what was measured to the log (tag [TAG], debug level).
 */
class RoomMonitor(context: Context) {
    /** What one look found: the device (null on the first look), and the connections by member. */
    class Sample(val device: DeviceReadings?, val links: Map<Long, LinkReadings>, val health: RoomHealth.State)

    private val power = context.getSystemService(PowerManager::class.java)
    private val activities = context.getSystemService(ActivityManager::class.java)
    private val ticksPerSecond = Os.sysconf(OsConstants._SC_CLK_TCK)
    private val cores = Runtime.getRuntime().availableProcessors()
    private val handler = CoreThread.handler()

    /** One thread for /proc, which ends when nothing has been asked of it for a while. */
    private val worker = ThreadPoolExecutor(0, 1, WORKER_IDLE_S, TimeUnit.SECONDS, LinkedBlockingQueue())

    private var members: () -> Map<Long, Call> = { emptyMap() }
    private var onSample: (Sample) -> Unit = {}
    private var health = RoomHealth.State()

    /** Tells the looks of one room from those of the next. */
    private var round = 0
    private val tick = Runnable { look() }

    // What the last look at /proc found; used on the worker thread only.
    private var lastAt = 0L
    private var lastProcessTicks = 0L
    private var lastThreadTicks = HashMap<String, Long>()

    /** Starts looking: [members] are the calls of the room by member, [onSample] gets what each look found. */
    fun start(members: () -> Map<Long, Call>, onSample: (Sample) -> Unit) {
        stop()
        this.members = members
        this.onSample = onSample
        health = RoomHealth.State()
        handler.post(tick)
    }

    fun stop() {
        round++
        handler.removeCallbacks(tick)
        members = { emptyMap() }
        onSample = {}
        worker.execute { lastAt = 0L }
    }

    private fun look() {
        val token = round
        val calls = members()
        val links = calls.mapNotNull { (member, call) -> linkOf(call)?.let { member to it } }.toMap()
        // For the log only: not asked of the SDK in a release build.
        val codec = calls.values.takeIf { BuildConfig.DEBUG }?.firstNotNullOfOrNull { it.currentParams.usedAudioPayloadType }
            ?.let { "${it.mimeType}/${it.clockRate}" }
        worker.execute {
            val device = sampleDevice()
            handler.post {
                if (token != round) return@post
                health = RoomHealth.next(health, device, links)
                // Every two seconds for as long as a room is open: in the log of a debug build only.
                if (BuildConfig.DEBUG) log(calls.size, device, links, codec)
                onSample(Sample(device, links, health))
            }
        }
        handler.postDelayed(tick, RoomHealth.INTERVAL_MS)
    }

    /** The connection to a member whose sound runs; null for one that is still ringing or on hold. */
    private fun linkOf(call: Call): LinkReadings? {
        if (call.state != Call.State.StreamsRunning) return null
        val stats = call.audioStats ?: return null
        return LinkReadings(
            quality = call.currentQuality.takeIf { it >= 0f },
            // The report the member sends back on what it gets of the phone's sound, and the one the phone sends.
            lossUp = stats.receiverLossRate,
            lossDown = stats.senderLossRate,
            jitterMs = stats.senderInterarrivalJitter * MS_PER_S,
            roundTripMs = stats.roundTripDelay * MS_PER_S,
        )
    }

    /** One look at /proc and at the system; null on the first one, which has nothing to compare with. */
    private fun sampleDevice(): DeviceReadings? {
        val now = SystemClock.elapsedRealtime()
        val process = ticksOf(File(PROC_SELF, "stat")) ?: return null
        val threads = HashMap<String, Long>()
        File(PROC_SELF, "task").listFiles()?.forEach { task -> ticksOf(File(task, "stat"))?.let { threads[task.name] = it } }
        val elapsed = now - lastAt
        val first = lastAt == 0L
        val processDelta = process - lastProcessTicks
        // A thread that came up since the last look is measured from the next one on.
        val busiest = threads.maxOfOrNull { (id, ticks) -> ticks - (lastThreadTicks[id] ?: ticks) } ?: 0L
        lastAt = now
        lastProcessTicks = process
        lastThreadTicks = threads
        if (first) return null
        return DeviceReadings(
            cpuPercent = RoomHealth.cpuPercent(processDelta, ticksPerSecond, elapsed, cores),
            busiestThreadPercent = RoomHealth.cpuPercent(busiest, ticksPerSecond, elapsed),
            thermalStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) power.currentThermalStatus else null,
            lowMemory = ActivityManager.MemoryInfo().also(activities::getMemoryInfo).lowMemory,
        )
    }

    private fun ticksOf(file: File): Long? = runCatching { file.readText() }.getOrNull()?.let(RoomHealth::cpuTicks)

    private fun log(members: Int, device: DeviceReadings?, links: Map<Long, LinkReadings>, codec: String?) {
        val qualities = links.values.mapNotNull { it.quality }
        Log.d(
            TAG,
            "members=$members streaming=${links.size} codec=$codec" +
                " cpu=${device?.cpuPercent?.let(::one)}% thread=${device?.busiestThreadPercent?.let(::one)}%" +
                " thermal=${device?.thermalStatus} lowMemory=${device?.lowMemory}" +
                " quality.min=${qualities.minOrNull()?.let(::one)}" +
                " lossUp.max=${links.values.maxOfOrNull { it.lossUp }?.let(::one)}" +
                " lossDown.max=${links.values.maxOfOrNull { it.lossDown }?.let(::one)}" +
                " jitter.max=${links.values.maxOfOrNull { it.jitterMs }?.let(::one)}ms" +
                " rtt.max=${links.values.maxOfOrNull { it.roundTripMs }?.let(::one)}ms" +
                " warnings=${health.warnings} poor=${health.poorMembers.size}",
        )
    }

    private fun one(value: Float): String = "%.1f".format(Locale.ROOT, value)

    private companion object {
        const val TAG = "SipLinkRoom"
        const val PROC_SELF = "/proc/self"
        const val MS_PER_S = 1000f
        const val WORKER_IDLE_S = 10L
    }
}
