package com.sky.siplink.core

import com.sky.siplink.BuildConfig
import com.sky.siplink.core.logic.Release
import com.sky.siplink.core.logic.ReleaseAsset
import com.sky.siplink.core.logic.UpdateRules
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * GitHub's answer to the question for the latest release, with what of its head the app uses.
 *
 * @param body the JSON of the release; only with status 200
 * @param etag what GitHub calls this answer; sent back, it answers 304 while nothing changed
 * @param remaining x-ratelimit-remaining
 * @param reset x-ratelimit-reset, in seconds since the epoch
 * @param retryAfter retry-after, in seconds
 */
class FeedAnswer(
    val status: Int,
    val body: String = "",
    val etag: String? = null,
    val remaining: String? = null,
    val reset: String? = null,
    val retryAfter: String? = null,
)

/**
 * Where the description of the latest release comes from. The app asks GitHub
 * ([GitHubReleases.feed]); the tests hand in answers of their own. Called off the main thread.
 */
fun interface ReleaseFeed {
    /**
     * @param etag the [FeedAnswer.etag] of the answer the app keeps; null: none
     * @throws IOException when there is no answer at all
     */
    fun fetch(etag: String?): FeedAnswer
}

/** A file being downloaded: its content and its length (-1: not stated). */
class AssetStream(val input: InputStream, val length: Long, private val onClose: () -> Unit = {}) : Closeable {
    override fun close() {
        try {
            input.close()
        } finally {
            onClose()
        }
    }
}

/** Where the files of a release are downloaded from: GitHub ([GitHubReleases.assets]), or the tests. Called off the main thread. */
fun interface AssetSource {
    /** @throws IOException when the file cannot be had from [url] */
    fun open(url: String): AssetStream
}

/** GitHub answered, but not with the file. */
class HttpStatusException(val status: Int) : IOException("HTTP $status")

/** The latest release of the app's public repository, from the GitHub API, and its files. */
object GitHubReleases {
    const val LATEST_URL = "https://api.github.com/repos/tom231efa/siplink-android/releases/latest"

    private const val TIMEOUT_MS = 10_000
    private const val DOWNLOAD_READ_TIMEOUT_MS = 30_000
    private const val MAX_REDIRECTS = 5

    /** A release description is a few kilobytes; more than this is no answer to read. */
    private const val MAX_ANSWER_BYTES = 1 shl 20

    val feed = ReleaseFeed(::fetch)

    val assets = AssetSource(::open)

    private fun fetch(etag: String?): FeedAnswer {
        val connection = URL(LATEST_URL).openConnection() as HttpsURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            // GitHub refuses requests without a User-Agent.
            connection.setRequestProperty("User-Agent", userAgent())
            if (!etag.isNullOrEmpty()) connection.setRequestProperty("If-None-Match", etag)
            val status = connection.responseCode
            val body = if (status == HttpsURLConnection.HTTP_OK) {
                val bytes = connection.inputStream.use { it.readAtMost(MAX_ANSWER_BYTES + 1) }
                if (bytes.size > MAX_ANSWER_BYTES) throw IOException("The answer is too long")
                bytes.toString(Charsets.UTF_8)
            } else {
                ""
            }
            return FeedAnswer(
                status = status,
                body = body,
                etag = connection.getHeaderField("ETag"),
                remaining = connection.getHeaderField("x-ratelimit-remaining"),
                reset = connection.getHeaderField("x-ratelimit-reset"),
                retryAfter = connection.getHeaderField("retry-after"),
            )
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Opens [url] for download. Redirections are followed by hand, and only to GitHub's own hosts
     * over HTTPS ([UpdateRules.isTrustedDownload]).
     */
    private fun open(url: String): AssetStream {
        var next = url
        repeat(MAX_REDIRECTS + 1) {
            if (!UpdateRules.isTrustedDownload(next)) throw IOException("Not a download of GitHub: $next")
            val connection = URL(next).openConnection() as HttpsURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = DOWNLOAD_READ_TIMEOUT_MS
            connection.setRequestProperty("User-Agent", userAgent())
            connection.setRequestProperty("Accept", "application/octet-stream")
            when (val status = connection.responseCode) {
                HttpURLConnection.HTTP_OK -> return AssetStream(connection.inputStream, connection.contentLengthLong) { connection.disconnect() }
                HttpURLConnection.HTTP_MOVED_PERM, HttpURLConnection.HTTP_MOVED_TEMP, HttpURLConnection.HTTP_SEE_OTHER,
                HTTP_TEMPORARY_REDIRECT, HTTP_PERMANENT_REDIRECT,
                -> {
                    val location = connection.getHeaderField("Location") ?: throw IOException("Redirection without a target")
                    next = URL(URL(next), location).toString()
                    connection.disconnect()
                }
                else -> {
                    connection.disconnect()
                    throw HttpStatusException(status)
                }
            }
        }
        throw IOException("Too many redirections")
    }

    private fun userAgent() = "SipLink/${BuildConfig.VERSION_NAME}"

    /**
     * The release that GitHub's answer for the latest release describes. Of the answer only
     * tag_name, html_url, body and the name, browser_download_url, size and digest of each asset
     * are read; a release without notes has a body of null, an asset uploaded before GitHub kept
     * digests a digest of null.
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
                assets?.optJSONObject(index)?.let {
                    ReleaseAsset(
                        name = it.text("name"),
                        url = it.text("browser_download_url"),
                        size = it.optLong("size", 0).coerceAtLeast(0),
                        sha256 = UpdateRules.sha256Of(it.text("digest")),
                    )
                }
            },
        )
    }

    /** The text under [key]; empty when it is missing or null (optString would give "null"). */
    private fun JSONObject.text(key: String): String = if (isNull(key)) "" else optString(key)

    private const val HTTP_TEMPORARY_REDIRECT = 307
    private const val HTTP_PERMANENT_REDIRECT = 308
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
