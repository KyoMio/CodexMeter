package com.kmnexus.codexmeter.app

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import com.kmnexus.codexmeter.widget.CodexMeterWidget

fun interface WidgetCountReader {
    suspend fun configuredWidgetCount(): Int?
}

internal class GlanceWidgetCountReader(
    context: Context,
) : WidgetCountReader {
    private val appContext = context.applicationContext

    override suspend fun configuredWidgetCount(): Int? =
        runCatching {
            GlanceAppWidgetManager(appContext).getGlanceIds(CodexMeterWidget::class.java).size
        }.getOrNull()
}
