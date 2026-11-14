package com.sky.siplink.core.logic

import com.sky.siplink.core.model.FeatureCodes
import com.sky.siplink.core.model.SupervisionMode

/** Builds the dial strings of the PBX features that act on an extension: a feature code followed by it. */
object FeatureDial {
    enum class Action(val label: String) {
        INTERCOM("对讲"),
        LISTEN("监听"),
        WHISPER("耳语"),
        BARGE("强插"),
        FORCE_HANG_UP("强拆"),
        PICKUP("代接"),
    }

    /** Extension part of whatever the user picked: `sip:1002@pbx;x=y` -> `1002`. */
    fun extensionOf(target: String): String = Formatters.userPart(target.trim()).filterNot { it.isWhitespace() }

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
    fun plainLabel(number: String, codes: FeatureCodes): String? = when (number) {
        "" -> null
        codes.voicemail.trim() -> "语音信箱"
        codes.groupPickup.trim() -> "同组代接"
        codes.park.trim() -> "驻留"
        codes.dndOn.trim() -> "开启免打扰"
        codes.dndOff.trim() -> "关闭免打扰"
        else -> null
    }

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
