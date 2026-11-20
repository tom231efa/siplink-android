package com.sky.siplink

import com.sky.siplink.core.logic.PhraseFormat
import com.sky.siplink.core.model.Phrase
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The texts of the app in one language, read from its resource files (src/main/res) on the JVM,
 * so that a test asserts on the real texts with their placeholders filled. A text the language
 * does not have comes from the default one (`values/`), as on the phone.
 *
 * @param qualifier the language part of the folder: "" for `values`, `b+zh+Hans` for `values-b+zh+Hans`
 */
class Texts(val qualifier: String) {
    val strings: Map<String, String>
    val plurals: Map<String, Map<String, String>>

    init {
        val strings = HashMap<String, String>()
        val plurals = HashMap<String, Map<String, String>>()
        for (file in files(qualifier)) {
            val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
            for (element in root.children("string")) strings[element.getAttribute("name")] = unescape(element.textContent)
            for (element in root.children("plurals")) {
                plurals[element.getAttribute("name")] = element.children("item").associate { it.getAttribute("quantity") to unescape(it.textContent) }
            }
        }
        this.strings = strings
        this.plurals = plurals
    }

    /** The language of the folder: "en" for the default one. */
    val language: String get() = qualifier.removePrefix("b+").substringBefore('+').substringBefore("-r").ifEmpty { "en" }

    fun render(phrase: Phrase): String = PhraseFormat(
        string = { id -> stringOf(STRING_NAMES.getValue(id)) },
        plural = { id, count -> pluralOf(PLURAL_NAMES.getValue(id), count) },
        rtl = language in RTL,
    ).render(phrase)

    private fun stringOf(name: String): String =
        strings[name] ?: (if (qualifier.isEmpty()) null else DEFAULT.strings[name]) ?: error("No string $name")

    private fun pluralOf(name: String, count: Int): String {
        val forms = plurals[name] ?: DEFAULT.plurals[name] ?: error("No plurals $name")
        return forms[PluralRules.category(language, count)] ?: forms.getValue("other")
    }

    companion object {
        val RES = File("src/main/res")
        val DEFAULT by lazy { Texts("") }
        val ZH by lazy { Texts("b+zh+Hans") }

        /** The languages written right to left among those the app may get. */
        val RTL = setOf("ar")

        /** The folders of the languages the app has, by their qualifier ("" for the default one). */
        fun qualifiers(): List<String> = RES.listFiles().orEmpty()
            .filter { it.isDirectory && (it.name == "values" || it.name.startsWith("values-")) && files(qualifierOf(it.name)).isNotEmpty() }
            .map { qualifierOf(it.name) }
            .sorted()

        private fun qualifierOf(folder: String): String = folder.removePrefix("values").removePrefix("-")

        fun files(qualifier: String): List<File> =
            File(RES, if (qualifier.isEmpty()) "values" else "values-$qualifier").listFiles { file -> file.name.startsWith("strings") && file.name.endsWith(".xml") }
                .orEmpty().sortedBy { it.name }

        private val STRING_NAMES: Map<Int, String> by lazy { R.string::class.java.fields.associate { it.getInt(null) to it.name } }
        private val PLURAL_NAMES: Map<Int, String> by lazy { R.plurals::class.java.fields.associate { it.getInt(null) to it.name } }

        /** What Android's resources make of a text: quotes around it kept its spaces, a backslash escapes the next character. */
        fun unescape(raw: String): String {
            val text = raw.trim().let { if (it.length >= 2 && it.startsWith('"') && it.endsWith('"')) it.substring(1, it.length - 1) else it }
            val out = StringBuilder()
            var i = 0
            while (i < text.length) {
                val c = text[i]
                if (c == '\\' && i + 1 < text.length) {
                    out.append(if (text[i + 1] == 'n') '\n' else if (text[i + 1] == 't') '\t' else text[i + 1])
                    i += 2
                } else {
                    out.append(c)
                    i++
                }
            }
            return out.toString()
        }

        private fun Element.children(tag: String): List<Element> {
            val nodes = getElementsByTagName(tag)
            return (0 until nodes.length).map { nodes.item(it) as Element }
        }
    }
}

/** [phrase] in the user's Simplified Chinese texts; null stays null. */
fun zh(phrase: Phrase?): String? = phrase?.let(Texts.ZH::render)

/**
 * The plural categories of the app's languages for whole numbers (CLDR, unicode.org/cldr: Language
 * Plural Rules), for the counts the app shows: what form a count takes, and which forms a
 * language has to have.
 */
object PluralRules {
    fun category(language: String, n: Int): String = when (language) {
        "zh", "ja" -> "other"
        "en" -> if (n == 1) "one" else "other"
        "fr" -> when {
            n == 0 || n == 1 -> "one"
            n != 0 && n % 1_000_000 == 0 -> "many"
            else -> "other"
        }
        "es" -> when {
            n == 1 -> "one"
            n != 0 && n % 1_000_000 == 0 -> "many"
            else -> "other"
        }
        "ru" -> when {
            n % 10 == 1 && n % 100 != 11 -> "one"
            n % 10 in 2..4 && n % 100 !in 12..14 -> "few"
            else -> "many"
        }
        "ar" -> when {
            n == 0 -> "zero"
            n == 1 -> "one"
            n == 2 -> "two"
            n % 100 in 3..10 -> "few"
            n % 100 in 11..99 -> "many"
            else -> "other"
        }
        else -> error("No plural rules for $language")
    }

    /** The forms every plural text of [language] has to have. */
    fun required(language: String): Set<String> = when (language) {
        "zh", "ja" -> setOf("other")
        "en" -> setOf("one", "other")
        "fr", "es" -> setOf("one", "many", "other")
        "ru" -> setOf("one", "few", "many", "other")
        "ar" -> setOf("zero", "one", "two", "few", "many", "other")
        else -> error("No plural rules for $language")
    }
}

/** The names a category of shortcut keys may not have, in Simplified Chinese ([com.sky.siplink.core.logic.ShortcutRules.RESERVED_CATEGORIES]). */
val zhReserved: Set<String> by lazy { com.sky.siplink.core.logic.ShortcutRules.RESERVED_CATEGORIES.mapTo(HashSet()) { Texts.ZH.render(Phrase.of(it)) } }
