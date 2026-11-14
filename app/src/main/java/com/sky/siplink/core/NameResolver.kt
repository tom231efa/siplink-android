package com.sky.siplink.core

import android.os.SystemClock
import com.sky.siplink.core.logic.NameLookup

/**
 * Names for the numbers of calls, taken from the directory: who is calling, and who is being
 * called. A name of the directory goes before the one the other side sends along.
 *
 * The answer is wanted while the call is being drawn, so [nameOf] never waits: it says what is
 * known right now and, for a number it has not seen, starts a lookup (local copy of the
 * directory first, then the LDAP server) whose result is announced to the listeners. A number
 * that is not in the directory is not asked for again for a while, so that calls from outside
 * do not cost a server request each.
 *
 * Use from the main thread.
 *
 * @param directory given as a function: the directory is only set up when it is first needed
 */
class NameResolver(private val directory: () -> DirectoryManager) {
    /** Most recently used last; the eldest goes when there are more than [MAX_NAMES]. */
    private val names = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean = size > MAX_NAMES
    }

    /** Numbers that are not in the directory, with the time that was found out. */
    private val unknown = HashMap<String, Long>()
    private val asking = HashSet<String>()
    private val listeners = ArrayList<() -> Unit>()

    /** [listener] is called whenever names were found that [nameOf] did not have before. */
    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    /** The directory name of a number or SIP address if it is known by now; looks it up otherwise. */
    fun nameOf(number: String): String? {
        val key = NameLookup.key(number) ?: return null
        names[key]?.let { return it }
        val missedAt = unknown[key]
        if (key in asking || (missedAt != null && SystemClock.elapsedRealtime() - missedAt < RETRY_AFTER_MS)) return null
        asking += key
        directory().lookup(number) { contact ->
            asking -= key
            if (contact == null) {
                unknown[key] = SystemClock.elapsedRealtime()
            } else {
                unknown -= key
                names[key] = contact.name
                listeners.forEach { it() }
            }
        }
        return null
    }

    /** Like [nameOf] for many numbers at once (the call history), from the local copy only. */
    fun preload(numbers: Collection<String>) {
        val wanted = numbers.filter { number -> NameLookup.key(number).let { it != null && it !in names } }
        if (wanted.isEmpty()) return
        directory().cachedNames(wanted) { found ->
            names.putAll(found)
            listeners.forEach { it() }
        }
    }

    /** The name [nameOf] would return, without ever starting a lookup. */
    fun known(number: String): String? = NameLookup.key(number)?.let(names::get)

    /** Forgets everything: the directory was changed or removed. */
    fun clear() {
        names.clear()
        unknown.clear()
    }

    private companion object {
        const val MAX_NAMES = 1000

        /** How long a number that was not found is left alone: ten minutes. */
        const val RETRY_AFTER_MS = 10 * 60 * 1000L
    }
}
