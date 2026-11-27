package com.kmnexus.codexmeter.notification

import com.kmnexus.codexmeter.data.currency.ExchangeRateReader
import com.kmnexus.codexmeter.domain.currency.CurrencyPreferenceReader
import com.kmnexus.codexmeter.domain.currency.CurrencyPreferences
import com.kmnexus.codexmeter.domain.currency.ExchangeRates
import com.kmnexus.codexmeter.domain.currency.withConvertedBalance
import com.kmnexus.codexmeter.domain.model.QuotaWindowId
import com.kmnexus.codexmeter.domain.quota.CurrentQuotaState
import com.kmnexus.codexmeter.domain.settings.NotificationPreferenceReader
import com.kmnexus.codexmeter.refresh.CurrentQuotaStatePublisher
import com.kmnexus.codexmeter.refresh.PerAccountQuotaEffectPublisher
import java.time.Clock
import java.time.Instant

class CurrentQuotaNotificationPublisher(
    private val notificationSink: NotificationSink,
    private val alertStateStore: NotificationAlertStateStore,
    private val optionsReader: NotificationRequestOptionsReader,
    private val alertThresholdsReader: AlertThresholdsReader,
    private val orchestrator: NotificationOrchestrator = NotificationOrchestrator(),
    private val alertPolicy: AlertPolicy = AlertPolicy(),
    private val alertWindowPreferenceReader: QuotaAlertWindowPreferenceReader =
        PrimaryQuotaAlertWindowPreferenceReader,
    private val statusNotificationStateLoader: StatusNotificationStateLoader =
        PassthroughStatusNotificationStateLoader,
    private val accountErrorEventReader: AccountErrorEventReader = NoopAccountErrorEventReader,
    private val clock: Clock,
    private val currencyPreferenceReader: CurrencyPreferenceReader = NoopCurrencyPreferenceReader,
    private val exchangeRateReader: ExchangeRateReader = NoopExchangeRateReader,
) : CurrentQuotaStatePublisher, PerAccountQuotaEffectPublisher {
    override suspend fun publish(state: CurrentQuotaState) {
        val options = optionsReader.currentOptions()
        if (!options.notificationPermissionAvailable || !options.statusNotificationEnabled) {
            notificationSink.cancel(NotificationOrchestrator.STATUS_NOTIFICATION_ID)
        }
        val statusState = if (options.notificationPermissionAvailable && options.statusNotificationEnabled) {
            statusNotificationStateLoader.loadStatusNotificationState(refreshedState = state)
        } else {
            state
        }
        val statusRequests = orchestrator.buildRequests(
            state = statusState,
            options = options.copy(
                quotaAlertsEnabled = false,
                accountErrorsEnabled = false,
            ),
        )
        val accountErrorEvent = if (options.notificationPermissionAvailable && options.accountErrorsEnabled) {
            accountErrorEventReader.accountErrorEvent(state)
        } else {
            null
        }
        val accountErrorRequests = orchestrator.buildRequests(
            state = state,
            options = options.copy(
                statusNotificationEnabled = false,
                quotaAlertsEnabled = false,
            ),
            accountErrorEvent = accountErrorEvent,
        )

        statusRequests.forEach(notificationSink::post)
        accountErrorRequests.forEach(notificationSink::post)
    }

    /**
     * Per-account threshold alerts. Unlike the status notification — which presents THE selected
     * account — alerts belong to whichever account was refreshed: docs/SPEC.md states any
     * alert-enabled account window can trigger. This channel therefore fires on every refresh,
     * including background refreshes of non-selected accounts, and is intentionally NOT guarded by
     * the state epoch: a stale write-back can never happen here because nothing is overwritten,
     * alert de-duplication is already keyed per (account, window, resetAt, threshold).
     */
    override suspend fun publishAccountEffects(state: CurrentQuotaState) {
        val options = optionsReader.currentOptions()
        if (!options.notificationPermissionAvailable || !options.quotaAlertsEnabled) return
        val thresholds = alertThresholdsReader.currentThresholds()
        val convertedState = convertForDisplay(state)
        val alertEvents = alertPolicy.evaluate(
            state = convertedState,
            thresholds = thresholds,
            enabledWindowIds = alertWindowPreferenceReader.enabledWindowIds(convertedState),
        ).filterNot { alertStateStore.hasNotified(it.key) }
        if (alertEvents.isEmpty()) return
        val notifiedAt = clock.instant()
        alertEvents.forEach { event ->
            orchestrator.buildRequests(
                state = state,
                alertEvents = listOf(event),
                options = options.copy(
                    statusNotificationEnabled = false,
                    accountErrorsEnabled = false,
                ),
            ).firstOrNull { it.channelId == NotificationChannels.QUOTA_ALERTS_CHANNEL_ID }
                ?.let { request ->
                    notificationSink.post(request)
                    alertStateStore.markNotified(event = event, notifiedAt = notifiedAt)
                }
        }
    }

    private suspend fun convertForDisplay(state: CurrentQuotaState): CurrentQuotaState {
        val currency = currencyPreferenceReader.currencyPreferences()
        val rates = exchangeRateReader.currentRates()
        return if (rates != null && state.snapshot != null) {
            state.copy(
                snapshot = state.snapshot.copy(
                    windows = state.snapshot.windows.map {
                        it.withConvertedBalance(currency.targetCurrency, rates)
                    },
                ),
                primaryWindow = state.primaryWindow
                    ?.withConvertedBalance(currency.targetCurrency, rates),
            )
        } else {
            state
        }
    }
}

fun interface NotificationSink {
    fun post(request: NotificationRequest)

    fun cancel(notificationId: Int) = Unit
}

fun interface NotificationRequestOptionsReader {
    suspend fun currentOptions(): NotificationRequestOptions
}

class StaticNotificationRequestOptionsReader(
    private val options: NotificationRequestOptions,
) : NotificationRequestOptionsReader {
    override suspend fun currentOptions(): NotificationRequestOptions = options
}

fun interface AlertThresholdsReader {
    suspend fun currentThresholds(): AlertThresholds
}

class StaticAlertThresholdsReader(
    private val thresholds: AlertThresholds,
) : AlertThresholdsReader {
    override suspend fun currentThresholds(): AlertThresholds = thresholds
}

interface NotificationAlertStateStore {
    suspend fun hasNotified(key: AlertDedupeKey): Boolean

    suspend fun markNotified(event: QuotaAlertEvent, notifiedAt: Instant)
}

fun interface QuotaAlertWindowPreferenceReader {
    suspend fun enabledWindowIds(state: CurrentQuotaState): Set<QuotaWindowId>
}

object PrimaryQuotaAlertWindowPreferenceReader : QuotaAlertWindowPreferenceReader {
    override suspend fun enabledWindowIds(state: CurrentQuotaState): Set<QuotaWindowId> =
        state.primaryWindow?.windowId?.let(::setOf).orEmpty()
}

class NotificationPreferenceQuotaAlertWindowReader(
    private val notificationPreferenceReader: NotificationPreferenceReader,
) : QuotaAlertWindowPreferenceReader {
    override suspend fun enabledWindowIds(state: CurrentQuotaState): Set<QuotaWindowId> {
        val account = state.account ?: return emptySet()
        val preferences = notificationPreferenceReader.notificationPreferences()
        return state.snapshot?.windows.orEmpty()
            .map { it.windowId }
            .filter { windowId ->
                preferences.isQuotaAlertEnabled(
                    providerId = account.providerId,
                    localAccountId = account.localAccountId,
                    windowId = windowId,
                )
            }
            .toSet()
    }
}

fun interface StatusNotificationStateLoader {
    suspend fun loadStatusNotificationState(refreshedState: CurrentQuotaState): CurrentQuotaState
}

object PassthroughStatusNotificationStateLoader : StatusNotificationStateLoader {
    override suspend fun loadStatusNotificationState(refreshedState: CurrentQuotaState): CurrentQuotaState =
        refreshedState
}

fun interface AccountErrorEventReader {
    suspend fun accountErrorEvent(state: CurrentQuotaState): AccountErrorNotificationEvent?
}

object NoopAccountErrorEventReader : AccountErrorEventReader {
    override suspend fun accountErrorEvent(state: CurrentQuotaState): AccountErrorNotificationEvent? = null
}

private object NoopCurrencyPreferenceReader : CurrencyPreferenceReader {
    override suspend fun currencyPreferences(): CurrencyPreferences = CurrencyPreferences()
}

private object NoopExchangeRateReader : ExchangeRateReader {
    override suspend fun currentRates(): ExchangeRates? = null
}
