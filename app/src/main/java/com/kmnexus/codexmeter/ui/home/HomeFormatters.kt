package com.kmnexus.codexmeter.ui.home

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import com.kmnexus.codexmeter.ui.theme.CodexMeterTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun formattedInstant(instant: Instant): String {
    val locale = LocalConfiguration.current.locales[0]
    return remember(instant, locale) {
        val pattern = DateFormat.getBestDateTimePattern(locale, "yMdjm")
        DateTimeFormatter
            .ofPattern(pattern, locale)
            .withZone(ZoneId.systemDefault())
            .format(instant)
    }
}

@Composable
@ReadOnlyComposable
internal fun toneColor(tone: HomeStatusTone): Color =
    when (tone) {
        HomeStatusTone.Neutral -> CodexMeterTheme.colors.tertiary
        HomeStatusTone.Success -> CodexMeterTheme.colors.success
        HomeStatusTone.Warning -> CodexMeterTheme.colors.warning
        HomeStatusTone.Danger -> CodexMeterTheme.colors.danger
    }
