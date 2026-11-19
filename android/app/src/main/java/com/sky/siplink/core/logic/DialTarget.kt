package com.sky.siplink.core.logic

/** What the user dials, in the form the SIP stack takes it. */
object DialTarget {
    /** Characters a SIP URI allows in its user part as they are (RFC 3261, "user"). */
    private const val USER_PLAIN = "-_.!~*'()&=+$,;?/"

    /**
     * [target] without blanks. A bare number stays as it is: the stack completes it with the
     * domain of the account and escapes it. An address written as `user@host`, the way the call
     * log shows it, gets its scheme and the escapes its user part needs: `#9@pbx` would otherwise
     * be read as a URI with a fragment.
     */
    fun of(target: String): String {
        val cleaned = target.filterNot { it.isWhitespace() }
        if (cleaned.startsWith("sip:", ignoreCase = true) || cleaned.startsWith("sips:", ignoreCase = true)) return cleaned
        val at = cleaned.lastIndexOf('@')
        if (at <= 0) return cleaned
        return "sip:" + escapeUser(cleaned.substring(0, at)) + cleaned.substring(at)
    }

    private fun escapeUser(user: String): String = buildString {
        for (byte in user.toByteArray(Charsets.UTF_8)) {
            val char = (byte.toInt() and 0xFF).toChar()
            if (char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' || char in USER_PLAIN) {
                append(char)
            } else {
                append('%').append("%02X".format(byte.toInt() and 0xFF))
            }
        }
    }
}
