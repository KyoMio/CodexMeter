package com.kmnexus.codexmeter.providers.antigravity.mapper

import com.kmnexus.codexmeter.domain.model.LocalAccountId
import com.kmnexus.codexmeter.domain.model.ProviderAccountId
import com.kmnexus.codexmeter.domain.model.ProviderId
import com.kmnexus.codexmeter.domain.model.QuotaWindowId
import com.kmnexus.codexmeter.domain.model.SnapshotId
import com.kmnexus.codexmeter.domain.quota.QuotaModelBucket
import com.kmnexus.codexmeter.domain.quota.QuotaSnapshot
import com.kmnexus.codexmeter.domain.quota.QuotaSnapshotSource
import com.kmnexus.codexmeter.domain.quota.QuotaWindow
import com.kmnexus.codexmeter.domain.quota.QuotaWindowAvailability
import com.kmnexus.codexmeter.domain.quota.QuotaWindowDisplayKind
import com.kmnexus.codexmeter.providers.antigravity.dto.AntigravityQuotaSummaryDto
import com.kmnexus.codexmeter.providers.antigravity.network.AntigravityModelQuota
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime

/**
 * Maps Antigravity quota into windows. Two entry points:
 *
 * - [mapSummary] is the primary path: it turns `:retrieveUserQuotaSummary` groups into one window
 *   per family (Gemini / Claude-GPT) × lane (5h / weekly / cycle), so a subscription shows its
 *   5-hour AND weekly windows side by side. When the summary reports no known quota the window
 *   list is empty and the caller falls back to [map].
 * - [map] is the per-model fallback (`:fetchAvailableModels` merged with `:retrieveUserQuota`):
 *   one window PER MODEL FAMILY, with each family's models carried as the window's per-model
 *   buckets for the account-page model-detail list. Each family window shows the family's LOWEST
 *   remaining quota (the most-constrained model). Internal/code-completion models (chat_*, tab_*,
 *   *-agent, *-image) are dropped; every other model id — claude, gpt-oss, unknown — joins the
 *   single Claude/GPT family, matching CodexBar's two known families.
 *
 * Both sources return proto3 JSON, which omits default values: a fully-depleted model or bucket
 * reports no `remainingFraction` at all (mapped to 0.0 = 100% used), and a blank `resetTime` maps
 * to a null `resetAt` while the entry is kept. Duplicate rows for one model keep the row with the
 * lowest remaining fraction.
 */
object AntigravityQuotaMapper {
    private enum class Family(val key: String, val windowId: String, val priority: Int) {
        Gemini("gemini", "antigravity_gemini_window", 0),
        ClaudeGpt("claude_gpt", "antigravity_claude_gpt_window", 1),
    }

    private enum class Lane(val key: String, val windowSeconds: Int?, val priority: Int) {
        FiveHour("5h", FIVE_HOUR_SECONDS, 0),
        Weekly("weekly", WEEK_SECONDS, 1),
        Cycle("cycle", null, 2),
    }

    fun map(
        tier: String?,
        models: List<AntigravityModelQuota>,
        localAccountId: LocalAccountId,
        providerAccountId: ProviderAccountId?,
        fetchedAt: Instant,
        source: QuotaSnapshotSource,
    ): QuotaSnapshot {
        val bucketsByFamily = models
            .asSequence()
            .mapNotNull { model ->
                val family = familyOf(model.modelId) ?: return@mapNotNull null
                family to QuotaModelBucket(
                    modelId = model.modelId,
                    displayName = model.label,
                    remainingFraction = (model.remainingFraction ?: 0.0).coerceIn(0.0, 1.0),
                    resetAt = parseIso(model.resetTime),
                )
            }
            // The same model can appear in several rows; keep its most-constrained row (first wins ties).
            .groupBy({ it.second.modelId })
            .map { (_, rows) ->
                rows.reduce { keep, row ->
                    if (row.second.remainingFraction < keep.second.remainingFraction) row else keep
                }
            }
            .groupBy({ it.first }, { it.second })

        if (bucketsByFamily.isEmpty()) {
            return emptySnapshot(localAccountId, fetchedAt, source)
        }

        // Most-constrained family (highest used%) first so the account summary surfaces it; the rest
        // remain available in the expandable per-family model list.
        val windows = bucketsByFamily.entries
            .map { (family, buckets) -> family to buildFamilyWindow(family, buckets) }
            .sortedWith(
                compareByDescending<Pair<Family, QuotaWindow>> { it.second.usedPercent ?: 0 }
                    .thenBy { it.first.priority },
            )
            .mapIndexed { index, (_, window) -> window.copy(isPrimaryCandidate = index == 0) }

        return QuotaSnapshot(
            snapshotId = SnapshotId("antigravity_${fetchedAt}"),
            providerId = ANTIGRAVITY_PROVIDER_ID,
            localAccountId = localAccountId,
            providerAccountId = providerAccountId,
            fetchedAt = fetchedAt,
            source = source,
            planType = tier,
            windows = windows,
            credits = null,
            responseDigest = null,
        )
    }

    fun mapSummary(
        dto: AntigravityQuotaSummaryDto,
        tier: String?,
        localAccountId: LocalAccountId,
        providerAccountId: ProviderAccountId?,
        fetchedAt: Instant,
        source: QuotaSnapshotSource,
    ): QuotaSnapshot {
        if (!dto.hasKnownQuotaSummary()) {
            return emptySnapshot(localAccountId, fetchedAt, source)
        }

        data class BucketWindow(val family: Family, val lane: Lane, val window: QuotaWindow)

        val windows = dto.payload().groups
            .flatMap { group ->
                group.buckets.mapNotNull { bucket ->
                    if (bucket.disabled == true) return@mapNotNull null
                    val stableId = bucket.bucketId ?: bucket.id
                    val title = bucket.displayName ?: bucket.name
                    val remaining = (bucket.remainingFractionOrNull() ?: 0.0).coerceIn(0.0, 1.0)
                    val family = summaryFamilyOf(stableId, title, group)
                    val resetAt = parseIso(bucket.resetTime)
                    val lane = laneOf(bucket.window, stableId, title, fetchedAt, resetAt)
                    BucketWindow(
                        family = family,
                        lane = lane,
                        window = QuotaWindow(
                            windowId = QuotaWindowId("antigravity_${family.key}_${lane.key}_window"),
                            titleKey = "antigravity_${family.key}_${lane.key}_window",
                            usedPercent = ((1.0 - remaining) * 100).coerceIn(0.0, 100.0).toInt(),
                            resetAt = resetAt,
                            limitWindowSeconds = lane.windowSeconds,
                            isPrimaryCandidate = false,
                            availability = if (remaining <= 0.0) {
                                QuotaWindowAvailability.Depleted
                            } else {
                                QuotaWindowAvailability.Available
                            },
                        ),
                    )
                }
            }
            // The same window can receive several buckets (e.g. per-tier rows); keep the fullest.
            .groupBy { it.window.windowId.value }
            .map { (_, same) ->
                same.reduce { keep, candidate ->
                    if ((candidate.window.usedPercent ?: 0) > (keep.window.usedPercent ?: 0)) candidate else keep
                }
            }
            // Most-constrained window first; ties break by family, then by lane recency.
            .sortedWith(
                compareByDescending<BucketWindow> { it.window.usedPercent ?: 0 }
                    .thenBy { it.family.priority }
                    .thenBy { it.lane.priority },
            )
            .mapIndexed { index, bucketWindow -> bucketWindow.window.copy(isPrimaryCandidate = index == 0) }

        return QuotaSnapshot(
            snapshotId = SnapshotId("antigravity_${fetchedAt}"),
            providerId = ANTIGRAVITY_PROVIDER_ID,
            localAccountId = localAccountId,
            providerAccountId = providerAccountId,
            fetchedAt = fetchedAt,
            source = source,
            planType = tier,
            windows = windows,
            credits = null,
            responseDigest = null,
        )
    }

    /**
     * Lane classification: the bucket's explicit `window` field wins, then the stable bucket id,
     * then the display title. Candidates are normalized (trimmed, lowercased, underscores to
     * dashes, trailing " limit" stripped) before matching the session aliases or "weekly".
     *
     * Buckets whose text matches nothing get a data-driven fallback before the cycle lane: a 5h
     * window's reset always sits within the next 5 hours and a weekly's within 7 days, so a reset
     * horizon within 6h / 7.5 days of the fetch (bounds inclusive) identifies the lane
     * unambiguously. A reset already in the past, further out, or absent cannot be placed and
     * stays cycle.
     */
    private fun laneOf(
        window: String?,
        bucketId: String?,
        displayName: String?,
        fetchedAt: Instant,
        resetAt: Instant?,
    ): Lane {
        for (candidate in listOf(window, bucketId, displayName)) {
            val normalized = candidate?.trim()?.lowercase()
                ?.replace('_', '-')?.removeSuffix(" limit")
                ?.takeIf { it.isNotBlank() }
                ?: continue
            if (normalized in SESSION_LANE_ALIASES) return Lane.FiveHour
            if (normalized.contains("weekly")) return Lane.Weekly
        }
        if (resetAt != null) {
            val untilReset = Duration.between(fetchedAt, resetAt)
            if (!untilReset.isNegative && untilReset <= SIX_HOURS) return Lane.FiveHour
            if (!untilReset.isNegative && untilReset <= SEVEN_AND_A_HALF_DAYS) return Lane.Weekly
        }
        return Lane.Cycle
    }

    /**
     * Bucket text first, then the group name. Only "gemini" selects the Gemini family; everything
     * else — claude, gpt, third-party, unknown text — belongs to the single Claude/GPT family.
     */
    private fun summaryFamilyOf(
        bucketId: String?,
        bucketTitle: String?,
        group: AntigravityQuotaSummaryDto.QuotaGroup,
    ): Family {
        val bucketText = "${bucketId.orEmpty()} ${bucketTitle.orEmpty()}"
        if (bucketText.lowercase().contains("gemini")) return Family.Gemini
        val groupText = (group.displayName ?: group.name ?: "").lowercase()
        return if (groupText.contains("gemini")) Family.Gemini else Family.ClaudeGpt
    }

    private fun buildFamilyWindow(family: Family, buckets: List<QuotaModelBucket>): QuotaWindow {
        val minRemaining = buckets.minOf { it.remainingFraction }
        val usedPercent = ((1.0 - minRemaining) * 100).coerceIn(0.0, 100.0).toInt()
        val availability = if (minRemaining <= 0.0) {
            QuotaWindowAvailability.Depleted
        } else {
            QuotaWindowAvailability.Available
        }
        return QuotaWindow(
            windowId = QuotaWindowId(family.windowId),
            titleKey = family.windowId,
            usedPercent = usedPercent,
            resetAt = buckets.mapNotNull { it.resetAt }.minOrNull(),
            limitWindowSeconds = null,
            isPrimaryCandidate = false,
            availability = availability,
            displayKind = QuotaWindowDisplayKind.MultiModelFraction,
            usesModelBucketSum = true,
            modelBuckets = buckets.sortedBy { it.displayName },
            subLabel = "${buckets.size} models",
        )
    }

    /**
     * Classifies a model id into a display family; internal/code-completion models return null.
     * Everything non-gemini — claude, gpt-oss, unknown ids — joins the Claude/GPT family.
     */
    private fun familyOf(modelId: String): Family? {
        val id = modelId.lowercase()
        if (id.contains("agent") || id.contains("image")) return null
        if (id.startsWith("chat_") || id.startsWith("tab_")) return null
        return if (id.contains("gemini")) Family.Gemini else Family.ClaudeGpt
    }

    private fun parseIso(value: String?): Instant? {
        if (value.isNullOrBlank()) return null
        return runCatching { Instant.parse(value) }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull()
    }

    private fun emptySnapshot(
        localAccountId: LocalAccountId,
        fetchedAt: Instant,
        source: QuotaSnapshotSource,
    ): QuotaSnapshot = QuotaSnapshot(
        snapshotId = SnapshotId("antigravity_empty_${fetchedAt}"),
        providerId = ANTIGRAVITY_PROVIDER_ID,
        localAccountId = localAccountId,
        providerAccountId = null,
        fetchedAt = fetchedAt,
        source = source,
        planType = null,
        windows = emptyList(),
        credits = null,
        responseDigest = null,
    )

    private val ANTIGRAVITY_PROVIDER_ID = ProviderId("antigravity")

    private const val FIVE_HOUR_SECONDS = 18_000
    private const val WEEK_SECONDS = 604_800

    // Reset-horizon bounds for the text-classification fallback, with slack beyond the real 5h /
    // 7d windows so stale fetches still classify.
    private val SIX_HOURS = Duration.ofHours(6)
    private val SEVEN_AND_A_HALF_DAYS = Duration.ofDays(7).plusHours(12)

    private val SESSION_LANE_ALIASES = setOf("session", "5h", "5-hour", "five hour", "five-hour")
}
