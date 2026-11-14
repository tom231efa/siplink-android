package com.sky.siplink.core.logic

import com.sky.siplink.core.model.AccountDraft
import com.sky.siplink.core.model.SipTransport

/** Pure validation / formatting rules for SIP accounts (no Android or SIP core dependency). */
object AccountRules {
    const val MAX_ACCOUNTS = 100
    const val MIN_EXPIRES = 60
    const val MAX_EXPIRES = 86_400
    const val MAX_NAME_LENGTH = 30

    /**
     * @param subject what the error is about
     * @param message what is wrong with it, as shown right under that field
     */
    enum class Error(val subject: String, val message: String) {
        LIMIT_REACHED("", "最多 $MAX_ACCOUNTS 个账号"),
        NAME_TOO_LONG("账号名称", "最多 $MAX_NAME_LENGTH 个字符"),
        NAME_TAKEN("账号名称", "已存在"),
        USERNAME_EMPTY("用户名", "必填"),
        USERNAME_INVALID("用户名", "含无效字符"),
        DOMAIN_EMPTY("域名", "必填"),
        DOMAIN_INVALID("域名", "格式不正确"),
        SERVER_INVALID("代理服务器", "应为 主机[:端口]"),
        EXPIRES_OUT_OF_RANGE("注册有效期", "应为 $MIN_EXPIRES–$MAX_EXPIRES 秒"),
        DUPLICATE("", "账号已存在"),

        /** A stored password belongs to the user name and domain it was entered for. */
        PASSWORD_REQUIRED("密码", "必填（已改用户名或域名）"),
        ADDRESS_REJECTED("", "地址无法解析"),
        NOT_FOUND("", "账号不存在"),
        ;

        /** The error where no field stands next to it: in a notice, or in a list of import errors. */
        val full: String get() = subject + message
    }

    private val userRegex = Regex("^[A-Za-z0-9\\-_.!~*'()&=+$,?/%]+$")
    private val hostLabel = "[A-Za-z0-9]([A-Za-z0-9\\-]*[A-Za-z0-9])?"
    private val hostRegex = Regex("^$hostLabel(\\.$hostLabel)*\\.?$")
    private val ipv6Regex = Regex("^\\[[0-9A-Fa-f:.]+]$")

    fun canAdd(currentCount: Int): Boolean = currentCount < MAX_ACCOUNTS

    /** Key used to detect duplicates: user part is case-sensitive, host part is not. */
    fun identityKey(username: String, domain: String): String = "$username@${domain.lowercase()}"

    /**
     * Trims every field, drops a leading `sip:` / `sips:` scheme and splits `user@domain`
     * typed into the username field when the domain was left empty.
     */
    fun normalize(draft: AccountDraft): AccountDraft {
        var username = draft.username.trim().removePrefix("sips:").removePrefix("sip:")
        var domain = draft.domain.trim().removePrefix("sips:").removePrefix("sip:")
        val at = username.lastIndexOf('@')
        if (at > 0) {
            if (domain.isEmpty()) domain = username.substring(at + 1)
            username = username.substring(0, at)
        }
        return draft.copy(
            name = draft.name.trim(),
            username = username,
            domain = domain,
            displayName = draft.displayName.trim(),
            authUser = draft.authUser.trim(),
            server = draft.server.trim().removePrefix("sips:").removePrefix("sip:"),
        )
    }

    /**
     * @param draft already normalized draft
     * @param existingKeys identity keys of the accounts already configured
     * @param editingKey identity key of the account being edited, null when adding a new one
     * @param takenNames names of the other accounts
     */
    fun validate(
        draft: AccountDraft,
        existingKeys: Collection<String>,
        editingKey: String? = null,
        takenNames: Collection<String> = emptyList(),
    ): List<Error> {
        val errors = mutableListOf<Error>()
        if (editingKey == null && !canAdd(existingKeys.size)) errors += Error.LIMIT_REACHED
        if (draft.name.length > MAX_NAME_LENGTH) errors += Error.NAME_TOO_LONG
        if (isNameTaken(draft.name, takenNames)) errors += Error.NAME_TAKEN

        when {
            draft.username.isEmpty() -> errors += Error.USERNAME_EMPTY
            !userRegex.matches(draft.username) -> errors += Error.USERNAME_INVALID
        }
        when {
            draft.domain.isEmpty() -> errors += Error.DOMAIN_EMPTY
            !isValidHostPort(draft.domain) -> errors += Error.DOMAIN_INVALID
        }
        if (draft.server.isNotEmpty() && !isValidHostPort(draft.server)) errors += Error.SERVER_INVALID
        if (draft.expires !in MIN_EXPIRES..MAX_EXPIRES) errors += Error.EXPIRES_OUT_OF_RANGE

        val key = identityKey(draft.username, draft.domain)
        if (key != editingKey && existingKeys.contains(key)) errors += Error.DUPLICATE
        return errors
    }

    /**
     * Whether another account is already called [name]. A name is how the user, and a shortcut
     * key fixed to a line, tells accounts apart; upper and lower case do not make two names.
     */
    fun isNameTaken(name: String, takenNames: Collection<String>): Boolean =
        name.isNotEmpty() && takenNames.any { it.equals(name, ignoreCase = true) }

    /** Accepts `host`, `host:port`, `1.2.3.4:5061`, `[::1]` and `[::1]:5061`. */
    fun isValidHostPort(value: String): Boolean {
        if (value.isEmpty() || value.any { it.isWhitespace() }) return false
        val host: String
        val port: String?
        if (value.startsWith("[")) {
            val end = value.indexOf(']')
            if (end < 0) return false
            host = value.substring(0, end + 1)
            val rest = value.substring(end + 1)
            port = when {
                rest.isEmpty() -> null
                rest.startsWith(":") -> rest.substring(1)
                else -> return false
            }
            if (!ipv6Regex.matches(host)) return false
        } else {
            val colon = value.lastIndexOf(':')
            host = if (colon >= 0) value.substring(0, colon) else value
            port = if (colon >= 0) value.substring(colon + 1) else null
            if (host.length > 253 || !hostRegex.matches(host)) return false
        }
        if (port != null) {
            val number = port.toIntOrNull() ?: return false
            if (port.length > 5 || number !in 1..65_535) return false
        }
        return true
    }

    fun identityUri(draft: AccountDraft): String = "sip:${draft.username}@${draft.domain}"

    /** Registrar / proxy URI; the transport is carried as a URI parameter. */
    fun serverUri(draft: AccountDraft): String {
        val host = draft.server.ifEmpty { draft.domain }
        return "sip:$host;transport=${transportParam(draft.transport)}"
    }

    fun transportParam(transport: SipTransport): String = when (transport) {
        SipTransport.UDP -> "udp"
        SipTransport.TCP -> "tcp"
        SipTransport.TLS -> "tls"
    }

    fun parseTransport(value: String): SipTransport? = when (value.trim().lowercase()) {
        "", "udp" -> SipTransport.UDP
        "tcp" -> SipTransport.TCP
        "tls" -> SipTransport.TLS
        else -> null
    }
}
