package com.sky.siplink.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * A row of options of which exactly one is chosen. The chosen one is told apart by colour
 * alone: a filled pill that glides over to it, without a check mark.
 *
 * @param options each value with the text it is shown as
 * @param dimmed options drawn grey: they can still be tapped, and [onSelect] decides what a tap does
 * @param track colour of the row, [thumb] of the pill, [content] of the texts and
 *   [selectedContent] of the text on the pill
 */
@Composable
fun <T> ChoiceRow(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    track: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    thumb: Color = MaterialTheme.colorScheme.primary,
    content: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    selectedContent: Color = MaterialTheme.colorScheme.onPrimary,
    dimmed: (T) -> Boolean = { false },
) {
    val index = options.indexOfFirst { it.first == selected }.coerceAtLeast(0)
    // Straight there, without a bounce. Read while placing, so the glide does not recompose.
    val position by animateFloatAsState(index.toFloat(), spring(dampingRatio = 1f, stiffness = 600f), label = "choice")

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(RowHeight)
            .clip(CircleShape)
            .background(track)
            .padding(Inset),
    ) {
        val cell = maxWidth / options.size
        val cellPx = with(LocalDensity.current) { cell.toPx() }
        Box(
            Modifier
                .offset { IntOffset((cellPx * position).roundToInt(), 0) }
                .width(cell)
                .fillMaxHeight()
                .clip(CircleShape)
                .background(thumb),
        )
        Row(
            Modifier
                .fillMaxSize()
                .selectableGroup(),
        ) {
            options.forEachIndexed { at, (value, label) ->
                val color by animateColorAsState(if (at == index) selectedContent else content, label = "choiceText")
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .selectable(
                            selected = at == index,
                            role = Role.RadioButton,
                            interactionSource = remember { MutableInteractionSource() },
                            // The pill is the feedback.
                            indication = null,
                            onClick = { onSelect(value) },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        color = if (dimmed(value)) color.copy(alpha = 0.38f) else color,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private val RowHeight = 42.dp
private val Inset = 3.dp
