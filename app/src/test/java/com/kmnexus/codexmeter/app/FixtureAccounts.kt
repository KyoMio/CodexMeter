package com.kmnexus.codexmeter.app

import com.kmnexus.codexmeter.data.local.entity.ProviderAccountEntity
import com.kmnexus.codexmeter.data.local.entity.QuotaSnapshotEntity
import java.time.Instant

/**
 * Shared account/snapshot fixtures for app-layer repository & notification tests.
 */
object FixtureAccounts {

    fun account(
        status: String = "active",
        localAccountId: String = "local-1",
        displayName: String = "Work",
    ) = ProviderAccountEntity(
        localAccountId = localAccountId,
        providerId = "codex",
        providerAccountId = "acct-$localAccountId",
        displayName = displayName,
        avatarInitial = displayName.first().uppercase(),
        avatarColorKey = localAccountId,
        status = status,
        createdAt = Instant.parse("2026-05-23T11:00:00Z").toEpochMilli(),
        updatedAt = Instant.parse("2026-05-23T11:30:00Z").toEpochMilli(),
        lastSuccessfulRefreshAt = null,
    )

    fun snapshot(
        fetchedAt: Instant = Instant.parse("2026-05-23T11:50:00Z"),
        snapshotId: String = "snapshot-1",
        localAccountId: String = "local-1",
    ) = QuotaSnapshotEntity(
        snapshotId = snapshotId,
        providerId = "codex",
        localAccountId = localAccountId,
        providerAccountId = "acct-$localAccountId",
        fetchedAt = fetchedAt.toEpochMilli(),
        source = "manualRefresh",
        planType = "plus",
        windowsJson = """[{"windowId":"five_hour","titleKey":"quota_window_five_hour","usedPercent":62,"resetAt":1779555600000,"limitWindowSeconds":18000,"isPrimaryCandidate":true,"availability":"Available"},{"windowId":"weekly","titleKey":"quota_window_weekly","usedPercent":41,"resetAt":1780012800000,"limitWindowSeconds":604800,"isPrimaryCandidate":true,"availability":"Available"}]""",
        creditsJson = null,
        responseDigest = "safe-digest",
    )
}
