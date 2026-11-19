package com.sky.siplink.core.logic

/**
 * Keys for the rows of a list whose items may come twice (two entries of a directory with the
 * same name and number, two speed dial keys alike): a list cannot have two rows with one key.
 */
object ListKeys {
    /** For each of [keys], the key and how often it came before: unique, and the same for a row as long as the rows before it stay. */
    fun distinct(keys: List<String>): List<Pair<String, Int>> {
        val seen = HashMap<String, Int>()
        return keys.map { key ->
            val before = seen[key] ?: 0
            seen[key] = before + 1
            key to before
        }
    }
}
