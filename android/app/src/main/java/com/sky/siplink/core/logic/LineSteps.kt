package com.sky.siplink.core.logic

import com.sky.siplink.core.logic.LineAdapt.Leave
import com.sky.siplink.core.logic.LineAdapt.Probe
import com.sky.siplink.core.logic.LineAdapt.Record
import com.sky.siplink.core.model.SipTransport

/**
 * The steps LineDoctor takes on one line ([LineAdapt]), without the SIP core: what a failed
 * registration leads to, what the findings of a probe change, when a line on another transport
 * tests its own one again, and what a line runs on.
 */
object LineSteps {
    /** The settings of an account that the doctor replaced in the core. */
    data class Own(val transport: SipTransport, val expires: Int, val outbound: Boolean)

    private const val SEPARATOR = "|"

    fun encodeOwn(own: Own): String = listOf(own.transport.name, own.expires, if (own.outbound) "1" else "0").joinToString(SEPARATOR)

    fun decodeOwn(text: String?): Own? {
        val fields = text?.split(SEPARATOR) ?: return null
        if (fields.size != 3) return null
        val transport = SipTransport.entries.firstOrNull { it.name == fields[0] } ?: return null
        return Own(transport, fields[1].toIntOrNull() ?: return null, fields[2] == "1")
    }

    /** What a failed registration leads to. */
    enum class Failure {
        /** Taken as it comes: the network wants a login first, or the server refused the account on its own transport. */
        NOTHING,

        /** Looked at again after [LineAdapt.CONFIRM_MS]: no answer may be a moment's loss. */
        CONFIRM,

        /** The server answered and refused the line on a transport it runs on for a test: the test ends at once. */
        END_TRIAL,
    }

    /**
     * What a registration that failed with SIP status [code] leads to.
     *
     * @param holds the network wants a login first
     * @param onTrial the line runs on another transport than its own, or on its own one for a test
     *   after another one worked
     */
    fun failure(code: Int, holds: Boolean, onTrial: Boolean): Failure = when {
        holds -> Failure.NOTHING
        LineAdapt.isTransportFailure(code) -> Failure.CONFIRM
        onTrial -> Failure.END_TRIAL
        else -> Failure.NOTHING
    }

    /** What changes for a line: the record to store, and the transport it now runs on for a test (null: none). */
    data class Change(val record: Record, val trial: SipTransport?)

    /** A line back on its own transport for a test did not register: back to [revertTo], which worked, and a longer wait. */
    fun revertFailed(record: Record, revertTo: SipTransport, now: Long): Record {
        val reverts = record.reverts + 1
        return record.copy(transport = revertTo, reverts = reverts, retestAt = now + LineAdapt.retestDelay(reverts), backAt = 0, usedAt = now)
    }

    /**
     * A line still fails after the probe found [probe]: the next transport the probe finds open,
     * or its own one when the transport on test failed and none is left. Null when nothing
     * changes: its own transport is not UDP, the server is down, or nothing is left to try.
     *
     * @param current the transport it runs on; [trial] the one on test (null: its own)
     * @param refused the server answered on [trial] and refused the line there: open, and no good
     */
    fun afterFailure(record: Record, own: SipTransport, current: SipTransport, trial: SipTransport?, probe: Probe, refused: Boolean, now: Long): Change? {
        if (own != SipTransport.UDP) return null
        // Everything closed says nothing about one transport: the server is down.
        if (!refused && probe.udp != true && !probe.tcp && probe.tls != true) return null
        // A transport that is open but did not register is not tried here again.
        val open = refused || when (trial) {
            SipTransport.TCP -> probe.tcp
            SipTransport.TLS -> probe.tls == true
            else -> false
        }
        val failed = if (trial != null && open) record.failed + trial else record.failed
        val udpUnfit = probe.udp == true && probe.udpLarge == false
        val next = LineAdapt.nextTransport(own, current, failed, probe, udpUnfit)
        return when {
            next != null -> {
                val left = if (current != SipTransport.UDP) record.left else if (udpUnfit) Leave.LARGE_LOST else Leave.NO_ANSWER
                Change(record.copy(transport = next, failed = failed, left = left, retestAt = now + LineAdapt.retestDelay(record.reverts), usedAt = now), next)
            }
            trial != null -> Change(record.copy(transport = null, failed = failed, left = null, backAt = 0, usedAt = now), null)
            else -> null
        }
    }

    /**
     * A line on UDP that registers, but not well enough ([why]), moves to the next transport the
     * probe found open; null when none is. One that leaves again before it held a day back on UDP
     * ([Record.backAt]) waits longer before it tries UDP the next time.
     */
    fun leaveUdp(record: Record, own: SipTransport, probe: Probe, why: Leave, now: Long): Change? {
        val next = LineAdapt.nextTransport(own, SipTransport.UDP, record.failed, probe, udpUnfit = true) ?: return null
        val reverts = if (record.backAt > 0) record.reverts + 1 else record.reverts
        // Over TCP the line is not watched for losses: the interval of its own is enough there.
        val base = if (why == Leave.NAT_FORGETS) record.copy(expires = null, lost = emptyList()) else record
        return Change(base.copy(transport = next, left = why, backAt = 0, reverts = reverts, retestAt = now + LineAdapt.retestDelay(reverts), usedAt = now), next)
    }

    /**
     * Whether a line on another transport is to look again whether its own one gets through: when
     * it is time, and between calls. Not from TLS: the probe would send a request in clear, which
     * a line on TLS does not do. It stays on TLS on that network while TLS works.
     */
    fun retestDue(record: Record, now: Long, calls: Int): Boolean =
        record.transport != null && record.transport != SipTransport.TLS && now >= record.retestAt && calls == 0

    /**
     * After the probe of a retest: the record, and whether the line goes back to its own transport
     * for a test (UDP gets through again, between calls); otherwise another wait. A line that left
     * because the NAT forgot it cannot be judged by a probe: it goes back once UDP answers, and the
     * losses that follow judge it ([held]).
     */
    fun afterRetest(record: Record, probe: Probe, calls: Int, now: Long): Pair<Record, Boolean> =
        if (LineAdapt.udpBack(probe) && calls == 0) {
            record.copy(transport = null, backAt = if (record.left == Leave.NAT_FORGETS) now else 0) to true
        } else {
            record.copy(retestAt = now + LineAdapt.retestDelay(record.reverts)) to false
        }

    /**
     * The record once the line registered on its own transport again after a test: what was
     * learned no longer applies, except after the NAT forgot it, which a registration does not
     * show: that waits for [held].
     */
    fun returned(record: Record): Record =
        if (record.left == Leave.NAT_FORGETS) record else record.copy(failed = emptySet(), reverts = 0, left = null)

    /** A line back on UDP after the NAT forgot it held a day without a loss: that is forgotten. */
    fun held(record: Record, now: Long): Record =
        if (record.transport == null && record.backAt > 0 && now - record.backAt >= LineAdapt.DAY_MS && record.lost.none { it >= record.backAt }) {
            record.copy(backAt = 0, reverts = 0, left = null, failed = emptySet())
        } else {
            record
        }

    /** A server is asked again for one account after this long at the earliest: PBXs block who asks too often. */
    const val MIN_PROBE_GAP_MS = 30_000L

    /** How long a probe asked for at [now] waits after the last one at [probedAt] (null: none yet); 0: none. */
    fun probeWait(probedAt: Long?, now: Long): Long = if (probedAt == null) 0 else (probedAt + MIN_PROBE_GAP_MS - now).coerceAtLeast(0)

    /**
     * What a line runs on.
     *
     * @param ice the STUN server its ICE asks; null: no ICE
     */
    data class Target(val transport: SipTransport, val expires: Int, val outbound: Boolean, val ice: String?) {
        /** Whether anything differs from the account's [own] settings. */
        fun adapted(own: Own): Boolean = transport != own.transport || expires != own.expires || outbound != own.outbound || ice != null
    }

    /**
     * What the line of an account with [own] settings runs on, with what is remembered for it
     * ([record]; null while the adaptation is off) and the STUN server setting [stunServer].
     */
    fun target(own: Own, record: Record?, stunServer: String): Target {
        val transport = record?.transport?.takeIf { own.transport == SipTransport.UDP } ?: own.transport
        val expires = record?.expires?.takeIf { it < own.expires } ?: own.expires
        // Calls take the way of the registration, which the domain of the account may not lead to.
        val outbound = transport != own.transport || own.outbound
        val stun = stunServer.trim().takeIf { MediaAdapt.stunServer(it) != null }
        val ice = stun?.takeIf { record?.ice == MediaAdapt.Ice.TRIAL || record?.ice == MediaAdapt.Ice.ON }
        return Target(transport, expires, outbound, ice)
    }
}
