package com.kmnexus.codexmeter.providers.antigravity

import com.kmnexus.codexmeter.domain.model.LocalAccountId
import com.kmnexus.codexmeter.domain.quota.QuotaSnapshot
import com.kmnexus.codexmeter.domain.quota.QuotaSnapshotSource
import com.kmnexus.codexmeter.domain.quota.QuotaWindowAvailability
import com.kmnexus.codexmeter.domain.quota.QuotaWindowDisplayKind
import com.kmnexus.codexmeter.providers.antigravity.dto.AntigravityQuotaSummaryDto
import com.kmnexus.codexmeter.providers.antigravity.mapper.AntigravityQuotaMapper
import java.time.Instant
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AntigravitySummaryMappingTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun mapSummary(body: String, tier: String? = "pro-tier"): QuotaSnapshot =
        AntigravityQuotaMapper.mapSummary(
            dto = json.decodeFromString<AntigravityQuotaSummaryDto>(body),
            tier = tier,
            localAccountId = LocalAccountId("antigravity-1"),
            providerAccountId = null,
            fetchedAt = Instant.parse("2026-06-01T00:00:00Z"),
            source = QuotaSnapshotSource.AppOpenRefresh,
        )

    @Test
    fun `maps summary groups into exactly four two-family lane windows`() {
        val body = """
            {
              "description": "quota summary",
              "groups": [
                {"displayName": "Gemini", "buckets": [
                  {"bucketId": "5h", "displayName": "Gemini Pro", "remainingFraction": 0.5, "resetTime": "2026-06-01T12:00:00Z"},
                  {"bucketId": "weekly", "displayName": "Gemini Pro", "remainingFraction": 0.9}
                ]},
                {"displayName": "Claude/GPT", "buckets": [
                  {"bucketId": "5h", "displayName": "Claude", "remainingFraction": 0.25, "resetTime": "2026-06-01T12:00:00Z"},
                  {"bucketId": "weekly", "displayName": "GPT-OSS", "remainingFraction": 0.7}
                ]}
              ]
            }
        """.trimIndent()

        val snapshot = mapSummary(body)

        assertEquals("pro-tier", snapshot.planType)
        val byId = snapshot.windows.associateBy { it.windowId.value }
        assertEquals(
            setOf(
                "antigravity_gemini_5h_window",
                "antigravity_gemini_weekly_window",
                "antigravity_claude_gpt_5h_window",
                "antigravity_claude_gpt_weekly_window",
            ),
            byId.keys,
        )
        // Real-device acceptance: a subscription shows exactly the 4 family x lane windows.
        assertEquals(4, snapshot.windows.size)

        val gemini5h = byId.getValue("antigravity_gemini_5h_window")
        assertEquals(50, gemini5h.usedPercent)
        assertEquals(18_000, gemini5h.limitWindowSeconds)
        assertEquals(Instant.parse("2026-06-01T12:00:00Z"), gemini5h.resetAt)
        assertEquals(QuotaWindowAvailability.Available, gemini5h.availability)
        assertTrue(gemini5h.modelBuckets.isEmpty())
        assertEquals(QuotaWindowDisplayKind.Percent, gemini5h.displayKind)

        val geminiWeekly = byId.getValue("antigravity_gemini_weekly_window")
        // (1 - 0.9) is not binary-exact; truncation like the T-501 path yields 9, not 10.
        assertEquals(9, geminiWeekly.usedPercent)
        assertEquals(604_800, geminiWeekly.limitWindowSeconds)
        assertNull(geminiWeekly.resetAt)

        assertEquals(75, byId.getValue("antigravity_claude_gpt_5h_window").usedPercent)
        assertEquals(30, byId.getValue("antigravity_claude_gpt_weekly_window").usedPercent)

        // proto3 JSON omits default values: a missing remainingFraction means fully used.
        val gpt = byId.getValue("antigravity_claude_gpt_5h_window")
        assertEquals(75, gpt.usedPercent)

        assertEquals(
            "antigravity_claude_gpt_5h_window",
            snapshot.windows.first { it.isPrimaryCandidate }.windowId.value,
        )
    }

    @Test
    fun `explicit window field wins over bucket id and display name`() {
        val body = """
            {"groups": [
              {"displayName": "Claude", "buckets": [
                {"bucketId": "session", "displayName": "Claude Session", "window": "weekly", "remainingFraction": 0.5}
              ]}
            ]}
        """.trimIndent()

        val snapshot = mapSummary(body)

        val window = snapshot.windows.single()
        assertEquals("antigravity_claude_gpt_weekly_window", window.windowId.value)
        assertEquals(604_800, window.limitWindowSeconds)
    }

    @Test
    fun `bucket ids and display names detect lanes through normalization`() {
        val body = """
            {"groups": [
              {"displayName": "Gemini", "buckets": [
                {"bucketId": "5-hour", "displayName": "Pro", "remainingFraction": 0.5},
                {"bucketId": "weekly", "displayName": "Flash", "remainingFraction": 0.5},
                {"bucketId": "five_hour", "displayName": "Ultra", "remainingFraction": 0.5},
                {"bucketId": "opus", "displayName": "Session limit", "remainingFraction": 0.5}
              ]}
            ]}
        """.trimIndent()

        val snapshot = mapSummary(body)

        // 5-hour / five_hour (underscore normalized) / "Session limit" (suffix stripped) all land in
        // the 5h lane and collapse into one window; the weekly id gets its own.
        val byId = snapshot.windows.associateBy { it.windowId.value }
        assertEquals(
            setOf("antigravity_gemini_5h_window", "antigravity_gemini_weekly_window"),
            byId.keys,
        )
        assertEquals(2, snapshot.windows.size)
        assertEquals(50, byId.getValue("antigravity_gemini_5h_window").usedPercent)
    }

    @Test
    fun `claude gpt third-party and unknown buckets all land in the claude gpt family`() {
        val body = """
            {"groups": [
              {"displayName": "Claude", "buckets": [
                {"bucketId": "5h", "displayName": "Sonnet", "remainingFraction": 0.5}
              ]},
              {"displayName": "GPT", "buckets": [
                {"bucketId": "weekly", "displayName": "GPT-OSS"}
              ]},
              {"displayName": "Third-party models", "buckets": [
                {"bucketId": "cycle", "displayName": "proxy", "remainingFraction": 0.9}
              ]},
              {"displayName": "Mystery", "buckets": [
                {"bucketId": "5h", "displayName": "Widget", "remainingFraction": 0.8}
              ]}
            ]}
        """.trimIndent()

        val byId = mapSummary(body).windows.associateBy { it.windowId.value }

        // Everything that is not Gemini belongs to the single claude_gpt family; the 5h rows of the
        // Claude and Mystery groups share one window (the fullest bucket wins: Sonnet 50% used
        // beats Widget 19% used — 1 - 0.8 truncates to 19, not 20) and the proto3 omitted
        // remainingFraction of the GPT weekly row still reads as fully used.
        assertEquals(
            setOf(
                "antigravity_claude_gpt_5h_window",
                "antigravity_claude_gpt_weekly_window",
                "antigravity_claude_gpt_cycle_window",
            ),
            byId.keys,
        )
        assertEquals(50, byId.getValue("antigravity_claude_gpt_5h_window").usedPercent)
        val weekly = byId.getValue("antigravity_claude_gpt_weekly_window")
        assertEquals(100, weekly.usedPercent)
        assertEquals(QuotaWindowAvailability.Depleted, weekly.availability)
    }

    @Test
    fun `non-gemini buckets under the gemini group stay gemini`() {
        val body = """
            {"groups": [
              {"displayName": "Gemini", "buckets": [
                {"bucketId": "5h", "displayName": "flash-preview", "remainingFraction": 0.5}
              ]}
            ]}
        """.trimIndent()

        val snapshot = mapSummary(body)

        // The bucket text has no "gemini" in it; the group name decides the family.
        val window = snapshot.windows.single()
        assertEquals("antigravity_gemini_5h_window", window.windowId.value)
    }

    @Test
    fun `gemini in the bucket text wins over a non-gemini group name`() {
        val body = """
            {"groups": [
              {"displayName": "Claude/GPT", "buckets": [
                {"bucketId": "5h", "displayName": "gemini-pro-5h", "remainingFraction": 0.5}
              ]}
            ]}
        """.trimIndent()

        val snapshot = mapSummary(body)

        // Bucket text outranks the group name (the "5h" bucketId carries the lane, the display
        // name carries the family): dropping the bucket-text check and deciding by group name
        // alone would file this bucket under claude_gpt and turn this test red.
        val window = snapshot.windows.single()
        assertEquals("antigravity_gemini_5h_window", window.windowId.value)
    }

    @Test
    fun `duplicate window ids keep the highest used percent`() {
        val body = """
            {"groups": [
              {"displayName": "Gemini", "buckets": [
                {"bucketId": "5h", "displayName": "Pro", "remainingFraction": 0.9},
                {"bucketId": "quota", "displayName": "Session limit", "remainingFraction": 0.2}
              ]}
            ]}
        """.trimIndent()

        val snapshot = mapSummary(body)

        val window = snapshot.windows.single()
        assertEquals("antigravity_gemini_5h_window", window.windowId.value)
        assertEquals(80, window.usedPercent)
    }

    @Test
    fun `nested remaining fraction counts as known quota`() {
        val body = """
            {"groups": [
              {"displayName": "Gemini", "buckets": [
                {"bucketId": "5h", "displayName": "Pro", "remaining": {"remainingFraction": 0.75}}
              ]}
            ]}
        """.trimIndent()

        val window = mapSummary(body).windows.single()

        assertEquals("antigravity_gemini_5h_window", window.windowId.value)
        assertEquals(25, window.usedPercent)
    }

    @Test
    fun `all disabled buckets produce no windows`() {
        val body = """
            {"groups": [
              {"displayName": "Gemini", "buckets": [
                {"bucketId": "5h", "displayName": "Pro", "remainingFraction": 0.5, "disabled": true}
              ]}
            ]}
        """.trimIndent()

        val snapshot = mapSummary(body)

        assertTrue(snapshot.windows.isEmpty())
    }

    @Test
    fun `response envelope wins over bare top-level groups`() {
        val body = """
            {
              "code": 0,
              "response": {"groups": [
                {"displayName": "Claude", "buckets": [
                  {"bucketId": "5h", "displayName": "Claude", "remainingFraction": 0.25}
                ]}
              ]},
              "groups": [
                {"displayName": "Gemini", "buckets": [
                  {"bucketId": "5h", "displayName": "Gemini", "remainingFraction": 0.5}
                ]}
              ]
            }
        """.trimIndent()

        val byId = mapSummary(body).windows.associateBy { it.windowId.value }

        assertEquals(setOf("antigravity_claude_gpt_5h_window"), byId.keys)
    }
}
