package com.sky.siplink.core.logic

import androidx.annotation.StringRes
import com.sky.siplink.R
import com.sky.siplink.core.model.FeatureCodes
import com.sky.siplink.core.model.Phrase
import com.sky.siplink.core.model.SupervisionMode

/** Builds the dial strings of the PBX features that act on an extension: a feature code followed by it. */
object FeatureDial {
    enum class Action(@StringRes val label: Int) {
        INTERCOM(R.string.feature_intercom),
        LISTEN(R.string.feature_listen),
        WHISPER(R.string.feature_whisper),
        BARGE(R.string.feature_barge),
        FORCE_HANG_UP(R.string.feature_force_hang_up),
        PICKUP(R.string.feature_pickup),
    }

    /**
     * Extension part of whatever the user picked: `sip:1002@pbx;x=y` -> `1002`. Its escapes are
     * read as the PBX reads them: `sip:%2A911002@pbx` dials `*911002`.
     */
    fun extensionOf(target: String): String = Formatters.unescaped(Formatters.userPart(target.trim())).filterNot { it.isWhitespace() }

    /** @return the string to dial, or null when the code is not configured or the target is empty */
    fun compose(action: Action, target: String, codes: FeatureCodes): String? {
        val extension = extensionOf(target)
        val prefix = codeOf(action, codes)
        if (prefix.isEmpty() || extension.isEmpty()) return null
        return prefix + extension
    }

    private fun codeOf(action: Action, codes: FeatureCodes): String = when (action) {
        Action.INTERCOM -> codes.intercom
        Action.LISTEN -> codes.listen
        Action.WHISPER -> codes.whisper
        Action.BARGE -> codes.barge
        Action.FORCE_HANG_UP -> codes.forceHangUp
        Action.PICKUP -> codes.pickup
    }.trim()

    /**
     * The opposite of [compose]: the feature and the extension behind a dialled number such as
     * `*801005`. Null when the number starts with none of the codes, or nothing but digits
     * follows the code. Of two codes that fit, the longer one is meant.
     */
    fun parse(number: String, codes: FeatureCodes): Pair<Action, String>? = Action.entries
        .map { it to codeOf(it, codes) }
        .filter { (_, code) ->
            code.isNotEmpty() && number.length > code.length && number.startsWith(code) &&
                number.drop(code.length).all(Char::isDigit)
        }
        .maxByOrNull { (_, code) -> code.length }
        ?.let { (action, code) -> action to number.drop(code.length) }

    /** What a number that is dialled as it is stands for, null when it is none of those codes. */
    @StringRes
    fun plainLabel(number: String, codes: FeatureCodes): Int? = when (number) {
        "" -> null
        codes.voicemail.trim() -> R.string.feature_voicemail
        codes.groupPickup.trim() -> R.string.feature_group_pickup
        codes.park.trim() -> R.string.feature_park
        codes.dndOn.trim() -> R.string.feature_dnd_on
        codes.dndOff.trim() -> R.string.feature_dnd_off
        else -> null
    }

    /** A feature works through its code: while the code is left empty the feature is not in use. */
    fun isConfigured(action: Action, codes: FeatureCodes): Boolean = codeOf(action, codes).isNotEmpty()

    /** Do not disturb can only be left to the PBX while both of its codes are set. */
    fun serverDndConfigured(codes: FeatureCodes): Boolean = codes.dndOn.isNotBlank() && codes.dndOff.isNotBlank()

    /** What a tap on a feature without a code is answered with; [label] is the name of the feature. */
    fun missingCode(label: Phrase): Phrase = Phrase.of(R.string.feature_code_missing, label)

    /** Supervision mode a call starts in, null for actions that are not supervision calls. */
    fun supervisionOf(action: Action): SupervisionMode? = when (action) {
        Action.LISTEN -> SupervisionMode.LISTEN
        Action.WHISPER -> SupervisionMode.WHISPER
        Action.BARGE -> SupervisionMode.BARGE
        else -> null
    }

    /** A feature code may only contain what a dial pad can produce. */
    fun isValidCode(code: String): Boolean = code.all { it.isDigit() || it == '*' || it == '#' }
}
