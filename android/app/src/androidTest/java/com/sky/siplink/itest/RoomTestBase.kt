package com.sky.siplink.itest

import android.graphics.Rect
import androidx.test.platform.app.InstrumentationRegistry
import com.sky.siplink.core.logic.RoomRules
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

    /**
     * Taps [text], a button in the rows of the members, once the rows stood still long enough for
     * the tap to be taken ([RoomRules.rowTapTaken]): a row that comes, goes or changes its height
     * moves those after it.
     */
    protected fun tapRow(text: String) {
        awaitRowsStill(text)
        tap(text)
    }

    /** Waits until the rows of the members show [text] and have stood still for longer than a tap is kept back after they moved. */
    protected fun awaitRowsStill(text: String) {
        val bounds = Rect()
        var before = ""
        var since = System.currentTimeMillis()
        await("the rows to stand still") {
            val now = nodes().filter { node -> ROW_ACTIONS.any { node.contentDescription?.startsWith(it) == true } }
                .joinToString("|") { node -> node.getBoundsInScreen(bounds); "${node.contentDescription}/${bounds.toShortString()}" }
            if (now != before) {
                before = now
                since = System.currentTimeMillis()
            }
            now.contains(text) && System.currentTimeMillis() - since > STILL_MS
        }
    }

    /** A room with [first] and [others] in it, on screen. */
    protected fun openRoom(first: String, vararg others: String, connectedAtLeast: Int = 1 + others.size) {
        callFirstMember(first)
        ok(onMain { engine.calls.openRoom() })
        if (others.isNotEmpty()) ok(onMain { engine.calls.inviteToRoom(others.toList()) })
        awaitRoom("$connectedAtLeast members connected") { connected(it) >= connectedAtLeast }
    }

    private companion object {
        /** What the buttons of a row say before the name of the member. */
        val ROW_ACTIONS = listOf("挂断 ", "移除 ", "重拨 ")

        /** How long the rows stand still before a tap: they slide, and then a tap is still kept back a moment. */
        const val STILL_MS = RoomRules.ROW_MOVE_MS + RoomRules.ROW_TAP_GUARD_MS + 100
    }
}
