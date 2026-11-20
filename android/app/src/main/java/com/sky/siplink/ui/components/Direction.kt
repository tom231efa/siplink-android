package com.sky.siplink.ui.components

import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection

/**
 * What keeps its physical order in a right-to-left language as in every other: the keys of the
 * dial pad and of the keypad of a call (1 2 3 from left to right), as on every phone, the slide of
 * an incoming call and the video pictures. The rest of the screen follows the direction of the
 * language. The texts in these blocks (digits, the letters under them) are centred in their keys
 * and as wide as they are, so the theme's alignment to the start of the screen cannot show there.
 */
@Composable
fun LeftToRight(content: @Composable () -> Unit) = CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr, content = content)

/**
 * The style of a text field that takes a machine value (a number, a SIP address, a host name,
 * a port): written left to right in every language, so that what is typed stays in the order it
 * is typed in. It stands on the side the screen starts from, as every text does (the theme's
 * typography).
 */
@Composable
@ReadOnlyComposable
fun machineValueStyle(): TextStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr)

/**
 * The alignment of a column of values at the end of their rows: the right, or the left in a
 * right-to-left language, whatever a value is made of (TextAlign.End follows the value's own
 * direction, and would put an address and an Arabic word on two different sides).
 */
@Composable
@ReadOnlyComposable
fun endAlign(): TextAlign = if (LocalLayoutDirection.current == LayoutDirection.Rtl) TextAlign.Left else TextAlign.Right
