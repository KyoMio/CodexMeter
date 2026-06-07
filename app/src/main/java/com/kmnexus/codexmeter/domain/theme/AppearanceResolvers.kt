package com.kmnexus.codexmeter.domain.theme

/** Resolve whether the dark palette applies. `systemDark` is platform-provided (isSystemInDarkTheme / uiMode). */
fun resolveDarkAppearance(mode: ThemeMode, systemDark: Boolean): Boolean =
    when (mode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> systemDark
    }
