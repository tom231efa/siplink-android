package com.sky.siplink.ui.accounts

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sky.siplink.R
import com.sky.siplink.core.CallSupport
import com.sky.siplink.core.LineCheck
import com.sky.siplink.core.LineNetwork
import com.sky.siplink.core.logic.LineAdapt
import com.sky.siplink.core.model.LineReport
import com.sky.siplink.core.model.MediaFlow
import com.sky.siplink.core.model.NatKind
import com.sky.siplink.core.model.NetType
import com.sky.siplink.core.model.ServerReport
import com.sky.siplink.core.model.SipTransport
import com.sky.siplink.engine
import com.sky.siplink.ui.components.FadingText
import com.sky.siplink.ui.components.LocalMessenger
import com.sky.siplink.ui.components.Personal
import com.sky.siplink.ui.components.SectionCard
import com.sky.siplink.ui.components.SubScreen
import com.sky.siplink.ui.components.endAlign
import com.sky.siplink.ui.components.pluralOf
import com.sky.siplink.ui.components.regStateLabel
import com.sky.siplink.ui.components.stringOf
import com.sky.siplink.ui.components.text
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The line checks of one account: values only. It changes nothing but what the line adapted
 * to, and that only on request; open under a managed configuration as well, which it does not touch.
 */
@Composable
fun LineDiagnosisScreen(accountId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val engine = context.engine
    val messenger = LocalMessenger.current
    val check = remember { LineCheck(context, engine.core, engine.accounts) }
    DisposableEffect(check) { onDispose { check.close() } }
    var report by remember { mutableStateOf<LineReport?>(null) }
    var busy by remember { mutableStateOf(false) }
    var round by remember { mutableIntStateOf(0) }
    var confirmReset by remember { mutableStateOf(false) }
    val resetDone = stringResource(R.string.diagnosis_reset_done)

    LaunchedEffect(round) {
        busy = check.run(accountId) {
            report = it
            busy = false
        }
        if (!busy) onBack()
    }

    Personal {
        SubScreen(
            title = stringResource(R.string.diagnosis_title),
            onBack = onBack,
            actions = { TextButton(onClick = { round++ }, enabled = !busy) { Text(stringResource(R.string.diagnosis_rerun)) } },
        ) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            report?.let { shown ->
                NetworkCard(shown, onBattery = {
                    runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                })
                ServerCard(shown)
                shown.servers?.let { ServersCard(it) }
                RegistrationCard(shown)
                shown.lastCall?.let { LastCallCard(shown) }
                if (shown.adaptedTransport != null || shown.adaptedExpires != null || shown.adaptedIce) {
                    AdaptationCard(shown, onReset = { confirmReset = true })
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(R.string.diagnosis_reset_title)) },
            text = { Text(stringResource(R.string.diagnosis_reset_text)) },
            confirmButton = {
                TextButton(onClick = {
                    engine.accounts.resetAdaptation(accountId)
                    confirmReset = false
                    messenger(resetDone)
                    round++
                }) { Text(stringResource(R.string.diagnosis_reset_confirm)) }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

private const val NONE = "—"

private val CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss")

@Composable
private fun NetworkCard(report: LineReport, onBattery: () -> Unit) {
    val network = report.network
    SectionCard(title = stringResource(R.string.diagnosis_network)) {
        ValueRow(
            stringResource(R.string.diagnosis_type),
            network?.let {
                val type = when (it.type) {
                    NetType.WIFI -> "Wi-Fi"
                    NetType.CELLULAR -> stringResource(R.string.diagnosis_type_cellular)
                    NetType.ETHERNET -> stringResource(R.string.diagnosis_type_ethernet)
                    NetType.OTHER -> stringResource(R.string.diagnosis_type_other)
                }
                if (it.vpn) stringOf(R.string.diagnosis_type_vpn, type) else type
            } ?: stringResource(R.string.diagnosis_no_network),
        )
        ValueRow(stringResource(R.string.diagnosis_local_address), report.localAddress ?: NONE)
        ValueRow(stringResource(R.string.diagnosis_public_address), report.publicAddress?.let(LineNetwork::hostPort) ?: NONE)
        ValueRow(
            "NAT",
            when (report.nat) {
                NatKind.NONE -> stringResource(R.string.diagnosis_nat_none)
                NatKind.PRESENT -> stringResource(R.string.diagnosis_nat_present)
                NatKind.CARRIER -> stringResource(R.string.diagnosis_nat_carrier)
                NatKind.SYMMETRIC -> stringResource(R.string.diagnosis_nat_symmetric)
                NatKind.UNKNOWN -> stringResource(R.string.diagnosis_unknown)
            },
        )
        ValueRow("IPv6", stringResource(if (network?.addresses?.any { ':' in it } == true) R.string.diagnosis_ipv6_yes else R.string.diagnosis_ipv6_no))
        ValueRow(
            stringResource(R.string.diagnosis_validation),
            when {
                network == null -> NONE
                network.captive -> stringResource(R.string.diagnosis_validation_login)
                network.validated -> stringResource(R.string.diagnosis_validation_passed)
                else -> stringResource(R.string.diagnosis_validation_failed)
            },
            error = network?.captive == true,
        )
        when (report.batteryUnrestricted) {
            null -> Unit
            true -> ValueRow(stringResource(R.string.diagnosis_battery), stringResource(R.string.diagnosis_battery_unrestricted))
            false -> Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // What the row is about yields first, so the value and its button keep their words whole.
                Box(Modifier.weight(1f)) { Label(stringResource(R.string.diagnosis_battery)) }
                Spacer(Modifier.width(16.dp))
                Text(stringResource(R.string.diagnosis_battery_restricted), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = onBattery) { Text(stringResource(R.string.diagnosis_battery_settings)) }
            }
        }
    }
}

@Composable
private fun ServerCard(report: LineReport) {
    SectionCard(title = stringResource(R.string.diagnosis_server)) {
        ValueRow(stringResource(R.string.diagnosis_address), report.server)
        val resolveFailed = stringResource(R.string.diagnosis_resolve_failed)
        ValueRow(stringResource(R.string.diagnosis_resolved), report.serverAddresses.joinToString(", ").ifEmpty { resolveFailed }, error = report.serverAddresses.isEmpty())
        // Only an account on UDP needs UDP; a line on TLS does not ask over it.
        val needsUdp = report.ownTransport == SipTransport.UDP
        ValueRow(
            "UDP",
            report.udp?.let { "${it.rttMs} ms" } ?: stringResource(if (report.udpAsked) R.string.diagnosis_no_response else R.string.diagnosis_not_tested),
            error = report.udpAsked && report.udp == null && needsUdp,
        )
        report.udpInvite?.let { whole ->
            val large = when {
                whole -> stringResource(R.string.diagnosis_large_passed)
                report.udpLarge == true -> stringResource(R.string.diagnosis_large_fragments_lost)
                else -> stringResource(R.string.diagnosis_large_lost)
            }
            ValueRow(stringResource(R.string.diagnosis_udp_large), large, error = !whole && needsUdp)
        }
        ValueRow("TCP", report.tcpMs?.let { "$it ms" } ?: stringResource(R.string.diagnosis_cannot_connect))
        ValueRow("TLS", report.tlsMs?.let { "$it ms" } ?: stringResource(R.string.diagnosis_cannot_connect))
        report.algSuspected?.let { ValueRow("SIP ALG", stringResource(if (it) R.string.diagnosis_alg_suspected else R.string.diagnosis_alg_not_found), error = it) }
        if (report.pbxUnreachable) ValueRow(stringResource(R.string.diagnosis_call_address), stringResource(R.string.diagnosis_call_address_internal), error = true)
    }
}

@Composable
private fun ServersCard(servers: ServerReport) {
    @Composable
    fun answer(found: Boolean?): String = when (found) {
        true -> stringResource(R.string.diagnosis_reachable)
        false -> stringResource(R.string.diagnosis_no_response)
        null -> NONE
    }
    SectionCard(title = stringResource(R.string.diagnosis_servers)) {
        ValueRow(stringResource(R.string.diagnosis_current), stringResource(if (servers.onBackup) R.string.diagnosis_on_backup else R.string.diagnosis_on_proxy))
        ValueRow(stringResource(R.string.diagnosis_proxy), answer(servers.primary), error = servers.primary == false)
        ValueRow(stringResource(R.string.diagnosis_backup), answer(servers.backup), error = servers.backup == false)
        ValueRow(stringResource(R.string.diagnosis_probed_at), servers.checkedAt?.let { CLOCK.format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())) } ?: NONE)
        ValueRow(stringResource(R.string.diagnosis_fast_timeout), if (servers.timeoutMs > 0) "${servers.timeoutMs} ms" else stringResource(R.string.diagnosis_off))
    }
}

@Composable
private fun RegistrationCard(report: LineReport) {
    SectionCard(title = stringResource(R.string.diagnosis_registration)) {
        ValueRow(stringResource(R.string.diagnosis_state), report.error ?: regStateLabel(report.registration), error = report.error != null)
        ValueRow(stringResource(R.string.diagnosis_transport), report.adaptedTransport?.let { LineAdapt.label(it).text() } ?: report.transport.name)
        ValueRow("Contact", report.contact ?: NONE)
        ValueRow(stringResource(R.string.diagnosis_expires), pluralOf(R.plurals.diagnosis_seconds, report.expires, report.expires.toString()))
    }
}

@Composable
private fun LastCallCard(report: LineReport) {
    val call = report.lastCall ?: return
    SectionCard(title = stringResource(R.string.diagnosis_last_call)) {
        ValueRow(
            stringResource(R.string.diagnosis_sound),
            when (call.flow) {
                MediaFlow.BOTH -> stringResource(R.string.diagnosis_sound_both)
                MediaFlow.SEND_ONLY -> stringResource(R.string.diagnosis_sound_send_only)
                MediaFlow.RECEIVE_ONLY -> stringResource(R.string.diagnosis_sound_receive_only)
                MediaFlow.NONE -> stringResource(R.string.diagnosis_sound_none)
            },
            error = call.flow != MediaFlow.BOTH,
        )
        ValueRow(stringResource(R.string.diagnosis_loss), stringOf(R.string.diagnosis_loss_value, percent(call.lossDown), percent(call.lossUp)))
        ValueRow(stringResource(R.string.diagnosis_jitter), "${call.jitterMs.toInt()} ms")
        ValueRow(stringResource(R.string.diagnosis_round_trip), "${call.roundTripMs.toInt()} ms")
        if (call.ice.isNotEmpty()) ValueRow("ICE", call.ice)
        if (call.mediaLost) ValueRow(stringResource(R.string.diagnosis_ended), CallSupport.NO_SOUND.text(), error = true)
    }
}

@Composable
private fun AdaptationCard(report: LineReport, onReset: () -> Unit) {
    SectionCard(title = stringResource(R.string.diagnosis_adaptation)) {
        report.adaptedTransport?.let { ValueRow(stringResource(R.string.diagnosis_adapted_transport), it.name) }
        report.adaptedExpires?.let { ValueRow(stringResource(R.string.diagnosis_adapted_expires), pluralOf(R.plurals.diagnosis_seconds, it, it.toString())) }
        if (report.adaptedIce) ValueRow("ICE", stringResource(R.string.diagnosis_on))
        OutlinedButton(
            onClick = onReset,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) { Text(stringResource(R.string.common_restore_defaults)) }
    }
}

private fun percent(value: Float): String = "%.1f%%".format(Locale.ROOT, value)

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** A value with what it is on its left; [error] marks one that explains a fault. */
@Composable
private fun ValueRow(label: String, value: String, error: Boolean = false) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Label(label)
        Spacer(Modifier.width(16.dp))
        // Addresses can be long: one line, fading where it does not fit.
        FadingText(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            textAlign = endAlign(),
            modifier = Modifier.weight(1f),
        )
    }
}
