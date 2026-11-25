@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)

package com.sky.siplink.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.sky.siplink.ui.Motion

/**
 * What stands above a field and gives way to the keyboard in a low window ([isLowWindow]): what
 * the keyboard leaves of a phone on its side is hardly higher than one field, and the field that
 * is typed into has to stay in sight, whole. It is back as soon as the keyboard is gone.
 *
 * It stands above what holds the fields, never around it: its going leaves the field as it is,
 * and the focus and the keyboard with it.
 */
@Composable
fun ColumnScope.AwayWhileTyping(content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = !isTypingInLowWindow(),
        enter = expandVertically(Motion.spatial()) + fadeIn(Motion.fade(160)),
        exit = shrinkVertically(Motion.spatial()) + fadeOut(Motion.fade(100)),
    ) { content() }
}

/** The top bar of a page that has fields: it gives way to the keyboard as [AwayWhileTyping] says. */
@Composable
fun ColumnScope.PageTopBar(bar: @Composable () -> Unit) {
    AwayWhileTyping(bar)
    // Without its bar the page still starts below the status bar, as the bar kept it.
    AnimatedVisibility(
        visible = isTypingInLowWindow(),
        enter = expandVertically(Motion.spatial()),
        exit = shrinkVertically(Motion.spatial()),
    ) { Spacer(Modifier.windowInsetsTopHeight(TopAppBarDefaults.windowInsets)) }
}

@Composable
private fun isTypingInLowWindow(): Boolean = isLowWindow() && WindowInsets.isImeVisible
