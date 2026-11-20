package com.sky.siplink.itest

import android.content.Context
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import com.sky.siplink.R
import java.lang.ref.WeakReference

/**
 * Finds the texts of the app that a screen cuts off, from their text layout: a text that runs out
 * of its box (an ellipsis, a fade, a clip), a text that wraps with one character alone on its
 * last line, and one that wraps inside a word. The app runs in the process of the tests, so its
 * Compose screens are read directly.
 *
 * Only the app's own texts count: what a screen shows is compared with the texts of the
 * language's resources (a text with values in it by the words of its pattern). Data (names,
 * numbers, addresses) may fade as designed and is let through.
 *
 * Not seen: a text that fits its box but touches the edge of what holds it (a segment filled edge
 * to edge), texts drawn without a Text (none in the app), screens narrower than the phone's.
 */
class CutTexts {
    private companion object {
        /** Rounding between the width of a line and the whole pixels of its box. */
        const val SLACK_PX = 1f

        /** Scripts written without spaces between words: a line may end between any two characters. */
        val UNSPACED = setOf(Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA)
    }

    private val roots = ArrayList<WeakReference<ViewRootForTest>>()

    /** Starts noting the Compose screens the app makes from now on: screens, dialogs and sheets. */
    fun watch() {
        ViewRootForTest.onViewCreatedCallback = { root -> synchronized(roots) { roots += WeakReference(root) } }
    }

    fun stop() {
        ViewRootForTest.onViewCreatedCallback = null
    }

    /**
     * What the check made of the screens shown now: [cut], the texts of the app it found cut off;
     * [seen], how many texts of the app it looked at. None seen means it saw nothing (no screen
     * noted, or none whose texts it can read): a check that cannot pass blind fails then.
     */
    class Result(val cut: List<String>, val seen: Int)

    /** Looks at the texts of [texts]' language on the screens shown now; call it on the main thread. */
    fun find(texts: LanguageTexts): Result {
        val shown = synchronized(roots) { roots.mapNotNull { it.get() } }.filter { it.view.isAttachedToWindow && it.view.isShown }
        val found = ArrayList<String>()
        var seen = 0
        for (root in shown) visit(root.semanticsOwner.unmergedRootSemanticsNode) { node ->
            val layout = layoutOf(node) ?: return@visit
            if (!texts.isAppText(layout.layoutInput.text.text)) return@visit
            seen++
            cut(layout)?.let(found::add)
        }
        return Result(found.distinct(), seen)
    }

    private fun visit(node: SemanticsNode, each: (SemanticsNode) -> Unit) {
        each(node)
        node.children.forEach { visit(it, each) }
    }

    /** The text layout of [node], if it shows a text that is not typed into a field (that is data). */
    private fun layoutOf(node: SemanticsNode): TextLayoutResult? {
        val config = node.config
        if (config.getOrNull(SemanticsProperties.EditableText) != null) return null
        val action = config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action ?: return null
        val layouts = ArrayList<TextLayoutResult>()
        if (!action(layouts)) return null
        return layouts.firstOrNull()
    }

    private fun cut(layout: TextLayoutResult): String? {
        val text = layout.layoutInput.text.text
        return when {
            runsOut(layout) -> "\"${LanguageTexts.plain(text)}\" is cut off"
            loneLastCharacter(layout, text) -> "\"${LanguageTexts.plain(text)}\" ends with one character on its last line"
            breaksAWord(layout, text) -> "\"${LanguageTexts.plain(text)}\" breaks a word between two lines"
            else -> null
        }
    }

    /**
     * Whether a line of the text is wider than its box, is cut short by an ellipsis, or the text
     * has more lines than its box shows. (TextLayoutResult.hasVisualOverflow also counts a text
     * laid out across the whole width to be aligned, and shown only as wide as it is: such a text
     * is not cut.)
     */
    private fun runsOut(layout: TextLayoutResult): Boolean = layout.didOverflowHeight ||
        (0 until layout.lineCount).any { line ->
            layout.isLineEllipsized(line) || layout.getLineRight(line) - layout.getLineLeft(line) > layout.size.width + SLACK_PX
        }

    private fun loneLastCharacter(layout: TextLayoutResult, text: String): Boolean {
        if (layout.lineCount < 2) return false
        val last = layout.lineCount - 1
        val line = text.substring(layout.getLineStart(last), layout.getLineEnd(last, visibleEnd = true))
        return LanguageTexts.plain(line).count { it.isLetterOrDigit() } == 1
    }

    /** Whether a line ends inside a word of a script that puts spaces between its words. */
    private fun breaksAWord(layout: TextLayoutResult, text: String): Boolean = (0 until layout.lineCount - 1).any { line ->
        val end = layout.getLineEnd(line)
        end in 1 until text.length && inWord(text[end - 1]) && inWord(text[end])
    }

    private fun inWord(char: Char): Boolean = char.isLetter() && Character.UnicodeScript.of(char.code) !in UNSPACED
}

/**
 * The texts of the app in one language, to tell them from data on a screen: every string and
 * every form of every plural, a text with values by the words around them.
 */
class LanguageTexts(context: Context) {
    private val exact = HashSet<String>()
    private val patterns = ArrayList<Regex>()

    init {
        val resources = context.resources
        val strings = R.string::class.java.fields.map { resources.getString(it.getInt(null)) }
        val plurals = R.plurals::class.java.fields.flatMap { field -> COUNTS.map { resources.getQuantityText(field.getInt(null), it).toString() } }
        for (text in strings + plurals) {
            if (!PLACEHOLDER.containsMatchIn(text)) {
                exact += plain(text)
                continue
            }
            val words = PLACEHOLDER.split(text).map { it.replace("%%", "%") }
            // A pattern of values and punctuation ("%1$s · %2$s") says nothing of its own.
            if (words.none { part -> part.any(Char::isLetter) }) continue
            patterns += Regex(words.joinToString(".*") { Regex.escape(plain(it)) }, RegexOption.DOT_MATCHES_ALL)
        }
    }

    fun isAppText(shown: String): Boolean {
        val text = plain(shown)
        return text in exact || patterns.any { it.matches(text) }
    }

    companion object {
        private val PLACEHOLDER = Regex("%\\d+\\$[sd]")

        /** Counts that reach every plural form of the app's languages. */
        private val COUNTS = listOf(0, 1, 2, 3, 5, 11, 21, 100, 1_000_000)

        /** [text] without the marks that set directions, which a screen adds to values. */
        fun plain(text: String): String = text.filterNot { it == '\u2068' || it == '\u2069' || it == '\u200F' || it == '\u200E' }
    }
}
