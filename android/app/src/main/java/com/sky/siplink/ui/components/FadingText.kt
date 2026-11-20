package com.sky.siplink.ui.components

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Text that someone else supplies (an address, a name, a host) and that may be longer than its
 * place: where it does not fit, the end of its last line fades out instead of ending in an
 * ellipsis or a hard cut.
 *
 * The fade takes the text's own transparency away, so it works on any background, the dark call
 * screen included. It is drawn only while the text does not fit; a text that fits is drawn as a
 * plain [Text]. It sits at the end the line runs out at: the right, or the left for right-to-left
 * text. A line that runs on keeps its beginning in sight: it starts where its own direction
 * starts (an address in an Arabic row at the left), not where the screen aligns a text that fits.
 *
 * @param maxLines one, the usual: the line runs on and is cut. More: the text wraps, and the last
 *   of these lines fades when there is more to come
 * @param fade how wide the fading part is
 */
@Composable
fun FadingText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    style: TextStyle = LocalTextStyle.current,
    fontWeight: FontWeight? = null,
    textAlign: TextAlign? = null,
    maxLines: Int = 1,
    fade: Dp = 28.dp,
) {
    // Where the text is cut, written while laying out and read while drawing: the same frame shows it.
    val cut = remember { mutableStateOf<Cut?>(null) }
    // Whether the one line runs on past the box: read from the last layout of this text.
    val runsOn = remember(text) { mutableStateOf(false) }
    Text(
        text,
        modifier = modifier.drawWithContent {
            val at = cut.value
            if (at == null) {
                drawContent()
                return@drawWithContent
            }
            val end = at.end.coerceIn(0f, size.width)
            val start = if (at.rightToLeft) end + fade.toPx() else end - fade.toPx()
            // Drawn offscreen, so that the mask takes away only the text's own pixels.
            drawContext.canvas.saveLayer(Rect(0f, 0f, size.width, size.height), Paint())
            drawContent()
            drawRect(
                Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), startX = start, endX = end),
                topLeft = Offset(minOf(start, end), at.top),
                size = Size(fade.toPx(), at.bottom - at.top),
                blendMode = BlendMode.DstIn,
            )
            drawContext.canvas.restore()
        },
        color = color,
        style = style,
        fontWeight = fontWeight,
        textAlign = if (runsOn.value) TextAlign.Start else textAlign,
        maxLines = maxLines,
        softWrap = maxLines > 1,
        overflow = TextOverflow.Clip,
        onTextLayout = { layout ->
            cut.value = Cut.of(layout, maxLines)
            runsOn.value = maxLines == 1 && linesRunOut(layout)
        },
    )
}

/**
 * The end of the line a text is cut at: [end] is where that line runs out (the box's edge for a
 * line that runs on past it), [top] and [bottom] bound the line.
 */
private data class Cut(val end: Float, val top: Float, val bottom: Float, val rightToLeft: Boolean) {
    companion object {
        fun of(layout: TextLayoutResult, maxLines: Int): Cut? {
            val cut = if (maxLines == 1) linesRunOut(layout) else layout.didOverflowHeight
            if (!cut || layout.lineCount == 0) return null
            val line = layout.lineCount - 1
            val rightToLeft = layout.getParagraphDirection(layout.getLineStart(line)) == ResolvedTextDirection.Rtl
            return Cut(
                end = if (rightToLeft) layout.getLineLeft(line) else layout.getLineRight(line),
                top = layout.getLineTop(line),
                bottom = layout.getLineBottom(line),
                rightToLeft = rightToLeft,
            )
        }
    }
}

/**
 * Whether the one line of [layout] is wider than its box. (didOverflowWidth also holds for a text
 * laid out across the whole width to be aligned and shown only as wide as it is, which fits.)
 */
private fun linesRunOut(layout: TextLayoutResult): Boolean =
    layout.lineCount > 0 && layout.getLineRight(0) - layout.getLineLeft(0) > layout.size.width + 1f
