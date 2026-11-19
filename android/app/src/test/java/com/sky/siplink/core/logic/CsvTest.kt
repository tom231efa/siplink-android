package com.sky.siplink.core.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CsvTest {
    @Test
    fun `plain fields are trimmed and empty ones are kept`() {
        assertEquals(listOf("a", "b", "", "d"), Csv.split(" a , b ,, d "))
        assertEquals(listOf(""), Csv.split(""))
    }

    @Test
    fun `quoted fields keep commas spaces and doubled quotes`() {
        assertEquals(listOf("x, y", " z ", "say \"hi\""), Csv.split("\"x, y\",\" z \",\"say \"\"hi\"\"\""))
    }

    @Test
    fun `an open quote is reported as null`() {
        assertNull(Csv.split("a,\"b"))
    }

    @Test
    fun `join quotes only what needs it and split reads it back`() {
        val fields = listOf("前台", "a,b", "say \"hi\"", " padded ", "")
        val line = Csv.join(fields)
        assertEquals("前台,\"a,b\",\"say \"\"hi\"\"\",\" padded \",", line)
        assertEquals(fields, Csv.split(line))
    }

    @Test
    fun `rows skip blank lines comments and a byte order mark and keep line numbers`() {
        val rows = Csv.rows("\uFEFFa,b\n\n# comment\n  c,d  \n\"open").toList()
        assertEquals(listOf(1, 4, 5), rows.map { it.first })
        assertEquals(listOf("a", "b"), rows[0].second)
        assertEquals(listOf("c", "d"), rows[1].second)
        assertNull(rows[2].second)
    }
}
