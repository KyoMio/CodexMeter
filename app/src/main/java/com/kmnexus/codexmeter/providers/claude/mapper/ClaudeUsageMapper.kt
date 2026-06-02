package com.kmnexus.codexmeter.providers.claude.mapper

import com.kmnexus.codexmeter.domain.model.LocalAccountId
import com.kmnexus.codexmeter.domain.model.ProviderAccountId
import com.kmnexus.codexmeter.domain.model.ProviderId
import com.kmnexus.codexmeter.domain.model.QuotaWindowId
import com.kmnexus.codexmeter.domain.model.SnapshotId
import com.kmnexus.codexmeter.domain.quota.QuotaSnapshot
import com.kmnexus.codexmeter.domain.quota.QuotaSnapshotSource
import com.kmnexus.codexmeter.domain.quota.QuotaWindow
import com.kmnexus.codexmeter.domain.quota.QuotaWindowAvailability
import com.kmnexus.codexmeter.domain.quota.QuotaWindowDisplayKind
import com.kmnexus.codexmeter.providers.claude.dto.ClaudeUsageResponseDto
import java.time.Instant
import java.time.OffsetDateTime

/**
 * Maps Claude OAuth usage windows into quota windows. `utilization` is a percentage; `resets_at` is
 * ISO-8601. five_hour is primary; seven_day (and Opus/Sonnet variants) are secondary.
 */
object ClaudeUsageMapper {
    fun map(
        dto: ClaudeUsageResponseDto,
        localAccountId: LocalAccountId,
        providerAccountId: ProviderAccountId?,
        fetchedAt: Instant,
        source: QuotaSnapshotSource,
    ): QuotaSnapshot {
        val windows = buildList {
            dto.five_hour?.let {
                add(toWindow(it, "claude_5h_window", FIVE_HOUR_SECONDS, "5 hours", isPrimary = true))
            }
            dto.seven_day?.let {
                add(toWindow(it, "claude_7d_window", SEVEN_DAY_SECONDS, "7 days", isPrimary = false))
            }
            // Pay-as-you-go spend, surfaced right after the 7-day window so it lands in the account
            // summary; only when the user has enabled it and a monthly cap exists.
            extraUsageWindow(dto.extra_usage)?.let { add(it) }
            dto.seven_day_opus?.let {
                add(toWindow(it, "claude_7d_opus_window", SEVEN_DAY_SECONDS, "7 days · Opus", isPrimary = false))
            }
            dto.seven_day_sonnet?.let {
                add(toWindow(it, "claude_7d_sonnet_window", SEVEN_DAY_SECONDS, "7 days · Sonnet", isPrimary = false))
            }
        }

        if (windows.isEmpty()) {
            return emptySnapshot(localAccountId, fetchedAt, source)
        }

        return QuotaSnapshot(
            snapshotId = SnapshotId("claude_${fetchedAt}"),
            providerId = CLAUDE_PROVIDER_ID,
            localAccountId = localAccountId,
            providerAccountId = providerAccountId,
            fetchedAt = fetchedAt,
            source = source,
            planType = null,
            windows = windows,
            credits = null,
            responseDigest = null,
        )
    }

    private fun toWindow(
        window: ClaudeUsageResponseDto.Window,
        windowId: String,
        limitWindowSeconds: Int,
        subLabel: String,
        isPrimary: Boolean,
    ): QuotaWindow {
        val usedPercent = window.utilization?.coerceIn(0.0, 100.0)?.toInt()
        val availability = when {
            usedPercent == null -> QuotaWindowAvailability.Missing
            usedPercent >= 100 -> QuotaWindowAvailability.Depleted
            else -> QuotaWindowAvailability.Available
        }
        return QuotaWindow(
            windowId = QuotaWindowId(windowId),
            titleKey = windowId,
            usedPercent = usedPercent,
            resetAt = parseIso(window.resets_at),
            limitWindowSeconds = limitWindowSeconds,
            isPrimaryCandidate = isPrimary,
            availability = availability,
            displayKind = QuotaWindowDisplayKind.Percent,
            subLabel = subLabel,
        )
    }

    private fun extraUsageWindow(extra: ClaudeUsageResponseDto.ExtraUsage?): QuotaWindow? {
        if (extra == null || extra.is_enabled != true) return null
        val limit = extra.monthly_limit ?: return null
        val used = extra.used_credits ?: 0.0
        val usedPercent = (extra.utilization ?: if (limit > 0) used / limit * 100 else 0.0)
            .coerceIn(0.0, 100.0).toInt()
        return QuotaWindow(
            windowId = QuotaWindowId("claude_extra_usage"),
            titleKey = "claude_extra_usage",
            usedPercent = usedPercent,
            resetAt = null,
            limitWindowSeconds = null,
            isPrimaryCandidate = false,
            availability = if (usedPercent >= 100) {
                QuotaWindowAvailability.Depleted
            } else {
                QuotaWindowAvailability.Available
            },
            displayKind = QuotaWindowDisplayKind.Percent,
            // Spend figure (e.g. "$25.00 / $100.00"); the account page renders this instead of percent.
            subLabel = formatSpend(used, limit, extra.currency),
        )
    }

    /** Cents -> "$25.00 / $100.00" style spend string. */
    private fun formatSpend(usedCents: Double, limitCents: Double, currency: String?): String {
        val symbol = when (currency?.uppercase()) {
            "USD" -> "$"
            "CNY", "RMB" -> "¥"
            null, "" -> "$"
            else -> "$currency "
        }
        fun money(cents: Double) = symbol + String.format("%.2f", cents / 100.0)
        return "${money(usedCents)} / ${money(limitCents)}"
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
        snapshotId = SnapshotId("claude_empty_${fetchedAt}"),
        providerId = CLAUDE_PROVIDER_ID,
        localAccountId = localAccountId,
        providerAccountId = null,
        fetchedAt = fetchedAt,
        source = source,
        planType = null,
        windows = emptyList(),
        credits = null,
        responseDigest = null,
    )

    private const val FIVE_HOUR_SECONDS = 5 * 3600
    private const val SEVEN_DAY_SECONDS = 7 * 86400
    private val CLAUDE_PROVIDER_ID = ProviderId("claude")
}
