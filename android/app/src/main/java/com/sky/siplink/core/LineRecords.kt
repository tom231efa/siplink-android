package com.sky.siplink.core

import com.sky.siplink.core.logic.LineAdapt
import com.sky.siplink.core.logic.LineSteps
import com.sky.siplink.core.logic.NetAddress
import org.linphone.core.Config

/**
 * What the adaptation of the lines keeps in the configuration of the SIP core: what was learned
 * for each account on each network ([LineAdapt.Record]), the account settings it replaced in the
 * core ([LineSteps.Own]), the next hop of a PBX that nothing reaches, and whether an account's
 * login registered anywhere. Main thread only.
 */
internal class LineRecords(private val config: Config) {
    fun record(id: String, network: String?): LineAdapt.Record? {
        val net = network ?: return null
        return LineAdapt.decode(config.getString(SECTION, LineAdapt.key(id, net), null))
    }

    fun store(id: String, network: String?, record: LineAdapt.Record) {
        val net = network ?: return
        val key = LineAdapt.key(id, net)
        if (record.isPlain) config.cleanEntry(SECTION, key) else config.setString(SECTION, key, LineAdapt.encode(record))
    }

    private fun keys(): List<String> = config.getKeysNamesList(SECTION).toList()

    /** Forgets what was learned for account [id], on every network. */
    fun forget(id: String) {
        keys().filter { LineAdapt.accountOf(it) == id }.forEach { config.cleanEntry(SECTION, it) }
    }

    /** Changes what was learned for account [id] on every network by [change]. */
    fun changeAll(id: String, change: (LineAdapt.Record) -> LineAdapt.Record) {
        for (key in keys().filter { LineAdapt.accountOf(it) == id }) {
            val record = LineAdapt.decode(config.getString(SECTION, key, null)) ?: continue
            val next = change(record)
            if (next.isPlain) config.cleanEntry(SECTION, key) else if (next != record) config.setString(SECTION, key, LineAdapt.encode(next))
        }
    }

    /** Forgets everything that was learned. */
    fun forgetAll() = config.cleanSection(SECTION)

    /** Forgets the networks not seen for long, and those beyond the most recent ones. */
    fun prune(now: Long) {
        val records = keys().associateWith { LineAdapt.decode(config.getString(SECTION, it, null)) ?: LineAdapt.Record() }
        LineAdapt.stale(records, now).forEach { config.cleanEntry(SECTION, it) }
    }

    /** The account's own settings where the doctor replaced some in the core; null where it did not. */
    fun own(id: String): LineSteps.Own? = LineSteps.decodeOwn(config.getString(APPLIED, id, null))

    fun storeOwn(id: String, own: LineSteps.Own?) {
        if (own == null) config.cleanEntry(APPLIED, id) else config.setString(APPLIED, id, LineSteps.encodeOwn(own))
    }

    /** The next hop that the PBX of account [id] named for its last call where nothing reaches it; null: none. */
    fun pbxHop(id: String): String? = config.getString(PBX, id, null)

    /** Keeps [hop] (an address literal, kept in its canonical form), or forgets it with null. */
    fun storePbxHop(id: String, hop: String?) {
        val canonical = hop?.let(NetAddress::parse)?.let(NetAddress::format)
        if (canonical == null) config.cleanEntry(PBX, id) else config.setString(PBX, id, canonical)
    }

    /**
     * Whether the login of account [id] (user, server, password) registered somewhere, on any
     * network and transport, since it last changed.
     */
    fun proven(id: String): Boolean = config.getString(PROVEN, id, null) == "1"

    fun storeProven(id: String, proven: Boolean) {
        if (proven) config.setString(PROVEN, id, "1") else config.cleanEntry(PROVEN, id)
    }

    private companion object {
        /** Section of the core configuration holding the [LineAdapt.Record]s, keyed by `account|network`. */
        const val SECTION = "siplink_line_adapt"

        /** Section holding the [LineSteps.Own] settings of the accounts the doctor changed, keyed by account id. */
        const val APPLIED = "siplink_line_applied"

        /** Section holding, by account id, the next hop of its PBX's last call where nothing reaches that. */
        const val PBX = "siplink_line_pbx"

        /** Section holding, by account id, "1" where the account's login registered ([proven]). */
        const val PROVEN = "siplink_line_proven"
    }
}
