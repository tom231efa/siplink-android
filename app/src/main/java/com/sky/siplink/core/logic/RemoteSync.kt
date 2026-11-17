package com.sky.siplink.core.logic

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

/**
 * The rules of talking to the management platform: when to sync, what its answers mean, what the
 * user is told, and what the phone reports about itself (design §3.3, §3.4).
 */
object RemoteSync {
    private const val MINUTE_MS = 60_000L

    /** Coming to the front syncs, but not again within this time. */
    const val FRONT_MIN_GAP_MS = MINUTE_MS

    const val NO_CONNECTION = "无法连接平台"
    const val PIN_MISMATCH = "平台证书与二维码不符"
    const val UNTRUSTED = "平台证书不受信任"
    const val UNAUTHORIZED = "远程配置认证失败"

    /** What an answer of the platform means for the phone. */
    enum class Outcome {
        /** A document came (200). */
        DOCUMENT,

        /** Nothing changed (204). */
        UNCHANGED,

        /** The phone is no longer this client (410): what was handed out goes. */
        REVOKED,

        /** The enrollment address is unknown, used or expired (404 when binding). */
        INVALID_TOKEN,

        /** Anything else: the configuration stays as it is, and the phone tries again later. */
        FAILED,
    }

    fun outcome(status: Int, enrolling: Boolean): Outcome = when {
        status == 200 -> Outcome.DOCUMENT
        status == 204 && !enrolling -> Outcome.UNCHANGED
        status == 410 && !enrolling -> Outcome.REVOKED
        status == 404 && enrolling -> Outcome.INVALID_TOKEN
        else -> Outcome.FAILED
    }

    /** What the user is told about an answer with [status] that brought no configuration. */
    fun failureText(status: Int): String = when {
        status == 401 -> UNAUTHORIZED
        status == 404 -> "二维码无效、已使用或已过期"
        status == 429 -> "请求过于频繁，请稍后重试"
        status >= 500 -> "平台暂时不可用（HTTP $status）"
        else -> "平台返回错误（HTTP $status）"
    }

    /** How long to wait after a sync that worked: the interval of the document. */
    fun refreshDelayMs(refreshMinutes: Int): Long = refreshMinutes * MINUTE_MS

    /**
     * How long to wait after [failures] syncs in a row failed: 1, 2, 4 … minutes, never longer
     * than the refresh interval. A Retry-After of the platform ([retryAfterSeconds]) is waited
     * out, within that interval as well.
     */
    fun retryDelayMs(failures: Int, refreshMinutes: Int, retryAfterSeconds: Long?): Long {
        val limit = refreshDelayMs(refreshMinutes)
        val backoff = (MINUTE_MS shl (failures - 1).coerceIn(0, 20)).coerceAtMost(limit)
        val asked = (retryAfterSeconds ?: 0L).coerceIn(0, limit / 1000) * 1000
        return maxOf(backoff, asked)
    }

    /** Whether coming to the front syncs, the last attempt having started at [lastAttemptMs]. */
    fun dueOnFront(nowMs: Long, lastAttemptMs: Long): Boolean = nowMs - lastAttemptMs >= FRONT_MIN_GAP_MS || nowMs < lastAttemptMs

    /** Whether a document of [version] changes anything after [applied] was applied. */
    fun isNew(version: String, applied: String): Boolean = version != applied

    /** A moment as RFC 3339, to the second, in UTC. */
    fun timestamp(ms: Long): String = Instant.ofEpochMilli(ms).truncatedTo(ChronoUnit.SECONDS).toString()

    /** An RFC 3339 time of the platform as it is shown; the text itself where it is no such time. */
    fun shownTime(rfc3339: String, zone: ZoneId): String = try {
        OffsetDateTime.parse(rfc3339).atZoneSameInstant(zone).format(SHOWN)
    } catch (e: DateTimeParseException) {
        rfc3339
    }

    /** A moment of the phone's clock as it is shown. */
    fun shownTime(ms: Long, zone: ZoneId): String = Instant.ofEpochMilli(ms).atZone(zone).format(SHOWN)

    private val SHOWN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    /** The last document the phone tried, and why it refused it (empty: it did not). */
    data class Applied(val version: String, val at: String, val error: String)

    /** The registration of one handed-out account, by its key. */
    data class Registration(val account: String, val state: String, val error: String)

    /** What the phone says about itself (§3.4); no number, contact, call or place. */
    data class Device(
        val appVersion: String,
        val appVersionCode: Int,
        val androidRelease: String,
        val androidSdk: Int,
        val model: String,
        val applied: Applied,
        val registrations: List<Registration>,
        val audioCodecs: List<String>,
        val videoCodecs: List<String>,
    )

    private fun deviceJson(device: Device): Map<String, Any?> = linkedMapOf(
        "appVersion" to device.appVersion,
        "appVersionCode" to device.appVersionCode,
        "androidRelease" to device.androidRelease,
        "androidSdk" to device.androidSdk,
        "model" to device.model,
        "applied" to linkedMapOf("version" to device.applied.version, "at" to device.applied.at, "error" to device.applied.error),
        "registrations" to device.registrations.map { linkedMapOf("account" to it.account, "state" to it.state, "error" to it.error) },
        "codecs" to linkedMapOf("audio" to device.audioCodecs, "video" to device.videoCodecs),
    )

    /** The body of the request that binds the phone (§3.2). */
    fun enrollBody(installId: String, device: Device): String = Json.write(linkedMapOf("installId" to installId, "device" to deviceJson(device)))

    /** The body of a sync request (§3.3); [have] is the version applied, or empty. */
    fun syncBody(have: String, device: Device): String = Json.write(linkedMapOf("have" to have, "device" to deviceJson(device)))

    /** The body of the request that unbinds the phone: the design names none, so an empty object. */
    const val LEAVE_BODY = "{}"
}
