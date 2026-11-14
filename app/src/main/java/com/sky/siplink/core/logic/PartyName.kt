package com.sky.siplink.core.logic

import com.sky.siplink.core.model.FeatureCodes

/** What the other side of a call is called on the screen and in the call log. */
object PartyName {
    const val WITHHELD = "匿名来电"

    /**
     * In this order: what a feature code we dialled stands for, the name of the number in the
     * directory, what the far end calls itself, its number.
     *
     * @param shown the name the far end gave, or its number when it gave none
     * @param address `user@host` of the far end
     * @param outgoing we placed the call, so its number may be one of our feature codes
     * @param directory the name of a number or address in the directory, null when it has none
     */
    fun of(shown: String, address: String, outgoing: Boolean, codes: FeatureCodes, directory: (String) -> String?): String {
        val number = Formatters.userPart(address)
        if (outgoing) {
            FeatureDial.plainLabel(number, codes)?.let { return it }
            // "*801005" is the intercom to 1005: named after whoever 1005 is.
            FeatureDial.parse(number, codes)?.let { (action, extension) ->
                return "${action.label} ${directory(extension) ?: extension}"
            }
        }
        directory(address)?.let { return it }
        val named = shown.isNotBlank() && shown != number && !NameLookup.isWithheld(shown)
        return when {
            named -> shown
            NameLookup.isWithheld(number) -> WITHHELD
            else -> number.ifEmpty { address }
        }
    }
}
