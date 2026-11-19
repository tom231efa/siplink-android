package com.sky.siplink.core

import com.sky.siplink.core.logic.LdapRules
import com.sky.siplink.core.model.LdapDirectory
import com.sky.siplink.core.model.LdapSecurity
import org.linphone.core.Config

/**
 * The LDAP directories as they are kept: in the configuration file of the SIP core, one section
 * per directory, numbered in their order. The file is private to the app, like the credentials
 * of the SIP accounts in it.
 */
internal class LdapStore(private val config: Config) {
    /** The directories, without their passwords ([passwordOf]). */
    fun load(): List<LdapDirectory> = (0 until LdapRules.MAX_DIRECTORIES).mapNotNull { index ->
        val section = SECTION_PREFIX + index
        val id = text(section, KEY_ID, "")
        if (id.isEmpty()) return@mapNotNull null
        val defaults = LdapDirectory()
        LdapDirectory(
            id = id,
            name = text(section, KEY_NAME, ""),
            enabled = config.getBool(section, KEY_ENABLED, true),
            host = text(section, KEY_HOST, ""),
            port = config.getInt(section, KEY_PORT, defaults.port),
            security = LdapSecurity.entries.firstOrNull { it.name == text(section, KEY_SECURITY, "") } ?: LdapSecurity.NONE,
            bindDn = text(section, KEY_BIND_DN, ""),
            baseDn = text(section, KEY_BASE_DN, ""),
            line = text(section, KEY_LINE, ""),
            lookupIncoming = config.getBool(section, KEY_LOOKUP_INCOMING, true),
            lookupOutgoing = config.getBool(section, KEY_LOOKUP_OUTGOING, true),
            maxResults = config.getInt(section, KEY_MAX_RESULTS, defaults.maxResults),
            nameAttributes = text(section, KEY_NAME_ATTRIBUTES, defaults.nameAttributes),
            numberAttributes = text(section, KEY_NUMBER_ATTRIBUTES, defaults.numberAttributes),
            nameFilter = text(section, KEY_NAME_FILTER, ""),
            numberFilter = text(section, KEY_NUMBER_FILTER, ""),
        )
    }

    fun passwordOf(id: String): String {
        val section = sections().firstOrNull { text(it, KEY_ID, "") == id } ?: return ""
        return text(section, KEY_PASSWORD, "")
    }

    /**
     * Replaces what is stored by [directories], in that order.
     *
     * @param passwords by directory id; one that is missing keeps the password it has
     */
    fun save(directories: List<LdapDirectory>, passwords: Map<String, String> = emptyMap()) {
        val kept = directories.associate { it.id to (passwords[it.id] ?: passwordOf(it.id)) }
        sections().forEach(config::cleanSection)
        directories.forEachIndexed { index, directory ->
            val section = SECTION_PREFIX + index
            config.setString(section, KEY_ID, directory.id)
            config.setString(section, KEY_NAME, directory.name)
            config.setBool(section, KEY_ENABLED, directory.enabled)
            config.setString(section, KEY_HOST, directory.host)
            config.setInt(section, KEY_PORT, directory.port)
            config.setString(section, KEY_SECURITY, directory.security.name)
            config.setString(section, KEY_BIND_DN, directory.bindDn)
            config.setString(section, KEY_PASSWORD, kept[directory.id].orEmpty())
            config.setString(section, KEY_BASE_DN, directory.baseDn)
            config.setString(section, KEY_LINE, directory.line)
            config.setBool(section, KEY_LOOKUP_INCOMING, directory.lookupIncoming)
            config.setBool(section, KEY_LOOKUP_OUTGOING, directory.lookupOutgoing)
            config.setInt(section, KEY_MAX_RESULTS, directory.maxResults)
            config.setString(section, KEY_NAME_ATTRIBUTES, directory.nameAttributes)
            config.setString(section, KEY_NUMBER_ATTRIBUTES, directory.numberAttributes)
            config.setString(section, KEY_NAME_FILTER, directory.nameFilter)
            config.setString(section, KEY_NUMBER_FILTER, directory.numberFilter)
        }
        config.sync()
    }

    private fun sections(): List<String> =
        (0 until LdapRules.MAX_DIRECTORIES).map { SECTION_PREFIX + it }.filter { config.hasSection(it) != 0 }

    private fun text(section: String, key: String, default: String): String =
        if (config.hasEntry(section, key) != 0) config.getString(section, key, "").orEmpty() else default

    private companion object {
        const val SECTION_PREFIX = "siplink_ldap_"
        const val KEY_ID = "id"
        const val KEY_NAME = "name"
        const val KEY_ENABLED = "enabled"
        const val KEY_HOST = "host"
        const val KEY_PORT = "port"
        const val KEY_SECURITY = "security"
        const val KEY_BIND_DN = "bind_dn"
        const val KEY_PASSWORD = "password"
        const val KEY_BASE_DN = "base_dn"
        const val KEY_MAX_RESULTS = "max_results"
        const val KEY_NAME_ATTRIBUTES = "name_attributes"
        const val KEY_NUMBER_ATTRIBUTES = "number_attributes"
        const val KEY_NAME_FILTER = "name_filter"
        const val KEY_NUMBER_FILTER = "number_filter"
        const val KEY_LINE = "line"
        const val KEY_LOOKUP_INCOMING = "lookup_incoming"
        const val KEY_LOOKUP_OUTGOING = "lookup_outgoing"
    }
}
