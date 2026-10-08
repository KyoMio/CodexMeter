package com.kmnexus.codexmeter.providers.kimicode.mapper

import com.kmnexus.codexmeter.domain.model.LocalAccountId
import com.kmnexus.codexmeter.domain.quota.QuotaSnapshotSource
import com.kmnexus.codexmeter.domain.quota.QuotaWindowAvailability
import com.kmnexus.codexmeter.domain.quota.QuotaWindowDisplayKind
import com.kmnexus.codexmeter.providers.kimicode.dto.KimiCodeUsageResponseDto
import java.time.Instant
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KimiCodeUsageMapperTest {
    private val json = Json {
        ignoreUnknownKeys = true
    }
    private val localAccountId = LocalAccountId("kimi-code-1")
    private val fetchedAt = Instant.parse("2026-10-08T00:00:00Z")

    private fun decode(body: String): KimiCodeUsageResponseDto = json.decodeFromString(body)

    /** The CodexBar-shaped ratio pool: all three windows as 0–1 used ratios. */
    @Test
    fun ratioPoolMapsThreeWindows() {
        val dto = decode(
            """
            {
              "usage": {"limit": "100", "used": "19", "remaining": "81", "resetTime": "2026-09-19T16:45:59Z"},
              "usages": {
                "limit_5h": {"used_ratio": 0.625, "reset_time": "2026-09-16T20:15:44Z"},
                "limit_7d": {"used_ratio": 0.125, "reset_time": "2026-09-20T00:00:00Z"},
                "limit_month_total": {"used_ratio": 0.0056, "reset_time": "2026-10-17T00:00:00Z"}
              }
            }
            """.trimIndent(),
        )

        val windows = KimiCodeUsageMapper.buildWindows(dto)

        assertEquals(listOf("kimi_code_5h", "kimi_code_7d", "kimi_code_month_total"), windows.map { it.windowId.value })
        val fiveHour = windows[0]
        assertEquals(18000, fiveHour.limitWindowSeconds)
        assertEquals(62, fiveHour.usedPercent) // 0.625 → 62.5 → 62 (truncate, like the other mappers)
        assertTrue(fiveHour.isPrimaryCandidate)
        assertEquals(Instant.parse("2026-09-16T20:15:44Z"), fiveHour.resetAt)
        val weekly = windows[1]
        assertEquals(604800, weekly.limitWindowSeconds)
        assertEquals(12, weekly.usedPercent)
        assertFalse(weekly.isPrimaryCandidate)
        val monthly = windows[2]
        // Month-total usage never assumes a day count, so the duration stays unknown.
        assertNull(monthly.limitWindowSeconds)
        assertEquals(0, monthly.usedPercent) // 0.56% truncates to 0
        windows.forEach { window ->
            assertEquals(window.windowId.value, window.titleKey)
            assertEquals(QuotaWindowDisplayKind.Percent, window.displayKind)
            // Ratio windows carry no counts; the UI renders the percent only.
            assertNull(window.usedCount)
            assertNull(window.limitCount)
        }
    }

    /** Without the ratio pool the counts windows become the rate window + the cycle usage window. */
    @Test
    fun countsOnlyFallbackMapsRateAndUsageWindows() {
        val dto = decode(
            """
            {
              "usage": {"limit": "100", "used": 19, "resetAt": "2026-09-19T16:45:59Z"},
              "limits": [
                {
                  "window": {"duration": 300, "timeUnit": "TIME_UNIT_MINUTE"},
                  "detail": {"limit": 100, "used": 10, "reset_time": "2026-09-16T20:15:44Z"}
                }
              ]
            }
            """.trimIndent(),
        )

        val windows = KimiCodeUsageMapper.buildWindows(dto)

        assertEquals(listOf("kimi_code_rate", "kimi_code_usage"), windows.map { it.windowId.value })
        val rate = windows[0]
        assertTrue(rate.isPrimaryCandidate)
        // 300 minutes = 5 hours.
        assertEquals(18000, rate.limitWindowSeconds)
        assertEquals(10, rate.usedPercent)
        assertEquals(Instant.parse("2026-09-16T20:15:44Z"), rate.resetAt)
        val usage = windows[1]
        assertFalse(usage.isPrimaryCandidate)
        // Never infer a weekly/monthly period from the cycle usage window.
        assertNull(usage.limitWindowSeconds)
        assertEquals(19, usage.usedPercent)
        assertEquals(Instant.parse("2026-09-19T16:45:59Z"), usage.resetAt)
        // Count fallbacks fill usedCount/limitCount.
        assertEquals(10, rate.usedCount)
        assertEquals(100, rate.limitCount)
    }

    /** Ratio windows win per slot: a present limit_5h blocks the rate window, a missing limit_7d falls back to `usage`. */
    @Test
    fun mixedInputPrefersRatioPoolPerSlot() {
        val dto = decode(
            """
            {
              "usage": {"limit": "200", "used": "40", "resetTime": "2026-09-19T16:45:59Z"},
              "usages": {
                "limit_5h": {"used_ratio": 0.5, "reset_time": "2026-09-16T20:15:44Z"}
              },
              "limits": [
                {
                  "window": {"duration": 5, "timeUnit": "TIME_UNIT_HOUR"},
                  "detail": {"limit": "100", "used": "99", "resetTime": "2026-09-16T20:15:44Z"}
                }
              ]
            }
            """.trimIndent(),
        )

        val windows = KimiCodeUsageMapper.buildWindows(dto)

        // limit_5h exists → ratio window; limits[0] must NOT surface; limit_7d missing → counts fallback.
        assertEquals(listOf("kimi_code_5h", "kimi_code_usage"), windows.map { it.windowId.value })
        assertEquals(50, windows[0].usedPercent)
        assertNull(windows[0].usedCount)
        assertEquals(20, windows[1].usedPercent)
    }

    /** `usage`/`limits[].detail` counts may arrive as strings, ints or doubles. */
    @Test
    fun countsAcceptStringIntAndDouble() {
        val dto = decode(
            """
            {
              "usage": {"limit": 100.5, "used": 25, "remaining": 75.5},
              "limits": [
                {
                  "window": {"duration": 1, "timeUnit": "TIME_UNIT_DAY"},
                  "detail": {"limit": "80", "remaining": "60"}
                }
              ]
            }
            """.trimIndent(),
        )

        val windows = KimiCodeUsageMapper.buildWindows(dto)

        val rate = windows.first { it.windowId.value == "kimi_code_rate" }
        // used missing → limit − remaining = 80 − 60 = 20 → 25%.
        assertEquals(20, rate.usedCount)
        assertEquals(25, rate.usedPercent)
        assertEquals(86400, rate.limitWindowSeconds)
        val usage = windows.first { it.windowId.value == "kimi_code_usage" }
        assertEquals(25, usage.usedCount)
        // 25 / 100.5 → 24.87…% truncates to 24.
        assertEquals(24, usage.usedPercent)
    }

    /** `used_ratio` is a 0–1 fraction that can go out of bounds; clamp to 0–100%. */
    @Test
    fun outOfRangeRatiosAreClamped() {
        val dto = decode(
            """
            {
              "usages": {
                "limit_5h": {"used_ratio": 1.05, "reset_time": "2026-09-16T20:15:44Z"},
                "limit_7d": {"used_ratio": -0.5, "reset_time": "2026-09-20T00:00:00Z"}
              }
            }
            """.trimIndent(),
        )

        val windows = KimiCodeUsageMapper.buildWindows(dto)

        assertEquals(100, windows[0].usedPercent)
        assertEquals(QuotaWindowAvailability.Depleted, windows[0].availability)
        assertEquals(0, windows[1].usedPercent)
    }

    /** Empty payloads must surface zero windows (the client then fails with no_quota_windows). */
    @Test
    fun emptyInputsProduceZeroWindows() {
        listOf("{}", """{"usages":{}}""", """{"usages":{"limit_5h":{}}}""").forEach { body ->
            assertTrue(
                "expected no windows for $body",
                KimiCodeUsageMapper.buildWindows(decode(body)).isEmpty(),
            )
        }
    }

    /** `limit<=0` count windows are missing, not "0% used". */
    @Test
    fun nonPositiveLimitSkipsCountWindow() {
        val dto = decode(
            """
            {
              "usage": {"limit": "0", "used": "0"},
              "limits": [
                {"window": {"duration": 5, "timeUnit": "TIME_UNIT_MINUTE"}, "detail": {"limit": -3, "used": "1"}}
              ]
            }
            """.trimIndent(),
        )

        assertTrue(KimiCodeUsageMapper.buildWindows(dto).isEmpty())
    }

    /** resetTime may arrive under any of the observed alias keys. */
    @Test
    fun resetTimeAliasesAreAccepted() {
        val ratioPool = decode(
            """
            {
              "usages": {
                "limit_5h": {"used_ratio": 0.5, "resetAt": "2026-09-16T20:15:44Z"},
                "limit_7d": {"used_ratio": 0.5, "reset_at": "2026-09-20T00:00:00Z"}
              }
            }
            """.trimIndent(),
        )
        // No ratio pools → the count fallbacks surface; both carry the `reset_at` alias.
        val counts = decode(
            """
            {
              "usage": {"limit": "10", "used": "1", "reset_at": "2026-09-19T16:45:59Z"},
              "limits": [
                {"window": {"duration": 1, "timeUnit": "TIME_UNIT_HOUR"},
                 "detail": {"limit": "10", "used": "1", "reset_at": "2026-09-16T19:15:44Z"}}
              ]
            }
            """.trimIndent(),
        )

        val ratioWindows = KimiCodeUsageMapper.buildWindows(ratioPool)
        assertEquals(Instant.parse("2026-09-16T20:15:44Z"), ratioWindows[0].resetAt)
        assertEquals(Instant.parse("2026-09-20T00:00:00Z"), ratioWindows[1].resetAt)

        val countWindows = KimiCodeUsageMapper.buildWindows(counts)
        assertEquals(Instant.parse("2026-09-16T19:15:44Z"), countWindows.first { it.windowId.value == "kimi_code_rate" }.resetAt)
        assertEquals(Instant.parse("2026-09-19T16:45:59Z"), countWindows.first { it.windowId.value == "kimi_code_usage" }.resetAt)
    }

    @Test
    fun ratioPoolFullUsageIsDepleted() {
        val dto = decode(
            """
            {"usages": {"limit_5h": {"used_ratio": 1.0, "reset_time": "2026-09-16T20:15:44Z"}}}
            """.trimIndent(),
        )

        val windows = KimiCodeUsageMapper.buildWindows(dto)

        assertEquals(1, windows.size)
        assertEquals(QuotaWindowAvailability.Depleted, windows[0].availability)
    }

    @Test
    fun mapBuildsSnapshotWithKimiCodeIdentity() {
        val dto = decode(
            """
            {
              "usages": {"limit_5h": {"used_ratio": 0.25, "reset_time": "2026-09-16T20:15:44Z"}},
              "user": {"membership": {"level": "LEVEL_INTERMEDIATE"}}
            }
            """.trimIndent(),
        )

        val snapshot = KimiCodeUsageMapper.map(
            dto = dto,
            localAccountId = localAccountId,
            providerAccountId = null,
            fetchedAt = fetchedAt,
            source = QuotaSnapshotSource.ApiKeyImport,
        )

        assertEquals("kimi_code", snapshot.providerId.value)
        assertEquals("kimi_code_${fetchedAt}", snapshot.snapshotId.value)
        assertEquals(localAccountId, snapshot.localAccountId)
        assertNull(snapshot.planType)
        assertNull(snapshot.credits)
        assertNull(snapshot.responseDigest)
        assertEquals(1, snapshot.windows.size)
        assertEquals("kimi_code_5h", snapshot.windows[0].titleKey)
    }
}
