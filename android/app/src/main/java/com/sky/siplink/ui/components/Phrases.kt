package com.sky.siplink.ui.components

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
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

/**
 * A string resource filled with [args], rendered as the phrases of the core are
 * ([com.sky.siplink.core.logic.PhraseFormat]): in a right-to-left language every value is
 * isolated, so that a number or an address keeps its own direction inside the sentence. Screens
 * fill their texts with this, not with `stringResource(id, args)` (SourceTextTest).
 */
@Composable
@ReadOnlyComposable
fun stringOf(@StringRes id: Int, vararg args: Any): String = Phrase.Res(id, args.toList()).text()

/** A plural resource for [count] filled with [args] (the count itself among them when the text shows it), as [stringOf]. */
@Composable
@ReadOnlyComposable
fun pluralOf(@PluralsRes id: Int, count: Int, vararg args: Any): String = Phrase.Count(id, count, args.toList()).text()
