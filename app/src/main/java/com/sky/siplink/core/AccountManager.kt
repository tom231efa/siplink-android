package com.sky.siplink.core

import android.os.Handler
import android.os.Looper
import com.sky.siplink.core.logic.AccountRules
import com.sky.siplink.core.model.AccountDraft
import com.sky.siplink.core.model.RegState
import com.sky.siplink.core.model.SipAccount
import com.sky.siplink.core.model.SipTransport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.linphone.core.Account
import org.linphone.core.AccountParams
import org.linphone.core.Address
import org.linphone.core.AuthInfo
import org.linphone.core.Core
import org.linphone.core.Factory
import org.linphone.core.RegistrationState
import org.linphone.core.TransportType
import java.util.UUID

/**
 * SIP accounts (up to [AccountRules.MAX_ACCOUNTS]). The SIP core configuration is the single
 * source of truth; this class validates input and publishes immutable snapshots for the UI.
 * Must be used from the thread that owns the core (the main thread).
 */
class AccountManager(private val core: Core) {
    private val state = MutableStateFlow<List<SipAccount>>(emptyList())
    val accounts: StateFlow<List<SipAccount>> = state.asStateFlow()

    /** Credentials of a removed account, kept until its unregistration went through. */
    private class Retired(val auth: AuthInfo, val identityKey: String)

    private val handler = Handler(Looper.getMainLooper())
    private val retired = HashMap<Long, Retired>()

    /** Accounts whose registration state changed since the list was last published, by native pointer. */
    private val changed = LinkedHashMap<Long, Account>()
    private val publisher = Runnable { publishChanged() }

    init {
        refresh()
    }

    fun refresh() {
        handler.removeCallbacks(publisher)
        changed.clear()
        val defaultId = core.defaultAccount?.let(::idOf)
        state.value = core.accountList.map { snapshot(it, defaultId) }
    }

    /**
     * Registration events come in bursts (a hundred accounts registering at once), so they are
     * gathered for a moment and published as one new list instead of one list per event.
     */
    fun onRegistrationChanged(account: Account) {
        val pointer = account.nativePointer
        if (pointer in retired) {
            // A removed account: once it is unregistered its credentials can go.
            if (account.state == RegistrationState.Cleared || account.state == RegistrationState.Failed) {
                finishRetiring(pointer)
            }
            return
        }
        if (changed.isEmpty()) handler.postDelayed(publisher, PUBLISH_DELAY_MS)
        changed[pointer] = account
    }

    private fun publishChanged() {
        val accounts = changed.values.toList()
        changed.clear()
        val list = state.value.toMutableList()
        val positions = HashMap<String, Int>(list.size * 2)
        list.forEachIndexed { index, account -> positions[account.id] = index }
        for (account in accounts) {
            val id = idOf(account)
            val index = positions[id]
            if (index == null) {
                // Not an account of the list: start over from what the core has.
                refresh()
                return
            }
            list[index] = snapshot(account, if (list[index].isDefault) id else null)
        }
        state.value = list
    }

    /**
     * Called after the user changed, switched or removed an account, with the accounts before and after:
     * whatever refers to an account by its name or address has to follow.
     */
    var onEdited: ((before: List<SipAccount>, after: List<SipAccount>) -> Unit)? = null

    fun find(id: String): Account? = core.accountList.firstOrNull { idOf(it) == id }

    fun add(input: AccountDraft): List<AccountRules.Error> {
        val draft = AccountRules.normalize(input)
        val errors = AccountRules.validate(draft, existingKeys(), takenNames = state.value.map { it.name })
        if (errors.isNotEmpty()) return errors
        if (!create(draft)) return listOf(AccountRules.Error.ADDRESS_REJECTED)
        refresh()
        return emptyList()
    }

    /** Adds already validated drafts (bulk import); returns how many were really created. */
    fun addAll(drafts: List<AccountDraft>): Int {
        var created = 0
        for (input in drafts) {
            val draft = AccountRules.normalize(input)
            if (AccountRules.validate(draft, existingKeys()).isEmpty() && create(draft)) created++
        }
        refresh()
        return created
    }

    fun update(id: String, input: AccountDraft): List<AccountRules.Error> {
        val account = find(id) ?: return listOf(AccountRules.Error.NOT_FOUND)
        val accountsBefore = state.value
        val before = snapshot(account, null)
        val draft = AccountRules.normalize(input)
        val editingKey = AccountRules.identityKey(before.username, before.domain)
        val otherNames = accountsBefore.filter { it.id != id }.map { it.name }
        val errors = AccountRules.validate(draft, existingKeys(), editingKey, otherNames).toMutableList()

        val credentialsChanged = draft.username != before.username ||
            !draft.domain.equals(before.domain, ignoreCase = true) ||
            draft.authUser != before.authUser
        // A stored digest (HA1) is bound to the user name and realm, so it cannot be reused.
        if (credentialsChanged && draft.password.isEmpty()) errors += AccountRules.Error.PASSWORD_REQUIRED
        if (errors.isNotEmpty()) return errors

        val identity = buildIdentity(draft) ?: return listOf(AccountRules.Error.ADDRESS_REJECTED)
        val server = Factory.instance().createAddress(AccountRules.serverUri(draft))
            ?: return listOf(AccountRules.Error.ADDRESS_REJECTED)

        val previousAuth = account.findAuthInfo()
        val params = account.params.clone()
        applyParams(params, draft, identity, server)
        account.params = params
        storeName(id, draft.name)

        if (draft.password.isNotEmpty()) {
            if (credentialsChanged) {
                // The old identity is unregistered now and still needs its credentials for that.
                previousAuth?.let { old -> handler.postDelayed({ dropAuth(old, editingKey) }, UNREGISTER_GRACE_MS) }
            } else {
                previousAuth?.let(core::removeAuthInfo)
            }
            addAuthInfo(draft, identity)
        }
        refresh()
        onEdited?.invoke(accountsBefore, state.value)
        return emptyList()
    }

    fun remove(id: String) {
        val account = find(id) ?: return
        val accountsBefore = state.value
        val wasDefault = core.defaultAccount?.let(::idOf) == id
        val auth = account.findAuthInfo()
        val identityKey = identityKeyOf(account)
        val registered = account.state == RegistrationState.Ok || account.state == RegistrationState.Progress
        core.removeAccount(account)
        storeName(id, "")
        when {
            auth == null -> Unit
            // The core now sends an un-REGISTER, which the server challenges like any other
            // request: without the credentials the registration would linger on the server.
            registered -> {
                val pointer = account.nativePointer
                retired[pointer] = Retired(auth, identityKey)
                handler.postDelayed({ finishRetiring(pointer) }, UNREGISTER_GRACE_MS)
            }
            else -> dropAuth(auth, identityKey)
        }
        if (wasDefault) core.defaultAccount = core.accountList.firstOrNull { idOf(it) != id }
        refresh()
        onEdited?.invoke(accountsBefore, state.value)
    }

    private fun finishRetiring(pointer: Long) {
        val entry = retired.remove(pointer) ?: return
        dropAuth(entry.auth, entry.identityKey)
    }

    private fun dropAuth(auth: AuthInfo, identityKey: String) {
        // The same identity may have been added again meanwhile: then the credentials stay.
        if (core.accountList.none { identityKeyOf(it) == identityKey }) core.removeAuthInfo(auth)
    }

    private fun identityKeyOf(account: Account): String {
        val identity = account.params.identityAddress
        return AccountRules.identityKey(identity?.username.orEmpty(), hostPort(identity))
    }

    fun setEnabled(id: String, enabled: Boolean) {
        val account = find(id) ?: return
        val accountsBefore = state.value
        val params = account.params.clone()
        params.isRegisterEnabled = enabled
        account.params = params
        // The switch the user just flipped should follow at once.
        onRegistrationChanged(account)
        handler.removeCallbacks(publisher)
        publishChanged()
        onEdited?.invoke(accountsBefore, state.value)
    }

    fun setDefault(id: String) {
        val account = find(id) ?: return
        core.defaultAccount = account
        refresh()
    }

    private fun existingKeys(): List<String> =
        state.value.map { AccountRules.identityKey(it.username, it.domain) }

    private fun create(draft: AccountDraft): Boolean {
        val identity = buildIdentity(draft) ?: return false
        val server = Factory.instance().createAddress(AccountRules.serverUri(draft)) ?: return false

        val params = core.createAccountParams()
        val id = UUID.randomUUID().toString()
        params.idkey = id
        applyParams(params, draft, identity, server)
        storeName(id, draft.name)

        addAuthInfo(draft, identity)
        val account = core.createAccount(params)
        if (core.addAccount(account) != 0) return false
        if (core.defaultAccount == null) core.defaultAccount = account
        return true
    }

    private fun buildIdentity(draft: AccountDraft): Address? {
        val identity = Factory.instance().createAddress(AccountRules.identityUri(draft)) ?: return null
        identity.displayName = draft.displayName.ifEmpty { null }
        return identity
    }

    private fun applyParams(params: AccountParams, draft: AccountDraft, identity: Address, server: Address) {
        params.identityAddress = identity
        params.serverAddress = server
        params.isOutboundProxyEnabled = draft.server.isNotEmpty()
        params.isRegisterEnabled = draft.enabled
        params.expires = draft.expires
    }

    private fun addAuthInfo(draft: AccountDraft, identity: Address) {
        val authInfo = Factory.instance().createAuthInfo(
            draft.username,
            draft.authUser.ifEmpty { null },
            draft.password,
            null,
            null,
            identity.domain,
        )
        core.addAuthInfo(authInfo)
    }

    /** The name is the app's own business: the SIP core has no field for it. */
    private fun storeName(id: String, name: String) {
        if (name.isEmpty()) core.config.cleanEntry(NAMES_SECTION, id) else core.config.setString(NAMES_SECTION, id, name)
    }

    private fun idOf(account: Account): String {
        val params = account.params
        return params.idkey?.takeIf { it.isNotEmpty() } ?: params.identityAddress?.asStringUriOnly().orEmpty()
    }

    private fun snapshot(account: Account, defaultId: String?): SipAccount {
        val params = account.params
        val identity = params.identityAddress
        val serverAddress = params.serverAddress
        val id = idOf(account)
        val domain = hostPort(identity)
        val server = hostPort(serverAddress)
        val registration = account.state
        val state = when {
            !params.isRegisterEnabled -> RegState.DISABLED
            registration == RegistrationState.Ok -> RegState.OK
            registration == RegistrationState.Progress || registration == RegistrationState.Refreshing -> RegState.PROGRESS
            registration == RegistrationState.Failed -> RegState.FAILED
            else -> RegState.NONE
        }
        return SipAccount(
            id = id,
            username = identity?.username.orEmpty(),
            domain = domain,
            displayName = identity?.displayName.orEmpty(),
            authUser = account.findAuthInfo()?.userid.orEmpty(),
            server = if (server.equals(domain, ignoreCase = true)) "" else server,
            transport = when (serverAddress?.transport) {
                TransportType.Tcp -> SipTransport.TCP
                TransportType.Tls -> SipTransport.TLS
                else -> SipTransport.UDP
            },
            expires = params.expires,
            enabled = params.isRegisterEnabled,
            state = state,
            isDefault = id == defaultId,
            error = if (state == RegState.FAILED) describeError(account) else null,
            name = core.config.getString(NAMES_SECTION, id, null).orEmpty(),
        )
    }

    private fun describeError(account: Account): String {
        val info = account.errorInfo
        val phrase = info.phrase.orEmpty()
        val code = info.protocolCode
        return when {
            code > 0 && phrase.isNotEmpty() -> "$code $phrase"
            phrase.isNotEmpty() -> phrase
            code > 0 -> code.toString()
            else -> "注册失败"
        }
    }

    /** `host[:port]`, with IPv6 literals wrapped in brackets. */
    private fun hostPort(address: Address?): String {
        val host = address?.domain.orEmpty()
        if (host.isEmpty()) return ""
        val literal = if (':' in host && !host.startsWith("[")) "[$host]" else host
        val port = address?.port ?: 0
        return if (port > 0) "$literal:$port" else literal
    }

    private companion object {
        /** How long the credentials of a removed identity are kept if its unregistration stalls. */
        const val UNREGISTER_GRACE_MS = 40_000L

        /** How long registration events are gathered before the list is published. */
        const val PUBLISH_DELAY_MS = 50L

        /** Section of the core configuration file holding the account names, keyed by account id. */
        const val NAMES_SECTION = "siplink_account_names"
    }
}
