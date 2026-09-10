package com.kmnexus.codexmeter.providers.grok.mapper

import com.kmnexus.codexmeter.domain.model.LocalAccountId
import com.kmnexus.codexmeter.domain.model.ProviderAccountId
import com.kmnexus.codexmeter.domain.model.ProviderId
import com.kmnexus.codexmeter.domain.model.QuotaWindowId
import com.kmnexus.codexmeter.domain.model.SnapshotId
import com.kmnexus.codexmeter.domain.quota.Credits
import com.kmnexus.codexmeter.domain.quota.QuotaSnapshot
import com.kmnexus.codexmeter.domain.quota.QuotaSnapshotSource
import com.kmnexus.codexmeter.domain.quota.QuotaWindow
import com.kmnexus.codexmeter.domain.quota.QuotaWindowAvailability
import com.kmnexus.codexmeter.domain.quota.QuotaWindowDisplayKind
import com.kmnexus.codexmeter.providers.grok.dto.GrokBillingResponseDto
import java.time.Instant
import java.time.OffsetDateTime

/**
 * Maps the grok billing projection into a single subscription-usage window plus credits, mirroring
 * Codex's weekly-window conventions: the window id "weekly" reuses the shared weekly label, the used
 * percent comes straight from `creditUsagePercent` (never estimated), and missing/broken fields
 * degrade to unavailable windows instead of throwing. Non-WEEKLY period types still map — the label
 * simply follows the period type.
 */
object GrokMapper {
    fun map(
        dto: GrokBillingResponseDto,
        localAccountId: LocalAccountId,
        providerAccountId: ProviderAccountId?,
        fetchedAt: Instant,
        source: QuotaSnapshotSource,
    ): QuotaSnapshot =
        QuotaSnapshot(
            snapshotId = SnapshotId(
                "grok:${localAccountId.value}:${fetchedAt.toEpochMilli()}:${source.name}",
            ),
            providerId = GROK_PROVIDER_ID,
            localAccountId = localAccountId,
            providerAccountId = providerAccountId,
            fetchedAt = fetchedAt,
            source = source,
            planType = null,
            windows = listOf(toUsageWindow(dto)),
            credits = dto.prepaidBalance?.let { prepaidBalance ->
                Credits(hasCredits = true, unlimited = false, balance = prepaidBalance)
            },
            responseDigest = null,
        )

    private fun toUsageWindow(dto: GrokBillingResponseDto): QuotaWindow {
        val period = dto.currentPeriod
        val end = period?.end
        val usedPercent = dto.creditUsagePercent
            ?.coerceIn(0.0, 100.0)
            ?.toInt()
        val resetAt = parseIso(end)
        // A window is only presentable when the provider actually reported usage and reset time;
        // anything absent reads as missing, a present-but-broken timestamp as decode failure.
        val resetDecodeFailed = !end.isNullOrBlank() && resetAt == null
        val mapped = usedPercent != null && !end.isNullOrBlank() && resetAt != null
        val availability = when {
            resetDecodeFailed -> QuotaWindowAvailability.DecodeFailed
            mapped -> QuotaWindowAvailability.Available
            else -> QuotaWindowAvailability.Missing
        }

        return QuotaWindow(
            windowId = QuotaWindowId(windowIdFor(period?.type)),
            titleKey = windowIdFor(period?.type),
            usedPercent = usedPercent.takeIf { mapped },
            resetAt = resetAt.takeIf { mapped },
            limitWindowSeconds = null,
            isPrimaryCandidate = true,
            availability = availability,
            displayKind = QuotaWindowDisplayKind.Percent,
        )
    }

    /** Weekly is the only observed type so far; a monthly period keeps its own label instead of lying. */
    private fun windowIdFor(periodType: String?): String =
        if (periodType?.contains("MONTH", ignoreCase = true) == true) MONTHLY_WINDOW_ID else WEEKLY_WINDOW_ID

    private fun parseIso(value: String?): Instant? {
        if (value.isNullOrBlank()) return null
        return runCatching { Instant.parse(value) }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull()
    }

    private const val WEEKLY_WINDOW_ID = "weekly"
    private const val MONTHLY_WINDOW_ID = "monthly"
    private val GROK_PROVIDER_ID = ProviderId("grok")
}
