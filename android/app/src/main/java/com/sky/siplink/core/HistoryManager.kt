package com.sky.siplink.core

import com.sky.siplink.core.logic.PartyName
import com.sky.siplink.core.model.FeatureCodes
import com.sky.siplink.core.model.HistoryEntry
import com.sky.siplink.core.model.HistoryKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.linphone.core.Call
import org.linphone.core.CallLog
import org.linphone.core.Core

/**
 * Call history backed by the SIP core's own call log storage.
 *
 * The log is read in full once; afterwards single entries are added, changed or removed, so
 * that a finished call does not cost a pass over the whole history. A number that is in the
 * directory is shown by its name there.
 */
class HistoryManager(
    private val core: Core,
    private val names: NameResolver,
    private val texts: AppTexts,
    private val codes: () -> FeatureCodes,
) {
    private val state = MutableStateFlow(load())
    val entries: StateFlow<List<HistoryEntry>> = state.asStateFlow()

    init {
        names.addListener(::rename)
    }

    /**
     * Asks the local copy of the directory for the names of the numbers in the history. Called
     * when the history is looked at, so that starting the app does not wait for the directory.
     */
    fun resolveNames() = names.preload(state.value.map { it.address })

    /** Names were found: entries of those numbers are called by them from now on. */
    private fun rename() {
        val renamed = state.value.map { entry -> names.known(entry.address)?.takeIf { it != entry.name }?.let { entry.copy(name = it) } ?: entry }
        if (renamed != state.value) state.value = renamed
    }

    /** The language of the app changed: the entries named after a feature code are named in it. */
    fun onLanguageChanged() {
        state.value = load()
    }

    /** A call log was written or changed by the core: put it where it belongs, newest first. */
    fun onLogUpdated(log: CallLog) {
        CoreThread.check("The call history")
        val entry = toEntry(log)
        val list = state.value.filterTo(ArrayList(state.value.size + 1)) { it.id != entry.id }
        val position = list.indexOfFirst { it.startedAtMs <= entry.startedAtMs }
        list.add(if (position < 0) list.size else position, entry)
        state.value = if (list.size > MAX_ENTRIES) list.subList(0, MAX_ENTRIES).toList() else list
    }

    fun remove(id: String) {
        // By its Call-ID, which the core looks up by itself; an entry without one by going through the log.
        val log = core.findCallLogFromCallId(id)?.takeIf { idOf(it) == id } ?: core.callLogs.firstOrNull { idOf(it) == id }
        log?.let(core::removeCallLog)
        state.value = state.value.filterNot { it.id == id }
    }

    fun clear() {
        core.clearCallLogs()
        state.value = emptyList()
    }

    /**
     * The newest [MAX_ENTRIES] calls, newest first. The logs are put in order by their start
     * alone, so that only those shown are read in full. (The core's own limit, max_call_logs, is a
     * setting of the core that goes by the order in which the logs were written, not by their start.)
     */
    private fun load(): List<HistoryEntry> =
        core.callLogs.map { it.startDate to it }.sortedByDescending { it.first }.take(MAX_ENTRIES).map { toEntry(it.second) }

    fun isMissed(log: CallLog): Boolean = log.dir == Call.Dir.Incoming && log.status == Call.Status.Missed

    private fun idOf(log: CallLog): String =
        log.callId?.takeIf { it.isNotEmpty() } ?: "${log.startDate}|${log.remoteAddress.asStringUriOnly()}"

    private fun toEntry(log: CallLog): HistoryEntry {
        val party = CallSupport.side(log.remoteAddress)
        val local = log.localAddress
        val kind = when {
            log.dir == Call.Dir.Incoming && log.status == Call.Status.Missed -> HistoryKind.MISSED
            log.dir == Call.Dir.Incoming -> HistoryKind.INCOMING
            log.status == Call.Status.Success -> HistoryKind.OUTGOING
            else -> HistoryKind.FAILED
        }
        return HistoryEntry(
            id = idOf(log),
            name = texts.render(PartyName.of(party.name, party.address, log.dir == Call.Dir.Outgoing, codes(), names::known)),
            address = party.address,
            kind = kind,
            startedAtMs = log.startDate * 1000L,
            durationSec = log.duration,
            accountLabel = "${local.username.orEmpty()}@${local.domain.orEmpty()}",
        )
    }

    private companion object {
        /** The newest calls shown; older ones stay in the core's storage. */
        const val MAX_ENTRIES = 500
    }
}
