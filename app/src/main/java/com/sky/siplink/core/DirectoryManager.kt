package com.sky.siplink.core

import android.database.sqlite.SQLiteException
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.sky.siplink.core.logic.LdapRules
import com.sky.siplink.core.logic.NameLookup
import com.sky.siplink.core.model.DirectoryContact
import com.sky.siplink.core.model.DirectoryState
import com.sky.siplink.core.model.LdapCertCheck
import com.sky.siplink.core.model.LdapConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.linphone.core.Core
import org.linphone.core.Ldap
import org.linphone.core.LdapParams
import org.linphone.core.MagicSearch
import org.linphone.core.MagicSearchListenerStub
import org.linphone.core.SearchResult
import java.util.concurrent.Executors

/**
 * LDAP directory: one server configuration stored in the SIP core, searched through the
 * core's MagicSearch. Must be used from the thread that owns the core (the main thread).
 *
 * What the server returns is also kept in a local copy ([DirectoryCache]). A search shows the
 * matches of that copy at once, and keeps them when the server has nothing to say - be it
 * because nothing matches there or because it cannot be reached, which the core does not tell apart.
 */
class DirectoryManager(private val core: Core, private val cache: DirectoryCache) {
    private val configState = MutableStateFlow<LdapConfig?>(null)

    /** Current LDAP server configuration (password is never exposed), null when none is set. */
    val config: StateFlow<LdapConfig?> = configState.asStateFlow()

    private val searchState = MutableStateFlow(DirectoryState())
    val state: StateFlow<DirectoryState> = searchState.asStateFlow()

    private val cacheState = MutableStateFlow(DirectoryCache.Stats())

    /** Size of the local copy; brought up to date by [refreshCacheStats] and after every change. */
    val cacheStats: StateFlow<DirectoryCache.Stats> = cacheState.asStateFlow()

    val isAvailable: Boolean get() = core.ldapAvailable()

    private val handler = Handler(Looper.getMainLooper())

    /** The local copy is a file: it is only touched from this thread. */
    private val cacheWorker = Executors.newSingleThreadExecutor()
    private var searchToken = 0

    /**
     * True once a StartTLS search ran. The LDAP library loads the CA certificates once per
     * process, at its first TLS connection, and keeps them from then on.
     */
    var tlsUsed = false
        private set

    /** Whether a change of the trusted CAs only reaches the directory after an app restart. */
    val tlsChangeNeedsRestart: Boolean
        get() = tlsUsed && configState.value?.let { it.enabled && it.useStartTls } == true

    private val magicSearch: MagicSearch = core.createMagicSearch().also {
        it.limitedSearch = false
        it.addListener(object : MagicSearchListenerStub() {
            override fun onSearchResultsReceived(magicSearch: MagicSearch) {
                searchToken++
                val found = magicSearch.lastSearch.flatMap(::toContacts).distinct()
                val shown = searchState.value
                searchState.value = if (found.isEmpty() && shown.fromCache) {
                    // Nothing from the server: what the local copy had stays on screen.
                    shown.copy(searching = false)
                } else {
                    shown.copy(searching = false, contacts = found, fromCache = false)
                }
                if (found.isNotEmpty()) {
                    onCache {
                        store(found)
                        publishCacheStats()
                    }
                }
            }

            override fun onResultsLimitReached(magicSearch: MagicSearch, sourcesFlag: Int) {
                searchState.value = searchState.value.copy(limitReached = true)
            }
        })
    }

    /** Called when the directory was replaced, removed or its local copy emptied: names found before may be wrong now. */
    var onChanged: (() -> Unit)? = null

    /** One question "whose number is this?" on its way to the server. */
    private class Lookup(val key: String, val queries: ArrayDeque<String>, val onResult: (DirectoryContact?) -> Unit)

    private val lookups = ArrayDeque<Lookup>()
    private var lookupToken = 0

    /** A search object of its own, so that looking up a caller does not disturb what the directory screen shows. */
    private val lookupSearch: MagicSearch = core.createMagicSearch().also {
        it.limitedSearch = false
        it.addListener(object : MagicSearchListenerStub() {
            override fun onSearchResultsReceived(magicSearch: MagicSearch) {
                val lookup = lookups.firstOrNull() ?: return
                lookupToken++
                val found = magicSearch.lastSearch.flatMap(::toContacts).distinct()
                if (found.isNotEmpty()) {
                    onCache {
                        store(found)
                        publishCacheStats()
                    }
                }
                val match = NameLookup.pick(lookup.key, found)
                if (match != null || lookup.queries.isEmpty()) finishLookup(match) else askServer()
            }
        })
    }

    init {
        reload()
    }

    /**
     * Finds the directory entry of a number: in the local copy first, which is instant and needs
     * no network, and only when it is not there on the server (whose answer then joins the copy).
     * [onResult] is called on the main thread, with null when the number is not in the directory.
     */
    fun lookup(number: String, onResult: (DirectoryContact?) -> Unit) {
        val key = NameLookup.key(number)
        val config = configState.value
        if (key == null || config == null) {
            onResult(null)
            return
        }
        cacheWorker.execute {
            val cached = try {
                cache.find(key)
            } catch (e: SQLiteException) {
                Log.w(TAG, "Directory cache failed", e)
                null
            }
            handler.post {
                when {
                    cached != null -> onResult(cached)
                    !config.enabled || key.length < config.minChars -> onResult(null)
                    else -> {
                        lookups += Lookup(key, ArrayDeque(NameLookup.queries(key)), onResult)
                        if (lookups.size == 1) askServer()
                    }
                }
            }
        }
    }

    /** The names the local copy has for [numbers], by [NameLookup.key]; never asks the server. */
    fun cachedNames(numbers: Collection<String>, onResult: (Map<String, String>) -> Unit) {
        val keys = numbers.mapNotNull(NameLookup::key).distinct()
        if (keys.isEmpty() || configState.value == null) return
        onCache {
            val names = names(keys)
            if (names.isNotEmpty()) handler.post { onResult(names) }
        }
    }

    private fun askServer() {
        val lookup = lookups.firstOrNull() ?: return
        val config = configState.value
        val query = lookup.queries.removeFirstOrNull()
        if (config == null || !config.enabled || query == null) {
            finishLookup(null)
            return
        }
        if (config.useStartTls) tlsUsed = true
        val token = ++lookupToken
        lookupSearch.resetSearchCache()
        lookupSearch.getContactsListAsync(query, "", MagicSearch.Source.LdapServers.toInt(), MagicSearch.Aggregation.None)
        // The server may never answer: do not keep the callers behind this one waiting.
        handler.postDelayed({ if (token == lookupToken) finishLookup(null) }, (config.timeoutSec + 3) * 1000L)
    }

    private fun finishLookup(match: DirectoryContact?) {
        val lookup = lookups.removeFirstOrNull() ?: return
        lookupToken++
        lookup.onResult(match)
        askServer()
    }

    /** @return validation errors, empty when the configuration was saved */
    fun save(input: LdapConfig): List<LdapRules.Error> {
        val config = LdapRules.normalize(input)
        val errors = LdapRules.validate(config)
        if (errors.isNotEmpty()) return errors

        val existing = core.ldapList.firstOrNull()
        val params = core.createLdapParams()
        params.enabled = config.enabled
        params.server = config.server
        params.isTlsEnabled = config.useStartTls
        params.serverCertificatesVerificationMode = when (config.certCheck) {
            LdapCertCheck.DEFAULT -> Ldap.CertVerificationMode.Default
            LdapCertCheck.ENABLED -> Ldap.CertVerificationMode.Enabled
            LdapCertCheck.DISABLED -> Ldap.CertVerificationMode.Disabled
        }
        if (config.bindDn.isEmpty()) {
            params.authMethod = Ldap.AuthMethod.Anonymous
            params.bindDn = ""
            params.password = ""
        } else {
            params.authMethod = Ldap.AuthMethod.Simple
            params.bindDn = config.bindDn
            // An empty password field means "keep the stored one".
            params.password = config.password.ifEmpty { existing?.params?.password.orEmpty() }
        }
        params.baseObject = config.baseDn
        params.filter = config.filter
        params.nameAttribute = config.nameAttributes
        params.sipAttribute = config.sipAttributes
        params.sipDomain = config.sipDomain
        params.maxResults = config.maxResults
        params.minChars = config.minChars
        params.timeout = config.timeoutSec
        params.delay = config.delayMs
        params.debugLevel = Ldap.DebugLevel.Off

        if (existing != null) {
            existing.params = params
        } else {
            core.addLdap(core.createLdapWithParams(params))
        }
        magicSearch.resetSearchCache()
        // Entries of another directory, or built for another SIP domain, are of no use any more.
        val before = configState.value
        if (before != null && (before.server != config.server || before.baseDn != config.baseDn || before.sipDomain != config.sipDomain)) {
            clearCache()
        }
        reload()
        onChanged?.invoke()
        return emptyList()
    }

    fun remove() {
        core.ldapList.forEach(core::removeLdap)
        magicSearch.resetSearchCache()
        searchState.value = DirectoryState()
        clearCache()
        reload()
    }

    /** Empties the local copy of the directory. */
    fun clearCache() {
        searchState.value = searchState.value.let { if (it.fromCache) it.copy(contacts = emptyList(), fromCache = false) else it }
        onCache {
            clear()
            publishCacheStats()
        }
        onChanged?.invoke()
    }

    fun refreshCacheStats() = onCache { publishCacheStats() }

    private fun DirectoryCache.publishCacheStats() {
        val stats = stats()
        handler.post { cacheState.value = stats }
    }

    /** Runs [work] on the thread of the local copy. A broken copy must never take the directory down with it. */
    private fun onCache(work: DirectoryCache.() -> Unit) {
        cacheWorker.execute {
            try {
                cache.work()
            } catch (e: SQLiteException) {
                Log.w(TAG, "Directory cache failed", e)
            }
        }
    }

    fun search(query: String) {
        val text = query.trim()
        val config = configState.value
        if (config == null || !config.enabled || text.length < config.minChars) {
            searchToken++
            searchState.value = DirectoryState(query = text)
            return
        }
        val token = ++searchToken
        if (config.useStartTls) tlsUsed = true
        // What is on screen stays there until something better arrives, but it is not this query's answer.
        searchState.value = searchState.value.copy(query = text, searching = true, limitReached = false, fromCache = false)
        // The local copy answers first; the server's answer replaces it when it has one.
        onCache {
            val cached = search(text, config.maxResults)
            handler.post {
                if (token == searchToken && cached.isNotEmpty()) {
                    searchState.value = searchState.value.copy(contacts = cached, fromCache = true)
                }
            }
        }
        magicSearch.resetSearchCache()
        magicSearch.getContactsListAsync(
            text,
            "",
            MagicSearch.Source.LdapServers.toInt(),
            MagicSearch.Aggregation.None,
        )
        // Safety net: never leave the spinner on if the server does not answer. What the local
        // copy had stays; the results of an earlier query do not.
        handler.postDelayed({
            if (token == searchToken) {
                searchState.value = searchState.value.let { it.copy(searching = false, contacts = if (it.fromCache) it.contacts else emptyList()) }
            }
        }, (config.timeoutSec + 3) * 1000L)
    }

    private fun reload() {
        configState.value = core.ldapList.firstOrNull()?.params?.let(::toConfig)
    }

    private fun toConfig(params: LdapParams): LdapConfig = LdapConfig(
        enabled = params.enabled,
        server = params.server,
        useStartTls = params.isTlsEnabled,
        certCheck = when (params.serverCertificatesVerificationMode) {
            Ldap.CertVerificationMode.Enabled -> LdapCertCheck.ENABLED
            Ldap.CertVerificationMode.Disabled -> LdapCertCheck.DISABLED
            else -> LdapCertCheck.DEFAULT
        },
        bindDn = params.bindDn.orEmpty(),
        password = "",
        baseDn = params.baseObject,
        filter = params.filter.orEmpty(),
        nameAttributes = params.nameAttribute.orEmpty(),
        sipAttributes = params.sipAttribute.orEmpty(),
        sipDomain = params.sipDomain.orEmpty(),
        maxResults = params.maxResults,
        minChars = params.minChars,
        timeoutSec = params.timeout,
        delayMs = params.delay,
    )

    /**
     * One row per number of the entry (extension, mobile, ...). The address of a search result
     * does not always belong to the number it reports, so the call target is built from the
     * number that is shown.
     */
    private fun toContacts(result: SearchResult): List<DirectoryContact> {
        val address = result.address
        val friend = result.friend
        val name = friend?.name?.takeIf { it.isNotBlank() } ?: address?.displayName?.takeIf { it.isNotBlank() }
        val organization = friend?.organization.orEmpty()
        val numbers = (friend?.phoneNumbers.orEmpty().toList() + listOfNotNull(result.phoneNumber))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        if (numbers.isNotEmpty()) {
            return numbers.map { DirectoryContact(name ?: it, it, callTargetOf(it), organization) }
        }
        // No number: the entry only has a SIP address.
        val user = address?.username?.takeIf { it.isNotBlank() } ?: return emptyList()
        return listOf(DirectoryContact(name ?: user, user, address.asStringUriOnly(), organization))
    }

    /** A number is dialled in the configured SIP domain, or else in the domain of the calling account. */
    private fun callTargetOf(number: String): String {
        val domain = configState.value?.sipDomain.orEmpty()
        if (domain.isEmpty()) return number
        return "sip:${number.filterNot { it.isWhitespace() || it in "()-" }}@$domain"
    }

    private companion object {
        const val TAG = "SipLink.Directory"
    }
}
