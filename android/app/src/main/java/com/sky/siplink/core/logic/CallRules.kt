package com.sky.siplink.core.logic

import com.sky.siplink.core.model.FeatureCodes
import com.sky.siplink.core.model.SupervisionMode

/**
 * Which feature wins where two of them could act on the same calls. The rules, highest first;
 * [com.sky.siplink.core.CallManager] follows them, and those that need no SIP core are here.
 *
 * 1. Do not disturb and the forwarding rules decide first about every call that comes in, an
 *    intercom call or one next to the room included ([IncomingPolicy]).
 * 2. Next to a call that is up, held or a room, a call that comes in only ever waits (call
 *    waiting; off, it is refused as busy). It is never answered by itself, intercom or not, and
 *    never takes the place of the call that is up ([mayAnswerByItself]); next to the room it can
 *    only be answered into the room, next to a call it puts that call on hold.
 * 3. A supervised call (listen, whisper, barge) stays a call of its own: it cannot become or join
 *    the room ([roomRefusal], [memberRefusal]), so that what it listens to never reaches others.
 *    Its mode keys go to that call alone, and only while its sound runs ([supervisionKeysGo]);
 *    the same keys typed on its keypad change the mode shown with it ([supervisionModeOf]).
 * 4. The room has no keypad, no transfer, no park, no recording and no video: what the keys,
 *    transfers and video requests act on is the one current call, and the room has none. A video
 *    request of a member is declined, a room is never recorded and opening one ends recordings.
 * 5. While a transfer or a park is under way the calls involved stay as they are: no room.
 * 6. Ending the room for everyone is asked for again once more than one member is in it
 *    ([confirmRoomEnd]); ending one member's call is its own key in that member's row.
 * 7. The room calls its members a few at a time and stops when the PBX says the line has had
 *    enough ([RoomPacing]); it holds at most what the setting allows ([RoomRules.limitOf]), and
 *    the SIP core takes no more calls than the room, one waiting call and the overlaps of a
 *    transfer ([RoomRules.MAX_CALLS]).
 */
object CallRules {
    /**
     * Whether a ringing call may be answered by itself (auto answer, intercom): only while no other
     * call is up, held or not, and no room is open.
     */
    fun mayAnswerByItself(otherCallUp: Boolean, roomOpen: Boolean): Boolean = !otherCallUp && !roomOpen

    /** Null when the calls may become a room; else why not. */
    fun roomRefusal(supervised: Boolean, transferring: Boolean): String? = when {
        supervised -> SUPERVISED
        transferring -> "转接进行中"
        else -> null
    }

    /** Null when [target] may be called into the room; a supervision or force hang-up code may not. */
    fun memberRefusal(target: String, codes: FeatureCodes): String? {
        val action = FeatureDial.parse(FeatureDial.extensionOf(target), codes)?.first ?: return null
        return SUPERVISED.takeIf {
            FeatureDial.supervisionOf(action) != null || action == FeatureDial.Action.FORCE_HANG_UP
        }
    }

    /** Whether the keys of a supervision mode go out: to the supervised call while its sound runs, never in the room. */
    fun supervisionKeysGo(supervised: Boolean, streaming: Boolean, roomOpen: Boolean): Boolean =
        supervised && streaming && !roomOpen

    /** The supervision mode a key typed on the keypad of a supervised call switches to; null for other keys. */
    fun supervisionModeOf(digit: Char): SupervisionMode? = SupervisionMode.entries.firstOrNull { it.dtmf == digit }

    /** Whether ending the room for everyone is asked for again: with more than one member in it. */
    fun confirmRoomEnd(present: Int): Boolean = present > 1

    private const val SUPERVISED = "监听通话不能加入会议室"
}
