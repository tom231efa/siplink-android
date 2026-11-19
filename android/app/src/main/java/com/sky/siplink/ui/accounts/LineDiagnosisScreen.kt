package com.sky.siplink.ui.accounts

import android.content.Intent
import android.provider.Settings
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sky.siplink.core.LineCheck
import com.sky.siplink.core.LineNetwork
import com.sky.siplink.core.logic.LineAdapt
import com.sky.siplink.core.model.LineReport
import com.sky.siplink.core.model.MediaFlow
import com.sky.siplink.core.model.NatKind
import com.sky.siplink.core.model.NetType
import com.sky.siplink.engine
import com.sky.siplink.ui.components.FadingText
import com.sky.siplink.ui.components.LocalMessenger
import com.sky.siplink.ui.components.Personal
import com.sky.siplink.ui.components.SectionCard
import com.sky.siplink.ui.components.SubScreen
import com.sky.siplink.ui.components.regStateLabel

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

    LaunchedEffect(round) {
        busy = check.run(accountId) {
            report = it
            busy = false
        }
        if (!busy) onBack()
    }

    Personal {
        SubScreen(
            title = "线路诊断",
            onBack = onBack,
            actions = { TextButton(onClick = { round++ }, enabled = !busy) { Text("重新诊断") } },
        ) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            report?.let { shown ->
                NetworkCard(shown, onBattery = {
                    runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                })
                ServerCard(shown)
                RegistrationCard(shown)
                shown.lastCall?.let { LastCallCard(shown) }
                if (shown.adaptedTransport != null || shown.adaptedExpires != null) {
                    AdaptationCard(shown, onReset = { confirmReset = true })
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("恢复默认？") },
            text = { Text("将清除该账号的自动调整。") },
            confirmButton = {
                TextButton(onClick = {
                    engine.accounts.resetAdaptation(accountId)
                    confirmReset = false
                    messenger("已恢复默认")
                    round++
                }) { Text("恢复") }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("取消") } },
        )
    }
}

private const val NONE = "—"

@Composable
private fun NetworkCard(report: LineReport, onBattery: () -> Unit) {
    val network = report.network
    SectionCard(title = "网络") {
        ValueRow(
            "类型",
            network?.let {
                val type = when (it.type) {
                    NetType.WIFI -> "Wi-Fi"
                    NetType.CELLULAR -> "移动数据"
                    NetType.ETHERNET -> "以太网"
                    NetType.OTHER -> "其他"
                }
                if (it.vpn) "$type · VPN" else type
            } ?: "无网络",
        )
        ValueRow("本机地址", report.localAddress ?: NONE)
        ValueRow("公网地址", report.publicAddress?.let(LineNetwork::hostPort) ?: NONE)
        ValueRow(
            "NAT",
            when (report.nat) {
                NatKind.NONE -> "无"
                NatKind.PRESENT -> "有"
                NatKind.CARRIER -> "运营商级"
                NatKind.SYMMETRIC -> "对称"
                NatKind.UNKNOWN -> "未知"
            },
        )
        ValueRow("IPv6", if (network?.addresses?.any { ':' in it } == true) "有" else "无")
        ValueRow(
            "网络验证",
            when {
                network == null -> NONE
                network.captive -> "需登录"
                network.validated -> "已通过"
                else -> "未通过"
            },
            error = network?.captive == true,
        )
        when (report.batteryUnrestricted) {
            null -> Unit
            true -> ValueRow("电池优化", "不受限")
            false -> Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Label("电池优化")
                Spacer(Modifier.weight(1f))
                Text("受限", style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = onBattery) { Text("设置") }
            }
        }
    }
}

@Composable
private fun ServerCard(report: LineReport) {
    SectionCard(title = "服务器") {
        ValueRow("地址", report.server)
        ValueRow("解析", report.serverAddresses.joinToString(", ").ifEmpty { "解析失败" }, error = report.serverAddresses.isEmpty())
        ValueRow("UDP", report.udp?.let { "${it.rttMs} ms" } ?: "无响应", error = report.udp == null)
        report.udpLarge?.let { ValueRow("UDP 大包", if (it) "通过" else "丢失", error = !it) }
        ValueRow("TCP", report.tcpMs?.let { "$it ms" } ?: "无法连接")
        ValueRow("TLS", report.tlsMs?.let { "$it ms" } ?: "无法连接")
        report.algSuspected?.let { ValueRow("SIP ALG", if (it) "疑似" else "未发现", error = it) }
    }
}

@Composable
private fun RegistrationCard(report: LineReport) {
    SectionCard(title = "注册") {
        ValueRow("状态", report.error ?: regStateLabel(report.registration), error = report.error != null)
        ValueRow("传输协议", report.adaptedTransport?.let(LineAdapt::label) ?: report.transport.name)
        ValueRow("Contact", report.contact ?: NONE)
        ValueRow("有效期", "${report.expires} 秒")
    }
}

@Composable
private fun LastCallCard(report: LineReport) {
    val call = report.lastCall ?: return
    SectionCard(title = "上次通话") {
        ValueRow(
            "声音",
            when (call.flow) {
                MediaFlow.BOTH -> "双向"
                MediaFlow.SEND_ONLY -> "仅发送"
                MediaFlow.RECEIVE_ONLY -> "仅接收"
                MediaFlow.NONE -> "无"
            },
            error = call.flow != MediaFlow.BOTH,
        )
        ValueRow("丢包", "↓ ${percent(call.lossDown)} · ↑ ${percent(call.lossUp)}")
        ValueRow("抖动", "${call.jitterMs.toInt()} ms")
        ValueRow("往返", "${call.roundTripMs.toInt()} ms")
        if (call.ice.isNotEmpty()) ValueRow("ICE", call.ice)
        if (call.mediaLost) ValueRow("结束", "媒体中断", error = true)
    }
}

@Composable
private fun AdaptationCard(report: LineReport, onReset: () -> Unit) {
    SectionCard(title = "自动调整") {
        report.adaptedTransport?.let { ValueRow("传输协议", it.name) }
        report.adaptedExpires?.let { ValueRow("注册有效期", "$it 秒") }
        OutlinedButton(
            onClick = onReset,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) { Text("恢复默认") }
    }
}

private fun percent(value: Float): String = "%.1f%%".format(value)

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
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}
