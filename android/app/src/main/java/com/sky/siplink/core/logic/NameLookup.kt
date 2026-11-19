package com.sky.siplink.core.logic

import com.sky.siplink.core.model.DirectoryContact

/** Rules for finding the directory entry that belongs to the number of a call. */
object NameLookup {
    /** Shortest number for which a longer one that ends in it counts as the same line (trunk prefix, country code). */
    const val MIN_SUFFIX_DIGITS = 7

    /** How many of its last digits a long number is searched by when the full number finds nothing. */
    const val FALLBACK_DIGITS = 8

    /** What a caller without a number is called on the wire. */
    private val withheld = setOf("anonymous", "unknown", "restricted", "unavailable", "private")

    /**
     * What the different spellings of one number have in common: its digits. Addresses that are
     * not numbers are compared in lower case. Null when there is nothing worth looking up: no
     * number, a withheld one, or a feature code (anything with `*` or `#`).
     */
    fun key(value: String): String? {
        val user = Formatters.userPart(value.trim())
        val compact = user.filterNot { it.isWhitespace() || it in "()-." }
        if (compact.isEmpty() || compact.any { it == '*' || it == '#' }) return null
        val digits = compact.removePrefix("+")
        if (digits.isNotEmpty() && digits.all(Char::isDigit)) return digits
        return compact.lowercase().takeUnless { it in withheld }
    }

    /** Whether [value] is what a PBX puts where the number of a caller who withholds it would be. */
    fun isWithheld(value: String): Boolean = Formatters.userPart(value.trim()).lowercase() in withheld

    /** Equal, or two numbers of which the longer one is the shorter one with a prefix. */
    fun sameLine(a: String, b: String): Boolean {
        if (a == b) return true
        val (short, long) = if (a.length <= b.length) a to b else b to a
        return short.length >= MIN_SUFFIX_DIGITS && short.all(Char::isDigit) && long.all(Char::isDigit) && long.endsWith(short)
    }

    /** The entry for the number behind [key]: one with exactly that number, else one that is the same line. */
    fun pick(key: String, contacts: List<DirectoryContact>): DirectoryContact? {
        val keyed = contacts.mapNotNull { contact -> key(contact.number)?.let { it to contact } }
        return (keyed.firstOrNull { it.first == key } ?: keyed.firstOrNull { sameLine(key, it.first) })?.second
    }

    /** What to ask the server, in turn: the number, then the last digits of a long one that may carry a prefix. */
    fun queries(key: String): List<String> =
        if (key.length > FALLBACK_DIGITS && key.all(Char::isDigit)) listOf(key, key.takeLast(FALLBACK_DIGITS)) else listOf(key)
}
