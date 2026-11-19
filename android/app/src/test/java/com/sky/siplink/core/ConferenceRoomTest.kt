package com.sky.siplink.core

import android.content.ContextWrapper
import android.os.Handler
import android.os.SystemClock
import com.sky.siplink.core.logic.RoomPacing
import com.sky.siplink.core.logic.RoomRules
import com.sky.siplink.core.model.MemberState
import com.sky.siplink.core.model.RoomMember
import com.sky.siplink.core.model.SideCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.linphone.core.Account
import org.linphone.core.Address
import org.linphone.core.Call
import org.linphone.core.Conference
import org.linphone.core.Core
import org.linphone.core.ErrorInfo
import java.lang.reflect.Proxy

/**
 * The conference room with SDK objects of its own (the SDK's Java classes are interfaces): the
 * calls are driven the way CallManager passes the SDK's reports on (onStateChanged first, then
 * onInvited, replace, onStreaming, onReleased), on a clock the test moves (android.os fakes of
 * the test sources).
 */
class ConferenceRoomTest {
    private var nextPointer = 100L
    private val calls = ArrayList<FakeCall>()
    private var limit = RoomRules.LIMIT
    private var lineSwitchedOn = true
    private var maxCalls = RoomRules.MAX_CALLS_WITHOUT_ROOM
    private var conferencesCreated = 0
    private var conferenceTerminated = false
    private var addFails: (FakeCall) -> Boolean = { false }
    private var inviteCreatesCall = true
    private var inRoomSound = true

    /** The users whose calls the room ended, in order; and those it took out of the conference again. */
    private val terminated = ArrayList<String>()
    private val removed = ArrayList<String>()
    private lateinit var room: ConferenceRoom

    inner class FakeCall(val user: String, var state: Call.State) {
        private val pointer = nextPointer++
        var code = 0
        var retryAfter = 0
        val proxy: Call = fake(Call::class.java) { name, _ ->
            when (name) {
                "getState" -> state
                "getNativePointer" -> pointer
                "getRemoteAddress" -> address(user)
                "getErrorInfo" -> fake(ErrorInfo::class.java) { info, _ ->
                    when (info) {
                        "getProtocolCode" -> code
                        "getRetryAfter" -> retryAfter
                        else -> null
                    }
                }
                "terminate" -> {
                    terminated += user
                    state = Call.State.End
                    0
                }
                else -> null
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> fake(type: Class<T>, answer: (String, Array<Any?>?) -> Any?): T =
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args!![0]
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "fake ${type.simpleName}"
                else -> answer(method.name, args) ?: default(method.returnType)
            }
        } as T

    private fun default(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        else -> if (type.isInterface && type.name.startsWith("org.linphone")) fake(type) { _, _ -> null } else null
    }

    private fun address(user: String): Address = fake(Address::class.java) { name, _ ->
        when (name) {
            "getUsername" -> user
            "getDomain" -> "pbx"
            else -> null
        }
    }

    private val account: Account = fake(Account::class.java) { name, args ->
        when (name) {
            "normalizeSipUri" -> address((args!![0] as String).removePrefix("sip:").substringBefore('@'))
            else -> null
        }
    }

    private fun callOf(proxy: Any?): FakeCall = calls.first { it.proxy === proxy }

    private val conference: Conference = fake(Conference::class.java) { name, args ->
        when (name) {
            "addParticipant" -> if (addFails(callOf(args!![0]))) -1 else 0
            "removeParticipant" -> {
                removed += callOf(args!![0]).user
                0
            }
            "inviteParticipants" -> {
                // The SDK reports OutgoingInit from inside: CallManager hands it to onInvited.
                if (inviteCreatesCall) {
                    val user = ((args!![0] as Array<*>).single() as Address).username!!
                    val call = FakeCall(user, Call.State.OutgoingInit).also { calls += it }
                    room.onStateChanged(call.proxy, Call.State.OutgoingInit)
                    room.onInvited(call.proxy)
                }
                0
            }
            "terminate" -> {
                conferenceTerminated = true
                0
            }
            "isIn" -> inRoomSound
            "leave" -> {
                inRoomSound = false
                0
            }
            "getAccount" -> account
            else -> null
        }
    }

    private val core: Core = fake(Core::class.java) { name, args ->
        when (name) {
            "createConferenceWithParams" -> {
                conferencesCreated++
                conference
            }
            "getCallsNb" -> calls.count { !CallSupport.isEnded(it.proxy) }
            "getMaxCalls" -> maxCalls
            "setMaxCalls" -> {
                maxCalls = args!![0] as Int
                null
            }
            "getDefaultAccount" -> account
            else -> null
        }
    }

    @Before
    fun setUp() {
        Handler.reset()
        SystemClock.now = 1_000_000L
        room = ConferenceRoom(
            core,
            RoomMonitor(ContextWrapper(null)),
            party = { call -> SideCall(call.remoteAddress.username!!, call.remoteAddress.username + "@pbx") },
            limit = { limit },
            lineOn = { lineSwitchedOn },
        ) {}
    }

    private fun openWith(vararg users: String): List<FakeCall> {
        val up = users.map { FakeCall(it, Call.State.StreamsRunning) }
        calls += up
        assertNull(room.open(up.map { it.proxy }, muted = false))
        return up
    }

    private fun byUser(user: String): FakeCall = calls.last { it.user == user }

    private fun state(call: FakeCall, state: Call.State) {
        call.state = state
        room.onStateChanged(call.proxy, state)
        if (state == Call.State.StreamsRunning) room.onStreaming(call.proxy)
    }

    private fun answer(call: FakeCall) {
        state(call, Call.State.Connected)
        state(call, Call.State.StreamsRunning)
    }

    /** The other side ends the call, and the SDK lets it go. */
    private fun hangUpThere(call: FakeCall) {
        state(call, Call.State.End)
        release(call)
    }

    private fun release(call: FakeCall) {
        call.state = Call.State.Released
        room.onReleased(call.proxy)
    }

    private fun after(ms: Long) = Handler.advanceTo(SystemClock.now + ms)

    /** Moves the clock on without running what is due meanwhile (an hour of looks at the room is of no use here). */
    private fun skip(ms: Long) {
        SystemClock.now += ms
    }

    private fun rows(): List<RoomMember> = room.snapshot()!!.members

    private fun row(user: String): RoomMember? = rows().firstOrNull { it.name == user }

    private fun heldBack(label: String): List<String> = rows().filter { it.state == MemberState.HELD_BACK && it.endReason == label }.map { it.name }

    @Test
    fun `a call the PBX puts in place of a member takes its place, one more than fits is refused`() {
        limit = 2
        val (first, _) = openWith("2501", "2502")
        // REFER on 2501's call: the SDK places the new call, which takes 2501's place in the full room.
        val referred = FakeCall("2601", Call.State.OutgoingInit).also { calls += it }
        room.onStateChanged(referred.proxy, Call.State.OutgoingInit)
        assertTrue(room.replace(referred.proxy, first.proxy))
        // Another REFER on the same call before the first new call is up: there is no place for it.
        val again = FakeCall("2602", Call.State.OutgoingInit).also { calls += it }
        assertFalse(room.replace(again.proxy, first.proxy))

        // Up, the new call ends the old one, whose row goes at once.
        answer(referred)
        assertEquals(listOf("2501"), terminated)
        state(first, Call.State.End)
        release(first)
        assertEquals(listOf("2502", "2601"), rows().map { it.name })
        assertEquals(2, room.snapshot()!!.present)
        // In the place of a call that is gone, a REFER needs a free place.
        assertFalse(room.replace(FakeCall("2603", Call.State.OutgoingInit).also { calls += it }.proxy, first.proxy))
    }

    @Test
    fun `a member whose call the PBX replaces leaves no row and stops nobody`() {
        openWith("2501")
        assertNull(room.invite(listOf("2601", "2602", "2603", "2604", "2605")))
        val member = byUser("2601")
        answer(member)
        after(2_000)
        val referred = FakeCall("2700", Call.State.OutgoingInit).also { calls += it }
        room.onStateChanged(referred.proxy, Call.State.OutgoingInit)
        assertTrue(room.replace(referred.proxy, member.proxy))
        // Up, the new call ends 2601's after two seconds: no short call.
        answer(referred)
        state(member, Call.State.End)
        after(100)
        release(member)
        after(10_000)
        assertNull(row("2601"))
        assertEquals(MemberState.CONNECTED, row("2700")?.state)
        assertTrue(rows().none { it.state == MemberState.HELD_BACK })
        assertTrue("Those waiting are called on", rows().none { it.state == MemberState.QUEUED })
    }

    @Test
    fun `a call that comes in to replace a member's takes its place`() {
        limit = 2
        val (first, _) = openWith("2501", "2502")
        val replacing = FakeCall("2601", Call.State.IncomingReceived).also { calls += it }
        assertTrue(room.replace(replacing.proxy, first.proxy))
        // Nor can another call be put in the place that one takes already: the room is full.
        assertFalse(room.replace(FakeCall("2602", Call.State.IncomingReceived).also { calls += it }.proxy, first.proxy))
        // The SDK answers it and ends the old call by itself.
        state(first, Call.State.End)
        release(first)
        answer(replacing)
        assertEquals(listOf("2502", "2601"), rows().map { it.name })
        assertTrue("The room ended a call", terminated.isEmpty())
    }

    @Test
    fun `one member that hangs up right after answering only pauses the calling`() {
        openWith("2501")
        assertNull(room.invite((1..10).map { "26%02d".format(it) }))
        val first = byUser("2601")
        answer(first)
        after(5_000)
        hangUpThere(first)
        val placed = calls.size
        after(RoomPacing.SHORT_CALL_PAUSE_MS - 1)
        assertEquals("Somebody was called during the pause", placed, calls.size)
        assertEquals("The row went while the run is open", MemberState.LEFT, row("2601")?.state)
        after(30_000)
        assertTrue("The calling did not go on", calls.size > placed)
        assertTrue(rows().none { it.state == MemberState.HELD_BACK })
        assertNull("The row stayed after the run", row("2601"))
    }

    @Test
    fun `two members in a row that hang up right after answering are the line's limit`() {
        openWith("2501")
        val numbers = (1..9).map { "24%02d".format(it) }
        assertNull(room.invite(numbers))
        val (first, second) = listOf(byUser("2401"), byUser("2402"))
        answer(first)
        answer(second)
        after(1_000)
        hangUpThere(first)
        // Longer than a row of one that left stays otherwise.
        after(RoomRules.LEFT_LINGER_MS + 500)
        hangUpThere(second)
        after(60_000)
        val placed = calls.map { it.user }.filter { it.startsWith("24") }
        assertEquals(listOf("2401", "2402", "2403", "2404", "2405"), placed)
        assertEquals(listOf("2401", "2402") + numbers.drop(placed.size), heldBack(RoomPacing.Failure.LINE_FULL.label))
        assertTrue(rows().none { it.state == MemberState.QUEUED })
    }

    @Test
    fun `a call the line took between two short ones breaks the run`() {
        openWith("2501")
        assertNull(room.invite(listOf("2601", "2602", "2603", "2604", "2605")))
        val (a, b, c) = listOf(byUser("2601"), byUser("2602"), byUser("2603"))
        answer(a)
        after(1_000)
        hangUpThere(a)
        after(100)
        answer(b)
        after(RoomPacing.SHORT_CALL_MS)
        answer(c)
        after(1_000)
        hangUpThere(c)
        after(60_000)
        assertTrue(rows().none { it.state == MemberState.HELD_BACK })
        assertTrue(rows().none { it.state == MemberState.QUEUED })
    }

    @Test
    fun `retry after holds for an hour at most, and calling the number again waits for it too`() {
        openWith("2501")
        assertNull(room.invite(listOf("2601")))
        val call = byUser("2601")
        call.code = 503
        call.retryAfter = Int.MAX_VALUE
        state(call, Call.State.Error)
        release(call)
        val id = row("2601")!!.id
        assertEquals("请稍后重试", room.retry(id))
        assertEquals("请稍后重试", room.invite(listOf("2601")))
        skip(RoomPacing.MAX_RETRY_AFTER_S * 1000L - 1)
        assertEquals("请稍后重试", room.invite(listOf("2601")))
        skip(1)
        assertNull(room.invite(listOf("2601")))
        assertEquals("Not called again in its own row", id, row("2601")!!.id)
        assertEquals(MemberState.CALLING, row("2601")!!.state)
    }

    @Test
    fun `ending the room twice ends each call once and the room closes`() {
        openWith("2501")
        assertNull(room.invite(listOf("2601", "2602")))
        room.end()
        room.end()
        assertEquals(listOf("2501", "2601", "2602"), terminated)
        calls.toList().forEach {
            state(it, Call.State.End)
            release(it)
        }
        assertFalse(room.isOpen)
    }

    @Test
    fun `the core takes the room's calls only while it is open`() {
        val (only) = openWith("2501")
        assertEquals(RoomRules.MAX_CALLS, maxCalls)
        hangUpThere(only)
        assertFalse(room.isOpen)
        assertEquals(RoomRules.MAX_CALLS_WITHOUT_ROOM, maxCalls)
    }

    @Test
    fun `a room that cannot be opened gives its calls back without ending them`() {
        val up = listOf(FakeCall("2501", Call.State.StreamsRunning), FakeCall("2502", Call.State.Paused)).also { calls += it }
        addFails = { it.user == "2502" }
        assertEquals("无法创建会议室", room.open(up.map { it.proxy }, muted = false))
        assertEquals(listOf("2501"), removed)
        assertTrue(terminated.isEmpty())
        assertFalse(conferenceTerminated)
        assertFalse(room.isOpen)
        assertEquals(RoomRules.MAX_CALLS_WITHOUT_ROOM, maxCalls)
    }

    @Test
    fun `calls whose media are being agreed on are not taken into a room`() {
        val up = listOf(FakeCall("2501", Call.State.StreamsRunning), FakeCall("2502", Call.State.UpdatedByRemote)).also { calls += it }
        assertEquals("请稍后再试", room.open(up.map { it.proxy }, muted = false))
        assertEquals(0, conferencesCreated)
    }

    @Test
    fun `a member whose call the SDK did not place can be called again or taken off`() {
        openWith("2501")
        inviteCreatesCall = false
        assertNull(room.invite(listOf("2601")))
        assertEquals(MemberState.HELD_BACK, row("2601")?.state)
        assertEquals("呼叫失败", row("2601")?.endReason)
        room.hangUp(row("2601")!!.id)
        assertNull(row("2601"))
    }

    @Test
    fun `nobody is called on a line that is switched off`() {
        openWith("2501")
        lineSwitchedOn = false
        assertEquals("线路已停用", room.invite(listOf("2601")))
        lineSwitchedOn = true
        assertNull(room.invite(listOf("2601", "2602", "2603", "2604")))
        // Switched off while one waits for its turn: it is not called.
        lineSwitchedOn = false
        answer(byUser("2601"))
        after(100)
        assertEquals(listOf("2604"), heldBack("线路已停用"))
        assertEquals(listOf("2601", "2602", "2603"), calls.map { it.user }.filter { it.startsWith("26") })
    }
}
