package com.kmnexus.codexmeter.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

// Shared tinting for the circular solid-color avatar initials used on both Home and Account. Kept next
// to the theme palette so the two feature packages reuse one definition instead of each copying it.

/** Badge background for an avatar, picked deterministically from the account's color key. */
@Composable
@ReadOnlyComposable
internal fun avatarColor(key: String): Color {
    val colors = listOf(CodexMeterTheme.colors.accent, CodexMeterTheme.colors.secondary, CodexMeterTheme.colors.warning)
    return colors[Math.floorMod(key.hashCode(), colors.size)]
}

/**
 * Ink for the solid-color avatar initial. The [avatarColor] badges turn light-toned in dark mode
 * (accent/secondary/warning all brighten), so a dark ink keeps the initial readable there; in light
 * mode those badges are saturated/dark and white reads. Plain white would disappear in dark.
 */
@Composable
@ReadOnlyComposable
internal fun avatarInitialColor(): Color =
    if (CodexMeterTheme.colors.isDark) CodexMeterTheme.colors.neutral else Color.White
