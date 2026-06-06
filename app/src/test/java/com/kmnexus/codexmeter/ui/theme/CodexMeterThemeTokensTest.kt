package com.kmnexus.codexmeter.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class CodexMeterThemeTokensTest {
    @Test
    fun `air glass color tokens match design values`() {
        assertEquals(Color(0xFF17181C), LightCodexMeterColors.primary)
        assertEquals(Color(0xFF6A7280), LightCodexMeterColors.secondary)
        assertEquals(Color(0xFF8A94A6), LightCodexMeterColors.tertiary)
        assertEquals(Color(0xFFF5F7FB), LightCodexMeterColors.neutral)
        assertEquals(Color(0xFFEEF2F8), LightCodexMeterColors.neutralAlt)
        assertEquals(Color(0xFFFFFFFF), LightCodexMeterColors.surface)
        assertEquals(Color(0xFFFAFCFF), LightCodexMeterColors.surfaceSoft)
        assertEquals(Color(0xFFE5EAF2), LightCodexMeterColors.border)
        assertEquals(Color(0xFF0071E3), LightCodexMeterColors.accent)
        assertEquals(Color(0xFFF8FBFF), LightCodexMeterColors.accentSoft)
        assertEquals(Color(0xFF18A058), LightCodexMeterColors.success)
        assertEquals(Color(0xFFEAF7F0), LightCodexMeterColors.successSoft)
        assertEquals(Color(0xFFD97706), LightCodexMeterColors.warning)
        assertEquals(Color(0xFFFFF4E4), LightCodexMeterColors.warningSoft)
        assertEquals(Color(0xFFDC2626), LightCodexMeterColors.danger)
        assertEquals(Color(0xFFFEEDEE), LightCodexMeterColors.dangerSoft)
    }

    @Test
    fun `air glass spacing tokens match design values`() {
        assertEquals(4.dp, CodexMeterSpacing.xs)
        assertEquals(8.dp, CodexMeterSpacing.sm)
        assertEquals(12.dp, CodexMeterSpacing.md)
        assertEquals(16.dp, CodexMeterSpacing.lg)
        assertEquals(20.dp, CodexMeterSpacing.xl)
        assertEquals(24.dp, CodexMeterSpacing.xxl)
    }

    @Test
    fun `air glass shape tokens match design values`() {
        assertEquals(RoundedCornerShape(8.dp), CodexMeterShapes.xs)
        assertEquals(RoundedCornerShape(13.dp), CodexMeterShapes.sm)
        assertEquals(RoundedCornerShape(16.dp), CodexMeterShapes.md)
        assertEquals(RoundedCornerShape(22.dp), CodexMeterShapes.lg)
        assertEquals(RoundedCornerShape(28.dp), CodexMeterShapes.xl)
        assertEquals(RoundedCornerShape(39.dp), CodexMeterShapes.screen)
        assertEquals(RoundedCornerShape(999.dp), CodexMeterShapes.pill)
    }

    @Test
    fun `air glass typography tokens expose design text styles`() {
        assertEquals(31.sp, CodexMeterTypography.display.fontSize)
        assertEquals(FontWeight(800), CodexMeterTypography.display.fontWeight)
        assertEquals(20.sp, CodexMeterTypography.title.fontSize)
        assertEquals(FontWeight(760), CodexMeterTypography.title.fontWeight)
        assertEquals(14.sp, CodexMeterTypography.body.fontSize)
        assertEquals(FontWeight(500), CodexMeterTypography.body.fontWeight)
        assertEquals(12.sp, CodexMeterTypography.label.fontSize)
        assertEquals(FontWeight(720), CodexMeterTypography.label.fontWeight)
        assertEquals(39.sp, CodexMeterTypography.number.fontSize)
        assertEquals(FontWeight(850), CodexMeterTypography.number.fontWeight)
    }

    @Test
    fun `air glass typography keeps implementation-safe letter spacing`() {
        // Token names, sizes, and weights follow DESIGN.md; app-level UI guardrails keep letter spacing at 0.sp.
        assertEquals(0.sp, CodexMeterTypography.display.letterSpacing)
        assertEquals(0.sp, CodexMeterTypography.title.letterSpacing)
        assertEquals(0.sp, CodexMeterTypography.body.letterSpacing)
        assertEquals(0.sp, CodexMeterTypography.label.letterSpacing)
        assertEquals(0.sp, CodexMeterTypography.number.letterSpacing)
    }

    @Test
    fun `material typography roles map to codexmeter tokens`() {
        val material = CodexMeterTypography.material

        assertEquals(CodexMeterTypography.display, material.headlineMedium)
        assertEquals(CodexMeterTypography.body, material.bodySmall)
        assertEquals(CodexMeterTypography.label, material.labelSmall)
    }

    @Test
    fun `font schemes map display body label and number families`() {
        val system = CodexMeterTypography.forScheme(CodexMeterFontScheme.SystemDefault)
        val geist = CodexMeterTypography.forScheme(CodexMeterFontScheme.GeistHybrid)
        val inter = CodexMeterTypography.forScheme(CodexMeterFontScheme.InterJetBrainsMono)
        val monoFocus = CodexMeterTypography.forScheme(CodexMeterFontScheme.MonoFocusGeistMono)

        assertEquals(FontFamily.Default, system.display.fontFamily)
        assertNotEquals(FontFamily.Default, geist.display.fontFamily)
        assertNotEquals(geist.display.fontFamily, geist.number.fontFamily)
        assertNotEquals(inter.display.fontFamily, inter.number.fontFamily)
        assertEquals(monoFocus.display.fontFamily, monoFocus.body.fontFamily)
        assertEquals(monoFocus.display.fontFamily, monoFocus.number.fontFamily)
        assertEquals("tnum", geist.number.fontFeatureSettings)
    }

    @Test
    fun `material color roles map to codexmeter tokens`() {
        val material = materialScheme(LightCodexMeterColors, dark = false)

        assertEquals(LightCodexMeterColors.accentSoft, material.primaryContainer)
        assertEquals(LightCodexMeterColors.border, material.outlineVariant)
        assertEquals(LightCodexMeterColors.accent, material.surfaceTint)
        assertEquals(LightCodexMeterColors.surfaceSoft, material.surfaceContainerLow)
        assertEquals(LightCodexMeterColors.neutral, material.surfaceContainer)
        assertEquals(LightCodexMeterColors.neutralAlt, material.surfaceContainerHighest)
    }
}
