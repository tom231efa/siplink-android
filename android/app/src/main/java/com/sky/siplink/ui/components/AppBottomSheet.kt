package com.sky.siplink.ui.components

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp

/**
 * Whether the window is low: a phone on its side. What stands one above the other in a sheet
 * stands side by side there, and a sheet takes all the height there is.
 *
 * Told by the window alone, never by the room something has inside it: the keyboard takes most
 * of that room, and a field that moved into another layout while the keyboard came up would
 * lose it again at once.
 */
@Composable
@ReadOnlyComposable
fun isLowWindow(): Boolean = LocalWindowInfo.current.containerDpSize.height < LowWindowBelow

/** A phone upright is higher than this, a phone on its side lower. */
private val LowWindowBelow = 600.dp

/** What a sheet without a handle keeps clear above its content. */
private val LowWindowTopGap = 12.dp

/**
 * The bottom sheet of this app. Its content needs no inset padding of its own: the sheet keeps
 * it clear of the navigation bar and of the keyboard.
 *
 * The sheet always ends below the status bar. Material's sheet may slide under it and then pads
 * its content by however much of the status bar it covers, which makes its height depend on how
 * far it has been dragged. A sheet about as tall as the screen then has no stable height: while
 * it is dragged down it jumps between two of them.
 *
 * In a low window ([isLowWindow]) the keyboard leaves a sheet hardly more than one field. The
 * sheet has no handle there and opens at its full height straight away, so that the field that
 * is typed into has that room; what a sheet shows above its field has to scroll away.
 *
 * @param expandFully open at full height straight away instead of half-way first
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppBottomSheet(onDismiss: () -> Unit, expandFully: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    val low = isLowWindow()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)),
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = expandFully || low),
        dragHandle = if (low) null else ({ BottomSheetDefaults.DragHandle() }),
    ) {
        if (low) Spacer(Modifier.height(LowWindowTopGap))
        content()
    }
}
