package com.sky.siplink.core

import android.os.Handler
import android.os.Looper
import com.sky.siplink.core.CallSupport.isEnded
import com.sky.siplink.core.CallSupport.phaseOf
import com.sky.siplink.core.logic.DialTarget
import com.sky.siplink.core.logic.FeatureDial
import com.sky.siplink.core.model.CallPhase
import com.sky.siplink.core.model.TransferStatus
import org.linphone.core.Call
import org.linphone.core.Core

/**
 * Transfer and parking for [CallManager]: blind and attended transfer, parking (a blind transfer
 * to the parking extension of the PBX), and how the calls end once the PBX has taken them.
 * Main thread only, like the SIP core.
 *
 * @param onChanged the state of a transfer changed
 */
internal class CallTransfer(
    private val core: Core,
    private val settings: SettingsStore,
    private val onChanged: () -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var attended = false
    private var parking = false

    var status: TransferStatus? = null
        private set

    /** The call was parked, and the PBX is announcing the parking slot on it. */
    val parked: Boolean get() = parking && status == TransferStatus.SUCCEEDED

    /** A transfer is under way or done: the call it moves is not to be taken off hold. */
    val moving: Boolean get() = status == TransferStatus.IN_PROGRESS || status == TransferStatus.SUCCEEDED

    /** Blind transfer: the PBX connects the other party of [call] to [target] and releases us. */
    fun blind(call: Call?, target: String): String? {
        call ?: return "当前无通话"
        val phase = phaseOf(call)
        if (phase != CallPhase.CONNECTED && phase != CallPhase.HELD) return "通话接通后方可转接"
        val cleaned = DialTarget.of(target)
        if (cleaned.isEmpty()) return "请输入转接号码"
        val address = (call.params.account ?: core.defaultAccount)?.normalizeSipUri(cleaned)
            ?: return "号码或地址无效"
        attended = false
        parking = false
        return request { call.transferTo(address) }
    }

    /** Parks [call]: a blind transfer to the parking extension of the PBX. */
    fun park(call: Call?): String? {
        val extension = settings.current.features.park.trim()
        if (extension.isEmpty()) return FeatureDial.missingCode("驻留")
        val error = blind(call, extension)
        parking = error == null
        return error
    }

    /** Second step of an attended transfer: connects the [held] party with the [consulted] one. */
    fun complete(consulted: Call, held: Call): String? {
        if (phaseOf(consulted) != CallPhase.CONNECTED) return "对方接听后方可转接"
        attended = true
        return request { held.transferToAnother(consulted) }
    }

    /**
     * The PBX reports how the transfer of [call] goes.
     *
     * @param both the two calls of an attended transfer, which end once it succeeded
     */
    fun onStateChanged(call: Call, state: Call.State?, both: List<Call>) {
        when (state) {
            Call.State.OutgoingInit, Call.State.OutgoingProgress,
            Call.State.OutgoingRinging, Call.State.OutgoingEarlyMedia,
            -> status = TransferStatus.IN_PROGRESS

            Call.State.Connected, Call.State.StreamsRunning -> {
                status = TransferStatus.SUCCEEDED
                when {
                    attended -> both.forEach { it.terminate() }
                    // The PBX announces the parking slot on this call and then hangs it up itself;
                    // the timer only covers a PBX that does neither.
                    parking -> handler.postDelayed({ if (!isEnded(call)) call.terminate() }, PARK_ANNOUNCE_TIMEOUT_MS)
                    !isEnded(call) -> call.terminate()
                }
                attended = false
            }

            Call.State.Error, Call.State.End -> {
                status = TransferStatus.FAILED
                attended = false
            }

            else -> Unit
        }
        onChanged()
    }

    /** A completed transfer or parking explains the end of the call better than the SIP reason. */
    fun endReason(): String? = when {
        status != TransferStatus.SUCCEEDED -> null
        parking -> "通话已驻留"
        else -> "转接成功"
    }

    fun reset() {
        attended = false
        status = null
        parking = false
    }

    private fun request(send: () -> Int): String? {
        status = TransferStatus.IN_PROGRESS
        val sent = send() == 0
        if (!sent) {
            attended = false
            status = TransferStatus.FAILED
        }
        onChanged()
        return if (sent) null else "转接请求发送失败"
    }

    private companion object {
        const val PARK_ANNOUNCE_TIMEOUT_MS = 12_000L
    }
}
