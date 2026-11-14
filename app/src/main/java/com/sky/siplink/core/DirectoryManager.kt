package com.sky.siplink.core

import android.database.sqlite.SQLiteException
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.sky.siplink.core.logic.LdapRules
import com.sky.siplink.core.logic.NameLookup
import com.sky.siplink.core.model.DirectoryContact
import com.sky.siplink.core.model.DirectoryState
import com.sky.siplink.core.model.LdapDirectory
import com.sky.siplink.core.model.LdapSecurity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.linphone.core.Core
import org.linphone.core.Ldap
import org.linphone.core.LdapParams
import org.linphone.core.MagicSearch
import org.linphone.core.MagicSearchListenerStub
import org.linphone.core.SearchResult
import java.util.UUID
import java.util.concurrent.Executors

/**
 * The LDAP directories: up to [LdapRules.MAX_DIRECTORIES] of them, each with its own server,
 * filters and lines. Must be used from the thread that owns the core (the main thread).
 *
 * The SIP core searches every server it knows at once and does not say where an entry came
 * from. So it only ever knows one: the directory that is being asked right now. Questions wait
 * in a queue and go out one at a time.
 *
 * What the servers return is also kept in a local copy ([DirectoryCache]). A search shows the
 * matches of that copy at once, and keeps them when the server has nothing to say - be it
 * because nothing matches there or because it cannot be reached, which the core does not tell apart.
 */
class DirectoryManager(private val core: Core, private val cache: DirectoryCache) {
    private val store = LdapStore(core.config)
    private val listState = MutableStateFlow(emptyList<LdapDirectory>())

    /** The directories in their order; passwords are never exposed. */
    val directories: StateFlow<List<LdapDirectory>> = listState.asStateFlow()

    private val searchState = MutableStateFlow(DirectoryState())

    /** What [search] found. */
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

    /** Whether a change of the trusted CAs only reaches the directories after an app restart. */
    val tlsChangeNeedsRestart: Boolean
        get() = tlsUsed && listState.value.any { it.enabled && it.security == LdapSecurity.START_TLS }

    /**
     * How long the SIP core waits before it sends a question to the server, in milliseconds.
     * Tests raise it to tell the answer of the local copy from the answer of the server.
     */
    var serverDelayMs = SERVER_DELAY_MS

    /** Called when a directory was changed or removed, or the local copy emptied: names found before may be wrong now. */
    var onChanged: (() -> Unit)? = null

    /**
     * One question to one server.
     *
     * @param forScreen asked by [search]; a newer search takes its place while it waits
     * @param onDone called with what the server returned (null when it did not answer in time)
     *   and whether there was more than it may return
     */
    private class Question(
        val directory: LdapDirectory,
        val text: String,
        val byNumber: Boolean,
        val forScreen: Boolean,
        val onDone: (List<DirectoryContact>?, Boolean) -> Unit,
    )

    /** The question that is with the server, and the search object that carries it. */
    private class Asking(val question: Question, val search: MagicSearch, val listener: MagicSearchListenerStub) {
        var limitReached = false
    }

    private val questions = ArrayDeque<Question>()
    private var asking: Asking? = null

    init {
        adoptLegacy()
        listState.value = store.load()
        if (listState.value.isEmpty()) forgetServer()
    }

    // ------------------------------------------------------------------ the directories

    /**
     * Adds [input] when it has no id, else replaces the directory with that id.
     *
     * @return validation errors, empty when the directory was saved
     */
    fun save(input: LdapDirectory): List<LdapRules.Error> {
        val directory = LdapRules.normalize(input)
        val list = listState.value
        val before = list.firstOrNull { it.id == directory.id }
        val errors = LdapRules.validate(directory, list.filter { it !== before }.map { it.name }, isNew = before == null)
        if (errors.isNotEmpty()) return errors

        val stored = directory.copy(id = before?.id ?: UUID.randomUUID().toString(), password = "")
        val password = when {
            // Nobody to bind as: nothing to keep a password for.
            directory.bindDn.isEmpty() -> ""
            // An empty password field means "keep the stored one".
            directory.password.isEmpty() -> null
            else -> directory.password
        }
        store.save(
            if (before == null) list + stored else list.map { if (it === before) stored else it },
            if (password == null) emptyMap() else mapOf(stored.id to password),
        )
        // Entries of another server or another part of it are not the entries of this directory.
        if (before != null && (before.host != stored.host || before.port != stored.port || before.baseDn != stored.baseDn)) {
            dropCopy(stored.id)
        }
        listState.value = store.load()
        onChanged?.invoke()
        return emptyList()
    }

    /** The password saved for the directory [id], to show it in its form. */
    fun passwordOf(id: String): String = store.passwordOf(id)

    fun remove(id: String) {
        val list = listState.value
        if (list.none { it.id == id }) return
        store.save(list.filter { it.id != id })
        listState.value = store.load()
        if (listState.value.isEmpty()) forgetServer()
        if (searchState.value.directory == id) {
            searchToken++
            searchState.value = DirectoryState()
        }
        dropCopy(id)
        onChanged?.invoke()
    }

    /**
     * The accounts changed: a directory whose line is none of [accounts] (ids) any more goes
     * back to being for every line.
     */
    fun followAccounts(accounts: Set<String>) {
        val list = listState.value
        val followed = list.map { if (it.line.isEmpty() || it.line in accounts) it else it.copy(line = "") }
        if (followed == list) return
        store.save(followed)
        listState.value = store.load()
        onChanged?.invoke()
    }

    /**
     * A directory configured before there could be several was kept by the SIP core itself:
     * it becomes the first directory of the list.
     */
    private fun adoptLegacy() {
        if (store.load().isNotEmpty()) return
        val params = core.ldapList.firstOrNull()?.params ?: return
        // Its one filter went over names and numbers alike.
        val filter = LdapRules.fromCoreFilter(params.filter.orEmpty())
        val legacy = LdapRules.normalize(
            LdapDirectory(
                id = UUID.randomUUID().toString(),
                enabled = params.enabled,
                host = params.server,
                security = if (params.isTlsEnabled) LdapSecurity.START_TLS else LdapSecurity.NONE,
                bindDn = params.bindDn.orEmpty(),
                baseDn = params.baseObject,
                maxResults = params.maxResults.takeIf { it in 1..LdapRules.MAX_RESULTS } ?: LdapRules.MAX_RESULTS,
                nameAttributes = params.nameAttribute.orEmpty(),
                numberAttributes = params.sipAttribute.orEmpty(),
                nameFilter = filter,
                numberFilter = filter,
            ),
        )
        store.save(listOf(legacy), mapOf(legacy.id to params.password.orEmpty()))
    }

    // ------------------------------------------------------------------ the local copy

    /** Empties the local copy of the directories. */
    fun clearCache() {
        searchState.value = searchState.value.let { if (it.fromCache) it.copy(contacts = emptyList(), fromCache = false) else it }
        onCache {
            clear()
            publishCacheStats()
        }
        onChanged?.invoke()
    }

    fun refreshCacheStats() = onCache { publishCacheStats() }

    private fun dropCopy(directory: String) = onCache {
        clear(directory)
        publishCacheStats()
    }

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

    // ------------------------------------------------------------------ searching

    /**
     * Searches the directory with the id [directoryId] for [query]: among the numbers when it
     * looks like one, else among the names. The result arrives in [state].
     */
    fun search(directoryId: String, query: String) {
        val text = query.trim()
        val directory = listState.value.firstOrNull { it.id == directoryId && it.enabled }
        val token = ++searchToken
        questions.removeAll { it.forScreen }
        if (directory == null) {
            searchState.value = DirectoryState(directory = directoryId, query = text)
            return
        }
        // What is on screen stays there until something better arrives, but it is not this
        // query's answer - and nothing of another directory stays.
        val shown = searchState.value
        searchState.value = shown.copy(
            directory = directory.id,
            query = text,
            searching = true,
            contacts = if (shown.directory == directory.id) shown.contacts else emptyList(),
            limitReached = false,
            fromCache = false,
        )
        // The local copy answers first; the server's answer replaces it when it has one.
        onCache {
            val cached = search(directory.id, text, directory.maxResults)
            handler.post {
                if (token == searchToken && searchState.value.searching && cached.isNotEmpty()) {
                    searchState.value = searchState.value.copy(contacts = cached, fromCache = true)
                }
            }
        }
        questions += Question(directory, text, LdapRules.isNumberQuery(text), forScreen = true) { found, limitReached ->
            if (token != searchToken) return@Question
            val now = searchState.value
            searchState.value = if (found.isNullOrEmpty() && now.fromCache) {
                // Nothing from the server: what the local copy had stays on screen.
                now.copy(searching = false)
            } else {
                now.copy(searching = false, contacts = found.orEmpty(), fromCache = false, limitReached = limitReached)
            }
        }
        askNext()
    }

    /** The ids of the directories that name the other side of a call ([LdapRules.namingDirectories]). */
    fun namingScope(incoming: Boolean, line: String?): List<String> =
        LdapRules.namingDirectories(listState.value, incoming, line).map { it.id }

    /**
     * Finds the directory entry of the number of a call: in the local copy first, which is
     * instant and needs no network, and only when it is not there on the servers (whose answer
     * then joins the copy), one directory after the other.
     * [onResult] is called on the main thread, with null when no directory has the number.
     *
     * @param incoming the call came in, rather than being placed
     * @param line id of the account the call is on, null when that is not known
     */
    fun lookup(number: String, incoming: Boolean, line: String?, onResult: (DirectoryContact?) -> Unit) {
        val key = NameLookup.key(number)
        val naming = LdapRules.namingDirectories(listState.value, incoming, line)
        if (key == null || naming.isEmpty()) {
            onResult(null)
            return
        }
        cacheWorker.execute {
            val cached = try {
                cache.find(key, naming.map { it.id })
            } catch (e: SQLiteException) {
                Log.w(TAG, "Directory cache failed", e)
                null
            }
            handler.post { if (cached != null) onResult(cached) else askServers(key, naming, onResult) }
        }
    }

    /** Asks [directories] in turn who [key] is, each with the questions of [NameLookup.queries]. */
    private fun askServers(key: String, directories: List<LdapDirectory>, onResult: (DirectoryContact?) -> Unit) {
        val steps = ArrayDeque(directories.flatMap { directory -> NameLookup.queries(key).map { directory to it } })

        fun step() {
            val (directory, query) = steps.removeFirstOrNull() ?: return onResult(null)
            val question = Question(directory, query, byNumber = true, forScreen = false) { found, _ ->
                val match = found?.let { NameLookup.pick(key, it) }
                if (match != null) onResult(match) else step()
            }
            // A call is waiting for its name: that goes before what the directory screen asked.
            questions.add(questions.indexOfFirst { it.forScreen }.takeIf { it >= 0 } ?: questions.size, question)
            askNext()
        }
        step()
    }

    /** The names the local copy has for [numbers], by [NameLookup.key]; never asks a server. */
    fun cachedNames(numbers: Collection<String>, onResult: (Map<String, String>) -> Unit) {
        val keys = numbers.mapNotNull(NameLookup::key).distinct()
        val naming = listState.value.filter { it.enabled && (it.lookupIncoming || it.lookupOutgoing) }.map { it.id }
        if (keys.isEmpty() || naming.isEmpty()) return
        onCache {
            val names = names(keys, naming)
            if (names.isNotEmpty()) handler.post { onResult(names) }
        }
    }

    // ------------------------------------------------------------------ the server

    private fun askNext() {
        if (asking != null) return
        val question = questions.removeFirstOrNull() ?: return
        // The directory may have been changed or removed while the question waited.
        val directory = listState.value.firstOrNull { it.id == question.directory.id && it.enabled }
        if (directory == null) {
            question.onDone(null, false)
            askNext()
            return
        }
        pointServerAt(directory, question.byNumber)
        if (directory.security == LdapSecurity.START_TLS) tlsUsed = true

        // A search object of its own for every question: an answer that comes after its time
        // then cannot be taken for the answer to the next one.
        val search = core.createMagicSearch()
        search.limitedSearch = false
        lateinit var current: Asking
        val listener = object : MagicSearchListenerStub() {
            override fun onSearchResultsReceived(magicSearch: MagicSearch) {
                finish(current, magicSearch.lastSearch.flatMap(::toContacts).distinct())
            }

            override fun onResultsLimitReached(magicSearch: MagicSearch, sourcesFlag: Int) {
                current.limitReached = true
            }
        }
        current = Asking(question, search, listener)
        asking = current
        search.addListener(listener)
        search.getContactsListAsync(question.text, "", MagicSearch.Source.LdapServers.toInt(), MagicSearch.Aggregation.None)
        // The server may never answer: do not keep the next question waiting for ever.
        handler.postDelayed({ finish(current, null) }, serverDelayMs + (TIMEOUT_SEC + GRACE_SEC) * 1000L)
    }

    private fun finish(done: Asking, found: List<DirectoryContact>?) {
        if (asking !== done) return
        asking = null
        done.search.removeListener(done.listener)
        val directory = done.question.directory.id
        // Nothing is kept for a directory that was removed while its server was being asked.
        if (!found.isNullOrEmpty() && listState.value.any { it.id == directory }) {
            onCache {
                store(directory, found)
                publishCacheStats()
            }
        }
        done.question.onDone(found, done.limitReached)
        askNext()
    }

    /** Makes [directory] the one server the SIP core knows, to be asked for names or for numbers. */
    private fun pointServerAt(directory: LdapDirectory, byNumber: Boolean) {
        val params = core.createLdapParams()
        params.enabled = true
        params.server = LdapRules.serverUrl(directory)
        params.isTlsEnabled = directory.security == LdapSecurity.START_TLS
        params.serverCertificatesVerificationMode = Ldap.CertVerificationMode.Default
        if (directory.bindDn.isEmpty()) {
            params.authMethod = Ldap.AuthMethod.Anonymous
            params.bindDn = ""
            params.password = ""
        } else {
            params.authMethod = Ldap.AuthMethod.Simple
            params.bindDn = directory.bindDn
            params.password = store.passwordOf(directory.id)
        }
        params.baseObject = directory.baseDn
        params.filter = LdapRules.coreFilter(if (byNumber) LdapRules.numberFilter(directory) else LdapRules.nameFilter(directory))
        params.nameAttribute = LdapRules.attributes(directory.nameAttributes).joinToString(",")
        params.sipAttribute = LdapRules.attributes(directory.numberAttributes).joinToString(",")
        params.sipDomain = ""
        params.maxResults = directory.maxResults
        params.minChars = 0
        params.timeout = TIMEOUT_SEC
        params.delay = serverDelayMs
        params.debugLevel = Ldap.DebugLevel.Off
        setServer(params)
    }

    private fun setServer(params: LdapParams) {
        val servers = core.ldapList
        servers.drop(1).forEach(core::removeLdap)
        val server = servers.firstOrNull()
        if (server != null) server.params = params else core.addLdap(core.createLdapWithParams(params))
    }

    /** Without a directory the SIP core has no server to ask (nor one to take for a directory of old at the next start). */
    private fun forgetServer() = core.ldapList.forEach(core::removeLdap)

    /**
     * One row per number of the entry (extension, mobile, ...). The address of a search result
     * does not always belong to the number it reports, so the call target is the number that
     * is shown; it is dialled like any other number.
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
        if (numbers.isNotEmpty()) return numbers.map { DirectoryContact(name ?: it, it, it, organization) }
        // No number: the entry only has a SIP address.
        val user = address?.username?.takeIf { it.isNotBlank() } ?: return emptyList()
        return listOf(DirectoryContact(name ?: user, user, address.asStringUriOnly(), organization))
    }

    private companion object {
        const val TAG = "SipLink.Directory"

        /** How long a server gets to answer, and what the SIP core is given on top to say that it did not. */
        const val TIMEOUT_SEC = 5
        const val GRACE_SEC = 3

        /** The screen already gathers the letters of a quick typist; the core need not wait again. */
        const val SERVER_DELAY_MS = 100
    }
}
