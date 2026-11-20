package com.sky.siplink

import com.sky.siplink.core.model.Language
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The texts of every language the app has (res/values-*) against the default ones (res/values,
 * English): the same keys, the same placeholders, every plural form the language needs, nothing
 * empty; and the languages are those of [Language] and of res/xml/locales_config.xml.
 */
class LanguageCatalogTest {
    private val default = Texts.DEFAULT

    /** Texts that are the same in every language and are kept in the default folder only (translatable="false"). */
    private val fixed: Set<String> = Texts.files("").flatMap { file ->
        val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
        val nodes = root.getElementsByTagName("string")
        (0 until nodes.length).map { nodes.item(it) as org.w3c.dom.Element }.filter { it.getAttribute("translatable") == "false" }.map { it.getAttribute("name") }
    }.toSet()

    private val languages: List<Texts> = Texts.qualifiers().filter { it.isNotEmpty() }.map(::Texts)

    @Test
    fun `the default texts and Simplified Chinese are there`() {
        assertTrue(default.strings.isNotEmpty())
        assertTrue(languages.any { it.qualifier == "b+zh+Hans" })
    }

    @Test
    fun `every language has exactly the keys of the default`() {
        for (texts in languages) {
            assertEquals(texts.qualifier, default.strings.keys - fixed, texts.strings.keys)
            assertEquals(texts.qualifier, default.plurals.keys, texts.plurals.keys)
        }
    }

    @Test
    fun `every text is in the file of its area`() {
        for (qualifier in Texts.qualifiers()) {
            for (file in Texts.files(qualifier)) {
                val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
                val names = listOf("string", "plurals").flatMap { tag ->
                    root.getElementsByTagName(tag).let { nodes -> (0 until nodes.length).map { (nodes.item(it) as org.w3c.dom.Element).getAttribute("name") } }
                }
                val area = file.name.removePrefix("strings").removeSuffix(".xml").removePrefix("_")
                // strings.xml: only what every area shares; strings_{area}.xml: names that start with {area}_.
                val misplaced = if (area.isEmpty()) names.filter { it !in SHARED && !it.startsWith("common_") } else names.filterNot { it.startsWith("${area}_") }
                assertEquals("${file.parentFile?.name}/${file.name}", emptyList<String>(), misplaced)
            }
        }
    }

    @Test
    fun `no text is empty`() {
        for (texts in languages + default) {
            assertEquals(texts.qualifier, emptyList<String>(), texts.strings.filterValues { it.isBlank() }.keys.toList())
            assertEquals(texts.qualifier, emptyList<String>(), texts.plurals.filterValues { forms -> forms.values.any { it.isBlank() } }.keys.toList())
        }
    }

    @Test
    fun `placeholders are numbered strings, the same in every language`() {
        for (texts in languages + default) {
            for ((key, text) in texts.strings) {
                assertWellFormed("${texts.qualifier} $key", text)
                assertEquals("${texts.qualifier} $key", placeholders(default.strings.getValue(key)), placeholders(text))
            }
            for ((key, forms) in texts.plurals) {
                val expected = placeholders(default.plurals.getValue(key).getValue("other"))
                for ((quantity, text) in forms) {
                    assertWellFormed("${texts.qualifier} $key $quantity", text)
                    // A form for one may say "one" in words; it uses no placeholder the default does not have.
                    assertTrue("${texts.qualifier} $key $quantity", expected.containsAll(placeholders(text)))
                }
                assertEquals("${texts.qualifier} $key", expected, placeholders(forms.getValue("other")))
            }
        }
    }

    @Test
    fun `every plural text has the forms of its language`() {
        for (texts in languages + default) {
            val required = PluralRules.required(texts.language)
            for ((key, forms) in texts.plurals) {
                assertEquals("${texts.qualifier} $key", required, forms.keys)
            }
        }
    }

    @Test
    fun `the languages are those of the app, as the system is told them`() {
        val config = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/res/xml/locales_config.xml")).documentElement
        val nodes = config.getElementsByTagName("locale")
        val listed = (0 until nodes.length).map { (nodes.item(it) as org.w3c.dom.Element).getAttribute("android:name") }
        assertEquals(Language.entries.map { it.tag }, listed)
        val tags = Language.entries.map { it.tag }.toSet()
        for (texts in languages) {
            assertTrue("${texts.qualifier} is no language of the app", tagOf(texts.qualifier) in tags)
        }
    }

    @Test
    fun `the plural rules pick the forms the languages have`() {
        assertEquals(listOf("other", "one", "other"), listOf(0, 1, 2).map { PluralRules.category("en", it) })
        assertEquals(listOf("one", "few", "many", "one", "many"), listOf(1, 3, 5, 21, 11).map { PluralRules.category("ru", it) })
        assertEquals(listOf("zero", "one", "two", "few", "many", "other"), listOf(0, 1, 2, 3, 11, 100).map { PluralRules.category("ar", it) })
        assertEquals("other", PluralRules.category("zh", 1))
    }

    /** A placeholder is `%1$s`, `%2$s`, …; a percent sign of a text with placeholders is written `%%`. */
    private fun assertWellFormed(where: String, text: String) {
        if (placeholders(text).isEmpty()) return
        val stray = Regex("%(?!\\d+\\\$s|%)").find(text.replace("%%", ""))
        assertTrue("$where: a % that is no placeholder: $text", stray == null)
    }

    private fun placeholders(text: String): Set<String> = Regex("%\\d+\\\$s").findAll(text.replace("%%", "")).map { it.value }.toSet()

    /** The BCP 47 tag of a resource qualifier: `b+zh+Hans` is zh-Hans, `fr` is fr. */
    private fun tagOf(qualifier: String): String = qualifier.removePrefix("b+").replace('+', '-').replace("-r", "-")

    private companion object {
        /** What strings.xml holds besides the common_* buttons: the texts every area shares. */
        val SHARED = setOf("app_name", "list_separator")
    }
}
