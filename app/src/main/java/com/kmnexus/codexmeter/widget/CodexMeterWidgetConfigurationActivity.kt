package com.kmnexus.codexmeter.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.datastore.preferences.core.Preferences
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import com.kmnexus.codexmeter.CodexMeterApp
import com.kmnexus.codexmeter.domain.model.AccountStatus
import com.kmnexus.codexmeter.domain.quota.QuotaWindowAvailability
import com.kmnexus.codexmeter.ui.quota.quotaWindowLabelRes
import com.kmnexus.codexmeter.ui.theme.CodexMeterTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CodexMeterWidgetConfigurationActivity : ComponentActivity() {
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(Activity.RESULT_CANCELED)
        configureDialogWindow()

        val appWidgetId = intent?.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        setContent {
            CodexMeterTheme {
                WidgetConfigurationRoute(
                    appWidgetId = appWidgetId,
                    loadState = { loadConfigurationState(appWidgetId) },
                    onCancel = { finish() },
                    onSave = { account, windowIds -> saveConfiguration(appWidgetId, account, windowIds) },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        window?.setLayout(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
        )
    }

    override fun onDestroy() {
        activityScope.cancel()
        super.onDestroy()
    }

    private fun configureDialogWindow() {
        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window?.setDimAmount(WIDGET_CONFIG_DIM_AMOUNT)
    }

    private suspend fun loadConfigurationState(appWidgetId: Int): WidgetConfigurationScreenState =
        withContext(Dispatchers.IO) {
            val app = application as CodexMeterApp
            val glanceId = GlanceAppWidgetManager(this@CodexMeterWidgetConfigurationActivity)
                .getGlanceIdBy(appWidgetId)
            val preferences = getAppWidgetState<Preferences>(
                context = this@CodexMeterWidgetConfigurationActivity,
                definition = PreferencesGlanceStateDefinition,
                glanceId = glanceId,
            )
            val configuration = preferences.toWidgetQuotaConfiguration()
            val accounts = app.accountListUseCase.loadAccounts()
            val selectableAccounts = accounts.accounts.filterNot { it.status == AccountStatus.Deleted }
            val selectedAccount = selectableAccounts.firstOrNull {
                configuration.providerId == it.providerId.value &&
                    configuration.localAccountId == it.localAccountId.value
            } ?: selectableAccounts.firstOrNull { it.localAccountId == accounts.currentAccountId }
                ?: selectableAccounts.firstOrNull()

            val windowOptionsByAccount = selectableAccounts.associate { account ->
                val options = accounts.latestQuotaSnapshots[account.localAccountId]?.windows
                    ?.filter { it.availability == QuotaWindowAvailability.Available }
                    ?.map { WidgetWindowOption(it.windowId.value, quotaWindowLabelRes(it.windowId.value)) }
                    ?.distinctBy { it.windowId }
                    .orEmpty()
                account.localAccountId.value to options
            }

            val defaultWindowId = app.primaryQuotaWindowPreferenceStore.primaryQuotaWindowId().value
            val available = windowOptionsByAccount[selectedAccount?.localAccountId?.value].orEmpty().map { it.windowId }
            val initialSelection = retainAvailableWindowIds(configuration.selectedWindowIds, available)
                .ifEmpty {
                    // 默认勾选 app 主窗口；不可用则勾第一个可用窗口。
                    listOfNotNull(available.firstOrNull { it == defaultWindowId } ?: available.firstOrNull())
                }

            WidgetConfigurationScreenState(
                accounts = selectableAccounts,
                selectedProviderId = selectedAccount?.providerId?.value,
                selectedLocalAccountId = selectedAccount?.localAccountId?.value,
                selectedWindowIds = initialSelection,
                defaultWindowId = defaultWindowId,
                currentAccountId = accounts.currentAccountId?.value,
                windowOptionsByAccount = windowOptionsByAccount,
            )
        }

    private fun saveConfiguration(
        appWidgetId: Int,
        selectedAccount: WidgetConfigurationAccountSelection?,
        selectedWindowIds: List<String>,
    ) {
        activityScope.launch {
            val app = application as CodexMeterApp
            val glanceId = GlanceAppWidgetManager(this@CodexMeterWidgetConfigurationActivity)
                .getGlanceIdBy(appWidgetId)
            val configuration = WidgetQuotaConfiguration(
                providerId = selectedAccount?.providerId,
                localAccountId = selectedAccount?.localAccountId,
                selectedWindowIds = selectedWindowIds,
            )
            val widgetState = withContext(Dispatchers.IO) {
                app.widgetQuotaStateLoader.loadWidgetQuotaState(configuration)
            }
            updateAppWidgetState(this@CodexMeterWidgetConfigurationActivity, glanceId) { preferences ->
                preferences.writeWidgetQuotaConfiguration(configuration)
                preferences.writeWidgetQuotaState(widgetState)
            }
            CodexMeterWidget().update(this@CodexMeterWidgetConfigurationActivity, glanceId)
            val result = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            setResult(Activity.RESULT_OK, result)
            finish()
        }
    }

    private companion object {
        const val WIDGET_CONFIG_DIM_AMOUNT = 0.32f
    }
}
