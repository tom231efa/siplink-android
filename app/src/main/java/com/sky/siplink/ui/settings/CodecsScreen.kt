package com.sky.siplink.ui.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sky.siplink.core.model.AudioCodec
import com.sky.siplink.engine
import com.sky.siplink.ui.components.LocalMessenger

/** Audio codecs: which ones are offered, and in which order of preference. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodecsScreen(onBack: () -> Unit) {
    val manager = LocalContext.current.engine.codecs
    val codecs by manager.codecs.collectAsStateWithLifecycle()
    val messenger = LocalMessenger.current

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("音频编码") },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回") }
            },
            actions = {
                TextButton(
                    onClick = {
                        manager.restoreDefaults()
                        messenger("已恢复默认的编码与顺序")
                    },
                ) { Text("恢复默认") }
            },
        )
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(codecs, key = { _, codec -> codec.key }, contentType = { _, _ -> "codec" }) { index, codec ->
                CodecRow(
                    codec = codec,
                    position = index + 1,
                    canMoveUp = index > 0,
                    canMoveDown = index < codecs.lastIndex,
                    onMove = { offset -> manager.move(codec.key, offset) },
                    onEnabledChange = { enabled ->
                        if (!manager.setEnabled(codec.key, enabled)) messenger("至少保留一种编码")
                    },
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }
}

@Composable
private fun CodecRow(
    codec: AudioCodec,
    position: Int,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMove: (Int) -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val badge by animateColorAsState(
        if (codec.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
        label = "codecBadge",
    )
    val onBadge by animateColorAsState(
        if (codec.enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        label = "codecBadgeText",
    )
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.padding(start = 14.dp, end = 10.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = badge, modifier = Modifier.size(28.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Text("$position", style = MaterialTheme.typography.labelMedium, color = onBadge)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(codec.mime, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${codec.clockRate} Hz" + if (codec.channels > 1) " · ${codec.channels} 声道" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { onMove(-1) }, enabled = canMoveUp) {
                Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = "提高 ${codec.mime} 的优先级")
            }
            IconButton(onClick = { onMove(1) }, enabled = canMoveDown) {
                Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "降低 ${codec.mime} 的优先级")
            }
            Spacer(Modifier.width(4.dp))
            Switch(checked = codec.enabled, onCheckedChange = onEnabledChange)
        }
    }
}
