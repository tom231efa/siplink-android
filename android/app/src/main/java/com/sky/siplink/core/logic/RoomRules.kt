package com.sky.siplink.core.logic

import com.sky.siplink.R
import com.sky.siplink.core.model.MemberState
import com.sky.siplink.core.model.Phrase

/** The rules of the conference room that need no SIP core: its size, who can still join, what a row says. */
object RoomRules {
    /**
     * How many members the room holds besides the user. Measured on a Galaxy Z Flip6 with the
     * SDK mixing G.711 (ConferenceLoadTest). It can never be more than [SDK_MIXER_INPUTS] allow.
     */
    const val LIMIT = 30

    /** The smallest room that can be set (Settings.roomLimit): the user and two members. */
    const val MIN_LIMIT = 2

    /** The size of the room a setting asks for, within what the room can hold. */
    fun limitOf(setting: Int): Int = setting.coerceIn(MIN_LIMIT, LIMIT)

    /**
     * How many calls the SIP core takes at once while a room is open: the room, one call waiting
     * next to it, and two more for the short overlaps while the PBX rearranges calls (REFER,
     * replaces) or a call that ended is still being released. A call beyond this the core refuses
     * as busy by itself.
     */
    const val MAX_CALLS = LIMIT + 3

    /** The same without a room: the call that is up, one held or waiting next to it, and the same two overlaps. */
    const val MAX_CALLS_WITHOUT_ROOM = 4

    /**
     * The inputs of the SDK's audio mixer (MIXER_MAX_CHANNELS in mediastreamer2's audiomixer.c,
     * SDK 5.5.39): the user's own sound takes one, every call in the room one more. A call beyond
     * them aborts the whole app ("No more free pin in mixer filter"), as the 50th member did on
     * the test phone.
     */
    const val SDK_MIXER_INPUTS = 50

    /** How long a member that left stays in the list with the reason before its row goes. */
    const val LEFT_LINGER_MS = 2_500L

    /**
     * How long after the rows of the member list stopped moving a tap on the hang up button of a
     * row is taken. A finger that stops a fling stops the list on touching it and lifts about
     * 100 ms later: that lift is no tap meant for the row it happens to be on. Nor is a tap aimed
     * at a row that a row above it, going, moved away under the finger.
     */
    const val ROW_TAP_GUARD_MS = 400L

    /** How long the rows after one that comes or goes take to slide into their places. */
    const val ROW_MOVE_MS = 250L

    /** Whether a tap on the hang up button of a row counts, [sinceMovedMs] after the rows last moved (negative while they still slide). */
    fun rowTapTaken(scrolling: Boolean, sinceMovedMs: Long): Boolean = !scrolling && sinceMovedMs >= ROW_TAP_GUARD_MS

    /** How many more members fit into a room that has [present]. */
    fun free(present: Int, limit: Int = LIMIT): Int = (limit - present).coerceAtLeast(0)

    /** Null when [adding] more members fit into a room that has [present]; else the notice that refuses them. */
    fun refusal(present: Int, adding: Int, limit: Int = LIMIT): Phrase? {
        val free = free(present, limit)
        return when {
            adding <= free -> null
            free == 0 -> Phrase.count(R.plurals.room_full, limit)
            else -> Phrase.count(R.plurals.room_free, free)
        }
    }

    /** The count in the title of the room: "12/30". */
    fun count(present: Int, limit: Int = LIMIT): String = "$present/$limit"

    /**
     * The targets to call: without blanks, each once, and without those already in the room.
     *
     * @param inRoom the user parts (numbers) of the members that are in the room or on their way in
     */
    fun newTargets(targets: List<String>, inRoom: Collection<String>): List<String> {
        val taken = inRoom.mapTo(HashSet()) { it.lowercase() }
        val result = LinkedHashMap<String, String>()
        for (target in targets) {
            val cleaned = target.filterNot { it.isWhitespace() }
            if (cleaned.isEmpty()) continue
            val user = Formatters.userPart(cleaned).lowercase()
            if (user in taken || user in result) continue
            result[user] = cleaned
        }
        return result.values.toList()
    }

    /** What the state of a member reads in its row; a member that left shows why ([endReason], as the snapshot rendered it). */
    fun stateLabel(state: MemberState, endReason: String?): Phrase = when (state) {
        MemberState.QUEUED -> Phrase.of(R.string.room_member_queued)
        MemberState.CALLING -> Phrase.of(R.string.room_member_calling)
        MemberState.RINGING -> Phrase.of(R.string.room_member_ringing)
        MemberState.CONNECTED -> Phrase.of(R.string.room_member_connected)
        MemberState.HELD -> Phrase.of(R.string.room_member_held)
        MemberState.LEFT -> endReason?.let(Phrase::plain) ?: Phrase.of(R.string.room_member_left)
        MemberState.HELD_BACK -> endReason?.let(Phrase::plain) ?: Phrase.of(R.string.room_member_held_back)
    }
}
