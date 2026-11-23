package com.sky.siplink.itest

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sky.siplink.core.CoreThread
import com.sky.siplink.core.model.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.linphone.core.Factory
import java.io.File

/**
 * The options of the SIP library on the advanced settings page start where a fresh SIP core of
 * this SDK, on this device, starts: installations from before the page exist do not change. The
 * one exception, adaptive rate control, is off on purpose (see [Settings.adaptiveRateControl]).
 *
 * A second core is started for this in a configuration file of its own; it has a class (and so a
 * process) to itself in testenv/run-tests.sh, so that it touches no other test.
 */
@RunWith(AndroidJUnit4::class)
class SdkDefaultsTest {
    @Test
    fun theSettingsStartWhereAFreshCoreStarts() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val file = File(context.cacheDir, "itest-fresh-linphonerc").apply { delete() }
        var started = -1
        var found: List<Boolean> = emptyList()
        // On the thread of the app's own core; nothing is asserted there.
        CoreThread.wait("test") {
            val core = Factory.instance().createCore(file.absolutePath, null, context)
            started = core.start()
            found = listOf(core.isEchoCancellationEnabled, core.isIpv6Enabled, core.isAdaptiveRateControlEnabled)
            core.stop()
        }
        file.delete()
        assertEquals("The fresh core did not start", 0, started)
        val defaults = Settings()
        assertEquals("Echo cancellation of a fresh core", defaults.echoCancellation, found[0])
        assertEquals("IPv6 of a fresh core", defaults.ipv6, found[1])
        // The one the app turns off: the SDK has it on.
        assertTrue("Adaptive rate control is off in a fresh core: the app's default changes nothing", found[2] && !defaults.adaptiveRateControl)
    }
}
