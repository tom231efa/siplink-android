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
import androidx.compose.foundation.lazy.LazyListScope
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
import com.sky.siplink.core.logic.VideoRules
import com.sky.siplink.core.model.Codec
import com.sky.siplink.core.model.Settings
import com.sky.siplink.core.model.VideoSize
import com.sky.siplink.engine
import com.sky.siplink.ui.components.ChoiceRow
import com.sky.siplink.ui.components.LocalConfigLocked
import com.sky.siplink.ui.components.LocalMessenger
import com.sky.siplink.ui.components.ManagedLock

/**
 * Audio and video codecs: which ones are offered, and in which order of preference. The video
 * ones are only listed while video calls are on, and only those the SDK provides on the device;
 * above them stand the size and the frame rate the own camera is sent with.
 * Where the configuration is locked all of it is only shown: no switch, arrow, choice or "restore defaults".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodecsScreen(onBack: () -> Unit) {
    val engine = LocalContext.current.engine
    val manager = engine.codecs
    val codecs by manager.codecs.collectAsStateWithLifecycle()
    val videoCodecs by manager.videoCodecs.collectAsStateWithLifecycle()
    val settings by engine.settings.settings.collectAsStateWithLifecycle()
    val messenger = LocalMessenger.current
    val locked = LocalConfigLocked.current
    val video = settings.videoCalls && videoCodecs.isNotEmpty()

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(if (video) "编码" else "音频编码") },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回") }
            },
            actions = {
                ManagedLock()
                if (!locked) {
                    TextButton(
                        onClick = {
                            manager.restoreDefaults()
                            val defaults = Settings()
                            engine.updateSettings { it.copy(videoSize = defaults.videoSize, videoFrameRate = defaults.videoFrameRate) }
                            messenger("已恢复默认的编码设置")
                        },
                    ) { Text("恢复默认") }
                }
            },
        )
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (video) item(key = "audioTitle", contentType = "title") { KindTitle("音频") }
            codecRows(codecs, onMove = manager::move) { key, enabled ->
                if (!manager.setEnabled(key, enabled)) messenger("至少保留一种音频编码")
            }
            if (video) {
                item(key = "videoTitle", contentType = "title") { KindTitle("视频") }
                item(key = "videoQuality", contentType = "quality") {
                    VideoQuality(
                        size = settings.videoSize,
                        frameRate = settings.videoFrameRate,
                        onSize = { size -> engine.updateSettings { it.copy(videoSize = size) } },
                        onFrameRate = { rate -> engine.updateSettings { it.copy(videoFrameRate = rate) } },
                    )
                }
                codecRows(videoCodecs, onMove = manager::move) { key, enabled ->
                    if (!manager.setEnabled(key, enabled)) messenger("至少保留一种视频编码")
                }
            }
        }
    }
}

private fun LazyListScope.codecRows(
    codecs: List<Codec>,
    onMove: (key: String, offset: Int) -> Unit,
    onEnabledChange: (key: String, enabled: Boolean) -> Unit,
) {
    itemsIndexed(codecs, key = { _, codec -> codec.key }, contentType = { _, _ -> "codec" }) { index, codec ->
        CodecRow(
            codec = codec,
            position = index + 1,
            canMoveUp = index > 0,
            canMoveDown = index < codecs.lastIndex,
            onMove = { offset -> onMove(codec.key, offset) },
            onEnabledChange = { enabled -> onEnabledChange(codec.key, enabled) },
            modifier = Modifier.animateItem(),
        )
    }
}

@Composable
private fun KindTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 2.dp),
    )
}

private val videoSizes = listOf(VideoSize.QVGA to "240p", VideoSize.VGA to "480p", VideoSize.HD to "720p", VideoSize.FHD to "1080p")

private val frameRates = VideoRules.FRAME_RATES.map { it to if (it == VideoRules.AUTO_FRAME_RATE) "自动" else "$it" }

/** How large and how often the own camera is sent, above the video codecs. */
@Composable
private fun VideoQuality(size: VideoSize, frameRate: Int, onSize: (VideoSize) -> Unit, onFrameRate: (Int) -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("清晰度", style = MaterialTheme.typography.bodyMedium)
            ChoiceRow(options = videoSizes, selected = size, onSelect = onSize)
            Text("帧率（帧/秒）", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
            ChoiceRow(options = frameRates, selected = frameRate, onSelect = onFrameRate)
        }
    }
}

@Composable
private fun CodecRow(
    codec: Codec,
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
    val locked = LocalConfigLocked.current
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
                // A video codec's clock rate (90 kHz for all of them) says nothing to the user.
                if (!codec.isVideo) {
                    Text(
                        "${codec.clockRate} Hz" + if (codec.channels > 1) " · ${codec.channels} 声道" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = { onMove(-1) }, enabled = canMoveUp && !locked) {
                Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = "提高 ${codec.mime} 的优先级")
            }
            IconButton(onClick = { onMove(1) }, enabled = canMoveDown && !locked) {
                Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "降低 ${codec.mime} 的优先级")
            }
            Spacer(Modifier.width(4.dp))
            Switch(checked = codec.enabled, onCheckedChange = onEnabledChange, enabled = !locked)
        }
    }
}
