package com.kmnexus.codexmeter.widget

import com.kmnexus.codexmeter.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetQuotaStatusLabelTest {
    @Test
    fun `fresh widget label follows header tone`() {
        assertEquals(
            R.string.home_quota_status_normal,
            freshState(tone = WidgetQuotaTone.Success).statusLabelResId(),
        )
        assertEquals(
            R.string.home_quota_status_caution,
            freshState(tone = WidgetQuotaTone.Warning).statusLabelResId(),
        )
        assertEquals(
            R.string.home_quota_status_warning,
            freshState(tone = WidgetQuotaTone.Danger).statusLabelResId(),
        )
        assertEquals(
            R.string.home_quota_status_unavailable,
            freshState(tone = WidgetQuotaTone.Neutral).statusLabelResId(),
        )
    }

    @Test
    fun `non-fresh statuses map to dedicated labels`() {
        assertEquals(R.string.widget_connect_codex, stateWith(WidgetQuotaStatus.NoAccount).statusLabelResId())
        assertEquals(R.string.widget_status_possibly_stale, stateWith(WidgetQuotaStatus.PossiblyStale).statusLabelResId())
        assertEquals(R.string.widget_status_expired, stateWith(WidgetQuotaStatus.Expired).statusLabelResId())
        assertEquals(R.string.widget_status_auth_required, stateWith(WidgetQuotaStatus.AuthRequired).statusLabelResId())
        assertEquals(R.string.widget_status_refresh_failed, stateWith(WidgetQuotaStatus.ErrorWithLastKnownGood).statusLabelResId())
        assertEquals(R.string.widget_status_no_data, stateWith(WidgetQuotaStatus.NoData).statusLabelResId())
    }

    @Test
    fun `compact status rail glow stays narrow and uses semantic accent`() {
        val style = WidgetStatusGlowRenderer.styleSpec(WidgetQuotaTone.Success)

        assertEquals(0xFF5CF2A6.toInt(), style.accentArgb)
        assertEquals(0x335CF2A6, style.outerGlowArgb)
        assertTrue(style.outerGlowWidthDp in 6.5f..8.0f)
        assertTrue(style.innerGlowWidthDp in 4.0f..5.2f)
        assertEquals(3.0f, style.coreWidthDp)
        assertTrue(style.outerBlurRadiusDp <= 3.0f)
        assertTrue(style.innerBlurRadiusDp <= 1.8f)
        assertTrue(style.innerGlowCenterAlpha <= 82)
    }

    private fun freshState(tone: WidgetQuotaTone): WidgetQuotaState =
        stateWith(status = WidgetQuotaStatus.Fresh, tone = tone)

    private fun stateWith(
        status: WidgetQuotaStatus,
        tone: WidgetQuotaTone = WidgetQuotaTone.Neutral,
    ): WidgetQuotaState =
        WidgetQuotaState(
            status = status,
            providerName = "Codex",
            providerId = "codex",
            localAccountId = "local-1",
            accountName = "Codex Main",
            tone = tone,
            clickTarget = WidgetClickTarget.Home,
        )
}
