package com.sky.siplink.core

import com.sky.siplink.R
import com.sky.siplink.core.logic.AccountRows
import com.sky.siplink.core.logic.AccountRules
import com.sky.siplink.core.model.AccountDraft
import com.sky.siplink.core.model.CallMediaSummary
import com.sky.siplink.core.model.RegState
import com.sky.siplink.core.model.ServerReport
import com.sky.siplink.core.model.SipAccount
import com.sky.siplink.core.model.SipTransport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.linphone.core.Account
import org.linphone.core.AccountParams
import org.linphone.core.Address
import org.linphone.core.AuthInfo
import org.linphone.core.Call
import org.linphone.core.Core
import org.linphone.core.Factory
import org.linphone.core.RegistrationState
import java.util.UUID

/**
 * SIP accounts (up to [AccountRules.MAX_ACCOUNTS]). The SIP core configuration is the single
 * source of truth; this class validates input and publishes immutable snapshots for the UI.
 * Must be used from the thread that owns the core (the main thread).
 *
 * While the configuration is managed ([ConfigLock]) the accounts cannot be changed here, which
 * each function reports in its own way; where it has a password of its own, theirs are handed
 * out only to who typed it.
 */
class AccountManager(private val core: Core, private val lock: ConfigLock, private val texts: AppTexts) {
    private val state = MutableStateFlow<List<SipAccount>>(emptyList())
    val accounts: StateFlow<List<SipAccount>> = state.asStateFlow()

    /**
     * Credentials of an identity that is being unregistered, because its account was removed or
     * moved to another address. The server challenges that request like any other, so they are
     * kept for a moment: without them the registration would linger on the server.
     */
    private class Parting(val auth: AuthInfo, val identityKey: String)

    private val handler = CoreThread.handler()
    private val parting = ArrayList<Parting>()

    /** The removed accounts that are still unregistering, by native pointer. */
    private val retired = HashMap<Long, Parting>()

    /** How the lines adapt to the network they are on, on the server the router chose. */
    private val doctor: LineDoctor = LineDoctor(
        core, handler, ::find, ::idOf, ::noteChanged,
        onBackup = { router.isOnBackup(it) },
        routing = { router.isRouting(it) },
    )

    /** How the sound of the calls goes: for the line checks, the call screen, and what the lines learn from it. */
    internal val media = CallMediaWatch(core, ::idOf, { router.isOnBackup(idOf(it)) }, doctor::onCallFinished)

    /** Backup servers and DNS servers of the accounts. */
    private val router: AccountRouter = AccountRouter(
        core, handler, ::find, ::idOf, ::noteChanged, doctor::holds,
        ownTransport = { doctor.own(it)?.transport },
        transportFor = doctor::transportFor,
        afterMove = doctor::onServerMoved,
    )

    /** Whether the lines adapt to the network by themselves ([LineDoctor]). */
    var autoAdapt: Boolean
        get() = doctor.enabled
        set(value) {
            doctor.enabled = value
        }

    /** The STUN server the line checks and the lines' ICE ask, `host[:port]`; empty: none. */
    var stunServer: String
        get() = doctor.stunServer
        set(value) {
            doctor.stunServer = value
        }

    /** The accounts whose line runs on another transport than their own just now, with that transport. */
    val adaptedTransports: StateFlow<Map<String, SipTransport>> get() = doctor.transports

    /** Accounts whose registration state changed since the list was last published, by native pointer. */
    private val changed = LinkedHashMap<Long, Account>()
    private val publisher = Runnable { publishChanged() }

    /** The accounts of the core by id while a [batch] runs; null otherwise. */
    private var batch: HashMap<String, Account>? = null

    init {
        refresh()
        router.refresh()
        // Both live as long as the core, which is as long as the process. The doctor learns of the
        // network first, the router chooses the servers on it, and then the doctor puts each line on
        // what it has for that server: one change of each account in this turn of the main thread,
        // and with it one registration (the SIP core registers once the turn is over).
        NetworkWatch.listen { facts ->
            doctor.onNetwork(facts, applyNow = false)
            router.onNetwork(facts)
            doctor.reapply()
        }
    }

    /** The language of the app changed: the list says why a registration failed in it. */
    fun onLanguageChanged() = refresh()

    fun refresh() {
        CoreThread.check("The accounts")
        handler.removeCallbacks(publisher)
        changed.clear()
        val defaultId = core.defaultAccount?.let(::idOf)
        state.value = core.accountList.map { snapshot(it, defaultId) }
    }

    /**
     * Runs [block], which changes many accounts one after the other ([put], [remove]), as one
     * change of the list: each change publishes its own row only, the accounts are found by id in
     * a map instead of through the core, and the list is made anew from the core once at the end.
     * What the core is told, and when, stays as without.
     */
    fun <T> batch(block: () -> T): T {
        if (batch != null) return block()
        batch = core.accountList.associateByTo(HashMap(), ::idOf)
        try {
            return block()
        } finally {
            batch = null
            refresh()
        }
    }

    /**
     * Has the list follow the account that was [id], which is [account] now, or went (null): made
     * anew from the core, or in a [batch] only that row, the batch making it anew at its end.
     */
    private fun refreshAfter(id: String, account: Account?) {
        val known = batch ?: return refresh()
        known.remove(id)
        val row = account?.let { snapshot(it, null).also { snap -> known[snap.id] = it } }
        state.value = AccountRows.replace(state.value, id, row, core.defaultAccount?.let(::idOf))
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
        router.onRegistration(account)
        doctor.onRegistration(account)
        noteChanged(account)
    }

    /** The network is back, maybe another one: the addresses of the servers may differ there. */
    fun onNetworkReachable() {
        doctor.onReachable()
        router.refresh()
    }

    // ---------------------------------------------------------------- the line checks

    /** What the line of account [id] runs on instead of its own settings; null: its own. */
    internal fun adaptation(id: String): LineDoctor.Adaptation? = doctor.adaptation(id)

    /** Whether the PBX of account [id] named an address of its own network as the next hop of its last call. */
    internal fun pbxUnreachable(id: String): Boolean = doctor.pbxUnreachable(id)

    /** How the sound of the last call of account [id] went; null without one since the app started. */
    fun lastCall(id: String): CallMediaSummary? = media.lastCall(id)

    /**
     * A call out on account [id] that went through [server] got no answer: [then] is told
     * whether the account is on its other server now, because [server] is silent and the other
     * one answers ([AccountRouter.leaveSilentServer]).
     */
    internal fun leaveSilentServer(id: String, server: String, then: (Boolean) -> Unit) = router.leaveSilentServer(id, server, then)

    /** Whether account [id] is registered on the server it moved to last ([AccountRouter.registeredSinceMove]). */
    internal fun registeredSinceMove(id: String): Boolean = router.registeredSinceMove(id)

    /** The two servers of account [id], for the line checks; null without a backup server. */
    internal fun servers(id: String): ServerReport? {
        val account = find(id) ?: return null
        val view = router.view(id, account)?.takeIf { it.record.backup.isNotEmpty() } ?: return null
        val seen = view.seen
        return ServerReport(view.onBackup, seen?.primary, seen?.backup, seen?.at, view.record.timeoutMs)
    }

    /** [call] is placed again over the other server ([CallRetry]): its end counts nowhere as a failed call. */
    internal fun supersede(call: Call) {
        doctor.supersede(call)
        media.supersede(call)
    }

    /** [call] could not be placed again after all ([supersede]): it counts as the call out without an answer it is. */
    internal fun unsupersede(call: Call) {
        doctor.unsupersede(call)
        media.unsupersede(call)
        media.failedOut(call, NO_ANSWER)
    }

    /** The fast timeout of account [id] in ms; 0: off, or no backup server. */
    internal fun serverTimeoutMs(id: String): Int = router.record(id)?.takeIf { it.backup.isNotEmpty() }?.timeoutMs ?: 0

    /** Puts the line of account [id] back on its own settings, everywhere, and forgets what was learned for it. */
    fun resetAdaptation(id: String) = CoreThread.post { doctor.forget(id) }

    /** Has [account] shown anew with the next list that is published. */
    private fun noteChanged(account: Account) {
        if (changed.isEmpty()) handler.postDelayed(publisher, PUBLISH_DELAY_MS)
        changed[account.nativePointer] = account
    }

    private fun publishChanged() {
        CoreThread.check("The accounts")
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

    fun find(id: String): Account? {
        val known = batch ?: return core.accountList.firstOrNull { idOf(it) == id }
        return known[id]?.takeIf { idOf(it) == id }
    }

    /**
     * The password saved for the account [id], to show it in its form. Empty where there is none:
     * of the accounts saved by earlier versions only a digest of the password is left. Empty as
     * well where a managed configuration keeps its passwords from being shown and [typed] is not
     * its password ([ConfigLock.showsPasswords]).
     */
    fun passwordOf(id: String, typed: String? = null): String = CoreThread.wait("AccountManager.passwordOf") {
        if (lock.showsPasswords(typed)) find(id)?.findAuthInfo()?.password.orEmpty() else ""
    }

    /**
     * Whether calls may go out on [account]: it is switched on. That includes the moment in
     * which its registration waits for the address of its server.
     */
    fun isSwitchedOn(account: Account): Boolean =
        account.params.isRegisterEnabled || router.record(idOf(account))?.waiting == true

    fun add(input: AccountDraft): List<AccountRules.Error> = CoreThread.wait("AccountManager.add") {
        if (lock.refuses) listOf(AccountRules.Error.MANAGED) else addAs(UUID.randomUUID().toString(), input)
    }

    /**
     * Makes the account [id] what [input] says: creates it with that id where there is none,
     * changes it where it is something else, and leaves it alone where it is [input] already
     * (it is not registered anew). For the remote side, which keeps the ids of its accounts.
     */
    fun put(id: String, input: AccountDraft): List<AccountRules.Error> {
        if (lock.refuses) return listOf(AccountRules.Error.MANAGED)
        val current = state.value.firstOrNull { it.id == id } ?: return addAs(id, input)
        val stored = current.toDraft().copy(password = find(id)?.findAuthInfo()?.password.orEmpty())
        if (AccountRules.normalize(stored) == AccountRules.normalize(input)) return emptyList()
        return changeAccount(id, input)
    }

    private fun addAs(id: String, input: AccountDraft): List<AccountRules.Error> {
        val draft = AccountRules.normalize(input)
        val errors = AccountRules.validate(draft, existingKeys(), takenNames = state.value.map { it.name })
        if (errors.isNotEmpty()) return errors
        val account = create(draft, id) ?: return listOf(AccountRules.Error.ADDRESS_REJECTED)
        refreshAfter(id, account)
        return emptyList()
    }

    /** Adds already validated drafts (bulk import); returns how many were really created (none while the configuration is managed). */
    fun addAll(drafts: List<AccountDraft>): Int = CoreThread.wait("AccountManager.addAll") {
        if (lock.refuses) return@wait 0
        var created = 0
        for (input in drafts) {
            val draft = AccountRules.normalize(input)
            if (AccountRules.validate(draft, existingKeys()).isEmpty() && create(draft, UUID.randomUUID().toString()) != null) created++
        }
        refresh()
        created
    }

    fun update(id: String, input: AccountDraft): List<AccountRules.Error> = CoreThread.wait("AccountManager.update") { changeAccount(id, input) }

    private fun changeAccount(id: String, input: AccountDraft): List<AccountRules.Error> {
        if (lock.refuses) return listOf(AccountRules.Error.MANAGED)
        val account = find(id) ?: return listOf(AccountRules.Error.NOT_FOUND)
        val accountsBefore = state.value
        val before = snapshot(account, null)
        val draft = AccountRules.normalize(input)
        val editingKey = AccountRules.identityKey(before.username, before.domain)
        val otherNames = accountsBefore.filter { it.id != id }.map { it.name }
        val errors = AccountRules.validate(draft, existingKeys(), editingKey, otherNames).toMutableList()

        val credentialsChanged = draft.username != before.username ||
            !draft.address.equals(before.domain, ignoreCase = true) ||
            draft.authUser != before.authUser
        val previousAuth = account.findAuthInfo()
        val savedPassword = previousAuth?.password.orEmpty()
        // An empty field keeps the saved password, also for another user name or server. Where only
        // a digest (HA1) is saved there is nothing to keep: it is bound to the user name and realm.
        val password = draft.password.ifEmpty { savedPassword }
        if (credentialsChanged && password.isEmpty()) errors += AccountRules.Error.PASSWORD_REQUIRED
        if (errors.isNotEmpty()) return errors

        val identity = buildIdentity(draft) ?: return listOf(AccountRules.Error.ADDRESS_REJECTED)
        // The account stays on the server it is on (which may be its backup) unless something about its servers changed.
        val rerouted = before.server != draft.server || before.backupServer != draft.backupServer ||
            before.dnsServer != draft.dnsServer || before.transport != draft.transport ||
            !before.domain.equals(draft.address, ignoreCase = true) || before.enabled != draft.enabled
        val server = if (rerouted) {
            Factory.instance().createAddress(AccountRules.serverUri(draft)) ?: return listOf(AccountRules.Error.ADDRESS_REJECTED)
        } else {
            null
        }

        val params = account.params.clone()
        // What the line adapted to goes, and is learned anew where the way to the server changed.
        doctor.release(id, params, forget = rerouted, credentials = credentialsChanged || password != savedPassword)
        applyParams(id, params, draft, identity, server)
        account.params = params
        if (rerouted) router.begin(id) else router.setTimeout(id, draft.serverTimeoutMs)
        doctor.reapply()
        storeName(id, draft.name)

        if (credentialsChanged || password != savedPassword) {
            // The core tells credentials apart by user name and host. Where both stay, as on another
            // port of the host, the new ones answer for the old address as well, and the old ones
            // would only stand in front of them: the form would show their password, or none.
            val sameLogin = draft.username == before.username &&
                previousAuth?.domain?.equals(identity.domain, ignoreCase = true) == true
            if (credentialsChanged && !sameLogin) {
                previousAuth?.let { old ->
                    val entry = part(old, editingKey)
                    handler.postDelayed({ release(entry) }, UNREGISTER_GRACE_MS)
                }
            } else {
                previousAuth?.let(core::removeAuthInfo)
            }
            addAuthInfo(draft.copy(password = password), identity)
        }
        refreshAfter(id, account)
        onEdited?.invoke(accountsBefore, state.value)
        return emptyList()
    }

    /** @return whether the account was removed: not when there is none with this id, nor while the configuration is managed */
    fun remove(id: String): Boolean = CoreThread.wait("AccountManager.remove") { removeAccount(id) }

    private fun removeAccount(id: String): Boolean {
        if (lock.refuses) return false
        val account = find(id) ?: return false
        val accountsBefore = state.value
        val wasDefault = core.defaultAccount?.let(::idOf) == id
        val auth = account.findAuthInfo()
        val identityKey = identityKeyOf(account.params.identityAddress)
        val registered = account.state == RegistrationState.Ok || account.state == RegistrationState.Progress
        core.removeAccount(account)
        storeName(id, "")
        router.forget(id)
        doctor.drop(id)
        media.forget(id)
        when {
            auth == null -> Unit
            registered -> {
                val pointer = account.nativePointer
                retired[pointer] = part(auth, identityKey)
                handler.postDelayed({ finishRetiring(pointer) }, UNREGISTER_GRACE_MS)
            }
            else -> core.removeAuthInfo(auth)
        }
        if (wasDefault) core.defaultAccount = core.accountList.firstOrNull { idOf(it) != id }
        refreshAfter(id, null)
        onEdited?.invoke(accountsBefore, state.value)
        return true
    }

    private fun finishRetiring(pointer: Long) {
        retired.remove(pointer)?.let(::release)
        if (retired.isEmpty() && afterUnregistering.isNotEmpty()) {
            val waiting = afterUnregistering.toList()
            afterUnregistering.clear()
            waiting.forEach { it() }
        }
    }

    /** What waits for the removed accounts to be unregistered ([whenUnregistered]). */
    private val afterUnregistering = ArrayList<() -> Unit>()

    /**
     * Runs [action] once every removed account has unregistered, or its time to do so
     * ([UNREGISTER_GRACE_MS]) has run out; at once when none is unregistering. What drops the
     * SIP connections (other CAs, other TLS checks) has to wait for this: an un-REGISTER cut off
     * by it is sent again by the SIP core as a REGISTER, and the PBX keeps the binding.
     */
    fun whenUnregistered(action: () -> Unit) {
        if (retired.isEmpty()) action() else afterUnregistering += action
    }

    private fun part(auth: AuthInfo, identityKey: String) = Parting(auth, identityKey).also { parting += it }

    /** Takes the credentials of [entry] out of the core, unless that happened already. */
    private fun release(entry: Parting) {
        if (parting.remove(entry)) core.removeAuthInfo(entry.auth)
    }

    private fun identityKeyOf(identity: Address?): String =
        AccountRules.identityKey(identity?.username.orEmpty(), hostPortOf(identity))

    /** @return whether the account was switched: not when there is none with this id, nor while the configuration is managed */
    fun setEnabled(id: String, enabled: Boolean): Boolean = CoreThread.wait("AccountManager.setEnabled") { switchAccount(id, enabled) }

    private fun switchAccount(id: String, enabled: Boolean): Boolean {
        if (lock.refuses) return false
        val account = find(id) ?: return false
        val accountsBefore = state.value
        if (!enabled) router.disable(id)
        // Switched on, an account whose server has to be looked up first is left to the router.
        if (!enabled || router.enable(id)) {
            val params = account.params.clone()
            params.isRegisterEnabled = enabled
            account.params = params
        }
        // The switch the user just flipped should follow at once.
        onRegistrationChanged(account)
        handler.removeCallbacks(publisher)
        publishChanged()
        onEdited?.invoke(accountsBefore, state.value)
        return true
    }

    /**
     * Has every account that is registered, or registering, register anew with a request built
     * afresh, so that it carries what the core sends now (its User-Agent): a refresh repeats the
     * request the registration was built with. The registration is switched off and on again in
     * one go, and the core builds a new REGISTER when it next runs, without unregistering in
     * between. Accounts that are switched off, or held back by the router, stay as they are.
     */
    fun registerAnew() {
        for (account in core.accountList) {
            val params = account.params
            if (!params.isRegisterEnabled || account.state !in REGISTERED) continue
            val off = params.clone()
            off.isRegisterEnabled = false
            account.params = off
            account.params = params.clone()
        }
    }

    /** @return whether the account is the default one now: not when there is none with this id, nor while the configuration is managed */
    fun setDefault(id: String): Boolean = CoreThread.wait("AccountManager.setDefault") {
        if (lock.refuses) return@wait false
        val account = find(id) ?: return@wait false
        core.defaultAccount = account
        refreshAfter(id, account)
        true
    }

    private fun existingKeys(): List<String> =
        state.value.map { AccountRules.identityKey(it.username, it.domain) }

    /** @return the account, or null when the core did not take it */
    private fun create(draft: AccountDraft, id: String): Account? {
        val identity = buildIdentity(draft) ?: return null
        val server = Factory.instance().createAddress(AccountRules.serverUri(draft)) ?: return null

        val params = core.createAccountParams()
        params.idkey = id
        applyParams(id, params, draft, identity, server)
        storeName(id, draft.name)

        addAuthInfo(draft, identity)
        val account = core.createAccount(params)
        if (core.addAccount(account) != 0) {
            router.forget(id)
            return null
        }
        batch?.put(id, account)
        if (core.defaultAccount == null) core.defaultAccount = account
        router.begin(id)
        return account
    }

    private fun buildIdentity(draft: AccountDraft): Address? {
        val identity = Factory.instance().createAddress(AccountRules.identityUri(draft)) ?: return null
        identity.displayName = draft.displayName.ifEmpty { null }
        return identity
    }

    /** @param server the server the account starts on; null leaves the account on the server it is on */
    private fun applyParams(id: String, params: AccountParams, draft: AccountDraft, identity: Address, server: Address?) {
        params.identityAddress = identity
        params.expires = draft.expires
        // Asked for, the PBX reports the mailbox of the account itself.
        params.mwiServerAddress = if (draft.subscribeMwi) Factory.instance().createAddress(AccountRules.identityUri(draft)) else null
        if (server == null) return
        params.serverAddress = server
        // With a server of its own, calls take the way of the registration instead of looking the domain up.
        params.isOutboundProxyEnabled = draft.server.isNotEmpty() || draft.backupServer.isNotEmpty() || draft.dnsServer.isNotEmpty()
        params.isRegisterEnabled = router.configure(id, draft)
    }

    private fun addAuthInfo(draft: AccountDraft, identity: Address) {
        // What this identity was unregistering with must not answer for it again: the core would
        // prefer those credentials, which have been used before, to the ones entered now.
        val identityKey = identityKeyOf(identity)
        parting.filter { it.identityKey == identityKey }.forEach(::release)
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

    /** What the app knows [account] by, also after it was renamed or moved to another address. */
    fun idOf(account: Account): String {
        val params = account.params
        return params.idkey?.takeIf { it.isNotEmpty() } ?: params.identityAddress?.asStringUriOnly().orEmpty()
    }

    private fun snapshot(account: Account, defaultId: String?): SipAccount {
        val params = account.params
        val identity = params.identityAddress
        val serverAddress = params.serverAddress
        val id = idOf(account)
        val domain = hostPortOf(identity)
        val server = hostPortOf(serverAddress)
        val route = router.view(id, account)
        // The account as the user set it up, not what its line adapted to.
        val own = doctor.own(id)
        val waiting = route?.record?.waiting == true
        val enabled = params.isRegisterEnabled || waiting
        val registration = account.state
        val state = when {
            !enabled -> RegState.DISABLED
            waiting -> RegState.PROGRESS
            registration == RegistrationState.Ok -> if (route?.settling == true) RegState.PROGRESS else RegState.OK
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
            // What the core holds is the server in use, which may be the backup one.
            server = route?.record?.primary ?: if (server.equals(domain, ignoreCase = true)) "" else server,
            transport = own?.transport ?: transportOf(serverAddress),
            expires = own?.expires ?: params.expires,
            enabled = enabled,
            state = state,
            isDefault = id == defaultId,
            error = when {
                state != RegState.FAILED -> null
                route?.dnsFailed == true -> texts.get(R.string.account_dns_failed)
                else -> describeError(account)
            },
            name = core.config.getString(NAMES_SECTION, id, null).orEmpty(),
            backupServer = route?.record?.backup.orEmpty(),
            dnsServer = route?.record?.dns.orEmpty(),
            serverTimeoutMs = route?.record?.takeIf { it.backup.isNotEmpty() }?.timeoutMs ?: 0,
            subscribeMwi = params.mwiServerAddress != null,
            onBackup = route?.onBackup == true,
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
            else -> texts.get(R.string.account_register_failed)
        }
    }

    private companion object {
        /** What the SIP core reports for a request nobody answered. */
        const val NO_ANSWER = 408

        /** How long the credentials of a removed identity are kept if its unregistration stalls. */
        const val UNREGISTER_GRACE_MS = 40_000L

        /** How long registration events are gathered before the list is published. */
        const val PUBLISH_DELAY_MS = 50L

        /** The states of a registration that [registerAnew] builds anew. */
        val REGISTERED = setOf(RegistrationState.Ok, RegistrationState.Refreshing, RegistrationState.Progress)

        /** Section of the core configuration file holding the account names, keyed by account id. */
        const val NAMES_SECTION = "siplink_account_names"
    }
}
