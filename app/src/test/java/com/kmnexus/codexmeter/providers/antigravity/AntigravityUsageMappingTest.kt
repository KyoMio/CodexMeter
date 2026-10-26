package com.kmnexus.codexmeter.providers.antigravity

import com.kmnexus.codexmeter.domain.model.LocalAccountId
import com.kmnexus.codexmeter.domain.quota.QuotaSnapshot
import com.kmnexus.codexmeter.domain.quota.QuotaSnapshotSource
import com.kmnexus.codexmeter.domain.quota.QuotaWindowAvailability
import com.kmnexus.codexmeter.providers.antigravity.dto.AntigravityQuotaResponseDto
import com.kmnexus.codexmeter.providers.antigravity.mapper.AntigravityQuotaMapper
import com.kmnexus.codexmeter.providers.antigravity.network.AntigravityModelQuota
import java.time.Instant
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AntigravityUsageMappingTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun map(body: String): QuotaSnapshot {
        val dto = json.decodeFromString<AntigravityQuotaResponseDto>(body)
        return AntigravityQuotaMapper.map(
            tier = "free-tier",
            models = dto.buckets.mapNotNull { bucket ->
                bucket.modelId?.let {
                    AntigravityModelQuota(
                        modelId = it,
                        label = it,
                        remainingFraction = bucket.remainingFraction,
                        resetTime = bucket.resetTime,
                    )
                }
            },
            localAccountId = LocalAccountId("antigravity-1"),
            providerAccountId = null,
            fetchedAt = Instant.parse("2026-06-01T00:00:00Z"),
            source = QuotaSnapshotSource.AppOpenRefresh,
        )
    }

    @Test
    fun groupsBucketsByFamilyAndShowsLowestRemaining() {
        val body = """
            {
              "buckets": [
                {"modelId": "claude-opus-4-6-thinking", "remainingFraction": 0.4, "resetTime": "2026-06-01T12:00:00Z"},
                {"modelId": "claude-sonnet-4-6", "remainingFraction": 0.9, "resetTime": "2026-06-01T12:00:00Z"},
                {"modelId": "gemini-3.1-pro-high", "remainingFraction": 0.75, "resetTime": "2026-06-01T12:00:00Z"},
                {"modelId": "gemini-2.5-flash", "remainingFraction": 1.0, "resetTime": "2026-06-01T12:00:00Z"},
                {"modelId": "gpt-oss-120b-medium", "remainingFraction": 1.0, "resetTime": "2026-06-01T12:00:00Z"},
                {"modelId": "gemini-3-flash-agent", "remainingFraction": 0.1, "resetTime": "2026-06-01T12:00:00Z"},
                {"modelId": "chat_20706", "remainingFraction": 1.0}
              ]
            }
        """.trimIndent()

        val snapshot = map(body)

        assertEquals("free-tier", snapshot.planType)

        // Two families total: claude and gpt-oss merge into one claude_gpt window; internal
        // (chat_*) and agent models excluded.
        val byId = snapshot.windows.associateBy { it.windowId.value }
        assertEquals(
            setOf(
                "antigravity_claude_gpt_window",
                "antigravity_gemini_window",
            ),
            byId.keys,
        )

        // Claude/GPT family lowest remaining = 0.4 -> 60% used; all three models retained as buckets.
        val claudeGpt = byId.getValue("antigravity_claude_gpt_window")
        assertEquals(60, claudeGpt.usedPercent)
        assertEquals(
            listOf("claude-opus-4-6-thinking", "claude-sonnet-4-6", "gpt-oss-120b-medium"),
            claudeGpt.modelBuckets.map { it.modelId },
        )
        assertEquals(true, claudeGpt.usesModelBucketSum)

        // Pro and flash share the single Gemini window; lowest remaining 0.75 -> 25% used.
        // (0.75 is binary-exact: usedPercent truncates via toInt, so 0.8 would assert as 19, not 20.)
        val gemini = byId.getValue("antigravity_gemini_window")
        assertEquals(25, gemini.usedPercent)
        assertEquals(2, gemini.modelBuckets.size)
        assertEquals(true, gemini.usesModelBucketSum)

        // Family order (Gemini first) decides placement even though Claude/GPT is the fuller
        // family (60% vs 25%); the primary candidate is the Gemini window.
        assertEquals(
            listOf("antigravity_gemini_window", "antigravity_claude_gpt_window"),
            snapshot.windows.map { it.windowId.value },
        )
        assertEquals("antigravity_gemini_window", snapshot.windows.first { it.isPrimaryCandidate }.windowId.value)
    }

    @Test
    fun newGeminiModelsShareTheSingleGeminiWindow() {
        val body = """
            {
              "buckets": [
                {"modelId": "gemini-3.1-pro-high", "remainingFraction": 0.8, "resetTime": "2026-06-01T12:00:00Z"},
                {"modelId": "gemini-2.5-flash", "remainingFraction": 1.0, "resetTime": "2026-06-01T12:00:00Z"},
                {"modelId": "gemini-4-argon", "remainingFraction": 0.5, "resetTime": "2026-06-01T12:00:00Z"}
              ]
            }
        """.trimIndent()

        val snapshot = map(body)

        val gemini = snapshot.windows.single { it.windowId.value == "antigravity_gemini_window" }
        assertEquals(3, gemini.modelBuckets.size)
        // usedPercent comes from the lowest remaining of the three models (0.5 -> 50% used).
        assertEquals(50, gemini.usedPercent)
    }

    @Test
    fun depletedModelWithoutRemainingFractionIsKeptAsFullyUsed() {
        // proto3 JSON omits default values: a 100%-used model reports no remainingFraction at all.
        val body = """
            {
              "buckets": [
                {"modelId": "gemini-2.5-flash", "resetTime": "2026-06-01T12:00:00Z"}
              ]
            }
        """.trimIndent()

        val snapshot = map(body)

        val gemini = snapshot.windows.single { it.windowId.value == "antigravity_gemini_window" }
        assertEquals(0.0, gemini.modelBuckets.single().remainingFraction, 0.0)
        assertEquals(100, gemini.usedPercent)
        assertEquals(QuotaWindowAvailability.Depleted, gemini.availability)
    }

    @Test
    fun bucketWithoutResetTimeIsKeptWithNullResetAt() {
        val body = """
            {
              "buckets": [
                {"modelId": "claude-opus-4-6", "remainingFraction": 0.4}
              ]
            }
        """.trimIndent()

        val snapshot = map(body)

        val claudeGpt = snapshot.windows.single { it.windowId.value == "antigravity_claude_gpt_window" }
        assertNull(claudeGpt.modelBuckets.single().resetAt)
        assertNull(claudeGpt.resetAt)
    }

    @Test
    fun duplicateModelRowsKeepTheLowestRemainingFractionRow() {
        val body = """
            {
              "buckets": [
                {"modelId": "gemini-2.5-flash", "remainingFraction": 0.8, "resetTime": "2026-06-01T12:00:00Z"},
                {"modelId": "gemini-2.5-flash", "remainingFraction": 0.3, "resetTime": "2026-06-02T12:00:00Z"}
              ]
            }
        """.trimIndent()

        val snapshot = map(body)

        val gemini = snapshot.windows.single { it.windowId.value == "antigravity_gemini_window" }
        assertEquals(1, gemini.modelBuckets.size)
        assertEquals(0.3, gemini.modelBuckets.single().remainingFraction, 0.0)
        // The whole 0.3 row is kept, not merged with the sibling row.
        assertEquals(Instant.parse("2026-06-02T12:00:00Z"), gemini.modelBuckets.single().resetAt)
    }

    @Test
    fun duplicateModelRowsWithEqualFractionKeepTheFirstRow() {
        val body = """
            {
              "buckets": [
                {"modelId": "gemini-2.5-flash", "remainingFraction": 0.5, "resetTime": "2026-06-01T12:00:00Z"},
                {"modelId": "gemini-2.5-flash", "remainingFraction": 0.5, "resetTime": "2026-06-02T12:00:00Z"}
              ]
            }
        """.trimIndent()

        val snapshot = map(body)

        val gemini = snapshot.windows.single { it.windowId.value == "antigravity_gemini_window" }
        assertEquals(1, gemini.modelBuckets.size)
        // Ties keep the first occurrence, so the first row's earlier resetTime survives.
        assertEquals(Instant.parse("2026-06-01T12:00:00Z"), gemini.modelBuckets.single().resetAt)
    }

    @Test
    fun claudeGptOssAndUnknownModelsShareTheSingleClaudeGptWindow() {
        // Unknown ids (veo-3) no longer get their own family: everything non-gemini joins claude_gpt.
        val body = """
            {
              "buckets": [
                {"modelId": "claude-opus-4-6", "remainingFraction": 0.4, "resetTime": "2026-06-01T12:00:00Z"},
                {"modelId": "gpt-oss-120b-medium", "remainingFraction": 0.9, "resetTime": "2026-06-01T12:00:00Z"},
                {"modelId": "veo-3", "remainingFraction": 0.7, "resetTime": "2026-06-01T12:00:00Z"}
              ]
            }
        """.trimIndent()

        val snapshot = map(body)

        val claudeGpt = snapshot.windows.single { it.windowId.value == "antigravity_claude_gpt_window" }
        assertEquals(
            listOf("claude-opus-4-6", "gpt-oss-120b-medium", "veo-3"),
            claudeGpt.modelBuckets.map { it.modelId },
        )
        // Lowest remaining 0.4 -> 60% used.
        assertEquals(60, claudeGpt.usedPercent)
        assertEquals(1, snapshot.windows.size)
        // With no Gemini family present the primary candidate falls to the Claude/GPT window.
        assertEquals(
            "antigravity_claude_gpt_window",
            snapshot.windows.single { it.isPrimaryCandidate }.windowId.value,
        )
    }

    @Test
    fun internalModelsAreStillDropped() {
        val body = """
            {
              "buckets": [
                {"modelId": "gemini-3-flash-agent", "remainingFraction": 0.1, "resetTime": "2026-06-01T12:00:00Z"},
                {"modelId": "chat_20706", "remainingFraction": 1.0},
                {"modelId": "tab_x", "remainingFraction": 0.9},
                {"modelId": "gemini-2.5-flash", "remainingFraction": 1.0, "resetTime": "2026-06-01T12:00:00Z"}
              ]
            }
        """.trimIndent()

        val snapshot = map(body)

        // Only the regular model survives; every internal/code-completion model is dropped.
        val gemini = snapshot.windows.single { it.windowId.value == "antigravity_gemini_window" }
        assertEquals(listOf("gemini-2.5-flash"), gemini.modelBuckets.map { it.modelId })
    }

    @Test
    fun imageModelsAreDroppedEvenWhenMostConstrained() {
        val body = """
            {
              "buckets": [
                {"modelId": "gemini-2.0-image-preview", "remainingFraction": 0.05, "resetTime": "2026-06-01T12:00:00Z"},
                {"modelId": "gemini-2.5-flash", "remainingFraction": 0.5, "resetTime": "2026-06-01T12:00:00Z"},
                {"modelId": "claude-opus-4-6", "remainingFraction": 1.0, "resetTime": "2026-06-01T12:00:00Z"}
              ]
            }
        """.trimIndent()

        val snapshot = map(body)

        assertTrue(
            snapshot.windows.flatMap { it.modelBuckets }.none { it.modelId == "gemini-2.0-image-preview" },
        )
        // The image model reports the lowest remaining (0.05); it must not drag the Gemini window
        // down to 95% used — the window reflects the regular models only (0.5 -> 50%).
        val gemini = snapshot.windows.single { it.windowId.value == "antigravity_gemini_window" }
        assertEquals(listOf("gemini-2.5-flash"), gemini.modelBuckets.map { it.modelId })
        assertEquals(50, gemini.usedPercent)
    }

    @Test
    fun snapshotIsEmptyWhenEveryBucketIsDropped() {
        // Only a missing modelId or an internal model triggers the empty snapshot now; a missing
        // resetTime no longer drops the bucket.
        val body = """
            {
              "buckets": [
                {"remainingFraction": 0.5, "resetTime": "2026-06-01T12:00:00Z"},
                {"modelId": "chat_20706", "remainingFraction": 1.0}
              ]
            }
        """.trimIndent()

        val snapshot = map(body)

        assertTrue(snapshot.windows.isEmpty())
        assertNull(snapshot.planType)
        assertTrue(snapshot.snapshotId.value.startsWith("antigravity_empty_"))
    }
}
