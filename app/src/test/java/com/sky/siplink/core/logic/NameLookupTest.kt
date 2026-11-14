package com.sky.siplink.core.logic

import com.sky.siplink.core.model.DirectoryContact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NameLookupTest {
    private fun contact(name: String, number: String) = DirectoryContact(name, number, number)

    @Test
    fun `spellings of one number share a key`() {
        assertEquals("1002", NameLookup.key("1002"))
        assertEquals("1002", NameLookup.key("sip:1002@pbx.example.com"))
        assertEquals("1002", NameLookup.key(" 1002@192.168.1.9 "))
        assertEquals("8613800001006", NameLookup.key("+86 138-0000-1006"))
        assertEquals("075588881006", NameLookup.key("(0755) 8888.1006"))
        assertEquals("alice", NameLookup.key("sip:Alice@example.org"))
    }

    @Test
    fun `nothing is looked up for withheld numbers and feature codes`() {
        assertNull(NameLookup.key(""))
        assertNull(NameLookup.key("anonymous@anonymous.invalid"))
        assertNull(NameLookup.key("Unknown"))
        assertNull(NameLookup.key("*801004"))
        assertNull(NameLookup.key("sip:#9@pbx.example.com"))
    }

    @Test
    fun `a long number is the same line with or without a prefix`() {
        assertTrue(NameLookup.sameLine("1002", "1002"))
        assertTrue(NameLookup.sameLine("13800001006", "8613800001006"))
        assertTrue(NameLookup.sameLine("013800001006", "13800001006"))
        // An extension is not the tail of someone's outside number.
        assertFalse(NameLookup.sameLine("1006", "13800001006"))
        assertFalse(NameLookup.sameLine("881006", "075588881006"))
        assertFalse(NameLookup.sameLine("13800001006", "13800001007"))
        assertFalse(NameLookup.sameLine("alicesmith", "xalicesmith"))
    }

    @Test
    fun `the entry with exactly the number goes before one with a prefix`() {
        val contacts = listOf(
            contact("带前缀", "0086 138 0000 1006"),
            contact("王芳", "138-0000-1006"),
            contact("别人", "13800001007"),
        )
        assertEquals("王芳", NameLookup.pick("13800001006", contacts)?.name)
        assertEquals("带前缀", NameLookup.pick("008613800001006", contacts)?.name)
        assertEquals("王芳", NameLookup.pick("013800001006", contacts.drop(1))?.name)
        assertNull(NameLookup.pick("1006", contacts))
        assertNull(NameLookup.pick("13900001006", contacts))
    }

    @Test
    fun `the server is asked for the number and then for the end of a long one`() {
        assertEquals(listOf("1002"), NameLookup.queries("1002"))
        assertEquals(listOf("12345678"), NameLookup.queries("12345678"))
        assertEquals(listOf("013800001006", "00001006"), NameLookup.queries("013800001006"))
        assertEquals(listOf("alice.smith"), NameLookup.queries("alice.smith"))
    }
}
