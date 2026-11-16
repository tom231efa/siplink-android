package com.sky.siplink.core.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class JsonTest {
    @Test
    fun valuesAreReadWithTheirTypes() {
        val value = Json.parse("""{"a": 1, "b": -2.5, "c": "x\"\u00e9\n", "d": [true, false, null], "e": {}, "f": 1e2}""")
        assertEquals(
            mapOf("a" to 1L, "b" to -2.5, "c" to "x\"é\n", "d" to listOf(true, false, null), "e" to emptyMap<String, Any?>(), "f" to 100.0),
            value,
        )
        assertEquals(listOf("中文", 0L), Json.parse(" [ \"中文\" , 0 ] "))
    }

    @Test
    fun whatIsNotJsonIsRefused() {
        listOf("", "{", "[1,]", "{\"a\" 1}", "01", "1.", "tru", "\"\u0001\"", "{} {}", "{'a':1}", "\"\\x\"").forEach { text ->
            assertThrows(text, Json.SyntaxException::class.java) { Json.parse(text) }
        }
    }

    @Test
    fun nestingIsLimited() {
        assertThrows(Json.SyntaxException::class.java) { Json.parse("[".repeat(100) + "]".repeat(100)) }
    }

    @Test
    fun writtenTextReadsBackTheSame() {
        val value = linkedMapOf("text" to "a\"b\\c\n\t\u0002中", "n" to 42L, "x" to -2.5, "list" to listOf(true, null, "x"), "map" to linkedMapOf("k" to false))
        val text = Json.write(value)
        assertEquals("""{"text":"a\"b\\c\n\t\u0002中","n":42,"x":-2.5,"list":[true,null,"x"],"map":{"k":false}}""", text)
        assertEquals(value, Json.parse(text))
    }
}
