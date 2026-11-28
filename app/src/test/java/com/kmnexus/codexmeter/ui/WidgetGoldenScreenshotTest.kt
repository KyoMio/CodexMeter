package com.kmnexus.codexmeter.ui

import android.widget.FrameLayout
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziComposeLocaleOption
import com.github.takahirom.roborazzi.RoborazziComposeOptions
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.kmnexus.codexmeter.domain.model.ProviderId
import com.kmnexus.codexmeter.providers.ProviderRegistry
import com.kmnexus.codexmeter.widget.CodexMeterWidgetContent
import com.kmnexus.codexmeter.widget.WidgetClickTarget
import com.kmnexus.codexmeter.widget.WidgetField
import com.kmnexus.codexmeter.widget.WidgetLayoutVariant
import com.kmnexus.codexmeter.widget.WidgetQuotaState
import com.kmnexus.codexmeter.widget.WidgetQuotaStatus
import com.kmnexus.codexmeter.widget.WidgetQuotaTone
import java.time.Instant
import java.util.Locale as JavaLocale
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Golden screenshots for the Glance widget at real cell sizes, zh-rCN + en.
 *
 * The real [CodexMeterWidgetContent] is composed through [GlanceRemoteViews] and captured at
 * exact dp dimensions matching launcher widget spans with real window durations and icons.
 */
@OptIn(ExperimentalRoborazziApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class WidgetGoldenScreenshotTest {

    private enum class Locale(
        val tag: String,
        val suffix: String,
        val javaLocale: JavaLocale,
    ) {
        ZH("zh-rCN", "zh", JavaLocale.SIMPLIFIED_CHINESE),
        EN("en", "en", JavaLocale.US),
    }

    private val fixedNow: Instant = Instant.parse("2026-05-23T12:00:00Z")
    private val strictCompareOptions = RoborazziOptions(
        compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.0f),
    )

    @ExperimentalGlanceRemoteViewsApi
    private fun widgetGolden(
        name: String,
        locale: Locale,
        widthDp: Int,
        heightDp: Int,
        expectedVariant: WidgetLayoutVariant,
        state: WidgetQuotaState,
    ) {
        val size = DpSize(widthDp.dp, heightDp.dp)
        val layoutVariant = WidgetLayoutVariant.fromSize(size)
        assertEquals(expectedVariant, layoutVariant)

        pinGoldenEnvironment(locale.javaLocale) {
            captureRoboImage(
                filePath = "src/test/roborazzi/${name}_${locale.suffix}.png",
                roborazziOptions = strictCompareOptions,
                roborazziComposeOptions = RoborazziComposeOptions.Builder()
                    .addOption(RoborazziComposeLocaleOption(locale.tag))
                    .build(),
            ) {
                Surface(color = Color.White) {
                    AndroidView(
                        factory = { viewContext ->
                            runBlocking {
                                val remoteViews = GlanceRemoteViews()
                                    .compose(viewContext, size) {
                                        CodexMeterWidgetContent(
                                            context = viewContext,
                                            state = state,
                                            widgetSize = size,
                                            layoutVariant = layoutVariant,
                                        )
                                    }.remoteViews
                                remoteViews.apply(viewContext, FrameLayout(viewContext))
                            }
                        },
                        modifier = Modifier.size(widthDp.dp, heightDp.dp),
                    )
                }
            }
        }
    }

    private fun widgetPercentField(
        id: String,
        percent: Int,
        tone: WidgetQuotaTone,
        limitWindowSeconds: Int?,
    ) = WidgetField(
        windowId = id,
        isBalance = false,
        percent = percent,
        balanceAmount = null,
        balanceCurrency = null,
        resetAt = fixedNow.plusSeconds(5 * 3600),
        tone = tone,
        limitWindowSeconds = limitWindowSeconds,
    )

    private fun codexWidgetState(fields: List<WidgetField>) = WidgetQuotaState(
        status = WidgetQuotaStatus.Fresh,
        providerName = "Codex",
        providerId = "codex",
        localAccountId = "demo-local-1",
        accountName = "Demo Main",
        tone = WidgetQuotaTone.Success,
        clickTarget = WidgetClickTarget.Home,
        fields = fields,
        providerIconRes = ProviderRegistry.iconFor(ProviderId("codex")),
    )

    @ExperimentalGlanceRemoteViewsApi
    @Test
    fun widget_typicalSizes() {
        val fiveHourField = widgetPercentField("five_hour", 58, WidgetQuotaTone.Success, 5 * 3600)
        val weeklyField = widgetPercentField("weekly", 82, WidgetQuotaTone.Success, 7 * 86_400)
        val monthlyField = widgetPercentField("monthly", 71, WidgetQuotaTone.Warning, null)
        val balanceField = WidgetField(
            windowId = "balance",
            isBalance = true,
            percent = null,
            balanceAmount = "8.50",
            balanceCurrency = "USD",
            resetAt = null,
            tone = WidgetQuotaTone.Neutral,
            limitWindowSeconds = null,
        )

        Locale.entries.forEach { locale ->
            widgetGolden(
                name = "widget_4x2",
                locale = locale,
                widthDp = 340,
                heightDp = 210,
                expectedVariant = WidgetLayoutVariant.FourByTwo,
                state = codexWidgetState(listOf(fiveHourField, weeklyField, monthlyField, balanceField)),
            )
            widgetGolden(
                name = "widget_3x1",
                locale = locale,
                widthDp = 248,
                heightDp = 104,
                expectedVariant = WidgetLayoutVariant.ThreeByOne,
                state = codexWidgetState(listOf(fiveHourField, weeklyField)),
            )
        }
    }
}
