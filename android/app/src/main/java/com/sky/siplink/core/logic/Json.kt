package com.sky.siplink.core.logic

/**
 * A small JSON reader and writer for the remote configuration, pure Kotlin so that the unit tests
 * run it as the app does (Android's org.json is only a stub there).
 *
 * Read, an object is a [Map] (in its order), an array a [List], a number a [Long] when it has
 * no fraction or exponent and a [Double] otherwise; true, false and null are themselves.
 */
object Json {
    /** How deep objects and arrays may nest: far more than any document of the platform has. */
    private const val MAX_DEPTH = 32

    class SyntaxException(message: String) : IllegalArgumentException(message)

    /** @throws SyntaxException when [text] is not exactly one JSON value */
    fun parse(text: String): Any? {
        val reader = Reader(text)
        val value = reader.value(0)
        reader.skipBlanks()
        if (!reader.atEnd) throw SyntaxException("Unexpected text at ${reader.position}")
        return value
    }

    /** [value] as JSON text: maps (with string keys), lists, strings, Int / Long / finite Double, booleans and null. */
    fun write(value: Any?): String = StringBuilder().also { write(value, it) }.toString()

    private fun write(value: Any?, out: StringBuilder) {
        when (value) {
            null -> out.append("null")
            is String -> quote(value, out)
            is Boolean, is Int, is Long -> out.append(value.toString())
            is Double -> {
                require(value.isFinite()) { "No JSON for $value" }
                out.append(value.toString())
            }
            is Map<*, *> -> {
                out.append('{')
                value.entries.forEachIndexed { index, (key, item) ->
                    if (index > 0) out.append(',')
                    quote(key as String, out)
                    out.append(':')
                    write(item, out)
                }
                out.append('}')
            }
            is List<*> -> {
                out.append('[')
                value.forEachIndexed { index, item ->
                    if (index > 0) out.append(',')
                    write(item, out)
                }
                out.append(']')
            }
            else -> throw IllegalArgumentException("No JSON for ${value::class.java.simpleName}")
        }
    }

    private fun quote(text: String, out: StringBuilder) {
        out.append('"')
        for (char in text) {
            when {
                char == '"' -> out.append("\\\"")
                char == '\\' -> out.append("\\\\")
                char == '\n' -> out.append("\\n")
                char == '\r' -> out.append("\\r")
                char == '\t' -> out.append("\\t")
                char < ' ' -> out.append("\\u%04x".format(char.code))
                else -> out.append(char)
            }
        }
        out.append('"')
    }

    private class Reader(private val text: String) {
        var position = 0
            private set

        val atEnd: Boolean get() = position >= text.length

        fun skipBlanks() {
            while (!atEnd && text[position] in " \t\r\n") position++
        }

        fun value(depth: Int): Any? {
            if (depth > MAX_DEPTH) fail("Nested too deeply")
            skipBlanks()
            if (atEnd) fail("Unexpected end")
            return when (text[position]) {
                '{' -> objectValue(depth)
                '[' -> arrayValue(depth)
                '"' -> stringValue()
                't' -> word("true", true)
                'f' -> word("false", false)
                'n' -> word("null", null)
                else -> numberValue()
            }
        }

        private fun objectValue(depth: Int): Map<String, Any?> {
            position++
            val map = LinkedHashMap<String, Any?>()
            skipBlanks()
            if (take('}')) return map
            while (true) {
                skipBlanks()
                if (atEnd || text[position] != '"') fail("Expected a key")
                val key = stringValue()
                skipBlanks()
                if (!take(':')) fail("Expected ':'")
                map[key] = value(depth + 1)
                skipBlanks()
                if (take('}')) return map
                if (!take(',')) fail("Expected ',' or '}'")
            }
        }

        private fun arrayValue(depth: Int): List<Any?> {
            position++
            val list = ArrayList<Any?>()
            skipBlanks()
            if (take(']')) return list
            while (true) {
                list += value(depth + 1)
                skipBlanks()
                if (take(']')) return list
                if (!take(',')) fail("Expected ',' or ']'")
            }
        }

        private fun stringValue(): String {
            position++
            val out = StringBuilder()
            while (true) {
                if (atEnd) fail("Unterminated string")
                val char = text[position++]
                when {
                    char == '"' -> return out.toString()
                    char == '\\' -> out.append(escape())
                    char < ' ' -> fail("Control character in a string")
                    else -> out.append(char)
                }
            }
        }

        private fun escape(): Char {
            if (atEnd) fail("Unterminated escape")
            return when (val char = text[position++]) {
                '"', '\\', '/' -> char
                'b' -> '\b'
                'f' -> '\u000C'
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                'u' -> {
                    if (position + 4 > text.length) fail("Short \\u escape")
                    val code = text.substring(position, position + 4).toIntOrNull(16) ?: fail("Bad \\u escape")
                    position += 4
                    code.toChar()
                }
                else -> fail("Unknown escape \\$char")
            }
        }

        private fun numberValue(): Number {
            val start = position
            if (take('-')) Unit
            val digits = position
            while (!atEnd && text[position].isAsciiDigit()) position++
            if (position == digits) fail("Unexpected character")
            if (text[digits] == '0' && position - digits > 1) fail("Leading zero")
            var integral = true
            if (take('.')) {
                integral = false
                if (!digitsFollow()) fail("Expected digits")
            }
            if (!atEnd && (text[position] == 'e' || text[position] == 'E')) {
                integral = false
                position++
                if (!atEnd && (text[position] == '+' || text[position] == '-')) position++
                if (!digitsFollow()) fail("Expected digits")
            }
            val number = text.substring(start, position)
            return if (integral) number.toLongOrNull() ?: number.toDouble() else number.toDouble()
        }

        private fun digitsFollow(): Boolean {
            val from = position
            while (!atEnd && text[position].isAsciiDigit()) position++
            return position > from
        }

        private fun word(word: String, value: Any?): Any? {
            if (!text.startsWith(word, position)) fail("Unexpected character")
            position += word.length
            return value
        }

        private fun take(char: Char): Boolean {
            if (atEnd || text[position] != char) return false
            position++
            return true
        }

        private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'

        private fun fail(message: String): Nothing = throw SyntaxException("$message at $position")
    }
}
