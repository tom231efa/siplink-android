package com.sky.siplink.core

import android.os.SystemClock
import com.sky.siplink.core.logic.NameLookup

/**
 * Names for the numbers of calls, taken from the directories: who is calling, and who is being
 * called. A name of a directory goes before the one the other side sends along.
 *
 * Which directories name a call depends on its direction and on the account it is on, so a
 * name is known for a number among certain directories (their ids, joined).
 *
 * The answer is wanted while the call is being drawn, so [nameOf] never waits: it says what is
 * known right now and, for a number it has not seen, starts a lookup (local copy of the
 * directories first, then the LDAP servers) whose result is announced to the listeners. A
 * number that is in no directory is not asked for again for a while, so that calls from
 * outside do not cost a server request each.
 *
 * Use on the thread of the core.
 *
 * @param directory given as a function: the directories are only set up when they are first needed
 */
class NameResolver(private val directory: () -> DirectoryManager) {
    /** By directories and number. Most recently used last; the eldest goes when there are more than [MAX_NAMES]. */
    private val names = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean = size > MAX_NAMES
    }

    /** By number alone: the name it was last found to have, for where no call tells the directories. */
    private val latest = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean = size > MAX_NAMES
    }

    /** Numbers that certain directories do not have, with the time that was found out. */
    private val unknown = HashMap<String, Long>()
    private val asking = HashSet<String>()
    private val listeners = ArrayList<() -> Unit>()

    /** [listener] is called whenever names were found that [nameOf] did not have before. */
    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    /**
     * The directory name of the number or SIP address of a call if it is known by now; looks it
     * up otherwise.
     *
     * @param incoming the call came in, rather than being placed
     * @param line id of the account the call is on, null when that is not known
     */
    fun nameOf(number: String, incoming: Boolean, line: String?): String? {
        val key = NameLookup.key(number) ?: return null
        val scope = directory().namingScope(incoming, line)
        if (scope.isEmpty()) return null
        val entry = scope.joinToString(",", postfix = "|$key")
        names[entry]?.let { return it }
        val missedAt = unknown[entry]
        if (entry in asking || (missedAt != null && SystemClock.elapsedRealtime() - missedAt < RETRY_AFTER_MS)) return null
        asking += entry
        directory().lookup(number, incoming, line) { contact ->
            asking -= entry
            if (contact == null) {
                unknown[entry] = SystemClock.elapsedRealtime()
            } else {
                unknown -= entry
                names[entry] = contact.name
                latest[key] = contact.name
                listeners.forEach { it() }
            }
        }
        return null
    }

    /** Names for many numbers at once (the call history), from the local copy only. */
    fun preload(numbers: Collection<String>) {
        val wanted = numbers.filter { number -> NameLookup.key(number).let { it != null && it !in latest } }
        if (wanted.isEmpty()) return
        directory().cachedNames(wanted) { found ->
            latest.putAll(found)
            listeners.forEach { it() }
        }
    }

    /** The name a number was last found to have, without ever starting a lookup. */
    fun known(number: String): String? = NameLookup.key(number)?.let(latest::get)

    /** Forgets everything: a directory was changed or removed. */
    fun clear() {
        names.clear()
        latest.clear()
        unknown.clear()
    }

    private companion object {
        const val MAX_NAMES = 1000

        /** How long a number that was not found is left alone: ten minutes. */
        const val RETRY_AFTER_MS = 10 * 60 * 1000L
    }
}
