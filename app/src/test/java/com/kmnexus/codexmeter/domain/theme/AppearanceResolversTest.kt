package com.kmnexus.codexmeter.domain.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppearanceResolversTest {
    @Test fun `light is never dark`() {
        assertFalse(resolveDarkAppearance(ThemeMode.LIGHT, systemDark = true))
        assertFalse(resolveDarkAppearance(ThemeMode.LIGHT, systemDark = false))
    }

    @Test fun `dark is always dark`() {
        assertTrue(resolveDarkAppearance(ThemeMode.DARK, systemDark = false))
        assertTrue(resolveDarkAppearance(ThemeMode.DARK, systemDark = true))
    }

    @Test fun `system follows system flag`() {
        assertTrue(resolveDarkAppearance(ThemeMode.SYSTEM, systemDark = true))
        assertFalse(resolveDarkAppearance(ThemeMode.SYSTEM, systemDark = false))
    }

    @Test fun `widget appearance maps from resolved dark`() {
        assertEquals(WidgetAppearance.DARK, resolveWidgetAppearance(ThemeMode.DARK, systemDark = false))
        assertEquals(WidgetAppearance.LIGHT, resolveWidgetAppearance(ThemeMode.LIGHT, systemDark = true))
        assertEquals(WidgetAppearance.DARK, resolveWidgetAppearance(ThemeMode.SYSTEM, systemDark = true))
    }

    @Test fun `unknown storage value falls back to system`() {
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromStorage("bogus"))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromStorage(null))
        assertEquals(ThemeMode.DARK, ThemeMode.fromStorage("dark"))
    }
}
