package com.sky.siplink.core.logic

import com.sky.siplink.core.model.MemberState

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
     * How many calls the SIP core takes at once: the room, one call waiting next to it, and two
     * more for the short overlaps while the PBX rearranges calls (REFER, replaces) or a call that
     * ended is still being released. A call beyond this the core refuses as busy by itself.
     */
    const val MAX_CALLS = LIMIT + 3

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
     * How long after the member list stopped moving a tap on the hang up button of a row is
     * taken. A finger that stops a fling stops the list on touching it and lifts about 100 ms
     * later: that lift is no tap meant for the row it happens to be on.
     */
    const val ROW_TAP_GUARD_MS = 400L

    /** Whether a tap on the hang up button of a row counts, [sinceStoppedMs] after the list last moved. */
    fun rowTapTaken(scrolling: Boolean, sinceStoppedMs: Long): Boolean = !scrolling && sinceStoppedMs >= ROW_TAP_GUARD_MS

    /** How many more members fit into a room that has [present]. */
    fun free(present: Int, limit: Int = LIMIT): Int = (limit - present).coerceAtLeast(0)

    /** Null when [adding] more members fit into a room that has [present]; else the notice that refuses them. */
    fun refusal(present: Int, adding: Int, limit: Int = LIMIT): String? {
        val free = free(present, limit)
        return when {
            adding <= free -> null
            free == 0 -> "会议室已满（最多 $limit 方）"
            else -> "最多还可添加 $free 方"
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

    /** What the state of a member reads in its row; a member that left shows why. */
    fun stateLabel(state: MemberState, endReason: String?): String = when (state) {
        MemberState.QUEUED -> "等待呼叫"
        MemberState.CALLING -> "呼叫中"
        MemberState.RINGING -> "振铃"
        MemberState.CONNECTED -> "已接通"
        MemberState.HELD -> "已保留"
        MemberState.LEFT -> endReason ?: "已离开"
        MemberState.HELD_BACK -> endReason ?: "未呼叫"
    }
}
