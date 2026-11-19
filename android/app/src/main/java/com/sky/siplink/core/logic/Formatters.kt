package com.sky.siplink.core.logic

/** Small presentation helpers kept free of Android dependencies so they can be unit tested. */
object Formatters {
    /** 0:07, 12:34, 1:02:03 */
    fun duration(totalSeconds: Long): String {
        val seconds = totalSeconds.coerceAtLeast(0)
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    /** One or two characters shown inside an avatar. */
    fun initials(name: String): String {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return "#"
        val first = trimmed.first()
        // CJK names read best with their last character(s); latin names with word initials.
        if (first.code > 0x2E7F) return trimmed.takeLast(minOf(2, trimmed.length))
        if (first.isDigit()) return trimmed.takeLast(minOf(2, trimmed.length))
        val words = trimmed.split(' ', '.', '_', '-').filter { it.isNotEmpty() }
        return words.take(2).joinToString("") { it.first().uppercase() }
    }

    private val schemes = listOf("sip:", "sips:", "tel:")

    /** Strips the scheme and URI parameters: `sip:1001@pbx.local;transport=tls` -> `1001@pbx.local`. */
    fun shortAddress(uri: String): String {
        val scheme = schemes.firstOrNull { uri.startsWith(it, ignoreCase = true) }
        return uri.drop(scheme?.length ?: 0).substringBefore(';').substringBefore('?')
    }

    /** User part of a SIP URI, or the whole value when it is not a URI. */
    fun userPart(uri: String): String = shortAddress(uri).substringBefore('@')

    /**
     * [text] with its escapes (`%` and two hex digits, of UTF-8 bytes, as in the user part of a
     * SIP URI) read: `%2A91` -> `*91`. A `%` that starts no escape stays as it is.
     */
    fun unescaped(text: String): String {
        if ('%' !in text) return text
        val result = StringBuilder()
        val bytes = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < text.length) {
            val high = if (text[i] == '%') text.getOrNull(i + 1)?.digitToIntOrNull(16) else null
            val low = if (high != null) text.getOrNull(i + 2)?.digitToIntOrNull(16) else null
            if (high != null && low != null) {
                bytes.write(high * 16 + low)
                i += 3
            } else {
                result.append(String(bytes.toByteArray(), Charsets.UTF_8))
                bytes.reset()
                result.append(text[i])
                i++
            }
        }
        return result.append(String(bytes.toByteArray(), Charsets.UTF_8)).toString()
    }

    /** Characters that can be sent as DTMF. */
    fun isDtmf(char: Char): Boolean = char.isDigit() || char == '*' || char == '#'
}
