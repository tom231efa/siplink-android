package com.sky.siplink.core.model

/**
 * The languages the app speaks, the same as the management platform's. [tag] is the BCP 47 tag
 * the app's language is set with (res/xml/locales_config.xml lists the same); [ownName] is how the
 * language calls itself, which is how the language setting lists it in every language.
 */
enum class Language(val tag: String, val ownName: String) {
    ENGLISH("en", "English"),
    CHINESE_SIMPLIFIED("zh-Hans", "简体中文"),
    CHINESE_TRADITIONAL("zh-Hant", "繁體中文"),
    JAPANESE("ja", "日本語"),
    ARABIC("ar", "العربية"),
    FRENCH("fr", "Français"),
    RUSSIAN("ru", "Русский"),
    SPANISH("es", "Español"),
    ;

    companion object {
        /**
         * The language of the app a chosen locale stands for; null for one the app does not
         * speak. [script] is the script of the locale with its likely subtags added (zh-TW is
         * written in Hant): it tells the two kinds of Chinese apart.
         */
        fun of(language: String, script: String): Language? = entries.firstOrNull {
            val (ownLanguage, ownScript) = it.tag.split('-').let { parts -> parts[0] to parts.getOrElse(1) { "" } }
            ownLanguage == language && (ownScript.isEmpty() || ownScript == script)
        }
    }
}
