package com.kmnexus.codexmeter.ui.account

import com.kmnexus.codexmeter.R
import com.kmnexus.codexmeter.domain.account.AccountDeleteUseCase
import com.kmnexus.codexmeter.domain.account.AccountListResult
import com.kmnexus.codexmeter.domain.account.AccountListUseCase
import com.kmnexus.codexmeter.domain.account.AccountRenameUseCase
import com.kmnexus.codexmeter.domain.account.NoopAccountDeleteUseCase
import com.kmnexus.codexmeter.domain.account.NoopAccountListUseCase
import com.kmnexus.codexmeter.domain.account.NoopAccountRenameUseCase
import com.kmnexus.codexmeter.domain.model.LocalAccountId
import com.kmnexus.codexmeter.domain.model.ProviderAccount
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
import com.kmnexus.codexmeter.domain.settings.NotificationPreferenceStore
import com.kmnexus.codexmeter.domain.settings.NotificationPreferences
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountViewModelTest {
    @Test
    fun `rename account persists change and uses returned account`() = runTest {
        val renamedAccount = account("local-1", displayName = "Work")
        val renameUseCase = RecordingAccountRenameUseCase(renamedAccount)
        val viewModel = viewModel(renameUseCase = renameUseCase)
        viewModel.setAccounts(accounts = listOf(account("local-1", displayName = "Codex Main")), defaultAccountId = localId("local-1"))

        viewModel.renameAccount(localId("local-1"), "Work")

        assertEquals(
            listOf(AccountRenameRequest(ProviderId("codex"), localId("local-1"), "Work")),
            renameUseCase.requests,
        )
        val account = viewModel.uiState.value.accounts.single()
        assertEquals("Work", account.displayName)
        assertEquals("W", account.avatarInitial)
        assertEquals(localId("local-1"), viewModel.uiState.value.defaultAccountId)
    }

    @Test
    fun `rename failure keeps pending dialog open`() = runTest {
        val viewModel = viewModel(renameUseCase = RecordingAccountRenameUseCase(result = null))
        viewModel.setAccounts(accounts = listOf(account("local-1", displayName = "Codex Main")), defaultAccountId = localId("local-1"))
        viewModel.requestRenameAccount(localId("local-1"))

        viewModel.renameAccount(localId("local-1"), "Work")

        val uiState = viewModel.uiState.value
        assertEquals("Codex Main", uiState.accounts.single().displayName)
        assertEquals(localId("local-1"), uiState.pendingRenameAccount?.accountId)
    }

    @Test
    fun `loading accounts uses persisted account list and default selection`() = runTest {
        val accountListUseCase = RecordingAccountListUseCase(
            AccountListResult(
                accounts = listOf(account("local-1"), account("local-2")),
                defaultAccountId = localId("local-2"),
            ),
        )
        val viewModel = viewModel(accountListUseCase = accountListUseCase)

        viewModel.loadAccounts()

        val uiState = viewModel.uiState.value
        assertEquals(1, accountListUseCase.loadCount)
        assertEquals(AccountContentStatus.HasAccounts, uiState.contentStatus)
        assertEquals(localId("local-2"), uiState.defaultAccountId)
        assertEquals(listOf(localId("local-1"), localId("local-2")), uiState.accounts.map { it.id })
        assertTrue(uiState.accounts.first { it.id == localId("local-2") }.isDefault)
    }

    @Test
    fun `loading accounts maps latest quota snapshots to remaining percent summaries`() = runTest {
        val accountListUseCase = RecordingAccountListUseCase(
            AccountListResult(
                accounts = listOf(account("local-1")),
                defaultAccountId = localId("local-1"),
                latestQuotaSnapshots = mapOf(
                    localId("local-1") to quotaSnapshot(
                        localAccountId = "local-1",
                        fiveHourUsedPercent = 62,
                        weeklyUsedPercent = 41,
                    ),
                ),
            ),
        )
        val viewModel = viewModel(accountListUseCase = accountListUseCase)

        viewModel.loadAccounts()

        val summaries = viewModel.uiState.value.accounts.single().quotaSummaries
        assertEquals(38, summaries.first { it.labelResId == R.string.account_quota_five_hour_label }.percent)
        assertEquals(59, summaries.first { it.labelResId == R.string.account_quota_weekly_label }.percent)
    }

    @Test
    fun `loading accounts exposes mapped Codex plan type from latest snapshot`() = runTest {
        val accountListUseCase = RecordingAccountListUseCase(
            AccountListResult(
                accounts = listOf(account("prolite"), account("pro")),
                defaultAccountId = localId("local-1"),
                latestQuotaSnapshots = mapOf(
                    localId("prolite") to quotaSnapshot(
                        localAccountId = "prolite",
                        fiveHourUsedPercent = 62,
                        weeklyUsedPercent = 41,
                        planType = "prolite",
                    ),
                    localId("pro") to quotaSnapshot(
                        localAccountId = "pro",
                        fiveHourUsedPercent = 62,
                        weeklyUsedPercent = 41,
                        planType = "pro",
                    ),
                ),
            ),
        )
        val viewModel = viewModel(accountListUseCase = accountListUseCase)

        viewModel.loadAccounts()

        val accounts = viewModel.uiState.value.accounts.associateBy { it.id }
        assertEquals("Pro 5x", accounts.getValue(localId("prolite")).planType)
        assertEquals("Pro 20x", accounts.getValue(localId("pro")).planType)
    }

    @Test
    fun `account cards are collapsed by default and can be toggled open`() {
        val viewModel = viewModel()
        viewModel.setAccounts(accounts = listOf(account("local-1"), account("local-2")), defaultAccountId = localId("local-1"))

        assertEquals(false, viewModel.uiState.value.accounts.first { it.id == localId("local-1") }.isExpanded)
        assertEquals(false, viewModel.uiState.value.accounts.first { it.id == localId("local-2") }.isExpanded)

        viewModel.toggleAccountExpanded(localId("local-1"))

        assertEquals(true, viewModel.uiState.value.accounts.first { it.id == localId("local-1") }.isExpanded)
        assertEquals(false, viewModel.uiState.value.accounts.first { it.id == localId("local-2") }.isExpanded)

        viewModel.toggleAccountExpanded(localId("local-1"))

        assertEquals(false, viewModel.uiState.value.accounts.first { it.id == localId("local-1") }.isExpanded)
    }

    @Test
    fun `loading accounts exposes credits balance unlimited and unavailable states`() = runTest {
        val accountListUseCase = RecordingAccountListUseCase(
            AccountListResult(
                accounts = listOf(account("balance"), account("unlimited"), account("missing")),
                defaultAccountId = localId("balance"),
                latestQuotaSnapshots = mapOf(
                    localId("balance") to quotaSnapshot(
                        localAccountId = "balance",
                        fiveHourUsedPercent = 62,
                        weeklyUsedPercent = 41,
                        credits = Credits(hasCredits = true, unlimited = false, balance = 125.5),
                    ),
                    localId("unlimited") to quotaSnapshot(
                        localAccountId = "unlimited",
                        fiveHourUsedPercent = 62,
                        weeklyUsedPercent = 41,
                        credits = Credits(hasCredits = true, unlimited = true, balance = null),
                    ),
                    localId("missing") to quotaSnapshot(
                        localAccountId = "missing",
                        fiveHourUsedPercent = 62,
                        weeklyUsedPercent = 41,
                        credits = null,
                    ),
                ),
            ),
        )
        val viewModel = viewModel(accountListUseCase = accountListUseCase)

        viewModel.loadAccounts()

        val accounts = viewModel.uiState.value.accounts.associateBy { it.id }
        assertEquals(AccountCreditsUi.Balance(125.5), accounts.getValue(localId("balance")).credits)
        assertEquals(AccountCreditsUi.Unlimited, accounts.getValue(localId("unlimited")).credits)
        assertEquals(AccountCreditsUi.Unavailable, accounts.getValue(localId("missing")).credits)
    }

    @Test
    fun `loading accounts exposes persisted quota alert switches`() = runTest {
        val preferences = NotificationPreferences()
            .withAccountQuotaAlertEnabled(
                providerId = ProviderId("codex"),
                localAccountId = localId("local-1"),
                windowId = QuotaWindowId("five_hour"),
                enabled = false,
            )
            .withAccountQuotaAlertEnabled(
                providerId = ProviderId("codex"),
                localAccountId = localId("local-1"),
                windowId = QuotaWindowId("weekly"),
                enabled = true,
            )
        val viewModel = viewModel(
            accountListUseCase = RecordingAccountListUseCase(
                AccountListResult(
                    accounts = listOf(account("local-1")),
                    defaultAccountId = localId("local-1"),
                    latestQuotaSnapshots = mapOf(
                        localId("local-1") to quotaSnapshot(
                            localAccountId = "local-1",
                            fiveHourUsedPercent = 50,
                            weeklyUsedPercent = 50,
                        ),
                    ),
                ),
            ),
            notificationPreferenceStore = RecordingNotificationPreferenceStore(preferences),
        )

        viewModel.loadAccounts()

        val account = viewModel.uiState.value.accounts.single()
        assertEquals(false, account.alertToggles.first { it.windowId == "five_hour" }.enabled)
        assertEquals(true, account.alertToggles.first { it.windowId == "weekly" }.enabled)
    }

    @Test
    fun `depleted windows with displayable values stay in summaries and alert toggles while missing and valueless stay hidden`() = runTest {
        val snapshot = QuotaSnapshot(
            snapshotId = SnapshotId("snapshot-depleted"),
            providerId = ProviderId("claude"),
            localAccountId = localId("local-1"),
            providerAccountId = ProviderAccountId("provider-local-1"),
            fetchedAt = Instant.parse("2026-05-23T10:00:00Z"),
            source = QuotaSnapshotSource.ManualRefresh,
            planType = "pro",
            windows = listOf(
                QuotaWindow(
                    windowId = QuotaWindowId("five_hour"),
                    titleKey = "five_hour",
                    usedPercent = 100,
                    resetAt = Instant.parse("2026-05-23T12:00:00Z"),
                    limitWindowSeconds = 18_000,
                    isPrimaryCandidate = true,
                    availability = QuotaWindowAvailability.Depleted,
                    displayKind = QuotaWindowDisplayKind.Percent,
                ),
                QuotaWindow(
                    windowId = QuotaWindowId("balance"),
                    titleKey = "balance",
                    usedPercent = null,
                    resetAt = null,
                    limitWindowSeconds = null,
                    isPrimaryCandidate = false,
                    availability = QuotaWindowAvailability.Depleted,
                    displayKind = QuotaWindowDisplayKind.Balance,
                    balanceAmount = "0.00",
                    balanceCurrency = "CNY",
                ),
                QuotaWindow(
                    windowId = QuotaWindowId("usage"),
                    titleKey = "usage",
                    usedPercent = null,
                    resetAt = null,
                    limitWindowSeconds = null,
                    isPrimaryCandidate = false,
                    availability = QuotaWindowAvailability.Depleted,
                    displayKind = QuotaWindowDisplayKind.UsageCount,
                    usedCount = 500,
                    limitCount = 500,
                ),
                QuotaWindow(
                    windowId = QuotaWindowId("antigravity_claude_window"),
                    titleKey = "antigravity_claude_window",
                    usedPercent = 100,
                    resetAt = null,
                    limitWindowSeconds = null,
                    isPrimaryCandidate = false,
                    availability = QuotaWindowAvailability.Depleted,
                    displayKind = QuotaWindowDisplayKind.MultiModelFraction,
                ),
                QuotaWindow(
                    windowId = QuotaWindowId("depleted_no_value"),
                    titleKey = "depleted_no_value",
                    usedPercent = null,
                    resetAt = null,
                    limitWindowSeconds = null,
                    isPrimaryCandidate = false,
                    availability = QuotaWindowAvailability.Depleted,
                    displayKind = QuotaWindowDisplayKind.Percent,
                ),
                QuotaWindow(
                    windowId = QuotaWindowId("missing_slot"),
                    titleKey = "missing_slot",
                    usedPercent = 100,
                    resetAt = null,
                    limitWindowSeconds = null,
                    isPrimaryCandidate = false,
                    availability = QuotaWindowAvailability.Missing,
                    displayKind = QuotaWindowDisplayKind.Percent,
                ),
            ),
            credits = null,
            responseDigest = "safe-digest",
        )
        val viewModel = viewModel(
            accountListUseCase = RecordingAccountListUseCase(
                AccountListResult(
                    accounts = listOf(account("local-1")),
                    defaultAccountId = localId("local-1"),
                    latestQuotaSnapshots = mapOf(localId("local-1") to snapshot),
                ),
            ),
        )

        viewModel.loadAccounts()

        val uiAccount = viewModel.uiState.value.accounts.single()
        assertEquals(
            listOf("five_hour", "balance", "usage", "antigravity_claude_window"),
            uiAccount.quotaSummaries.map { it.windowId },
        )
        assertEquals(0, uiAccount.quotaSummaries[0].percent)
        assertEquals("¥0.00", uiAccount.quotaSummaries[1].valueText)
        assertEquals("500 / 500", uiAccount.quotaSummaries[2].valueText)
        assertEquals(0, uiAccount.quotaSummaries[3].percent)
        assertEquals(
            listOf("five_hour", "balance", "usage", "antigravity_claude_window"),
            uiAccount.alertToggles.map { it.windowId },
        )
    }

    @Test
    fun `claude five-window snapshot keeps sonnet summary and alert toggle when five hour window is depleted`() = runTest {
        val snapshot = QuotaSnapshot(
            snapshotId = SnapshotId("snapshot-claude-five"),
            providerId = ProviderId("claude"),
            localAccountId = localId("local-1"),
            providerAccountId = ProviderAccountId("provider-local-1"),
            fetchedAt = Instant.parse("2026-05-23T10:00:00Z"),
            source = QuotaSnapshotSource.ManualRefresh,
            planType = "pro",
            windows = listOf(
                // Claude window order mirrors ClaudeUsageMapper: a depleted 5-hour window must not
                // crowd the still-available Sonnet summary or its alert toggle out of the card.
                claudeWindow("claude_5h_window", usedPercent = 100, limitWindowSeconds = 18_000, subLabel = "5 hours"),
                claudeWindow("claude_7d_window", usedPercent = 25, limitWindowSeconds = 604_800, subLabel = "7 days"),
                claudeWindow("claude_extra_usage", usedPercent = 90, limitWindowSeconds = null, subLabel = "$90.00 / $100.00"),
                claudeWindow("claude_7d_opus_window", usedPercent = 40, limitWindowSeconds = 604_800, subLabel = "7 days · Opus"),
                claudeWindow("claude_7d_sonnet_window", usedPercent = 50, limitWindowSeconds = 604_800, subLabel = "7 days · Sonnet"),
            ),
            credits = null,
            responseDigest = "safe-digest",
        )
        val viewModel = viewModel(
            accountListUseCase = RecordingAccountListUseCase(
                AccountListResult(
                    accounts = listOf(account("local-1")),
                    defaultAccountId = localId("local-1"),
                    latestQuotaSnapshots = mapOf(localId("local-1") to snapshot),
                ),
            ),
        )

        viewModel.loadAccounts()

        val uiAccount = viewModel.uiState.value.accounts.single()
        val expectedWindows = listOf(
            "claude_5h_window",
            "claude_7d_window",
            "claude_extra_usage",
            "claude_7d_opus_window",
            "claude_7d_sonnet_window",
        )
        assertEquals(expectedWindows, uiAccount.quotaSummaries.map { it.windowId })
        assertEquals(expectedWindows, uiAccount.alertToggles.map { it.windowId })
        val sonnetSummary = uiAccount.quotaSummaries.single { it.windowId == "claude_7d_sonnet_window" }
        assertEquals(R.string.window_label_7d_sonnet, sonnetSummary.labelResId)
        assertEquals(50, sonnetSummary.percent)
        assertEquals(0, uiAccount.quotaSummaries.first { it.windowId == "claude_5h_window" }.percent)
        // Presence is the regression surface: a non-default alert window starts off unless the
        // user enables it, but the row itself must survive so it can be toggled.
        val sonnetToggle = uiAccount.alertToggles.single { it.windowId == "claude_7d_sonnet_window" }
        assertEquals(R.string.window_label_7d_sonnet, sonnetToggle.labelResId)
    }

    @Test
    fun `toggling five hour and weekly alerts persists account window preference`() = runTest {
        val store = RecordingNotificationPreferenceStore(NotificationPreferences())
        val viewModel = viewModel(notificationPreferenceStore = store)
        viewModel.setAccounts(
            accounts = listOf(account("local-1")),
            defaultAccountId = localId("local-1"),
            latestQuotaSnapshots = mapOf(
                localId("local-1") to quotaSnapshot(
                    localAccountId = "local-1",
                    fiveHourUsedPercent = 50,
                    weeklyUsedPercent = 50,
                ),
            ),
        )

        viewModel.setQuotaAlertEnabled(localId("local-1"), QuotaWindowId("five_hour"), false)
        viewModel.setQuotaAlertEnabled(localId("local-1"), QuotaWindowId("weekly"), true)

        assertEquals(
            false,
            store.preferences.isQuotaAlertEnabled(
                providerId = ProviderId("codex"),
                localAccountId = localId("local-1"),
                windowId = QuotaWindowId("five_hour"),
            ),
        )
        assertEquals(
            true,
            store.preferences.isQuotaAlertEnabled(
                providerId = ProviderId("codex"),
                localAccountId = localId("local-1"),
                windowId = QuotaWindowId("weekly"),
            ),
        )
        val account = viewModel.uiState.value.accounts.single()
        assertEquals(false, account.alertToggles.first { it.windowId == "five_hour" }.enabled)
        assertEquals(true, account.alertToggles.first { it.windowId == "weekly" }.enabled)
    }

    @Test
    fun `enabling an alert switch requests immediate evaluation for account window`() = runTest {
        val requester = RecordingAccountQuotaAlertEvaluationRequester()
        val viewModel = viewModel(
            notificationPreferenceStore = RecordingNotificationPreferenceStore(NotificationPreferences()),
            quotaAlertEvaluationRequester = requester,
        )
        viewModel.setAccounts(accounts = listOf(account("local-1")), defaultAccountId = localId("local-1"))

        viewModel.setQuotaAlertEnabled(localId("local-1"), QuotaWindowId("weekly"), true)

        assertEquals(
            listOf(AccountQuotaAlertEvaluationRequest(ProviderId("codex"), localId("local-1"), QuotaWindowId("weekly"))),
            requester.requests,
        )
    }

    @Test
    fun `deleting default account invokes delete use case and selects another account when available`() = runTest {
        val deleteUseCase = RecordingAccountDeleteUseCase()
        val viewModel = viewModel(deleteUseCase)
        viewModel.setAccounts(accounts = listOf(account("local-1"), account("local-2")), defaultAccountId = localId("local-1"))

        viewModel.requestDeleteAccount(localId("local-1"))
        viewModel.confirmDelete()

        assertEquals(
            listOf(AccountDeleteRequest(providerId = ProviderId("codex"), localAccountId = localId("local-1"))),
            deleteUseCase.requests,
        )
        val uiState = viewModel.uiState.value
        assertEquals(AccountContentStatus.HasAccounts, uiState.contentStatus)
        assertEquals(localId("local-2"), uiState.defaultAccountId)
        assertEquals(listOf(localId("local-2")), uiState.accounts.map { it.id })
        assertTrue(uiState.accounts.single().isDefault)
        assertNull(uiState.pendingDeleteAccount)
    }

    @Test
    fun `deleting last account returns unauthenticated state`() = runTest {
        val viewModel = viewModel()
        viewModel.setAccounts(accounts = listOf(account("local-1")), defaultAccountId = localId("local-1"))

        viewModel.requestDeleteAccount(localId("local-1"))
        viewModel.confirmDelete()

        val uiState = viewModel.uiState.value
        assertEquals(AccountContentStatus.Unauthenticated, uiState.contentStatus)
        assertNull(uiState.defaultAccountId)
        assertTrue(uiState.accounts.isEmpty())
        assertNull(uiState.pendingDeleteAccount)
    }

    @Test
    fun `delete use case failure keeps account and pending confirmation`() = runTest {
        val viewModel = viewModel(ThrowingAccountDeleteUseCase(IllegalStateException("delete failed")))
        viewModel.setAccounts(accounts = listOf(account("local-1")), defaultAccountId = localId("local-1"))

        viewModel.requestDeleteAccount(localId("local-1"))
        viewModel.confirmDelete()

        val uiState = viewModel.uiState.value
        assertEquals(AccountContentStatus.HasAccounts, uiState.contentStatus)
        assertEquals(listOf(localId("local-1")), uiState.accounts.map { it.id })
        assertEquals(localId("local-1"), uiState.defaultAccountId)
        assertEquals(localId("local-1"), uiState.pendingDeleteAccount?.accountId)
    }

    @Test
    fun `request and dismiss add account toggles add sheet state`() {
        val viewModel = viewModel()
        viewModel.requestAddAccount()

        assertTrue(viewModel.uiState.value.showAddAccountSheet)

        viewModel.dismissAddAccount()

        assertEquals(false, viewModel.uiState.value.showAddAccountSheet)
        assertNull(viewModel.uiState.value.selectedAddAccountIntent)
    }

    @Test
    fun `add account intents only include login`() {
        assertEquals(listOf(AccountAddAccountIntent.LoginToCodex), AccountAddAccountIntent.entries)
    }

    @Test
    fun `selecting relogin emits a request targeting that account and closes the sheet`() {
        val viewModel = viewModel()
        viewModel.setAccounts(accounts = listOf(account("local-1")), defaultAccountId = localId("local-1"))
        viewModel.requestAddAccount()

        viewModel.selectRelogin(localId("local-1"))

        assertEquals(false, viewModel.uiState.value.showAddAccountSheet)
        val request = viewModel.uiState.value.pendingReloginRequest
        assertEquals(ProviderId("codex"), request?.providerId)
        assertEquals(localId("local-1"), request?.localAccountId)
        assertEquals("provider-local-1", request?.providerAccountId)
    }

    @Test
    fun `consuming the relogin request clears it`() {
        val viewModel = viewModel()
        viewModel.setAccounts(accounts = listOf(account("local-1")), defaultAccountId = localId("local-1"))
        viewModel.selectRelogin(localId("local-1"))

        viewModel.consumeReloginRequest()

        assertEquals(null, viewModel.uiState.value.pendingReloginRequest)
    }

    private class RecordingAccountDeleteUseCase : AccountDeleteUseCase {
        val requests = mutableListOf<AccountDeleteRequest>()

        override suspend fun deleteAccount(providerId: ProviderId, localAccountId: LocalAccountId) {
            requests += AccountDeleteRequest(providerId = providerId, localAccountId = localAccountId)
        }
    }

    private class ThrowingAccountDeleteUseCase(
        private val exception: Throwable,
    ) : AccountDeleteUseCase {
        override suspend fun deleteAccount(providerId: ProviderId, localAccountId: LocalAccountId) {
            throw exception
        }
    }

    private data class AccountDeleteRequest(
        val providerId: ProviderId,
        val localAccountId: LocalAccountId,
    )

    private class RecordingAccountListUseCase(
        private val result: AccountListResult,
    ) : AccountListUseCase {
        var loadCount = 0

        override suspend fun loadAccounts(): AccountListResult {
            loadCount += 1
            return result
        }
    }

    private class RecordingAccountRenameUseCase(
        private val result: ProviderAccount?,
    ) : AccountRenameUseCase {
        val requests = mutableListOf<AccountRenameRequest>()

        override suspend fun renameAccount(
            providerId: ProviderId,
            localAccountId: LocalAccountId,
            displayName: String,
        ): ProviderAccount? {
            requests += AccountRenameRequest(providerId = providerId, localAccountId = localAccountId, displayName = displayName)
            return result
        }
    }

    private data class AccountRenameRequest(
        val providerId: ProviderId,
        val localAccountId: LocalAccountId,
        val displayName: String,
    )

    private class RecordingNotificationPreferenceStore(
        var preferences: NotificationPreferences,
    ) : NotificationPreferenceStore {
        override suspend fun notificationPreferences(): NotificationPreferences = preferences

        override suspend fun updateNotificationPreferences(preferences: NotificationPreferences) {
            this.preferences = preferences
        }
    }

    private class RecordingAccountQuotaAlertEvaluationRequester : AccountQuotaAlertEvaluationRequester {
        val requests = mutableListOf<AccountQuotaAlertEvaluationRequest>()

        override suspend fun requestQuotaAlertEvaluation(
            providerId: ProviderId,
            localAccountId: LocalAccountId,
            windowId: QuotaWindowId,
        ) {
            requests += AccountQuotaAlertEvaluationRequest(providerId, localAccountId, windowId)
        }
    }

    private data class AccountQuotaAlertEvaluationRequest(
        val providerId: ProviderId,
        val localAccountId: LocalAccountId,
        val windowId: QuotaWindowId,
    )

    private fun viewModel(
        deleteUseCase: AccountDeleteUseCase = NoopAccountDeleteUseCase,
        accountListUseCase: AccountListUseCase = NoopAccountListUseCase,
        renameUseCase: AccountRenameUseCase = NoopAccountRenameUseCase,
        notificationPreferenceStore: NotificationPreferenceStore = AccountInMemoryNotificationPreferenceStore(),
        quotaAlertEvaluationRequester: AccountQuotaAlertEvaluationRequester = NoopAccountQuotaAlertEvaluationRequester,
    ): AccountViewModel =
        AccountViewModel(
            deleteUseCase = deleteUseCase,
            accountListUseCase = accountListUseCase,
            renameUseCase = renameUseCase,
            notificationPreferenceStore = notificationPreferenceStore,
            quotaAlertEvaluationRequester = quotaAlertEvaluationRequester,
        )

    private fun account(id: String, displayName: String = "Account $id"): ProviderAccount =
        ProviderAccount.createNew(
            localAccountId = localId(id),
            providerId = ProviderId("codex"),
            providerAccountId = ProviderAccountId("provider-$id"),
            displayName = displayName,
            now = Instant.parse("2026-05-23T09:00:00Z"),
        )

    private fun quotaSnapshot(
        localAccountId: String,
        fiveHourUsedPercent: Int?,
        weeklyUsedPercent: Int?,
        planType: String? = "plus",
        credits: Credits? = null,
    ): QuotaSnapshot =
        QuotaSnapshot(
            snapshotId = SnapshotId("snapshot-$localAccountId"),
            providerId = ProviderId("codex"),
            localAccountId = localId(localAccountId),
            providerAccountId = ProviderAccountId("provider-$localAccountId"),
            fetchedAt = Instant.parse("2026-05-23T10:00:00Z"),
            source = QuotaSnapshotSource.ManualRefresh,
            planType = planType,
            windows = listOf(
                quotaWindow("five_hour", fiveHourUsedPercent, limitWindowSeconds = 18_000),
                quotaWindow("weekly", weeklyUsedPercent, limitWindowSeconds = 604_800),
            ),
            credits = credits,
            responseDigest = "safe-digest",
        )

    private fun quotaWindow(
        windowId: String,
        usedPercent: Int?,
        limitWindowSeconds: Int = 18_000,
    ): QuotaWindow =
        QuotaWindow(
            windowId = QuotaWindowId(windowId),
            titleKey = "quota_window_$windowId",
            usedPercent = usedPercent,
            resetAt = Instant.parse("2026-05-23T12:00:00Z"),
            limitWindowSeconds = limitWindowSeconds,
            isPrimaryCandidate = true,
            availability = QuotaWindowAvailability.Available,
        )

    private fun claudeWindow(
        windowId: String,
        usedPercent: Int,
        limitWindowSeconds: Int?,
        subLabel: String,
    ): QuotaWindow =
        QuotaWindow(
            windowId = QuotaWindowId(windowId),
            titleKey = windowId,
            usedPercent = usedPercent,
            resetAt = Instant.parse("2026-05-23T12:00:00Z"),
            limitWindowSeconds = limitWindowSeconds,
            isPrimaryCandidate = windowId == "claude_5h_window",
            availability = if (usedPercent >= 100) {
                QuotaWindowAvailability.Depleted
            } else {
                QuotaWindowAvailability.Available
            },
            displayKind = QuotaWindowDisplayKind.Percent,
            subLabel = subLabel,
        )

    private fun localId(id: String): LocalAccountId = LocalAccountId(id)
}
