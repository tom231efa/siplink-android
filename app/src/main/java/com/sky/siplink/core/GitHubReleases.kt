package com.sky.siplink.core

import com.sky.siplink.BuildConfig
import com.sky.siplink.core.logic.Release
import com.sky.siplink.core.logic.ReleaseAsset
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Where the description of the latest release comes from: the JSON of GitHub's answer. The app
 * asks GitHub ([GitHubReleases.feed]); the tests hand in an answer of their own. Called off the
 * main thread.
 */
fun interface ReleaseFeed {
    /** @throws IOException when there is no answer, or one with another status than 200 ([HttpStatusException]) */
    fun fetch(): String
}

/** GitHub answered, but not with the release. */
class HttpStatusException(val status: Int) : IOException("HTTP $status")

/** The latest release of the app's public repository, from the GitHub API. */
object GitHubReleases {
    const val LATEST_URL = "https://api.github.com/repos/tom231efa/siplink-android/releases/latest"

    private const val TIMEOUT_MS = 10_000

    /** A release description is a few kilobytes; more than this is no answer to read. */
    private const val MAX_ANSWER_BYTES = 1 shl 20

    val feed = ReleaseFeed { fetch(LATEST_URL) }

    private fun fetch(url: String): String {
        val connection = URL(url).openConnection() as HttpsURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            // GitHub refuses requests without a User-Agent.
            connection.setRequestProperty("User-Agent", "SipLink/${BuildConfig.VERSION_NAME}")
            val status = connection.responseCode
            if (status != HttpsURLConnection.HTTP_OK) throw HttpStatusException(status)
            val bytes = connection.inputStream.use { it.readAtMost(MAX_ANSWER_BYTES + 1) }
            if (bytes.size > MAX_ANSWER_BYTES) throw IOException("The answer is too long")
            return bytes.toString(Charsets.UTF_8)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * The release that GitHub's answer for the latest release describes. Of the answer only
     * tag_name, html_url, body and the name and browser_download_url of each asset are read;
     * a release without notes has a body of null.
     *
     * @throws org.json.JSONException when the answer is not JSON or has no tag_name
     */
    fun parse(json: String): Release {
        val root = JSONObject(json)
        val assets = root.optJSONArray("assets")
        return Release(
            tag = root.getString("tag_name"),
            pageUrl = root.text("html_url"),
            notes = root.text("body"),
            assets = (0 until (assets?.length() ?: 0)).mapNotNull { index ->
                assets?.optJSONObject(index)?.let { ReleaseAsset(it.text("name"), it.text("browser_download_url")) }
            },
        )
    }

    /** The text under [key]; empty when it is missing or null (optString would give "null"). */
    private fun JSONObject.text(key: String): String = if (isNull(key)) "" else optString(key)
}

/** Reads at most [limit] bytes; InputStream.readNBytes needs Android 13. */
internal fun InputStream.readAtMost(limit: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (out.size() < limit) {
        val read = read(buffer, 0, minOf(buffer.size, limit - out.size()))
        if (read < 0) break
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}
