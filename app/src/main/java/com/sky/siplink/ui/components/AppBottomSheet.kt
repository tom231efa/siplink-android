package com.sky.siplink.ui.components

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * The bottom sheet of this app. Its content needs no inset padding of its own: the sheet keeps
 * it clear of the navigation bar and of the keyboard.
 *
 * The sheet always ends below the status bar. Material's sheet may slide under it and then pads
 * its content by however much of the status bar it covers, which makes its height depend on how
 * far it has been dragged. A sheet about as tall as the screen then has no stable height: while
 * it is dragged down it jumps between two of them.
 *
 * @param expandFully open at full height straight away instead of half-way first
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppBottomSheet(onDismiss: () -> Unit, expandFully: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)),
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = expandFully),
        content = content,
    )
}
