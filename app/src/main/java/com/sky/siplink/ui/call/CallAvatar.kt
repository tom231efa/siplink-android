package com.sky.siplink.ui.call

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sky.siplink.ui.components.Avatar

/**
 * The avatar of the other party. While the call rings it has a pulse: the halo around it swells
 * in two quick beats and then rests, like a heartbeat. Each beat sends a thin ring outwards,
 * quick at first and slowing down as it fades, and the avatar itself gives a little with it.
 *
 * @param haloSize the room the whole thing takes up: how far the rings travel
 */
@Composable
internal fun CallAvatar(name: String, beating: Boolean, size: Dp, haloSize: Dp) {
    // Fades the pulse in and out. Its clock only runs while there is something of it to see: a
    // call can last for hours.
    val strength by animateFloatAsState(if (beating) 1f else 0f, tween(FADE_MS), label = "pulseStrength")
    val clock = if (beating || strength > 0f) {
        rememberInfiniteTransition(label = "pulse").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(HEARTBEAT_MS, easing = LinearEasing)),
            label = "pulseClock",
        )
    } else {
        null
    }

    Box(Modifier.size(haloSize), contentAlignment = Alignment.Center) {
        if (clock != null) {
            // Everything is worked out while drawing, so a beat costs no recomposition.
            Canvas(Modifier.fillMaxSize()) {
                val time = clock.value
                val body = size.toPx() / 2f
                val reach = this.size.minDimension / 2f
                val edge = body * HALO_REST

                // The halo right around the avatar, and a fainter one further out a moment behind.
                val swell = swell(time)
                drawCircle(Color.White.copy(alpha = (0.10f + 0.16f * swell) * strength), radius = edge + body * HALO_SWELL * swell)
                val late = swell(time - AURA_LAG)
                drawCircle(Color.White.copy(alpha = (0.05f + 0.08f * late) * strength), radius = body * (AURA_REST + AURA_SWELL * late))

                for (beat in BEATS) {
                    val age = phase(time - beat.at) / WAVE_SPAN
                    if (age < 1f) {
                        val travelled = 1f - (1f - age) * (1f - age)
                        drawCircle(
                            Color.White.copy(alpha = WAVE_ALPHA * beat.force * (1f - age) * strength),
                            radius = edge + (reach - edge) * travelled,
                            style = Stroke(width = WAVE_WIDTH.toPx() * (1f - 0.6f * age)),
                        )
                    }
                }
            }
        }
        Avatar(
            name,
            Modifier.graphicsLayer {
                val scale = 1f + AVATAR_GIVE * strength * (clock?.value?.let(::swell) ?: 0f)
                scaleX = scale
                scaleY = scale
            },
            size = size,
        )
    }
}

/** One beat of the heartbeat: when it starts, as a fraction of the heartbeat, and how strong it is. */
private class Beat(val at: Float, val force: Float)

/** A strong beat and a weaker one right behind it; the rest of the time is the pause. */
private val BEATS = arrayOf(Beat(at = 0f, force = 1f), Beat(at = 0.2f, force = 0.6f))

/** Where in the heartbeat [time] is; [time] may lie before its start. */
private fun phase(time: Float): Float = ((time % 1f) + 1f) % 1f

/** How much of a beat is left [since] it started: up at once, down slowly. */
private fun pulse(since: Float): Float = when {
    since < ATTACK -> 1f - (1f - since / ATTACK).let { it * it }
    since < ATTACK + DECAY -> (1f - (since - ATTACK) / DECAY).let { it * it }
    else -> 0f
}

/** How far the halo is swollen at [time]: what is left of both beats. */
private fun swell(time: Float): Float {
    var sum = 0f
    for (beat in BEATS) sum += beat.force * pulse(phase(time - beat.at))
    return sum.coerceAtMost(1f)
}

private const val HEARTBEAT_MS = 1500
private const val FADE_MS = 400

/** Parts of the heartbeat: a beat rises, falls, and its ring travels. */
private const val ATTACK = 0.06f
private const val DECAY = 0.3f
private const val WAVE_SPAN = 0.62f
private const val AURA_LAG = 0.04f

/** Sizes, in radii of the avatar. */
private const val HALO_REST = 1.14f
private const val HALO_SWELL = 0.12f
private const val AURA_REST = 1.38f
private const val AURA_SWELL = 0.14f
private const val AVATAR_GIVE = 0.045f

private const val WAVE_ALPHA = 0.34f
private val WAVE_WIDTH = 2.5.dp
