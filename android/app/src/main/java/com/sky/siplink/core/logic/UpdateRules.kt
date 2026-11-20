package com.sky.siplink.core.logic

import androidx.annotation.StringRes
import com.sky.siplink.R
import com.sky.siplink.core.model.Phrase
import java.net.URI

/**
 * A file attached to a release.
 *
 * @param size its size in bytes as GitHub states it; 0: not stated
 * @param sha256 the SHA-256 of its content as GitHub computed it at upload, in lower-case hex; empty: none given
 */
data class ReleaseAsset(val name: String, val url: String, val size: Long = 0, val sha256: String = "")

/** The latest release as GitHub describes it. [tag] is the name of its tag, such as "v0.1.2". */
data class Release(val tag: String, val pageUrl: String, val notes: String, val assets: List<ReleaseAsset>)

/**
 * What a check found and keeps: the version without its "v", the release notes, the page of the
 * release, and the APK for this device; null when the release has none for it, the page is then
 * what "download" opens.
 */
data class FoundRelease(val version: String, val notes: String, val pageUrl: String, val apk: ReleaseAsset?)

/** Why the last check found nothing, in the general words the version row shows. */
enum class UpdateFailure(@StringRes val text: Int) {
    CHECK_FAILED(R.string.update_check_failed),

    /** GitHub's limit for requests without an account was reached. */
    RATE_LIMITED(R.string.update_rate_limited),
}

/**
 * What the app knows about updates. [checking] lasts as long as the process; the rest is kept
 * across restarts (core/UpdateChecker.kt).
 *
 * @param latest the release the last successful check found, newer or not; null before one
 * @param skipped the version the user chose to skip; empty: none
 * @param lastAttempt when the last check started, successful or not, in ms since the epoch; 0: never
 * @param lastSuccess when the last successful check started; 0: none so far
 * @param failure why the last check failed; null when it did not
 * @param etag what GitHub called its answer about [latest]: sent along, an unchanged answer is not sent again
 * @param blockedUntil after a rate-limit answer: GitHub is not asked again before this time, in ms since the epoch
 */
data class UpdateState(
    val checking: Boolean = false,
    val latest: FoundRelease? = null,
    val skipped: String = "",
    val lastAttempt: Long = 0,
    val lastSuccess: Long = 0,
    val failure: UpdateFailure? = null,
    val etag: String = "",
    val blockedUntil: Long = 0,
)

/** Where the download of the APK of a version stands. */
sealed interface DownloadState {
    data object Idle : DownloadState

    /** @param total the size of the file; 0: not known */
    data class Running(val version: String, val done: Long, val total: Long) : DownloadState

    /** Downloaded and checked: [path] is ready for the system's installer. */
    data class Ready(val version: String, val path: String) : DownloadState

    data class Failed(val version: String) : DownloadState
}

/** Decisions about updates that need neither the network nor Android; core/UpdateChecker.kt applies them. */
object UpdateRules {
    /**
     * The app checks each time it comes to the front, as the user asked; only not twice within
     * this time, so that coming back from a call screen or a system dialog is not another
     * request. GitHub allows 60 requests an hour from one address without an account; what it
     * refuses beyond that is waited out ([blockedUntil]).
     */
    const val MIN_GAP_MS = 60 * 1000L

    /** How long to wait after a rate-limit answer that names no time: GitHub asks for at least a minute. */
    const val DEFAULT_BACKOFF_MS = 60 * 1000L

    /** The app's repository: what the GitHub mark at the foot of the settings opens. */
    const val REPOSITORY_PAGE = "https://github.com/tom231efa/siplink-android"

    /** The page of all releases: what "download" opens when a release brings no usable address of its own. */
    const val RELEASES_PAGE = "$REPOSITORY_PAGE/releases"

    /** Where the files of the app's releases are downloaded from; an APK anywhere else is not taken. */
    const val ASSET_PREFIX = "$RELEASES_PAGE/download/"

    /** [tag] without a leading "v": "v0.1.2" is version "0.1.2". */
    fun versionOf(tag: String): String = tag.trim().removePrefix("v").removePrefix("V")

    /** The numbers of a dotted version such as "0.1.2" or "v0.1.2"; null for anything else ("0.2.0-beta" included). */
    fun numbers(version: String): List<Int>? {
        val parts = versionOf(version).split('.')
        if (parts.any { part -> part.isEmpty() || part.length > MAX_PART_DIGITS || !part.all { it in '0'..'9' } }) return null
        return parts.map { it.toInt() }
    }

    /**
     * Whether [tag] names a version strictly newer than [installed]. Missing numbers count as 0,
     * so "0.2" and "0.2.0" are the same version. Either of them unreadable: no.
     */
    fun isNewer(tag: String, installed: String): Boolean {
        val found = numbers(tag) ?: return false
        val own = numbers(installed) ?: return false
        for (index in 0 until maxOf(found.size, own.size)) {
            val a = found.getOrElse(index) { 0 }
            val b = own.getOrElse(index) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    /**
     * The APK of [assets] built for this device: the first of [abis] (the device's ABIs, the
     * preferred one first) that the name of an APK contains. Only files of the app's own
     * releases count ([ASSET_PREFIX]). Null when there is none.
     */
    fun apkFor(assets: List<ReleaseAsset>, abis: List<String>): ReleaseAsset? {
        val apks = assets.filter { it.name.endsWith(".apk", ignoreCase = true) && it.url.startsWith(ASSET_PREFIX) }
        for (abi in abis) apks.firstOrNull { abi in it.name }?.let { return it }
        return null
    }

    /** What a check that returned [release] keeps of it. */
    fun found(release: Release, abis: List<String>): FoundRelease = FoundRelease(
        version = versionOf(release.tag),
        notes = release.notes.trim(),
        pageUrl = release.pageUrl.takeIf(::isHttps) ?: RELEASES_PAGE,
        apk = apkFor(release.assets, abis),
    )

    /** The SHA-256 of a "digest" of GitHub's description of an asset ("sha256:<hex>"), in lower case; empty for anything else. */
    fun sha256Of(digest: String?): String {
        val hex = digest?.trim()?.takeIf { it.startsWith("sha256:", ignoreCase = true) }?.substring(7)?.lowercase() ?: return ""
        return hex.takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }.orEmpty()
    }

    /**
     * Whether a download may come from [url]: HTTPS, from GitHub itself or the host it keeps the
     * files of releases on (*.githubusercontent.com). A redirection anywhere else is not followed.
     */
    fun isTrustedDownload(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val host = uri.host?.lowercase() ?: return false
        return uri.scheme.equals("https", ignoreCase = true) && uri.userInfo == null &&
            (host == "github.com" || host.endsWith(".githubusercontent.com"))
    }

    /** Whether the app checks by itself at [now], coming to the front: not while GitHub asked to wait, nor again within [MIN_GAP_MS]. */
    fun checksOnEntry(now: Long, state: UpdateState): Boolean {
        if (now < state.blockedUntil) return false
        val last = state.lastAttempt
        return last <= 0 || now < last || now - last >= MIN_GAP_MS
    }

    /**
     * Until when GitHub is not to be asked again after it answered with [status]: for its rate
     * limits, a 403 or 429 with no requests left (until [reset], in seconds since the epoch) or
     * with a [retryAfter] in seconds; a 429 that says neither waits [DEFAULT_BACKOFF_MS]. Null
     * when the answer is no rate limit.
     */
    fun blockedUntil(status: Int, remaining: String?, reset: String?, retryAfter: String?, now: Long): Long? {
        if (status != HTTP_FORBIDDEN && status != HTTP_TOO_MANY_REQUESTS) return null
        retryAfter?.trim()?.toLongOrNull()?.takeIf { it >= 0 }?.let { return now + it * 1000 }
        if (remaining?.trim() == "0") reset?.trim()?.toLongOrNull()?.let { return maxOf(it * 1000, now + 1000) }
        return if (status == HTTP_TOO_MANY_REQUESTS) now + DEFAULT_BACKOFF_MS else null
    }

    /** The newer version that the user is offered: known, newer than [installed], and not skipped. Null when there is none. */
    fun offered(state: UpdateState, installed: String): FoundRelease? =
        state.latest?.takeIf { isNewer(it.version, installed) && it.version != state.skipped }

    /** What the row of the version says under it. */
    fun status(state: UpdateState, installed: String, download: DownloadState = DownloadState.Idle): Phrase {
        val latest = state.latest?.takeIf { isNewer(it.version, installed) }
        val offered = offered(state, installed)
        return when {
            download is DownloadState.Running && download.version == offered?.version -> Phrase.of(R.string.update_downloading, percent(download))
            download is DownloadState.Ready && download.version == offered?.version -> Phrase.of(R.string.update_downloaded, download.version)
            state.checking -> Phrase.of(R.string.update_checking)
            offered != null -> Phrase.of(R.string.update_available, offered.version)
            state.failure != null -> Phrase.of(state.failure.text)
            latest != null -> Phrase.of(R.string.update_skipped, latest.version)
            state.lastSuccess > 0 -> Phrase.of(R.string.update_latest)
            else -> Phrase.of(R.string.update_tap_to_check)
        }
    }

    /** How far a download is: "42%", or the megabytes so far when the size is not known. */
    fun percent(download: DownloadState.Running): String =
        if (download.total > 0) "${(download.done * 100 / download.total).coerceIn(0, 100)}%" else RecordingRules.sizeText(download.done)

    private fun isHttps(url: String): Boolean = url.startsWith("https://")

    /** More digits than an Int holds are no version number. */
    private const val MAX_PART_DIGITS = 9

    const val HTTP_OK = 200
    const val HTTP_NOT_MODIFIED = 304
    private const val HTTP_FORBIDDEN = 403
    private const val HTTP_TOO_MANY_REQUESTS = 429
}
