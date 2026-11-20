package com.sky.siplink.core

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.icu.util.ULocale
import android.os.Build
import android.os.LocaleList
import androidx.core.content.edit
import com.sky.siplink.core.model.Language

/**
 * The language of the app's texts: one of [Language], or the system's ([chosen] null), which is
 * the default. Which resources a language list gets is Android's own resolution: a language the
 * app does not have falls through to the next one of the list, and to English (`values/`).
 *
 * Android 13 and later keep the choice themselves (per-app language, [LocaleManager]): the
 * setting in the app and the system's page of the app's language are one value, and the system
 * hands the activities a configuration in that language. Before Android 13 the choice is kept in
 * the app's own preferences and put into the configuration of the activities ([wrap]).
 *
 * The texts rendered outside the activities ([localized]) are rendered in the languages read
 * from that setting, not in the configuration of the process: a process without an activity was
 * seen to fall back to the system's languages while the app had its own (Android 16).
 */
class AppLanguage(private val app: Context) {
    /**
     * Called on the main thread when the languages of the texts changed, chosen in the app or
     * found by [refresh]. [recreate]: the activities still show the languages they were made in
     * and are to be made anew (before Android 13, a choice in the app).
     */
    var onChanged: ((recreate: Boolean) -> Unit)? = null

    /** The languages the texts are rendered in: the app's own, or the system's. */
    private var current: LocaleList = effective()
    private var localizedContext: Context? = null

    init {
        if (!SYSTEM_KEEPS_IT) LocaleList.setDefault(current)
    }

    /** The app's own language list; empty: the system's languages. */
    private fun locales(): LocaleList =
        if (SYSTEM_KEEPS_IT) app.getSystemService(LocaleManager::class.java).applicationLocales else storedLocales(app) ?: LocaleList.getEmptyLocaleList()

    /** The languages of the texts for the app's own list [own]. */
    private fun effective(own: LocaleList = locales()): LocaleList = own.takeIf { !it.isEmpty } ?: systemLocales(app)

    /** Whether the app follows the system's languages. */
    val followsSystem: Boolean get() = locales().isEmpty

    /** The language chosen for the app; null while it follows the system, or for a language the app does not speak. */
    val chosen: Language? get() = locales().takeIf { !it.isEmpty }?.get(0)?.let { Language.of(it.language, ULocale.addLikelySubtags(ULocale.forLocale(it)).script) }

    /** Sets the language of the app; null: the system's. */
    fun choose(language: Language?) {
        val own = LocaleList.forLanguageTags(language?.tag.orEmpty())
        if (SYSTEM_KEEPS_IT) {
            app.getSystemService(LocaleManager::class.java).applicationLocales = own
        } else {
            app.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit(commit = true) { putString(KEY_TAGS, own.toLanguageTags()) }
        }
        update(effective(own), recreate = !SYSTEM_KEEPS_IT)
    }

    /** Reads the languages again, after a change of the configuration: the system's, or (Android 13 and later) the app's on the system's page. */
    fun refresh() = update(effective(), recreate = false)

    private fun update(now: LocaleList, recreate: Boolean) {
        if (now == current) return
        current = now
        localizedContext = null
        if (!SYSTEM_KEEPS_IT) LocaleList.setDefault(now)
        onChanged?.invoke(recreate)
    }

    /** A context of the app whose resources are in the app's languages, for texts rendered outside the activities. */
    fun localized(): Context = localizedContext ?: app.createConfigurationContext(
        Configuration(app.resources.configuration).apply {
            setLocales(current)
            setLayoutDirection(current[0])
        },
    ).also { localizedContext = it }

    companion object {
        /** Android 13 and later keep the language of an app themselves. */
        private val SYSTEM_KEEPS_IT = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        private const val PREFERENCES = "app_language"
        private const val KEY_TAGS = "tags"

        private fun storedTags(context: Context): String =
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(KEY_TAGS, null).orEmpty()

        /** The system's languages, without the app's own (from Android 13 on, [LocaleManager.getSystemLocales]). */
        fun systemLocales(context: Context): LocaleList =
            if (SYSTEM_KEEPS_IT) context.getSystemService(LocaleManager::class.java).systemLocales else Resources.getSystem().configuration.locales

        private fun storedLocales(context: Context): LocaleList? = storedTags(context).takeIf { it.isNotEmpty() }?.let(LocaleList::forLanguageTags)

        /**
         * [base] in the app's language, for attachBaseContext of the activities. Before Android
         * 13 only, and only while a language is chosen; otherwise [base] itself.
         */
        fun wrap(base: Context): Context {
            if (SYSTEM_KEEPS_IT) return base
            val locales = storedLocales(base) ?: return base
            val configuration = Configuration(base.resources.configuration)
            configuration.setLocales(locales)
            configuration.setLayoutDirection(locales[0])
            return base.createConfigurationContext(configuration)
        }
    }
}
