package com.sky.siplink.ui.call

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.SwapCalls
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.sky.siplink.core.model.CallInfo
import com.sky.siplink.core.model.SideCall
import com.sky.siplink.ui.components.FadingText
import com.sky.siplink.ui.theme.Brand

// The banners of the second call over a call: one that rings next to it, one on hold beside it.

/** Second call ringing on top of the current one. */
@Composable
internal fun WaitingCallBanner(waiting: SideCall?, onAnswer: () -> Unit, onDecline: () -> Unit) {
    Row(
        Modifier
            .padding(top = 12.dp)
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(Color.White.copy(alpha = 0.16f))
            .padding(start = 16.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("新来电", style = MaterialTheme.typography.labelMedium, color = Brand.Amber)
            FadingText(
                waiting?.name.orEmpty(),
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
            )
        }
        SmallRound(Brand.HangUpRed, Icons.Rounded.CallEnd, "拒绝新来电", onDecline)
        Spacer(Modifier.width(10.dp))
        SmallRound(Brand.CallGreen, Icons.Rounded.Call, "保留当前通话并接听", onAnswer)
    }
}

/** The other call, parked on hold; tapping swaps the two calls. */
@Composable
internal fun HeldCallBanner(info: CallInfo, onSwap: () -> Unit) {
    val held = info.heldCall
    Row(
        Modifier
            .padding(top = 12.dp)
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(Color.White.copy(alpha = 0.12f))
            .clickable(onClick = onSwap)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                if (info.consulting) "等待转接（保留中）" else "保留中",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.7f),
            )
            FadingText(
                held?.name.orEmpty(),
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
            )
        }
        Icon(Icons.Rounded.SwapCalls, contentDescription = "切换通话", tint = Color.White)
    }
}

@Composable
private fun SmallRound(color: Color, icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(color)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(22.dp))
    }
}
