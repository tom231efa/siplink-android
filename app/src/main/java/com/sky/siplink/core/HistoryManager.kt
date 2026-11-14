package com.sky.siplink.core

import com.sky.siplink.core.logic.Formatters
import com.sky.siplink.core.model.HistoryEntry
import com.sky.siplink.core.model.HistoryKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.linphone.core.Call
import org.linphone.core.CallLog
import org.linphone.core.Core

/** Call history backed by the SIP core's own call log storage. */
class HistoryManager(private val core: Core) {
    private val state = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val entries: StateFlow<List<HistoryEntry>> = state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        state.value = core.callLogs.map(::toEntry).sortedByDescending { it.startedAtMs }
    }

    fun remove(id: String) {
        core.callLogs.firstOrNull { idOf(it) == id }?.let(core::removeCallLog)
        refresh()
    }

    fun clear() {
        core.clearCallLogs()
        refresh()
    }

    fun isMissed(log: CallLog): Boolean = log.dir == Call.Dir.Incoming && log.status == Call.Status.Missed

    private fun idOf(log: CallLog): String =
        log.callId?.takeIf { it.isNotEmpty() } ?: "${log.startDate}|${log.remoteAddress.asStringUriOnly()}"

    private fun toEntry(log: CallLog): HistoryEntry {
        val remote = log.remoteAddress
        val address = Formatters.shortAddress(remote.asStringUriOnly())
        val local = log.localAddress
        val kind = when {
            log.dir == Call.Dir.Incoming && log.status == Call.Status.Missed -> HistoryKind.MISSED
            log.dir == Call.Dir.Incoming -> HistoryKind.INCOMING
            log.status == Call.Status.Success -> HistoryKind.OUTGOING
            else -> HistoryKind.FAILED
        }
        return HistoryEntry(
            id = idOf(log),
            name = remote.displayName?.takeIf { it.isNotBlank() } ?: remote.username ?: address,
            address = address,
            kind = kind,
            startedAtMs = log.startDate * 1000L,
            durationSec = log.duration,
            accountLabel = "${local.username.orEmpty()}@${local.domain.orEmpty()}",
        )
    }
}
