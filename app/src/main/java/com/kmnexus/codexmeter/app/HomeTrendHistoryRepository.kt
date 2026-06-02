package com.kmnexus.codexmeter.app

import com.kmnexus.codexmeter.data.local.dao.ProviderAccountDao
import com.kmnexus.codexmeter.data.local.dao.QuotaSnapshotDao
import com.kmnexus.codexmeter.data.preferences.CurrentAccountReader
import com.kmnexus.codexmeter.data.repository.toDomain
import com.kmnexus.codexmeter.domain.model.LocalAccountId
import com.kmnexus.codexmeter.domain.quota.QuotaSnapshot
import com.kmnexus.codexmeter.domain.quota.QuotaWindow
import com.kmnexus.codexmeter.domain.quota.QuotaWindowAvailability
import com.kmnexus.codexmeter.domain.quota.QuotaWindowDisplayKind
import com.kmnexus.codexmeter.ui.home.HomeTrendHistoryLoader
import com.kmnexus.codexmeter.ui.home.HomeTrendPointUi
import com.kmnexus.codexmeter.ui.home.HomeTrendQuery
import java.time.Clock
import java.time.Duration
import java.time.Instant

internal class HomeTrendHistoryRepository(
    private val currentAccountReader: CurrentAccountReader,
    private val providerAccountDao: ProviderAccountDao,
    private val quotaSnapshotDao: QuotaSnapshotDao,
    private val clock: Clock,
) : HomeTrendHistoryLoader {
    override suspend fun loadTrend(accountId: LocalAccountId?, query: HomeTrendQuery): List<HomeTrendPointUi> {
        val selection = currentAccountReader.currentAccountSelection() ?: return emptyList()
        if (accountId != null && selection.localAccountId != accountId) return emptyList()
        val account = providerAccountDao.getById(selection.localAccountId.value)
            ?.takeIf { it.providerId == selection.providerId.value }
            ?: return emptyList()

        val now = clock.instant()
        val windowStart = now.minus(TREND_WINDOW)
        val snapshots = quotaSnapshotDao.listForAccountSince(
            providerId = account.providerId,
            localAccountId = account.localAccountId,
            sinceMillis = windowStart.toEpochMilli(),
        ).mapNotNull { entity -> runCatching { entity.toDomain() }.getOrNull() }
            .sortedBy { it.fetchedAt }

        return if (query.useModelBucketSum) {
            modelBucketSumPoints(snapshots, windowStart, now)
        } else {
            scalarDiffPoints(snapshots, query.windowId, query.displayKind, windowStart, now)
        }
    }

    private fun scalarDiffPoints(
        snapshots: List<QuotaSnapshot>,
        windowId: String,
        displayKind: QuotaWindowDisplayKind,
        windowStart: Instant,
        now: Instant,
    ): List<HomeTrendPointUi> {
        val series = snapshots.mapNotNull { snapshot ->
            val window = snapshot.windows
                .firstOrNull { it.windowId.value == windowId && it.availability == QuotaWindowAvailability.Available }
                ?: return@mapNotNull null
            val value = window.cumulativeValue(displayKind) ?: return@mapNotNull null
            snapshot.fetchedAt to value
        }
        return series.zipWithNext().map { (prev, curr) ->
            val delta = consumptionDelta(prev.second, curr.second, displayKind).coerceAtLeast(0.0)
            HomeTrendPointUi(
                capturedAt = curr.first,
                usageValue = delta,
                xPositionInWindow = curr.first.positionInTrendWindow(windowStart, now),
            )
        }
    }

    private fun modelBucketSumPoints(
        snapshots: List<QuotaSnapshot>,
        windowStart: Instant,
        now: Instant,
    ): List<HomeTrendPointUi> {
        val series = snapshots.mapNotNull { snapshot ->
            // query.windowId is intentionally NOT used here: we sum across ALL of the provider's
            // model-bucket-sum windows (e.g. Antigravity's per-family windows) so the chart
            // reflects total usage across every model family, not just the primary one.
            val usedByModel = snapshot.windows
                .filter { it.usesModelBucketSum && it.availability == QuotaWindowAvailability.Available }
                .flatMap { it.modelBuckets }
                .associate { it.modelId to (1.0 - it.remainingFraction).coerceIn(0.0, 1.0) }
            if (usedByModel.isEmpty()) null else snapshot.fetchedAt to usedByModel
        }
        return series.zipWithNext().map { (prev, curr) ->
            val prevUsed = prev.second
            val delta = curr.second.entries.sumOf { (modelId, used) ->
                (used - (prevUsed[modelId] ?: 0.0)).coerceAtLeast(0.0)
            }
            HomeTrendPointUi(
                capturedAt = curr.first,
                usageValue = delta,
                xPositionInWindow = curr.first.positionInTrendWindow(windowStart, now),
            )
        }
    }

    private fun QuotaWindow.cumulativeValue(displayKind: QuotaWindowDisplayKind): Double? =
        when (displayKind) {
            QuotaWindowDisplayKind.Balance -> balanceAmount?.toDoubleOrNull()
            QuotaWindowDisplayKind.UsageCount -> usedCount?.toDouble()
            // Providers that use MultiModelFraction set usesModelBucketSum = true and therefore
            // take the modelBucketSumPoints path instead; in practice this scalar path only sees
            // plain Percent windows.
            else -> usedPercent?.toDouble()
        }

    // Used metrics rise with consumption; balance falls as it is spent.
    private fun consumptionDelta(prev: Double, curr: Double, displayKind: QuotaWindowDisplayKind): Double =
        if (displayKind == QuotaWindowDisplayKind.Balance) prev - curr else curr - prev

    private fun Instant.positionInTrendWindow(windowStart: Instant, windowEnd: Instant): Float {
        val elapsedMillis = Duration.between(windowStart, this).toMillis().coerceAtLeast(0L)
        val windowMillis = Duration.between(windowStart, windowEnd).toMillis().coerceAtLeast(1L)
        return (elapsedMillis.toFloat() / windowMillis.toFloat()).coerceIn(0f, 1f)
    }

    private companion object {
        val TREND_WINDOW: Duration = Duration.ofHours(24)
    }
}
