package com.kmnexus.codexmeter.providers.minimax.mapper

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
import com.kmnexus.codexmeter.providers.minimax.dto.MiniMaxUsageResponseDto
import com.kmnexus.codexmeter.providers.minimax.dto.MiniMaxUsageResponseDto.ModelRemain
import java.time.Instant

/**
 * Maps MiniMax `coding_plan/remains` into quota windows. `current_*_total_count` is the limit and
 * `current_*_usage_count` is the REMAINING amount (CodexBar semantics), so used = total - remaining.
 * Text-generation models are aggregated into an interval (primary) and weekly window; other models
 * are surfaced as model buckets.
 */
object MiniMaxUsageMapper {
    fun map(
        dto: MiniMaxUsageResponseDto,
        localAccountId: LocalAccountId,
        providerAccountId: ProviderAccountId?,
        fetchedAt: Instant,
        source: QuotaSnapshotSource,
    ): QuotaSnapshot {
        // Drop capabilities the account isn't entitled to (status 3 = no permission), e.g. video
        // generation on a text-only plan — otherwise it would surface as a phantom "100%" quota.
        val modelRemains = dto.effectiveModelRemains.filter { it.current_interval_status != NO_PERMISSION_STATUS }
        if (modelRemains.isEmpty()) {
            return emptySnapshot(localAccountId, fetchedAt, source)
        }

        val textGenModels = modelRemains.filter { isTextGeneration(it.model_name) }
        val nonTextGenModels = modelRemains.filter { !isTextGeneration(it.model_name) }

        val modelBuckets = buildModelBuckets(nonTextGenModels)
        // Attach the non-text-gen buckets to the primary window only (the account-page model-detail
        // list flat-maps buckets across all windows, so attaching to both would double-count them).
        val primaryWindow = buildPrimaryWindow(textGenModels)?.copy(modelBuckets = modelBuckets)
        val secondaryWindow = buildSecondaryWindow(textGenModels)

        val windows = buildList {
            if (primaryWindow != null) add(primaryWindow)
            if (secondaryWindow != null) add(secondaryWindow)
        }

        if (windows.isEmpty() && modelBuckets.isEmpty()) {
            return emptySnapshot(localAccountId, fetchedAt, source)
        }

        return QuotaSnapshot(
            snapshotId = SnapshotId("mm_${fetchedAt}"),
            providerId = MINIMAX_PROVIDER_ID,
            localAccountId = localAccountId,
            providerAccountId = providerAccountId,
            fetchedAt = fetchedAt,
            source = source,
            planType = dto.planName,
            windows = windows,
            credits = null,
            responseDigest = null,
        )
    }

    private fun buildPrimaryWindow(textGenModels: List<ModelRemain>): QuotaWindow? {
        if (textGenModels.isEmpty()) return null

        val totalRemaining = textGenModels.sumOf { it.current_interval_usage_count ?: 0 }
        val totalLimit = textGenModels.sumOf { it.current_interval_total_count ?: 0 }
        val usedPercent = usedPercentFor(
            totalRemaining = totalRemaining,
            totalLimit = totalLimit,
            remainingPercents = textGenModels.mapNotNull { it.current_interval_remaining_percent },
        )
        val availability = availabilityFor(usedPercent, totalRemaining, totalLimit)

        return QuotaWindow(
            windowId = QuotaWindowId("minimax_interval"),
            titleKey = "minimax_interval_window",
            usedPercent = usedPercent,
            resetAt = textGenModels.firstNotNullOfOrNull { epochToInstant(it.end_time) },
            limitWindowSeconds = INTERVAL_WINDOW_SECONDS,
            isPrimaryCandidate = true,
            availability = availability,
            displayKind = QuotaWindowDisplayKind.MultiModelFraction,
            subLabel = "Interval · ${textGenModels.size} models",
        )
    }

    private fun buildSecondaryWindow(textGenModels: List<ModelRemain>): QuotaWindow? {
        if (textGenModels.isEmpty()) return null

        val totalRemaining = textGenModels.sumOf { it.current_weekly_usage_count ?: 0 }
        val totalLimit = textGenModels.sumOf { it.current_weekly_total_count ?: 0 }
        val usedPercent = usedPercentFor(
            totalRemaining = totalRemaining,
            totalLimit = totalLimit,
            remainingPercents = textGenModels.mapNotNull { it.current_weekly_remaining_percent },
        ) ?: return null
        val availability = availabilityFor(usedPercent, totalRemaining, totalLimit)

        return QuotaWindow(
            windowId = QuotaWindowId("minimax_weekly"),
            titleKey = "minimax_weekly_window",
            usedPercent = usedPercent,
            resetAt = textGenModels.firstNotNullOfOrNull { epochToInstant(it.weekly_end_time) },
            limitWindowSeconds = WEEKLY_WINDOW_SECONDS,
            isPrimaryCandidate = false,
            availability = availability,
            displayKind = QuotaWindowDisplayKind.MultiModelFraction,
            subLabel = null,
        )
    }

    /**
     * Used-percent for a window: from `used / limit` counts when a real limit exists, otherwise from
     * the server's `remaining_percent` (percent-based plans report `total_count == 0`). Null only when
     * neither signal is available.
     */
    private fun usedPercentFor(totalRemaining: Int, totalLimit: Int, remainingPercents: List<Int>): Int? =
        when {
            totalLimit > 0 -> (100 - (totalRemaining * 100.0 / totalLimit).coerceIn(0.0, 100.0)).toInt()
            remainingPercents.isNotEmpty() ->
                (100 - remainingPercents.average().coerceIn(0.0, 100.0)).toInt()
            else -> null
        }

    private fun availabilityFor(usedPercent: Int?, totalRemaining: Int, totalLimit: Int): QuotaWindowAvailability =
        when {
            usedPercent == null -> QuotaWindowAvailability.Missing
            totalLimit > 0 && totalRemaining <= 0 -> QuotaWindowAvailability.Depleted
            usedPercent >= 100 -> QuotaWindowAvailability.Depleted
            else -> QuotaWindowAvailability.Available
        }

    private fun buildModelBuckets(nonTextGenModels: List<ModelRemain>): List<QuotaModelBucket> =
        nonTextGenModels.mapNotNull { model ->
            val name = model.model_name ?: return@mapNotNull null
            val remaining = model.current_interval_usage_count ?: 0
            val limit = model.current_interval_total_count ?: 0
            val fraction = when {
                limit > 0 -> (remaining.toDouble() / limit).coerceIn(0.0, 1.0)
                model.current_interval_remaining_percent != null ->
                    (model.current_interval_remaining_percent / 100.0).coerceIn(0.0, 1.0)
                else -> 0.0
            }
            QuotaModelBucket(
                modelId = name,
                displayName = name,
                remainingFraction = fraction,
                resetAt = epochToInstant(model.end_time),
            )
        }

    private fun isTextGeneration(modelName: String?): Boolean {
        if (modelName == null) return false
        val lower = modelName.lowercase()
        // "general" is the coding-plan text model some accounts report instead of "MiniMax-M*"/"M2.*".
        return lower.contains("minimax-m") || lower.startsWith("m2.") || lower == "general"
    }

    /** MiniMax epoch fields are sometimes seconds, sometimes milliseconds. */
    private fun epochToInstant(value: Long?): Instant? {
        if (value == null) return null
        return when {
            value > 1_000_000_000_000L -> Instant.ofEpochMilli(value)
            value > 1_000_000_000L -> Instant.ofEpochSecond(value)
            else -> null
        }
    }

    private fun emptySnapshot(
        localAccountId: LocalAccountId,
        fetchedAt: Instant,
        source: QuotaSnapshotSource,
    ): QuotaSnapshot = QuotaSnapshot(
        snapshotId = SnapshotId("mm_empty_${fetchedAt}"),
        providerId = MINIMAX_PROVIDER_ID,
        localAccountId = localAccountId,
        providerAccountId = null,
        fetchedAt = fetchedAt,
        source = source,
        planType = null,
        windows = emptyList(),
        credits = null,
        responseDigest = null,
    )

    private const val NO_PERMISSION_STATUS = 3
    private const val INTERVAL_WINDOW_SECONDS = 5 * 3600 // 5-hour text-gen interval
    private const val WEEKLY_WINDOW_SECONDS = 7 * 86400 // 1 week
    private val MINIMAX_PROVIDER_ID = ProviderId("minimax")
}
