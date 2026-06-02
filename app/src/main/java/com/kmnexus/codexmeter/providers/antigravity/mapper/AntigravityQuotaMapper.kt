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
import com.kmnexus.codexmeter.providers.antigravity.dto.AntigravityQuotaResponseDto
import java.time.Instant
import java.time.OffsetDateTime

/**
 * Maps Antigravity quota buckets (per-model `remainingFraction`, 0–1) into one window PER MODEL
 * FAMILY (Claude / Gemini Pro / Gemini Flash / GPT-OSS). Each family window shows the family's
 * LOWEST remaining quota (the most-constrained model) and carries its models as buckets for the
 * account-page model-detail list. Internal/code-completion models (chat_*, tab_*, *-agent, *-image)
 * and buckets without a `resetTime` are excluded.
 */
object AntigravityQuotaMapper {
    private enum class Family(val windowId: String, val priority: Int) {
        Claude("antigravity_claude_window", 0),
        GeminiPro("antigravity_gemini_pro_window", 1),
        GeminiFlash("antigravity_gemini_flash_window", 2),
        GptOss("antigravity_gpt_oss_window", 3),
    }

    fun map(
        dto: AntigravityQuotaResponseDto,
        localAccountId: LocalAccountId,
        providerAccountId: ProviderAccountId?,
        fetchedAt: Instant,
        source: QuotaSnapshotSource,
    ): QuotaSnapshot {
        val bucketsByFamily = dto.buckets
            .asSequence()
            .filter { it.modelId != null && it.remainingFraction != null && !it.resetTime.isNullOrBlank() }
            .mapNotNull { bucket ->
                val modelId = bucket.modelId ?: return@mapNotNull null
                val family = familyOf(modelId) ?: return@mapNotNull null
                family to QuotaModelBucket(
                    modelId = modelId,
                    displayName = displayNameFor(modelId),
                    remainingFraction = (bucket.remainingFraction ?: 0.0).coerceIn(0.0, 1.0),
                    resetAt = parseIso(bucket.resetTime),
                )
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
            planType = dto.tier,
            windows = windows,
            credits = null,
            responseDigest = null,
        )
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

    /** Classifies a model id into a display family; internal/code-completion models return null. */
    private fun familyOf(modelId: String): Family? {
        val id = modelId.lowercase()
        return when {
            id.contains("agent") || id.contains("image") -> null
            id.startsWith("chat_") || id.startsWith("tab_") -> null
            id.startsWith("claude") -> Family.Claude
            id.startsWith("gpt-oss") -> Family.GptOss
            id.contains("gemini") && id.contains("pro") -> Family.GeminiPro
            id.contains("gemini") && id.contains("flash") -> Family.GeminiFlash
            else -> null
        }
    }

    /** kebab/snake model id -> readable display name, e.g. "claude-opus-4-6" -> "Claude Opus 4 6". */
    private fun displayNameFor(modelId: String): String =
        modelId.split('-', '_')
            .filter { it.isNotBlank() }
            .joinToString(" ") { part -> part.replaceFirstChar { it.uppercaseChar() } }

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
}
