package com.sky.siplink.core

import android.os.Handler
import android.os.Looper
import com.sky.siplink.core.logic.LdapRules
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

/**
 * LDAP directory: one server configuration stored in the SIP core, searched through the
 * core's MagicSearch. Must be used from the thread that owns the core (the main thread).
 */
class DirectoryManager(private val core: Core) {
    private val configState = MutableStateFlow<LdapConfig?>(null)

    /** Current LDAP server configuration (password is never exposed), null when none is set. */
    val config: StateFlow<LdapConfig?> = configState.asStateFlow()

    private val searchState = MutableStateFlow(DirectoryState())
    val state: StateFlow<DirectoryState> = searchState.asStateFlow()

    val isAvailable: Boolean get() = core.ldapAvailable()

    private val handler = Handler(Looper.getMainLooper())
    private var searchToken = 0

    private val magicSearch: MagicSearch = core.createMagicSearch().also {
        it.limitedSearch = false
        it.addListener(object : MagicSearchListenerStub() {
            override fun onSearchResultsReceived(magicSearch: MagicSearch) {
                searchToken++
                searchState.value = searchState.value.copy(
                    searching = false,
                    contacts = magicSearch.lastSearch.mapNotNull(::toContact).distinct(),
                )
            }

            override fun onResultsLimitReached(magicSearch: MagicSearch, sourcesFlag: Int) {
                searchState.value = searchState.value.copy(limitReached = true)
            }
        })
    }

    init {
        reload()
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
        reload()
        return emptyList()
    }

    fun remove() {
        core.ldapList.forEach(core::removeLdap)
        magicSearch.resetSearchCache()
        searchState.value = DirectoryState()
        reload()
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
        searchState.value = searchState.value.copy(query = text, searching = true, limitReached = false)
        magicSearch.resetSearchCache()
        magicSearch.getContactsListAsync(
            text,
            "",
            MagicSearch.Source.LdapServers.toInt(),
            MagicSearch.Aggregation.None,
        )
        // Safety net: never leave the spinner on if the server does not answer.
        handler.postDelayed({
            if (token == searchToken) searchState.value = searchState.value.copy(searching = false)
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

    private fun toContact(result: SearchResult): DirectoryContact? {
        val address = result.address
        val friend = result.friend
        val number = result.phoneNumber?.takeIf { it.isNotBlank() } ?: address?.username ?: return null
        val name = friend?.name?.takeIf { it.isNotBlank() }
            ?: address?.displayName?.takeIf { it.isNotBlank() }
            ?: number
        return DirectoryContact(
            name = name,
            number = number,
            callTarget = address?.asStringUriOnly() ?: number,
            organization = friend?.organization.orEmpty(),
        )
    }
}
