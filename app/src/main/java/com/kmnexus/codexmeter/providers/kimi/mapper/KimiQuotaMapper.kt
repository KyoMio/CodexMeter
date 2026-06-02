package com.kmnexus.codexmeter.providers.kimi.mapper

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
import com.kmnexus.codexmeter.providers.kimi.dto.KimiQuotaResponseDto
import java.time.Instant
import java.time.OffsetDateTime

/**
 * Maps the Kimi GetUsages coding scope into quota windows: the scope `detail` is the weekly quota
 * (primary) and the first `limits[].detail` is the short rate-limit window. Counts are strings and
 * `used` falls back to limit − remaining, matching CodexBar's `KimiUsageSnapshot.toUsageSnapshot`.
 */
object KimiQuotaMapper {
    fun map(
        dto: KimiQuotaResponseDto,
        localAccountId: LocalAccountId,
        providerAccountId: ProviderAccountId?,
        fetchedAt: Instant,
        source: QuotaSnapshotSource,
    ): QuotaSnapshot {
        val usage = dto.codingUsage()
            ?: return emptySnapshot(localAccountId, fetchedAt, source)

        // 5-hour rate window first (primary), then the 7-day window — matching the shared
        // "5h then 7d" ordering used across providers.
        val windows = buildList {
            usage.limits.firstOrNull()?.let { rate ->
                rate.detail?.let { detail ->
                    val w = rate.window
                    add(
                        detailToWindow(
                            detail = detail,
                            windowId = "kimi_rate_window",
                            limitWindowSeconds = w?.let { windowSeconds(it.duration, it.timeUnit) },
                            isPrimary = true,
                            subLabel = w?.let { "${it.duration ?: ""} ${it.timeUnit ?: ""}".trim() },
                        ),
                    )
                }
            }
            usage.detail?.let { weekly ->
                add(
                    detailToWindow(
                        detail = weekly,
                        windowId = "kimi_weekly_window",
                        limitWindowSeconds = WEEKLY_WINDOW_SECONDS,
                        isPrimary = false,
                        subLabel = null,
                    ),
                )
            }
        }

        if (windows.isEmpty()) {
            return emptySnapshot(localAccountId, fetchedAt, source)
        }

        return QuotaSnapshot(
            snapshotId = SnapshotId("kimi_${fetchedAt}"),
            providerId = KIMI_PROVIDER_ID,
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

    private fun detailToWindow(
        detail: KimiQuotaResponseDto.Detail,
        windowId: String,
        limitWindowSeconds: Int?,
        isPrimary: Boolean,
        subLabel: String?,
    ): QuotaWindow {
        val limit = detail.limit?.toIntOrNull() ?: 0
        val remaining = detail.remaining?.toIntOrNull()
        val used = detail.used?.toIntOrNull()
            ?: remaining?.let { (limit - it).coerceAtLeast(0) }
            ?: 0
        val usedPercent = if (limit > 0) {
            (used * 100.0 / limit).coerceIn(0.0, 100.0).toInt()
        } else {
            null
        }
        val availability = when {
            limit == 0 -> QuotaWindowAvailability.Missing
            remaining != null && remaining <= 0 -> QuotaWindowAvailability.Depleted
            used >= limit -> QuotaWindowAvailability.Depleted
            else -> QuotaWindowAvailability.Available
        }

        return QuotaWindow(
            windowId = QuotaWindowId(windowId),
            titleKey = windowId,
            usedPercent = usedPercent,
            resetAt = parseIso(detail.resetTime),
            limitWindowSeconds = limitWindowSeconds,
            isPrimaryCandidate = isPrimary,
            availability = availability,
            // Render as a remaining-percent bar (like Codex/Claude) rather than a raw count.
            displayKind = QuotaWindowDisplayKind.Percent,
            usedCount = used,
            limitCount = limit,
            subLabel = subLabel?.takeIf { it.isNotBlank() },
        )
    }

    private fun windowSeconds(duration: Int?, timeUnit: String?): Int? {
        if (duration == null || duration <= 0) return null
        // Kimi sends enum-style units like "TIME_UNIT_MINUTE", so match by substring.
        val unit = timeUnit?.lowercase() ?: return null
        return when {
            unit.contains("second") -> duration
            unit.contains("minute") || unit.contains("min") -> duration * 60
            unit.contains("hour") -> duration * 3600
            unit.contains("day") -> duration * 86400
            unit.contains("week") -> duration * 7 * 86400
            else -> null
        }
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
        snapshotId = SnapshotId("kimi_empty_${fetchedAt}"),
        providerId = KIMI_PROVIDER_ID,
        localAccountId = localAccountId,
        providerAccountId = null,
        fetchedAt = fetchedAt,
        source = source,
        planType = null,
        windows = emptyList(),
        credits = null,
        responseDigest = null,
    )

    private const val WEEKLY_WINDOW_SECONDS = 7 * 86400
    private val KIMI_PROVIDER_ID = ProviderId("kimi")
}
