package com.sky.siplink.core.logic

import com.sky.siplink.R
import com.sky.siplink.core.model.Phrase

/** One line of comma separated text, with double quotes around fields that need them. */
object Csv {
    data class LineError(val line: Int, val reason: Phrase)

    /** What is wrong with a line whose fields cannot be read: a quote left open ([fields] null), or too few fields for [format]. */
    fun shapeError(fields: List<String>?, format: Phrase): Phrase =
        if (fields == null) Phrase.of(R.string.csv_unclosed_quote) else Phrase.of(R.string.csv_too_few_fields, format)

    /**
     * The rows of a text: line number and fields, null fields when a quote is left open. Blank
     * lines and lines starting with `#` are skipped, as is a byte order mark.
     */
    fun rows(text: String): Sequence<Pair<Int, List<String>?>> =
        text.lineSequence().mapIndexedNotNull { index, raw ->
            val line = raw.trim().removePrefix("\uFEFF").trim()
            if (line.isEmpty() || line.startsWith("#")) null else (index + 1) to split(line)
        }

    /** Splits one CSV line; returns null when a quoted field is not closed. */
    fun split(line: String): List<String>? {
        val fields = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        var wasQuoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                quoted && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> {
                    current.append('"')
                    i++
                }
                c == '"' -> {
                    quoted = !quoted
                    wasQuoted = true
                }
                c == ',' && !quoted -> {
                    fields += if (wasQuoted) current.toString() else current.toString().trim()
                    current.setLength(0)
                    wasQuoted = false
                }
                else -> current.append(c)
            }
            i++
        }
        if (quoted) return null
        fields += if (wasQuoted) current.toString() else current.toString().trim()
        return fields
    }

    /** The opposite of [split]: quotes a field when it holds a comma, a quote or outer spaces. */
    fun join(fields: List<String>): String = fields.joinToString(",") { field ->
        val quote = field.any { it == ',' || it == '"' } || field != field.trim()
        if (quote) "\"" + field.replace("\"", "\"\"") + "\"" else field
    }
}
