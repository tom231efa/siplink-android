package com.sky.siplink.core.logic

import com.sky.siplink.R
import com.sky.siplink.core.model.Phrase

/**
 * The checks a remote configuration has to pass before any of it is applied: the app's own rules
 * for accounts, directories and shortcut keys, and the ranges of its settings. The platform
 * checks with the same rules; this is the phone's own look.
 */
object RemoteChecks {
    /** What a document of a later major version than [RemoteDocument.SCHEMA] is answered with. */
    val NEEDS_UPDATE: Phrase = Phrase.of(R.string.remote_needs_update)

    private val hexHash = Regex("^[0-9A-Fa-f]{64}$")

    /**
     * [document] as the app keeps it (its accounts, directories and keys normalized), or what is
     * wrong with it. The names its categories may not have are the platform's
     * ([ShortcutRules.PLATFORM_RESERVED_CATEGORIES]), not those of the phone's language.
     */
    fun check(document: RemoteDocument): RemoteDocument.Parsed {
        when {
            document.schema > RemoteDocument.SCHEMA -> return RemoteDocument.Parsed.Invalid(listOf(NEEDS_UPDATE))
            document.schema < RemoteDocument.SCHEMA -> return RemoteDocument.Parsed.Invalid(listOf(Phrase.of(R.string.remote_error_schema)))
        }
        val errors = ArrayList<Phrase>()
        if (document.version.isEmpty()) errors += Phrase.of(R.string.remote_error_no_version)
        val hash = document.configPasswordHash
        if (hash.isNotEmpty() && !hexHash.matches(hash)) errors += Phrase.of(R.string.remote_error_password_hash)
        val settings = document.settings
        if (settings.forwardNoAnswerSeconds !in IncomingPolicy.MIN_NO_ANSWER_SECONDS..IncomingPolicy.MAX_NO_ANSWER_SECONDS) {
            errors += Phrase.of(R.string.remote_error_no_answer_range, IncomingPolicy.MIN_NO_ANSWER_SECONDS, IncomingPolicy.MAX_NO_ANSWER_SECONDS)
        }
        if (settings.videoFrameRate !in VideoRules.FRAME_RATES) {
            errors += Phrase.of(R.string.remote_error_frame_rate, VideoRules.FRAME_RATES.joinToString(" / "))
        }
        UserAgent.error(settings.userAgent)?.let { errors += it.full }

        val accounts = accounts(document.accounts, errors)
        val directories = directories(document.directories, errors)
        val shortcuts = shortcuts(document, errors)
        val codecs = document.codecs
        if (codecs != null && (codecs.audio + codecs.video).any { it.key.isBlank() }) errors += Phrase.of(R.string.remote_error_codec_name)

        if (errors.isNotEmpty()) return RemoteDocument.Parsed.Invalid(errors)
        return RemoteDocument.Parsed.Ok(
            document.copy(configPasswordHash = hash.lowercase(), accounts = accounts, directories = directories, shortcuts = shortcuts),
        )
    }

    private fun accounts(accounts: List<RemoteAccount>, errors: MutableList<Phrase>): List<RemoteAccount> {
        val keys = HashSet<String>()
        val identities = ArrayList<String>()
        val names = ArrayList<String>()
        return accounts.map { account ->
            val draft = AccountRules.normalize(account.draft)
            val name = draft.name.ifEmpty { draft.username.ifEmpty { account.key } }
            keyErrors(account.key, keys)?.let { errors += Phrase.of(R.string.remote_error_account, name, it) }
            AccountRules.validate(draft, identities, takenNames = names).forEach { errors += Phrase.of(R.string.remote_error_account, name, it.full) }
            identities += AccountRules.identityKey(draft.username, draft.address)
            names += draft.name
            account.copy(draft = draft)
        }
    }

    private fun directories(directories: List<RemoteDirectory>, errors: MutableList<Phrase>): List<RemoteDirectory> {
        val keys = HashSet<String>()
        val names = ArrayList<String>()
        return directories.map { remote ->
            val directory = LdapRules.normalize(remote.directory)
            val name = directory.title.ifEmpty { remote.key }
            keyErrors(remote.key, keys)?.let { errors += Phrase.of(R.string.remote_error_directory, name, it) }
            LdapRules.validate(directory, names, isNew = true).forEach { errors += Phrase.of(R.string.remote_error_directory, name, it.full) }
            names += directory.name
            remote.copy(directory = directory)
        }
    }

    private fun shortcuts(document: RemoteDocument, errors: MutableList<Phrase>) = document.shortcuts.let { keys ->
        if (keys.size > ShortcutRules.MAX_SHORTCUTS) errors += ShortcutRules.Error.LIMIT_REACHED.full
        keys.map { key ->
            val shortcut = ShortcutRules.normalize(key)
            ShortcutRules.validate(shortcut, ShortcutRules.PLATFORM_RESERVED_CATEGORIES).forEach {
                errors += Phrase.of(R.string.remote_error_key, ShortcutRules.title(shortcut), it.full)
            }
            shortcut
        }
    }

    /** What is wrong with the key that names an element of the document, after the keys of [seen]; null when nothing. */
    private fun keyErrors(key: String, seen: MutableSet<String>): Phrase? = when {
        key.isEmpty() -> Phrase.of(R.string.remote_error_key_missing)
        !seen.add(key) -> Phrase.of(R.string.remote_error_key_twice)
        else -> null
    }
}
