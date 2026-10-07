package com.kmnexus.codexmeter.providers.kimi

import com.kmnexus.codexmeter.domain.model.LocalAccountId
import com.kmnexus.codexmeter.domain.quota.QuotaSnapshotSource
import com.kmnexus.codexmeter.domain.quota.QuotaWindowDisplayKind
import com.kmnexus.codexmeter.providers.kimi.dto.KimiQuotaResponseDto
import com.kmnexus.codexmeter.providers.kimi.mapper.KimiQuotaMapper
import java.time.Instant
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class KimiUsageMappingTest {
    @Test
    fun cycleDurationIsUnknownRegardlessOfResetDistanceOrFetchTime() {
        val reset = Instant.parse("2026-07-01T00:00:00Z")
        val dto = KimiQuotaResponseDto(listOf(KimiQuotaResponseDto.Usage(
            scope = "FEATURE_CODING",
            detail = KimiQuotaResponseDto.Detail(limit = "100", remaining = "80", resetTime = reset.toString()),
        )))
        listOf(reset.minusSeconds(30L * 86400), reset.minusSeconds(7L * 86400), reset.minusSeconds(60), reset.plusSeconds(60)).forEach { now ->
            val cycle = KimiQuotaMapper.map(dto, LocalAccountId("kimi-test"), null, now, QuotaSnapshotSource.CookieAuth).windows.single()
            assertEquals("kimi_weekly_window", cycle.windowId.value)
            assertEquals(null, cycle.limitWindowSeconds)
            assertEquals(reset, cycle.resetAt)
            assertEquals(80, cycle.remainingPercent)
        }
    }

    @Test
    fun rateDurationUsesOnlyValidApiMetadata() {
        val cases = listOf(
            Triple(300, "TIME_UNIT_MINUTE", 18000),
            Triple(5, "TIME_UNIT_HOUR", 18000),
            Triple(1, "TIME_UNIT_DAY", 86400),
            Triple(0, "TIME_UNIT_HOUR", null),
            Triple(-1, "TIME_UNIT_HOUR", null),
            Triple(Int.MAX_VALUE, "TIME_UNIT_DAY", null),
            Triple(5, "UNKNOWN_HOUR", null),
            Triple(1, "TIME_UNIT_MONTH", null),
            Triple(null, "TIME_UNIT_HOUR", null),
            Triple(5, null, null),
        )
        cases.forEach { (duration, unit, expected) ->
            val dto = KimiQuotaResponseDto(listOf(KimiQuotaResponseDto.Usage(
                limits = listOf(KimiQuotaResponseDto.RateLimit(
                    window = KimiQuotaResponseDto.Window(duration, unit),
                    detail = KimiQuotaResponseDto.Detail(limit = "100", remaining = "80"),
                )),
            )))
            val rate = KimiQuotaMapper.map(dto, LocalAccountId("kimi-test"), null, Instant.EPOCH, QuotaSnapshotSource.CookieAuth).windows.single()
            assertEquals("$duration $unit", expected, rate.limitWindowSeconds)
        }
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val localAccountId = LocalAccountId("kimi-1")
    private val fetchedAt = Instant.parse("2026-06-01T00:00:00Z")

    /** Synthetic GetUsages fixture: missing `used` falls back to limit−remaining. */
    @Test
    fun mapsCodingScopeCycleAndRateWindows() {
        val body = """
            {
              "usages": [
                {
                  "scope": "FEATURE_CODING",
                  "detail": {"limit":"100","remaining":"80","resetTime":"2026-06-06T12:57:23.215757Z"},
                  "limits": [
                    {
                      "window": {"duration": 300, "timeUnit": "TIME_UNIT_MINUTE"},
                      "detail": {"limit":"100","remaining":"100","resetTime":"2026-05-30T17:57:23.215757Z"}
                    }
                  ]
                }
              ],
              "totalQuota": {"limit":"100","remaining":"80"}
            }
        """.trimIndent()

        val dto = json.decodeFromString<KimiQuotaResponseDto>(body)
        val snapshot = KimiQuotaMapper.map(
            dto = dto,
            localAccountId = localAccountId,
            providerAccountId = null,
            fetchedAt = fetchedAt,
            source = QuotaSnapshotSource.CookieAuth,
        )

        val weekly = snapshot.windows.first { it.windowId.value == "kimi_weekly_window" }
        // no `used` field → used = limit − remaining = 20 → 20%
        assertEquals(20, weekly.usedPercent)
        assertEquals(20, weekly.usedCount)
        assertEquals(100, weekly.limitCount)
        assertNotNull(weekly.resetAt)
        assertEquals(null, weekly.limitWindowSeconds)

        val rate = snapshot.windows.first { it.windowId.value == "kimi_rate_window" }
        assertEquals(0, rate.usedPercent)
        assertEquals(300 * 60, rate.limitWindowSeconds)

        // Rendered as percentage (not raw count), and the 5h rate window comes first.
        assertEquals(QuotaWindowDisplayKind.Percent, rate.displayKind)
        assertEquals(QuotaWindowDisplayKind.Percent, weekly.displayKind)
        assertEquals("kimi_rate_window", snapshot.windows.first().windowId.value)
    }
}
