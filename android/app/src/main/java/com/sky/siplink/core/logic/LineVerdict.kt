package com.sky.siplink.core.logic

import com.sky.siplink.core.model.MediaFlow
import com.sky.siplink.core.model.Mapped
import com.sky.siplink.core.model.NatKind
import com.sky.siplink.core.model.OptionsAnswer

/** What the answers of the line checks mean. */
object LineVerdict {
    /**
     * What lies between the phone (addresses [local]) and the servers.
     *
     * @param seen where the SIP server saw the probe come from (its Via), null without an answer
     * @param stun where a STUN server saw the same socket, null without one; with both from one
     *   socket, different ports mean a NAT that maps each destination on its own (symmetric)
     */
    fun nat(local: List<String>, seen: Mapped?, stun: Mapped? = null): NatKind {
        val outside = seen ?: stun ?: return NatKind.UNKNOWN
        if (seen != null && stun != null && NetAddress.same(seen.address, stun.address) && seen.port != stun.port) return NatKind.SYMMETRIC
        if (local.any { NetAddress.same(it, outside.address) }) return NatKind.NONE
        return if (local.any { NetAddress.kind(it) == NetAddress.Kind.SHARED }) NatKind.CARRIER else NatKind.PRESENT
    }

    /** Where the SIP server saw the probe come from: the Via `received` and `rport` of its answer. */
    fun seen(answer: OptionsAnswer?, sentFrom: Mapped): Mapped? {
        if (answer == null) return null
        return Mapped(answer.received ?: sentFrom.address, answer.rport ?: sentFrom.port)
    }

    /**
     * Whether a SIP ALG on the way is likely, from the answer to a probe that asked for `rport`:
     * a server adds `received` to such a request whatever the address (RFC 3581 §4), so a Via
     * that came back changed or without it, or a body whose length no longer matches its header,
     * was rewritten on the way. Null without an answer. A clean answer does not rule an ALG out:
     * one that rewrites and restores leaves no trace the phone can see.
     */
    fun algSuspected(answer: OptionsAnswer?): Boolean? {
        if (answer == null) return null
        return !answer.sentByIntact || answer.received == null || !answer.lengthIntact
    }

    /** No packet arrived for this long after the sound started: the call has none in that direction. */
    const val SILENT_AFTER_MS = 5_000L

    /** What the other side's RTCP reports said of the sound this phone sends. */
    enum class FarReports {
        /** None came: nothing is known of it. */
        NONE,

        /** Reports came, and none was about this phone's stream: none of its packets arrived. */
        SILENT,

        /** At least one was about this phone's stream: its packets arrived. */
        ABOUT_US,
    }

    /**
     * Which way the sound of a call goes, from the packets sent and received [elapsedMs] after
     * it started, what the other side's reports said ([far]), and the loss the last of them
     * reported of what this phone sends (percent; 0 without one, as the SIP core gives it). Sound
     * both ways shows at once; sound missing only after [SILENT_AFTER_MS], before that it is null.
     * Without reports from the other side, what this phone sends counts as arriving.
     */
    fun flow(sent: Long, received: Long, far: FarReports, remoteLoss: Float, elapsedMs: Long): MediaFlow? {
        val out = sent > 0 && far != FarReports.SILENT && remoteLoss < ALL_LOST
        val into = received > 0
        if (out && into) return MediaFlow.BOTH
        if (elapsedMs < SILENT_AFTER_MS) return null
        return when {
            out -> MediaFlow.SEND_ONLY
            into -> MediaFlow.RECEIVE_ONLY
            else -> MediaFlow.NONE
        }
    }

    private const val ALL_LOST = 99f
}
