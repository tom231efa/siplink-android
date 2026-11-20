package com.sky.siplink.core.logic

import com.sky.siplink.core.model.FeatureCodes
import com.sky.siplink.zh
import org.junit.Assert.assertEquals
import org.junit.Test

class PartyNameTest {
    private val codes = FeatureCodes()
    private val directory = mapOf("1005" to "王芳", "1005@pbx.example.com" to "王芳", "+8613800138000@pbx.example.com" to "张伟")

    private fun name(shown: String, address: String, outgoing: Boolean = false) =
        zh(PartyName.of(shown, address, outgoing, codes) { directory[it] })

    @Test
    fun `the directory knows better than the far end`() {
        assertEquals("王芳", name("Ext 1005", "1005@pbx.example.com"))
        assertEquals("张伟", name("+8613800138000", "+8613800138000@pbx.example.com"))
    }

    @Test
    fun `without the directory it is the name the far end gave`() {
        assertEquals("前台 Reception", name("前台 Reception", "1000@pbx.example.com"))
        assertEquals("O'Brien, \"Bob\"", name("O'Brien, \"Bob\"", "1002@pbx.example.com"))
    }

    @Test
    fun `a name that only repeats the number is the number`() {
        assertEquals("1002", name("1002", "1002@pbx.example.com"))
        assertEquals("1002", name("", "1002@pbx.example.com"))
        assertEquals("02155512345", name("  ", "02155512345@pbx.example.com"))
    }

    @Test
    fun `a caller who withholds the number is called so`() {
        assertEquals("匿名来电", name("anonymous", "anonymous@anonymous.invalid"))
        assertEquals("匿名来电", name("Anonymous", "anonymous@anonymous.invalid"))
        assertEquals("匿名来电", name("Unknown", "unknown@pbx.example.com"))
        // A PBX that withholds the number may still pass a name on.
        assertEquals("门口保安", name("门口保安", "anonymous@pbx.example.com"))
        // And one that passes the number may call it "Anonymous".
        assertEquals("1002", name("Anonymous", "1002@pbx.example.com"))
    }

    @Test
    fun `a feature code we dialled is named after what it does and whom it is for`() {
        assertEquals("对讲 王芳", name("*801005", "*801005@pbx.example.com", outgoing = true))
        assertEquals("对讲 1009", name("*801009", "*801009@pbx.example.com", outgoing = true))
        assertEquals("监听 王芳", name("*911005", "*911005@pbx.example.com", outgoing = true))
        assertEquals("代接 王芳", name("**1005", "**1005@pbx.example.com", outgoing = true))
        assertEquals("语音信箱", name("*97", "*97@pbx.example.com", outgoing = true))
        assertEquals("同组代接", name("*8", "*8@pbx.example.com", outgoing = true))
        assertEquals("开启免打扰", name("*78", "*78@pbx.example.com", outgoing = true))
        assertEquals("关闭免打扰", name("*79", "*79@pbx.example.com", outgoing = true))
        assertEquals("驻留", name("700", "700@pbx.example.com", outgoing = true))
    }

    @Test
    fun `a number that only looks like a feature code is left alone`() {
        // Somebody calling us from such a number did not dial our codes.
        assertEquals("*801005", name("*801005", "*801005@pbx.example.com", outgoing = false))
        // A code that is not configured stands for nothing.
        val none = FeatureCodes(intercom = "", voicemail = "")
        assertEquals("*801005", zh(PartyName.of("*801005", "*801005@pbx.example.com", true, none) { null }))
        assertEquals("*97", zh(PartyName.of("*97", "*97@pbx.example.com", true, none) { null }))
        // A code followed by something that is no extension.
        assertEquals("*80abc", name("*80abc", "*80abc@pbx.example.com", outgoing = true))
    }

    @Test
    fun `an address without a user is shown as it is`() {
        assertEquals("pbx.example.com", name("", "pbx.example.com"))
    }
}
