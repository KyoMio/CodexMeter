package com.kmnexus.codexmeter.domain.theme

enum class WidgetAppearance { LIGHT, DARK }

/** Resolve whether the dark palette applies. `systemDark` is platform-provided (isSystemInDarkTheme / uiMode). */
fun resolveDarkAppearance(mode: ThemeMode, systemDark: Boolean): Boolean =
    when (mode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> systemDark
    }

fun resolveWidgetAppearance(mode: ThemeMode, systemDark: Boolean): WidgetAppearance =
    if (resolveDarkAppearance(mode, systemDark)) WidgetAppearance.DARK else WidgetAppearance.LIGHT
