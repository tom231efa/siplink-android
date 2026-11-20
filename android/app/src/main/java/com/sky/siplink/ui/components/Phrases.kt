package com.sky.siplink.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.sky.siplink.core.model.Phrase
import com.sky.siplink.core.render

/** A [Phrase] of the core in the app's language; read again when the configuration (the language) changes. */
@Composable
@ReadOnlyComposable
fun Phrase.text(): String {
    LocalConfiguration.current
    return LocalContext.current.render(this)
}
