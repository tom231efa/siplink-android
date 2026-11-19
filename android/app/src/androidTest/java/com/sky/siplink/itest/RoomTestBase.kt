package com.sky.siplink.itest

import androidx.test.platform.app.InstrumentationRegistry
import com.sky.siplink.core.model.MemberState
import com.sky.siplink.core.model.RoomInfo
import com.sky.siplink.openCallScreen

/**
 * What the tests of the conference room share. The members are numbers of the test PBX
 * (testenv/asterisk/config/extensions.conf) that beep now and then and report what they hear.
 * The SDK only mixes with the sound card, so the app does not send its tone file into a room:
 * its microphone is muted instead.
 */
abstract class RoomTestBase(pictures: String) : ScreenTestBase(pictures) {
    protected val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    protected fun room(): RoomInfo? = call()?.room

    protected fun awaitRoom(what: String, match: (RoomInfo) -> Boolean): RoomInfo {
        var found: RoomInfo? = null
        await(what, LONG_TIMEOUT_MS) { room()?.takeIf(match)?.also { found = it } != null }
        return found!!
    }

    protected fun connected(room: RoomInfo): Int = room.members.count { it.state == MemberState.CONNECTED }

    /** The legs of the app on the PBX, in any state. */
    protected fun appLegs(): List<String> = pbxChannels().map { it["Channel"].orEmpty() }.filter { it.startsWith(pbxLeg(APP)) }

    /** The legs of the app on the PBX that are up. */
    protected fun appLegsUp(): List<String> = pbxChannels()
        .filter { it["Channel"].orEmpty().startsWith(pbxLeg(APP)) && it["ChannelStateDesc"] == "Up" }
        .map { it["Channel"].orEmpty() }

    /** Registers, calls [first], and shows the call screen, the microphone muted; the sound card plays the room. */
    protected fun callFirstMember(first: String = "2501") {
        onMain { engine.core.useFiles = false }
        showApp()
        register()
        dial(first)
        awaitConnected(first)
        onMain { engine.calls.toggleMute() }
        context.openCallScreen()
        callScreen()
    }

    /** A room with [first] and [others] in it, on screen. */
    protected fun openRoom(first: String, vararg others: String, connectedAtLeast: Int = 1 + others.size) {
        callFirstMember(first)
        ok(onMain { engine.calls.openRoom() })
        if (others.isNotEmpty()) ok(onMain { engine.calls.inviteToRoom(others.toList()) })
        awaitRoom("$connectedAtLeast members connected") { connected(it) >= connectedAtLeast }
    }
}
