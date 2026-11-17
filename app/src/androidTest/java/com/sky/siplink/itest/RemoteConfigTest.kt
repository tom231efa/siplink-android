package com.sky.siplink.itest

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sky.siplink.core.logic.Json
import com.sky.siplink.core.logic.ManagedConfig
import com.sky.siplink.core.logic.RemoteAddress
import com.sky.siplink.core.model.Shortcut
import com.sky.siplink.core.model.SipTransport
import com.sky.siplink.core.model.ThemeMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The remote configuration from end to end, without the management platform: [PlatformDouble]
 * answers in its place, over plain HTTP on 127.0.0.1 (which debug builds accept). The address is
 * typed in, as "输入地址" offers; the camera is not needed.
 *
 * The configuration it hands out points at the PBX and the LDAP server of testenv/, so that the
 * account registers as a real one would. Pictures of the screens are saved in the cache
 * directory of the app (itest-remote/).
 */
@RunWith(AndroidJUnit4::class)
class RemoteConfigTest : ScreenTestBase(PICTURES) {
    private lateinit var platform: PlatformDouble

    /** What the platform answers a sync with; the test changes it as the platform would change. */
    @Volatile
    private var syncAnswer = PlatformDouble.Response(204)
    private val enrollUrl get() = "${platform.origin}/p/$TOKEN"

    @Before
    fun startPlatform() {
        platform = PlatformDouble()
        await("the shortcut keys to be read") { engine.shortcuts.loaded.value }
        // The question about the lock screen would stand in front of the screens.
        onMain { engine.updateSettings { it.copy(lockScreenPromptDeclined = true) } }
    }

    @After
    fun stopPlatform() {
        if (::platform.isInitialized) {
            // A configuration a failed test left behind goes, with the platform still there to be told.
            if (engine.remote.status.value.enrolled) {
                await("the last request to end") { !engine.remote.status.value.busy }
                platform.answer = { PlatformDouble.Response(204) }
                onMain { engine.remote.delete {} }
                await("the remote configuration to be gone") { !engine.remote.status.value.enrolled }
            }
            platform.close()
        }
        onMain { engine.updateSettings { it.copy(themeMode = ThemeMode.SYSTEM) } }
    }

    // ------------------------------------------------------------------ what the platform hands out

    /**
     * A document as §3.5 of the platform's design describes it.
     *
     * @param version what tells this document from others
     * @param deviceKey sent only when the phone binds itself
     */
    private fun document(
        version: Char,
        lineName: String = LINE,
        forwardBusy: String = "$CUSTOMER",
        keys: List<Pair<String, String>> = listOf(SPEED_KEY to "$AGENT"),
        transport: SipTransport = SipTransport.UDP,
        deviceKey: String? = null,
    ): String {
        // Over TLS the account is trusted through the CA of the same document, by the name in the PBX's certificate.
        val domain = if (transport == SipTransport.TLS) env.pbxName else env.pbxHost
        val document = linkedMapOf<String, Any?>(
            "schema" to 1,
            "version" to version.toString().repeat(64),
            "generatedAt" to "2026-11-16T08:30:00Z",
            "platform" to mapOf("name" to PLATFORM, "syncUrl" to "${platform.origin}/api/device/v1/sync"),
            "client" to mapOf("name" to CLIENT, "region" to REGION),
            "refreshMinutes" to 60,
            "configPasswordHash" to "",
            "settings" to mapOf("forwardBusy" to forwardBusy, "videoCalls" to false),
            "accounts" to listOf(
                mapOf(
                    "key" to "p1", "name" to lineName, "username" to "$APP", "password" to env.sipPassword,
                    "domain" to domain, "transport" to transport.name,
                ),
            ),
            "defaultAccount" to "p1",
            "caCertificates" to listOf(String(env.caPem, Charsets.US_ASCII)),
            "directories" to listOf(
                mapOf(
                    "key" to "d1", "name" to DIRECTORY, "host" to env.ldapHost, "bindDn" to env.ldapBindDn,
                    "password" to env.ldapPassword, "baseDn" to env.ldapBaseDn, "line" to "p1",
                ),
            ),
            "shortcuts" to keys.map { (label, number) -> mapOf("label" to label, "number" to number, "action" to "speed", "category" to "常用") },
        )
        if (deviceKey != null) document["deviceKey"] = deviceKey
        return Json.write(document)
    }

    /** Answers the binding with the first document (its account over [transport]), and syncs with [syncAnswer]. */
    private fun platformAnswers(transport: SipTransport = SipTransport.UDP) {
        platform.answer = { request ->
            when {
                request.method != "POST" -> PlatformDouble.Response(405)
                request.path == "/p/$TOKEN" -> PlatformDouble.Response(200, document('a', transport = transport, deviceKey = DEVICE_KEY))
                request.headers["authorization"] != "Bearer $DEVICE_KEY" -> PlatformDouble.Response(401, """{"error":"unauthorized"}""")
                request.path == "/api/device/v1/sync" -> syncAnswer
                request.path == "/api/device/v1/leave" -> PlatformDouble.Response(204)
                else -> PlatformDouble.Response(404)
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun bodyOf(request: PlatformDouble.Request): Map<String, Any?> = Json.parse(request.body) as Map<String, Any?>

    private fun syncs(): List<PlatformDouble.Request> = platform.requests.filter { it.path == "/api/device/v1/sync" }

    /** Binds the app to the platform by typing the address into the remote configuration screen. */
    private fun enrollByHand() {
        tap("设置")
        tap("远程配置")
        see("扫描二维码")
        picture("empty")
        tap("输入地址")
        type(enrollUrl)
        picture("address")
        tap("下一步")
        see("载入远程配置？")
        tap("载入")
        await("the configuration to be applied") { engine.lock.managed.value && engine.accounts.accounts.value.isNotEmpty() }
    }

    // ------------------------------------------------------------------ the tests

    @Test
    fun aConfigurationIsLoadedSyncedAndTakenBack() {
        platformAnswers()
        showApp()
        enrollByHand()

        // The binding: the token in the path, no device key yet, the report of the phone.
        val enrollment = platform.requests.single { it.path == "/p/$TOKEN" }
        assertFalse("The binding carried a device key", "authorization" in enrollment.headers)
        val sent = bodyOf(enrollment)
        assertTrue("No installation id", (sent["installId"] as String).isNotEmpty())
        assertTrue("No report", (sent["device"] as Map<*, *>).containsKey("appVersion"))

        // Everything that was handed out is there.
        val account = awaitRegistered(APP)
        assertEquals(LINE, account.name)
        assertEquals("$CUSTOMER", engine.settings.current.forwardBusy)
        assertFalse(engine.settings.current.videoCalls)
        val directory = engine.directory.directories.value.single()
        assertEquals(DIRECTORY, directory.name)
        assertEquals("The directory is not on the line", account.id, directory.line)
        assertEquals(1, engine.tls.certificates.value.size)
        assertEquals(listOf(SPEED_KEY), engine.shortcuts.shortcuts.value.map { it.label })
        see(PLATFORM)
        see(CLIENT)
        see(REGION)
        picture("enrolled")

        // The screens are locked; the remote configuration screen itself is the user's.
        back()
        see(ManagedConfig.LOCKED)
        locked("视频通话")
        usable("免打扰", "深色")
        tap("关于")
        locked("自动检查更新")
        back()
        tap("深色")
        await("the dark theme") { engine.settings.current.themeMode == ThemeMode.DARK }

        // Unchanged: 204, nothing moves.
        tap("远程配置")
        tap("立即同步")
        await("a sync") { syncs().size == 1 }
        assertEquals("a".repeat(64), bodyOf(syncs().last())["have"])
        see("上次同步")
        absent("尚未成功")
        assertEquals(account.id, engine.accounts.accounts.value.single().id)

        // A key of the user's own next to the handed-out ones.
        onMain { assertTrue(engine.shortcuts.add(Shortcut(0, OWN_KEY, "$AGENT")).isEmpty()) }
        assertTrue(engine.shortcuts.shortcuts.value.single { it.label == OWN_KEY }.own)

        // Changed on the platform: the same account, renamed; the same directory; another key; another forwarding.
        syncAnswer = PlatformDouble.Response(
            200,
            document('b', lineName = RENAMED_LINE, forwardBusy = "$ECHO_PHONE", keys = listOf(SPEED_KEY to "$AGENT", SECOND_KEY to "$CUSTOMER")),
        )
        tap("立即同步")
        await("the changed configuration") { engine.settings.current.forwardBusy == "$ECHO_PHONE" }
        assertEquals("a".repeat(64), bodyOf(syncs().last())["have"])
        val renamed = engine.accounts.accounts.value.single()
        assertEquals("The account was made anew instead of changed", account.id, renamed.id)
        assertEquals(RENAMED_LINE, renamed.name)
        assertEquals(directory.id, engine.directory.directories.value.single().id)
        // The user's own key stays ahead of those the platform hands out.
        assertEquals(listOf(OWN_KEY, SPEED_KEY, SECOND_KEY), engine.shortcuts.shortcuts.value.map { it.label })
        assertTrue("The user's own key is gone", engine.shortcuts.shortcuts.value.single { it.label == OWN_KEY }.own)
        assertEquals(ThemeMode.DARK, engine.settings.current.themeMode)
        awaitRegistered(APP)

        // The next sync says it has the new one.
        syncAnswer = PlatformDouble.Response(204)
        tap("立即同步")
        await("a third sync") { syncs().size == 3 }
        assertEquals("b".repeat(64), bodyOf(syncs().last())["have"])

        // Deleted on the platform: everything that was handed out goes, and the user's own keys with it.
        syncAnswer = PlatformDouble.Response(410, """{"error":"revoked","reason":"deleted"}""")
        tap("立即同步")
        await("the configuration to be taken back") { !engine.lock.managed.value }
        see("扫描二维码")
        see("远程配置已被平台收回")
        picture("revoked")
        assertTrue("Accounts are left", engine.accounts.accounts.value.isEmpty())
        assertTrue("Directories are left", engine.directory.directories.value.isEmpty())
        assertTrue("CAs are left", engine.tls.certificates.value.isEmpty())
        assertTrue("Shortcut keys are left", engine.shortcuts.shortcuts.value.isEmpty())
        assertEquals("", engine.settings.current.forwardBusy)
        assertTrue(engine.settings.current.videoCalls)
        // What was the user's own stays.
        assertEquals(ThemeMode.DARK, engine.settings.current.themeMode)
        assertFalse(engine.remote.status.value.enrolled)
        // The account said goodbye to the PBX before the CAs went, which drops the SIP connections.
        await("the PBX to forget the contact of $APP") { !pbxHasContact(APP) }
        back()
        usable("视频通话")
        tap("关于")
        usable("自动检查更新")
        back()
    }

    @Test
    fun deletingByHandTellsThePlatformAndTakesEverythingBack() {
        // Over TLS, trusted only through the handed-out CA: the account has to unregister before that CA goes.
        platformAnswers(SipTransport.TLS)
        onMain {
            val parsed = engine.remote.parse(enrollUrl) as RemoteAddress.Parsed.Ok
            engine.remote.enroll(parsed.enrollment) {}
        }
        await("the configuration to be applied") { engine.lock.managed.value && engine.accounts.accounts.value.isNotEmpty() }
        onMain { assertTrue(engine.shortcuts.add(Shortcut(0, OWN_KEY, "$AGENT")).isEmpty()) }
        awaitRegistered(APP)
        await("the PBX to know the contact of $APP") { pbxHasContact(APP) }
        assertTrue("The PBX contact is not a TLS one", "transport=tls" in pbx.command("pjsip show aor $APP").lowercase())
        showApp()

        tap("设置")
        tap("远程配置")
        see(CLIENT)
        tap("删除远程配置")
        see("删除远程配置？")
        picture("delete")
        tap("删除")
        await("the configuration to be taken back") { !engine.lock.managed.value }
        val leave = platform.requests.single { it.path == "/api/device/v1/leave" }
        assertEquals("Bearer $DEVICE_KEY", leave.headers["authorization"])
        see("扫描二维码")
        assertTrue(engine.accounts.accounts.value.isEmpty())
        assertTrue(engine.shortcuts.shortcuts.value.isEmpty())
        assertTrue(engine.tls.certificates.value.isEmpty())
        assertFalse(engine.remote.status.value.enrolled)
        await("the PBX to forget the contact of $APP") { !pbxHasContact(APP) }
    }

    @Test
    fun aUsedQrCodeChangesNothing() {
        // The platform's answer to a token that is unknown, used or expired.
        platform.answer = { PlatformDouble.Response(404, """{"error":"invalid_token"}""") }
        showApp()
        tap("设置")
        tap("远程配置")
        tap("输入地址")
        type(enrollUrl)
        tap("下一步")
        tap("载入")
        see("二维码无效、已使用或已过期")
        assertFalse(engine.lock.managed.value)
        assertFalse(engine.remote.status.value.enrolled)

        // An address that is none is refused before anything is sent.
        val asked = platform.requests.size
        tap("输入地址")
        type("${platform.origin}/settings")
        tap("下一步")
        see(RemoteAddress.Error.NOT_ENROLLMENT.message)
        assertEquals(asked, platform.requests.size)
    }

    private companion object {
        const val PICTURES = "itest-remote"

        /** Made up for the tests: the double knows no other. */
        const val TOKEN = "itestEnrollToken0123456789abcdefghijklmnopq"
        const val DEVICE_KEY = "itestDeviceKey0123456789abcdefghijklmnopqrs"

        const val PLATFORM = "测试平台"
        const val CLIENT = "前台-01"
        const val REGION = "测试区"
        const val LINE = "总机"
        const val RENAMED_LINE = "总台"
        const val DIRECTORY = "总部"
        const val SPEED_KEY = "前台"
        const val SECOND_KEY = "客服"
        const val OWN_KEY = "我的自建键"
    }
}
