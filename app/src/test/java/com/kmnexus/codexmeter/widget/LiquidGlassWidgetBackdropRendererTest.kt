package com.kmnexus.codexmeter.widget

import org.junit.Assert.assertTrue
import org.junit.Test

class LiquidGlassWidgetBackdropRendererTest {
    @Test
    fun `backdrop style keeps the frosted body light and translucent`() {
        val style = LiquidGlassWidgetBackdropRenderer.styleSpec(WidgetQuotaTone.Success, com.kmnexus.codexmeter.domain.theme.WidgetAppearance.DARK)

        assertTrue(style.bodyTopAlpha in 102..120)
        assertTrue(style.bodyMiddleAlpha in 88..104)
        assertTrue(style.bodyToneAlpha in 80..96)
        assertTrue(style.bodyBottomAlpha in 86..102)
        assertTrue(style.frostVeilPeakAlpha <= 16)
        assertTrue(style.innerAirPeakAlpha <= 12)
    }

    @Test
    fun `backdrop style keeps only a close lightweight contact shadow`() {
        val style = LiquidGlassWidgetBackdropRenderer.styleSpec(WidgetQuotaTone.Success, com.kmnexus.codexmeter.domain.theme.WidgetAppearance.DARK)

        assertTrue(style.bottomShadowPeakAlpha in 3..8)
        assertTrue(style.bottomShadowContactAlpha in 1..4)
        assertTrue(style.bottomShadowBlue > style.bottomShadowRed)
    }

    @Test
    fun `backdrop style uses hairline rim instead of thick stacked borders`() {
        val style = LiquidGlassWidgetBackdropRenderer.styleSpec(WidgetQuotaTone.Success, com.kmnexus.codexmeter.domain.theme.WidgetAppearance.DARK)

        assertTrue(style.topHighlightPeakAlpha in 14..24)
        assertTrue(style.topEdgeRimAlpha in 14..22)
        assertTrue(style.sideRimPeakAlpha in 7..14)
        assertTrue(style.bottomRimBlueAlpha in 0..4)
        assertTrue(style.lensBluePeakAlpha <= 8)
        assertTrue(style.lensCyanPeakAlpha <= 5)
        assertTrue(style.outerBorderStrokeScale in 0.18f..0.30f)
        assertTrue(style.innerBorderStrokeScale <= 0.08f)
    }

    @Test
    fun `backdrop style keeps highlight and shadow attached to the same thin body`() {
        val style = LiquidGlassWidgetBackdropRenderer.styleSpec(WidgetQuotaTone.Success, com.kmnexus.codexmeter.domain.theme.WidgetAppearance.DARK)

        assertTrue(style.topHighlightTopInsetScale <= 2f)
        assertTrue(style.topHighlightHeightFraction <= 0.07f)
        assertTrue(style.topHighlightSideInsetScale in 2f..8f)
        assertTrue(style.topHighlightMaxHeightScale <= 2.8f)
        assertTrue(style.bottomShadowCenterOffsetScale <= 0.5f)
        assertTrue(style.bottomShadowTopOffsetScale in -0.2f..0.4f)
        assertTrue(style.bottomShadowBottomOffsetScale in 0.3f..1.2f)
        assertTrue(style.bottomShadowContactTopOffsetScale in -0.2f..0.3f)
        assertTrue(style.bottomShadowContactBottomOffsetScale <= 0.8f)
    }
}
