package com.sky.siplink.core.logic

import com.sky.siplink.core.model.AccountDraft
import com.sky.siplink.core.model.DndMode
import com.sky.siplink.core.model.DtmfMode
import com.sky.siplink.core.model.FeatureCodes
import com.sky.siplink.core.model.LdapDirectory
import com.sky.siplink.core.model.LdapSecurity
import com.sky.siplink.core.model.MediaSecurity
import com.sky.siplink.core.model.Settings
import com.sky.siplink.core.model.Shortcut
import com.sky.siplink.core.model.ShortcutAction
import com.sky.siplink.core.model.ShortcutIcon
import com.sky.siplink.core.model.SipTransport
import com.sky.siplink.core.model.VideoSize
import com.sky.siplink.zh
import com.sky.siplink.zhReserved
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The provisioning document of the management platform as the app reads it. The fixture
 * (remote-document.json) is written from §3.5 of the platform's design: the platform repository
 * had no golden document yet. Its CA is made when the test runs.
 */
class RemoteDocumentTest {
    private val ca = TestCertificates.ca()

    private val fixture: String = javaClass.classLoader!!.getResource("remote-document.json")!!.readText()
        .replace("\"@CA@\"", Json.write(CertificateTools.toPem(ca.certificate)))

    private fun parsed(text: String): RemoteDocument {
        val result = RemoteDocument.parse(text, zhReserved)
        assertTrue("Refused: $result", result is RemoteDocument.Parsed.Ok)
        return (result as RemoteDocument.Parsed.Ok).document
    }

    /** What is wrong with [text], in the user's Chinese texts. */
    private fun errors(text: String): List<String?> {
        val result = RemoteDocument.parse(text, zhReserved)
        assertTrue("Taken: $result", result is RemoteDocument.Parsed.Invalid)
        return (result as RemoteDocument.Parsed.Invalid).errors.map(::zh)
    }

    /** The fixture with the value at [path] (keys of objects inside each other) replaced by [json]. */
    @Suppress("UNCHECKED_CAST")
    private fun with(json: String, vararg path: String): String {
        val root = Json.parse(fixture) as MutableMap<String, Any?>
        val node = path.dropLast(1).fold(root) { node, key -> node[key] as MutableMap<String, Any?> }
        node[path.last()] = Json.parse(json)
        return Json.write(root)
    }

    /** The fixture with element [index] of the list [list] changed by [change]. */
    @Suppress("UNCHECKED_CAST")
    private fun withElement(list: String, index: Int, change: (MutableMap<String, Any?>) -> Unit): String {
        val root = Json.parse(fixture) as MutableMap<String, Any?>
        change((root[list] as List<Any?>)[index] as MutableMap<String, Any?>)
        return Json.write(root)
    }

    /**
     * A platform newer than the app sends fields the app has never heard of. They are passed
     * over wherever they stand, so that the platform need not know which version it talks to.
     */
    @Test
    @Suppress("UNCHECKED_CAST")
    fun fieldsTheAppDoesNotKnowArePassedOver() {
        val root = Json.parse(fixture) as MutableMap<String, Any?>
        var marked = 0
        fun mark(node: Any?) {
            when (node) {
                is MutableMap<*, *> -> {
                    node.values.toList().forEach(::mark)
                    (node as MutableMap<String, Any?>)["fieldOfALaterVersion"] = mapOf("any" to listOf(1, "two", null))
                    marked++
                }
                is List<*> -> node.forEach(::mark)
            }
        }
        mark(root)
        assertTrue("The fixture has objects at several levels", marked > 10)
        assertEquals(parsed(fixture), parsed(Json.write(root)))
    }

    @Test
    fun everyFieldBecomesWhatTheDesignSays() {
        val document = parsed(fixture)
        assertEquals(1, document.schema)
        assertEquals("4f2a9c0b7e1d3a5c6b8e0f1a2d3c4b5a6f7e8d9c0b1a2f3e4d5c6b7a8f9e0d1c", document.version)
        assertEquals("2026-11-16T08:30:00Z", document.generatedAt)
        assertEquals("dK3y-ExampleOnly_0123456789abcdefghijklmnopq", document.deviceKey)
        assertEquals("示例平台", document.platformName)
        assertEquals("https://platform.example.com:8443/api/device/v1/sync", document.syncUrl)
        assertEquals("前台-01", document.clientName)
        assertEquals("华东", document.region)
        assertEquals(30, document.refreshMinutes)
        // Kept in lower case, as ManagedConfig.isPassword compares either.
        assertEquals("8d969eef6ecad3c29a3a629280e686cf0c3f5d5a86aff3ca12020c923adc6c92", document.configPasswordHash)
        assertTrue(ManagedConfig.isPassword(document.configPasswordHash, "123456"))

        assertEquals(
            Settings(
                keepAlive = false,
                intercomAutoAnswer = true,
                intercomSpeaker = false,
                autoAnswer = true,
                autoAnswerSeconds = 10,
                verifyTlsCertificate = true,
                verifyTlsHostname = false,
                mediaSecurity = MediaSecurity.SRTP_OPTIONAL,
                dndMode = DndMode.SERVER,
                callWaiting = false,
                rejectAnonymous = true,
                dtmfMode = DtmfMode.SIP_INFO,
                forwardAlways = "",
                forwardBusy = "8001",
                forwardNoAnswer = "8002",
                forwardNoAnswerSeconds = 25,
                features = FeatureCodes("701", "*81", "*82", "*83", "*84", "*8", "", "*98", "*90", "*76", "*77"),
                videoCalls = false,
                videoSize = VideoSize.HD,
                videoFrameRate = 15,
                autoUpdateCheck = false,
                // Sent or not, the do not disturb switch is no part of what is handed out.
                doNotDisturb = false,
            ),
            document.settings,
        )

        assertEquals(
            listOf(
                RemoteAccount(
                    "p1",
                    AccountDraft(
                        name = "总机", username = "1001", password = "example-password-1", domain = "pbx.example.com", port = "5061",
                        displayName = "前台", authUser = "auth1001", server = "sbc.example.com:5061", backupServer = "sbc2.example.com:5061",
                        dnsServer = "192.0.2.53", transport = SipTransport.TLS, expires = 600, subscribeMwi = true, enabled = true,
                    ),
                ),
                RemoteAccount("p2", AccountDraft(username = "2001", password = "example-password-2", domain = "192.0.2.10")),
            ),
            document.accounts,
        )
        assertEquals("p2", document.defaultAccount)
        assertEquals(
            RemoteCodecs(
                audio = listOf(CodecChoice("opus/48000/2", true), CodecChoice("PCMA/8000/1", false)),
                video = listOf(CodecChoice("VP8/90000/0", true)),
            ),
            document.codecs,
        )
        assertEquals(listOf(CertificateTools.fingerprint(ca.certificate)), document.caCertificates.map(CertificateTools::fingerprint))

        assertEquals(
            listOf(
                RemoteDirectory(
                    "d1",
                    LdapDirectory(
                        name = "总部", enabled = true, host = "ldap.example.com", port = 1389, security = LdapSecurity.START_TLS,
                        bindDn = "uid=abc,ou=clients,dc=siplink", password = "example-ldap-password", baseDn = "ou=staff,ou=books,dc=siplink",
                        lookupIncoming = true, lookupOutgoing = false, maxResults = 200, nameAttributes = "cn",
                        numberAttributes = "telephoneNumber mobile", nameFilter = "", numberFilter = "(telephoneNumber=%)",
                    ),
                    lineKey = "p1",
                ),
                RemoteDirectory("d2", LdapDirectory(host = "192.0.2.20", baseDn = "dc=example,dc=com"), lineKey = ""),
            ),
            document.directories,
        )

        assertEquals(
            listOf(
                Shortcut(0, "前台", "1000", ShortcutAction.SPEED_DIAL, "常用", "总机", ShortcutIcon.RECEPTION),
                Shortcut(0, "转财务", "1200", ShortcutAction.BLIND_TRANSFER, "转接"),
                Shortcut(0, "咨询", "1300", ShortcutAction.ATTENDED_TRANSFER),
                Shortcut(0, "会议密码", "1234#", ShortcutAction.DTMF),
                Shortcut(0, "转手机", "8001", ShortcutAction.FORWARD),
                Shortcut(0, "免打扰", "", ShortcutAction.DND, icon = ShortcutIcon.LOCK),
            ),
            document.shortcuts,
        )
    }

    @Test
    fun aMissingFieldIsTheAppsDefaultNotThePhonesValue() {
        val document = parsed("""{"schema": 1, "version": "v1"}""")
        assertEquals(Settings(), document.settings)
        assertEquals(RemoteDocument.DEFAULT_REFRESH_MINUTES, document.refreshMinutes)
        assertNull("No codecs: the SDK's defaults", document.codecs)
        assertTrue(document.accounts.isEmpty() && document.directories.isEmpty() && document.shortcuts.isEmpty() && document.caCertificates.isEmpty())
        assertEquals("", document.configPasswordHash)
        assertEquals("", document.deviceKey)
        // Inside an object as well: a feature code that is not sent is the app's.
        assertEquals(FeatureCodes(park = "701"), parsed(with("""{"park": "701"}""", "settings", "features")).settings.features)
        assertEquals(AccountDraft(username = "2001", password = "example-password-2", domain = "192.0.2.10"), parsed(fixture).accounts[1].draft)
    }

    @Test
    fun theOptionsOfTheSipLibraryAreReadAndAnOlderPlatformLeavesTheDefaults() {
        // The fixture is from before the platform sent them: the app's defaults.
        val older = parsed(fixture).settings
        assertEquals(listOf("", false, true, true), listOf(older.userAgent, older.adaptiveRateControl, older.echoCancellation, older.ipv6))
        val newer = parsed(
            with("""{"userAgent": "Example Phone/2.0", "adaptiveRateControl": true, "echoCancellation": false, "ipv6": false}""", "settings"),
        ).settings
        assertEquals(listOf("Example Phone/2.0", true, false, false), listOf(newer.userAgent, newer.adaptiveRateControl, newer.echoCancellation, newer.ipv6))
    }

    @Test
    fun recordingIsAllowedAndNotByDefaultUnlessTheDocumentSaysOtherwise() {
        // The fixture is from before the platform sent the two keys.
        val older = parsed(fixture).settings
        assertEquals(true to false, older.callRecording to older.recordByDefault)
        val newer = parsed(with("""{"callRecording": false, "recordByDefault": true}""", "settings")).settings
        assertEquals(false to true, newer.callRecording to newer.recordByDefault)
        assertEquals(listOf("settings.callRecording 格式不正确"), errors(with("\"no\"", "settings", "callRecording")))
    }

    @Test
    fun theSizeOfTheRoomIsTheAppsUnlessSentAndStaysInItsRange() {
        // The fixture is from before the platform sent it.
        assertEquals(RoomRules.LIMIT, parsed(fixture).settings.roomLimit)
        assertEquals(8, parsed(with("8", "settings", "roomLimit")).settings.roomLimit)
        assertEquals(RoomRules.MIN_LIMIT, parsed(with("1", "settings", "roomLimit")).settings.roomLimit)
        assertEquals(RoomRules.LIMIT, parsed(with("100", "settings", "roomLimit")).settings.roomLimit)
        assertEquals(listOf("settings.roomLimit 格式不正确"), errors(with("\"8\"", "settings", "roomLimit")))
    }

    @Test
    fun theFastTimeoutIsOffUnlessSentAndStaysInItsRange() {
        assertEquals(0, parsed(fixture).accounts[0].draft.serverTimeoutMs)
        fun timeout(value: Long) = parsed(withElement("accounts", 0) { it["serverTimeoutMs"] = value }).accounts[0].draft.serverTimeoutMs
        assertEquals(500, timeout(500))
        assertEquals(0, timeout(0))
        assertEquals(ServerChoice.MIN_TIMEOUT_MS, timeout(50))
        assertEquals(ServerChoice.MAX_TIMEOUT_MS, timeout(30_000))
        assertEquals(listOf("accounts[0].serverTimeoutMs 格式不正确"), errors(withElement("accounts", 0) { it["serverTimeoutMs"] = "500" }))
    }

    @Test
    fun theLinesAdaptWithoutStunUnlessTheDocumentSaysOtherwise() {
        // The fixture is from before the platform sent the two keys.
        val older = parsed(fixture).settings
        assertEquals(true to "", older.autoNetwork to older.stunServer)
        val newer = parsed(with("""{"autoNetwork": false, "stunServer": "stun.example.com:3478"}""", "settings")).settings
        assertEquals(false to "stun.example.com:3478", newer.autoNetwork to newer.stunServer)
        assertEquals(listOf("settings.autoNetwork 格式不正确"), errors(with("\"no\"", "settings", "autoNetwork")))
        assertEquals(listOf("settings.stunServer 格式不正确"), errors(with("3478", "settings", "stunServer")))
        // Written as an account's server address is, or the document is refused.
        assertEquals("[2001:db8::1]", parsed(with("\"[2001:db8::1]\"", "settings", "stunServer")).settings.stunServer)
        assertEquals(listOf("settings.stunServer 格式不正确"), errors(with("\"stun:stun.example.com\"", "settings", "stunServer")))
        assertEquals(listOf("settings.stunServer 格式不正确"), errors(with("\" stun.example.com\"", "settings", "stunServer")))
    }

    @Test
    fun autoAnswerIsOffUnlessSentAndItsTimeIsOneThatCanBeChosen() {
        val absent = parsed(with("""{"intercomAutoAnswer": true}""", "settings")).settings
        assertEquals(false to AutoAnswerRules.DEFAULT_SECONDS, absent.autoAnswer to absent.autoAnswerSeconds)
        // A time the app does not offer becomes the nearest one it does.
        val odd = parsed(with("""{"autoAnswer": true, "autoAnswerSeconds": 7}""", "settings")).settings
        assertEquals(true to 5, odd.autoAnswer to odd.autoAnswerSeconds)
        assertEquals(0, parsed(with("-4", "settings", "autoAnswerSeconds")).settings.autoAnswerSeconds)
        assertEquals(30, parsed(with("600", "settings", "autoAnswerSeconds")).settings.autoAnswerSeconds)
        assertEquals(listOf("settings.autoAnswer 格式不正确"), errors(with("\"yes\"", "settings", "autoAnswer")))
        assertEquals(listOf("settings.autoAnswerSeconds 格式不正确"), errors(with("\"3\"", "settings", "autoAnswerSeconds")))
    }

    @Test
    fun aUserAgentTheRuleRefusesRefusesTheDocument() {
        assertEquals(listOf(zh(UserAgent.Error.SPACE_AT_ENDS.full)), errors(with("\" Example\"", "settings", "userAgent")))
        assertEquals(listOf(zh(UserAgent.Error.NOT_PRINTABLE_ASCII.full)), errors(with("\"话机\"", "settings", "userAgent")))
        assertEquals(listOf("settings.ipv6 格式不正确"), errors(with("\"yes\"", "settings", "ipv6")))
    }

    @Test
    fun nullIsMissing() {
        assertEquals(Settings().keepAlive, parsed(with("null", "settings", "keepAlive")).settings.keepAlive)
    }

    @Test
    fun theRefreshIntervalStaysInItsRange() {
        assertEquals(RemoteDocument.MIN_REFRESH_MINUTES, parsed(with("1", "refreshMinutes")).refreshMinutes)
        assertEquals(RemoteDocument.MAX_REFRESH_MINUTES, parsed(with("5000", "refreshMinutes")).refreshMinutes)
    }

    @Test
    fun aLaterSchemaAsksForAnUpdateAndAnEarlierOneIsRefused() {
        assertEquals(listOf(zh(RemoteChecks.NEEDS_UPDATE)), errors(with("2", "schema")))
        assertEquals(listOf("不支持的配置格式"), errors(with("0", "schema")))
        assertEquals(listOf("不支持的配置格式"), errors("""{"version": "v1"}"""))
        assertEquals(listOf("配置缺少版本"), errors("""{"schema": 1}"""))
    }

    @Test
    fun whatIsNotADocumentIsRefused() {
        assertEquals(listOf("配置不是有效的 JSON"), errors("<html>"))
        assertEquals(listOf("配置不是 JSON 对象"), errors("[]"))
    }

    @Test
    fun fieldsOfAnotherTypeAreNamed() {
        assertEquals(listOf("settings.keepAlive 格式不正确"), errors(with("\"yes\"", "settings", "keepAlive")))
        assertEquals(listOf("settings.mediaSecurity 格式不正确"), errors(with("\"srtp\"", "settings", "mediaSecurity")))
        assertEquals(listOf("settings.forwardNoAnswerSeconds 格式不正确"), errors(with("20.5", "settings", "forwardNoAnswerSeconds")))
        assertEquals(listOf("accounts[0].port 格式不正确"), errors(withElement("accounts", 0) { it["port"] = 5061L }))
        assertEquals(listOf("accounts[1].transport 格式不正确"), errors(withElement("accounts", 1) { it["transport"] = "udp" }))
        assertEquals(listOf("accounts[2] 格式不正确"), errors(with("""[{"key":"a","username":"1","domain":"example.com"},{"key":"b","username":"2","domain":"example.com"},"x"]""", "accounts")))
        assertEquals(listOf("CA 证书 1：无法解析"), errors(with("""["-----BEGIN CERTIFICATE-----\nAAAA\n-----END CERTIFICATE-----"]""", "caCertificates")))
        assertEquals(listOf("shortcuts[0].action 格式不正确"), errors(withElement("shortcuts", 0) { it["action"] = "call" }))
        assertEquals(listOf("shortcuts[1].icon 格式不正确"), errors(withElement("shortcuts", 1) { it["icon"] = "rocket" }))
    }

    @Test
    fun theAccountsHaveToPassTheAppsRules() {
        val duplicate = errors(withElement("accounts", 1) { it["username"] = "1001"; it["domain"] = "PBX.example.com:5061" })
        assertEquals(listOf("账号“1001”：" + zh(AccountRules.Error.DUPLICATE.full)), duplicate)
        assertEquals(
            listOf("账号“总机”：" + zh(AccountRules.Error.NAME_TAKEN.full)),
            errors(withElement("accounts", 1) { it["name"] = "总机" }),
        )
        assertEquals(
            listOf("账号“20 01”：" + zh(AccountRules.Error.USERNAME_INVALID.full)),
            errors(withElement("accounts", 1) { it["username"] = "20 01" }),
        )
        assertEquals(listOf("账号“2001”：key 重复"), errors(withElement("accounts", 1) { it["key"] = "p1" }))
        assertEquals(listOf("账号“2001”：缺少 key"), errors(withElement("accounts", 1) { it.remove("key") }))
        assertEquals(
            listOf("账号“总机”：" + zh(AccountRules.Error.EXPIRES_OUT_OF_RANGE.full)),
            errors(withElement("accounts", 0) { it["expires"] = 30L }),
        )
    }

    @Test
    fun theDirectoriesHaveToPassTheAppsRules() {
        assertEquals(
            listOf("通讯录“192.0.2.20”：" + zh(LdapRules.Error.STARTTLS_NEEDS_HOST_NAME.full)),
            errors(withElement("directories", 1) { it["security"] = "START_TLS" }),
        )
        assertEquals(
            listOf("通讯录“总部”：" + zh(LdapRules.Error.HOST_LDAPS.full)),
            errors(withElement("directories", 0) { it["host"] = "ldaps://ldap.example.com" }),
        )
        assertEquals(
            listOf("通讯录“总部”：" + zh(LdapRules.Error.NUMBER_FILTER_NO_PLACEHOLDER.full)),
            errors(withElement("directories", 0) { it["numberFilter"] = "(telephoneNumber=1)" }),
        )
        assertEquals(
            listOf("通讯录“总部”：" + zh(LdapRules.Error.NAME_TAKEN.full)),
            errors(withElement("directories", 1) { it["name"] = "总部" }),
        )
    }

    @Test
    fun theShortcutKeysHaveToPassTheAppsRules() {
        assertEquals(
            listOf("快捷键“会议密码”：" + zh(ShortcutRules.Error.DTMF_INVALID.full)),
            errors(withElement("shortcuts", 3) { it["number"] = "12a" }),
        )
        assertEquals(
            listOf("快捷键“前台”：" + zh(ShortcutRules.Error.CATEGORY_RESERVED.full)),
            errors(withElement("shortcuts", 0) { it["category"] = "全部" }),
        )
        val many = (0..ShortcutRules.MAX_SHORTCUTS).joinToString(",", "[", "]") { """{"label":"k$it","number":"$it"}""" }
        assertEquals(listOf(zh(ShortcutRules.Error.LIMIT_REACHED.full)), errors(with(many, "shortcuts")))
    }

    @Test
    fun theSettingsStayInTheirRanges() {
        assertEquals(listOf("无应答等待时间应为 5–120 秒"), errors(with("3", "settings", "forwardNoAnswerSeconds")))
        assertEquals(listOf("视频帧率应为 0 / 15 / 24 / 30"), errors(with("25", "settings", "videoFrameRate")))
        assertEquals(listOf("配置密码格式不正确"), errors(with("\"abc\"", "configPasswordHash")))
    }

    @Test
    fun everyFaultIsReportedAtOnce() {
        // A field of the wrong type is reported before the rules are looked at.
        val text = with("""{"keepAlive": 1, "videoFrameRate": 25}""", "settings")
        assertEquals(listOf("settings.keepAlive 格式不正确"), errors(text))
        @Suppress("UNCHECKED_CAST")
        val both = withElement("accounts", 1) { it["username"] = "" }.let { Json.parse(it) as MutableMap<String, Any?> }
            .also { it["configPasswordHash"] = "xyz" }.let(Json::write)
        assertEquals(listOf("配置密码格式不正确", "账号“p2”：" + zh(AccountRules.Error.USERNAME_EMPTY.full)), errors(both))
    }

    @Test
    fun theDeviceKeyIsFoundInADocumentThatIsRefused() {
        assertEquals("dK3y-ExampleOnly_0123456789abcdefghijklmnopq", RemoteDocument.deviceKeyOf(with("2", "schema")))
        assertEquals("", RemoteDocument.deviceKeyOf("not json"))
    }
}
