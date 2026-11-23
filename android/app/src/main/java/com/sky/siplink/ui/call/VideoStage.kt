package com.sky.siplink.ui.call

import android.content.Context
import android.view.TextureView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.VideocamOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sky.siplink.R
import com.sky.siplink.core.VideoViews
import com.sky.siplink.core.logic.PreviewDock
import com.sky.siplink.core.logic.VideoRules
import com.sky.siplink.core.model.CallVideo
import com.sky.siplink.engine
import com.sky.siplink.ui.components.LeftToRight
import com.sky.siplink.ui.theme.Brand
import org.linphone.mediastream.video.capture.CaptureTextureView

/**
 * The picture of the other side on the whole screen, with the own camera in a small window. The
 * small window stands at a side edge, wherever the user dragged it ([PreviewDock]), and keeps
 * clear of the controls while they are shown; a tap on it swaps the two pictures. Its buttons
 * are in the row at the top of the call screen ([VideoButtons]); the shade under the controls
 * shows with them ([chrome]); the call screen decides when (see ControlsVisibility).
 *
 * A picture never changes its shape. Where the other side's has another shape than the screen it
 * is either shown whole, with the rest of the screen black, or enlarged until it fills the
 * screen, its edges cut off: [VideoRules.fillsByDefault] picks, and a key changes it. The own
 * picture, large, is always shown whole.
 *
 * The two pictures keep their views when they swap: only the places of the views change, so
 * the SIP core goes on drawing into the same ones (they are handed to it once, as they come and
 * go: [VideoViews]).
 */
@Composable
internal fun VideoStage(video: CallVideo, stage: VideoStageState, chrome: Boolean) {
    val engine = LocalContext.current.engine
    val settings by engine.settings.settings.collectAsStateWithLifecycle()
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val insets = WindowInsets.safeDrawing
    val scope = rememberCoroutineScope()
    val small = remember { SmallPictureState() }
    // The pictures stand where the user put them, left or right, in every language: placed by the left edge.
    LeftToRight {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clipToBounds(),
        ) {
            val picture = video.received
            val cut = if (picture != null) VideoRules.cutWhenFilled(picture, constraints.maxWidth, constraints.maxHeight) else 0f
            SideEffect { stage.cut = cut }
            val canSwap = VideoRules.canSwap(video.sending, picture != null)
            // Back to normal without one of the pictures, and so it stays when the picture returns.
            LaunchedEffect(canSwap) { if (!canSwap) stage.swapChosen = false }
            val swapped = stage.swapped(video)

            // The small window: the shape of the picture in it, at its place in the band it may stand in now.
            val (smallWidth, smallHeight) = VideoRules.previewSize(if (swapped) picture else video.sent, PreviewLongSide.value)
            val widthPx = with(density) { smallWidth.dp.toPx() }
            val heightPx = with(density) { smallHeight.dp.toPx() }
            val safe = Rect(
                insets.getLeft(density, direction).toFloat(),
                insets.getTop(density).toFloat(),
                insets.getRight(density, direction).toFloat(),
                insets.getBottom(density).toFloat(),
            )
            val (shown, hidden) = dockAreas(
                constraints.maxWidth.toFloat(),
                constraints.maxHeight.toFloat(),
                safe,
                with(density) { DockMargin.toPx() },
                stage,
                wide = maxWidth > maxHeight,
            )
            val now = if (chrome) shown else hidden
            val place = PreviewDock.Place(settings.previewSide, settings.previewPlace)
            FollowSpot(small, PreviewDock.spot(place, widthPx, heightPx, shown, now), controlsShown = chrome)
            val frame = SmallPictureState.Frame(widthPx, heightPx, shown, now, place, with(density) { FlingSpeed.toPx() })
            SideEffect { small.frame = frame }
            val labels = SmallPictureLabels.current()
            val smallWindow = Modifier
                .size(smallWidth.dp, smallHeight.dp)
                .smallPicture(
                    small, scope, frame, labels,
                    own = !swapped,
                    canSwap = canSwap,
                    onSwap = { stage.swapChosen = !stage.swapChosen },
                    onPlace = { chosen -> engine.updateSettings { it.copy(previewSide = chosen.side, previewPlace = chosen.fraction) } },
                )

            if (video.receiving) {
                // Only the user's change is animated: the first picture and one of a new size take their place at once.
                val scale by animateFloatAsState(
                    targetValue = if (stage.filled && !swapped) VideoRules.fillScale(cut) else 1f,
                    animationSpec = if (stage.fillChosen != null) tween(220) else snap(),
                    label = "pictureScale",
                )
                Box(
                    if (swapped) {
                        Modifier
                            .zIndex(SMALL)
                            .offset { small.offset }
                            .then(smallWindow)
                    } else {
                        Modifier.fillMaxSize()
                    },
                ) {
                    RemoteVideo(
                        if (swapped) {
                            Modifier.fillMaxSize()
                        } else {
                            Modifier
                                // The view has the shape of the picture, so that the SDK draws the whole of it
                                // into the whole of the view; until a picture came, the view is the screen.
                                .layout { measurable, constraints ->
                                    val size = picture?.let { VideoRules.fitted(it, constraints.maxWidth, constraints.maxHeight) }
                                    val width = size?.width ?: constraints.maxWidth
                                    val height = size?.height ?: constraints.maxHeight
                                    val placeable = measurable.measure(Constraints.fixed(width, height))
                                    layout(constraints.maxWidth, constraints.maxHeight) {
                                        placeable.place((constraints.maxWidth - width) / 2, (constraints.maxHeight - height) / 2)
                                    }
                                }
                                .graphicsLayer {
                                    scaleX = scale
                                    scaleY = scale
                                }
                        },
                    )
                }
            }
            // Shade where the name and the controls sit, so that white stays readable on a bright picture:
            // top and bottom, or, with the phone on its side, the top and the side the controls stand at.
            val wide = maxWidth > maxHeight
            AnimatedVisibility(chrome, Modifier.fillMaxSize().zIndex(SHADE), enter = fadeIn(tween(180)), exit = fadeOut(tween(140))) {
                Box(
                    if (wide) {
                        Modifier
                            .fillMaxSize()
                            .background(Brush.horizontalGradient(0f to Shade, 0.5f to Color.Transparent))
                            .background(Brush.verticalGradient(0f to Shade, 0.4f to Color.Transparent))
                    } else {
                        Modifier
                            .fillMaxSize()
                            .background(Brush.verticalGradient(0f to Shade, 0.22f to Color.Transparent, 0.55f to Color.Transparent, 1f to Shade))
                    },
                )
            }
            AnimatedVisibility(
                video.sending,
                if (swapped) Modifier.zIndex(LARGE) else Modifier.zIndex(SMALL).offset { small.offset },
                enter = fadeIn(tween(180)) + scaleIn(tween(200), 0.85f),
                exit = fadeOut(tween(140)) + scaleOut(tween(160), 0.85f),
            ) {
                // Large, the own picture fills the screen, and the SDK's view shows it whole (black bars).
                Box(if (swapped) Modifier.fillMaxSize() else smallWindow) {
                    LocalPreview(Modifier.fillMaxSize())
                }
            }
        }
    }
}

/** Where the SDK draws the other side's video while the view is on screen. */
@Composable
private fun RemoteVideo(modifier: Modifier) {
    AndroidView(factory = ::RemoteVideoView, modifier = modifier)
}

/** Where the SDK draws what the own camera sends. */
@Composable
private fun LocalPreview(modifier: Modifier) {
    AndroidView(factory = ::PreviewView, modifier = modifier)
}

/*
 * The two views give themselves to the SIP core when they come into a window, and take
 * themselves back before they leave it, not later ([VideoViews]): the camera, which waits for
 * its view, would otherwise not start on a call screen started anew.
 */

private class RemoteVideoView(context: Context) : TextureView(context) {
    private val views = context.engine.videoViews

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        views.give(VideoViews.Kind.REMOTE, this)
    }

    override fun onDetachedFromWindow() {
        views.takeBack(VideoViews.Kind.REMOTE, this)
        super.onDetachedFromWindow()
    }
}

private class PreviewView(context: Context) : CaptureTextureView(context) {
    private val views = context.engine.videoViews

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        views.give(VideoViews.Kind.PREVIEW, this)
    }

    override fun onDetachedFromWindow() {
        views.takeBack(VideoViews.Kind.PREVIEW, this)
        super.onDetachedFromWindow()
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
        Text(stringResource(R.string.video_request), style = MaterialTheme.typography.titleSmall, color = Color.White, modifier = Modifier.weight(1f))
        TextButton(onClick = onDecline) { Text(stringResource(R.string.video_request_decline), color = Color.White.copy(alpha = 0.8f)) }
        TextButton(onClick = onAccept) { Text(stringResource(R.string.video_request_accept), color = Brand.Blue) }
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
        Text(stringResource(if (on) R.string.video_answer_with_video else R.string.video_answer_with_audio), style = MaterialTheme.typography.labelLarge, color = content)
    }
}

private val Shade = Color.Black.copy(alpha = 0.55f)

/** The longer side of the small window. */
private val PreviewLongSide = 148.dp

/** What the small window keeps from the edges, the bars and the controls. */
private val DockMargin = 12.dp

/** From this speed on (per second), a small window let go goes to the side it was flung towards. */
private val FlingSpeed = 400.dp

/** The order the parts are drawn in: the large picture, the shade, the small window. */
private const val LARGE = 0f
private const val SHADE = 1f
private const val SMALL = 2f
