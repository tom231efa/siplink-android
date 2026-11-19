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
        if (seen != null && stun != null && seen.address == stun.address && seen.port != stun.port) return NatKind.SYMMETRIC
        if (local.any { it.equals(outside.address, ignoreCase = true) }) return NatKind.NONE
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

    /**
     * Which way the sound of a call goes, from the packets sent and received [elapsedMs] after
     * it started, and the loss the other side reports of what this phone sends (percent; null
     * while it sent no report). Sound both ways shows at once; sound missing only after
     * [SILENT_AFTER_MS], before that it is null.
     */
    fun flow(sent: Long, received: Long, remoteLoss: Float?, elapsedMs: Long): MediaFlow? {
        val out = sent > 0 && (remoteLoss == null || remoteLoss < ALL_LOST)
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
