package com.kmnexus.codexmeter.ui.navigation

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.kmnexus.codexmeter.R
import com.kmnexus.codexmeter.ui.auth.AddAccountEntryMode

sealed interface CodexMeterRoute {
    val route: String

    @get:StringRes
    val labelResId: Int

    @get:StringRes
    val contentDescriptionResId: Int

    @get:DrawableRes
    val iconResId: Int

    data object Home : CodexMeterRoute {
        override val route = "home"
        override val labelResId = R.string.tab_home
        override val contentDescriptionResId = R.string.tab_home_content_description
        override val iconResId = R.drawable.ic_tab_home
    }

    data object Account : CodexMeterRoute {
        override val route = "account"
        override val labelResId = R.string.tab_account
        override val contentDescriptionResId = R.string.tab_account_content_description
        override val iconResId = R.drawable.ic_tab_account
    }

    data object Settings : CodexMeterRoute {
        override val route = "settings"
        override val labelResId = R.string.tab_settings
        override val contentDescriptionResId = R.string.tab_settings_content_description
        override val iconResId = R.drawable.ic_tab_settings
    }

    data object AddAccount : CodexMeterRoute {
        private const val BASE_ROUTE = "add_account"
        const val ENTRY_MODE_ARG = "entry_mode"
        val routeWithEntryMode = "$BASE_ROUTE/{$ENTRY_MODE_ARG}"

        override val route = BASE_ROUTE
        override val labelResId = R.string.account_add_account
        override val contentDescriptionResId = R.string.account_add_account
        override val iconResId = R.drawable.ic_tab_account

        fun routeFor(entryMode: AddAccountEntryMode): String =
            when (entryMode) {
                is AddAccountEntryMode.ProviderSelection -> route
                else -> "$BASE_ROUTE/${entryMode.routeValue}"
            }
    }

    companion object {
        const val EXTRA_LAUNCH_DESTINATION = "com.kmnexus.codexmeter.extra.LAUNCH_DESTINATION"

        val bottomTabs = listOf(Home, Account, Settings)

        fun startRouteForLaunchDestination(destination: String?): String =
            when (destination) {
                CodexMeterLaunchDestination.AddAccount.value -> AddAccount.route
                CodexMeterLaunchDestination.SettingsUpdate.value -> Settings.route
                else -> Home.route
            }
    }
}

enum class CodexMeterLaunchDestination(val value: String) {
    Home("home"),
    AddAccount("add_account"),
    SettingsUpdate("settings_update"),
}
