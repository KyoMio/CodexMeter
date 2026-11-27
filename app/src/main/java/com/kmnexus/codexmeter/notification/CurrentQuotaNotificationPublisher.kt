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
    /**
     * Updates or cancels the single persistent status notification (`STATUS_CHANNEL_ID`).
     * Per-account effects (quota alerts and account error notifications) belong in
     * [publishAccountEffects] so refreshes of non-selected or non-status-bound accounts never lose
     * alerts or error notifications under the status epoch guard.
     */
    override suspend fun publish(state: CurrentQuotaState) {
        val options = optionsReader.currentOptions()
        if (!options.notificationPermissionAvailable || !options.statusNotificationEnabled) {
            notificationSink.cancel(NotificationOrchestrator.STATUS_NOTIFICATION_ID)
            return
        }
        val statusState = statusNotificationStateLoader.loadStatusNotificationState(refreshedState = state)
        val statusRequests = orchestrator.buildRequests(
            state = statusState,
            options = options.copy(
                quotaAlertsEnabled = false,
                accountErrorsEnabled = false,
            ),
        )
        statusRequests.forEach(notificationSink::post)
    }

    /**
     * Per-account notification effects: quota threshold alerts and account error notifications.
     * Fires for every refreshed account regardless of which account is selected on Home or pinned
     * to the persistent status notification.
     */
    override suspend fun publishAccountEffects(state: CurrentQuotaState) {
        val options = optionsReader.currentOptions()
        if (!options.notificationPermissionAvailable) return
        if (options.quotaAlertsEnabled) {
            postQuotaAlerts(state = state, options = options)
        }
        if (options.accountErrorsEnabled) {
            postAccountErrors(state = state, options = options)
        }
    }

    private suspend fun postQuotaAlerts(
        state: CurrentQuotaState,
        options: NotificationRequestOptions,
    ) {
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

    private suspend fun postAccountErrors(
        state: CurrentQuotaState,
        options: NotificationRequestOptions,
    ) {
        val accountErrorEvent = accountErrorEventReader.accountErrorEvent(state)
        val accountErrorRequests = orchestrator.buildRequests(
            state = state,
            options = options.copy(
                statusNotificationEnabled = false,
                quotaAlertsEnabled = false,
            ),
            accountErrorEvent = accountErrorEvent,
        )
        accountErrorRequests.forEach(notificationSink::post)
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
