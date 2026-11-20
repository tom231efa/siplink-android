package com.sky.siplink.core

import android.content.Context
import android.view.View
import androidx.annotation.StringRes
import com.sky.siplink.core.logic.PhraseFormat
import com.sky.siplink.core.model.Phrase

/**
 * Renders the texts of the core and of the notifications in the app's language ([AppLanguage]).
 * What is rendered goes into a snapshot for the screens or a notification, never into a store;
 * the managers publish again when the language changes ([SipEngine.onLanguageChanged]).
 */
class AppTexts(private val language: AppLanguage) {
    fun render(phrase: Phrase): String = language.localized().render(phrase)

    fun get(@StringRes id: Int, vararg args: Any): String = render(Phrase.of(id, *args))
}

/** [phrase] in the language of this context's resources (an activity's: the app's language). */
fun Context.render(phrase: Phrase): String {
    val resources = resources
    return PhraseFormat(
        string = resources::getString,
        plural = resources::getQuantityString,
        rtl = resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL,
    ).render(phrase)
}
