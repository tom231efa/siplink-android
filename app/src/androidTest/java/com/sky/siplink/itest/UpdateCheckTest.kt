package com.sky.siplink.itest

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sky.siplink.BuildConfig
import com.sky.siplink.core.ReleaseFeed
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The check for a newer version, as the user gets it. GitHub is not asked: the app is handed
 * canned answers, so no network is needed for this.
 *
 * Pictures of the screens are saved in the cache directory of the app (itest-update/).
 */
@RunWith(AndroidJUnit4::class)
class UpdateCheckTest : ScreenTestBase("itest-update") {
    private val updates get() = engine.updates

    @After
    fun forget() {
        onMain {
            updates.feed = NO_RELEASES
            updates.forget()
        }
    }

    /** GitHub's answer for a latest release with the tag [tag]. */
    private fun answer(tag: String) = ReleaseFeed {
        """
            {
              "tag_name": "$tag",
              "html_url": "$PAGE/tag/$tag",
              "body": "$NOTES",
              "assets": [{"name": "SipLink-${tag.removePrefix("v")}-arm64-v8a.apk", "browser_download_url": "$PAGE/download/$tag/app.apk"}]
            }
        """.trimIndent()
    }

    /** Starts as a new installation would, with [autoCheck] for the switch, and GitHub answering with [feed]. */
    private fun start(feed: ReleaseFeed, autoCheck: Boolean = true) {
        onMain {
            // The question about the lock screen would stand in front of the screens.
            assertTrue(engine.updateSettings { it.copy(lockScreenPromptDeclined = true, autoUpdateCheck = autoCheck) })
            updates.forget()
            updates.feed = feed
        }
        showApp()
    }

    @Test
    fun aNewerReleaseIsOfferedUntilItIsSkipped() {
        // Shown for the first time, the app asks by itself.
        start(answer("v$NEWER"))
        await("the automatic check") { updates.status.value.latest?.version == NEWER }
        see(DOT)
        tap("设置")
        see("有新版本 $NEWER")
        picture("offered")

        // The version, its notes, and the two things to do about it.
        tap("有新版本 $NEWER")
        see("新版本 $NEWER")
        see(NOTES)
        see("下载安装包")
        picture("release")
        tap("跳过此版本")
        await("the version to be skipped") { updates.status.value.skipped == NEWER }
        see("已跳过 $NEWER")
        absent("有新版本 $NEWER", DOT)
        picture("skipped")

        // Asked by hand, a check that fails says so...
        onMain { updates.feed = NO_RELEASES }
        tap("已跳过 $NEWER")
        see("无法连接 GitHub")
        // ...and one that works offers the skipped version again: the user asked for it.
        onMain { updates.feed = answer("v$NEWER") }
        tap("无法连接 GitHub")
        see("有新版本 $NEWER")
        see(DOT)

        // A release that is not newer than the app offers nothing.
        onMain {
            updates.feed = answer("v${BuildConfig.VERSION_NAME}")
            updates.checkNow()
        }
        see("已是最新版本")
        absent(DOT)
    }

    @Test
    fun switchedOffTheAppDoesNotAskByItself() {
        start(answer("v$NEWER"), autoCheck = false)
        tap("设置")
        see("点按检查更新")
        assertEquals("The app asked by itself", 0L, updates.status.value.lastAttempt)

        // Switched on, it asks the next time it comes to the front, and then not again that day.
        tap("自动检查更新")
        await("the switch to be on") { engine.settings.current.autoUpdateCheck }
        onMain { updates.checkIfDue() }
        see("有新版本 $NEWER")
        val asked = updates.status.value.lastAttempt
        onMain {
            updates.feed = answer("v$EVEN_NEWER")
            updates.checkIfDue()
        }
        see("有新版本 $NEWER")
        assertEquals("The app asked again within the day", asked, updates.status.value.lastAttempt)
    }

    private companion object {
        const val PAGE = "https://github.com/tom231efa/siplink-android/releases"

        /** Versions no release of the app will reach. */
        const val NEWER = "99.0.0"
        const val EVEN_NEWER = "99.1.0"
        const val NOTES = "- 测试用的发布说明"

        /**
         * What the settings tab says to a screen reader while it carries the dot: its name and the
         * news. Its text stays "设置", which the tab is tapped by.
         */
        const val DOT = "设置，有新版本可用"
    }
}
