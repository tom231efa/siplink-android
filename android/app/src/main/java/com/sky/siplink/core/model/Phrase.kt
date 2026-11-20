package com.sky.siplink.core.model

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes

/**
 * A text of the interface held as data, rendered in the language of the moment: a string
 * resource with its arguments ([Res]), a plural resource with the count that picks its form
 * ([Count]), or text that is data itself and shown as it is ([Plain]: a name, a number, the reason
 * phrase a server sent).
 *
 * Arguments are Phrases themselves or plain values: a String is put in as it is, a number with
 * Western digits. The patterns of the resources use `%1$s`, `%2$s`, … for every argument.
 * Rendering: [com.sky.siplink.core.logic.PhraseFormat]; in the app [com.sky.siplink.core.AppTexts].
 */
sealed interface Phrase {
    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : Phrase

    /** [args] fill the pattern of the form [count] picks; the count itself is the first of them when the text shows it. */
    data class Count(@PluralsRes val id: Int, val count: Int, val args: List<Any>) : Phrase

    data class Plain(val text: String) : Phrase

    companion object {
        fun of(@StringRes id: Int, vararg args: Any): Phrase = Res(id, args.toList())

        /** A plural text that shows its count as `%1$s`, then [args] as `%2$s`, … */
        fun count(@PluralsRes id: Int, count: Int, vararg args: Any): Phrase = Count(id, count, listOf(count) + args)

        fun plain(text: String): Phrase = Plain(text)
    }
}
