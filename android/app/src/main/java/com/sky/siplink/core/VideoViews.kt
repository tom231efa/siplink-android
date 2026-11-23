package com.sky.siplink.core

import android.view.TextureView
import org.linphone.core.Core

/**
 * The views of the call screen the SIP core draws into: the other side's picture ([Kind.REMOTE])
 * and the own camera's ([Kind.PREVIEW]). A view is handed over as it comes into its window ([give]) and
 * taken back before it leaves it ([takeBack]); the core sets them on its own thread.
 *
 * Taking back waits for the core. The SDK listens to the surface of the view it holds, and when
 * that surface goes it sets itself a task, on the core's thread, to forget "its view": had the
 * view not been taken back by then, the task would run after the next call screen handed over
 * its own views (a call screen started anew because the system changed its theme or its font
 * size), and forget those. Taken back first, the view is the SDK's no more, and the SDK no longer
 * listens to it.
 */
class VideoViews internal constructor(private val core: Core) {
    /** The view of each kind the core has now, as it was handed over: what the core gives back for it is not always the view itself. */
    private val held = HashMap<Kind, TextureView>()

    /** [view] came into its window: the core draws the pictures of [kind] into it from now on. */
    fun give(kind: Kind, view: TextureView) = CoreThread.post {
        held[kind] = view
        set(kind, view)
    }

    /** [view] is about to leave its window: the core stops drawing into it, unless another view took its place already. */
    fun takeBack(kind: Kind, view: TextureView) = CoreThread.wait("VideoViews.takeBack") {
        if (held[kind] === view) {
            held.remove(kind)
            set(kind, null)
        }
    }

    private fun set(kind: Kind, view: TextureView?) {
        when (kind) {
            Kind.REMOTE -> core.nativeVideoWindowId = view
            Kind.PREVIEW -> core.nativePreviewWindowId = view
        }
    }

    enum class Kind { REMOTE, PREVIEW }
}
