package com.kmnexus.codexmeter.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.kmnexus.codexmeter.domain.model.LocalAccountId
import com.kmnexus.codexmeter.domain.model.ProviderId
import com.kmnexus.codexmeter.domain.model.QuotaWindowId
import com.kmnexus.codexmeter.domain.settings.AccountQuotaAlertKey
import com.kmnexus.codexmeter.domain.settings.CAUTION_THRESHOLD_RANGE
import com.kmnexus.codexmeter.domain.settings.DEFAULT_BALANCE_CAUTION_THRESHOLD
import com.kmnexus.codexmeter.domain.settings.DEFAULT_BALANCE_WARNING_THRESHOLD
import com.kmnexus.codexmeter.domain.settings.DEFAULT_CAUTION_THRESHOLD
import com.kmnexus.codexmeter.domain.settings.DEFAULT_NOTIFICATION_WINDOW_ID
import com.kmnexus.codexmeter.domain.settings.DEFAULT_REFRESH_INTERVAL_MINUTES
import com.kmnexus.codexmeter.domain.settings.DEFAULT_WARNING_THRESHOLD
import com.kmnexus.codexmeter.domain.settings.LIMIT_THRESHOLD
import com.kmnexus.codexmeter.domain.settings.NotificationAccountSelection
import com.kmnexus.codexmeter.domain.settings.NotificationPreferenceStore
import com.kmnexus.codexmeter.domain.settings.NotificationPreferences
import com.kmnexus.codexmeter.domain.settings.WARNING_THRESHOLD_RANGE
import java.io.File
import kotlinx.coroutines.flow.first

class NotificationPreferencesDataStore(
    internal val dataStore: DataStore<Preferences>,
) : NotificationPreferenceStore {
    override suspend fun notificationPreferences(): NotificationPreferences {
        val preferences = dataStore.data.first().let { stored ->
            if (stored[Keys.THRESHOLD_SEMANTICS_VERSION] == CURRENT_THRESHOLD_SEMANTICS_VERSION) {
                stored
            } else {
                dataStore.edit { it.migrateThresholdSemantics() }
            }
        }
        val caution = preferences[Keys.CAUTION_THRESHOLD]
            ?.coerceIn(CAUTION_THRESHOLD_RANGE)
            ?: DEFAULT_CAUTION_THRESHOLD
        val warning = preferences[Keys.WARNING_THRESHOLD]
            ?.takeIf { it in WARNING_THRESHOLD_RANGE && it < caution }
            ?: DEFAULT_WARNING_THRESHOLD.coerceAtMost(caution - 1)

        return NotificationPreferences(
            statusNotificationEnabled = preferences[Keys.STATUS_NOTIFICATION_ENABLED] ?: true,
            quotaAlertsEnabled = preferences[Keys.QUOTA_ALERTS_ENABLED] ?: true,
            accountErrorsEnabled = preferences[Keys.ACCOUNT_ERRORS_ENABLED] ?: true,
            cautionThreshold = caution,
            warningThreshold = warning,
            limitThreshold = LIMIT_THRESHOLD,
            balanceCautionThreshold = (preferences[Keys.BALANCE_CAUTION_THRESHOLD] ?: DEFAULT_BALANCE_CAUTION_THRESHOLD.toFloat()).toDouble(),
            balanceWarningThreshold = (preferences[Keys.BALANCE_WARNING_THRESHOLD] ?: DEFAULT_BALANCE_WARNING_THRESHOLD.toFloat()).toDouble(),
            backgroundRefreshIntervalMinutes = preferences[Keys.BACKGROUND_REFRESH_INTERVAL_MINUTES]
                ?: DEFAULT_REFRESH_INTERVAL_MINUTES,
            persistentNotificationAccount = preferences.toPersistentNotificationAccount(),
            persistentNotificationWindowId = preferences[Keys.PERSISTENT_NOTIFICATION_WINDOW_ID]
                ?.takeIf { it.isNotBlank() }
                ?.let(::QuotaWindowId)
                ?: DEFAULT_NOTIFICATION_WINDOW_ID,
            accountQuotaAlertSettings = preferences[Keys.ACCOUNT_QUOTA_ALERT_SETTINGS]
                .orEmpty()
                .mapNotNull(::decodeAccountQuotaAlertSetting)
                .toMap(),
        )
    }

    override suspend fun updateNotificationPreferences(preferences: NotificationPreferences) {
        dataStore.edit { stored ->
            stored[Keys.STATUS_NOTIFICATION_ENABLED] = preferences.statusNotificationEnabled
            stored[Keys.QUOTA_ALERTS_ENABLED] = preferences.quotaAlertsEnabled
            stored[Keys.ACCOUNT_ERRORS_ENABLED] = preferences.accountErrorsEnabled
            stored[Keys.CAUTION_THRESHOLD] = preferences.cautionThreshold
            stored[Keys.WARNING_THRESHOLD] = preferences.warningThreshold
            stored[Keys.BALANCE_CAUTION_THRESHOLD] = preferences.balanceCautionThreshold.toFloat()
            stored[Keys.BALANCE_WARNING_THRESHOLD] = preferences.balanceWarningThreshold.toFloat()
            stored[Keys.BACKGROUND_REFRESH_INTERVAL_MINUTES] = preferences.backgroundRefreshIntervalMinutes
            stored[Keys.THRESHOLD_SEMANTICS_VERSION] = CURRENT_THRESHOLD_SEMANTICS_VERSION
            val notificationAccount = preferences.persistentNotificationAccount
            if (notificationAccount == null) {
                stored.remove(Keys.PERSISTENT_NOTIFICATION_PROVIDER_ID)
                stored.remove(Keys.PERSISTENT_NOTIFICATION_LOCAL_ACCOUNT_ID)
            } else {
                stored[Keys.PERSISTENT_NOTIFICATION_PROVIDER_ID] = notificationAccount.providerId.value
                stored[Keys.PERSISTENT_NOTIFICATION_LOCAL_ACCOUNT_ID] = notificationAccount.localAccountId.value
            }
            stored[Keys.PERSISTENT_NOTIFICATION_WINDOW_ID] = preferences.persistentNotificationWindowId.value
            val encodedAlertSettings = preferences.accountQuotaAlertSettings.map(::encodeAccountQuotaAlertSetting).toSet()
            if (encodedAlertSettings.isEmpty()) {
                stored.remove(Keys.ACCOUNT_QUOTA_ALERT_SETTINGS)
            } else {
                stored[Keys.ACCOUNT_QUOTA_ALERT_SETTINGS] = encodedAlertSettings
            }
        }
    }

    private fun MutablePreferences.migrateThresholdSemantics() {
        val storedCaution = this[Keys.CAUTION_THRESHOLD]
        val storedWarning = this[Keys.WARNING_THRESHOLD]
        if (storedCaution != null || storedWarning != null) {
            val (caution, warning) = if (
                storedCaution != null &&
                storedWarning != null &&
                storedCaution < storedWarning
            ) {
                remainingThresholdsFromLegacyUsedThresholds(
                    caution = storedCaution,
                    warning = storedWarning,
                )
            } else {
                normalizedRemainingThresholds(
                    caution = storedCaution,
                    warning = storedWarning,
                )
            }
            this[Keys.CAUTION_THRESHOLD] = caution
            this[Keys.WARNING_THRESHOLD] = warning
        }
        this[Keys.THRESHOLD_SEMANTICS_VERSION] = CURRENT_THRESHOLD_SEMANTICS_VERSION
    }

    private fun remainingThresholdsFromLegacyUsedThresholds(caution: Int, warning: Int): Pair<Int, Int> =
        normalizedRemainingThresholds(
            caution = 100 - caution,
            warning = 100 - warning,
        )

    private fun normalizedRemainingThresholds(caution: Int?, warning: Int?): Pair<Int, Int> {
        val safeCaution = caution
            ?.coerceIn(CAUTION_THRESHOLD_RANGE)
            ?: DEFAULT_CAUTION_THRESHOLD
        val safeWarning = warning
            ?.takeIf { it in WARNING_THRESHOLD_RANGE && it < safeCaution }
            ?: DEFAULT_WARNING_THRESHOLD.coerceAtMost(safeCaution - 1)
        return safeCaution to safeWarning
    }

    private fun Preferences.toPersistentNotificationAccount(): NotificationAccountSelection? {
        val providerId = this[Keys.PERSISTENT_NOTIFICATION_PROVIDER_ID]?.takeIf { it.isNotBlank() } ?: return null
        val localAccountId = this[Keys.PERSISTENT_NOTIFICATION_LOCAL_ACCOUNT_ID]?.takeIf { it.isNotBlank() } ?: return null
        return NotificationAccountSelection(
            providerId = ProviderId(providerId),
            localAccountId = LocalAccountId(localAccountId),
        )
    }

    private fun encodeAccountQuotaAlertSetting(entry: Map.Entry<AccountQuotaAlertKey, Boolean>): String =
        listOf(
            entry.key.providerId.value,
            entry.key.localAccountId.value,
            entry.key.windowId.value,
            if (entry.value) "1" else "0",
        ).joinToString(separator = ACCOUNT_ALERT_SEPARATOR)

    private fun decodeAccountQuotaAlertSetting(encoded: String): Pair<AccountQuotaAlertKey, Boolean>? {
        val parts = encoded.split(ACCOUNT_ALERT_SEPARATOR)
        if (parts.size != ACCOUNT_ALERT_FIELD_COUNT || parts.any { it.isBlank() }) return null
        val enabled = when (parts[3]) {
            "1" -> true
            "0" -> false
            else -> return null
        }
        return AccountQuotaAlertKey(
            providerId = ProviderId(parts[0]),
            localAccountId = LocalAccountId(parts[1]),
            windowId = QuotaWindowId(parts[2]),
        ) to enabled
    }

    companion object {
        private const val CURRENT_THRESHOLD_SEMANTICS_VERSION = 2
        private const val ACCOUNT_ALERT_SEPARATOR = "\u001F"
        private const val ACCOUNT_ALERT_FIELD_COUNT = 4

        fun create(file: File): NotificationPreferencesDataStore =
            NotificationPreferencesDataStore(
                PreferenceDataStoreFactory.create(
                    produceFile = { file },
                ),
            )
    }

    object Keys {
        val STATUS_NOTIFICATION_ENABLED = booleanPreferencesKey("status_notification_enabled")
        val QUOTA_ALERTS_ENABLED = booleanPreferencesKey("quota_alerts_enabled")
        val ACCOUNT_ERRORS_ENABLED = booleanPreferencesKey("account_errors_enabled")
        val CAUTION_THRESHOLD = intPreferencesKey("notification_caution_threshold")
        val WARNING_THRESHOLD = intPreferencesKey("notification_warning_threshold")
        val BALANCE_CAUTION_THRESHOLD = floatPreferencesKey("balance_caution_threshold")
        val BALANCE_WARNING_THRESHOLD = floatPreferencesKey("balance_warning_threshold")
        val BACKGROUND_REFRESH_INTERVAL_MINUTES = intPreferencesKey("background_refresh_interval_minutes")
        val THRESHOLD_SEMANTICS_VERSION = intPreferencesKey("notification_threshold_semantics_version")
        val PERSISTENT_NOTIFICATION_PROVIDER_ID = stringPreferencesKey("persistent_notification_provider_id")
        val PERSISTENT_NOTIFICATION_LOCAL_ACCOUNT_ID = stringPreferencesKey("persistent_notification_local_account_id")
        val PERSISTENT_NOTIFICATION_WINDOW_ID = stringPreferencesKey("persistent_notification_window_id")
        val ACCOUNT_QUOTA_ALERT_SETTINGS = stringSetPreferencesKey("account_quota_alert_settings")
    }
}
