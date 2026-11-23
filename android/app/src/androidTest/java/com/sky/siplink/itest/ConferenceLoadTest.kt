package com.sky.siplink.itest

import android.os.SystemClock
import android.util.Log
import com.sky.siplink.core.logic.RoomRules
import com.sky.siplink.core.model.MemberState
import com.sky.siplink.core.model.RoomWarning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Measurements more than tests, run by hand and not by a full run of testenv/run-tests.sh.
 *
 * [aFullRoomKeepsItsSound]: the conference room filled with 30 members and then up to its limit,
 * a few minutes each, the room on screen, with what the phone and the PBX see written to the log
 * (tag [TAG]; the app's own measurements under the tag SipLinkRoom). It fails when a member
 * stops getting the room's sound or packets.
 *
 * [aPoorConnectionIsShown]: pictures of the warning and of the measured values. It needs packet
 * loss on the way to the PBX while it runs, which the test cannot make itself (for instance a
 * fifth of the UDP packets to the PBX's RTP ports dropped by nftables in the network namespace
 * of the PBX's container).
 *
 * [aDeskPhoneWithDirectMediaStaysInTheRoom]: needs the PBX with direct media on for its phones
 * (endpoint/direct_media = yes and endpoint/media_encryption = no in the pjsip_wizard.conf of the
 * running container, then "module reload res_pjsip_config_wizard.so"; with SDES offered, Asterisk
 * keeps the sound itself). The PBX then moves the sound of the app's call to desk phone 1002 off
 * itself (re-INVITE); the room must keep the member, and the two must still hear each other where
 * the network lets them reach each other directly. A phone on the LAN does not reach the scripted
 * phones that way: the PBX hands it the address of the host with the port of the scripted phone.
 *
 * The members are numbers 25XX of the test PBX; they beep now and then and report what they hear
 * (testenv/asterisk/config/extensions.conf). The app's microphone is muted: what each member
 * hears is the others, mixed by the app.
 */
class ConferenceLoadTest : RoomTestBase("conference-load") {
    /** Calls the first member, opens the room from that call, and shows it. */
    private fun openRoom() = openRoom(member(1))

    @Test
    fun aFullRoomKeepsItsSound() {
        val joined = pbx.mark()
        openRoom()
        for (size in listOf(FIRST_SIZE, RoomRules.LIMIT).distinct()) {
            fill(size)
            measure(size, joined)
            picture("room-$size")
        }
        // Halfway down the list of a full room.
        repeat(2) { scroll(forward = true) }
        picture("room-${RoomRules.LIMIT}-scrolled")
        assertEquals(zh(RoomRules.refusal(RoomRules.LIMIT, 1)!!), onCore { engine.calls.inviteToRoom(listOf(member(RoomRules.LIMIT + 1))) })
        // Ending a full room: how long the main thread is held by it, and until every call is gone.
        val start = SystemClock.uptimeMillis()
        onCore { engine.calls.hangUp() }
        val held = SystemClock.uptimeMillis() - start
        onCore { }
        val busy = SystemClock.uptimeMillis() - start
        awaitNoCall(LONG_TIMEOUT_MS)
        Log.i(TAG, "end size=${RoomRules.LIMIT} hangUp.ms=$held mainFree.ms=$busy allGone.ms=${SystemClock.uptimeMillis() - start}")
    }

    @Test
    fun aPoorConnectionIsShown() {
        openRoom()
        ok(onCore { engine.calls.inviteToRoom((2..WARNING_ROOM).map(::member)) })
        await("the warning of a poor connection (is there packet loss on the way to the PBX?)", WARNING_TIMEOUT_MS) {
            room()?.warnings?.contains(RoomWarning.NETWORK) == true
        }
        settle()
        picture("warning")
        tap("网络质量差")
        see("处理器")
        picture("readings")
        onCore { engine.calls.hangUp() }
        awaitNoCall(LONG_TIMEOUT_MS)
    }

    @Test
    fun aDeskPhoneWithDirectMediaStaysInTheRoom() {
        openRoom()
        val heard = phones.mark()
        ok(onCore { engine.calls.inviteToRoom(listOf("$AGENT")) })
        awaitRoom("the desk phone in the room") { connected(it) == 2 }
        val legs = appLegsUp().toSet()
        // Time for the PBX to move the sound (re-INVITE both ways) and for the app to follow.
        Thread.sleep(SETTLE_MS)
        val before = rtpStats()
        Thread.sleep(SETTLE_MS)
        val after = rtpStats()
        for (leg in legs) Log.i(TAG, "direct media leg=$leg rx+=${(after[leg]?.rxCount ?: 0) - (before[leg]?.rxCount ?: 0)}")
        pbx.command("bridge show all").lineSequence().filter { it.isNotBlank() }.forEach { Log.i(TAG, "direct media bridge $it") }
        assertEquals("A member left the room", 2, connected(room()!!))
        assertEquals("A leg was replaced", legs, appLegsUp().toSet())
        val phoneHears = phoneHearsWithin(AGENT, heard, SETTLE_MS)
        val mark = pbx.mark()
        phoneTalks(AGENT)
        val memberHears = pbx.sees("ChannelTalkingStart", mark, SETTLE_MS) { it["Channel"].orEmpty() in legs && it["Exten"] == member(1) }
        Log.i(TAG, "direct media phoneHearsRoom=$phoneHears memberHearsPhone=$memberHears")
        assertTrue("The desk phone hears the room", phoneHears)
        assertTrue("The member hears the desk phone", memberHears)
        onCore { engine.calls.hangUp() }
        awaitNoCall(LONG_TIMEOUT_MS)
    }

    private fun member(index: Int) = "${FIRST_MEMBER + index - 1}"

    /** Calls members in batches until [size] are connected. */
    private fun fill(size: Int) {
        while (room()!!.present < size) {
            val present = room()!!.present
            val batch = (present + 1..minOf(size, present + BATCH)).map(::member)
            val start = SystemClock.uptimeMillis()
            ok(onCore { engine.calls.inviteToRoom(batch) })
            Log.i(TAG, "invite from=${present + 1} count=${batch.size} main.ms=${SystemClock.uptimeMillis() - start}")
            await("${present + batch.size} members connected", LONG_TIMEOUT_MS) {
                room()!!.members.count { it.state == MemberState.CONNECTED } >= present + batch.size
            }
        }
        await("$size legs of the app up on the PBX", LONG_TIMEOUT_MS) { appLegsUp().size == size }
        Thread.sleep(SETTLE_MS)
    }

    /**
     * Watches a full room for [MEASURE_MS]: the phone's measurements every two seconds, and per
     * leg on the PBX the packets and the sound that reach the member.
     */
    private fun measure(size: Int, joined: Int) {
        val legs = appLegsUp()
        val start = pbx.mark()
        val before = rtpStats()
        val cpu = ArrayList<Float>()
        val thread = ArrayList<Float>()
        val thermal = HashSet<Int?>()
        var lowMemory = false
        var poor = 0
        // How long the main thread takes to get to a task: the screen stalls for as long.
        val mainLag = ArrayList<Long>()
        val deadline = System.currentTimeMillis() + MEASURE_MS
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(SAMPLE_MS)
            val asked = SystemClock.uptimeMillis()
            onCore { }
            mainLag += SystemClock.uptimeMillis() - asked
            val room = room() ?: break
            engine.calls.roomReadings.value?.device?.let {
                cpu += it.cpuPercent
                thread += it.busiestThreadPercent
                thermal += it.thermalStatus
                lowMemory = lowMemory || it.lowMemory
            }
            poor = maxOf(poor, room.members.count { it.poorLink })
            assertEquals("A member left the room", size, room.members.count { it.state == MemberState.CONNECTED })
        }
        val after = rtpStats()
        val codec = onCore { engine.core.calls.firstOrNull()?.currentParams?.usedAudioPayloadType?.let { "${it.mimeType}/${it.clockRate}" } }
        // A leg whose last word since it joined is silence has lost the room's sound.
        val silent = legs.filter { leg ->
            pbx.events("ChannelTalkingStart", joined) { it["Channel"] == leg }.isEmpty() ||
                pbx.events("ChannelTalkingStop", start) { it["Channel"] == leg }.size >
                pbx.events("ChannelTalkingStart", start) { it["Channel"] == leg }.size
        }
        val dropouts = legs.sumOf { leg -> pbx.events("ChannelTalkingStop", start) { it["Channel"] == leg }.size }
        val received = legs.map { (after[it]?.rxCount ?: 0) - (before[it]?.rxCount ?: 0) }
        val sent = legs.map { (after[it]?.txCount ?: 0) - (before[it]?.txCount ?: 0) }
        val expected = MEASURE_MS / PACKET_MS
        Log.i(
            TAG,
            "size=$size codec=$codec seconds=${MEASURE_MS / 1000}" +
                " cpu.avg=${cpu.average().format()} cpu.max=${cpu.maxOrNull()?.format()}" +
                " thread.avg=${thread.average().format()} thread.max=${thread.maxOrNull()?.format()}" +
                " thermal=$thermal lowMemory=$lowMemory poorLinks.max=$poor warnings=${room()?.warnings}" +
                " mainLag.max.ms=${mainLag.maxOrNull()}" +
                " pbx.rx.min=${received.minOrNull()} pbx.rx.max=${received.maxOrNull()} pbx.tx.min=${sent.minOrNull()} expected=$expected" +
                " pbx.rxlost.max=${legs.maxOfOrNull { (after[it]?.rxLost ?: 0) - (before[it]?.rxLost ?: 0) }}" +
                " pbx.rxjitter.max=${after.values.maxOfOrNull { it.rxJitter }}" +
                " silent=${silent.size} dropouts=$dropouts",
        )
        assertTrue("Members without the room's sound: $silent", silent.isEmpty())
        assertTrue("A member got too few packets of the app: $received", received.all { it >= expected * MIN_PACKET_SHARE })
    }

    /**
     * What the PBX counted of the RTP of each leg of the app, by channel: "pjsip show channelstats"
     * lists a leg as "1001-0000002a" with the packets it received from the app (and lost, and
     * their jitter in seconds) and those it sent.
     */
    private fun rtpStats(): Map<String, Rtp> = pbx.command("pjsip show channelstats").lineSequence()
        .mapNotNull { STATS_ROW.find(it) }
        .associate { row ->
            val (channel, rxCount, rxLost, rxJitter, txCount) = row.destructured
            "PJSIP/$channel" to Rtp(rxCount.toLong(), rxLost.toLong(), rxJitter.toDouble(), txCount.toLong())
        }

    private class Rtp(val rxCount: Long, val rxLost: Long, val rxJitter: Double, val txCount: Long)

    private fun Number.format(): String = "%.1f".format(toDouble())

    private companion object {
        const val TAG = "SipLinkRoomTest"

        /** A row of "pjsip show channelstats": channel, up time, codec, then received count, lost, percent, jitter, sent count. */
        val STATS_ROW = Regex("""(\d+-[0-9a-f]+)\s+\d+:\d+:\d+\s+\S+\s+(\d+)\s+(\d+)\s+\d+\s+([\d.]+)\s+(\d+)""")
        const val FIRST_MEMBER = 2501

        /** Members of the room in which a connection is made poor. */
        const val WARNING_ROOM = 5
        const val WARNING_TIMEOUT_MS = 120_000L
        const val FIRST_SIZE = 30
        const val BATCH = 10
        const val SETTLE_MS = 10_000L
        const val MEASURE_MS = 180_000L
        const val SAMPLE_MS = 2_000L

        /** G.711 is sent in packets of 20 ms. */
        const val PACKET_MS = 20L

        /** A member gets at least this share of the packets it should: a few are lost on a busy network. */
        const val MIN_PACKET_SHARE = 0.95
    }
}
