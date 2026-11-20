package com.sky.siplink.core.logic

import com.sky.siplink.R
import com.sky.siplink.Texts
import com.sky.siplink.core.model.Language
import com.sky.siplink.core.model.Phrase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhraseFormatTest {
    private val english = Texts.DEFAULT
    private val chinese = Texts.ZH

    @Test
    fun `a phrase is rendered from the texts of its language, with its arguments`() {
        val phrase = Phrase.of(R.string.remote_platform_unavailable, 503)
        assertEquals("Platform temporarily unavailable (HTTP 503)", english.render(phrase))
        assertEquals("平台暂时不可用（HTTP 503）", chinese.render(phrase))
    }

    @Test
    fun `arguments may be phrases themselves, and plain text is shown as it is`() {
        val phrase = Phrase.of(R.string.remote_error_account, "总机", Phrase.of(R.string.account_error_username_empty))
        assertEquals("Account “总机”: User name is required", english.render(phrase))
        assertEquals("账号“总机”：用户名必填", chinese.render(phrase))
        assertEquals("100% plain", english.render(Phrase.plain("100% plain")))
    }

    @Test
    fun `a count picks the form of its language`() {
        assertEquals("At most 1 character", english.render(FormRules.maxChars(1)))
        assertEquals("At most 24 characters", english.render(FormRules.maxChars(24)))
        assertEquals("最多 24 个字符", chinese.render(FormRules.maxChars(24)))
    }

    @Test
    fun `a text without arguments is taken as it is`() {
        assertEquals("需包含 %", chinese.render(Phrase.of(R.string.directory_error_placeholder)))
    }

    @Test
    fun `numbers keep Western digits, and right to left each value is isolated`() {
        val format = PhraseFormat(string = { "يجب %1\$s–%2\$s" }, plural = { _, _ -> "" }, rtl = true)
        assertEquals("يجب ⁨200⁩–⁨10000⁩", format.render(Phrase.of(R.string.account_error_timeout_range, 200, 10_000)))
        val leftToRight = PhraseFormat(string = { "%1\$s ms" }, plural = { _, _ -> "" }, rtl = false)
        assertEquals("1500 ms", leftToRight.render(Phrase.of(R.string.account_error_timeout_range, 1500)))
    }

    @Test
    fun `a phrase is kept by the names of its resources, and comes back`() {
        val names = mapOf(R.string.remote_invalid to "remote_invalid", R.plurals.form_max_chars to "form_max_chars")
        val codec = PhraseCodec(names::getValue) { name, _ -> names.entries.firstOrNull { it.value == name }?.key }
        val phrases = listOf(Phrase.of(R.string.remote_invalid, FormRules.maxChars(24)), Phrase.plain("x"))
        val text = codec.encode(phrases)
        assertEquals(
            """[{"string":"remote_invalid","args":[{"plural":"form_max_chars","count":24,"args":["24"]}]},{"text":"x"}]""",
            text,
        )
        assertEquals("远程配置无效：最多 24 个字符", chinese.render(codec.decode(text)!!.first()))
        // What an older version kept is shown as it was; a resource that is gone is left out.
        assertEquals(listOf(Phrase.plain("平台返回错误（HTTP 400）")), codec.decode("平台返回错误（HTTP 400）"))
        assertNull(codec.decode("""[{"string":"gone","args":[]}]"""))
        assertNull(codec.decode(""))
    }

    @Test
    fun `a chosen locale names a language of the app by its script`() {
        assertEquals(Language.CHINESE_SIMPLIFIED, Language.of("zh", "Hans"))
        assertEquals(Language.CHINESE_TRADITIONAL, Language.of("zh", "Hant"))
        assertEquals(Language.ENGLISH, Language.of("en", "Latn"))
        assertEquals(Language.ARABIC, Language.of("ar", "Arab"))
        assertNull(Language.of("de", "Latn"))
    }
}
