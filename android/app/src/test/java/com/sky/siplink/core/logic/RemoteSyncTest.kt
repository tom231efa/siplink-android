package com.sky.siplink.core.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class RemoteSyncTest {
    private val minute = 60_000L

    @Test
    fun theAnswersMeanWhatTheDesignSays() {
        assertEquals(RemoteSync.Outcome.DOCUMENT, RemoteSync.outcome(200, enrolling = false))
        assertEquals(RemoteSync.Outcome.DOCUMENT, RemoteSync.outcome(200, enrolling = true))
        assertEquals(RemoteSync.Outcome.UNCHANGED, RemoteSync.outcome(204, enrolling = false))
        assertEquals(RemoteSync.Outcome.REVOKED, RemoteSync.outcome(410, enrolling = false))
        assertEquals(RemoteSync.Outcome.INVALID_TOKEN, RemoteSync.outcome(404, enrolling = true))
        // Binding knows no "unchanged" and no "revoked"; nothing else changes the configuration.
        listOf(204 to true, 410 to true, 401 to false, 404 to false, 429 to false, 500 to false, 503 to true, 302 to false).forEach { (status, enrolling) ->
            assertEquals("$status", RemoteSync.Outcome.FAILED, RemoteSync.outcome(status, enrolling))
        }
    }

    @Test
    fun failuresAreToldInChinese() {
        assertEquals(RemoteSync.UNAUTHORIZED, RemoteSync.failureText(401))
        assertEquals("二维码无效、已使用或已过期", RemoteSync.failureText(404))
        assertEquals("请求过于频繁，请稍后重试", RemoteSync.failureText(429))
        assertEquals("平台暂时不可用（HTTP 503）", RemoteSync.failureText(503))
        assertEquals("平台返回错误（HTTP 400）", RemoteSync.failureText(400))
    }

    @Test
    fun aSyncThatWorkedWaitsTheRefreshInterval() {
        assertEquals(60 * minute, RemoteSync.refreshDelayMs(60))
        assertEquals(5 * minute, RemoteSync.refreshDelayMs(5))
    }

    @Test
    fun failuresAreRetriedSoonerAtFirstButNeverLaterThanTheInterval() {
        assertEquals(listOf(1, 2, 4, 8, 16, 32, 60, 60).map { it * minute }, (1..8).map { RemoteSync.retryDelayMs(it, 60, null) })
        assertEquals(5 * minute, RemoteSync.retryDelayMs(10, 5, null))
        // What the platform asks to wait is waited, within the interval.
        assertEquals(3 * minute, RemoteSync.retryDelayMs(1, 60, 180))
        assertEquals(2 * minute, RemoteSync.retryDelayMs(2, 60, 30))
        assertEquals(60 * minute, RemoteSync.retryDelayMs(1, 60, 100_000))
        assertEquals(minute, RemoteSync.retryDelayMs(1, 60, -5))
    }

    @Test
    fun comingToTheFrontSyncsOnceAMinuteAtMost() {
        val now = 10 * minute
        assertTrue(RemoteSync.dueOnFront(now, 0))
        assertTrue(RemoteSync.dueOnFront(now, now - minute))
        assertFalse(RemoteSync.dueOnFront(now, now - minute + 1))
        // A clock set back does not keep the phone from syncing.
        assertTrue(RemoteSync.dueOnFront(now, now + minute))
    }

    @Test
    fun onlyAnotherVersionIsNew() {
        assertTrue(RemoteSync.isNew("b".repeat(64), "a".repeat(64)))
        assertTrue(RemoteSync.isNew("a".repeat(64), ""))
        assertFalse(RemoteSync.isNew("a".repeat(64), "a".repeat(64)))
    }

    @Test
    fun timesAreRfc3339AndShownInTheLocalZone() {
        assertEquals("2026-11-16T08:30:00Z", RemoteSync.timestamp(1_794_817_800_123L))
        val shanghai = ZoneId.of("Asia/Shanghai")
        assertEquals("2026-11-16 16:30", RemoteSync.shownTime("2026-11-16T08:30:00Z", shanghai))
        assertEquals("2026-11-16 16:30", RemoteSync.shownTime("2026-11-16T16:30:00+08:00", shanghai))
        assertEquals("不是时间", RemoteSync.shownTime("不是时间", shanghai))
        assertEquals("2026-11-16 16:30", RemoteSync.shownTime(1_794_817_800_123L, shanghai))
    }

    @Test
    fun theReportCarriesTheFieldsOfTheDesign() {
        val device = RemoteSync.Device(
            appVersion = "0.1.2",
            appVersionCode = 3,
            androidRelease = "16",
            androidSdk = 36,
            model = "Example Phone",
            applied = RemoteSync.Applied("v1", "2026-11-16T08:30:00Z", ""),
            registrations = listOf(RemoteSync.Registration("p1", "OK", ""), RemoteSync.Registration("p2", "FAILED", "403 Forbidden")),
            audioCodecs = listOf("opus/48000/2", "PCMA/8000/1"),
            videoCodecs = listOf("VP8/90000/0"),
        )
        val expectedDevice = mapOf(
            "appVersion" to "0.1.2",
            "appVersionCode" to 3L,
            "androidRelease" to "16",
            "androidSdk" to 36L,
            "model" to "Example Phone",
            "applied" to mapOf("version" to "v1", "at" to "2026-11-16T08:30:00Z", "error" to ""),
            "registrations" to listOf(
                mapOf("account" to "p1", "state" to "OK", "error" to ""),
                mapOf("account" to "p2", "state" to "FAILED", "error" to "403 Forbidden"),
            ),
            "codecs" to mapOf("audio" to listOf("opus/48000/2", "PCMA/8000/1"), "video" to listOf("VP8/90000/0")),
        )
        assertEquals(mapOf("installId" to "id-1", "device" to expectedDevice), Json.parse(RemoteSync.enrollBody("id-1", device)))
        assertEquals(mapOf("have" to "", "device" to expectedDevice), Json.parse(RemoteSync.syncBody("", device)))
        assertEquals(emptyMap<String, Any?>(), Json.parse(RemoteSync.LEAVE_BODY))
    }
}
