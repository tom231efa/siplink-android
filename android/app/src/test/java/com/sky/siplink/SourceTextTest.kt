package com.sky.siplink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The texts of the interface live in the string resources of res/values and its languages, not
 * in the code: no string literal of the Kotlin sources holds Chinese, Japanese or Korean
 * characters, but in the files below. Comments may.
 */
class SourceTextTest {
    private val sources = File("src/main/java")

    /** Literals that are data, not texts of the interface: they stay as they are in every language. */
    private val data = mapOf(
        "com/sky/siplink/core/model/Language.kt" to "the names of the languages, each in its own",
        "com/sky/siplink/core/logic/ShortcutRules.kt" to "the Chinese words of the types and icons that the CSV format takes",
    )

    /** Screens whose texts are not moved to the resources yet. The list only shrinks: a file without such a literal leaves it. */
    private val notYet = listOf(
        "com/sky/siplink/ui/AppRoot.kt",
        "com/sky/siplink/ui/MainNavigation.kt",
        "com/sky/siplink/ui/StartupPermissions.kt",
        "com/sky/siplink/ui/accounts/AccountEditScreen.kt",
        "com/sky/siplink/ui/accounts/AccountImportSheet.kt",
        "com/sky/siplink/ui/accounts/AccountsScreen.kt",
        "com/sky/siplink/ui/accounts/LineDiagnosisScreen.kt",
        "com/sky/siplink/ui/call/CallBanners.kt",
        "com/sky/siplink/ui/call/CallControls.kt",
        "com/sky/siplink/ui/call/CallPanels.kt",
        "com/sky/siplink/ui/call/CallScreen.kt",
        "com/sky/siplink/ui/call/RecordControl.kt",
        "com/sky/siplink/ui/call/RoomAddSheet.kt",
        "com/sky/siplink/ui/call/RoomScreen.kt",
        "com/sky/siplink/ui/call/SlideToAnswer.kt",
        "com/sky/siplink/ui/call/VideoStage.kt",
        "com/sky/siplink/ui/call/VideoWindow.kt",
        "com/sky/siplink/ui/components/AccountPicker.kt",
        "com/sky/siplink/ui/components/CallLauncher.kt",
        "com/sky/siplink/ui/components/Components.kt",
        "com/sky/siplink/ui/components/ConfigLocked.kt",
        "com/sky/siplink/ui/components/CsvImportSheet.kt",
        "com/sky/siplink/ui/components/FeatureSheet.kt",
        "com/sky/siplink/ui/components/FormRows.kt",
        "com/sky/siplink/ui/components/LdapRestartDialog.kt",
        "com/sky/siplink/ui/components/SubScreen.kt",
        "com/sky/siplink/ui/dialer/DialPad.kt",
        "com/sky/siplink/ui/dialer/DialerScreen.kt",
        "com/sky/siplink/ui/directory/DirectoryScreen.kt",
        "com/sky/siplink/ui/directory/LdapEditScreen.kt",
        "com/sky/siplink/ui/directory/LdapListScreen.kt",
        "com/sky/siplink/ui/history/HistoryScreen.kt",
        "com/sky/siplink/ui/recordings/RecordingParts.kt",
        "com/sky/siplink/ui/recordings/RecordingsScreen.kt",
        "com/sky/siplink/ui/settings/AboutScreen.kt",
        "com/sky/siplink/ui/settings/AdvancedSettingsScreen.kt",
        "com/sky/siplink/ui/settings/AutoAnswerScreen.kt",
        "com/sky/siplink/ui/settings/CodecsScreen.kt",
        "com/sky/siplink/ui/settings/FeatureCodesScreen.kt",
        "com/sky/siplink/ui/settings/QrScanner.kt",
        "com/sky/siplink/ui/settings/RecordingSettingsScreen.kt",
        "com/sky/siplink/ui/settings/RemoteConfigScreen.kt",
        "com/sky/siplink/ui/settings/SettingsScreen.kt",
        "com/sky/siplink/ui/settings/TlsScreen.kt",
        "com/sky/siplink/ui/settings/UpdateRows.kt",
        "com/sky/siplink/ui/shortcuts/CategoryNameDialog.kt",
        "com/sky/siplink/ui/shortcuts/ShortcutBoard.kt",
        "com/sky/siplink/ui/shortcuts/ShortcutEditSheet.kt",
        "com/sky/siplink/ui/shortcuts/ShortcutRunner.kt",
        "com/sky/siplink/ui/shortcuts/ShortcutsScreen.kt",
    )

    @Test
    fun `no text of the interface is written in the code`() {
        val found = sources.walk().filter { it.isFile && it.extension == "kt" }
            .associate { it.relativeTo(sources).invariantSeparatorsPath to cjkLiterals(it.readText()) }
            .filterValues { it.isNotEmpty() }
        val unexpected = found.filterKeys { it !in data && it !in notYet }
        assertTrue("Texts in the code instead of the resources: $unexpected", unexpected.isEmpty())
        val done = (data.keys + notYet).filter { it !in found }
        assertEquals("Listed, but without such a literal (take them off the list)", emptyList<String>(), done)
    }

    @Test
    fun `the scanner finds literals and leaves comments alone`() {
        val q = "\""
        val code = listOf(
            "// ${q}注释$q",
            "/* ${q}注释$q /* 嵌套 */ */",
            "val a = ${q}English \${ ${q}中文$q } x$q",
            "val b = $q$q${q}原样$q$q$q",
            "val c = '字'",
            "val d = $q\\${q}转义\\$q$q",
        ).joinToString("\n")
        assertEquals(listOf("中文", "原样", "字", "\\\"转义\\\""), cjkLiterals(code))
    }

    companion object {
        /** The Kotlin string and character literals of [code] that hold CJK characters, as they are written. */
        fun cjkLiterals(code: String): List<String> = KotlinLiterals(code).all().filter { literal -> literal.any(::isCjk) }

        fun isCjk(char: Char): Boolean {
            val script = Character.UnicodeScript.of(char.code)
            if (script == Character.UnicodeScript.HAN || script == Character.UnicodeScript.HIRAGANA ||
                script == Character.UnicodeScript.KATAKANA || script == Character.UnicodeScript.HANGUL
            ) {
                return true
            }
            val block = Character.UnicodeBlock.of(char)
            return block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION || block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS
        }
    }
}

/** Reads the literals out of Kotlin code: comments are skipped, templates in strings are code again. */
private class KotlinLiterals(private val code: String) {
    private var i = 0
    private val literals = ArrayList<String>()

    fun all(): List<String> {
        code(endAtBrace = false)
        return literals
    }

    /** Code up to the end, or up to the brace that closes a template. */
    private fun code(endAtBrace: Boolean) {
        var depth = 0
        while (i < code.length) {
            when {
                code.startsWith("//", i) -> i = code.indexOf('\n', i).let { if (it < 0) code.length else it }
                code.startsWith("/*", i) -> blockComment()
                code.startsWith("\"\"\"", i) -> string(raw = true)
                code[i] == '"' -> string(raw = false)
                code[i] == '\'' -> char()
                code[i] == '{' -> {
                    depth++
                    i++
                }
                code[i] == '}' -> {
                    if (endAtBrace && depth == 0) {
                        i++
                        return
                    }
                    depth--
                    i++
                }
                else -> i++
            }
        }
    }

    private fun blockComment() {
        var depth = 0
        while (i < code.length) {
            when {
                code.startsWith("/*", i) -> {
                    depth++
                    i += 2
                }
                code.startsWith("*/", i) -> {
                    depth--
                    i += 2
                    if (depth == 0) return
                }
                else -> i++
            }
        }
    }

    private fun string(raw: Boolean) {
        val quote = if (raw) "\"\"\"" else "\""
        i += quote.length
        val text = StringBuilder()
        while (i < code.length && !code.startsWith(quote, i)) {
            when {
                !raw && code[i] == '\\' -> {
                    text.append(code, i, minOf(i + 2, code.length))
                    i += 2
                }
                code.startsWith("\${", i) -> {
                    i += 2
                    code(endAtBrace = true)
                }
                else -> text.append(code[i++])
            }
        }
        i += quote.length
        literals += text.toString()
    }

    private fun char() {
        val end = code.indexOf('\'', if (code.getOrNull(i + 1) == '\\') i + 3 else i + 2)
        if (end < 0) {
            i++
            return
        }
        literals += code.substring(i + 1, end)
        i = end + 1
    }
}
