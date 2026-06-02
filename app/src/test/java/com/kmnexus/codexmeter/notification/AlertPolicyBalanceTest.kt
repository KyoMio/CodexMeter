package com.kmnexus.codexmeter.notification

import com.kmnexus.codexmeter.domain.model.*
import com.kmnexus.codexmeter.domain.quota.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class AlertPolicyBalanceTest {
    private val policy = AlertPolicy()

    @Test
    fun evaluate_balanceBetweenWarningAndCaution_returnsCaution() {
        val thresholds = AlertThresholds(caution = 30, warning = 10, limit = 0,
            balanceCaution = 5.0, balanceWarning = 1.0)
        val window = QuotaWindow(windowId = QuotaWindowId("balance"), titleKey = "balance",
            usedPercent = null, resetAt = null, limitWindowSeconds = null,
            isPrimaryCandidate = true, availability = QuotaWindowAvailability.Available,
            displayKind = QuotaWindowDisplayKind.Balance, balanceAmount = "3.00")
        val snapshot = QuotaSnapshot(snapshotId = SnapshotId("s1"), providerId = ProviderId("deepseek"),
            localAccountId = LocalAccountId("a1"), providerAccountId = null, fetchedAt = Instant.EPOCH,
            source = QuotaSnapshotSource.ManualRefresh, planType = null, windows = listOf(window), credits = null, responseDigest = null)
        val account = ProviderAccount.createNew(LocalAccountId("a1"), ProviderId("deepseek"), null, "Test", Instant.EPOCH)
        val factory = CurrentQuotaStateFactory()
        val state = factory.create(account, snapshot, null, Instant.EPOCH, QuotaWindowId("balance"))
        val events = policy.evaluate(state, thresholds = thresholds, enabledWindowIds = setOf(QuotaWindowId("balance")))
        assertEquals(1, events.size)
        assertEquals(AlertLevel.Caution, events[0].level)
    }

    @Test
    fun evaluate_balanceAtOrBelowWarning_returnsWarning() {
        val thresholds = AlertThresholds(caution = 30, warning = 10, limit = 0,
            balanceCaution = 5.0, balanceWarning = 1.0)
        // amount exactly at warning threshold
        val windowAtWarning = QuotaWindow(windowId = QuotaWindowId("balance"), titleKey = "balance",
            usedPercent = null, resetAt = null, limitWindowSeconds = null,
            isPrimaryCandidate = true, availability = QuotaWindowAvailability.Available,
            displayKind = QuotaWindowDisplayKind.Balance, balanceAmount = "1.00")
        val snapshot = QuotaSnapshot(snapshotId = SnapshotId("s1"), providerId = ProviderId("deepseek"),
            localAccountId = LocalAccountId("a1"), providerAccountId = null, fetchedAt = Instant.EPOCH,
            source = QuotaSnapshotSource.ManualRefresh, planType = null, windows = listOf(windowAtWarning), credits = null, responseDigest = null)
        val account = ProviderAccount.createNew(LocalAccountId("a1"), ProviderId("deepseek"), null, "Test", Instant.EPOCH)
        val factory = CurrentQuotaStateFactory()
        val state = factory.create(account, snapshot, null, Instant.EPOCH, QuotaWindowId("balance"))
        val events = policy.evaluate(state, thresholds = thresholds, enabledWindowIds = setOf(QuotaWindowId("balance")))
        assertEquals(1, events.size)
        assertEquals(AlertLevel.Warning, events[0].level)
    }

    @Test
    fun evaluate_balanceAboveCaution_returnsEmpty() {
        val thresholds = AlertThresholds(caution = 30, warning = 10, limit = 0,
            balanceCaution = 5.0, balanceWarning = 1.0)
        val window = QuotaWindow(windowId = QuotaWindowId("balance"), titleKey = "balance",
            usedPercent = null, resetAt = null, limitWindowSeconds = null,
            isPrimaryCandidate = true, availability = QuotaWindowAvailability.Available,
            displayKind = QuotaWindowDisplayKind.Balance, balanceAmount = "10.00")
        val snapshot = QuotaSnapshot(snapshotId = SnapshotId("s1"), providerId = ProviderId("deepseek"),
            localAccountId = LocalAccountId("a1"), providerAccountId = null, fetchedAt = Instant.EPOCH,
            source = QuotaSnapshotSource.ManualRefresh, planType = null, windows = listOf(window), credits = null, responseDigest = null)
        val account = ProviderAccount.createNew(LocalAccountId("a1"), ProviderId("deepseek"), null, "Test", Instant.EPOCH)
        val factory = CurrentQuotaStateFactory()
        val state = factory.create(account, snapshot, null, Instant.EPOCH, QuotaWindowId("balance"))
        val events = policy.evaluate(state, thresholds = thresholds, enabledWindowIds = setOf(QuotaWindowId("balance")))
        assertTrue(events.isEmpty())
    }

    @Test
    fun evaluate_percentWindow_stillWorks() {
        val thresholds = AlertThresholds(caution = 30, warning = 10, limit = 0)
        val window = QuotaWindow(windowId = QuotaWindowId("5h"), titleKey = "5h",
            usedPercent = 95, resetAt = Instant.now().plusSeconds(3600), limitWindowSeconds = 18000,
            isPrimaryCandidate = true, availability = QuotaWindowAvailability.Available,
            displayKind = QuotaWindowDisplayKind.Percent)
        val snapshot = QuotaSnapshot(snapshotId = SnapshotId("s1"), providerId = ProviderId("codex"),
            localAccountId = LocalAccountId("a1"), providerAccountId = null, fetchedAt = Instant.EPOCH,
            source = QuotaSnapshotSource.ManualRefresh, planType = null, windows = listOf(window), credits = null, responseDigest = null)
        val account = ProviderAccount.createNew(LocalAccountId("a1"), ProviderId("codex"), null, "Test", Instant.EPOCH)
        val factory = CurrentQuotaStateFactory()
        val state = factory.create(account, snapshot, null, Instant.EPOCH, QuotaWindowId("5h"))
        val events = policy.evaluate(state, thresholds = thresholds, enabledWindowIds = setOf(QuotaWindowId("5h")))
        assertEquals(1, events.size)
        assertEquals(AlertLevel.Warning, events[0].level)  // remaining=5% <= warning=10%
    }
}
