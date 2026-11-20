package com.sky.siplink.core.logic

import com.sky.siplink.R
import com.sky.siplink.core.model.AccountDraft
import com.sky.siplink.core.model.Phrase
import com.sky.siplink.core.model.SipTransport

/** Pure validation / formatting rules for SIP accounts (no Android or SIP core dependency). */
object AccountRules {
    const val MAX_ACCOUNTS = 100
    const val MIN_EXPIRES = 60
    const val MAX_EXPIRES = 86_400
    const val MAX_NAME_LENGTH = 30

    /**
     * @param message what is wrong, as shown right under the field it is about
     * @param full the error where no field stands next to it (in a notice, a list of import errors): it names the field
     */
    enum class Error(val message: Phrase, full: Phrase? = null) {
        LIMIT_REACHED(Phrase.count(R.plurals.account_limit, MAX_ACCOUNTS)),
        NAME_TOO_LONG(FormRules.maxChars(MAX_NAME_LENGTH), Phrase.count(R.plurals.account_error_name_too_long, MAX_NAME_LENGTH)),
        NAME_TAKEN(FormRules.TAKEN, Phrase.of(R.string.account_error_name_taken)),
        USERNAME_EMPTY(FormRules.REQUIRED, Phrase.of(R.string.account_error_username_empty)),
        USERNAME_INVALID(Phrase.of(R.string.account_error_invalid_chars), Phrase.of(R.string.account_error_username_invalid)),
        DOMAIN_EMPTY(FormRules.REQUIRED, Phrase.of(R.string.account_error_domain_empty)),
        DOMAIN_INVALID(Phrase.of(R.string.form_invalid), Phrase.of(R.string.account_error_domain_invalid)),
        PORT_INVALID(FormRules.PORT_RANGE, Phrase.of(R.string.account_error_port_invalid)),
        SERVER_INVALID(FormRules.HOST_PORT, Phrase.of(R.string.account_error_server_invalid)),
        BACKUP_INVALID(FormRules.HOST_PORT, Phrase.of(R.string.account_error_backup_invalid)),
        BACKUP_SAME(Phrase.of(R.string.account_error_same_as_proxy), Phrase.of(R.string.account_error_backup_same)),
        DNS_INVALID(Phrase.of(R.string.account_error_dns_format), Phrase.of(R.string.account_error_dns_invalid)),
        TIMEOUT_INVALID(
            Phrase.of(R.string.account_error_timeout_range, ServerChoice.MIN_TIMEOUT_MS, ServerChoice.MAX_TIMEOUT_MS),
            Phrase.of(R.string.account_error_timeout_invalid, ServerChoice.MIN_TIMEOUT_MS, ServerChoice.MAX_TIMEOUT_MS),
        ),
        EXPIRES_OUT_OF_RANGE(
            Phrase.of(R.string.account_error_expires_range, MIN_EXPIRES, MAX_EXPIRES),
            Phrase.of(R.string.account_error_expires_invalid, MIN_EXPIRES, MAX_EXPIRES),
        ),
        DUPLICATE(Phrase.of(R.string.account_error_duplicate)),

        /** No password is saved to go along, only a digest that belongs to the user name and domain it was made for. */
        PASSWORD_REQUIRED(Phrase.of(R.string.account_error_password_required), Phrase.of(R.string.account_error_password_required_full)),
        ADDRESS_REJECTED(Phrase.of(R.string.account_error_address_rejected)),
        NOT_FOUND(Phrase.of(R.string.account_error_not_found)),

        /** Not a fault of the account: the configuration is managed and takes no change from the phone. */
        MANAGED(ManagedConfig.NOTICE),
        ;

        val full: Phrase = full ?: message
    }

    private val userRegex = Regex("^[A-Za-z0-9\\-_.!~*'()&=+$,?/%]+$")
    private val hostLabel = "[A-Za-z0-9]([A-Za-z0-9\\-]*[A-Za-z0-9])?"
    private val hostRegex = Regex("^$hostLabel(\\.$hostLabel)*\\.?$")
    private val ipv6Regex = Regex("^\\[[0-9A-Fa-f:.]+]$")
    private const val WIDE_COLON = '\uFF1A'

    fun canAdd(currentCount: Int): Boolean = currentCount < MAX_ACCOUNTS

    /** Key used to detect duplicates: user part is case-sensitive, host part is not. */
    fun identityKey(username: String, domain: String): String = "$username@${domain.lowercase()}"

    /**
     * Trims every field, drops a leading `sip:` / `sips:` scheme, splits `user@domain` typed
     * into the username field when the domain was left empty, and takes a port typed behind the
     * domain (`host:port`, as providers print it) to the port field.
     */
    fun normalize(draft: AccountDraft): AccountDraft {
        var username = draft.username.trim().removePrefix("sips:").removePrefix("sip:")
        // A Chinese keyboard types the colon in front of a port in its wide form.
        var domain = draft.domain.trim().replace(WIDE_COLON, ':').removePrefix("sips:").removePrefix("sip:")
        val at = username.lastIndexOf('@')
        if (at > 0) {
            if (domain.isEmpty()) domain = username.substring(at + 1)
            username = username.substring(0, at)
        }
        var port = draft.port.trim()
        val (host, typedPort) = AccountRoute.split(domain)
        if (typedPort != null) {
            // The port written out with the domain is the one that is meant, whatever the port field holds.
            domain = host
            port = typedPort.toString()
        }
        return draft.copy(
            name = draft.name.trim(),
            username = username,
            domain = domain,
            port = port,
            displayName = draft.displayName.trim(),
            authUser = draft.authUser.trim(),
            server = draft.server.trim().removePrefix("sips:").removePrefix("sip:"),
            backupServer = draft.backupServer.trim().removePrefix("sips:").removePrefix("sip:"),
            dnsServer = draft.dnsServer.trim(),
            // Without a backup server there is nothing to go to: the timeout means nothing.
            serverTimeoutMs = if (draft.backupServer.isBlank()) 0 else draft.serverTimeoutMs,
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
        if (draft.port.isNotEmpty() && !isValidPort(draft.port)) errors += Error.PORT_INVALID
        if (draft.server.isNotEmpty() && !isValidHostPort(draft.server)) errors += Error.SERVER_INVALID
        when {
            draft.backupServer.isEmpty() -> Unit
            !isValidHostPort(draft.backupServer) -> errors += Error.BACKUP_INVALID
            draft.backupServer.equals(draft.server.ifEmpty { draft.address }, ignoreCase = true) -> errors += Error.BACKUP_SAME
        }
        if (draft.dnsServer.isNotEmpty() && !AccountRoute.isValidDnsServer(draft.dnsServer)) errors += Error.DNS_INVALID
        if (!ServerChoice.isValidTimeout(draft.serverTimeoutMs)) errors += Error.TIMEOUT_INVALID
        if (draft.expires !in MIN_EXPIRES..MAX_EXPIRES) errors += Error.EXPIRES_OUT_OF_RANGE

        val key = identityKey(draft.username, draft.address)
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
        return port == null || isValidPort(port)
    }

    fun isValidPort(port: String): Boolean =
        port.length in 1..5 && port.all { it in '0'..'9' } && port.toInt() in 1..65_535

    fun identityUri(draft: AccountDraft): String = "sip:${draft.username}@${draft.address}"

    /** URI of the registrar / proxy the account starts on; the transport is carried as a URI parameter. */
    fun serverUri(draft: AccountDraft): String = AccountRoute.uri(draft.server.ifEmpty { draft.address }, draft.transport)

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
