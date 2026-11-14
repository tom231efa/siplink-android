package com.sky.siplink.core.logic

import com.sky.siplink.core.model.AccountDraft
import com.sky.siplink.core.model.SipTransport

/** Pure validation / formatting rules for SIP accounts (no Android or SIP core dependency). */
object AccountRules {
    const val MAX_ACCOUNTS = 100
    const val MIN_EXPIRES = 60
    const val MAX_EXPIRES = 86_400

    enum class Error(val message: String) {
        LIMIT_REACHED("最多只能添加 $MAX_ACCOUNTS 个账号"),
        USERNAME_EMPTY("请输入用户名"),
        USERNAME_INVALID("用户名包含无效字符"),
        DOMAIN_EMPTY("请输入域名或服务器地址"),
        DOMAIN_INVALID("域名格式不正确"),
        SERVER_INVALID("代理服务器格式不正确，应为 主机[:端口]"),
        EXPIRES_OUT_OF_RANGE("注册有效期需在 $MIN_EXPIRES–$MAX_EXPIRES 秒之间"),
        DUPLICATE("该账号已存在"),
        PASSWORD_REQUIRED("修改用户名、域名或认证用户后需要重新输入密码"),
        ADDRESS_REJECTED("SIP 协议栈无法解析该账号地址"),
        NOT_FOUND("账号不存在"),
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
     */
    fun validate(
        draft: AccountDraft,
        existingKeys: Collection<String>,
        editingKey: String? = null,
    ): List<Error> {
        val errors = mutableListOf<Error>()
        if (editingKey == null && !canAdd(existingKeys.size)) errors += Error.LIMIT_REACHED

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
