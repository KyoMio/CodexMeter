package com.kmnexus.codexmeter.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

data class CodexMeterColorPalette(
    val isDark: Boolean,
    val primary: Color, val secondary: Color, val tertiary: Color,
    val neutral: Color, val neutralAlt: Color, val surface: Color, val surfaceSoft: Color,
    val border: Color, val accent: Color, val accentSoft: Color,
    val glassBase: Color, val glassInk: Color,
    val glassTintBlue: Color, val glassTintCyan: Color, val glassTintViolet: Color,
    val glassStrokeLight: Color, val glassStrokeCool: Color, val glassShadow: Color,
    val success: Color, val successSoft: Color,
    val warning: Color, val warningSoft: Color,
    val danger: Color, val dangerSoft: Color,
)

val LightCodexMeterColors = CodexMeterColorPalette(
    isDark = false,
    primary = Color(0xFF17181C), secondary = Color(0xFF6A7280), tertiary = Color(0xFF8A94A6),
    neutral = Color(0xFFF5F7FB), neutralAlt = Color(0xFFEEF2F8), surface = Color(0xFFFFFFFF), surfaceSoft = Color(0xFFFAFCFF),
    border = Color(0xFFE5EAF2), accent = Color(0xFF0071E3), accentSoft = Color(0xFFF8FBFF),
    glassBase = Color(0xFFEAF3FF), glassInk = Color(0xFF151821),
    glassTintBlue = Color(0xFF5DB8FF), glassTintCyan = Color(0xFF7EF4E8), glassTintViolet = Color(0xFF9D8CFF),
    glassStrokeLight = Color(0xFFFFFFFF), glassStrokeCool = Color(0xFFB9D8FF), glassShadow = Color(0xFF8AA9D6),
    success = Color(0xFF18A058), successSoft = Color(0xFFEAF7F0),
    warning = Color(0xFFD97706), warningSoft = Color(0xFFFFF4E4),
    danger = Color(0xFFDC2626), dangerSoft = Color(0xFFFEEDEE),
)

val DarkCodexMeterColors = CodexMeterColorPalette(
    isDark = true,
    primary = Color(0xFFEEF1F6), secondary = Color(0xFF9AA4B2), tertiary = Color(0xFF6B7484),
    neutral = Color(0xFF0E1218), neutralAlt = Color(0xFF141B25), surface = Color(0xFF171F2B), surfaceSoft = Color(0xFF10151D),
    border = Color(0xFF28313F), accent = Color(0xFF4C9EFF), accentSoft = Color(0xFF15314F),
    glassBase = Color(0xFF1C2636), glassInk = Color(0xFF151821),
    glassTintBlue = Color(0xFF5DB8FF), glassTintCyan = Color(0xFF7EF4E8), glassTintViolet = Color(0xFF9D8CFF),
    glassStrokeLight = Color(0xFFFFFFFF), glassStrokeCool = Color(0xFF3E5A7A), glassShadow = Color(0xFF060810),
    success = Color(0xFF35C77D), successSoft = Color(0xFF14271E),
    warning = Color(0xFFF0A63E), warningSoft = Color(0xFF2A2012),
    danger = Color(0xFFF2564B), dangerSoft = Color(0xFF2B1517),
)

internal val LocalCodexMeterColors = staticCompositionLocalOf { LightCodexMeterColors }

/** Same-name object + @Composable fun coexist (different namespaces), mirroring MaterialTheme. */
object CodexMeterTheme {
    val colors: CodexMeterColorPalette
        @Composable @ReadOnlyComposable get() = LocalCodexMeterColors.current
}
