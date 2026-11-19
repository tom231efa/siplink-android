package com.sky.siplink.core

import android.os.SystemClock
import com.sky.siplink.core.model.SideCall
import org.linphone.core.Call
import org.linphone.core.CallParams

/**
 * Records calls, both sides mixed, without telling anyone: no tone, nothing in the signalling.
 *
 * The SIP core records a call only into a file that was named in its parameters before they
 * were given to the call ([CallParams.setRecordFile]); every call the app places or answers gets
 * one while recording is allowed ([prepare], [attach]). Recording then starts and stops with
 * [Call.startRecording] and [Call.stopRecording] as often as the user likes, all into that one
 * file, and survives hold and resume: the core starts it again when it restarts the call's sound.
 * It ends with the call; the [RecordingStore] then finishes the file.
 *
 * A conference room is not recorded: the core records no call that is part of a conference.
 * The recordings of the calls end as they go into the room.
 *
 * Main thread only, like the SIP core.
 *
 * @param allowed whether recording is allowed ([com.sky.siplink.core.model.Settings.callRecording])
 * @param byDefault whether every call is recorded from the moment it is connected
 * @param party who the other side of a call is, as the call screen names it
 * @param onChanged a call started or stopped being recorded
 */
internal class CallRecorder(
    private val store: RecordingStore,
    private val allowed: () -> Boolean,
    private val byDefault: () -> Boolean,
    private val party: (Call) -> SideCall,
    private val onChanged: () -> Unit,
) {
    /** The recording of one call. */
    private class Take(val path: String) {
        /** The recording ran before: the file holds sound of this call. */
        var begun = false

        /** Recording by default was applied; the user's choice counts from then on. */
        var defaultApplied = false

        /** How long the recording ran before its last stop. */
        var recordedMs = 0L

        /** SystemClock.elapsedRealtime() when it last started; null while it is stopped. */
        var since: Long? = null
    }

    private val takes = HashMap<Long, Take>()

    /** Names a file for the call that is given [params]; returns it, or null while recording is not allowed. */
    fun prepare(params: CallParams): String? {
        if (!allowed()) return null
        return store.newPath().also { params.recordFile = it }
    }

    /** [call] was given the parameters [prepare] named [path] in. */
    fun attach(call: Call, path: String?) {
        if (path != null) takes[call.nativePointer] = Take(path)
    }

    /** Whether [call] can be recorded now. */
    fun recordable(call: Call): Boolean = allowed() && call.nativePointer in takes

    /**
     * When the recording of [call] would have started had it run without a break, in
     * SystemClock.elapsedRealtime(); null while it is not being recorded.
     */
    fun startedAt(call: Call): Long? {
        val take = takes[call.nativePointer] ?: return null
        val since = take.since ?: return null
        return since - take.recordedMs
    }

    /** Starts or stops recording [call]. */
    fun toggle(call: Call?) {
        val take = call?.let { takes[it.nativePointer] } ?: return
        if (take.since != null) stop(call, take) else if (recordable(call)) start(call, take)
        onChanged()
    }

    /**
     * [call] is connected and its sound runs: it is recorded now if every call is to be.
     *
     * @param inRoom the call is part of the conference room, which is not recorded
     */
    fun onStreaming(call: Call, inRoom: Boolean) {
        val take = takes[call.nativePointer] ?: return
        if (take.defaultApplied) return
        take.defaultApplied = true
        if (byDefault() && !inRoom && recordable(call) && take.since == null) {
            start(call, take)
            onChanged()
        }
    }

    /** The calls are about to go into the conference room, which is not recorded. */
    fun onMerging(calls: List<Call>) = calls.forEach { call -> takes[call.nativePointer]?.let { if (it.since != null) stop(call, it) } }

    /** Recording was switched off (in the settings or by a remote configuration): running recordings end, their files stay. */
    fun onAllowedChanged(calls: List<Call>) {
        if (allowed()) return
        calls.forEach { call -> takes[call.nativePointer]?.let { if (it.since != null) stop(call, it) } }
        onChanged()
    }

    /** [call] is gone: what it recorded is handed to the store. */
    fun onReleased(call: Call) {
        val take = takes.remove(call.nativePointer) ?: return
        if (take.begun) store.finish(take.path)
    }

    private fun start(call: Call, take: Take) {
        call.startRecording()
        if (!call.isRecording) return
        if (!take.begun) {
            take.begun = true
            val side = party(call)
            store.begin(
                take.path,
                RecordingStore.CallFacts(
                    number = side.address.substringBefore('@'),
                    name = side.name,
                    incoming = call.dir == Call.Dir.Incoming,
                    line = CallSupport.lineOf(call.params.account),
                    startedAt = System.currentTimeMillis(),
                ),
            )
        }
        take.since = SystemClock.elapsedRealtime()
    }

    private fun stop(call: Call, take: Take) {
        call.stopRecording()
        take.since?.let { take.recordedMs += SystemClock.elapsedRealtime() - it }
        take.since = null
    }
}
