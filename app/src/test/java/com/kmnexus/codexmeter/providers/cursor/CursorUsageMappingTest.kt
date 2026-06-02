package com.kmnexus.codexmeter.providers.cursor

import com.kmnexus.codexmeter.domain.model.LocalAccountId
import com.kmnexus.codexmeter.domain.quota.QuotaSnapshotSource
import com.kmnexus.codexmeter.domain.quota.QuotaWindowAvailability
import com.kmnexus.codexmeter.providers.cursor.dto.CursorUsageResponseDto
import com.kmnexus.codexmeter.providers.cursor.mapper.CursorUsageMapper
import java.time.Instant
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class CursorUsageMappingTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun mapsUsageSummaryCentsBucketsToPercent() {
        val body = """
            {
              "membershipType": "pro",
              "individualUsage": {
                "plan": {"enabled": true, "used": 5000, "limit": 10000, "remaining": 5000},
                "onDemand": {"enabled": true, "used": 2500, "limit": 10000, "remaining": 7500}
              }
            }
        """.trimIndent()

        val dto = json.decodeFromString<CursorUsageResponseDto>(body)
        val snapshot = CursorUsageMapper.map(
            dto = dto,
            localAccountId = LocalAccountId("cursor-1"),
            providerAccountId = null,
            fetchedAt = Instant.parse("2026-06-01T00:00:00Z"),
            source = QuotaSnapshotSource.CookieAuth,
        )

        assertEquals("pro", snapshot.planType)
        assertEquals(50, snapshot.windows.first { it.windowId.value == "cursor_plan" }.usedPercent)
        assertEquals(25, snapshot.windows.first { it.windowId.value == "cursor_on_demand" }.usedPercent)
    }

    /**
     * Free-tier accounts report `plan.limit == 0` but still carry `totalPercentUsed`. The mapper must
     * fall back to the percent field so the plan window is `Available` (and thus rendered) instead of
     * being dropped as `Missing` (the bug behind "Cursor quota not displaying").
     */
    @Test
    fun mapsFreeTierTotalPercentUsedWhenLimitIsZero() {
        val body = """
            {
              "membershipType": "free",
              "isUnlimited": false,
              "individualUsage": {
                "plan": {"enabled": true, "used": 0, "limit": 0, "remaining": 0, "totalPercentUsed": 0},
                "onDemand": {"enabled": false, "used": 0, "limit": null, "remaining": null}
              }
            }
        """.trimIndent()

        val dto = json.decodeFromString<CursorUsageResponseDto>(body)
        val snapshot = CursorUsageMapper.map(
            dto = dto,
            localAccountId = LocalAccountId("cursor-free"),
            providerAccountId = null,
            fetchedAt = Instant.parse("2026-06-01T00:00:00Z"),
            source = QuotaSnapshotSource.CookieAuth,
        )

        val plan = snapshot.windows.first { it.windowId.value == "cursor_plan" }
        assertEquals(0, plan.usedPercent)
        assertEquals(QuotaWindowAvailability.Available, plan.availability)
    }
}
