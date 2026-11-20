package com.sky.siplink

import com.sky.siplink.core.model.Phrase
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Every text with arguments goes through PhraseFormat, which isolates the values in right-to-left
 * text: the screens fill their texts with stringOf / pluralOf (ui/components/Phrases.kt), and
 * code outside composition with `resources.render(Phrase…)`. Android's own formatting
 * (`stringResource(id, args)`, `pluralStringResource`, `getString(id, args)`,
 * `getQuantityString`) puts a number or an address into an Arabic sentence without isolating it.
 */
class FilledTextTest {
    private val sources = File("src/main/java")

    @Test
    fun `no text is filled by Android's own formatting`() {
        val found = sources.walk().filter { it.isFile && it.extension == "kt" }
            .flatMap { file -> unisolated(file.readText()).map { "${file.relativeTo(sources).invariantSeparatorsPath}: $it" } }
            .toList()
        assertEquals("Texts filled without PhraseFormat (use stringOf / pluralOf, or render a Phrase)", emptyList<String>(), found)
    }

    /**
     * Where a text starts or ends is the screen's business: TextAlign.Start and TextAlign.End
     * follow the text's own direction, and put an address and an Arabic word of one Arabic screen
     * on two different sides. The theme aligns every text to the start of the screen; a column of
     * values at the end takes endAlign() (ui/components/Direction.kt).
     */
    @Test
    fun `no screen aligns a text by its own direction`() {
        val ui = File(sources, "com/sky/siplink/ui")
        val found = ui.walk().filter { it.isFile && it.extension == "kt" && it.name !in OWN_DIRECTION }
            .flatMap { file -> ALIGNED_BY_TEXT.findAll(withoutComments(file.readText())).map { "${file.name}: ${it.value}" } }
            .toList()
        assertEquals("Use the theme's alignment or endAlign()", emptyList<String>(), found)
    }

    @Test
    fun `the scanner finds the calls with arguments and leaves the others alone`() {
        val q = "\""
        val code = listOf(
            "stringResource(R.string.a)",
            "stringResource(if (x) R.string.a else R.string.b)",
            "// stringResource(R.string.a, x)",
            "/* pluralStringResource(R.plurals.a, 1) */",
            "config.getString(SECTION, key, ${q}a, b$q)",
            "string = this::getString",
            "stringResource(R.string.b, f(1, 2))",
            "stringResource(\n    R.string.c,\n    x,\n)",
            "pluralStringResource(R.plurals.d, n, n.toString())",
            "resources.getString(R.string.e, ${q}(${q})",
            "resources.getQuantityString(R.plurals.f, n)",
            "x.substringBefore('(') + stringResource(R.string.g, ')')",
        ).joinToString("\n")
        val calls = unisolated(code).map { it.substringBefore('(') + "(" + it.substringAfter('(').substringBefore(',').substringBefore(')').trim() }
        assertEquals(
            listOf("stringResource(R.string.b", "stringResource(R.string.c", "pluralStringResource(R.plurals.d", "getString(R.string.e", "getQuantityString(R.plurals.f", "stringResource(R.string.g"),
            calls,
        )
    }

    @Test
    fun `in Arabic the values of a filled text are isolated, in other languages they are not`() {
        val arabic = Texts("ar").render(Phrase.of(R.string.main_banner_incoming, "+1 555 0100"))
        assertEquals(true, "\u2068+1 555 0100\u2069" in arabic)
        val french = Texts("fr").render(Phrase.of(R.string.main_banner_incoming, "+1 555 0100"))
        assertEquals(false, '\u2068' in french)
        assertEquals(true, "+1 555 0100" in french)
    }

    @Test
    fun `in Arabic a text without letters of its own runs from the right, one with letters as it is`() {
        val count = Phrase.of(R.string.shortcut_count_of, "3", "50")
        assertEquals("\u200F\u20683\u2069 / \u206850\u2069", Texts("ar").render(count))
        assertEquals("3 / 50", Texts("").render(count))
        val rate = Texts("ar").render(Phrase.of(R.string.codec_rate, "8000"))
        assertEquals(false, rate.startsWith('\u200F'))
    }

    private companion object {
        val ALIGNED_BY_TEXT = Regex("""TextAlign\.(Start|End)\b""")

        /** FadingText: a line that runs on starts where its own direction starts, so that its beginning stays in sight. */
        val OWN_DIRECTION = setOf("FadingText.kt")

        val CALL = Regex("""(?<![\w:])(stringResource|pluralStringResource|getQuantityString|getString)\(""")

        /** The calls of [code] that fill a text by Android's own formatting, as they start. */
        fun unisolated(code: String): List<String> {
            val plain = withoutComments(code)
            return CALL.findAll(plain).mapNotNull { match ->
                val open = match.range.last
                val args = arguments(plain, open)
                val name = match.groupValues[1]
                val filled = when (name) {
                    "pluralStringResource", "getQuantityString" -> true
                    "stringResource" -> args.size > 1
                    else -> args.size > 1 && args[0].startsWith("R.string.")
                }
                if (filled) "$name(${args.joinToString(", ")})" else null
            }.toList()
        }

        /** The top-level arguments of the call whose parenthesis opens at [open]. */
        fun arguments(code: String, open: Int): List<String> {
            val args = ArrayList<String>()
            var depth = 0
            var start = open + 1
            var i = open
            while (i < code.length) {
                val c = code[i]
                when {
                    c == '"' || c == '\'' -> {
                        i = skipLiteral(code, i)
                        continue
                    }
                    c == '(' || c == '[' || c == '{' -> depth++
                    c == ')' || c == ']' || c == '}' -> {
                        depth--
                        if (depth == 0) {
                            code.substring(start, i).trim().takeIf { it.isNotEmpty() }?.let(args::add)
                            return args
                        }
                    }
                    c == ',' && depth == 1 -> {
                        args += code.substring(start, i).trim()
                        start = i + 1
                    }
                }
                i++
            }
            return args
        }

        /** The index after the string or character literal that starts at [at] (templates are not followed: they hold no call of these). */
        fun skipLiteral(code: String, at: Int): Int {
            if (code[at] == '\'') return code.indexOf('\'', at + if (code.getOrNull(at + 1) == '\\') 3 else 2).let { if (it < 0) code.length else it + 1 }
            if (code.startsWith("\"\"\"", at)) return code.indexOf("\"\"\"", at + 3).let { if (it < 0) code.length else it + 3 }
            var i = at + 1
            while (i < code.length && code[i] != '"') i += if (code[i] == '\\') 2 else 1
            return i + 1
        }

        fun withoutComments(code: String): String {
            val out = StringBuilder()
            var i = 0
            while (i < code.length) {
                when {
                    code[i] == '"' || code[i] == '\'' -> {
                        val end = skipLiteral(code, i).coerceAtMost(code.length)
                        out.append(code, i, end)
                        i = end
                    }
                    code.startsWith("//", i) -> i = code.indexOf('\n', i).let { if (it < 0) code.length else it }
                    code.startsWith("/*", i) -> {
                        var depth = 0
                        while (i < code.length) {
                            if (code.startsWith("/*", i)) {
                                depth++
                                i += 2
                            } else if (code.startsWith("*/", i)) {
                                depth--
                                i += 2
                                if (depth == 0) break
                            } else {
                                i++
                            }
                        }
                    }
                    else -> out.append(code[i++])
                }
            }
            return out.toString()
        }
    }
}
