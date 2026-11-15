package com.sky.siplink.ui.call

import android.view.TextureView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cameraswitch
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.VideocamOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.sky.siplink.core.model.CallVideo
import com.sky.siplink.engine
import com.sky.siplink.ui.theme.Brand
import org.linphone.core.Core
import org.linphone.mediastream.video.capture.CaptureTextureView

/**
 * The picture of the other side over the whole screen, with the own camera in a small window
 * in the corner. Tapping the picture shows or hides the controls ([chrome]).
 */
@Composable
internal fun VideoStage(
    video: CallVideo,
    chrome: Boolean,
    onTap: () -> Unit,
    onSwitchCamera: () -> Unit,
    onToggleCamera: () -> Unit,
) {
    val core = LocalContext.current.engine.core
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onTap),
    ) {
        if (video.receiving) RemoteVideo(core, Modifier.fillMaxSize())
        // Shade where the name and the controls sit, so that white stays readable on a bright picture.
        AnimatedVisibility(chrome, Modifier.fillMaxSize(), enter = fadeIn(tween(180)), exit = fadeOut(tween(140))) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(0f to Shade, 0.22f to Color.Transparent, 0.55f to Color.Transparent, 1f to Shade)),
            )
        }
        Column(
            Modifier
                .align(Alignment.TopEnd)
                .systemBarsPadding()
                .padding(top = 12.dp, end = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AnimatedVisibility(video.sending, enter = fadeIn(tween(180)) + scaleIn(tween(200), 0.85f), exit = fadeOut(tween(140)) + scaleOut(tween(160), 0.85f)) {
                LocalPreview(
                    core,
                    Modifier
                        .size(width = PreviewWidth, height = PreviewHeight)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF202328)),
                )
            }
            AnimatedVisibility(chrome, enter = fadeIn(tween(180)), exit = fadeOut(tween(140))) {
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (video.sending) CornerButton(Icons.Rounded.Cameraswitch, "切换摄像头", onSwitchCamera)
                    CornerButton(
                        if (video.sending) Icons.Rounded.Videocam else Icons.Rounded.VideocamOff,
                        if (video.sending) "关闭摄像头" else "打开摄像头",
                        onToggleCamera,
                    )
                }
            }
        }
    }
}

/** Where the SDK draws the other side's video while the view is on screen. */
@Composable
private fun RemoteVideo(core: Core, modifier: Modifier) {
    val context = LocalContext.current
    val view = remember { TextureView(context) }
    DisposableEffect(view) {
        core.nativeVideoWindowId = view
        onDispose { if (core.nativeVideoWindowId === view) core.nativeVideoWindowId = null }
    }
    AndroidView(factory = { view }, modifier = modifier)
}

/** Where the SDK draws what the own camera sends. */
@Composable
private fun LocalPreview(core: Core, modifier: Modifier) {
    val context = LocalContext.current
    val view = remember { CaptureTextureView(context) }
    DisposableEffect(view) {
        core.nativePreviewWindowId = view
        onDispose { if (core.nativePreviewWindowId === view) core.nativePreviewWindowId = null }
    }
    AndroidView(factory = { view }, modifier = modifier)
}

@Composable
private fun CornerButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.42f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(22.dp))
    }
}

/** The other side asks to add video to the call: the camera starts only if the user says so. */
@Composable
internal fun VideoRequestBanner(onAccept: () -> Unit, onDecline: () -> Unit) {
    Row(
        Modifier
            .padding(top = 12.dp)
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(Color.White.copy(alpha = 0.16f))
            .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Videocam, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text("对方请求开启视频", style = MaterialTheme.typography.titleSmall, color = Color.White, modifier = Modifier.weight(1f))
        TextButton(onClick = onDecline) { Text("拒绝", color = Color.White.copy(alpha = 0.8f)) }
        TextButton(onClick = onAccept) { Text("开启", color = Brand.Blue) }
    }
}

/**
 * Above the answer slider of a call that offers video: whether it is answered with video. Off
 * at first, so that the camera never starts unless it was chosen.
 */
@Composable
internal fun AnswerWithVideoToggle(on: Boolean, onChange: (Boolean) -> Unit) {
    val container by animateColorAsState(if (on) Color.White else Color.White.copy(alpha = 0.14f), label = "videoAnswerBg")
    val content by animateColorAsState(if (on) Brand.CallSurface else Color.White, label = "videoAnswerFg")
    Row(
        Modifier
            .clip(CircleShape)
            .background(container)
            .clickable { onChange(!on) }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (on) Icons.Rounded.Videocam else Icons.Rounded.VideocamOff, contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(if (on) "视频接听" else "语音接听", style = MaterialTheme.typography.labelLarge, color = content)
    }
}

private val Shade = Color.Black.copy(alpha = 0.55f)
private val PreviewWidth = 104.dp
private val PreviewHeight = 156.dp
