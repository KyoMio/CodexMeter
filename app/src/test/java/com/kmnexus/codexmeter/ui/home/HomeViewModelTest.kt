package com.kmnexus.codexmeter.ui.home

import com.kmnexus.codexmeter.R
import com.kmnexus.codexmeter.domain.model.AccountStatus
import com.kmnexus.codexmeter.domain.model.LocalAccountId
import com.kmnexus.codexmeter.domain.model.ProviderAccount
import com.kmnexus.codexmeter.domain.model.ProviderAccountId
import com.kmnexus.codexmeter.domain.model.ProviderId
import com.kmnexus.codexmeter.domain.model.QuotaWindowId
import com.kmnexus.codexmeter.domain.model.RefreshAttemptId
import com.kmnexus.codexmeter.domain.model.SnapshotId
import com.kmnexus.codexmeter.domain.quota.CurrentQuotaFreshness
import com.kmnexus.codexmeter.domain.quota.CurrentQuotaState
import com.kmnexus.codexmeter.domain.quota.CurrentQuotaStatus
import com.kmnexus.codexmeter.domain.quota.Credits
import com.kmnexus.codexmeter.domain.quota.QuotaSnapshot
import com.kmnexus.codexmeter.domain.quota.QuotaSnapshotSource
import com.kmnexus.codexmeter.domain.quota.QuotaWindow
import com.kmnexus.codexmeter.domain.quota.QuotaWindowAvailability
import com.kmnexus.codexmeter.domain.quota.QuotaWindowDisplayKind
import com.kmnexus.codexmeter.domain.refresh.QuotaError
import com.kmnexus.codexmeter.domain.refresh.RefreshAttempt
import com.kmnexus.codexmeter.domain.refresh.RefreshAttemptStatus
import com.kmnexus.codexmeter.domain.refresh.RefreshTrigger
import com.kmnexus.codexmeter.domain.settings.NotificationPreferenceReader
import com.kmnexus.codexmeter.domain.settings.NotificationPreferences
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    private val now = Instant.parse("2026-05-23T10:00:00Z")
    private val viewModel = HomeViewModel()
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUpMainDispatcher() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state is loading until current account is resolved`() {
        val viewModel = HomeViewModel()

        assertEquals(HomeContentStatus.Loading, viewModel.uiState.value.contentStatus)
        assertTrue(viewModel.uiState.value.isRefreshing)
        assertNull(viewModel.uiState.value.primaryAction)
    }

    @Test
    fun `unauthenticated state shows only login action without quota cards`() {
        val uiState = viewModel.mapToUiState(
            state = currentState(
                status = CurrentQuotaStatus.Unauthenticated,
                freshness = CurrentQuotaFreshness.Unknown,
                account = null,
                snapshot = null,
            ),
        )

        assertEquals(HomeContentStatus.Unauthenticated, uiState.contentStatus)
        assertEquals(R.string.home_state_unauthenticated_title, uiState.statusTitleResId)
        assertNull(uiState.account)
        assertNull(uiState.fiveHourCard)
        assertNull(uiState.weeklyCard)
        assertEquals(HomeActionKind.LoginToCodex, uiState.primaryAction?.kind)
        assertNull(uiState.secondaryAction)
        assertFalse(uiState.isRefreshing)
    }

    @Test
    fun `fresh state maps account and both quota cards`() {
        val snapshot = snapshot(fetchedAt = now.minus(Duration.ofMinutes(5)))

        val uiState = viewModel.mapToUiState(
            state = currentState(
                status = CurrentQuotaStatus.Fresh,
                freshness = CurrentQuotaFreshness.Fresh,
                snapshot = snapshot,
            ),
        )

        assertEquals(HomeContentStatus.Fresh, uiState.contentStatus)
        assertEquals(R.string.home_state_fresh_title, uiState.statusTitleResId)
        assertEquals("Codex Main", uiState.account?.displayName)
        assertEquals("C", uiState.account?.avatarInitial)
        assertEquals("five_hour", uiState.fiveHourCard?.windowId)
        assertEquals(R.string.account_quota_five_hour_label, uiState.fiveHourCard?.titleResId)
        assertEquals(38, uiState.fiveHourCard?.percent)
        assertEquals(HomeQuotaStatus.Normal, uiState.fiveHourCard?.status)
        assertEquals("weekly", uiState.weeklyCard?.windowId)
        assertEquals(R.string.account_quota_weekly_label, uiState.weeklyCard?.titleResId)
        assertEquals(59, uiState.weeklyCard?.percent)
        assertEquals(2, uiState.quotaCards.size)
        assertEquals("five_hour", uiState.quotaCards[0].windowId)
        assertEquals(QuotaWindowDisplayKind.Percent, uiState.quotaCards[0].displayKind)
        assertEquals(62, uiState.quotaCards[0].usedPercent)
        assertEquals(true, uiState.quotaCards[0].isPrimary)
        assertEquals("weekly", uiState.quotaCards[1].windowId)
        assertEquals(41, uiState.quotaCards[1].usedPercent)
        assertEquals(snapshot.fetchedAt, uiState.refresh.lastSuccessfulRefreshAt)
        assertEquals(R.string.home_trend_placeholder_title, uiState.trend.titleResId)
        assertEquals(R.string.home_trend_metric_usage, uiState.trend.metricLabelResId)
    }

    @Test
    fun `balance primary window yields spend metric label`() {
        val uiState = viewModel.mapToUiState(
            state = currentState(
                status = CurrentQuotaStatus.Fresh,
                freshness = CurrentQuotaFreshness.Fresh,
                snapshot = snapshot(fetchedAt = now.minus(Duration.ofMinutes(5))),
                primaryWindow = primaryWindowWithKind(QuotaWindowDisplayKind.Balance),
            ),
        )

        assertEquals(R.string.home_trend_metric_spend, uiState.trend.metricLabelResId)
    }

    @Test
    fun `usage count primary window yields calls metric label`() {
        val uiState = viewModel.mapToUiState(
            state = currentState(
                status = CurrentQuotaStatus.Fresh,
                freshness = CurrentQuotaFreshness.Fresh,
                snapshot = snapshot(fetchedAt = now.minus(Duration.ofMinutes(5))),
                primaryWindow = primaryWindowWithKind(QuotaWindowDisplayKind.UsageCount),
            ),
        )

        assertEquals(R.string.home_trend_metric_calls, uiState.trend.metricLabelResId)
    }

    @Test
    fun `fresh state maps hero account summary to plan and credits`() {
        val snapshot = snapshot(
            fetchedAt = now.minus(Duration.ofMinutes(5)),
            planType = "pro",
            credits = Credits(hasCredits = true, unlimited = false, balance = 64.5),
        )

        val uiState = viewModel.mapToUiState(
            state = currentState(
                status = CurrentQuotaStatus.Fresh,
                freshness = CurrentQuotaFreshness.Fresh,
                snapshot = snapshot,
            ),
        )

        assertEquals("Pro 20x", uiState.account?.planType)
        assertEquals(HomeCreditsUi.Balance(64.5), uiState.account?.credits)
        assertEquals(38, uiState.fiveHourCard?.percent)
        assertEquals(HomeQuotaStatus.Normal, uiState.fiveHourCard?.status)
    }

    @Test
    fun `fresh state maps prolite hero plan to Pro 5x`() {
        val uiState = viewModel.mapToUiState(
            state = currentState(
                status = CurrentQuotaStatus.Fresh,
                freshness = CurrentQuotaFreshness.Fresh,
                snapshot = snapshot(
                    fetchedAt = now.minus(Duration.ofMinutes(5)),
                    planType = "prolite",
                ),
            ),
        )

        assertEquals("Pro 5x", uiState.account?.planType)
    }

    @Test
    fun `current quota update loads recent trend history for primary window`() = runTest {
        val trendLoader = RecordingHomeTrendHistoryLoader(
            points = listOf(
                HomeTrendPointUi(capturedAt = now.minus(Duration.ofHours(1)), usageValue = 5.0),
                HomeTrendPointUi(capturedAt = now, usageValue = 8.0),
            ),
        )
        val viewModel = HomeViewModel(trendHistoryLoader = trendLoader)

        viewModel.updateCurrentQuotaState(
            state = currentState(
                status = CurrentQuotaStatus.Fresh,
                freshness = CurrentQuotaFreshness.Fresh,
                snapshot = snapshot(fetchedAt = now.minus(Duration.ofMinutes(5))),
            ),
        )
        advanceUntilIdle()

        assertEquals(listOf("five_hour"), trendLoader.requests.map { it.windowId })
        assertEquals(2, viewModel.uiState.value.trend.points.size)
        assertEquals(8.0, viewModel.uiState.value.trend.points.last().usageValue, 1e-9)
    }

    @Test
    fun `current quota update defaults trend selection to primary quota window`() = runTest {
        val trendLoader = RecordingHomeTrendHistoryLoader()
        val viewModel = HomeViewModel(trendHistoryLoader = trendLoader)

        viewModel.updateCurrentQuotaState(
            state = currentState(
                status = CurrentQuotaStatus.Fresh,
                freshness = CurrentQuotaFreshness.Fresh,
                snapshot = snapshot(fetchedAt = now.minus(Duration.ofMinutes(5))),
                primaryWindow = weeklyWindow(usedPercent = 41),
            ),
        )
        advanceUntilIdle()

        assertEquals(listOf("weekly"), trendLoader.requests.map { it.windowId })
    }

    @Test
    fun `trend loads for providers whose primary window id is not five_hour`() = runTest {
        // Non-Codex providers (e.g. DeepSeek "balance") have no "five_hour" window, so
        // CurrentQuotaState.primaryWindow is null. The trend must still load by falling back
        // to the snapshot's own primary-candidate window instead of giving up.
        val trendLoader = RecordingHomeTrendHistoryLoader(
            points = listOf(
                HomeTrendPointUi(capturedAt = now.minus(Duration.ofHours(1)), usageValue = 3.0),
                HomeTrendPointUi(capturedAt = now, usageValue = 4.0),
            ),
        )
        val viewModel = HomeViewModel(trendHistoryLoader = trendLoader)

        val balanceWindow = QuotaWindow(
            windowId = QuotaWindowId("balance"),
            titleKey = "deepseek_balance",
            usedPercent = null,
            resetAt = null,
            limitWindowSeconds = null,
            isPrimaryCandidate = true,
            availability = QuotaWindowAvailability.Available,
            displayKind = QuotaWindowDisplayKind.Balance,
            balanceAmount = "12.5",
        )
        val balanceSnapshot = snapshot(fetchedAt = now.minus(Duration.ofMinutes(5)))
            .copy(windows = listOf(balanceWindow))

        viewModel.updateCurrentQuotaState(
            state = currentState(
                status = CurrentQuotaStatus.Fresh,
                freshness = CurrentQuotaFreshness.Fresh,
                snapshot = balanceSnapshot,
                primaryWindow = null,
            ),
        )
        advanceUntilIdle()

        assertEquals(listOf("balance"), trendLoader.requests.map { it.windowId })
        assertEquals(QuotaWindowDisplayKind.Balance, trendLoader.requests.single().displayKind)
        assertEquals(R.string.home_trend_metric_spend, viewModel.uiState.value.trend.metricLabelResId)
        assertEquals(2, viewModel.uiState.value.trend.points.size)
    }

    @Test
    fun `home quota status uses persisted remaining threshold preferences`() = runTest {
        val currentState = currentState(
            status = CurrentQuotaStatus.Fresh,
            freshness = CurrentQuotaFreshness.Fresh,
            snapshot = snapshot(fetchedAt = now.minus(Duration.ofMinutes(5))),
        )
        val viewModel = HomeViewModel(
            currentQuotaStateLoader = FakeHomeCurrentQuotaStateLoader(currentState),
            appOpenRefreshUseCase = FakeHomeAppOpenRefreshUseCase(currentState),
            notificationPreferenceReader = StaticNotificationPreferenceReader(
                NotificationPreferences(cautionThreshold = 50, warningThreshold = 20),
            ),
        )

        viewModel.loadCurrentState()
        advanceUntilIdle()

        assertEquals(HomeQuotaStatus.Caution, viewModel.uiState.value.fiveHourCard?.status)
        assertEquals(HomeStatusTone.Warning, viewModel.uiState.value.fiveHourCard?.tone)
    }

    @Test
    fun `current quota update does not reuse previous account trend points`() = runTest {
        val trendLoader = RecordingHomeTrendHistoryLoader(
            pointsByAccountId = mapOf(
                "local-1" to listOf(
                    HomeTrendPointUi(capturedAt = now.minus(Duration.ofHours(1)), usageValue = 5.0),
                ),
            ),
        )
        val viewModel = HomeViewModel(trendHistoryLoader = trendLoader)
        viewModel.updateCurrentQuotaState(
            state = currentState(
                status = CurrentQuotaStatus.Fresh,
                freshness = CurrentQuotaFreshness.Fresh,
                snapshot = snapshot(fetchedAt = now.minus(Duration.ofMinutes(5))),
            ),
        )
        advanceUntilIdle()

        viewModel.updateCurrentQuotaState(
            state = currentState(
                status = CurrentQuotaStatus.Fresh,
                freshness = CurrentQuotaFreshness.Fresh,
                account = account(localAccountId = "local-2"),
                snapshot = snapshot(
                    fetchedAt = now.minus(Duration.ofMinutes(1)),
                    localAccountId = "local-2",
                ),
            ),
        )

        assertEquals(emptyList<HomeTrendPointUi>(), viewModel.uiState.value.trend.points)
    }

    @Test
    fun `current quota update clears same account cached trend before reloading history`() = runTest {
        val trendLoader = RecordingHomeTrendHistoryLoader(
            pointsByAccountId = mapOf(
                "local-1" to listOf(
                    HomeTrendPointUi(capturedAt = now.minus(Duration.ofHours(1)), usageValue = 5.0),
                ),
            ),
        )
        val viewModel = HomeViewModel(trendHistoryLoader = trendLoader)
        viewModel.updateCurrentQuotaState(
            state = currentState(
                status = CurrentQuotaStatus.Fresh,
                freshness = CurrentQuotaFreshness.Fresh,
                snapshot = snapshot(fetchedAt = now.minus(Duration.ofMinutes(5))),
            ),
        )
        advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.trend.points.size)
        trendLoader.pointsByAccountId = emptyMap()

        viewModel.updateCurrentQuotaState(
            state = currentState(
                status = CurrentQuotaStatus.Fresh,
                freshness = CurrentQuotaFreshness.Fresh,
                snapshot = snapshot(fetchedAt = now.minus(Duration.ofMinutes(1))),
            ),
        )

        assertEquals(emptyList<HomeTrendPointUi>(), viewModel.uiState.value.trend.points)
    }

    @Test
    fun `loading current state maps persisted current quota state`() = runTest {
        val viewModel = HomeViewModel(
            currentQuotaStateLoader = FakeHomeCurrentQuotaStateLoader(
                currentState(
                    status = CurrentQuotaStatus.Fresh,
                    freshness = CurrentQuotaFreshness.Fresh,
                    snapshot = snapshot(fetchedAt = now.minus(Duration.ofMinutes(5))),
                ),
            ),
            appOpenRefreshUseCase = FakeHomeAppOpenRefreshUseCase(
                currentState(
                    status = CurrentQuotaStatus.Fresh,
                    freshness = CurrentQuotaFreshness.Fresh,
                    snapshot = snapshot(fetchedAt = now.minus(Duration.ofMinutes(5))),
                ),
            ),
        )

        viewModel.loadCurrentState()
        advanceUntilIdle()

        assertEquals(HomeContentStatus.Fresh, viewModel.uiState.value.contentStatus)
        assertEquals(38, viewModel.uiState.value.fiveHourCard?.percent)
    }

    @Test
    fun `app open load keeps persisted quota visible while refresh is in flight`() = runTest {
        val persistedState = currentState(
            status = CurrentQuotaStatus.Fresh,
            freshness = CurrentQuotaFreshness.Fresh,
            snapshot = snapshot(fetchedAt = now.minus(Duration.ofMinutes(20))),
        )
        val refreshedState = currentState(
            status = CurrentQuotaStatus.Fresh,
            freshness = CurrentQuotaFreshness.Fresh,
            snapshot = snapshot(fetchedAt = now.minus(Duration.ofMinutes(1))),
        )
        val appOpenRefreshUseCase = DeferredHomeAppOpenRefreshUseCase()
        val viewModel = HomeViewModel(
            currentQuotaStateLoader = FakeHomeCurrentQuotaStateLoader(persistedState),
            appOpenRefreshUseCase = appOpenRefreshUseCase,
        )

        viewModel.loadCurrentState()
        advanceUntilIdle()

        assertEquals(HomeContentStatus.Fresh, viewModel.uiState.value.contentStatus)
        assertTrue(viewModel.uiState.value.isRefreshing)
        assertEquals(now.minus(Duration.ofMinutes(20)), viewModel.uiState.value.refresh.lastSuccessfulRefreshAt)
        assertEquals(38, viewModel.uiState.value.fiveHourCard?.percent)

        appOpenRefreshUseCase.complete(refreshedState)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isRefreshing)
        assertEquals(now.minus(Duration.ofMinutes(1)), viewModel.uiState.value.refresh.lastSuccessfulRefreshAt)
        assertEquals(0, viewModel.uiState.value.manualRefreshSuccessCount)
    }

    @Test
    fun `manual refresh exposes loading state then maps refreshed quota state`() = runTest {
        val viewModel = HomeViewModel(
            currentQuotaStateLoader = FakeHomeCurrentQuotaStateLoader(
                currentState(
                    status = CurrentQuotaStatus.Fresh,
                    freshness = CurrentQuotaFreshness.Fresh,
                    snapshot = snapshot(fetchedAt = now.minus(Duration.ofMinutes(20))),
                ),
            ),
            appOpenRefreshUseCase = FakeHomeAppOpenRefreshUseCase(
                currentState(
                    status = CurrentQuotaStatus.Fresh,
                    freshness = CurrentQuotaFreshness.Fresh,
                    snapshot = snapshot(fetchedAt = now.minus(Duration.ofMinutes(20))),
                ),
            ),
            refreshUseCase = FakeHomeRefreshUseCase(
                currentState(
                    status = CurrentQuotaStatus.Fresh,
                    freshness = CurrentQuotaFreshness.Fresh,
                    snapshot = snapshot(fetchedAt = now.minus(Duration.ofMinutes(1))),
                ),
            ),
        )
        viewModel.loadCurrentState()
        advanceUntilIdle()

        viewModel.refreshNow()
        assertTrue(viewModel.uiState.value.isRefreshing)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isRefreshing)
        assertEquals(now.minus(Duration.ofMinutes(1)), viewModel.uiState.value.refresh.lastSuccessfulRefreshAt)
        assertEquals(1, viewModel.uiState.value.manualRefreshSuccessCount)
    }

    @Test
    fun `manual refresh propagates refresh use case infrastructure exceptions`() {
        try {
            runTest {
                val viewModel = HomeViewModel(
                    currentQuotaStateLoader = FakeHomeCurrentQuotaStateLoader(
                        currentState(
                            status = CurrentQuotaStatus.Fresh,
                            freshness = CurrentQuotaFreshness.Fresh,
                            snapshot = snapshot(fetchedAt = now.minus(Duration.ofMinutes(20))),
                        ),
                    ),
                    appOpenRefreshUseCase = FakeHomeAppOpenRefreshUseCase(
                        currentState(
                            status = CurrentQuotaStatus.Fresh,
                            freshness = CurrentQuotaFreshness.Fresh,
                            snapshot = snapshot(fetchedAt = now.minus(Duration.ofMinutes(20))),
                        ),
                    ),
                    refreshUseCase = ThrowingHomeRefreshUseCase(IllegalStateException("store failed")),
                )
                viewModel.loadCurrentState()
                advanceUntilIdle()

                viewModel.refreshNow()
                advanceUntilIdle()
            }
            fail("Expected infrastructure exception to propagate")
        } catch (exception: IllegalStateException) {
            assertEquals("store failed", exception.message)
        }
    }

    @Test
    fun `loading state with last known quota keeps cards and exposes progress state`() {
        val snapshot = snapshot(fetchedAt = now.minus(Duration.ofMinutes(5)))

        val uiState = viewModel.mapToUiState(
            state = currentState(
                status = CurrentQuotaStatus.Loading,
                freshness = CurrentQuotaFreshness.Fresh,
                snapshot = snapshot,
            ),
        )

        assertEquals(HomeContentStatus.Loading, uiState.contentStatus)
        assertEquals(R.string.home_state_loading_title, uiState.statusTitleResId)
        assertEquals(38, uiState.fiveHourCard?.percent)
        assertEquals(59, uiState.weeklyCard?.percent)
        assertTrue(uiState.isRefreshing)
        assertEquals(R.string.home_refreshing, uiState.refresh.buttonTextResId)
        assertNull(uiState.loading)
    }

    @Test
    fun `loading state without quota exposes loading card instead of quota cards`() {
        val uiState = viewModel.mapToUiState(
            state = currentState(
                status = CurrentQuotaStatus.Loading,
                freshness = CurrentQuotaFreshness.Unknown,
                snapshot = null,
            ),
        )

        assertEquals(HomeContentStatus.Loading, uiState.contentStatus)
        assertNull(uiState.fiveHourCard)
        assertNull(uiState.weeklyCard)
        assertTrue(uiState.isRefreshing)
        assertEquals(R.string.home_loading_card_title, uiState.loading?.titleResId)
        assertEquals(R.string.home_loading_card_description, uiState.loading?.descriptionResId)
    }

    @Test
    fun `possibly stale and expired states keep last known quota with stale copy`() {
        val stale = viewModel.mapToUiState(
            state = currentState(
                status = CurrentQuotaStatus.PossiblyStale,
                freshness = CurrentQuotaFreshness.PossiblyStale,
                snapshot = snapshot(fetchedAt = now.minus(Duration.ofMinutes(45))),
            ),
        )
        val expired = viewModel.mapToUiState(
            state = currentState(
                status = CurrentQuotaStatus.Expired,
                freshness = CurrentQuotaFreshness.Expired,
                snapshot = snapshot(fetchedAt = now.minus(Duration.ofHours(3))),
            ),
        )

        assertEquals(HomeContentStatus.PossiblyStale, stale.contentStatus)
        assertEquals(R.string.home_state_possibly_stale_title, stale.statusTitleResId)
        assertEquals(38, stale.fiveHourCard?.percent)
        assertEquals(HomeContentStatus.Expired, expired.contentStatus)
        assertEquals(R.string.home_state_expired_title, expired.statusTitleResId)
        assertEquals(59, expired.weeklyCard?.percent)
    }

    @Test
    fun `auth required state keeps last known quota and asks for reauth`() {
        val uiState = viewModel.mapToUiState(
            state = currentState(
                status = CurrentQuotaStatus.AuthRequired,
                freshness = CurrentQuotaFreshness.Fresh,
                snapshot = snapshot(fetchedAt = now.minus(Duration.ofMinutes(10))),
                error = QuotaError.AuthRequired(httpStatus = 401, diagnosticsDigest = "safe"),
            ),
        )

        assertEquals(HomeContentStatus.AuthRequired, uiState.contentStatus)
        assertEquals(R.string.home_state_auth_required_title, uiState.statusTitleResId)
        assertEquals(R.string.error_auth_required, uiState.errorMessageResId)
        assertEquals(38, uiState.fiveHourCard?.percent)
        assertEquals(HomeActionKind.LoginToCodex, uiState.primaryAction?.kind)
        assertNull(uiState.secondaryAction)
    }

    @Test
    fun `error with last known good state preserves quota and exposes safe error copy`() {
        val uiState = viewModel.mapToUiState(
            state = currentState(
                status = CurrentQuotaStatus.ErrorWithLastKnownGood,
                freshness = CurrentQuotaFreshness.Fresh,
                snapshot = snapshot(fetchedAt = now.minus(Duration.ofMinutes(10))),
                latestAttempt = failedAttempt(),
                error = QuotaError.Network(diagnosticsDigest = "safe"),
            ),
            isRefreshing = true,
        )

        assertEquals(HomeContentStatus.ErrorWithLastKnownGood, uiState.contentStatus)
        assertEquals(R.string.home_state_error_lkg_title, uiState.statusTitleResId)
        assertEquals(R.string.error_network, uiState.errorMessageResId)
        assertEquals(38, uiState.fiveHourCard?.percent)
        assertEquals(failedAttempt().finishedAt, uiState.refresh.lastAttemptFinishedAt)
        assertTrue(uiState.isRefreshing)
        assertEquals(R.string.home_refreshing, uiState.refresh.buttonTextResId)
    }

    private fun currentState(
        status: CurrentQuotaStatus,
        freshness: CurrentQuotaFreshness,
        account: ProviderAccount? = account(),
        snapshot: QuotaSnapshot?,
        primaryWindow: QuotaWindow? = snapshot?.windows?.firstOrNull { it.windowId.value == "five_hour" },
        latestAttempt: RefreshAttempt? = null,
        error: QuotaError? = null,
    ): CurrentQuotaState =
        CurrentQuotaState(
            status = status,
            freshness = freshness,
            account = account,
            snapshot = snapshot,
            latestAttempt = latestAttempt,
            primaryWindow = primaryWindow,
            secondaryWindows = snapshot?.windows.orEmpty().filterNot { it.windowId == primaryWindow?.windowId },
            primaryWindowCanAlert = true,
            error = error,
        )

    private fun account(
        status: AccountStatus = AccountStatus.Active,
        localAccountId: String = "local-1",
    ): ProviderAccount =
        ProviderAccount.createNew(
            localAccountId = LocalAccountId(localAccountId),
            providerId = ProviderId("codex"),
            providerAccountId = ProviderAccountId("acct-$localAccountId"),
            displayName = "Codex Main",
            now = Instant.parse("2026-05-23T09:00:00Z"),
        ).copy(status = status, lastSuccessfulRefreshAt = now.minus(Duration.ofMinutes(5)))

    private fun snapshot(
        fetchedAt: Instant,
        localAccountId: String = "local-1",
        planType: String? = "plus",
        credits: Credits? = null,
    ): QuotaSnapshot =
        QuotaSnapshot(
            snapshotId = SnapshotId("snapshot-1"),
            providerId = ProviderId("codex"),
            localAccountId = LocalAccountId(localAccountId),
            providerAccountId = ProviderAccountId("acct-$localAccountId"),
            fetchedAt = fetchedAt,
            source = QuotaSnapshotSource.ManualRefresh,
            planType = planType,
            windows = listOf(
                quotaWindow(
                    windowId = QuotaWindowId("five_hour"),
                    usedPercent = 62,
                    resetAt = Instant.parse("2026-05-23T12:00:00Z"),
                ),
                quotaWindow(
                    windowId = QuotaWindowId("weekly"),
                    usedPercent = 41,
                    resetAt = Instant.parse("2026-05-25T00:00:00Z"),
                ),
            ),
            credits = credits,
            responseDigest = "safe-digest",
        )

    private fun quotaWindow(
        windowId: QuotaWindowId,
        usedPercent: Int?,
        resetAt: Instant?,
    ): QuotaWindow =
        QuotaWindow(
            windowId = windowId,
            titleKey = "quota_window_${windowId.value}",
            usedPercent = usedPercent,
            resetAt = resetAt,
            limitWindowSeconds = 18_000,
            isPrimaryCandidate = true,
            availability = QuotaWindowAvailability.Available,
        )

    private fun weeklyWindow(usedPercent: Int?): QuotaWindow =
        quotaWindow(
            windowId = QuotaWindowId("weekly"),
            usedPercent = usedPercent,
            resetAt = Instant.parse("2026-05-25T00:00:00Z"),
        )

    private fun primaryWindowWithKind(displayKind: QuotaWindowDisplayKind): QuotaWindow =
        QuotaWindow(
            windowId = QuotaWindowId("metric_test_window"),
            titleKey = "quota_window_metric_test",
            usedPercent = null,
            resetAt = null,
            limitWindowSeconds = null,
            isPrimaryCandidate = true,
            availability = QuotaWindowAvailability.Available,
            displayKind = displayKind,
        )

    private fun failedAttempt(): RefreshAttempt =
        RefreshAttempt(
            attemptId = RefreshAttemptId("attempt-1"),
            providerId = ProviderId("codex"),
            localAccountId = LocalAccountId("local-1"),
            trigger = RefreshTrigger.Manual,
            startedAt = now.minus(Duration.ofSeconds(30)),
            finishedAt = now.minus(Duration.ofSeconds(20)),
            status = RefreshAttemptStatus.Failed,
            errorCode = "network",
            httpStatus = null,
            retryable = true,
            userActionRequired = false,
            diagnosticsDigest = "safe-digest",
        )

    private class FakeHomeCurrentQuotaStateLoader(
        private val state: CurrentQuotaState,
    ) : HomeCurrentQuotaStateLoader {
        override suspend fun loadCurrentState(): CurrentQuotaState = state
    }

    private class FakeHomeAppOpenRefreshUseCase(
        private val state: CurrentQuotaState,
    ) : HomeAppOpenRefreshUseCase {
        override suspend fun refreshForAppOpen(): CurrentQuotaState = state
    }

    private class DeferredHomeAppOpenRefreshUseCase : HomeAppOpenRefreshUseCase {
        private val deferred = CompletableDeferred<CurrentQuotaState>()

        override suspend fun refreshForAppOpen(): CurrentQuotaState = deferred.await()

        fun complete(state: CurrentQuotaState) {
            deferred.complete(state)
        }
    }

    private class RecordingHomeTrendHistoryLoader(
        private val points: List<HomeTrendPointUi> = emptyList(),
        var pointsByAccountId: Map<String, List<HomeTrendPointUi>> = emptyMap(),
    ) : HomeTrendHistoryLoader {
        val requests = mutableListOf<HomeTrendQuery>()

        override suspend fun loadTrend(accountId: LocalAccountId?, query: HomeTrendQuery): List<HomeTrendPointUi> {
            requests.add(query)
            return pointsByAccountId[accountId?.value] ?: points
        }
    }

    private class FakeHomeRefreshUseCase(
        private val state: CurrentQuotaState,
    ) : HomeRefreshUseCase {
        override suspend fun refreshCurrentState(): CurrentQuotaState = state
    }

    private class ThrowingHomeRefreshUseCase(
        private val exception: RuntimeException,
    ) : HomeRefreshUseCase {
        override suspend fun refreshCurrentState(): CurrentQuotaState {
            throw exception
        }
    }

    private class StaticNotificationPreferenceReader(
        private val preferences: NotificationPreferences,
    ) : NotificationPreferenceReader {
        override suspend fun notificationPreferences(): NotificationPreferences = preferences
    }
}
