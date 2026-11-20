package com.sky.siplink.itest

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sky.siplink.core.logic.UserAgent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The options of the SIP library on the advanced settings page, as the user sets them: they reach
 * the SIP core, and a name of the user's own reaches the PBX exactly as typed. Pictures are saved
 * in the cache directory of the app (itest-advanced/).
 */
@RunWith(AndroidJUnit4::class)
class AdvancedSettingsTest : ScreenTestBase("itest-advanced") {
    private fun openPage() {
        onMain { engine.updateSettings { it.copy(lockScreenPromptDeclined = true) } }
        showApp()
        tap("设置")
        tap("高级设置")
        see("媒体与网络")
    }

    @Test
    fun theSwitchesReachTheCore() {
        // Written at the start: the SDK's own default would have it on.
        assertFalse("Adaptive rate control is on", onMain { engine.core.isAdaptiveRateControlEnabled })
        openPage()
        picture("page")

        tap("自适应速率控制")
        await("adaptive rate control in the core") { onMain { engine.core.isAdaptiveRateControlEnabled } }
        assertTrue(engine.settings.current.adaptiveRateControl)
        tap("自适应速率控制")
        await("adaptive rate control off in the core") { !onMain { engine.core.isAdaptiveRateControlEnabled } }

        tap("回声消除")
        await("echo cancellation off in the core") { !onMain { engine.core.isEchoCancellationEnabled } }
        assertFalse(engine.settings.current.echoCancellation)
        tap("回声消除")
        await("echo cancellation in the core") { onMain { engine.core.isEchoCancellationEnabled } }

        tap("允许 IPv6")
        await("IPv6 off in the core") { !onMain { engine.core.isIpv6Enabled } }
        assertFalse(engine.settings.current.ipv6)
        tap("允许 IPv6")
        await("IPv6 in the core") { onMain { engine.core.isIpv6Enabled } }
    }

    @Test
    fun aNameOfTheUsersOwnReachesThePbxAsTyped() {
        register(APP)
        val default = engine.defaultUserAgent
        await("the PBX to store the default name") { pbxUserAgent(APP) == default }
        openPage()

        // A name the rule refuses says why and is not kept.
        typeInto(FIELD, " $CUSTOM")
        see(zh(UserAgent.Error.SPACE_AT_ENDS.message))
        assertEquals("", engine.settings.current.userAgent)

        // Without the app being started again, the PBX learns the new name with the next REGISTER.
        typeInto(FIELD, CUSTOM)
        await("the name to be kept") { engine.settings.current.userAgent == CUSTOM }
        assertEquals(CUSTOM, onMain { engine.core.userAgent })
        await("the PBX to store the name as typed") { pbxUserAgent(APP) == CUSTOM }
        picture("custom")

        // Emptied, the default is back.
        typeInto(FIELD, "")
        await("the default name in the core") { onMain { engine.core.userAgent } == default }
        await("the PBX to store the default name again") { pbxUserAgent(APP) == default }
    }

    private companion object {
        const val FIELD = "客户端名称（User-Agent）"

        /** Spaces, a slash and brackets inside: it has to go on the wire as one piece. */
        const val CUSTOM = "Example Desk Phone/2.0 (itest)"
    }
}
