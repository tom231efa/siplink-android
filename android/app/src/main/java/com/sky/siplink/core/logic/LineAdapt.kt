package com.sky.siplink.core.logic

import com.sky.siplink.core.model.SipTransport

/**
 * Rules of the automatic adaptation of a line (an account on one network). Nothing changes on
 * a line that works: an adjustment is tried only after a failure that points at the network,
 * kept only once it worked, remembered for that account on that network, and given up again
 * when a later test finds the line fine without it.
 *
 * The steps: another transport when UDP does not get through (UDP → TCP → TLS, TLS only where
 * the existing trust settings accept the server, which the real registration finds out), and a
 * shorter registration interval when the NAT is seen to forget the line between registrations.
 */
object LineAdapt {
    /**
     * What is remembered for one account on one network.
     *
     * @param transport the transport used instead of the account's own; null: its own
     * @param expires the registration interval used instead of the account's own; null: its own
     * @param failed transports that were tried here and did not register
     * @param retestAt when to look again whether the account's own settings work (ms since the epoch)
     * @param reverts how often the line went back to its own transport and that did not hold
     * @param lost when the NAT was seen to forget the line (ms since the epoch), the last day's worth
     * @param usedAt when the phone was last on this network with this account
     */
    data class Record(
        val transport: SipTransport? = null,
        val expires: Int? = null,
        val failed: Set<SipTransport> = emptySet(),
        val retestAt: Long = 0,
        val reverts: Int = 0,
        val lost: List<Long> = emptyList(),
        val usedAt: Long = 0,
    ) {
        /** Nothing in effect and nothing to remember. */
        val isPlain: Boolean get() = transport == null && expires == null && failed.isEmpty() && lost.isEmpty() && reverts == 0
    }

    private const val SEPARATOR = '|'

    fun encode(record: Record): String = listOf(
        record.transport?.name.orEmpty(),
        record.expires?.toString().orEmpty(),
        record.failed.joinToString(",") { it.name },
        record.retestAt.toString(),
        record.reverts.toString(),
        record.lost.joinToString(","),
        record.usedAt.toString(),
    ).joinToString(SEPARATOR.toString())

    fun decode(text: String?): Record? {
        val fields = text?.split(SEPARATOR) ?: return null
        if (fields.size != 7) return null
        return Record(
            transport = SipTransport.entries.firstOrNull { it.name == fields[0] },
            expires = fields[1].toIntOrNull(),
            failed = fields[2].split(',').mapNotNullTo(HashSet()) { word -> SipTransport.entries.firstOrNull { it.name == word } },
            retestAt = fields[3].toLongOrNull() ?: 0,
            reverts = fields[4].toIntOrNull() ?: 0,
            lost = fields[5].split(',').mapNotNull(String::toLongOrNull),
            usedAt = fields[6].toLongOrNull() ?: 0,
        )
    }

    /**
     * Whether a registration that failed with SIP status [code] may be the network's doing: no
     * answer at all (the SIP core reports 408, or 503 when it could not connect). A server that
     * answered with anything else was reached.
     */
    fun isTransportFailure(code: Int): Boolean = code <= 0 || code == 408 || code == 503

    /** Failed registrations in a row after which the network is looked at: one alone can be a moment's loss. */
    const val FAILURES_BEFORE_PROBE = 2

    /**
     * What the probes found on the way to the server of an account.
     *
     * @param udp the server answered an OPTIONS request over UDP
     * @param udpLarge it answered one padded to [LARGE_PACKET] bytes; null: not asked
     * @param tcp a TCP connection to its SIP port was taken
     * @param tls a TCP connection to its TLS port was taken; null: TLS is out of the question there
     */
    data class Probe(val udp: Boolean, val udpLarge: Boolean?, val tcp: Boolean, val tls: Boolean?)

    /** The size of the padded request: past the 1300 bytes from which RFC 3261 §18.1.1 wants TCP. */
    const val LARGE_PACKET = 1400

    /**
     * The transport to try next on a line now on [current] whose own transport is [own], or null
     * when there is nothing to try. Only away from UDP, and only to what the probe found open.
     *
     * @param largeLost large UDP packets are lost while small ones get through: the reason for a
     *   failed call even though the registration works
     */
    fun nextTransport(own: SipTransport, current: SipTransport, failed: Set<SipTransport>, probe: Probe, largeLost: Boolean = false): SipTransport? {
        if (own != SipTransport.UDP) return null
        if (current == SipTransport.UDP && probe.udp && !largeLost) return null
        val order = listOf(SipTransport.TCP, SipTransport.TLS)
        val after = order.dropWhile { current != SipTransport.UDP && it != current }.drop(if (current == SipTransport.UDP) 0 else 1)
        return after.firstOrNull { candidate ->
            candidate !in failed && when (candidate) {
                SipTransport.TCP -> probe.tcp
                SipTransport.TLS -> probe.tls == true
                SipTransport.UDP -> false
            }
        }
    }

    /** Whether the line can go back to UDP: small and large requests get through again. */
    fun udpBack(probe: Probe): Boolean = probe.udp && probe.udpLarge != false

    private const val DAY_MS = 24 * 60 * 60_000L
    private const val MAX_RETEST_MS = 7 * DAY_MS

    /** When to test again after the line needed another transport [reverts] times after going back: a day, doubling up to a week. */
    fun retestDelay(reverts: Int): Long = (DAY_MS shl reverts.coerceIn(0, 3)).coerceAtMost(MAX_RETEST_MS)

    /** The registration interval used while the NAT forgets the line ([bindingLost]). */
    const val SHORT_EXPIRES = 300

    /** Times the NAT has to be seen forgetting the line within a day before the interval is shortened. */
    const val LOSSES_TO_ACT = 2

    /**
     * The record after the NAT was seen to forget the line at [now]: the public address of the
     * registration changed while the phone stayed on the same network. With [LOSSES_TO_ACT] of
     * these within a day the registration interval is shortened, so that the server learns the
     * new address sooner; an account that registers this often or more already keeps its own.
     */
    fun bindingLost(record: Record, ownExpires: Int, now: Long): Record {
        val lost = (record.lost + now).filter { now - it < DAY_MS }.takeLast(LOSSES_TO_ACT * 2)
        val shorten = lost.size >= LOSSES_TO_ACT && ownExpires > SHORT_EXPIRES
        return record.copy(lost = lost, expires = if (shorten) SHORT_EXPIRES else record.expires, retestAt = if (shorten) now + DAY_MS else record.retestAt)
    }

    /** The record after a day without a loss: the account's own interval again. */
    fun bindingSteady(record: Record, now: Long): Record =
        if (record.expires != null && record.lost.all { now - it >= DAY_MS }) record.copy(expires = null, lost = emptyList()) else record

    /** Records that are not used for this long are forgotten. */
    const val KEEP_MS = 30 * DAY_MS

    /** Networks remembered at most; the ones used longest ago go first. */
    const val MAX_NETWORKS = 20

    /**
     * The keys (`account|network`) of [records] to forget at [now]: those unused for
     * [KEEP_MS], and those of all but the [MAX_NETWORKS] networks used most recently.
     */
    fun stale(records: Map<String, Record>, now: Long): Set<String> {
        val old = records.filterValues { now - it.usedAt > KEEP_MS }.keys
        val networks = records.filterKeys { it !in old }.entries
            .groupBy({ networkOf(it.key) }, { it.value.usedAt })
            .mapValues { (_, used) -> used.max() }
            .entries.sortedByDescending { it.value }
            .drop(MAX_NETWORKS).map { it.key }.toSet()
        return old + records.keys.filter { networkOf(it) in networks }
    }

    fun key(account: String, network: String): String = "$account$SEPARATOR$network"

    fun accountOf(key: String): String = key.substringBeforeLast(SEPARATOR)

    fun networkOf(key: String): String = key.substringAfterLast(SEPARATOR)

    /** What the account list shows next to a line that runs on another transport than its own. */
    fun label(transport: SipTransport): String = "${transport.name}（自动）"
}
