package com.kmnexus.codexmeter.providers.antigravity

import com.kmnexus.codexmeter.domain.model.LocalAccountId
import com.kmnexus.codexmeter.domain.quota.QuotaSnapshotSource
import com.kmnexus.codexmeter.providers.antigravity.dto.AntigravityQuotaResponseDto
import com.kmnexus.codexmeter.providers.antigravity.mapper.AntigravityQuotaMapper
import java.time.Instant
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class AntigravityUsageMappingTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun groupsBucketsByFamilyAndShowsLowestRemaining() {
        val body = """
            {
              "buckets": [
                {"modelId": "claude-opus-4-6-thinking", "remainingFraction": 0.4, "resetTime": "2026-06-01T12:00:00Z"},
                {"modelId": "claude-sonnet-4-6", "remainingFraction": 0.9, "resetTime": "2026-06-01T12:00:00Z"},
                {"modelId": "gemini-3.1-pro-high", "remainingFraction": 0.8, "resetTime": "2026-06-01T12:00:00Z"},
                {"modelId": "gemini-2.5-flash", "remainingFraction": 1.0, "resetTime": "2026-06-01T12:00:00Z"},
                {"modelId": "gpt-oss-120b-medium", "remainingFraction": 1.0, "resetTime": "2026-06-01T12:00:00Z"},
                {"modelId": "gemini-3-flash-agent", "remainingFraction": 0.1, "resetTime": "2026-06-01T12:00:00Z"},
                {"modelId": "chat_20706", "remainingFraction": 1.0}
              ]
            }
        """.trimIndent()

        val dto = json.decodeFromString<AntigravityQuotaResponseDto>(body)
            .copy(tier = "free-tier")
        val snapshot = AntigravityQuotaMapper.map(
            dto = dto,
            localAccountId = LocalAccountId("antigravity-1"),
            providerAccountId = null,
            fetchedAt = Instant.parse("2026-06-01T00:00:00Z"),
            source = QuotaSnapshotSource.AppOpenRefresh,
        )

        assertEquals("free-tier", snapshot.planType)

        // One window per visible family; internal (chat_*) and agent models excluded.
        val byId = snapshot.windows.associateBy { it.windowId.value }
        assertEquals(
            setOf(
                "antigravity_claude_window",
                "antigravity_gemini_pro_window",
                "antigravity_gemini_flash_window",
                "antigravity_gpt_oss_window",
            ),
            byId.keys,
        )

        // Claude family lowest remaining = 0.4 -> 60% used; both claude models retained as buckets.
        val claude = byId.getValue("antigravity_claude_window")
        assertEquals(60, claude.usedPercent)
        assertEquals(2, claude.modelBuckets.size)
        assertEquals(true, claude.usesModelBucketSum)

        // Gemini Flash agent model is excluded, so the only flash bucket is full (0% used).
        val flash = byId.getValue("antigravity_gemini_flash_window")
        assertEquals(0, flash.usedPercent)
        assertEquals(1, flash.modelBuckets.size)
        assertEquals(true, flash.usesModelBucketSum)

        // Most-constrained family (Claude, 60% used) is the primary candidate.
        assertEquals("antigravity_claude_window", snapshot.windows.first { it.isPrimaryCandidate }.windowId.value)
    }
}
