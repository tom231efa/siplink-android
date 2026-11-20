package com.sky.siplink.itest

import android.media.MediaMetadataRetriever
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sky.siplink.core.RecordingStore
import com.sky.siplink.core.logic.RemoteDocument
import com.sky.siplink.core.logic.RemotePlan
import com.sky.siplink.core.model.CallPhase
import com.sky.siplink.core.model.Recording
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Call recording: calls to the echo extension of the test PBX recorded by hand, by default and
 * through hold, the file that results, a managed configuration that forbids recording, and what
 * the store keeps and finishes. The app's audio is the tone file of [PbxTestBase], so the
 * recording holds sound of both sides.
 */
@RunWith(AndroidJUnit4::class)
class CallRecordingTest : PbxTestBase() {
    private val store get() = engine.recordings
    private val recordings: List<Recording> get() = store.recordings.value

    @Before
    fun startEmpty() = onMain { store.remove(recordings) }

    @After
    fun removeRecordings() = onMain {
        engine.lock.setManaged(false)
        store.remove(recordings)
    }

    /** The one recording the store finished after the call, once it is there. */
    private fun awaitRecording(): Recording {
        await("the recording to be finished", LONG_TIMEOUT_MS) { recordings.size == 1 }
        return recordings.single()
    }

    /** What the file says about its own length, in ms. */
    private fun fileDurationMs(file: File): Long = MediaMetadataRetriever().run {
        try {
            setDataSource(file.path)
            extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
        } finally {
            release()
        }
    }

    /** A recording of about [seconds] seconds: duration and size as AAC at 24–32 kbit/s makes them. */
    private fun assertPlausible(recording: Recording, seconds: LongRange) {
        val file = store.fileOf(recording)
        assertTrue("No file ${file.name}", file.isFile)
        assertTrue("Not converted: ${file.name}", file.name.endsWith(".m4a"))
        assertEquals(file.length(), recording.bytes)
        assertTrue("Duration ${recording.durationMs} ms", recording.durationMs / 1000 in seconds)
        assertTrue("File duration ${fileDurationMs(file)} ms", fileDurationMs(file) / 1000 in seconds)
        // 32 kbit/s is 4 KB a second; leave room for the container and a lower rate.
        val perSecond = recording.bytes * 1000 / recording.durationMs
        assertTrue("$perSecond bytes a second", perSecond in 1_000..8_000)
        assertFalse("A WAV file was left", file.parentFile!!.listFiles()!!.any { it.name.endsWith(".wav") })
    }

    @Test
    fun aCallIsRecordedThroughHoldIntoACompactFile() {
        register()
        dial("2000") // echo
        var info = awaitConnected("2000")
        awaitAppHearsAudio()
        assertTrue("The call cannot be recorded", info.recordable)
        assertEquals(null, info.recordingSince)

        onMain { engine.calls.toggleRecording() }
        awaitCall("the recording to run") { it.recordingSince != null }
        Thread.sleep(4_000)
        onMain { engine.calls.toggleHold() }
        awaitCall("the call to be on hold") { it.phase == CallPhase.HELD }
        Thread.sleep(1_000)
        onMain { engine.calls.toggleHold() }
        info = awaitConnected("2000")
        assertTrue("Hold stopped the recording", info.recordingSince != null)
        assertTrue(onMain { engine.core.currentCall?.isRecording == true })
        Thread.sleep(3_000)
        onMain { engine.calls.hangUp() }
        awaitNoCall()

        val recording = awaitRecording()
        assertEquals("2000", recording.number)
        assertFalse(recording.incoming)
        assertTrue(recording.line.startsWith("$APP@"))
        assertPlausible(recording, 6L..12L)
    }

    @Test
    fun everyCallIsRecordedByDefaultAndTheButtonStopsAndStartsIt() {
        settings { it.copy(recordByDefault = true) }
        register()
        dial("2000")
        awaitConnected("2000")
        awaitCall("the recording to start by itself") { it.recordingSince != null }
        Thread.sleep(3_000)
        onMain { engine.calls.toggleRecording() }
        awaitCall("the recording to stop") { it.recordingSince == null }
        Thread.sleep(3_000)
        onMain { engine.calls.toggleRecording() }
        awaitCall("the recording to go on") { it.recordingSince != null }
        Thread.sleep(3_000)
        onMain { engine.calls.hangUp() }
        awaitNoCall()
        // Both parts in one file, the break left out.
        assertPlausible(awaitRecording(), 5L..9L)
    }

    @Test
    fun switchedOffNoCallCanBeRecorded() {
        settings { it.copy(callRecording = false, recordByDefault = true) }
        register()
        dial("2000")
        val info = awaitConnected("2000")
        assertFalse(info.recordable)
        onMain { engine.calls.toggleRecording() }
        never("a recording", QUIET_WINDOW_MS) { call()?.recordingSince != null }
        onMain { engine.calls.hangUp() }
        awaitNoCall()
        never("a recording to be listed", QUIET_WINDOW_MS) { recordings.isNotEmpty() }
    }

    @Test
    fun aManagedConfigurationThatForbidsRecordingEndsTheRecordingAndKeepsIt() {
        register()
        dial("2000")
        awaitConnected("2000")
        onMain { engine.calls.toggleRecording() }
        awaitCall("the recording to run") { it.recordingSince != null }
        Thread.sleep(3_000)

        // A sync brings a configuration without recording, as the platform sends it.
        val document = (RemoteDocument.parse("""{"schema": 1, "version": "v1", "settings": {"callRecording": false}}""", emptySet()) as RemoteDocument.Parsed.Ok).document
        onMain {
            engine.lock.setManaged(true)
            engine.lock.fromRemote { engine.updateSettings { RemotePlan.settings(document.settings, it) } }
        }
        awaitCall("the recording to end") { it.recordingSince == null && !it.recordable }
        // The user cannot switch it on again.
        assertFalse(onMain { engine.updateSettings { it.copy(callRecording = true) } })
        onMain { engine.calls.toggleRecording() }
        never("a recording", QUIET_WINDOW_MS) { call()?.recordingSince != null }
        onMain { engine.calls.hangUp() }
        awaitNoCall()
        assertPlausible(awaitRecording(), 2L..6L)
    }

    @Test
    fun theOldestGoAndWhatACrashLeftIsFinished() {
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "itest-recordings").apply {
            deleteRecursively()
            mkdirs()
        }
        val now = System.currentTimeMillis()
        // Three finished recordings of a second, with what was known about their calls.
        listOf("old" to now - 3 * DAY_MS, "mid" to now - 2 * DAY_MS, "new" to now - DAY_MS).forEach { (name, startedAt) ->
            File(directory, "$name.wav").writeBytes(wav(8_000, seconds = 1, sizesWritten = true))
            File(directory, "$name.facts").writeText("""{"number":"$name","name":"","incoming":true,"line":"1001@pbx","startedAt":$startedAt}""")
        }
        // One left by a crash: no sizes in its head, nothing known about its call; one without sound; a half-converted file.
        File(directory, "crashed.wav").writeBytes(wav(8_000, seconds = 2, sizesWritten = false))
        File(directory, "silent.wav").writeBytes(wav(8_000, seconds = 0, sizesWritten = true))
        File(directory, "mid.m4a.part").writeBytes(ByteArray(10))

        val kept = RecordingStore(directory) { 2 }
        await("the store to finish what it found", LONG_TIMEOUT_MS) {
            directory.listFiles()!!.none { it.name.endsWith(".wav") || it.name.endsWith(".part") || it.name.endsWith(".facts") }
        }
        await("the list to settle") { kept.recordings.value.size == 2 }
        val list = kept.recordings.value
        // The crashed one counts from when its file was last written, so it is the newest.
        assertEquals(listOf("", "new"), list.map { it.number })
        assertEquals(2000L, list[0].durationMs)
        list.forEach { assertEquals(File(directory, it.file).length(), it.bytes) }
        assertEquals(
            setOf("crashed.m4a", "new.m4a", "index.json"),
            directory.listFiles()!!.map { it.name }.toSet(),
        )
        directory.deleteRecursively()
    }

    /** A WAV file of 16-bit mono silence as the SIP core writes it; one left by a crash has no sizes in its head. */
    private fun wav(rate: Int, seconds: Int, sizesWritten: Boolean): ByteArray {
        val data = rate * 2 * seconds
        val buffer = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(if (sizesWritten) 36 + data else 0).put("WAVE".toByteArray())
        buffer.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        buffer.put("data".toByteArray()).putInt(if (sizesWritten) data else 0)
        return buffer.array()
    }

    private companion object {
        const val DAY_MS = 24 * 60 * 60 * 1000L
    }
}
