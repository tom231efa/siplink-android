package com.sky.siplink.core

import android.util.Log
import com.sky.siplink.core.logic.Json
import com.sky.siplink.core.logic.RecordingRules
import com.sky.siplink.core.logic.WavFormat
import com.sky.siplink.core.logic.WavInfo
import com.sky.siplink.core.model.Recording
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.Executors

/**
 * The call recordings, in a directory of the app's private files with an index beside them.
 *
 * The SIP core records a call into a WAV file ([newPath]); what is known about the call goes
 * into a small file next to it as the recording starts ([begin]). Once the call is gone
 * ([finish]) the WAV file becomes an .m4a file ([AudioTranscoder]), is put in the index, and the
 * oldest recordings go as far as the limits ask ([RecordingRules.overflow]). What a crash left
 * behind is finished the same way at the next start; a WAV file without sound is removed.
 *
 * Files are read and written on a thread of their own; [recordings] can be read anywhere.
 *
 * @param directory where the recordings are kept: [DIRECTORY] in the app's files
 * @param keep how many recordings are kept ([com.sky.siplink.core.model.Settings.recordingsKept])
 */
class RecordingStore(private val directory: File, private val keep: () -> Int) {
    init {
        directory.mkdirs()
    }

    private val index = File(directory, INDEX)
    private val io = Executors.newSingleThreadExecutor { Thread(it, "recordings-io") }

    private val state = MutableStateFlow<List<Recording>>(emptyList())

    /** Every recording, newest first. */
    val recordings: StateFlow<List<Recording>> = state.asStateFlow()

    /** What is known about a call when its recording starts. */
    class CallFacts(val number: String, val name: String, val incoming: Boolean, val line: String, val startedAt: Long)

    init {
        io.execute {
            state.value = readIndex()
            recover()
        }
    }

    /** A new file for the SIP core to record a call into; nothing is written to it before the recording starts. */
    fun newPath(): String = File(directory, "rec-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}.$RAW").path

    /** The recording into [path] has started: what is known about the call is kept with it. */
    fun begin(path: String, facts: CallFacts) {
        val base = File(path).nameWithoutExtension
        io.execute {
            val values = mapOf(
                "number" to facts.number,
                "name" to facts.name,
                "incoming" to facts.incoming,
                "line" to facts.line,
                "startedAt" to facts.startedAt,
            )
            runCatching { write(File(directory, "$base.$FACTS"), Json.write(values)) }
                .onFailure { Log.e(TAG, "Unable to keep the facts of $base", it) }
        }
    }

    /** The call recorded into [path] is gone: its recording is finished and listed. */
    fun finish(path: String) {
        val base = File(path).nameWithoutExtension
        io.execute { complete(base) }
    }

    /** The file of [recording]; read it off the main thread. */
    fun fileOf(recording: Recording): File = File(directory, recording.file)

    /** Removes [recordings] and their files. */
    fun remove(recordings: Collection<Recording>) {
        val gone = recordings.mapTo(HashSet()) { it.id }
        if (gone.isEmpty()) return
        io.execute {
            state.value.filter { it.id in gone }.forEach { File(directory, it.file).delete() }
            state.value = state.value.filterNot { it.id in gone }
            saveIndex()
        }
    }

    // ---------------------------------------------------------------- on the io thread

    /** Finishes what the last run left: recordings not listed yet, and half-written files. */
    private fun recover() {
        directory.listFiles()?.forEach { file ->
            if (file.name.endsWith(".$PART") || file.name.endsWith(".$TEMPORARY")) file.delete()
        }
        directory.listFiles { file -> file.name.endsWith(".$RAW") }?.forEach { complete(it.nameWithoutExtension) }
        // Facts of a call whose recording never wrote a file.
        directory.listFiles { file -> file.name.endsWith(".$FACTS") }?.forEach { facts ->
            if (!File(directory, facts.nameWithoutExtension + ".$RAW").exists()) facts.delete()
        }
        // Listed recordings whose files are gone.
        val present = state.value.filter { File(directory, it.file).isFile }
        if (present.size != state.value.size) {
            state.value = present
            saveIndex()
        }
        trim()
    }

    private fun complete(base: String) {
        val raw = File(directory, "$base.$RAW")
        val factsFile = File(directory, "$base.$FACTS")
        if (state.value.any { it.id == base }) {
            // Listed already: only the cleaning up was cut short.
            raw.delete()
            factsFile.delete()
            return
        }
        if (!raw.isFile) {
            factsFile.delete()
            return
        }
        val info = readHead(raw)
        if (info == null || info.durationMs < MIN_DURATION_MS) {
            raw.delete()
            factsFile.delete()
            return
        }
        val facts = readFacts(factsFile)
        val converted = File(directory, "$base.$COMPRESSED")
        val part = File(directory, "$base.$COMPRESSED.$PART")
        val file = runCatching {
            AudioTranscoder.wavToM4a(raw, info, part)
            if (!part.renameTo(converted)) error("rename failed")
            converted
        }.getOrElse {
            Log.w(TAG, "Keeping $base as WAV: it could not be converted", it)
            fixHead(raw, info)
            raw
        }
        val recording = Recording(
            id = base,
            file = file.name,
            number = facts?.get("number") as? String ?: "",
            name = facts?.get("name") as? String ?: "",
            incoming = facts?.get("incoming") as? Boolean ?: false,
            line = facts?.get("line") as? String ?: "",
            startedAt = (facts?.get("startedAt") as? Number)?.toLong() ?: (raw.lastModified() - info.durationMs),
            durationMs = info.durationMs,
            bytes = file.length(),
        )
        state.value = (listOf(recording) + state.value).sortedByDescending { it.startedAt }
        saveIndex()
        if (file != raw) raw.delete()
        factsFile.delete()
        trim()
    }

    /** The oldest recordings go until the limits are kept. */
    private fun trim() {
        val gone = RecordingRules.overflow(state.value, keep()).mapTo(HashSet()) { it.id }
        if (gone.isEmpty()) return
        state.value.filter { it.id in gone }.forEach { File(directory, it.file).delete() }
        state.value = state.value.filterNot { it.id in gone }
        saveIndex()
    }

    private fun readHead(file: File): WavInfo? = runCatching {
        RandomAccessFile(file, "r").use { input ->
            val head = ByteArray(minOf(WavFormat.HEAD_BYTES.toLong(), input.length()).toInt())
            input.readFully(head)
            WavFormat.parse(head, input.length())
        }
    }.getOrNull()

    /** Writes the right sizes into the head of a WAV file that the SIP core could not close. */
    private fun fixHead(file: File, info: WavInfo) {
        val (riff, data) = WavFormat.sizes(info)
        runCatching {
            RandomAccessFile(file, "rw").use { output ->
                output.seek(RIFF_SIZE_AT)
                output.write(littleEndian(riff))
                output.seek(info.dataOffset - 4L)
                output.write(littleEndian(data))
            }
        }
    }

    private fun littleEndian(value: Int) = byteArrayOf(value.toByte(), (value shr 8).toByte(), (value shr 16).toByte(), (value shr 24).toByte())

    @Suppress("UNCHECKED_CAST")
    private fun readFacts(file: File): Map<String, Any?>? =
        runCatching { Json.parse(file.readText()) as? Map<String, Any?> }.getOrNull()

    @Suppress("UNCHECKED_CAST")
    private fun readIndex(): List<Recording> = runCatching {
        if (!index.isFile) return emptyList()
        (Json.parse(index.readText()) as List<Any?>).mapNotNull { item ->
            val fields = item as? Map<String, Any?> ?: return@mapNotNull null
            Recording(
                id = fields["id"] as String,
                file = fields["file"] as String,
                number = fields["number"] as? String ?: "",
                name = fields["name"] as? String ?: "",
                incoming = fields["incoming"] as? Boolean ?: false,
                line = fields["line"] as? String ?: "",
                startedAt = (fields["startedAt"] as Number).toLong(),
                durationMs = (fields["durationMs"] as? Number)?.toLong() ?: 0,
                bytes = (fields["bytes"] as? Number)?.toLong() ?: 0,
            )
        }.sortedByDescending { it.startedAt }
    }.onFailure { Log.e(TAG, "Unable to read the list of recordings", it) }.getOrDefault(emptyList())

    private fun saveIndex() {
        val list = state.value.map {
            mapOf(
                "id" to it.id,
                "file" to it.file,
                "number" to it.number,
                "name" to it.name,
                "incoming" to it.incoming,
                "line" to it.line,
                "startedAt" to it.startedAt,
                "durationMs" to it.durationMs,
                "bytes" to it.bytes,
            )
        }
        runCatching { write(index, Json.write(list)) }.onFailure { Log.e(TAG, "Unable to store the list of recordings", it) }
    }

    /** Written next to the file and then moved over it, so a crash never leaves half a file. */
    private fun write(to: File, text: String) {
        val draft = File(directory, to.name + ".$TEMPORARY")
        draft.writeText(text)
        if (!draft.renameTo(to)) error("rename failed")
    }

    companion object {
        /** The directory in the app's files that the app keeps its recordings in. */
        const val DIRECTORY = "recordings"

        private const val TAG = "SipLink.Recordings"
        private const val INDEX = "index.json"
        private const val RAW = "wav"
        private const val COMPRESSED = "m4a"
        private const val FACTS = "facts"
        private const val PART = "part"
        private const val TEMPORARY = "tmp"

        /** A recording shorter than this holds no sound worth keeping. */
        private const val MIN_DURATION_MS = 500L

        /** Where the size of the RIFF chunk stands in a WAV file. */
        private const val RIFF_SIZE_AT = 4L
    }
}
