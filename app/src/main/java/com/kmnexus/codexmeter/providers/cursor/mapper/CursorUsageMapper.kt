package com.kmnexus.codexmeter.providers.cursor.mapper

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
import com.kmnexus.codexmeter.providers.cursor.dto.CursorUsageBucket
import com.kmnexus.codexmeter.providers.cursor.dto.CursorUsageResponseDto
import java.time.Instant

/**
 * Maps Cursor's usage-summary into percent windows. `plan` (primary) and `onDemand` report spend
 * caps in cents; used% = used / limit, matching CodexBar's dashboard projection.
 */
object CursorUsageMapper {
    fun map(
        dto: CursorUsageResponseDto,
        localAccountId: LocalAccountId,
        providerAccountId: ProviderAccountId?,
        fetchedAt: Instant,
        source: QuotaSnapshotSource,
    ): QuotaSnapshot {
        val usage = dto.individualUsage
            ?: return emptySnapshot(localAccountId, fetchedAt, source)

        val windows = buildList {
            usage.plan?.let { plan ->
                buildWindow(plan, "cursor_plan", "cursor_plan_window", "Plan", isPrimary = true)
                    ?.let { add(it) }
            }
            usage.onDemand?.let { od ->
                buildWindow(od, "cursor_on_demand", "cursor_on_demand_window", "On Demand", isPrimary = false)
                    ?.let { add(it) }
            }
        }

        if (windows.isEmpty()) {
            return emptySnapshot(localAccountId, fetchedAt, source)
        }

        return QuotaSnapshot(
            snapshotId = SnapshotId("cursor_${fetchedAt}"),
            providerId = CURSOR_PROVIDER_ID,
            localAccountId = localAccountId,
            providerAccountId = providerAccountId,
            fetchedAt = fetchedAt,
            source = source,
            planType = dto.membershipType,
            windows = windows,
            credits = null,
            responseDigest = null,
        )
    }

    private fun buildWindow(
        bucket: CursorUsageBucket,
        windowId: String,
        titleKey: String,
        subLabel: String,
        isPrimary: Boolean,
    ): QuotaWindow? {
        val used = bucket.used
        val limit = bucket.limit
        val percentUsed = bucket.totalPercentUsed
        if (percentUsed == null && used == null && limit == null) return null

        // Prefer the server-provided percent (works for free-tier `limit == 0`); fall back to the
        // legacy cent-based `used / limit` projection only when no percent is reported.
        val usedPercent = when {
            percentUsed != null -> percentUsed.coerceIn(0.0, 100.0).toInt()
            limit != null && limit > 0 -> ((used ?: 0) * 100.0 / limit).coerceIn(0.0, 100.0).toInt()
            else -> null
        }
        val availability = when {
            usedPercent == null -> QuotaWindowAvailability.Missing
            usedPercent >= 100 -> QuotaWindowAvailability.Depleted
            else -> QuotaWindowAvailability.Available
        }

        return QuotaWindow(
            windowId = QuotaWindowId(windowId),
            titleKey = titleKey,
            usedPercent = usedPercent,
            resetAt = null,
            limitWindowSeconds = null,
            isPrimaryCandidate = isPrimary,
            availability = availability,
            displayKind = QuotaWindowDisplayKind.Percent,
            subLabel = subLabel,
        )
    }

    private fun emptySnapshot(
        localAccountId: LocalAccountId,
        fetchedAt: Instant,
        source: QuotaSnapshotSource,
    ): QuotaSnapshot = QuotaSnapshot(
        snapshotId = SnapshotId("cursor_empty_${fetchedAt}"),
        providerId = CURSOR_PROVIDER_ID,
        localAccountId = localAccountId,
        providerAccountId = null,
        fetchedAt = fetchedAt,
        source = source,
        planType = null,
        windows = emptyList(),
        credits = null,
        responseDigest = null,
    )

    private val CURSOR_PROVIDER_ID = ProviderId("cursor")
}
