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
    private val json = Json { ignoreUnknownKeys = true }
    private val localAccountId = LocalAccountId("kimi-1")
    private val fetchedAt = Instant.parse("2026-06-01T00:00:00Z")

    /** Mirrors the real GetUsages "primary" capture: no `used` field (use limit−remaining), and
     *  enum-style `timeUnit` "TIME_UNIT_MINUTE" with duration 300 (= 5h). */
    @Test
    fun mapsCodingScopeWeeklyAndRateWindows() {
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

        val rate = snapshot.windows.first { it.windowId.value == "kimi_rate_window" }
        assertEquals(0, rate.usedPercent)
        assertEquals(300 * 60, rate.limitWindowSeconds)

        // Rendered as percentage (not raw count), and the 5h rate window comes first.
        assertEquals(QuotaWindowDisplayKind.Percent, rate.displayKind)
        assertEquals(QuotaWindowDisplayKind.Percent, weekly.displayKind)
        assertEquals("kimi_rate_window", snapshot.windows.first().windowId.value)
    }
}
