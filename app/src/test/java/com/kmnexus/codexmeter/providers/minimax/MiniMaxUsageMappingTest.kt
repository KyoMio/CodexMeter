package com.kmnexus.codexmeter.providers.minimax

import com.kmnexus.codexmeter.domain.model.LocalAccountId
import com.kmnexus.codexmeter.domain.quota.QuotaSnapshotSource
import com.kmnexus.codexmeter.providers.minimax.dto.MiniMaxUsageResponseDto
import com.kmnexus.codexmeter.providers.minimax.mapper.MiniMaxUsageMapper
import java.time.Instant
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class MiniMaxUsageMappingTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val localAccountId = LocalAccountId("minimax-1")
    private val fetchedAt = Instant.parse("2026-05-31T00:00:00Z")

    /** The coding_plan/remains payload nests model_remains under `data`; usage_count is REMAINING. */
    @Test
    fun decodesNestedCodingPlanRemainsAndMapsUsedPercent() {
        val body = """
            {
              "base_resp": {"status_code": 0, "status_msg": "success"},
              "data": {
                "current_subscribe_title": "MiniMax Coding Pro",
                "model_remains": [
                  {
                    "model_name": "MiniMax-M2",
                    "current_interval_total_count": 100,
                    "current_interval_usage_count": 40,
                    "end_time": 1772000000,
                    "current_weekly_total_count": 1000,
                    "current_weekly_usage_count": 600
                  }
                ]
              }
            }
        """.trimIndent()

        val dto = json.decodeFromString<MiniMaxUsageResponseDto>(body)
        assertEquals(0, dto.statusCode)
        assertEquals(1, dto.effectiveModelRemains.size)

        val snapshot = MiniMaxUsageMapper.map(
            dto = dto,
            localAccountId = localAccountId,
            providerAccountId = null,
            fetchedAt = fetchedAt,
            source = QuotaSnapshotSource.ApiKeyImport,
        )

        assertEquals("MiniMax Coding Pro", snapshot.planType)
        val interval = snapshot.windows.first { it.windowId.value == "minimax_interval" }
        // total 100, remaining 40 -> used 60%
        assertEquals(60, interval.usedPercent)
        assertNotNull(interval.resetAt)
        val weekly = snapshot.windows.first { it.windowId.value == "minimax_weekly" }
        // total 1000, remaining 600 -> used 40%
        assertEquals(40, weekly.usedPercent)
    }

    /**
     * Non-text-generation models (speech/video/music/…) must be surfaced as model buckets on the
     * primary window so the account-page model-detail list can render them. They were previously
     * computed and then dropped.
     */
    @Test
    fun attachesNonTextGenModelsAsBucketsOnPrimaryWindow() {
        val body = """
            {
              "model_remains": [
                {
                  "model_name": "MiniMax-M2",
                  "current_interval_total_count": 100,
                  "current_interval_usage_count": 40,
                  "end_time": 1772000000
                },
                {
                  "model_name": "speech-hd",
                  "current_interval_total_count": 100,
                  "current_interval_usage_count": 95,
                  "end_time": 1772000000
                }
              ]
            }
        """.trimIndent()

        val dto = json.decodeFromString<MiniMaxUsageResponseDto>(body)
        val snapshot = MiniMaxUsageMapper.map(
            dto = dto,
            localAccountId = localAccountId,
            providerAccountId = null,
            fetchedAt = fetchedAt,
            source = QuotaSnapshotSource.ApiKeyImport,
        )

        val interval = snapshot.windows.first { it.windowId.value == "minimax_interval" }
        val bucket = interval.modelBuckets.single()
        assertEquals("speech-hd", bucket.modelId)
        // remaining 95 / 100 -> 0.95 remaining fraction
        assertEquals(0.95, bucket.remainingFraction, 0.001)
    }

    /**
     * Percent-based plans report `*_total_count == 0` and carry the remaining quota as
     * `*_remaining_percent`. The mapper must use that instead of the zero counts (the bug behind
     * "MiniMax shows no quota").
     */
    @Test
    fun usesRemainingPercentWhenCountsAreZero() {
        val body = """
            {
              "model_remains": [
                {
                  "model_name": "general",
                  "current_interval_total_count": 0,
                  "current_interval_usage_count": 0,
                  "current_interval_remaining_percent": 70,
                  "end_time": 1780315200000,
                  "current_weekly_total_count": 0,
                  "current_weekly_usage_count": 0,
                  "current_weekly_remaining_percent": 90,
                  "weekly_end_time": 1780848000000
                },
                {
                  "model_name": "video",
                  "current_interval_total_count": 0,
                  "current_interval_usage_count": 0,
                  "current_interval_remaining_percent": 100,
                  "end_time": 1780329600000
                }
              ]
            }
        """.trimIndent()

        val dto = json.decodeFromString<MiniMaxUsageResponseDto>(body)
        val snapshot = MiniMaxUsageMapper.map(
            dto = dto,
            localAccountId = localAccountId,
            providerAccountId = null,
            fetchedAt = fetchedAt,
            source = QuotaSnapshotSource.ApiKeyImport,
        )

        // "general" is text-gen -> interval (5h) + weekly windows from remaining_percent.
        val interval = snapshot.windows.first { it.windowId.value == "minimax_interval" }
        assertEquals(30, interval.usedPercent) // 100 - 70
        val weekly = snapshot.windows.first { it.windowId.value == "minimax_weekly" }
        assertEquals(10, weekly.usedPercent) // 100 - 90
        // "video" -> model bucket at full remaining.
        val videoBucket = interval.modelBuckets.single { it.modelId == "video" }
        assertEquals(1.0, videoBucket.remainingFraction, 0.001)
    }

    /**
     * All non-text-gen models (speech / video / music / image / coding tools) must be surfaced as
     * buckets for the account-page model-detail list — not just "video". Mirrors the spike capture.
     */
    @Test
    fun surfacesEveryNonTextGenModelAsBucket() {
        val body = """
            {
              "model_remains": [
                {"model_name": "MiniMax-M*", "current_interval_total_count": 600, "current_interval_usage_count": 600, "end_time": 1772000000},
                {"model_name": "speech-hd", "current_interval_total_count": 100, "current_interval_usage_count": 100, "end_time": 1772000000},
                {"model_name": "MiniMax-Hailuo-2.3-Fast-6s-768p", "current_interval_total_count": 100, "current_interval_usage_count": 80, "end_time": 1772000000},
                {"model_name": "music-2.6", "current_interval_total_count": 100, "current_interval_usage_count": 100, "end_time": 1772000000},
                {"model_name": "lyrics_generation", "current_interval_total_count": 100, "current_interval_usage_count": 100, "end_time": 1772000000},
                {"model_name": "image-01", "current_interval_total_count": 100, "current_interval_usage_count": 50, "end_time": 1772000000},
                {"model_name": "coding-plan-search", "current_interval_total_count": 60, "current_interval_usage_count": 60, "end_time": 1772000000}
              ]
            }
        """.trimIndent()

        val dto = json.decodeFromString<MiniMaxUsageResponseDto>(body)
        val snapshot = MiniMaxUsageMapper.map(
            dto = dto,
            localAccountId = localAccountId,
            providerAccountId = null,
            fetchedAt = fetchedAt,
            source = QuotaSnapshotSource.ApiKeyImport,
        )

        val interval = snapshot.windows.first { it.windowId.value == "minimax_interval" }
        val ids = interval.modelBuckets.map { it.modelId }.toSet()
        // "MiniMax-M*" is text-gen (in the main cards); every other model is a detail bucket.
        assertEquals(
            setOf(
                "speech-hd",
                "MiniMax-Hailuo-2.3-Fast-6s-768p",
                "music-2.6",
                "lyrics_generation",
                "image-01",
                "coding-plan-search",
            ),
            ids,
        )
    }

    /**
     * Capabilities the account isn't entitled to (status 3) are dropped — e.g. video on a text-only
     * plan, where text/image/speech share the single "general" quota. So there's nothing left for a
     * model-detail list.
     */
    @Test
    fun excludesNoPermissionStatusModels() {
        val body = """
            {
              "model_remains": [
                {"model_name": "general", "current_interval_total_count": 0, "current_interval_usage_count": 0, "current_interval_remaining_percent": 100, "current_interval_status": 1, "end_time": 1772000000},
                {"model_name": "video", "current_interval_total_count": 0, "current_interval_usage_count": 0, "current_interval_remaining_percent": 100, "current_interval_status": 3, "end_time": 1772000000}
              ]
            }
        """.trimIndent()

        val dto = json.decodeFromString<MiniMaxUsageResponseDto>(body)
        val snapshot = MiniMaxUsageMapper.map(
            dto = dto,
            localAccountId = localAccountId,
            providerAccountId = null,
            fetchedAt = fetchedAt,
            source = QuotaSnapshotSource.ApiKeyImport,
        )

        val interval = snapshot.windows.first { it.windowId.value == "minimax_interval" }
        // "general" (status 1) drives the main window; "video" (status 3) is excluded entirely.
        assertEquals(emptyList<String>(), interval.modelBuckets.map { it.modelId })
    }
}
