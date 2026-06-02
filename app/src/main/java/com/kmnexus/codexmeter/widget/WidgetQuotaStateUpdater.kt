package com.kmnexus.codexmeter.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.updateAll
import com.kmnexus.codexmeter.domain.quota.CurrentQuotaState
import com.kmnexus.codexmeter.domain.settings.NotificationPreferenceReader
import com.kmnexus.codexmeter.domain.settings.NotificationPreferences
import com.kmnexus.codexmeter.refresh.CurrentQuotaStatePublisher

class WidgetQuotaStateUpdater(
    context: Context,
    private val factory: WidgetQuotaStateFactory = WidgetQuotaStateFactory(),
    private val widget: CodexMeterWidget = CodexMeterWidget(),
    private val notificationPreferenceReader: NotificationPreferenceReader =
        DefaultWidgetNotificationPreferenceReader,
    private val widgetQuotaStateLoader: WidgetQuotaStateLoader? = null,
) : CurrentQuotaStatePublisher {
    private val appContext = context.applicationContext

    override suspend fun publish(state: CurrentQuotaState) {
        val notificationPreferences = notificationPreferenceReader.notificationPreferences()
        val manager = GlanceAppWidgetManager(appContext)
        manager.getGlanceIds(CodexMeterWidget::class.java).forEach { glanceId ->
            updateAppWidgetState(appContext, glanceId) { preferences ->
                val configuration = preferences.toWidgetQuotaConfiguration()
                val configuredWidgetState = widgetQuotaStateLoader?.loadWidgetQuotaState(configuration)
                    ?: factory.create(
                        state,
                        notificationPreferences,
                        configuration.selectedWindowIds,
                    )
                preferences.writeWidgetQuotaState(configuredWidgetState)
            }
        }
        widget.updateAll(appContext)
    }
}

private object DefaultWidgetNotificationPreferenceReader : NotificationPreferenceReader {
    override suspend fun notificationPreferences(): NotificationPreferences = NotificationPreferences()
}
