package com.sky.siplink.core.logic

import com.sky.siplink.R
import com.sky.siplink.core.model.FeatureCodes
import com.sky.siplink.core.model.Phrase

/** What the other side of a call is called on the screen and in the call log. */
object PartyName {
    /**
     * In this order: what a feature code we dialled stands for, the name of the number in the
     * directory, what the far end calls itself, its number.
     *
     * @param shown the name the far end gave, or its number when it gave none
     * @param address `user@host` of the far end
     * @param outgoing we placed the call, so its number may be one of our feature codes
     * @param directory the name of a number or address in the directory, null when it has none
     */
    fun of(shown: String, address: String, outgoing: Boolean, codes: FeatureCodes, directory: (String) -> String?): Phrase {
        val number = Formatters.userPart(address)
        if (outgoing) {
            FeatureDial.plainLabel(number, codes)?.let { return Phrase.of(it) }
            // "*801005" is the intercom to 1005: named after whoever 1005 is.
            FeatureDial.parse(number, codes)?.let { (action, extension) ->
                return Phrase.of(R.string.feature_to, Phrase.of(action.label), directory(extension) ?: extension)
            }
        }
        directory(address)?.let { return Phrase.plain(it) }
        val named = shown.isNotBlank() && shown != number && !NameLookup.isWithheld(shown)
        return when {
            named -> Phrase.plain(shown)
            NameLookup.isWithheld(number) -> Phrase.of(R.string.call_withheld)
            else -> Phrase.plain(number.ifEmpty { address })
        }
    }
}
