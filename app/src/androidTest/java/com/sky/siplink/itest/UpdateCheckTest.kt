package com.sky.siplink.itest

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sky.siplink.BuildConfig
import com.sky.siplink.core.AssetSource
import com.sky.siplink.core.AssetStream
import com.sky.siplink.core.FeedAnswer
import com.sky.siplink.core.ReleaseFeed
import com.sky.siplink.core.logic.DownloadState
import com.sky.siplink.core.logic.FoundRelease
import com.sky.siplink.core.logic.ReleaseAsset
import com.sky.siplink.core.logic.UpdateFailure
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The check for a newer version and its download, as the user gets them. GitHub is not asked:
 * the app is handed canned answers and files, so no network is needed for this.
 *
 * Pictures of the screens are saved in the cache directory of the app (itest-update/).
 */
@RunWith(AndroidJUnit4::class)
class UpdateCheckTest : ScreenTestBase("itest-update") {
    private val updates get() = engine.updates

    /** The ETags the app sent along, one per request (null: none). */
    private val sent = CopyOnWriteArrayList<String?>()

    @After
    fun forget() {
        onMain {
            updates.feed = NO_RELEASES
            updates.forget()
        }
    }

    /** GitHub's answer for a latest release with the tag [tag], its APK as [apk] states it. */
    private fun answer(tag: String, apk: ReleaseAsset? = null, etag: String = "\"etag-$tag\"") = ReleaseFeed { sentEtag ->
        sent += sentEtag
        val asset = apk ?: ReleaseAsset("SipLink-${tag.removePrefix("v")}-arm64-v8a.apk", "$PAGE/download/$tag/app.apk")
        FeedAnswer(
            status = 200,
            etag = etag,
            body = """
                {
                  "tag_name": "$tag",
                  "html_url": "$PAGE/tag/$tag",
                  "body": "$NOTES",
                  "assets": [{
                    "name": "${asset.name}",
                    "browser_download_url": "${asset.url}",
                    "size": ${asset.size},
                    "digest": ${if (asset.sha256.isEmpty()) "null" else "\"sha256:${asset.sha256}\""}
                  }]
                }
            """.trimIndent(),
        )
    }

    /** Starts as a new installation would, with [autoCheck] for the switch, and GitHub answering with [feed]. */
    private fun start(feed: ReleaseFeed, autoCheck: Boolean = true) {
        onMain {
            // The question about the lock screen would stand in front of the screens.
            assertTrue(engine.updateSettings { it.copy(lockScreenPromptDeclined = true, autoUpdateCheck = autoCheck) })
            updates.forget()
            updates.feed = feed
        }
        sent.clear()
        showApp()
    }

    @Test
    fun aNewerReleaseIsOfferedUntilItIsSkipped() {
        // Shown for the first time, the app asks by itself.
        start(answer("v$NEWER"))
        await("the automatic check") { updates.status.value.latest?.version == NEWER }
        see(DOT)
        tap("设置")
        // The settings page only says that there is one; the About page says which.
        see("有新版本")
        absent("有新版本 $NEWER")
        tap("关于")
        see("有新版本 $NEWER")
        picture("offered")

        // The version, its notes, and the two things to do about it.
        tap("有新版本 $NEWER")
        see("新版本 $NEWER")
        see(NOTES)
        see("下载并安装")
        picture("release")
        tap("跳过此版本")
        await("the version to be skipped") { updates.status.value.skipped == NEWER }
        see("已跳过 $NEWER")
        absent("有新版本 $NEWER")
        picture("skipped")

        // Asked by hand, a check that fails says so in general words, and nothing else shows...
        onMain { updates.feed = NO_RELEASES }
        tap("已跳过 $NEWER")
        see("更新检查失败")
        // ...and one that works offers the skipped version again: the user asked for it.
        onMain { updates.feed = answer("v$NEWER") }
        tap("更新检查失败")
        see("有新版本 $NEWER")
        back()
        see(DOT)
        tap("关于")

        // A release that is not newer than the app offers nothing.
        onMain {
            updates.feed = answer("v${BuildConfig.VERSION_NAME}")
            updates.checkNow()
        }
        see("已是最新版本")
        back()
        absent(DOT)
        see(BuildConfig.VERSION_NAME)
    }

    @Test
    fun anUnchangedReleaseIsAskedForWithItsEtag() {
        start(answer("v$NEWER", etag = ETAG))
        await("the automatic check") { updates.status.value.latest?.version == NEWER }
        assertEquals(listOf<String?>(null), sent.toList())
        // GitHub answers that nothing changed: what was found stays, and the check counts as done.
        onMain {
            updates.feed = ReleaseFeed { etag ->
                sent += etag
                FeedAnswer(304)
            }
            updates.checkNow()
        }
        await("the second check") { sent.size == 2 && !updates.status.value.checking }
        assertEquals(ETAG, sent[1])
        assertEquals(NEWER, updates.status.value.latest?.version)
        assertEquals(null, updates.status.value.failure)
    }

    @Test
    fun aRateLimitIsShownOnlyInTheSettingsAndWaitedOut() {
        val reset = System.currentTimeMillis() / 1000 + 3600
        val limited = ReleaseFeed { etag ->
            sent += etag
            FeedAnswer(403, remaining = "0", reset = "$reset")
        }
        start(limited)
        await("the automatic check") { sent.size == 1 && !updates.status.value.checking }
        // Nothing shows itself: no dot, no notice; the version row says it in general words.
        absent(DOT)
        assertEquals(UpdateFailure.RATE_LIMITED, updates.status.value.failure)
        assertEquals(reset * 1000, updates.status.value.blockedUntil)
        tap("设置")
        // Not on the settings page itself: only where the user looks for it.
        absent("请求过于频繁")
        tap("关于")
        see("请求过于频繁")
        picture("rate-limited")
        // Until the time GitHub named, neither the app nor the user asks again.
        onMain {
            updates.checkOnEntry()
            updates.checkNow()
        }
        never("another request", QUIET_WINDOW_MS) { sent.size > 1 }
        see("请求过于频繁")
    }

    @Test
    fun switchedOffTheAppDoesNotAskByItself() {
        start(answer("v$NEWER"), autoCheck = false)
        tap("设置")
        tap("关于")
        see("点按检查更新")
        assertEquals("The app asked by itself", 0L, updates.status.value.lastAttempt)

        // Switched on, it asks the next time it comes to the front, and not again right after.
        tap("自动检查更新")
        await("the switch to be on") { engine.settings.current.autoUpdateCheck }
        onMain { updates.checkOnEntry() }
        see("有新版本 $NEWER")
        val asked = updates.status.value.lastAttempt
        onMain {
            updates.feed = answer("v$EVEN_NEWER")
            updates.checkOnEntry()
        }
        see("有新版本 $NEWER")
        assertEquals("The app asked again within minutes", asked, updates.status.value.lastAttempt)
    }

    /** Offers [NEWER] with an APK as [apk] says, its content coming from [content]. */
    private fun offer(apk: ReleaseAsset, content: () -> ByteArray): FoundRelease {
        onMain {
            updates.forget()
            updates.feed = answer("v$NEWER", apk)
            updates.assets = AssetSource { url ->
                if (url != apk.url) throw IOException("Unexpected $url")
                val bytes = content()
                AssetStream(ByteArrayInputStream(bytes), bytes.size.toLong())
            }
            updates.checkNow()
        }
        await("the release to be found") { updates.status.value.latest?.version == NEWER }
        return updates.status.value.latest!!
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun aDownloadIsCheckedBeforeItCanBeInstalled() {
        // The app's own installed package stands in for the APK of the release.
        val apk = File(InstrumentationRegistry.getInstrumentation().targetContext.packageCodePath).readBytes()
        // Named for the device's ABI, so that the app takes it as its own.
        val name = "SipLink-$NEWER-${Build.SUPPORTED_ABIS[0]}.apk"
        val url = "$PAGE/download/v$NEWER/$name"

        // Shorter than GitHub states: refused.
        var release = offer(ReleaseAsset(name, url, apk.size.toLong(), sha256(apk))) { apk.copyOf(apk.size - 1) }
        onMain { updates.startDownload(release) }
        await("the short download to be refused") { updates.download.value is DownloadState.Failed }

        // Of the right size, but not what GitHub's digest says: refused.
        val other = apk.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
        release = offer(ReleaseAsset(name, url, apk.size.toLong(), sha256(apk))) { other }
        onMain { updates.startDownload(release) }
        await("the altered download to be refused") { updates.download.value is DownloadState.Failed }

        // As stated: ready for the installer, which only the user's tap starts.
        release = offer(ReleaseAsset(name, url, apk.size.toLong(), sha256(apk))) { apk }
        onMain { updates.startDownload(release) }
        await("the download to be ready") { updates.download.value is DownloadState.Ready }
        val ready = updates.download.value as DownloadState.Ready
        assertTrue(File(ready.path).isFile)
        assertEquals(apk.size.toLong(), File(ready.path).length())

        // The sheet offers to install it.
        start(answer("v$NEWER", ReleaseAsset(name, url, apk.size.toLong(), sha256(apk))))
        onMain { updates.startDownload(updates.status.value.latest ?: return@onMain) }
        tap("设置")
        tap("关于")
        see("已下载 $NEWER")
        tap("已下载 $NEWER")
        see("安装")
        picture("downloaded")
    }

    private companion object {
        const val PAGE = "https://github.com/tom231efa/siplink-android/releases"

        /** Versions no release of the app will reach. */
        const val NEWER = "99.0.0"
        const val EVEN_NEWER = "99.1.0"
        const val NOTES = "- 测试用的发布说明"
        const val ETAG = "\"etag-of-the-test\""

        /**
         * What the settings tab says to a screen reader while it carries the dot: its name and the
         * news. Its text stays "设置", which the tab is tapped by.
         */
        const val DOT = "设置，有新版本可用"
    }
}
