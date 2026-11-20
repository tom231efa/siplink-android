package com.sky.siplink.ui.call

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.sky.siplink.R
import com.sky.siplink.core.logic.Formatters
import kotlinx.coroutines.delay

/**
 * The record button of the call screen. Stopped, a plain dot; recording, the dot is filled and
 * the time recorded stands beside it. Nothing blinks, and starting or stopping says nothing else.
 *
 * @param since while recording: SystemClock.elapsedRealtime() the recording would have started at without its breaks
 */
@Composable
internal fun RecordControl(since: Long?, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val recording = since != null
    val label = stringResource(if (recording) R.string.call_record_stop else R.string.call_record_start)
    Row(
        modifier
            .heightIn(min = 40.dp)
            .widthIn(min = 40.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = if (recording) 0.5f else 0.32f))
            .clickable(role = Role.Button, onClickLabel = label, onClick = onToggle)
            .padding(horizontal = if (recording) 12.dp else 9.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Rounded.FiberManualRecord,
            contentDescription = label,
            tint = if (recording) RecordingDot else Color.White.copy(alpha = 0.8f),
            modifier = Modifier.size(if (recording) 12.dp else 22.dp),
        )
        if (since != null) {
            Text(
                recordedTime(since),
                style = MaterialTheme.typography.labelLarge,
                color = Color.White,
            )
        }
    }
}

@Composable
private fun recordedTime(since: Long): String {
    val now by produceState(SystemClock.elapsedRealtime(), since) {
        while (true) {
            value = SystemClock.elapsedRealtime()
            delay(500)
        }
    }
    return Formatters.duration(((now - since) / 1000).coerceAtLeast(0))
}

/** A muted red: the usual sign of a recording, kept small. */
private val RecordingDot = Color(0xFFE5484D)
