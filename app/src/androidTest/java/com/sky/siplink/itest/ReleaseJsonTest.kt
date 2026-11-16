package com.sky.siplink.itest

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sky.siplink.core.GitHubReleases
import com.sky.siplink.core.logic.FoundRelease
import com.sky.siplink.core.logic.ReleaseAsset
import com.sky.siplink.core.logic.UpdateRules
import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * GitHub's description of a release, as the app reads it. On the device because org.json is
 * part of Android: the JVM unit tests have only a stub of it. Needs no PBX and no network.
 */
@RunWith(AndroidJUnit4::class)
class ReleaseJsonTest {
    @Test
    fun theFieldsTheAppUsesAreRead() {
        val release = GitHubReleases.parse(RELEASE)
        assertEquals("v0.2.0", release.tag)
        assertEquals("$PAGE/tag/v0.2.0", release.pageUrl)
        assertEquals("- 新增自动检查更新\n- 修复若干问题", release.notes)
        assertEquals(
            listOf(
                ReleaseAsset("SipLink-0.2.0-arm64-v8a.apk", "$PAGE/download/v0.2.0/SipLink-0.2.0-arm64-v8a.apk"),
                ReleaseAsset("SipLink-0.2.0-armeabi-v7a.apk", "$PAGE/download/v0.2.0/SipLink-0.2.0-armeabi-v7a.apk"),
            ),
            release.assets,
        )
        assertEquals(
            FoundRelease("0.2.0", "- 新增自动检查更新\n- 修复若干问题", "$PAGE/download/v0.2.0/SipLink-0.2.0-armeabi-v7a.apk"),
            UpdateRules.found(release, listOf("armeabi-v7a", "armeabi")),
        )
    }

    @Test
    fun aReleaseWithoutNotesOrFilesStillReads() {
        val release = GitHubReleases.parse("""{"tag_name": "v0.2.0", "html_url": "$PAGE/tag/v0.2.0", "body": null}""")
        assertEquals("", release.notes)
        assertTrue(release.assets.isEmpty())
        // The page of the release is opened instead of a package.
        assertEquals("$PAGE/tag/v0.2.0", UpdateRules.found(release, listOf("arm64-v8a")).url)
    }

    @Test
    fun anAnswerThatDescribesNoReleaseIsRefused() {
        listOf("", "<html>rate limited</html>", """{"message": "Not Found"}""", "[]").forEach { answer ->
            val error = runCatching { GitHubReleases.parse(answer) }.exceptionOrNull()
            assertTrue("\"$answer\" was read as a release", error is JSONException)
        }
    }

    private companion object {
        const val PAGE = "https://github.com/tom231efa/siplink-android/releases"

        /** Shaped like GitHub's answer, with fields the app does not read left in. */
        val RELEASE = """
            {
              "url": "https://api.github.com/repos/tom231efa/siplink-android/releases/1",
              "html_url": "$PAGE/tag/v0.2.0",
              "id": 1,
              "tag_name": "v0.2.0",
              "name": "SipLink 0.2.0",
              "draft": false,
              "prerelease": false,
              "assets": [
                {"name": "SipLink-0.2.0-arm64-v8a.apk", "size": 1, "browser_download_url": "$PAGE/download/v0.2.0/SipLink-0.2.0-arm64-v8a.apk"},
                {"name": "SipLink-0.2.0-armeabi-v7a.apk", "size": 1, "browser_download_url": "$PAGE/download/v0.2.0/SipLink-0.2.0-armeabi-v7a.apk"}
              ],
              "body": "- 新增自动检查更新\n- 修复若干问题"
            }
        """.trimIndent()
    }
}
