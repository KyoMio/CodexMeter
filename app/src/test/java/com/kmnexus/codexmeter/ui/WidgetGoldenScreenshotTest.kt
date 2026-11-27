package com.kmnexus.codexmeter.ui

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.RoborazziComposeLocaleOption
import com.github.takahirom.roborazzi.RoborazziComposeOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.kmnexus.codexmeter.widget.WidgetClickTarget
import com.kmnexus.codexmeter.widget.WidgetField
import com.kmnexus.codexmeter.widget.WidgetQuotaState
import com.kmnexus.codexmeter.widget.WidgetQuotaStatus
import com.kmnexus.codexmeter.widget.WidgetQuotaTone
import java.time.Instant
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Golden screenshots for the Glance widget at typical sizes, zh-rCN + en.
 *
 * The real CodexMeterWidgetContent is composed through GlanceRemoteViews (the same pipeline the
 * androidTest on-device screenshot test uses), the inflated RemoteViews hierarchy is wrapped in
 * AndroidView, and roborazzi captures that — the entire chain stays JVM-fast while exercising the
 * real glance layout at real dp sizes. Fixtures are synthetic and sanitized.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class WidgetGoldenScreenshotTest {

    private enum class Locale(val tag: String, val suffix: String) {
        ZH("zh-rCN", "zh"),
        EN("en", "en"),
    }

    private val fixedNow: Instant = Instant.parse("2026-05-23T12:00:00Z")

    @androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
    private fun widgetGolden(
        name: String,
        locale: Locale,
        widthDp: Int,
        heightDp: Int,
        state: WidgetQuotaState,
    ) {
        captureRoboImage(
            filePath = "src/test/roborazzi/${name}_${locale.suffix}.png",
            roborazziComposeOptions = RoborazziComposeOptions.Builder()
                .addOption(RoborazziComposeLocaleOption(locale.tag))
                .build(),
        ) {
            androidx.compose.ui.viewinterop.AndroidView(
                factory = { viewContext ->
                    kotlinx.coroutines.runBlocking {
                        val size = androidx.compose.ui.unit.DpSize(widthDp.dp, heightDp.dp)
                        val remoteViews = androidx.glance.appwidget.GlanceRemoteViews()
                            .compose(viewContext, size) {
                                com.kmnexus.codexmeter.widget.CodexMeterWidgetContent(
                                    context = viewContext,
                                    state = state,
                                    widgetSize = size,
                                    layoutVariant = com.kmnexus.codexmeter.widget.WidgetLayoutVariant.fromSize(size),
                                )
                            }.remoteViews
                        remoteViews.apply(viewContext, android.widget.FrameLayout(viewContext))
                    }
                },
                modifier = Modifier.size(widthDp.dp, heightDp.dp),
            )
        }
    }

    private fun widgetPercentField(id: String, percent: Int, tone: WidgetQuotaTone) =
        WidgetField(
            windowId = id,
            isBalance = false,
            percent = percent,
            balanceAmount = null,
            balanceCurrency = null,
            resetAt = fixedNow.plusSeconds(5 * 3600),
            tone = tone,
        )

    private fun codexWidgetState() = WidgetQuotaState(
        status = WidgetQuotaStatus.Fresh,
        providerName = "Codex",
        providerId = "codex",
        localAccountId = "demo-local-1",
        accountName = "Demo Main",
        tone = WidgetQuotaTone.Success,
        clickTarget = WidgetClickTarget.Home,
        fields = listOf(
            widgetPercentField("five_hour", 58, WidgetQuotaTone.Success),
            widgetPercentField("weekly", 82, WidgetQuotaTone.Success),
        ),
    )

    @androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
    @Test
    fun widget_typicalSizes() {
        Locale.entries.forEach { locale ->
            widgetGolden("widget_4x2", locale, widthDp = 250, heightDp = 110, state = codexWidgetState())
            widgetGolden("widget_3x1", locale, widthDp = 180, heightDp = 50, state = codexWidgetState())
        }
    }
}
