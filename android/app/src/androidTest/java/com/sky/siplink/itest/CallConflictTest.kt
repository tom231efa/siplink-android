package com.sky.siplink.itest

import android.app.Notification
import android.app.NotificationManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import com.sky.siplink.core.model.CallPhase
import com.sky.siplink.core.model.HistoryKind
import com.sky.siplink.core.model.MemberState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The conference room and the other call features when something else acts on the calls: the
 * PBX ending every leg at once, a member whose sound stops, the network going away and coming
 * back, another app taking the sound, an intercom call next to a held call or the room, which is
 * not answered by itself even once it is alone. Each ends in a state from which the next call
 * works. (A supervised call and the room: SupervisionTest.)
 */
class CallConflictTest : RoomTestBase("conflicts") {
    /** Nothing is left of the calls: no call, no room, no call notification, the sound out of its call mode. */
    private fun assertAllClear() {
        awaitNoCall()
        assertEquals(0, onMain { engine.core.callsNb })
        // Left no room behind, the user in it: with no call left, only the core itself can tell.
        @Suppress("DEPRECATION")
        assertFalse(onMain { engine.core.isInConference })
        assertFalse(onMain { engine.calls.hasOngoingCall })
        await("the sound to leave its call mode") { context.getSystemService(AudioManager::class.java).mode == AudioManager.MODE_NORMAL }
        await("the call notification to go") {
            context.getSystemService(NotificationManager::class.java).activeNotifications
                .none { it.notification.category == Notification.CATEGORY_CALL }
        }
    }

    /** The next call works as ever. */
    private fun aCallStillWorks() {
        dial("2501")
        awaitConnected("2501")
        onMain { engine.calls.hangUp() }
        awaitNoCall()
    }

    @Test
    fun theRoomEndsCleanlyWhenThePbxEndsEveryLeg() {
        openRoom("2501", "2502", "2601", connectedAtLeast = 2)
        awaitRoom("a member ringing") { room -> room.members.any { it.state == MemberState.RINGING } }

        // All legs at once, the ringing one included.
        appLegs().forEach { pbx.command("channel request hangup $it") }
        assertEquals("通话结束", awaitEnded(LONG_TIMEOUT_MS).endReason)
        assertAllClear()
        // Every member's call is in the history, the one that never answered as not connected.
        val recent = engine.history.entries.value.take(3).associateBy { it.address.substringBefore('@') }
        assertEquals(HistoryKind.OUTGOING, recent["2501"]?.kind)
        assertEquals(HistoryKind.OUTGOING, recent["2502"]?.kind)
        assertEquals(HistoryKind.FAILED, recent["2601"]?.kind)
        aCallStillWorks()
    }

    @Test
    fun theRoomOutlivesAShortLossOfTheNetwork() {
        openRoom("2501", "2502")
        val legs = appLegs().toSet()
        // What the system tells the SDK when the phone loses its network for a moment.
        onMain { engine.core.setNetworkReachable(false) }
        Thread.sleep(NETWORK_GONE_MS)
        assertEquals("A member left while the network was gone", 2, connected(room()!!))
        val mark = pbx.mark()
        onMain { engine.core.setNetworkReachable(true) }
        // The SDK renews every leg (re-INVITE); the same legs go on, and the members hear each other again.
        for (leg in legs) pbx.awaitEvent("ChannelTalkingStart", mark, NETWORK_SETTLE_MS) { it["Channel"] == leg }
        assertEquals(legs, appLegs().toSet())
        assertEquals(2, connected(room()!!))
        onMain { engine.calls.hangUp() }
        assertAllClear()
    }

    @Test
    fun anotherAppTakingTheSoundTakesTheUserOutUntilTheyReturn() {
        openRoom("2501", "2502")
        val audio = context.getSystemService(AudioManager::class.java)
        // What a phone call of the mobile network does to the app: it takes the sound for itself.
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).build())
            .setOnAudioFocusChangeListener { }
            .build()
        assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED, audio.requestAudioFocus(request))
        try {
            awaitRoom("the user to be out of the room") { it.away }
            see("已暂离")
            picture("away")
            // The members go on among themselves.
            assertEquals(2, connected(room()!!))
        } finally {
            audio.abandonAudioFocusRequest(request)
        }
        tap("返回会议室")
        awaitRoom("the user back in the room") { !it.away }
        assertTrue(onMain { engine.core.calls.any { it.conference?.isIn == true } })
        onMain { engine.calls.hangUp() }
        assertAllClear()
    }

    @Test
    fun anIntercomCallNextToAHeldCallWaits() {
        settings { it.copy(autoAnswer = true, autoAnswerSeconds = 0, intercomAutoAnswer = true) }
        showApp()
        register()
        dial("2501")
        awaitConnected("2501")
        onMain { engine.calls.toggleHold() }
        awaitCall("the call on hold") { it.phase == CallPhase.HELD }

        phoneCalls(AGENT, "*80$APP")
        awaitCall("the intercom call to wait") { it.waitingCall != null }
        never("the intercom call answered by itself", QUIET_WINDOW_MS) { call()?.waitingCall == null }
        assertEquals("The held call was replaced", CallPhase.HELD, call()!!.phase)
        // The held call ends: the intercom call, which waited next to it, rings on and is left to the user.
        onMain { engine.calls.hangUp() }
        awaitCall("the intercom call to ring on its own") { it.phase == CallPhase.INCOMING && it.intercom }
        never("the intercom call answered by itself once alone", QUIET_WINDOW_MS) { call()?.phase == CallPhase.CONNECTED }
        onMain { engine.calls.decline() }
        await("the intercom call to be turned away") { !phoneHasCall(AGENT) }
        assertAllClear()
    }

    @Test
    fun anIntercomCallThatWaitedNextToTheRoomIsNotAnsweredWhenTheRoomEnds() {
        settings { it.copy(autoAnswer = true, autoAnswerSeconds = 0, intercomAutoAnswer = true) }
        openRoom("2501")
        phoneCalls(AGENT, "*80$APP")
        awaitCall("the intercom call to wait") { it.waitingCall != null && it.room != null }
        // The room ends: the intercom call takes the screen, ringing, and is left to the user.
        onMain { engine.calls.hangUp() }
        awaitCall("the intercom call to ring on its own") { it.room == null && it.phase == CallPhase.INCOMING && it.intercom }
        never("the intercom call answered by itself once the room was gone", QUIET_WINDOW_MS) { call()?.phase == CallPhase.CONNECTED }
        onMain { engine.calls.decline() }
        await("the intercom call to be turned away") { !phoneHasCall(AGENT) }
        assertAllClear()
    }

    private companion object {
        const val NETWORK_GONE_MS = 6_000L
        const val NETWORK_SETTLE_MS = 30_000L
    }
}
