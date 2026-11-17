package com.sky.siplink.core.logic

import com.sky.siplink.core.model.Recording
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class RecordingRulesTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val mb = 1024L * 1024

    private fun at(text: String): Long = LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()

    private fun recording(id: String, startedAt: Long, bytes: Long = mb, number: String = "1001", file: String = "$id.m4a") =
        Recording(id, file, number, "", incoming = true, line = "1001@pbx.example.com", startedAt = startedAt, durationMs = 60_000, bytes = bytes)

    @Test
    fun theOldestGoBeyondTheCount() {
        val all = (1..5).map { recording("r$it", it * 1000L) }
        assertEquals(listOf("r1", "r2"), RecordingRules.overflow(all.shuffled(), keep = 3).map { it.id })
        assertEquals(emptyList<Recording>(), RecordingRules.overflow(all, keep = 5))
    }

    @Test
    fun theOldestGoBeyondTheSize() {
        val all = listOf(recording("old", 1, bytes = 900 * mb), recording("mid", 2, bytes = 900 * mb), recording("new", 3, bytes = 900 * mb))
        assertEquals(listOf("old"), RecordingRules.overflow(all, keep = 500).map { it.id })
        assertEquals(2L * 1024 * mb, RecordingRules.MAX_TOTAL_BYTES)
        // Exactly at the limit is within it.
        val exact = listOf(recording("a", 1, bytes = 1024 * mb), recording("b", 2, bytes = 1024 * mb))
        assertEquals(emptyList<Recording>(), RecordingRules.overflow(exact, keep = 500))
    }

    @Test
    fun theCountIsOneOfTheChoices() {
        assertEquals(listOf(100, 200, 500, 1000), RecordingRules.KEEP_CHOICES)
        assertEquals(500, RecordingRules.DEFAULT_KEEP)
        assertEquals(200, RecordingRules.keepChoice(200))
        assertEquals(500, RecordingRules.keepChoice(0))
        assertEquals(500, RecordingRules.keepChoice(123))
    }

    @Test
    fun theWindowsAreWholeDaysOfTheLocalCalendar() {
        val now = at("2026-11-17T09:30:00")
        assertNull(RecordingRules.window(RecordingPeriod.ALL, now, zone))
        assertEquals(at("2026-11-17T00:00:00")..(at("2026-11-18T00:00:00") - 1), RecordingRules.window(RecordingPeriod.TODAY, now, zone))
        assertEquals(at("2026-11-11T00:00:00"), RecordingRules.window(RecordingPeriod.WEEK, now, zone)!!.first)
        assertEquals(at("2026-10-19T00:00:00"), RecordingRules.window(RecordingPeriod.MONTH, now, zone)!!.first)
        // A range is its days, in whichever order they were picked; without both days it is no window.
        val range = RecordingRules.window(RecordingPeriod.RANGE, now, zone, LocalDate.of(2026, 11, 3), LocalDate.of(2026, 11, 1))
        assertEquals(at("2026-11-01T00:00:00")..(at("2026-11-04T00:00:00") - 1), range)
        assertNull(RecordingRules.window(RecordingPeriod.RANGE, now, zone, null, LocalDate.of(2026, 11, 1)))
    }

    @Test
    fun theListIsNewestFirstAndKeepsToTheWindow() {
        val all = listOf(recording("yesterday", at("2026-11-16T23:59:59")), recording("today", at("2026-11-17T00:00:00")), recording("later", at("2026-11-17T08:00:00")))
        val today = RecordingRules.window(RecordingPeriod.TODAY, at("2026-11-17T09:30:00"), zone)
        assertEquals(listOf("later", "today"), RecordingRules.shown(all, today).map { it.id })
        assertEquals(listOf("later", "today", "yesterday"), RecordingRules.shown(all, null).map { it.id })
    }

    @Test
    fun anExportIsNamedByItsTimeAndNumber() {
        val start = at("2026-11-17T14:30:05")
        assertEquals("20261117-143005_1001.m4a", RecordingRules.exportName(recording("a", start), zone))
        assertEquals("20261117-143005_+86138_001.wav", RecordingRules.exportName(recording("a", start, number = "+86138/001", file = "a.wav"), zone))
        // What a file name cannot carry becomes "_".
        assertEquals("20261117-143005____1.m4a", RecordingRules.exportName(recording("a", start, number = "前台:1"), zone))
        assertEquals("20261117-143005.m4a", RecordingRules.exportName(recording("a", start, number = ""), zone))
        assertEquals("audio/mp4", RecordingRules.mimeType("a.m4a"))
        assertEquals("audio/wav", RecordingRules.mimeType("a.WAV"))
    }

    @Test
    fun sizesAreShownPlainly() {
        assertEquals("1 KB", RecordingRules.sizeText(1))
        assertEquals("512 KB", RecordingRules.sizeText(512 * 1024))
        assertEquals("3.5 MB", RecordingRules.sizeText(3 * mb + mb / 2))
        assertEquals("2.00 GB", RecordingRules.sizeText(2 * 1024 * mb))
    }
}
