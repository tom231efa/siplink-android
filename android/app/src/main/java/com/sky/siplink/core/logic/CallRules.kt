package com.sky.siplink.core.logic

import androidx.annotation.StringRes
import com.sky.siplink.R
import com.sky.siplink.core.model.FeatureCodes
import com.sky.siplink.core.model.SupervisionMode

/**
 * Which feature wins where two of them could act on the same calls. The rules, highest first;
 * [com.sky.siplink.core.CallManager] follows them, and those that need no SIP core are here.
 *
 * 1. Do not disturb and the forwarding rules decide first about every call that comes in, an
 *    intercom call or one next to the room included ([IncomingPolicy]).
 * 2. Next to a call that is up, held or a room, a call that comes in only ever waits (call
 *    waiting; off, it is refused as busy). It is never answered by itself, intercom or not, not
 *    even once the call or the room next to it has ended ([AutoAnswerRules.delayMs]), and never
 *    takes the place of the call that is up ([mayAnswerByItself]); next to the room it can only be
 *    answered into the room (one that does not fit keeps ringing), next to a call it puts that
 *    call on hold.
 * 3. A supervised call (listen, whisper, barge) stays a call of its own: it cannot become or join
 *    the room ([roomRefusal], [memberRefusal]), so that what it listens to never reaches others.
 *    Its mode keys go to that call alone, and only while its sound runs ([supervisionKeysGo]);
 *    the same keys typed on its keypad then change the mode shown with it ([modeAfterKey]).
 * 4. The room has no keypad, no transfer, no park, no recording and no video: what the keys,
 *    transfers and video requests act on is the one current call, and the room has none. A video
 *    request of a member is declined, a room is never recorded and opening one ends recordings.
 * 5. While a transfer or a park is under way the calls involved stay as they are: no room.
 * 6. Ending the room for everyone is asked for again once more than one member is in it
 *    ([confirmRoomEnd]); ending one member's call is its own key in that member's row.
 * 7. The room calls its members a few at a time and stops when the PBX says the line has had
 *    enough ([RoomPacing]); it holds at most what the setting allows ([RoomRules.limitOf]), a
 *    call the PBX puts in place of a member's included, and the SIP core takes no more calls than
 *    the room, one waiting call and the overlaps of a transfer ([RoomRules.MAX_CALLS]; without a
 *    room [RoomRules.MAX_CALLS_WITHOUT_ROOM]).
 */
object CallRules {
    /**
     * Whether a ringing call may be answered by itself (auto answer, intercom): only while no other
     * call is up, held or not, and no room is open.
     */
    fun mayAnswerByItself(otherCallUp: Boolean, roomOpen: Boolean): Boolean = !otherCallUp && !roomOpen

    /** Null when the calls may become a room; else why not. */
    @StringRes
    fun roomRefusal(supervised: Boolean, transferring: Boolean): Int? = when {
        supervised -> SUPERVISED
        transferring -> R.string.call_room_transferring
        else -> null
    }

    /** Null when [target] may be called into the room; a supervision or force hang-up code may not. */
    @StringRes
    fun memberRefusal(target: String, codes: FeatureCodes): Int? {
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

    /**
     * The mode shown with a call after [digit] was typed on its keypad: a mode key changes it
     * only where it goes out as one ([supervisionKeysGo]); [mode] is null for a call that is not
     * supervised.
     */
    fun modeAfterKey(mode: SupervisionMode?, digit: Char, streaming: Boolean, roomOpen: Boolean): SupervisionMode? =
        if (supervisionKeysGo(mode != null, streaming, roomOpen)) supervisionModeOf(digit) ?: mode else mode

    /** Whether ending the room for everyone is asked for again: with more than one member in it. */
    fun confirmRoomEnd(present: Int): Boolean = present > 1

    /**
     * Whether the end of a room the user ended is shown at once, while its calls still go down:
     * not when a call waits next to the room, which takes the screen once the room is closed.
     */
    fun showsRoomEndAtOnce(callWaiting: Boolean): Boolean = !callWaiting

    /**
     * How long from [now] the end of a call stays on screen: [lingerMs] from the moment it was
     * shown ([shownAt]; null: it is shown now), and no longer once that is over.
     */
    fun endLingerMs(shownAt: Long?, now: Long, lingerMs: Long): Long = if (shownAt == null) lingerMs else (shownAt + lingerMs - now).coerceAtLeast(0)

    private val SUPERVISED = R.string.call_room_supervised
}
