package com.sky.siplink.core.logic

/**
 * The checks a remote configuration has to pass before any of it is applied: the app's own rules
 * for accounts, directories and shortcut keys, and the ranges of its settings. The platform
 * checks with the same rules; this is the phone's own look.
 */
object RemoteChecks {
    /** What a document of a later major version than [RemoteDocument.SCHEMA] is answered with. */
    const val NEEDS_UPDATE = "请升级 SipLink"

    private val hexHash = Regex("^[0-9A-Fa-f]{64}$")

    /** [document] as the app keeps it (its accounts, directories and keys normalized), or what is wrong with it. */
    fun check(document: RemoteDocument): RemoteDocument.Parsed {
        when {
            document.schema > RemoteDocument.SCHEMA -> return RemoteDocument.Parsed.Invalid(listOf(NEEDS_UPDATE))
            document.schema < RemoteDocument.SCHEMA -> return RemoteDocument.Parsed.Invalid(listOf("不支持的配置格式"))
        }
        val errors = ArrayList<String>()
        if (document.version.isEmpty()) errors += "配置缺少版本"
        val hash = document.configPasswordHash
        if (hash.isNotEmpty() && !hexHash.matches(hash)) errors += "配置密码格式不正确"
        val settings = document.settings
        if (settings.forwardNoAnswerSeconds !in IncomingPolicy.MIN_NO_ANSWER_SECONDS..IncomingPolicy.MAX_NO_ANSWER_SECONDS) {
            errors += "无应答等待时间应为 ${IncomingPolicy.MIN_NO_ANSWER_SECONDS}–${IncomingPolicy.MAX_NO_ANSWER_SECONDS} 秒"
        }
        if (settings.videoFrameRate !in VideoRules.FRAME_RATES) errors += "视频帧率应为 ${VideoRules.FRAME_RATES.joinToString(" / ")}"
        UserAgent.error(settings.userAgent)?.let { errors += it.full }

        val accounts = accounts(document.accounts, errors)
        val directories = directories(document.directories, errors)
        val shortcuts = shortcuts(document, errors)
        val codecs = document.codecs
        if (codecs != null && (codecs.audio + codecs.video).any { it.key.isBlank() }) errors += "编码缺少名称"

        if (errors.isNotEmpty()) return RemoteDocument.Parsed.Invalid(errors)
        return RemoteDocument.Parsed.Ok(
            document.copy(configPasswordHash = hash.lowercase(), accounts = accounts, directories = directories, shortcuts = shortcuts),
        )
    }

    private fun accounts(accounts: List<RemoteAccount>, errors: MutableList<String>): List<RemoteAccount> {
        val keys = HashSet<String>()
        val identities = ArrayList<String>()
        val names = ArrayList<String>()
        return accounts.map { account ->
            val draft = AccountRules.normalize(account.draft)
            val label = "账号“${draft.name.ifEmpty { draft.username.ifEmpty { account.key } }}”："
            keyErrors(account.key, keys)?.let { errors += label + it }
            AccountRules.validate(draft, identities, takenNames = names).forEach { errors += label + it.full }
            identities += AccountRules.identityKey(draft.username, draft.address)
            names += draft.name
            account.copy(draft = draft)
        }
    }

    private fun directories(directories: List<RemoteDirectory>, errors: MutableList<String>): List<RemoteDirectory> {
        val keys = HashSet<String>()
        val names = ArrayList<String>()
        return directories.map { remote ->
            val directory = LdapRules.normalize(remote.directory)
            val label = "通讯录“${directory.title.ifEmpty { remote.key }}”："
            keyErrors(remote.key, keys)?.let { errors += label + it }
            LdapRules.validate(directory, names, isNew = true).forEach { errors += label + it.full }
            names += directory.name
            remote.copy(directory = directory)
        }
    }

    private fun shortcuts(document: RemoteDocument, errors: MutableList<String>) = document.shortcuts.let { keys ->
        if (keys.size > ShortcutRules.MAX_SHORTCUTS) errors += ShortcutRules.Error.LIMIT_REACHED.full
        keys.map { key ->
            val shortcut = ShortcutRules.normalize(key)
            ShortcutRules.validate(shortcut).forEach { errors += "快捷键“${shortcut.label}”：${it.full}" }
            shortcut
        }
    }

    /** What is wrong with the key that names an element of the document, after the keys of [seen]; null when nothing. */
    private fun keyErrors(key: String, seen: MutableSet<String>): String? = when {
        key.isEmpty() -> "缺少 key"
        !seen.add(key) -> "key 重复"
        else -> null
    }
}
