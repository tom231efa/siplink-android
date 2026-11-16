package com.sky.siplink.core.logic

import com.sky.siplink.core.model.LdapSecurity
import com.sky.siplink.core.model.Settings
import com.sky.siplink.core.model.ShortcutAction
import com.sky.siplink.core.model.ShortcutIcon
import com.sky.siplink.core.model.SipTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The golden documents of the management platform, copied unchanged from its repository
 * (`docs/examples/provisioning-document.json`, the three of `internal/provision/testdata/`): the app has
 * to take each of them as the platform builds it. Their CAs and passwords are test data.
 */
class PlatformDocumentsTest {
    private fun read(name: String): RemoteDocument {
        val text = javaClass.classLoader!!.getResource(name)!!.readText()
        val result = RemoteDocument.parse(text)
        assertTrue("$name refused: $result", result is RemoteDocument.Parsed.Ok)
        return (result as RemoteDocument.Parsed.Ok).document
    }

    private fun caNames(document: RemoteDocument): List<String> =
        document.caCertificates.map { CertificateTools.commonName(it.subjectX500Principal.name) }

    /** What the example and the "full" document share: two lines, four directories, three keys. */
    private fun assertFull(document: RemoteDocument) {
        assertEquals(30, document.refreshMinutes)
        assertEquals("4b5ae2f2e1c6d27d16d43fae156c653347234dfb404fd3cb5104f5513efd6871", document.configPasswordHash)
        assertEquals(listOf("p1", "p2"), document.accounts.map { it.key })
        val (first, second) = document.accounts.map { it.draft }
        assertEquals(listOf("前台一号", "1001", "pbx.example.com", "", SipTransport.TLS), listOf(first.name, first.username, first.domain, first.port, first.transport))
        assertEquals(listOf("分部", "2001", "auth2001", "192.0.2.20", "5070", SipTransport.UDP), listOf(second.name, second.username, second.authUser, second.domain, second.port, second.transport))
        assertTrue("A line without its password", document.accounts.all { it.draft.password.isNotEmpty() })
        assertEquals("p2", document.defaultAccount)

        assertEquals(listOf("d1", "d2", "d3", "d4"), document.directories.map { it.key })
        assertEquals(listOf("员工通讯录", "公司目录", "个人目录", "公共目录"), document.directories.map { it.directory.name })
        assertEquals(listOf("p1", "", "", ""), document.directories.map { it.lineKey })
        assertEquals(listOf(true, true, true, false), document.directories.map { it.directory.enabled })
        assertEquals("(displayName=*%s*)", document.directories[2].directory.nameFilter)
        // The anonymous one has no password; the others bring theirs.
        assertEquals(listOf(true, true, true, false), document.directories.map { it.directory.password.isNotEmpty() })

        assertEquals(listOf("保安室", "转财务", "免打扰"), document.shortcuts.map { it.label })
        assertEquals(listOf(ShortcutAction.SPEED_DIAL, ShortcutAction.BLIND_TRANSFER, ShortcutAction.DND), document.shortcuts.map { it.action })
        assertEquals(listOf(ShortcutIcon.STAR, null, null), document.shortcuts.map { it.icon })
        assertEquals("前台一号", document.shortcuts[0].line)

        assertEquals(listOf("opus/48000/2", "PCMA/8000/1", "PCMU/8000/1"), document.codecs!!.audio.map { it.key })
        assertEquals("13800000000", document.settings.forwardAlways)
        assertEquals("*85", document.settings.features.park)
        assertEquals("SipLink-Example/1.0", document.settings.userAgent)
        assertEquals(true, document.settings.adaptiveRateControl)
        assertEquals(false, document.settings.echoCancellation)
        assertEquals(true, document.settings.ipv6)
    }

    @Test
    fun theExampleOfTheDesignIsTaken() {
        val document = read("platform-docs-examples-provisioning-document.json")
        assertFull(document)
        // The example is the answer to a binding: it carries the device key.
        assertEquals(43, document.deviceKey.length)
        assertEquals(listOf("SipLink CA 00000000", "Example PBX CA"), caNames(document))
        assertEquals(listOf(LdapSecurity.START_TLS, LdapSecurity.START_TLS, LdapSecurity.START_TLS, LdapSecurity.NONE), document.directories.map { it.directory.security })
        assertEquals("https://platform.example.com/api/device/v1/sync", document.syncUrl)
    }

    @Test
    fun theFullDocumentIsTaken() {
        val document = read("platform-provision-testdata-full.json")
        assertFull(document)
        assertEquals("", document.deviceKey)
        // The platform's own CA first, then the template's.
        assertEquals(listOf("SipLink CA 00000000", "Example PBX CA"), caNames(document))
        assertEquals(listOf(LdapSecurity.START_TLS, LdapSecurity.START_TLS, LdapSecurity.START_TLS, LdapSecurity.NONE), document.directories.map { it.directory.security })
    }

    @Test
    fun theHostedBookOverPlainLdapIsTaken() {
        val document = read("platform-provision-testdata-hosted-plain.json")
        assertFull(document)
        // A hosted book at a literal address goes without StartTLS, and the platform's CA is not handed out.
        assertEquals("192.0.2.10", document.directories[0].directory.host)
        assertEquals(listOf(LdapSecurity.NONE, LdapSecurity.START_TLS, LdapSecurity.START_TLS, LdapSecurity.NONE), document.directories.map { it.directory.security })
        assertEquals(listOf("Example PBX CA"), caNames(document))
    }

    @Test
    fun theMinimalDocumentIsTaken() {
        val document = read("platform-provision-testdata-minimal.json")
        assertEquals(60, document.refreshMinutes)
        assertEquals("", document.configPasswordHash)
        assertEquals(listOf("p1"), document.accounts.map { it.key })
        assertEquals(listOf("总部", "1001", "pbx.example.com", SipTransport.UDP), document.accounts[0].draft.let { listOf(it.name, it.username, it.domain, it.transport) })
        assertEquals("p1", document.defaultAccount)
        assertTrue(document.directories.isEmpty() && document.shortcuts.isEmpty() && document.caCertificates.isEmpty())
        assertNull("No codecs: the SDK's defaults", document.codecs)
        assertEquals("700", document.settings.features.park)
        assertEquals(Settings().copy(features = document.settings.features), document.settings)
    }
}
