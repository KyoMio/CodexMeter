package com.kmnexus.codexmeter.widget

import android.content.Context
import android.content.Intent
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class CodexMeterWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CodexMeterWidget()

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        // Redraw the widget when the system night mode (and therefore the resolved glass appearance
        // in SYSTEM theme mode) changes. Known limitation: if the app process is dead the broadcast
        // is missed and the widget instead refreshes on the next periodic refresh / app open.
        if (intent.action == Intent.ACTION_CONFIGURATION_CHANGED) {
            val appContext = context.applicationContext
            val pendingResult = goAsync()
            CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
                try {
                    CodexMeterWidget().updateAll(appContext)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}
