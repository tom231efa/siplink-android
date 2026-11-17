package com.sky.siplink.core.logic

import com.sky.siplink.core.model.BannerPosition
import com.sky.siplink.core.model.DndMode
import com.sky.siplink.core.model.DtmfMode
import com.sky.siplink.core.model.FeatureCodes
import com.sky.siplink.core.model.MediaSecurity
import com.sky.siplink.core.model.Settings
import com.sky.siplink.core.model.ThemeMode
import com.sky.siplink.core.model.VideoSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedConfigTest {
    /** What was handed out; the defaults would do, a few values of its own make mix-ups show. */
    private val current = Settings(forwardBusy = "1002", features = FeatureCodes(park = "701"), videoCalls = false)

    /** [current] with every setting changed. */
    private val everythingChanged = Settings(
        keepAlive = !current.keepAlive,
        intercomAutoAnswer = !current.intercomAutoAnswer,
        intercomSpeaker = !current.intercomSpeaker,
        autoAnswer = !current.autoAnswer,
        autoAnswerSeconds = 10,
        themeMode = ThemeMode.DARK,
        dynamicColor = !current.dynamicColor,
        callBannerPosition = BannerPosition.TOP,
        verifyTlsCertificate = !current.verifyTlsCertificate,
        verifyTlsHostname = !current.verifyTlsHostname,
        mediaSecurity = MediaSecurity.SRTP_MANDATORY,
        doNotDisturb = !current.doNotDisturb,
        dndMode = DndMode.SERVER,
        callWaiting = !current.callWaiting,
        rejectAnonymous = !current.rejectAnonymous,
        dtmfMode = DtmfMode.SIP_INFO,
        forwardAlways = "1003",
        forwardBusy = "1004",
        forwardNoAnswer = "1005",
        forwardNoAnswerSeconds = current.forwardNoAnswerSeconds + 10,
        features = FeatureCodes(park = "702", voicemail = "", dndOn = "*76"),
        videoCalls = !current.videoCalls,
        videoSize = VideoSize.HD,
        videoFrameRate = 30,
        lockScreenPromptDeclined = !current.lockScreenPromptDeclined,
        autoUpdateCheck = !current.autoUpdateCheck,
        userAgent = "Example/1.0",
        adaptiveRateControl = !current.adaptiveRateControl,
        echoCancellation = !current.echoCancellation,
        ipv6 = !current.ipv6,
        callRecording = !current.callRecording,
        recordByDefault = !current.recordByDefault,
        recordingsKept = 100,
    )

    /** The settings that stay the user's own, taken from [everythingChanged]. */
    private val personalChanged = current.copy(
        doNotDisturb = everythingChanged.doNotDisturb,
        themeMode = everythingChanged.themeMode,
        dynamicColor = everythingChanged.dynamicColor,
        callBannerPosition = everythingChanged.callBannerPosition,
        lockScreenPromptDeclined = everythingChanged.lockScreenPromptDeclined,
        recordingsKept = everythingChanged.recordingsKept,
    )

    /** The values of a data class in the order of its properties. */
    private fun components(settings: Settings): List<Any?> = Settings::class.java.declaredMethods
        .filter { it.name.matches(Regex("component\\d+")) }
        .sortedBy { it.name.removePrefix("component").toInt() }
        .map { it.invoke(settings) }

    @Test
    fun `the test changes every setting`() {
        // A setting added later has to be changed above as well, or its place among the locked
        // or the personal ones goes untested.
        val before = components(current)
        val after = components(everythingChanged)
        assertEquals(before.size, after.size)
        before.zip(after).forEachIndexed { index, (old, new) -> assertNotEquals("Setting ${index + 1} is not changed by the test", old, new) }
    }

    @Test
    fun `only the user's own settings are taken from a local change`() {
        assertEquals(personalChanged, ManagedConfig.localUpdate(current, everythingChanged))
    }

    @Test
    fun `a change of personal settings is taken in full`() {
        assertEquals(personalChanged, ManagedConfig.localUpdate(current, personalChanged))
        val dnd = current.copy(doNotDisturb = true)
        assertEquals(dnd, ManagedConfig.localUpdate(current, dnd))
        val declined = current.copy(lockScreenPromptDeclined = true)
        assertEquals(declined, ManagedConfig.localUpdate(current, declined))
    }

    @Test
    fun `a change of locked settings comes to nothing`() {
        val locked = listOf<(Settings) -> Settings>(
            { it.copy(keepAlive = !it.keepAlive) },
            { it.copy(intercomAutoAnswer = !it.intercomAutoAnswer) },
            { it.copy(intercomSpeaker = !it.intercomSpeaker) },
            // Answering regular calls by itself, and after how long, is the administrator's like the intercom switches.
            { it.copy(autoAnswer = !it.autoAnswer) },
            { it.copy(autoAnswerSeconds = 20) },
            { it.copy(verifyTlsCertificate = false) },
            { it.copy(verifyTlsHostname = false) },
            { it.copy(mediaSecurity = MediaSecurity.SRTP_OPTIONAL) },
            { it.copy(dndMode = DndMode.SERVER) },
            { it.copy(callWaiting = !it.callWaiting) },
            { it.copy(rejectAnonymous = !it.rejectAnonymous) },
            { it.copy(dtmfMode = DtmfMode.SIP_INFO) },
            { it.copy(forwardAlways = "1003") },
            { it.copy(forwardBusy = "") },
            { it.copy(forwardNoAnswer = "1003") },
            { it.copy(forwardNoAnswerSeconds = 45) },
            { it.copy(features = it.features.copy(intercom = "")) },
            { it.copy(features = FeatureCodes()) },
            { it.copy(videoCalls = !it.videoCalls) },
            { it.copy(videoSize = VideoSize.QVGA) },
            { it.copy(videoFrameRate = 24) },
            // An administrator decides whether the phones ask GitHub for updates.
            { it.copy(autoUpdateCheck = !it.autoUpdateCheck) },
            // The options of the SIP library on the advanced settings page.
            { it.copy(userAgent = "Example/1.0") },
            { it.copy(adaptiveRateControl = !it.adaptiveRateControl) },
            { it.copy(echoCancellation = !it.echoCancellation) },
            { it.copy(ipv6 = !it.ipv6) },
            // Whether calls are recorded, and whether by default, is the administrator's.
            { it.copy(callRecording = !it.callRecording) },
            { it.copy(recordByDefault = !it.recordByDefault) },
        )
        locked.forEachIndexed { index, change ->
            val wanted = change(current)
            assertNotEquals("Change $index changes nothing", current, wanted)
            assertEquals("Change $index was taken", current, ManagedConfig.localUpdate(current, wanted))
        }
    }

    @Test
    fun `who keeps do not disturb is locked while its switch is not`() {
        // What the advanced settings page does when the mode is changed: the mode stays, the switch follows.
        val server = current.copy(dndMode = DndMode.SERVER, doNotDisturb = true)
        val wanted = server.copy(dndMode = DndMode.SOFTWARE, doNotDisturb = false)
        val taken = ManagedConfig.localUpdate(server, wanted)
        assertEquals(server.copy(doNotDisturb = false), taken)
        assertNotEquals("The refusal does not show", wanted, taken)
    }

    @Test
    fun `nothing changes when nothing is asked for`() {
        assertEquals(current, ManagedConfig.localUpdate(current, current))
    }

    @Test
    fun `the password of the configuration is the one its hash was made of`() {
        // `printf 'abc' | sha256sum`, and the same for a password that is not ASCII.
        val abc = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        val chinese = "6608321b199d7bba1d7fd8c4dc6daf3c808e1b05598c8607898061dab35fa667"
        assertTrue(ManagedConfig.isPassword(abc, "abc"))
        assertTrue(ManagedConfig.isPassword(abc.uppercase(), "abc"))
        assertTrue(ManagedConfig.isPassword("  $abc\n", "abc"))
        assertTrue(ManagedConfig.isPassword(chinese, "查看密码1"))
        assertFalse(ManagedConfig.isPassword(abc, "abd"))
        assertFalse(ManagedConfig.isPassword(abc, "ABC"))
        assertFalse(ManagedConfig.isPassword(abc, ""))
        assertFalse(ManagedConfig.isPassword(abc, abc))
    }

    @Test
    fun `without a hash, or with something that is none, nothing is the password`() {
        assertFalse(ManagedConfig.isPassword("", ""))
        assertFalse(ManagedConfig.isPassword("", "abc"))
        assertFalse(ManagedConfig.isPassword("   ", "abc"))
        assertFalse(ManagedConfig.isPassword("abc", "abc"))
        assertFalse(ManagedConfig.isPassword("not a hash", "not a hash"))
    }
}
