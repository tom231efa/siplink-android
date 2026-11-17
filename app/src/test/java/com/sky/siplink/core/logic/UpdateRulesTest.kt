package com.sky.siplink.core.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateRulesTest {
    private val hour = 60 * 60 * 1000L

    /** The ABIs of the test phone (Samsung, Android 16), preferred one first. */
    private val phone = listOf("arm64-v8a", "armeabi-v7a", "armeabi")

    private fun apk(name: String) = ReleaseAsset(name, "https://github.com/tom231efa/siplink-android/releases/download/v0.2.0/$name")

    private val release = Release(
        tag = "v0.2.0",
        pageUrl = "https://github.com/tom231efa/siplink-android/releases/tag/v0.2.0",
        notes = "  - 新增自动检查更新\n",
        assets = listOf(apk("SipLink-0.2.0-armeabi-v7a.apk"), apk("SipLink-0.2.0-arm64-v8a.apk"), apk("checksums.txt")),
    )

    private val found = FoundRelease("0.2.0", "- 新增自动检查更新", release.pageUrl, release.assets[1])

    @Test
    fun aTagIsADottedVersionWithOrWithoutItsV() {
        assertEquals("0.1.2", UpdateRules.versionOf("v0.1.2"))
        assertEquals("0.1.2", UpdateRules.versionOf(" V0.1.2 "))
        assertEquals("0.1.2", UpdateRules.versionOf("0.1.2"))
        assertEquals(listOf(0, 1, 2), UpdateRules.numbers("v0.1.2"))
        assertEquals(listOf(1, 10), UpdateRules.numbers("1.10"))
        listOf("", "v", "0.1.", ".1", "0..1", "0.2.0-beta", "0.2.0 rc1", "x1.0", "1.0a", "-1.0", "9999999999.0").forEach {
            assertNull("\"$it\" was read as a version", UpdateRules.numbers(it))
        }
    }

    @Test
    fun onlyAStrictlyGreaterVersionIsNewer() {
        assertTrue(UpdateRules.isNewer("v0.1.3", "0.1.2"))
        assertTrue(UpdateRules.isNewer("v0.2.0", "0.1.2"))
        assertTrue(UpdateRules.isNewer("v1.0", "0.9.9"))
        // Compared as numbers, not as text.
        assertTrue(UpdateRules.isNewer("v0.1.10", "0.1.9"))
        assertTrue(UpdateRules.isNewer("v0.1.2.1", "0.1.2"))
        assertFalse(UpdateRules.isNewer("v0.1.2", "0.1.2"))
        assertFalse(UpdateRules.isNewer("v0.1.2.0", "0.1.2"))
        assertFalse(UpdateRules.isNewer("v0.2", "0.2.0"))
        assertFalse(UpdateRules.isNewer("v0.1.1", "0.1.2"))
        assertFalse(UpdateRules.isNewer("v0.1.9", "0.1.10"))
    }

    @Test
    fun anythingUnreadableIsNoUpdate() {
        assertFalse(UpdateRules.isNewer("v0.3.0-beta", "0.1.2"))
        assertFalse(UpdateRules.isNewer("latest", "0.1.2"))
        assertFalse(UpdateRules.isNewer("", "0.1.2"))
        assertFalse(UpdateRules.isNewer("v9.0.0", "0.1.2-debug"))
    }

    @Test
    fun theApkOfTheDevicesPreferredAbiIsDownloaded() {
        assertEquals(release.assets[1], UpdateRules.apkFor(release.assets, phone))
        // The first ABI of the device that has an APK.
        assertEquals(release.assets[0], UpdateRules.apkFor(release.assets, listOf("armeabi-v7a", "armeabi")))
        // Only APKs, upper case in the extension included.
        assertEquals(
            "SipLink-0.2.0-arm64-v8a.APK",
            UpdateRules.apkFor(listOf(apk("arm64-v8a-notes.txt"), apk("SipLink-0.2.0-arm64-v8a.APK")), phone)?.name,
        )
        // Only from the releases of the app's own repository.
        val elsewhere = ReleaseAsset("SipLink-0.2.0-arm64-v8a.apk", "https://github.com/someone/else/releases/download/v0.2.0/SipLink-0.2.0-arm64-v8a.apk")
        assertNull(UpdateRules.apkFor(listOf(elsewhere), phone))
        assertNull(UpdateRules.apkFor(listOf(ReleaseAsset("SipLink-0.2.0-arm64-v8a.apk", "http://example.com/a.apk")), phone))
    }

    @Test
    fun withoutAnApkForTheDeviceThereIsOnlyThePage() {
        assertNull(UpdateRules.found(release, listOf("x86_64", "x86")).apk)
        assertEquals(release.pageUrl, UpdateRules.found(release, listOf("x86_64", "x86")).pageUrl)
        // A page that is not HTTPS is not opened; the page of all releases stands in.
        assertEquals(UpdateRules.RELEASES_PAGE, UpdateRules.found(release.copy(pageUrl = "http://example.com/"), phone).pageUrl)
        assertEquals(UpdateRules.RELEASES_PAGE, UpdateRules.found(release.copy(pageUrl = ""), phone).pageUrl)
    }

    @Test
    fun aCheckKeepsTheVersionTheNotesThePageAndTheApk() {
        assertEquals(found, UpdateRules.found(release, phone))
    }

    @Test
    fun aDigestIsTakenOnlyAsSha256() {
        val hex = "2151b604e3429bff440b9fbc03eb3617bc2603cda96c95b9bb05277f9ddba255"
        assertEquals(hex, UpdateRules.sha256Of("sha256:$hex"))
        assertEquals(hex, UpdateRules.sha256Of("SHA256:${hex.uppercase()}"))
        listOf(null, "", "null", "sha1:$hex", "sha256:", "sha256:${hex.dropLast(1)}", "sha256:${hex.dropLast(1)}x").forEach {
            assertEquals("\"$it\" was taken", "", UpdateRules.sha256Of(it))
        }
    }

    @Test
    fun downloadsComeOnlyFromGitHubOverHttps() {
        assertTrue(UpdateRules.isTrustedDownload(release.assets[1].url))
        assertTrue(UpdateRules.isTrustedDownload("https://release-assets.githubusercontent.com/github-production-release-asset/1/2?sp=r"))
        assertTrue(UpdateRules.isTrustedDownload("https://objects.githubusercontent.com/github-production-release-asset-2e65be/1"))
        listOf(
            "http://github.com/tom231efa/siplink-android/releases/download/v0.2.0/a.apk",
            "https://example.com/a.apk",
            "https://github.com.example.com/a.apk",
            "https://githubusercontent.com.example.com/a.apk",
            "https://user@github.com/a.apk",
            "not a url",
        ).forEach { assertFalse("$it was trusted", UpdateRules.isTrustedDownload(it)) }
    }

    @Test
    fun theAppChecksOnEveryEntryButNotTwiceWithinAMinute() {
        val now = 1_800_000_000_000L
        val gap = UpdateRules.MIN_GAP_MS
        assertTrue("Never checked", UpdateRules.checksOnEntry(now, UpdateState()))
        assertFalse(UpdateRules.checksOnEntry(now + gap - 1, UpdateState(lastAttempt = now)))
        assertTrue(UpdateRules.checksOnEntry(now + gap, UpdateState(lastAttempt = now)))
        assertTrue("The clock was put back", UpdateRules.checksOnEntry(now - 1, UpdateState(lastAttempt = now)))
        // Not before the time GitHub named.
        assertFalse(UpdateRules.checksOnEntry(now + hour, UpdateState(lastAttempt = now, blockedUntil = now + hour + 1)))
        assertTrue(UpdateRules.checksOnEntry(now + hour, UpdateState(lastAttempt = now, blockedUntil = now + hour)))
    }

    @Test
    fun aRateLimitIsRespectedUntilTheTimeGitHubNames() {
        val now = 1_800_000_000_000L
        val reset = (now + hour) / 1000
        assertEquals(reset * 1000, UpdateRules.blockedUntil(403, "0", "$reset", null, now))
        assertEquals(reset * 1000, UpdateRules.blockedUntil(429, "0", "$reset", null, now))
        assertEquals(now + 30_000, UpdateRules.blockedUntil(429, null, null, "30", now))
        assertEquals(now + 30_000, UpdateRules.blockedUntil(403, "5", "$reset", "30", now))
        // A 429 that names no time waits a minute; a 403 that is no rate limit is a plain failure.
        assertEquals(now + UpdateRules.DEFAULT_BACKOFF_MS, UpdateRules.blockedUntil(429, null, null, null, now))
        assertNull(UpdateRules.blockedUntil(403, "12", "$reset", null, now))
        assertNull(UpdateRules.blockedUntil(403, null, null, null, now))
        assertNull(UpdateRules.blockedUntil(200, "0", "$reset", null, now))
        assertNull(UpdateRules.blockedUntil(304, "0", "$reset", null, now))
        // A reset in the past still waits a moment.
        assertEquals(now + 1000, UpdateRules.blockedUntil(403, "0", "1", null, now))
    }

    @Test
    fun aNewerVersionIsOfferedUntilItIsSkipped() {
        val state = UpdateState(latest = found, lastAttempt = 1, lastSuccess = 1)
        assertEquals(found, UpdateRules.offered(state, "0.1.2"))
        assertNull(UpdateRules.offered(state.copy(skipped = "0.2.0"), "0.1.2"))
        // A newer one than the one skipped is offered again.
        val newer = state.copy(latest = found.copy(version = "0.2.1"), skipped = "0.2.0")
        assertEquals("0.2.1", UpdateRules.offered(newer, "0.1.2")?.version)
        // Once it is installed there is nothing to offer.
        assertNull(UpdateRules.offered(state, "0.2.0"))
        assertNull(UpdateRules.offered(UpdateState(), "0.1.2"))
    }

    @Test
    fun theRowSaysWhatIsKnownInGeneralWords() {
        val installed = "0.1.2"
        assertEquals("点按检查更新", UpdateRules.status(UpdateState(), installed))
        assertEquals("正在检查…", UpdateRules.status(UpdateState(checking = true, latest = found), installed))
        val checked = UpdateState(latest = found, lastAttempt = 1, lastSuccess = 1)
        assertEquals("有新版本 0.2.0", UpdateRules.status(checked, installed))
        assertEquals("已跳过 0.2.0", UpdateRules.status(checked.copy(skipped = "0.2.0"), installed))
        assertEquals("已是最新版本", UpdateRules.status(checked, "0.2.0"))
        assertEquals("已是最新版本", UpdateRules.status(checked.copy(latest = found.copy(version = "0.3.0-beta")), installed))
        // A failure is named in two plain ways, and a newer version found before still counts more.
        assertEquals("更新检查失败", UpdateRules.status(checked.copy(failure = UpdateFailure.CHECK_FAILED), "0.2.0"))
        assertEquals("请求过于频繁", UpdateRules.status(UpdateState(failure = UpdateFailure.RATE_LIMITED), installed))
        assertEquals("有新版本 0.2.0", UpdateRules.status(checked.copy(failure = UpdateFailure.CHECK_FAILED), installed))
        // A download of the version offered says how far it is.
        assertEquals("正在下载 25%", UpdateRules.status(checked, installed, DownloadState.Running("0.2.0", 25, 100)))
        assertEquals("已下载 0.2.0", UpdateRules.status(checked, installed, DownloadState.Ready("0.2.0", "/x.apk")))
        assertEquals("有新版本 0.2.0", UpdateRules.status(checked, installed, DownloadState.Ready("0.1.9", "/x.apk")))
        assertEquals("512 KB", UpdateRules.percent(DownloadState.Running("0.2.0", 512 * 1024, 0)))
    }
}
