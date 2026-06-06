package com.kmnexus.codexmeter.widget

import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.lifecycle.lifecycleScope
import com.kmnexus.codexmeter.providers.ProviderRegistry
import com.kmnexus.codexmeter.domain.model.ProviderId
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * Debug-only preview that lays the real widget content over the live wallpaper so the translucent
 * glass style can be visually accepted. Launch per spec:
 *   adb shell am start -n com.kmnexus.codexmeter.debug/com.kmnexus.codexmeter.widget.WidgetPreviewActivity --es spec 4x2
 * then `adb exec-out screencap -p`.
 */
class WidgetPreviewActivity : ComponentActivity() {

    @OptIn(ExperimentalGlanceRemoteViewsApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)

        val density = resources.displayMetrics.density
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        setContentView(root)

        val specName = intent?.getStringExtra("spec") ?: "4x2"
        val spec = specFor(specName)

        lifecycleScope.launch {
            val glance = GlanceRemoteViews()
            val size = DpSize(spec.widthDp.dp, spec.heightDp.dp)
            val remoteViews = glance.compose(this@WidgetPreviewActivity, size) {
                CodexMeterWidgetContent(this@WidgetPreviewActivity, spec.state, size, spec.variant)
            }.remoteViews
            val container = FrameLayout(this@WidgetPreviewActivity)
            val view = remoteViews.apply(this@WidgetPreviewActivity, container)
            container.addView(view)
            root.addView(
                container,
                LinearLayout.LayoutParams(
                    (spec.widthDp * density).toInt(),
                    (spec.heightDp * density).toInt(),
                ),
            )
        }
    }

    private data class Spec(
        val variant: WidgetLayoutVariant,
        val widthDp: Int,
        val heightDp: Int,
        val state: WidgetQuotaState,
    )

    private fun specFor(name: String): Spec {
        val codexIcon = ProviderRegistry.iconFor(ProviderId("codex"))
        val now = Instant.parse("2026-06-02T09:00:00Z")
        fun pct(id: String, percent: Int, tone: WidgetQuotaTone, reset: Boolean = true) = WidgetField(
            windowId = id, isBalance = false, percent = percent,
            balanceAmount = null, balanceCurrency = null,
            resetAt = if (reset) now.plusSeconds(5 * 3600) else null, tone = tone,
        )
        val balance = WidgetField(
            windowId = "balance", isBalance = true, percent = null,
            balanceAmount = "8.50", balanceCurrency = "USD", resetAt = null, tone = WidgetQuotaTone.Neutral,
        )
        fun data(fields: List<WidgetField>) = WidgetQuotaState(
            status = WidgetQuotaStatus.Fresh, providerName = "Codex", providerId = "codex",
            localAccountId = "acc-1", accountName = "个人开发号", tone = WidgetQuotaTone.Success,
            clickTarget = WidgetClickTarget.Home, fields = fields,
            isUnconfigured = false, hasAccounts = true, providerIconRes = codexIcon,
        )
        return when (name) {
            "3x1" -> Spec(WidgetLayoutVariant.ThreeByOne, 252, 110, data(listOf(pct("five_hour", 87, WidgetQuotaTone.Success))))
            "4x1" -> Spec(WidgetLayoutVariant.FourByOne, 340, 110, data(listOf(
                pct("five_hour", 87, WidgetQuotaTone.Success), pct("weekly", 42, WidgetQuotaTone.Warning),
            )))
            "3x2" -> Spec(WidgetLayoutVariant.ThreeByTwo, 252, 210, data(listOf(
                pct("five_hour", 87, WidgetQuotaTone.Success), pct("weekly", 42, WidgetQuotaTone.Warning), balance,
            )))
            "unconfigured" -> Spec(WidgetLayoutVariant.ThreeByTwo, 252, 210, WidgetQuotaState(
                status = WidgetQuotaStatus.NoAccount, providerName = "CodexMeter", providerId = null,
                localAccountId = null, accountName = null, tone = WidgetQuotaTone.Neutral,
                clickTarget = WidgetClickTarget.Home, fields = emptyList(),
                isUnconfigured = true, hasAccounts = true, providerIconRes = null,
            ))
            else -> Spec(WidgetLayoutVariant.FourByTwo, 340, 210, data(listOf(
                pct("five_hour", 87, WidgetQuotaTone.Success), pct("weekly", 42, WidgetQuotaTone.Warning),
                pct("monthly", 71, WidgetQuotaTone.Success), balance,
            )))
        }
    }
}
