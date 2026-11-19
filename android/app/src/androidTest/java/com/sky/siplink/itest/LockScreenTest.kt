package com.sky.siplink.itest

import android.app.Activity
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.sky.siplink.CallActivity
import com.sky.siplink.openCallScreen
import com.sky.siplink.ui.call.LockGate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * The call screen shows over the lock screen: the sheets that reach beyond the call (adding
 * members to the room, transfer, the speed dial keys) open only once the phone is unlocked, and
 * close when the screen comes back locked.
 *
 * The test phone has no lock screen, and its settings are not changed for a test: the app asks a
 * lock screen this test plays ([LockGate.keyguard]), locked until the test unlocks it the way the
 * system does once the user has.
 */
class LockScreenTest : RoomTestBase("lock") {
    private class PlayedKeyguard : LockGate.Keyguard {
        @Volatile
        var locked = true
        val asked = AtomicInteger()
        private var waiting: (() -> Unit)? = null

        override fun isLocked(activity: Activity): Boolean = locked

        override fun requestUnlock(activity: Activity, onUnlocked: () -> Unit) {
            asked.incrementAndGet()
            waiting = onUnlocked
        }

        /** The user unlocked the phone; to be run on the main thread, as the system reports it there. */
        fun unlock() {
            locked = false
            waiting?.invoke()
            waiting = null
        }
    }

    private val system = LockGate.keyguard
    private val keyguard = PlayedKeyguard()

    @Before
    fun playTheLockScreen() {
        LockGate.keyguard = keyguard
    }

    @After
    fun giveItBack() {
        LockGate.keyguard = system
    }

    @Test
    fun membersAreAddedToTheRoomOnlyOnceThePhoneIsUnlocked() {
        openRoom("2501", "2502")
        tap("添加成员")
        never("the sheet of the room open over the lock screen", QUIET_WINDOW_MS) { shown(FIELD).isNotEmpty() }
        assertEquals(1, keyguard.asked.get())
        onMain { keyguard.unlock() }
        see(FIELD)
        picture("room-add-unlocked")

        // The call screen comes back with the phone locked again: the sheet has closed.
        keyguard.locked = true
        shell("input keyevent KEYCODE_HOME")
        // Stopped, as with the screen switched off; paused only, it would come back without starting again.
        await("the call screen to stop") { callScreenIn(Stage.STOPPED) }
        context.openCallScreen()
        callScreen()
        see("添加成员")
        absent(FIELD)

        // Unlocked, nothing is asked.
        keyguard.locked = false
        tap("添加成员")
        see(FIELD)
        assertEquals(1, keyguard.asked.get())
    }

    @Test
    fun transferAndTheSpeedDialKeysWaitForTheUnlockToo() {
        callFirstMember()
        tap("转接")
        never("the transfer sheet open over the lock screen", QUIET_WINDOW_MS) { shown("盲转").isNotEmpty() }
        onMain { keyguard.unlock() }
        see("盲转")
        back()
        await("the transfer sheet to close") { find("盲转") == null }

        keyguard.locked = true
        tap("快捷键")
        never("the speed dial keys open over the lock screen", QUIET_WINDOW_MS) { shown("暂无快捷键").isNotEmpty() }
        assertEquals(2, keyguard.asked.get())
        onMain { keyguard.unlock() }
        see("暂无快捷键")
    }

    private fun callScreenIn(stage: Stage): Boolean = onMain {
        ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(stage).any { it is CallActivity }
    }

    private companion object {
        /** The field of the sheet that adds members. */
        const val FIELD = "号码或姓名"
    }
}
