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
 * dial pad and of the keypad of a call (1 2 3 from left to right), as on every phone. The rest
 * of the screen follows the direction of the language.
 */
@Composable
fun LeftToRight(content: @Composable () -> Unit) = CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr, content = content)

/**
 * The style of a text field that takes a machine value (a number, a SIP address, a host name,
 * a port): written left to right in every language, so that what is typed stays in the order it
 * is typed in. It stands on the side the language starts from, as a field of the system's own
 * (gravity start) does.
 */
@Composable
@ReadOnlyComposable
fun machineValueStyle(): TextStyle = LocalTextStyle.current.copy(
    textDirection = TextDirection.Ltr,
    textAlign = if (LocalLayoutDirection.current == LayoutDirection.Rtl) TextAlign.Right else TextAlign.Left,
)
