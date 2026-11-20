package com.sky.siplink.core.logic

import com.sky.siplink.R
import com.sky.siplink.core.model.Phrase

/** The words the forms share under their fields: what the rules of several kinds of entry answer alike. */
object FormRules {
    val REQUIRED: Phrase = Phrase.of(R.string.form_required)
    val TAKEN: Phrase = Phrase.of(R.string.form_taken)
    val HOST_PORT: Phrase = Phrase.of(R.string.form_host_port)
    val PORT_RANGE: Phrase = Phrase.of(R.string.form_port_range)

    fun maxChars(max: Int): Phrase = Phrase.count(R.plurals.form_max_chars, max)
}
