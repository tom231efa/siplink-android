package com.sky.siplink.itest

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.test.platform.app.InstrumentationRegistry
import com.sky.siplink.core.AppLanguage
import com.sky.siplink.core.model.Phrase
import com.sky.siplink.core.render

/** [phrase] in the user's Simplified Chinese texts, whatever the language of the app. */
fun zh(phrase: Phrase): String = textsIn("zh-Hans").render(phrase)

/** A context of the app whose texts are in [tags], a language list as the system's settings make one. */
fun textsIn(tags: String): Context = InstrumentationRegistry.getInstrumentation().targetContext.let { context ->
    context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocales(LocaleList.forLanguageTags(tags)) })
}

/** The phone's own language list, as its system settings have it, whatever the language of the app. */
fun systemLanguages(): String = AppLanguage.systemLocales(InstrumentationRegistry.getInstrumentation().targetContext).toLanguageTags()
