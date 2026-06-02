package com.kmnexus.codexmeter.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import com.kmnexus.codexmeter.ui.theme.CodexMeterColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
internal fun formattedInstant(instant: Instant): String {
    val locale = LocalConfiguration.current.locales[0]
    return remember(instant, locale) {
        DateTimeFormatter
            .ofLocalizedDateTime(FormatStyle.SHORT)
            .withLocale(locale)
            .withZone(ZoneId.systemDefault())
            .format(instant)
    }
}

internal fun toneColor(tone: HomeStatusTone): Color =
    when (tone) {
        HomeStatusTone.Neutral -> CodexMeterColors.tertiary
        HomeStatusTone.Success -> CodexMeterColors.success
        HomeStatusTone.Warning -> CodexMeterColors.warning
        HomeStatusTone.Danger -> CodexMeterColors.danger
    }
