package com.sky.siplink.core.logic

import com.sky.siplink.R
import com.sky.siplink.core.model.Phrase

/**
 * The name the app gives itself in the User-Agent header of its SIP requests: by default the SIP
 * stack's and then its own, or one the user (or a remote configuration) sets.
 */
object UserAgent {
    /** The management platform checks the same limit. */
    const val MAX_LENGTH = 120

    /**
     * Named first, before the app. Some services answer only clients whose name begins with one
     * they know: a relay in front of hosted PBXs was seen to refuse "SipLink/…" with 403 before
     * asking for any password, and to let "Linphone…" in.
     */
    private const val SDK_NAME = "LinphoneSDK"
    private const val APP_NAME = "SipLink"

    /**
     * @param message what is wrong, as shown right under the field
     * @param full the error where no field stands next to it (a remote configuration's errors): it names the value
     */
    enum class Error(val message: Phrase, val full: Phrase) {
        TOO_LONG(FormRules.maxChars(MAX_LENGTH), Phrase.count(R.plurals.user_agent_error_too_long, MAX_LENGTH)),
        NOT_PRINTABLE_ASCII(Phrase.of(R.string.user_agent_error_ascii), Phrase.of(R.string.user_agent_error_ascii_full)),
        SPACE_AT_ENDS(Phrase.of(R.string.user_agent_error_spaces), Phrase.of(R.string.user_agent_error_spaces_full)),
    }

    /** What is wrong with [value] as a name of the user's own; null when nothing. Empty stands for the default name. */
    fun error(value: String): Error? = when {
        value.length > MAX_LENGTH -> Error.TOO_LONG
        value.any { it !in ' '..'~' } -> Error.NOT_PRINTABLE_ASCII
        value.trim() != value -> Error.SPACE_AT_ENDS
        else -> null
    }

    /** The name the app sends when none is set, as it stands in the header. */
    fun default(sdkVersion: String, appVersion: String): String = "$SDK_NAME/$sdkVersion $APP_NAME/$appVersion"

    /**
     * The name and the version to hand to the SIP core (`Core.setUserAgent`), which joins them as
     * `name/version`, or sends the name alone when there is no version. A name of the user's own
     * goes as the name, without a version: on the wire exactly as typed.
     *
     * @param custom the name of the user's own, which [error] accepted; empty for the default
     */
    fun parts(custom: String, sdkVersion: String, appVersion: String): Pair<String, String?> =
        if (custom.isEmpty()) SDK_NAME to "$sdkVersion $APP_NAME/$appVersion" else custom to null
}
