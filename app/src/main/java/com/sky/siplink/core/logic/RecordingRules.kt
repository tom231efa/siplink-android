package com.sky.siplink.core.logic

import com.sky.siplink.core.model.Recording
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The periods the list of recordings can be narrowed to. */
enum class RecordingPeriod { ALL, TODAY, WEEK, MONTH, RANGE }

/** Rules of the call recordings that need neither files nor Android: how many are kept, which are shown, what an export is called. */
object RecordingRules {
    /** How many recordings can be kept; the user picks one of these. */
    val KEEP_CHOICES = listOf(100, 200, 500, 1000)
    const val DEFAULT_KEEP = 500

    /** All recordings together take no more than this: 2 GiB. */
    const val MAX_TOTAL_BYTES = 2L * 1024 * 1024 * 1024

    /** [value] when it is one of [KEEP_CHOICES], else the default. */
    fun keepChoice(value: Int): Int = if (value in KEEP_CHOICES) value else DEFAULT_KEEP

    /**
     * The recordings that have to go so that at most [keep] of them, of at most [maxBytes]
     * together, are left: the oldest first.
     */
    fun overflow(all: List<Recording>, keep: Int, maxBytes: Long = MAX_TOTAL_BYTES): List<Recording> {
        val newestFirst = all.sortedByDescending { it.startedAt }
        var total = 0L
        val gone = ArrayList<Recording>()
        newestFirst.forEachIndexed { index, recording ->
            total += recording.bytes
            if (index >= keep || total > maxBytes) gone += recording
        }
        return gone.reversed()
    }

    /**
     * The time window of [period] at [now], from its first millisecond to its last, both included:
     * today, the last 7 or 30 days (today included), or the days [from] to [to] of a chosen range.
     * Null: no window, everything is shown.
     */
    fun window(period: RecordingPeriod, now: Long, zone: ZoneId, from: LocalDate? = null, to: LocalDate? = null): LongRange? {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val (first, last) = when (period) {
            RecordingPeriod.ALL -> return null
            RecordingPeriod.TODAY -> today to today
            RecordingPeriod.WEEK -> today.minusDays(6) to today
            RecordingPeriod.MONTH -> today.minusDays(29) to today
            RecordingPeriod.RANGE -> {
                if (from == null || to == null) return null
                minOf(from, to) to maxOf(from, to)
            }
        }
        val start = first.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = last.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        return start..end
    }

    /** The recordings that started in [window] (null: all of them), newest first. */
    fun shown(all: List<Recording>, window: LongRange?): List<Recording> =
        all.filter { window == null || it.startedAt in window }.sortedByDescending { it.startedAt }

    private val exportTime = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    /**
     * The name an exported recording gets: its start time and the other side's number, such as
     * "20261117-143005_1001.m4a". Characters a file name cannot carry become "_".
     */
    fun exportName(recording: Recording, zone: ZoneId): String {
        val time = exportTime.format(Instant.ofEpochMilli(recording.startedAt).atZone(zone))
        val number = recording.number.map { if (it.isLetterOrDigit() && it.code < 128 || it in "+*#.-") it else '_' }.joinToString("")
        val extension = recording.file.substringAfterLast('.', "")
        return buildString {
            append(time)
            if (number.isNotEmpty()) append('_').append(number.take(MAX_NUMBER_IN_NAME))
            if (extension.isNotEmpty()) append('.').append(extension)
        }
    }

    /** The media type of a recording's file, by its extension. */
    fun mimeType(file: String): String = when (file.substringAfterLast('.', "").lowercase()) {
        "m4a" -> "audio/mp4"
        "wav" -> "audio/wav"
        else -> "application/octet-stream"
    }

    /** A size as a list shows it: "512 KB", "3.4 MB", "1.25 GB". */
    fun sizeText(bytes: Long): String = when {
        bytes < MB -> "${(bytes + KB - 1) / KB} KB"
        bytes < GB -> String.format(java.util.Locale.ROOT, "%.1f MB", bytes.toDouble() / MB)
        else -> String.format(java.util.Locale.ROOT, "%.2f GB", bytes.toDouble() / GB)
    }

    private const val KB = 1024L
    private const val MB = KB * 1024
    private const val GB = MB * 1024
    private const val MAX_NUMBER_IN_NAME = 64
}
