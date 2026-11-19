package com.sky.siplink.core.logic

import com.sky.siplink.core.model.LdapDirectory
import com.sky.siplink.core.model.Settings

/**
 * How a remote configuration becomes the phone's: which local ids its accounts and directories
 * get, which codecs go where, and what of the settings stays the user's. The managers do the rest.
 */
object RemotePlan {
    /** The handed-out [remote] settings, with the user's own ones taken from [current] ([ManagedConfig.localUpdate]). */
    fun settings(remote: Settings, current: Settings): Settings = ManagedConfig.localUpdate(remote, current)

    /** What is left of the settings when a remote configuration is gone: the defaults, and the user's own ones. */
    fun wipedSettings(current: Settings): Settings = settings(Settings(), current)

    /**
     * The local id of every element of a remote configuration, by its key: the id it had at the
     * last sync where that still exists, a new one ([newId]) otherwise.
     *
     * @param known key to id, as kept from the last sync
     * @param existing the ids that exist on the phone
     */
    fun ids(keys: List<String>, known: Map<String, String>, existing: Set<String>, newId: () -> String): Map<String, String> =
        keys.associateWith { key -> known[key]?.takeIf { it in existing } ?: newId() }

    /**
     * The directories of a remote configuration as the phone keeps them: with their local ids,
     * and their lines as local account ids. A line naming an account the phone has not got (the
     * platform leaves out accounts without credentials) makes the directory one for every line.
     */
    fun directories(remote: List<RemoteDirectory>, ids: Map<String, String>, accountIds: Map<String, String>): List<LdapDirectory> =
        remote.map { it.directory.copy(id = ids.getValue(it.key), line = accountIds[it.lineKey].orEmpty(), password = "") }

    /** The passwords of [remote] by the local ids of [ids]. */
    fun directoryPasswords(remote: List<RemoteDirectory>, ids: Map<String, String>): Map<String, String> =
        remote.associate { ids.getValue(it.key) to it.directory.password }

    /**
     * The codecs of one kind in their new order, each with whether it is on: the ones [wanted]
     * names first, in its order and as it says; then the others of [current] in their order,
     * switched off. What [wanted] names and the phone has not got is left out.
     *
     * @param current the keys of the phone's codecs, in their order
     */
    fun codecOrder(current: List<String>, wanted: List<CodecChoice>): List<CodecChoice> {
        val present = current.toSet()
        val named = wanted.filter { it.key in present }.distinctBy { it.key }
        val namedKeys = named.mapTo(HashSet()) { it.key }
        return named + current.filter { it !in namedKeys }.map { CodecChoice(it, false) }
    }
}
