package com.sky.siplink.core.logic

import com.sky.siplink.core.model.Shortcut
import com.sky.siplink.core.model.SipAccount

/**
 * The fixed line of a shortcut key: the account it calls out on, referred to by the name of the
 * account or by its `user@domain`. A reference by name survives an export of the keys and an
 * import on another phone, but it has to follow when the account is renamed or removed.
 */
object ShortcutLines {
    /** What a key stores to call out on [account]. */
    fun of(account: SipAccount): String = account.name.ifBlank { account.identity }

    fun matches(account: SipAccount, line: String): Boolean =
        line.isNotEmpty() && (account.name == line || account.identity.equals(line, ignoreCase = true))

    /**
     * The account [line] stands for, or null when there is none (any more). Of several accounts
     * with that name, one that is switched on.
     */
    fun resolve(line: String, accounts: List<SipAccount>): SipAccount? {
        val hits = accounts.filter { matches(it, line) }
        return hits.firstOrNull { it.enabled } ?: hits.firstOrNull()
    }

    /** How many keys are fixed to [account]. */
    fun fixedTo(account: SipAccount, shortcuts: List<Shortcut>): Int = shortcuts.count { matches(account, it.line) }

    /**
     * The keys after the accounts changed from [before] to [after]. A key whose line was one of
     * the accounts and no longer is follows that account if it still exists under another name
     * or address, and otherwise asks for its line on every press again.
     *
     * A line that was no account before either is left alone: it came with an import, and its
     * account may still be added.
     *
     * @return [shortcuts] itself when no key had to change
     */
    fun follow(shortcuts: List<Shortcut>, before: List<SipAccount>, after: List<SipAccount>): List<Shortcut> {
        var changed = false
        val followed = shortcuts.map { shortcut ->
            val line = shortcut.line
            if (line.isEmpty() || after.any { matches(it, line) }) return@map shortcut
            val old = resolve(line, before) ?: return@map shortcut
            changed = true
            shortcut.copy(line = after.firstOrNull { it.id == old.id }?.let(::of).orEmpty())
        }
        return if (changed) followed else shortcuts
    }
}
