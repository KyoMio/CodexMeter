package com.kmnexus.codexmeter.providers.claude

import com.kmnexus.codexmeter.domain.model.LocalAccountId
import com.kmnexus.codexmeter.domain.quota.QuotaSnapshotSource
import com.kmnexus.codexmeter.providers.claude.dto.ClaudeUsageResponseDto
import com.kmnexus.codexmeter.providers.claude.mapper.ClaudeUsageMapper
import java.time.Instant
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ClaudeUsageMappingTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun mapsOAuthUtilizationWindows() {
        val body = """
            {
              "five_hour": {"utilization": 62.0, "resets_at": "2026-06-01T05:00:00Z"},
              "seven_day": {"utilization": 40.0, "resets_at": "2026-06-08T00:00:00Z"}
            }
        """.trimIndent()

        val dto = json.decodeFromString<ClaudeUsageResponseDto>(body)
        val snapshot = ClaudeUsageMapper.map(
            dto = dto,
            localAccountId = LocalAccountId("claude-1"),
            providerAccountId = null,
            fetchedAt = Instant.parse("2026-06-01T00:00:00Z"),
            source = QuotaSnapshotSource.AppOpenRefresh,
        )

        val fiveHour = snapshot.windows.first { it.windowId.value == "claude_5h_window" }
        assertEquals(62, fiveHour.usedPercent)
        assertNotNull(fiveHour.resetAt)
        assertEquals(true, fiveHour.isPrimaryCandidate)
        assertEquals(40, snapshot.windows.first { it.windowId.value == "claude_7d_window" }.usedPercent)
    }

    @Test
    fun mapsEnabledExtraUsageToSpendWindow() {
        val body = """
            {
              "five_hour": {"utilization": 10.0, "resets_at": "2026-06-01T05:00:00Z"},
              "seven_day": {"utilization": 20.0, "resets_at": "2026-06-08T00:00:00Z"},
              "extra_usage": {
                "is_enabled": true,
                "monthly_limit": 10000,
                "used_credits": 2500,
                "utilization": 25.0,
                "currency": "USD"
              }
            }
        """.trimIndent()

        val dto = json.decodeFromString<ClaudeUsageResponseDto>(body)
        val snapshot = ClaudeUsageMapper.map(
            dto = dto,
            localAccountId = LocalAccountId("claude-1"),
            providerAccountId = null,
            fetchedAt = Instant.parse("2026-06-01T00:00:00Z"),
            source = QuotaSnapshotSource.AppOpenRefresh,
        )

        val extra = snapshot.windows.first { it.windowId.value == "claude_extra_usage" }
        assertEquals(25, extra.usedPercent)
        assertEquals("$25.00 / $100.00", extra.subLabel)
        // Ordered right after the 7-day window (so it lands within the account summary).
        assertEquals(2, snapshot.windows.indexOfFirst { it.windowId.value == "claude_extra_usage" })
    }

    @Test
    fun omitsDisabledExtraUsage() {
        val body = """
            {
              "five_hour": {"utilization": 10.0, "resets_at": "2026-06-01T05:00:00Z"},
              "extra_usage": {"is_enabled": false, "monthly_limit": 10000, "used_credits": 0}
            }
        """.trimIndent()

        val dto = json.decodeFromString<ClaudeUsageResponseDto>(body)
        val snapshot = ClaudeUsageMapper.map(
            dto = dto,
            localAccountId = LocalAccountId("claude-1"),
            providerAccountId = null,
            fetchedAt = Instant.parse("2026-06-01T00:00:00Z"),
            source = QuotaSnapshotSource.AppOpenRefresh,
        )

        assertEquals(0, snapshot.windows.count { it.windowId.value == "claude_extra_usage" })
    }
}
