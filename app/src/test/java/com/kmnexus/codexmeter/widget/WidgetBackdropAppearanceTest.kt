package com.kmnexus.codexmeter.widget

import com.kmnexus.codexmeter.domain.theme.WidgetAppearance
import org.junit.Assert.assertNotEquals
import org.junit.Test

class WidgetBackdropAppearanceTest {
    @Test fun `light and dark specs differ for every tone`() {
        for (tone in WidgetQuotaTone.entries) {
            val light = LiquidGlassWidgetBackdropRenderer.styleSpec(tone, WidgetAppearance.LIGHT)
            val dark = LiquidGlassWidgetBackdropRenderer.styleSpec(tone, WidgetAppearance.DARK)
            assertNotEquals("tone=$tone light vs dark must differ", light, dark)
        }
    }
}
