package com.sky.siplink.core.logic

import com.sky.siplink.core.model.Phrase
import java.util.Locale

/**
 * Renders a [Phrase] from the patterns of one language, the way Android's own getString(id,
 * args) does: a pattern without arguments is taken as it is, one with arguments is filled by
 * String.format. Every argument goes in as text: numbers with Western digits (Locale.ROOT), and,
 * in a right-to-left language, each value that is not a text of the app ([Phrase.Plain], a
 * String, a number) wrapped in a first-strong isolate (U+2068 … U+2069), so that a number or
 * an address keeps its own direction.
 *
 * @param string the pattern of a string resource
 * @param plural the pattern of a plural resource for a count
 * @param rtl the language is written right to left
 */
class PhraseFormat(
    private val string: (Int) -> String,
    private val plural: (Int, Int) -> String,
    private val rtl: Boolean,
) {
    fun render(phrase: Phrase): String = when (phrase) {
        is Phrase.Plain -> phrase.text
        is Phrase.Res -> fill(string(phrase.id), phrase.args)
        is Phrase.Count -> fill(plural(phrase.id, phrase.count), phrase.args)
    }

    private fun fill(pattern: String, args: List<Any>): String {
        if (args.isEmpty()) return pattern
        return String.format(Locale.ROOT, pattern, *args.map(::argument).toTypedArray())
    }

    private fun argument(value: Any): String = when (value) {
        is Phrase.Plain -> isolated(value.text)
        is Phrase -> render(value)
        else -> isolated(value.toString())
    }

    private fun isolated(text: String): String = if (rtl) "$FSI$text$PDI" else text

    private companion object {
        const val FSI = '\u2068'
        const val PDI = '\u2069'
    }
}
