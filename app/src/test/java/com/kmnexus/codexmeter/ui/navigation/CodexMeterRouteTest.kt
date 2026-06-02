package com.kmnexus.codexmeter.ui.navigation

import com.kmnexus.codexmeter.R
import com.kmnexus.codexmeter.ui.auth.AddAccountEntryMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class CodexMeterRouteTest {
    @Test
    fun `bottom tabs are home account settings in order`() {
        assertEquals(
            listOf(CodexMeterRoute.Home, CodexMeterRoute.Account, CodexMeterRoute.Settings),
            CodexMeterRoute.bottomTabs,
        )
        assertEquals(
            listOf("home", "account", "settings"),
            CodexMeterRoute.bottomTabs.map { it.route },
        )
    }

    @Test
    fun `bottom tabs declare label content description and icon resources`() {
        CodexMeterRoute.bottomTabs.forEach { route ->
            assertNotEquals("label resource must be set for ${route.route}", 0, route.labelResId)
            assertNotEquals(
                "content description resource must be set for ${route.route}",
                0,
                route.contentDescriptionResId,
            )
            assertNotEquals("icon resource must be set for ${route.route}", 0, route.iconResId)
        }

        assertEquals(R.string.tab_home, CodexMeterRoute.Home.labelResId)
        assertEquals(R.string.tab_home_content_description, CodexMeterRoute.Home.contentDescriptionResId)
        assertEquals(R.drawable.ic_tab_home, CodexMeterRoute.Home.iconResId)

        assertEquals(R.string.tab_account, CodexMeterRoute.Account.labelResId)
        assertEquals(R.string.tab_account_content_description, CodexMeterRoute.Account.contentDescriptionResId)
        assertEquals(R.drawable.ic_tab_account, CodexMeterRoute.Account.iconResId)

        assertEquals(R.string.tab_settings, CodexMeterRoute.Settings.labelResId)
        assertEquals(R.string.tab_settings_content_description, CodexMeterRoute.Settings.contentDescriptionResId)
        assertEquals(R.drawable.ic_tab_settings, CodexMeterRoute.Settings.iconResId)
    }

    @Test
    fun `widget launch destination resolves add account route`() {
        assertEquals(
            CodexMeterRoute.Home.route,
            CodexMeterRoute.startRouteForLaunchDestination(null),
        )
        assertEquals(
            CodexMeterRoute.Home.route,
            CodexMeterRoute.startRouteForLaunchDestination(CodexMeterLaunchDestination.Home.value),
        )
        assertEquals(
            CodexMeterRoute.AddAccount.route,
            CodexMeterRoute.startRouteForLaunchDestination(CodexMeterLaunchDestination.AddAccount.value),
        )
    }

    @Test
    fun `add account routes only preserve device code login entry mode`() {
        assertEquals(
            "add_account/login",
            CodexMeterRoute.AddAccount.routeFor(AddAccountEntryMode.LoginToCodex),
        )
        assertEquals(
            AddAccountEntryMode.LoginToCodex,
            AddAccountEntryMode.fromRouteValue("login"),
        )
        assertEquals(
            AddAccountEntryMode.Choose,
            AddAccountEntryMode.fromRouteValue("import"),
        )
        assertEquals(
            AddAccountEntryMode.Choose,
            AddAccountEntryMode.fromRouteValue("unexpected"),
        )
    }
}
