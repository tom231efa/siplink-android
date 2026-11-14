package com.sky.siplink.core.logic

import com.sky.siplink.core.model.FeatureCodes
import com.sky.siplink.core.model.SupervisionMode

/** Builds the dial strings of PBX features (supervisor codes, pickup, parking, voicemail). */
object FeatureDial {
    enum class Action(val label: String, val description: String) {
        LISTEN("监听", "只听对方通话，双方都听不到你"),
        WHISPER("耳语", "只有该分机能听到你，对端听不到"),
        BARGE("强插", "加入通话，双方都能听到你"),
        FORCE_HANG_UP("强拆", "强制挂断该分机正在进行的通话"),
        PICKUP("代接", "接起正在该分机振铃的来电"),
    }

    /** Extension part of whatever the user picked: `sip:1002@pbx;x=y` -> `1002`. */
    fun extensionOf(target: String): String = Formatters.userPart(target.trim()).filterNot { it.isWhitespace() }

    /** @return the string to dial, or null when the code is not configured or the target is empty */
    fun compose(action: Action, target: String, codes: FeatureCodes): String? {
        val extension = extensionOf(target)
        val prefix = when (action) {
            Action.LISTEN -> codes.listen
            Action.WHISPER -> codes.whisper
            Action.BARGE -> codes.barge
            Action.FORCE_HANG_UP -> codes.forceHangUp
            Action.PICKUP -> codes.pickup
        }.trim()
        if (prefix.isEmpty() || extension.isEmpty()) return null
        return prefix + extension
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
