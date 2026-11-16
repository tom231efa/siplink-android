package com.sky.siplink.core.logic

/** A file attached to a release: its name and where the browser downloads it from. */
data class ReleaseAsset(val name: String, val url: String)

/** The latest release as GitHub describes it. [tag] is the name of its tag, such as "v0.1.2". */
data class Release(val tag: String, val pageUrl: String, val notes: String, val assets: List<ReleaseAsset>)

/** What a check found and keeps: the version without its "v", the release notes, and what "download" opens. */
data class FoundRelease(val version: String, val notes: String, val url: String)

/**
 * What the app knows about updates. [checking], and [failure] of a manual check, last as long as
 * the process; the rest is kept across restarts (core/UpdateChecker.kt).
 *
 * @param latest the release the last successful check found, newer or not; null before one
 * @param skipped the version the user chose to skip; empty: none
 * @param lastAttempt when the last check started, successful or not, in ms since the epoch; 0: never
 * @param lastFailed whether that check failed
 * @param lastSuccess when the last successful check started; 0: none so far
 * @param failure what the row says about a manual check that failed, until the next check
 */
data class UpdateState(
    val checking: Boolean = false,
    val latest: FoundRelease? = null,
    val skipped: String = "",
    val lastAttempt: Long = 0,
    val lastFailed: Boolean = false,
    val lastSuccess: Long = 0,
    val failure: String? = null,
)

/** Decisions about updates that need neither the network nor Android; core/UpdateChecker.kt applies them. */
object UpdateRules {
    /** How long a successful check is good for, and how long a failed one waits before the next automatic try. */
    const val CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L
    const val RETRY_INTERVAL_MS = 60 * 60 * 1000L

    /** The page of all releases: what "download" opens when a release brings no usable address of its own. */
    const val RELEASES_PAGE = "https://github.com/tom231efa/siplink-android/releases"

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
     * preferred one first) that the name of an APK contains. Null when there is none.
     */
    fun apkFor(assets: List<ReleaseAsset>, abis: List<String>): ReleaseAsset? {
        val apks = assets.filter { it.name.endsWith(".apk", ignoreCase = true) && isHttps(it.url) }
        for (abi in abis) apks.firstOrNull { abi in it.name }?.let { return it }
        return null
    }

    /** What "download" opens for [release]: its APK for this device, else its page, else the page of all releases. */
    fun downloadUrl(release: Release, abis: List<String>): String =
        apkFor(release.assets, abis)?.url ?: release.pageUrl.takeIf(::isHttps) ?: RELEASES_PAGE

    /** What a check that returned [release] keeps of it. */
    fun found(release: Release, abis: List<String>): FoundRelease =
        FoundRelease(versionOf(release.tag), release.notes.trim(), downloadUrl(release, abis))

    /**
     * Whether an automatic check is due at [now]: never checked, a day after a successful
     * check, an hour after a failed one. A clock that was put back makes it due as well.
     */
    fun isDue(now: Long, lastAttempt: Long, lastFailed: Boolean): Boolean {
        if (lastAttempt <= 0 || now < lastAttempt) return true
        return now - lastAttempt >= if (lastFailed) RETRY_INTERVAL_MS else CHECK_INTERVAL_MS
    }

    /** The newer version that the user is offered: known, newer than [installed], and not skipped. Null when there is none. */
    fun offered(state: UpdateState, installed: String): FoundRelease? =
        state.latest?.takeIf { isNewer(it.version, installed) && it.version != state.skipped }

    /** What the row of the version says under it. */
    fun status(state: UpdateState, installed: String): String {
        val latest = state.latest?.takeIf { isNewer(it.version, installed) }
        return when {
            state.checking -> "正在检查…"
            state.failure != null -> state.failure
            latest != null && latest.version == state.skipped -> "已跳过 ${latest.version}"
            latest != null -> "有新版本 ${latest.version}"
            state.lastSuccess > 0 -> "已是最新版本"
            else -> "点按检查更新"
        }
    }

    /**
     * What the row says after a manual check failed.
     *
     * @param httpStatus the status GitHub answered with; null: no answer at all; 200: an answer that could not be read
     */
    fun failureText(httpStatus: Int?): String = when (httpStatus) {
        null -> "无法连接 GitHub"
        HTTP_OK -> "检查失败：无法读取 GitHub 的回复"
        HTTP_NOT_FOUND -> "检查失败：尚无发布版本"
        HTTP_FORBIDDEN, HTTP_TOO_MANY_REQUESTS -> "检查失败：GitHub 暂时拒绝了请求"
        else -> "检查失败（HTTP $httpStatus）"
    }

    private fun isHttps(url: String): Boolean = url.startsWith("https://")

    /** More digits than an Int holds are no version number. */
    private const val MAX_PART_DIGITS = 9

    const val HTTP_OK = 200
    private const val HTTP_FORBIDDEN = 403
    private const val HTTP_NOT_FOUND = 404
    private const val HTTP_TOO_MANY_REQUESTS = 429
}
