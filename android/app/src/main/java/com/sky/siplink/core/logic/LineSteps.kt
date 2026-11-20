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
     * Whether a line is on test: just moved to a transport ([trial]), back on its own one for a
     * test after another one worked ([revertTo]), or found on another transport than its own
     * ([current], [own]) where it never registered ([LineAdapt.Record.registered]). A line that
     * registered on its transport is settled there, however long ago.
     */
    fun onTrial(trial: SipTransport?, revertTo: SipTransport?, current: SipTransport, own: SipTransport, record: Record?): Boolean =
        trial != null || revertTo != null || (current != own && record?.registered != true)

    /**
     * What a registration that failed with SIP status [code] leads to.
     *
     * @param holds the network wants a login first
     * @param onTrial the line is on test ([onTrial]); a refusal on a settled line is the account's
     *   business (a changed password, a PBX that reloads), not its transport's
     * @param proven the account's login registered before, anywhere, since it last changed. Only
     *   then is a challenge (401, 407) on test the transport's refusal (Asterisk answers its
     *   endpoint ACL with 401 too); else the login is wrong, and the line stays where it is, with
     *   that error for the user to see.
     */
    fun failure(code: Int, holds: Boolean, onTrial: Boolean, proven: Boolean): Failure = when {
        holds -> Failure.NOTHING
        LineAdapt.isTransportFailure(code) -> Failure.CONFIRM
        !onTrial -> Failure.NOTHING
        (code == UNAUTHORIZED || code == PROXY_AUTHENTICATION) && !proven -> Failure.NOTHING
        else -> Failure.END_TRIAL
    }

    private const val UNAUTHORIZED = 401
    private const val PROXY_AUTHENTICATION = 407

    /** What changes for a line: the record to store, and the transport it now runs on for a test (null: none). */
    data class Change(val record: Record, val trial: SipTransport?)

    /** A line back on its own transport for a test did not register: back to [revertTo], which worked, and a longer wait. */
    fun revertFailed(record: Record, revertTo: SipTransport, now: Long): Record {
        val reverts = record.reverts + 1
        return record.copy(
            transport = revertTo,
            registered = true,
            reverts = reverts,
            retestAt = now + LineAdapt.retestAfter(revertTo, reverts),
            backAt = 0,
            usedAt = now,
        )
    }

    /** The record once the line registered on [current]: settled there where it is the transport it moved to. */
    fun registeredOn(record: Record, current: SipTransport): Record =
        if (record.transport == current && !record.registered) record.copy(registered = true) else record

    /** The record after the account's credentials changed: what did not register here may now. */
    fun credentialsChanged(record: Record): Record = record.copy(failed = emptySet(), failedUntil = 0)

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
        // Everything closed says nothing about one transport: the server is down. Where UDP was
        // not asked, a closed transport on test sends the line back to its own one.
        if (!refused && probe.udp == false && !probe.tcp && probe.tls != true) return null
        // A transport that is open but did not register is not tried here again for a while.
        val open = refused || when (trial) {
            SipTransport.TCP -> probe.tcp
            SipTransport.TLS -> probe.tls == true
            else -> false
        }
        val known = LineAdapt.failedAt(record, now)
        val marked = trial != null && open
        val failed = if (marked) known + trial else known
        val failedUntil = if (marked) now + LineAdapt.retestDelay(record.reverts) else record.failedUntil.takeIf { known.isNotEmpty() } ?: 0
        val udpUnfit = probe.udp == true && probe.udpLarge == false
        val next = LineAdapt.nextTransport(own, current, failed, probe, udpUnfit)
        val base = record.copy(failed = failed, failedUntil = failedUntil, registered = false, usedAt = now)
        return when {
            next != null -> {
                val left = if (current != SipTransport.UDP) record.left else if (udpUnfit) Leave.LARGE_LOST else Leave.NO_ANSWER
                Change(base.copy(transport = next, left = left, retestAt = now + LineAdapt.retestAfter(next, record.reverts)), next)
            }
            trial != null -> Change(base.copy(transport = null, left = null, backAt = 0), null)
            else -> null
        }
    }

    /**
     * A line on UDP that registers, but not well enough ([why]), moves to the next transport the
     * probe found open; null when none is. One that leaves again before it held a day back on UDP
     * ([Record.backAt]) waits longer before it tries UDP the next time.
     */
    fun leaveUdp(record: Record, own: SipTransport, probe: Probe, why: Leave, now: Long): Change? {
        val next = LineAdapt.nextTransport(own, SipTransport.UDP, LineAdapt.failedAt(record, now), probe, udpUnfit = true) ?: return null
        val reverts = if (record.backAt > 0) record.reverts + 1 else record.reverts
        // Over TCP the line is not watched for losses: the interval of its own is enough there.
        val base = if (why == Leave.NAT_FORGETS) record.copy(expires = null, lost = emptyList()) else record
        val moved = base.copy(transport = next, registered = false, left = why, backAt = 0, reverts = reverts, retestAt = now + LineAdapt.retestAfter(next, reverts), usedAt = now)
        return Change(moved, next)
    }

    /** Whether a line on another transport is to look again whether its own one gets through: when it is time ([LineAdapt.retestAfter]), and between calls. */
    fun retestDue(record: Record, now: Long, calls: Int): Boolean = record.transport != null && now >= record.retestAt && calls == 0

    /**
     * After the probe of a retest: the record, and whether the line goes back to its own transport
     * for a test (UDP gets through again, between calls); otherwise another wait. A line that left
     * because the NAT forgot it cannot be judged by a probe: it goes back once UDP answers, at the
     * short interval it had there at once, and the losses that follow judge it ([held]).
     */
    fun afterRetest(record: Record, probe: Probe, calls: Int, now: Long): Pair<Record, Boolean> =
        if (LineAdapt.udpBack(probe) && calls == 0) {
            val forgets = record.left == Leave.NAT_FORGETS
            val back = record.copy(transport = null, registered = false, backAt = if (forgets) now else 0)
            (if (forgets) back.copy(expires = LineAdapt.SHORT_EXPIRES) else back) to true
        } else {
            record.copy(retestAt = now + LineAdapt.retestAfter(record.transport, record.reverts)) to false
        }

    /**
     * The record once the line registered on its own transport again after a test: what was
     * learned no longer applies, except after the NAT forgot it, which a registration does not
     * show: that waits for [held].
     */
    fun returned(record: Record): Record =
        if (record.left == Leave.NAT_FORGETS) record else record.copy(failed = emptySet(), failedUntil = 0, reverts = 0, left = null)

    /** A line back on UDP after the NAT forgot it held a day without a loss: that is forgotten. */
    fun held(record: Record, now: Long): Record =
        if (record.transport == null && record.backAt > 0 && now - record.backAt >= LineAdapt.DAY_MS && record.lost.none { it >= record.backAt }) {
            record.copy(backAt = 0, reverts = 0, left = null, failed = emptySet(), failedUntil = 0)
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
