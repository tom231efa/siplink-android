package com.sky.siplink.core.model

/** Where a member of the conference room stands. */
enum class MemberState {
    /** Waits for its turn to be called (the room calls a few at a time). */
    QUEUED,

    /** Being called, or being taken into the room. */
    CALLING,
    RINGING,
    CONNECTED,

    /** The member put the call on hold: it hears nothing and is not heard. */
    HELD,

    /** The call ended; the row stays a moment with the reason, then goes. */
    LEFT,

    /** Not called, or its call failed, after an answer of the PBX; the row stays with the reason until the user calls again. */
    HELD_BACK,
}

/** What was measured of the connection to one member (see [com.sky.siplink.core.logic.RoomHealth]). */
data class LinkReadings(
    /** The SDK's quality of the call, 0 (worst) to 5; null while it has none. */
    val quality: Float?,
    /** Packets lost on the way to the member, as the member reports them, in percent. */
    val lossUp: Float,
    /** Packets of the member lost on the way to the phone, in percent. */
    val lossDown: Float,
    /** Interarrival jitter of what the phone receives, in milliseconds. */
    val jitterMs: Float,
    /** Round trip time, in milliseconds; 0 until a report has come back. */
    val roundTripMs: Float,
)

/** One person in the conference room besides the user. */
data class RoomMember(
    /** The same for as long as the member's call exists: the key of its row. */
    val id: Long,
    val name: String,
    val address: String,
    val state: MemberState,
    /** Why the member left ([MemberState.LEFT]) or is not called ([MemberState.HELD_BACK]). */
    val endReason: String? = null,
    /** The connection to the member has been poor for some seconds in a row. */
    val poorLink: Boolean = false,
    /** The quality of the connection in bars, 1 to 3 ([com.sky.siplink.core.logic.RoomHealth.signalBars]); null before it was measured. */
    val signal: Int? = null,
    /** The last measurement of a connection that is poor ([poorLink]); null otherwise. */
    val link: LinkReadings? = null,
) {
    /** In the room, on its way in, or waiting to be called: it counts against the limit and is not called again. */
    val taken: Boolean get() = state != MemberState.LEFT && state != MemberState.HELD_BACK
}

/** What a warning of the conference room is about. */
enum class RoomWarning { LOAD, HEAT, MEMORY, NETWORK }

/** What was measured of the phone itself (see [com.sky.siplink.core.logic.RoomHealth]). */
data class DeviceReadings(
    /** CPU time of the app's process, in percent of all cores together. */
    val cpuPercent: Float,
    /** CPU time of the busiest thread of the process, in percent of one core: the room's mixer runs in one. */
    val busiestThreadPercent: Float,
    /** One of PowerManager.THERMAL_STATUS_*; null where the system does not tell. */
    val thermalStatus: Int?,
    /** The system says that memory runs low. */
    val lowMemory: Boolean,
)

/**
 * The conference room: the phone is the host, and a local conference of the SIP core mixes the
 * sound of all the calls in it. There is no video in it.
 */
data class RoomInfo(
    /** In the order they came in; those that left stay a moment ([MemberState.LEFT]). */
    val members: List<RoomMember>,
    /** How many members it holds at most besides the user. */
    val limit: Int,
    /** SystemClock.elapsedRealtime() when the room was opened. */
    val openedAt: Long,
    /** The warnings that stand right now; empty while all is well. */
    val warnings: Set<RoomWarning> = emptySet(),
    /** The last measurement of the phone; null before the first one. */
    val device: DeviceReadings? = null,
    /** The user is out of the room's sound (another app took it); the members go on among themselves. */
    val away: Boolean = false,
) {
    /** The members that are in the room, on their way in, or waiting to be called: what the limit counts. */
    val present: Int get() = members.count { it.taken }
}
