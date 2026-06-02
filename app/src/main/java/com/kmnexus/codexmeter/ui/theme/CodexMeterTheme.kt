package com.kmnexus.codexmeter.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kmnexus.codexmeter.R

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

internal val CodexMeterColorScheme = lightColorScheme(
    primary = CodexMeterColors.accent,
    onPrimary = CodexMeterColors.surface,
    primaryContainer = CodexMeterColors.accentSoft,
    onPrimaryContainer = CodexMeterColors.accent,
    inversePrimary = CodexMeterColors.accent,
    secondary = CodexMeterColors.secondary,
    onSecondary = CodexMeterColors.surface,
    secondaryContainer = CodexMeterColors.neutralAlt,
    onSecondaryContainer = CodexMeterColors.secondary,
    tertiary = CodexMeterColors.tertiary,
    onTertiary = CodexMeterColors.surface,
    tertiaryContainer = CodexMeterColors.neutralAlt,
    onTertiaryContainer = CodexMeterColors.tertiary,
    background = CodexMeterColors.neutral,
    onBackground = CodexMeterColors.primary,
    surface = CodexMeterColors.surface,
    onSurface = CodexMeterColors.primary,
    surfaceVariant = CodexMeterColors.surfaceSoft,
    onSurfaceVariant = CodexMeterColors.secondary,
    surfaceTint = CodexMeterColors.accent,
    inverseSurface = CodexMeterColors.primary,
    inverseOnSurface = CodexMeterColors.surface,
    outline = CodexMeterColors.border,
    outlineVariant = CodexMeterColors.border,
    scrim = CodexMeterColors.primary,
    surfaceBright = CodexMeterColors.surface,
    surfaceDim = CodexMeterColors.neutralAlt,
    surfaceContainer = CodexMeterColors.neutral,
    surfaceContainerHigh = CodexMeterColors.neutralAlt,
    surfaceContainerHighest = CodexMeterColors.neutralAlt,
    surfaceContainerLow = CodexMeterColors.surfaceSoft,
    surfaceContainerLowest = CodexMeterColors.surface,
    error = CodexMeterColors.danger,
    onError = CodexMeterColors.surface,
    errorContainer = CodexMeterColors.dangerSoft,
    onErrorContainer = CodexMeterColors.danger,
    primaryFixed = CodexMeterColors.accentSoft,
    primaryFixedDim = CodexMeterColors.accentSoft,
    onPrimaryFixed = CodexMeterColors.accent,
    onPrimaryFixedVariant = CodexMeterColors.accent,
    secondaryFixed = CodexMeterColors.neutralAlt,
    secondaryFixedDim = CodexMeterColors.neutralAlt,
    onSecondaryFixed = CodexMeterColors.primary,
    onSecondaryFixedVariant = CodexMeterColors.secondary,
    tertiaryFixed = CodexMeterColors.neutralAlt,
    tertiaryFixedDim = CodexMeterColors.neutralAlt,
    onTertiaryFixed = CodexMeterColors.primary,
    onTertiaryFixedVariant = CodexMeterColors.tertiary,
)

@Composable
fun CodexMeterTheme(
    fontScheme: CodexMeterFontScheme = CodexMeterFontScheme.SystemDefault,
    content: @Composable () -> Unit,
) {
    val typography = CodexMeterTypography.forScheme(fontScheme)
    MaterialTheme(
        colorScheme = CodexMeterColorScheme,
        typography = typography.material,
        shapes = CodexMeterShapes.material,
    ) {
        CompositionLocalProvider(
            LocalCodexMeterTextStyles provides typography,
            content = content,
        )
    }
}
