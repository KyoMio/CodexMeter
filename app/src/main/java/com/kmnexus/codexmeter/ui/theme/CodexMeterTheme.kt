package com.kmnexus.codexmeter.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kmnexus.codexmeter.R
import com.kmnexus.codexmeter.domain.theme.ThemeMode
import com.kmnexus.codexmeter.domain.theme.resolveDarkAppearance

enum class CodexMeterFontScheme {
    SystemDefault,
    GeistHybrid,
    InterJetBrainsMono,
    MonoFocusGeistMono,
}

data class CodexMeterTextStyles(
    val display: TextStyle,
    val title: TextStyle,
    val body: TextStyle,
    val label: TextStyle,
    val number: TextStyle,
) {
    val material: Typography = Typography(
        displayLarge = display,
        displayMedium = display,
        displaySmall = display,
        headlineLarge = display,
        headlineMedium = display,
        headlineSmall = display,
        titleLarge = title,
        titleMedium = title.copy(fontSize = 17.sp, lineHeight = 20.sp),
        titleSmall = title,
        bodyLarge = body,
        bodyMedium = body,
        bodySmall = body,
        labelLarge = label,
        labelMedium = label,
        labelSmall = label,
    )
}

object CodexMeterFontFamilies {
    private val weights = arrayOf(
        FontWeight.W400,
        FontWeight.W500,
        FontWeight.W600,
        FontWeight.W700,
        FontWeight.W800,
        FontWeight(850),
    )

    val system = FontFamily.Default
    val geistSans = bundledFontFamily(R.font.geist_variable)
    val geistMono = bundledFontFamily(R.font.geist_mono_variable)
    val inter = bundledFontFamily(R.font.inter_variable)
    val jetBrainsMono = bundledFontFamily(R.font.jetbrains_mono_variable)

    private fun bundledFontFamily(resourceId: Int): FontFamily =
        FontFamily(*weights.map { weight -> Font(resourceId, weight = weight) }.toTypedArray())
}

private val SystemCodexMeterTextStyles = buildCodexMeterTextStyles(
    uiFamily = CodexMeterFontFamilies.system,
    numberFamily = CodexMeterFontFamilies.system,
)

private val LocalCodexMeterTextStyles = staticCompositionLocalOf { SystemCodexMeterTextStyles }

object CodexMeterTypography {
    val display: TextStyle = SystemCodexMeterTextStyles.display
    val title: TextStyle = SystemCodexMeterTextStyles.title
    val body: TextStyle = SystemCodexMeterTextStyles.body
    val label: TextStyle = SystemCodexMeterTextStyles.label
    val number: TextStyle = SystemCodexMeterTextStyles.number
    val material: Typography = SystemCodexMeterTextStyles.material

    val current: CodexMeterTextStyles
        @Composable get() = LocalCodexMeterTextStyles.current

    fun forScheme(scheme: CodexMeterFontScheme): CodexMeterTextStyles =
        when (scheme) {
            CodexMeterFontScheme.SystemDefault -> SystemCodexMeterTextStyles
            CodexMeterFontScheme.GeistHybrid -> buildCodexMeterTextStyles(
                uiFamily = CodexMeterFontFamilies.geistSans,
                numberFamily = CodexMeterFontFamilies.geistMono,
            )
            CodexMeterFontScheme.InterJetBrainsMono -> buildCodexMeterTextStyles(
                uiFamily = CodexMeterFontFamilies.inter,
                numberFamily = CodexMeterFontFamilies.jetBrainsMono,
            )
            CodexMeterFontScheme.MonoFocusGeistMono -> buildCodexMeterTextStyles(
                uiFamily = CodexMeterFontFamilies.geistMono,
                numberFamily = CodexMeterFontFamilies.geistMono,
            )
        }
}

private fun buildCodexMeterTextStyles(
    uiFamily: FontFamily,
    numberFamily: FontFamily,
): CodexMeterTextStyles =
    CodexMeterTextStyles(
        display = TextStyle(
            fontFamily = uiFamily,
            fontWeight = FontWeight(800),
            fontSize = 31.sp,
            lineHeight = 33.sp,
            letterSpacing = 0.sp,
        ),
        title = TextStyle(
            fontFamily = uiFamily,
            fontWeight = FontWeight(760),
            fontSize = 20.sp,
            lineHeight = 24.sp,
            letterSpacing = 0.sp,
        ),
        body = TextStyle(
            fontFamily = uiFamily,
            fontWeight = FontWeight(500),
            fontSize = 14.sp,
            lineHeight = 21.sp,
            letterSpacing = 0.sp,
        ),
        label = TextStyle(
            fontFamily = uiFamily,
            fontWeight = FontWeight(720),
            fontSize = 12.sp,
            lineHeight = 16.sp,
            letterSpacing = 0.sp,
        ),
        number = TextStyle(
            fontFamily = numberFamily,
            fontWeight = FontWeight(850),
            fontSize = 39.sp,
            lineHeight = 37.sp,
            letterSpacing = 0.sp,
            fontFeatureSettings = "tnum",
        ),
    )

object CodexMeterShapes {
    val xs = RoundedCornerShape(8.dp)
    val sm = RoundedCornerShape(13.dp)
    val md = RoundedCornerShape(16.dp)
    val lg = RoundedCornerShape(22.dp)
    val xl = RoundedCornerShape(28.dp)
    val screen = RoundedCornerShape(39.dp)
    val pill = RoundedCornerShape(999.dp)

    val material = Shapes(
        extraSmall = xs,
        small = sm,
        medium = md,
        large = lg,
        extraLarge = xl,
    )
}

private fun lightScheme(p: CodexMeterColorPalette) = lightColorScheme(
    primary = p.accent,
    onPrimary = p.surface,
    primaryContainer = p.accentSoft,
    onPrimaryContainer = p.accent,
    inversePrimary = p.accent,
    secondary = p.secondary,
    onSecondary = p.surface,
    secondaryContainer = p.neutralAlt,
    onSecondaryContainer = p.secondary,
    tertiary = p.tertiary,
    onTertiary = p.surface,
    tertiaryContainer = p.neutralAlt,
    onTertiaryContainer = p.tertiary,
    background = p.neutral,
    onBackground = p.primary,
    surface = p.surface,
    onSurface = p.primary,
    surfaceVariant = p.surfaceSoft,
    onSurfaceVariant = p.secondary,
    surfaceTint = p.accent,
    inverseSurface = p.primary,
    inverseOnSurface = p.surface,
    outline = p.border,
    outlineVariant = p.border,
    scrim = p.primary,
    surfaceBright = p.surface,
    surfaceDim = p.neutralAlt,
    surfaceContainer = p.neutral,
    surfaceContainerHigh = p.neutralAlt,
    surfaceContainerHighest = p.neutralAlt,
    surfaceContainerLow = p.surfaceSoft,
    surfaceContainerLowest = p.surface,
    error = p.danger,
    onError = p.surface,
    errorContainer = p.dangerSoft,
    onErrorContainer = p.danger,
    primaryFixed = p.accentSoft,
    primaryFixedDim = p.accentSoft,
    onPrimaryFixed = p.accent,
    onPrimaryFixedVariant = p.accent,
    secondaryFixed = p.neutralAlt,
    secondaryFixedDim = p.neutralAlt,
    onSecondaryFixed = p.primary,
    onSecondaryFixedVariant = p.secondary,
    tertiaryFixed = p.neutralAlt,
    tertiaryFixedDim = p.neutralAlt,
    onTertiaryFixed = p.primary,
    onTertiaryFixedVariant = p.tertiary,
)

private fun darkScheme(p: CodexMeterColorPalette) = darkColorScheme(
    primary = p.accent,
    onPrimary = Color(0xFF08121F), // dark ink reads on the bright accent
    primaryContainer = p.accentSoft,
    onPrimaryContainer = p.accent,
    secondary = p.secondary,
    onSecondary = p.primary,
    secondaryContainer = p.neutralAlt,
    onSecondaryContainer = p.primary,
    tertiary = p.tertiary,
    onTertiary = p.primary,
    background = p.neutral,
    onBackground = p.primary,
    surface = p.surface,
    onSurface = p.primary,
    surfaceVariant = p.surfaceSoft,
    onSurfaceVariant = p.secondary,
    surfaceTint = p.accent,
    inverseSurface = p.primary,
    inverseOnSurface = p.neutral,
    outline = p.border,
    outlineVariant = p.border,
    scrim = Color(0xFF000000),
    error = p.danger,
    onError = Color(0xFF2B0F0F),
    errorContainer = p.dangerSoft,
    onErrorContainer = p.danger,
)

internal fun materialScheme(palette: CodexMeterColorPalette, dark: Boolean) =
    if (dark) darkScheme(palette) else lightScheme(palette)

@Composable
fun CodexMeterTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    fontScheme: CodexMeterFontScheme = CodexMeterFontScheme.SystemDefault,
    content: @Composable () -> Unit,
) {
    val dark = resolveDarkAppearance(themeMode, isSystemInDarkTheme())
    val palette = if (dark) DarkCodexMeterColors else LightCodexMeterColors
    val typography = CodexMeterTypography.forScheme(fontScheme)
    CompositionLocalProvider(LocalCodexMeterColors provides palette) {
        MaterialTheme(
            colorScheme = materialScheme(palette, dark),
            typography = typography.material,
            shapes = CodexMeterShapes.material,
        ) {
            CompositionLocalProvider(
                LocalCodexMeterTextStyles provides typography,
                content = content,
            )
        }
    }
}
