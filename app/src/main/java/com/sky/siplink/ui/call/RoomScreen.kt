package com.sky.siplink.ui.call

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sky.siplink.core.CallManager
import com.sky.siplink.core.logic.CallRules
import com.sky.siplink.core.logic.Formatters
import com.sky.siplink.core.logic.RoomHealth
import com.sky.siplink.core.logic.RoomRules
import com.sky.siplink.core.model.CallInfo
import com.sky.siplink.core.model.LinkReadings
import com.sky.siplink.core.model.MemberState
import com.sky.siplink.core.model.RoomInfo
import com.sky.siplink.core.model.RoomMember
import com.sky.siplink.core.model.RoomWarning
import com.sky.siplink.core.model.SideCall
import com.sky.siplink.engine
import com.sky.siplink.ui.components.Avatar
import com.sky.siplink.ui.components.FadingText
import com.sky.siplink.ui.theme.Brand
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter

/**
 * The call screen while the conference room is open: who is in it and how each one stands, in
 * place of the single caller; below (or, with the phone on its side, beside) the controls of
 * the room. Laid out by start and end, so that it mirrors for right-to-left languages by itself.
 */
@Composable
internal fun RoomContent(
    info: CallInfo,
    room: RoomInfo,
    calls: CallManager,
    notice: String?,
    onAddMembers: () -> Unit,
    onNotice: (String) -> Unit,
    confirmEnd: Boolean,
    onConfirmEnd: (Boolean) -> Unit,
) {
    // Ending the room for everyone is asked for again (CallRules.confirmRoomEnd); one member is ended in its row.
    val end = { if (CallRules.confirmRoomEnd(room.present)) onConfirmEnd(true) else calls.hangUp() }
    val controls: @Composable (Modifier) -> Unit = { modifier ->
        RoomControls(info, room, calls, onAddMembers, onNotice, onEnd = end, modifier)
    }
    if (confirmEnd) {
        AlertDialog(
            onDismissRequest = { onConfirmEnd(false) },
            title = { Text("结束会议？") },
            text = { Text("全部 ${room.present} 方通话将被挂断。") },
            confirmButton = {
                TextButton(onClick = {
                    onConfirmEnd(false)
                    calls.hangUp()
                }) { Text("确认结束", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { onConfirmEnd(false) }) { Text("取消") } },
        )
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth > maxHeight) {
            Row(
                Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .padding(horizontal = 24.dp),
            ) {
                Box(
                    Modifier
                        .width(SideControlsWidth)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                    contentAlignment = Alignment.Center,
                ) {
                    controls(Modifier.padding(vertical = 8.dp))
                }
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(start = 24.dp, top = 8.dp),
                ) {
                    RoomHead(info, room, calls, notice, onNotice)
                    MemberList(room, calls, onNotice, Modifier.weight(1f))
                }
            }
        } else {
            Column(
                Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .padding(horizontal = 20.dp),
            ) {
                Spacer(Modifier.height(16.dp))
                RoomHead(info, room, calls, notice, onNotice)
                MemberList(room, calls, onNotice, Modifier.weight(1f))
                Spacer(Modifier.height(12.dp))
                controls(Modifier)
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

/** The name of the room with its count and its time, the warnings, and a call that waits. */
@Composable
private fun ColumnScope.RoomHead(info: CallInfo, room: RoomInfo, calls: CallManager, notice: String?, onNotice: (String) -> Unit) {
    var readingsOpen by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom) {
                FadingText(
                    "会议室",
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    RoomRules.count(room.present, room.limit),
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White.copy(alpha = 0.7f),
                )
            }
            Text(callTimer(room.openedAt), style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.7f))
        }
        AnimatedVisibility(room.warnings.isNotEmpty(), enter = fadeIn(), exit = fadeOut()) {
            WarningChip(room.warnings, onClick = { readingsOpen = !readingsOpen })
        }
    }
    AnimatedVisibility(
        visible = readingsOpen && room.warnings.isNotEmpty(),
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
    ) {
        Readings(room)
    }
    AnimatedVisibility(
        visible = room.away,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
    ) {
        Away(onReturn = { calls.rejoinRoom()?.let(onNotice) })
    }
    AnimatedVisibility(
        visible = info.waitingCall != null,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
    ) {
        WaitingForRoom(info.waitingCall, onJoin = { calls.joinRoom()?.let(onNotice) }, onDecline = calls::decline)
    }
    NoticeLine(notice)
}

/** The most pressing warning; a tap opens the measured values. */
@Composable
private fun WarningChip(warnings: Set<RoomWarning>, onClick: () -> Unit) {
    val text = WARNING_ORDER.first { it in warnings }.let(::warningText)
    Row(
        Modifier
            .padding(start = 12.dp)
            .widthIn(max = 200.dp)
            .clip(CircleShape)
            .background(Brand.Amber.copy(alpha = 0.9f))
            .clickable(onClickLabel = "查看测量值", onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.WarningAmber, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        FadingText(text, style = MaterialTheme.typography.labelMedium, color = Color.White, modifier = Modifier.weight(1f, fill = false))
    }
}

private val WARNING_ORDER = listOf(RoomWarning.HEAT, RoomWarning.LOAD, RoomWarning.MEMORY, RoomWarning.NETWORK)

private fun warningText(warning: RoomWarning): String = when (warning) {
    RoomWarning.HEAT -> "设备温度过高"
    RoomWarning.LOAD -> "设备负载过高"
    RoomWarning.MEMORY -> "内存不足"
    RoomWarning.NETWORK -> "网络质量差"
}

/** What was measured last: of the phone, and of the members whose connection is poor. */
@Composable
private fun Readings(room: RoomInfo) {
    val device = room.device
    Column(
        Modifier
            .padding(top = 10.dp)
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(Color.White.copy(alpha = 0.1f))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Reading("处理器", device?.let { percent(it.cpuPercent) }, device != null && device.cpuPercent >= RoomHealth.CPU_PERCENT)
        Reading("最忙线程", device?.let { percent(it.busiestThreadPercent) }, device != null && device.busiestThreadPercent >= RoomHealth.THREAD_PERCENT)
        Reading("设备温度", device?.let { thermalText(it.thermalStatus) }, device != null && RoomHealth.hot(device))
        Reading("内存", device?.let { if (it.lowMemory) "不足" else "正常" }, device?.lowMemory == true)
        room.members.filter { it.poorLink }.take(MAX_POOR_SHOWN).forEach { member ->
            Reading(member.name, member.link?.let(::linkText), highlighted = true)
        }
    }
}

@Composable
private fun Reading(label: String, value: String?, highlighted: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        FadingText(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.7f),
            modifier = Modifier.widthIn(max = 120.dp),
        )
        Spacer(Modifier.width(12.dp))
        FadingText(
            value ?: "—",
            style = MaterialTheme.typography.bodyMedium,
            color = if (highlighted) Brand.Amber else Color.White,
            modifier = Modifier.weight(1f),
        )
    }
}

private fun percent(value: Float): String = "%.0f%%".format(value)

private fun thermalText(status: Int?): String = when (status) {
    null -> "未知"
    0 -> "正常"
    1 -> "轻度"
    2 -> "中度"
    3 -> "严重"
    4 -> "危急"
    5 -> "紧急"
    else -> "即将关机"
}

private fun linkText(link: LinkReadings): String =
    "丢包 ↑%.0f%% ↓%.0f%%  抖动 %.0f ms  往返 %.0f ms".format(link.lossUp, link.lossDown, link.jitterMs, link.roundTripMs)

/** At most this many members with a poor connection are named in the readings. */
private const val MAX_POOR_SHOWN = 5

/**
 * The members, in the order they came in. A tap while the list moves, or right after it
 * stopped (the tap that stops a fling), acts on no one ([RoomRules.rowTapTaken]).
 */
@Composable
private fun MemberList(room: RoomInfo, calls: CallManager, onNotice: (String) -> Unit, modifier: Modifier) {
    val listState = rememberLazyListState()
    // When the list last stopped moving; it starts still.
    var stoppedAt by remember { mutableLongStateOf(Long.MIN_VALUE / 2) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.drop(1).filter { !it }.collect { stoppedAt = SystemClock.uptimeMillis() }
    }
    val guarded = { action: () -> Unit ->
        if (RoomRules.rowTapTaken(listState.isScrollInProgress, SystemClock.uptimeMillis() - stoppedAt)) action()
    }
    LazyColumn(modifier.fillMaxWidth(), state = listState, contentPadding = PaddingValues(vertical = 8.dp)) {
        items(room.members, key = { it.id }, contentType = { "member" }) { member ->
            MemberRow(
                member = member,
                onHangUp = { guarded { calls.hangUpMember(member.id) } },
                onRetry = { guarded { calls.retryMember(member.id)?.let(onNotice) } },
                modifier = Modifier.animateItem(),
            )
        }
    }
}

/**
 * One member: hung up while its call is up or on its way; taken off the list while it waits to
 * be called; called again or taken off once the PBX held it back.
 */
@Composable
private fun MemberRow(member: RoomMember, onHangUp: () -> Unit, onRetry: () -> Unit, modifier: Modifier) {
    val left = member.state == MemberState.LEFT
    val heldBack = member.state == MemberState.HELD_BACK
    val number = Formatters.userPart(member.address)
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .graphicsLayer { alpha = if (left) 0.55f else 1f }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(member.name, size = 40.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            FadingText(member.name, style = MaterialTheme.typography.titleMedium, color = Color.White)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (number != member.name) {
                    FadingText(
                        number,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.6f),
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    RoomRules.stateLabel(member.state, member.endReason),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (left || heldBack || member.state == MemberState.HELD) Brand.Amber else Color.White.copy(alpha = 0.6f),
                    maxLines = 1,
                )
                if (member.state == MemberState.CONNECTED) {
                    Spacer(Modifier.width(8.dp))
                    SignalBars(member.signal, member.poorLink)
                }
            }
        }
        when {
            left -> Unit
            heldBack -> {
                RowAction(Icons.Rounded.Call, "重拨 ${member.name}", Color.White, onRetry)
                RowAction(Icons.Rounded.Close, "移除 ${member.name}", Color.White, onHangUp)
            }
            member.state == MemberState.QUEUED -> RowAction(Icons.Rounded.Close, "移除 ${member.name}", Color.White, onHangUp)
            else -> RowAction(Icons.Rounded.CallEnd, "挂断 ${member.name}", Brand.HangUpRed, onHangUp)
        }
    }
}

/** A small round button at the end of a member's row. */
@Composable
private fun RowAction(icon: ImageVector, description: String, tint: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.1f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(20.dp))
        }
    }
}

/**
 * The quality of a member's connection as three bars ([RoomHealth.signalBars]); amber once it
 * has been poor for some seconds.
 */
@Composable
private fun SignalBars(bars: Int?, poor: Boolean) {
    val shown = bars ?: if (poor) 1 else return
    val on = if (poor) Brand.Amber else Color.White.copy(alpha = 0.7f)
    val off = Color.White.copy(alpha = 0.18f)
    Canvas(
        Modifier
            .size(width = 14.dp, height = 11.dp)
            .semantics { contentDescription = if (poor) "通话质量差" else "通话质量 $shown/3" },
    ) {
        val gap = size.width / 7f
        val width = (size.width - 2 * gap) / 3f
        for (index in 0 until 3) {
            val height = size.height * (index + 1) / 3f
            drawRoundRect(
                color = if (index < shown) on else off,
                topLeft = Offset(index * (width + gap), size.height - height),
                size = Size(width, height),
                cornerRadius = CornerRadius(width / 3f),
            )
        }
    }
}

/** The user is out of the room's sound since another app took it; the members go on. */
@Composable
private fun Away(onReturn: () -> Unit) {
    Row(
        Modifier
            .padding(top = 12.dp)
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(Color.White.copy(alpha = 0.16f))
            .padding(start = 16.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("已暂离", style = MaterialTheme.typography.titleMedium, color = Brand.Amber, modifier = Modifier.weight(1f))
        Button(
            onClick = onReturn,
            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Brand.CallSurface),
        ) { Text("返回会议室") }
    }
}

/** A call that comes in while the room is open: it can only be answered into the room. */
@Composable
private fun WaitingForRoom(waiting: SideCall?, onJoin: () -> Unit, onDecline: () -> Unit) {
    Column(
        Modifier
            .padding(top = 12.dp)
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(Color.White.copy(alpha = 0.16f))
            .padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 8.dp),
    ) {
        Text("新来电", style = MaterialTheme.typography.labelMedium, color = Brand.Amber)
        FadingText(waiting?.name.orEmpty(), style = MaterialTheme.typography.titleMedium, color = Color.White)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            TextButton(onClick = onDecline) { Text("拒接", color = Color.White.copy(alpha = 0.85f)) }
            Button(
                onClick = onJoin,
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Brand.CallSurface),
            ) { Text("加入会议室") }
        }
    }
}

/** Mute, the route of the sound, adding members, video (never in a room), and the end of the room. */
@Composable
private fun RoomControls(
    info: CallInfo,
    room: RoomInfo,
    calls: CallManager,
    onAddMembers: () -> Unit,
    onNotice: (String) -> Unit,
    onEnd: () -> Unit,
    modifier: Modifier,
) {
    val settings by LocalContext.current.engine.settings.settings.collectAsStateWithLifecycle()
    val full = RoomRules.refusal(room.present, 1, room.limit)
    Column(
        modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            ControlToggle(
                icon = if (info.muted) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                label = if (info.muted) "已静音" else "静音",
                active = info.muted,
                onClick = calls::toggleMute,
            )
            RouteToggle(info, calls)
            ControlToggle(
                Icons.Rounded.PersonAdd,
                "添加成员",
                active = false,
                // Full, the key is grey and its tap says why.
                dimmed = full != null,
                onClick = { if (full != null) onNotice(full) else onAddMembers() },
            )
            // Nothing of video is shown while video calls are switched off; in a room it cannot be switched on.
            if (settings.videoCalls) ControlToggle(Icons.Rounded.Videocam, "视频", active = false, enabled = false, onClick = {})
        }
        RoundAction(Brand.HangUpRed, Icons.Rounded.CallEnd, "结束会议", onEnd, size = 72.dp)
    }
}
