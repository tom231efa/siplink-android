package com.sky.siplink.core.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateRulesTest {
    private val hour = 60 * 60 * 1000L
    private val day = 24 * hour

    /** The ABIs of the test phone (Samsung, Android 16), preferred one first. */
    private val phone = listOf("arm64-v8a", "armeabi-v7a", "armeabi")

    private fun apk(name: String) = ReleaseAsset(name, "https://github.com/tom231efa/siplink-android/releases/download/v0.2.0/$name")

    private val release = Release(
        tag = "v0.2.0",
        pageUrl = "https://github.com/tom231efa/siplink-android/releases/tag/v0.2.0",
        notes = "  - 新增自动检查更新\n",
        assets = listOf(apk("SipLink-0.2.0-armeabi-v7a.apk"), apk("SipLink-0.2.0-arm64-v8a.apk"), apk("checksums.txt")),
    )

    private val found = FoundRelease("0.2.0", "- 新增自动检查更新", release.assets[1].url)

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
        assertEquals(release.assets[1].url, UpdateRules.downloadUrl(release, phone))
        // Only APKs, upper case in the extension included.
        assertEquals(
            "SipLink-0.2.0-arm64-v8a.APK",
            UpdateRules.apkFor(listOf(apk("arm64-v8a-notes.txt"), apk("SipLink-0.2.0-arm64-v8a.APK")), phone)?.name,
        )
    }

    @Test
    fun withoutAnApkForTheDeviceTheReleasePageIsOpened() {
        assertNull(UpdateRules.apkFor(release.assets, listOf("x86_64", "x86")))
        assertEquals(release.pageUrl, UpdateRules.downloadUrl(release, listOf("x86_64", "x86")))
        assertEquals(release.pageUrl, UpdateRules.downloadUrl(release.copy(assets = emptyList()), phone))
        // An address that is not HTTPS is not opened; the page of all releases stands in.
        val plain = release.copy(pageUrl = "http://example.com/", assets = listOf(ReleaseAsset("SipLink-0.2.0-arm64-v8a.apk", "http://example.com/a.apk")))
        assertNull(UpdateRules.apkFor(plain.assets, phone))
        assertEquals(UpdateRules.RELEASES_PAGE, UpdateRules.downloadUrl(plain, phone))
        assertEquals(UpdateRules.RELEASES_PAGE, UpdateRules.downloadUrl(release.copy(pageUrl = "", assets = emptyList()), phone))
    }

    @Test
    fun aCheckKeepsTheVersionTheNotesAndWhatToOpen() {
        assertEquals(found, UpdateRules.found(release, phone))
    }

    @Test
    fun anAutomaticCheckIsDueOnceADayOrAnHourAfterAFailure() {
        val last = 1_800_000_000_000L
        assertTrue("Never checked", UpdateRules.isDue(last, 0, false))
        assertFalse(UpdateRules.isDue(last + day - 1, last, false))
        assertTrue(UpdateRules.isDue(last + day, last, false))
        assertFalse(UpdateRules.isDue(last + hour - 1, last, true))
        assertTrue(UpdateRules.isDue(last + hour, last, true))
        assertTrue("The clock was put back", UpdateRules.isDue(last - 1, last, false))
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
    fun theRowSaysWhatIsKnown() {
        val installed = "0.1.2"
        assertEquals("点按检查更新", UpdateRules.status(UpdateState(), installed))
        assertEquals("正在检查…", UpdateRules.status(UpdateState(checking = true, latest = found), installed))
        val checked = UpdateState(latest = found, lastAttempt = 1, lastSuccess = 1)
        assertEquals("有新版本 0.2.0", UpdateRules.status(checked, installed))
        assertEquals("已跳过 0.2.0", UpdateRules.status(checked.copy(skipped = "0.2.0"), installed))
        assertEquals("已是最新版本", UpdateRules.status(checked, "0.2.0"))
        assertEquals("已是最新版本", UpdateRules.status(checked.copy(latest = found.copy(version = "0.3.0-beta")), installed))
        // A failed manual check says so; one that failed by itself says nothing new.
        assertEquals("无法连接 GitHub", UpdateRules.status(checked.copy(failure = "无法连接 GitHub", lastFailed = true), installed))
        assertEquals("有新版本 0.2.0", UpdateRules.status(checked.copy(lastFailed = true, lastAttempt = 2), installed))
    }

    @Test
    fun aFailureIsNamedShortly() {
        assertEquals("无法连接 GitHub", UpdateRules.failureText(null))
        assertEquals("检查失败：无法读取 GitHub 的回复", UpdateRules.failureText(200))
        assertEquals("检查失败：尚无发布版本", UpdateRules.failureText(404))
        assertEquals("检查失败：GitHub 暂时拒绝了请求", UpdateRules.failureText(403))
        assertEquals("检查失败：GitHub 暂时拒绝了请求", UpdateRules.failureText(429))
        assertEquals("检查失败（HTTP 502）", UpdateRules.failureText(502))
    }
}
