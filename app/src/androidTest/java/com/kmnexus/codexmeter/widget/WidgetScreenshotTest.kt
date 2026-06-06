package com.kmnexus.codexmeter.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.widget.FrameLayout
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kmnexus.codexmeter.domain.model.ProviderId
import com.kmnexus.codexmeter.providers.ProviderRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.time.Instant

/**
 * Renders the real widget content at each size with sample data and writes PNGs to the app's
 * external files dir for visual acceptance. Run on an emulator/device, then `adb pull` the PNGs.
 */
@RunWith(AndroidJUnit4::class)
class WidgetScreenshotTest {

    private data class Spec(
        val name: String,
        val variant: WidgetLayoutVariant,
        val widthDp: Int,
        val heightDp: Int,
        val state: WidgetQuotaState,
    )

    @OptIn(ExperimentalGlanceRemoteViewsApi::class)
    @Test
    fun renderWidgetSizes() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val outDir = context.getExternalFilesDir(null) ?: context.filesDir
        val density = context.resources.displayMetrics.density
        val codexIcon = ProviderRegistry.iconFor(ProviderId("codex"))
        val now = Instant.parse("2026-06-02T09:00:00Z")

        fun percentField(id: String, percent: Int, tone: WidgetQuotaTone, hasReset: Boolean = true) =
            WidgetField(
                windowId = id,
                isBalance = false,
                percent = percent,
                balanceAmount = null,
                balanceCurrency = null,
                resetAt = if (hasReset) now.plusSeconds(5 * 3600) else null,
                tone = tone,
            )

        val balanceField = WidgetField(
            windowId = "balance",
            isBalance = true,
            percent = null,
            balanceAmount = "8.50",
            balanceCurrency = "USD",
            resetAt = null,
            tone = WidgetQuotaTone.Neutral,
        )

        fun dataState(fields: List<WidgetField>) = WidgetQuotaState(
            status = WidgetQuotaStatus.Fresh,
            providerName = "Codex",
            providerId = "codex",
            localAccountId = "acc-1",
            accountName = "个人开发号",
            tone = WidgetQuotaTone.Success,
            clickTarget = WidgetClickTarget.Home,
            fields = fields,
            isUnconfigured = false,
            hasAccounts = true,
            providerIconRes = codexIcon,
        )

        val specs = listOf(
            Spec("3x1", WidgetLayoutVariant.ThreeByOne, 252, 110, dataState(
                listOf(percentField("five_hour", 87, WidgetQuotaTone.Success)),
            )),
            Spec("4x1", WidgetLayoutVariant.FourByOne, 340, 110, dataState(
                listOf(
                    percentField("five_hour", 87, WidgetQuotaTone.Success),
                    percentField("weekly", 42, WidgetQuotaTone.Warning),
                ),
            )),
            // 3x2 includes a balance field (no reset) to verify big numbers stay aligned.
            Spec("3x2", WidgetLayoutVariant.ThreeByTwo, 252, 210, dataState(
                listOf(
                    percentField("five_hour", 87, WidgetQuotaTone.Success),
                    percentField("weekly", 42, WidgetQuotaTone.Warning),
                    balanceField,
                ),
            )),
            Spec("4x2", WidgetLayoutVariant.FourByTwo, 340, 210, dataState(
                listOf(
                    percentField("five_hour", 87, WidgetQuotaTone.Success),
                    percentField("weekly", 42, WidgetQuotaTone.Warning),
                    percentField("monthly", 71, WidgetQuotaTone.Success),
                    balanceField,
                ),
            )),
            Spec("4x2_three_fields", WidgetLayoutVariant.FourByTwo, 340, 210, dataState(
                listOf(
                    percentField("five_hour", 87, WidgetQuotaTone.Success),
                    percentField("weekly", 42, WidgetQuotaTone.Warning),
                    percentField("monthly", 71, WidgetQuotaTone.Success),
                ),
            )),
            Spec("unconfigured", WidgetLayoutVariant.ThreeByTwo, 252, 210, WidgetQuotaState(
                status = WidgetQuotaStatus.NoAccount,
                providerName = "CodexMeter",
                providerId = null,
                localAccountId = null,
                accountName = null,
                tone = WidgetQuotaTone.Neutral,
                clickTarget = WidgetClickTarget.Home,
                fields = emptyList(),
                isUnconfigured = true,
                hasAccounts = true,
                providerIconRes = codexIcon,
            )),
        )

        val glance = GlanceRemoteViews()
        for (spec in specs) {
            val size = DpSize(spec.widthDp.dp, spec.heightDp.dp)
            val remoteViews = glance.compose(context, size) {
                CodexMeterWidgetContent(context, spec.state, size, spec.variant, com.kmnexus.codexmeter.domain.theme.WidgetAppearance.DARK)
            }.remoteViews

            val parent = FrameLayout(context)
            val view = remoteViews.apply(context, parent)
            val widthPx = (spec.widthDp * density).toInt()
            val heightPx = (spec.heightDp * density).toInt()
            view.measure(
                View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY),
            )
            view.layout(0, 0, widthPx, heightPx)

            val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            // Wallpaper-like backdrop so any transparent area is visible.
            canvas.drawColor(Color.rgb(60, 64, 72))
            view.draw(canvas)

            FileOutputStream(File(outDir, "widget_${spec.name}.png")).use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }
}
