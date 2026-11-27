package com.kmnexus.codexmeter.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziComposeLocaleOption
import com.github.takahirom.roborazzi.RoborazziComposeOptions
import com.github.takahirom.roborazzi.RoborazziComposeSizeOption
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.kmnexus.codexmeter.R
import com.kmnexus.codexmeter.domain.model.AccountStatus
import com.kmnexus.codexmeter.domain.model.LocalAccountId
import com.kmnexus.codexmeter.domain.model.ProviderId
import com.kmnexus.codexmeter.domain.quota.QuotaWindowDisplayKind
import com.kmnexus.codexmeter.providers.ProviderRegistry
import com.kmnexus.codexmeter.ui.account.AccountContentStatus
import com.kmnexus.codexmeter.ui.account.AccountCreditsUi
import com.kmnexus.codexmeter.ui.account.AccountItemUi
import com.kmnexus.codexmeter.ui.account.AccountScreen
import com.kmnexus.codexmeter.ui.account.AccountStatusTone
import com.kmnexus.codexmeter.ui.account.AccountUiState
import com.kmnexus.codexmeter.ui.auth.AddAccountScreen
import com.kmnexus.codexmeter.ui.auth.DeviceCodeLoginUiState
import com.kmnexus.codexmeter.ui.auth.DeviceCodeLoginUiStatus
import com.kmnexus.codexmeter.ui.auth.ProviderSelectionScreen
import com.kmnexus.codexmeter.ui.auth.WebViewAuthConfig
import com.kmnexus.codexmeter.ui.auth.WebViewAuthScreen
import com.kmnexus.codexmeter.ui.home.HomeAccountChoiceUi
import com.kmnexus.codexmeter.ui.home.HomeAccountUi
import com.kmnexus.codexmeter.ui.home.HomeActionKind
import com.kmnexus.codexmeter.ui.home.HomeActionUi
import com.kmnexus.codexmeter.ui.home.HomeContentStatus
import com.kmnexus.codexmeter.ui.home.HomeCreditsUi
import com.kmnexus.codexmeter.ui.home.HomeQuotaCardUi
import com.kmnexus.codexmeter.ui.home.HomeQuotaStatus
import com.kmnexus.codexmeter.ui.home.HomeRefreshUi
import com.kmnexus.codexmeter.ui.home.HomeScreen
import com.kmnexus.codexmeter.ui.home.HomeStatusTone
import com.kmnexus.codexmeter.ui.home.HomeTrendUi
import com.kmnexus.codexmeter.ui.home.HomeUiState
import com.kmnexus.codexmeter.ui.quota.quotaWindowLabelRes
import com.kmnexus.codexmeter.ui.settings.SettingsScreen
import com.kmnexus.codexmeter.ui.settings.SettingsUiState
import com.kmnexus.codexmeter.ui.theme.CodexMeterFontScheme
import com.kmnexus.codexmeter.ui.theme.CodexMeterTheme
import java.time.Instant
import java.util.Locale as JavaLocale
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Golden screenshot matrix (Roborazzi on Robolectric, JVM-fast).
 *
 * All fixtures are synthetic and sanitized: no real account names, tokens, cookies, or emails.
 * Width is pinned to a realistic 412dp phone viewport, font scheme matches MainActivity
 * ([CodexMeterFontScheme.MonoFocusGeistMono]), and pixel diffs are strictly zero-tolerance.
 */
@OptIn(ExperimentalRoborazziApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class UiGoldenScreenshotTest {

    private enum class Locale(
        val tag: String,
        val suffix: String,
        val javaLocale: JavaLocale,
    ) {
        ZH("zh-rCN", "zh", JavaLocale.SIMPLIFIED_CHINESE),
        EN("en", "en", JavaLocale.US),
    }

    private val strictCompareOptions = RoborazziOptions(
        compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.0f),
    )

    private fun golden(
        name: String,
        locale: Locale,
        heightDp: Int = DEFAULT_HEIGHT_DP,
        content: @Composable () -> Unit,
    ) {
        pinGoldenEnvironment(locale.javaLocale) {
            val options = RoborazziComposeOptions.Builder()
                .addOption(RoborazziComposeLocaleOption(locale.tag))
                .addOption(RoborazziComposeSizeOption(PHONE_WIDTH_DP, heightDp))
                .build()
            captureRoboImage(
                filePath = "src/test/roborazzi/${name}_${locale.suffix}.png",
                roborazziOptions = strictCompareOptions,
                roborazziComposeOptions = options,
            ) {
                CodexMeterTheme(fontScheme = CodexMeterFontScheme.MonoFocusGeistMono) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        content()
                    }
                }
            }
        }
    }

    private fun forEachLocale(block: (Locale) -> Unit) {
        Locale.entries.forEach(block)
    }

    // region Home fixtures

    private val fixedNow: Instant = Instant.parse("2026-05-23T12:00:00Z")

    private fun codexAccount(displayName: String = "Demo Main") = HomeAccountUi(
        localAccountId = LocalAccountId("demo-local-1"),
        providerId = ProviderId("codex"),
        displayName = displayName,
        avatarInitial = displayName.first().uppercase(),
        avatarColorKey = "demo-local-1",
        planType = "plus",
        credits = HomeCreditsUi.Unavailable,
        providerIconResId = ProviderRegistry.iconFor(ProviderId("codex")),
    )

    private fun accountChoice(id: String, provider: String, name: String) = HomeAccountChoiceUi(
        localAccountId = LocalAccountId(id),
        providerId = ProviderId(provider),
        displayName = name,
        avatarInitial = name.first().uppercase(),
        avatarColorKey = id,
        providerIconResId = ProviderRegistry.iconFor(ProviderId(provider)),
    )

    private fun percentCard(
        windowId: String,
        titleResId: Int,
        usedPercent: Int?,
        status: HomeQuotaStatus = HomeQuotaStatus.Normal,
        tone: HomeStatusTone = HomeStatusTone.Neutral,
        statusLabelResId: Int = R.string.home_quota_status_normal,
        isPrimary: Boolean = false,
        resetAt: Instant? = Instant.parse("2026-05-23T17:00:00Z"),
    ) = HomeQuotaCardUi(
        windowId = windowId,
        titleResId = titleResId,
        displayKind = QuotaWindowDisplayKind.Percent,
        usedPercent = usedPercent,
        balanceAmount = null,
        balanceCurrency = null,
        usedCount = null,
        limitCount = null,
        subLabel = null,
        isPrimary = isPrimary,
        resetAt = resetAt,
        status = status,
        tone = tone,
        statusLabelResId = statusLabelResId,
    )

    private fun codexCards() = listOf(
        percentCard(
            windowId = "five_hour",
            titleResId = R.string.home_quota_window_five_hour,
            usedPercent = 42,
            isPrimary = true,
        ),
        percentCard(
            windowId = "weekly",
            titleResId = R.string.home_quota_window_weekly,
            usedPercent = 18,
        ),
    )

    private fun antigravityCards() = listOf(
        antigravityCard("antigravity_gemini_5h_window", 35, hoursAhead = 5, isPrimary = true),
        antigravityCard("antigravity_gemini_weekly_window", 62, daysAhead = 3),
        antigravityCard("antigravity_claude_gpt_5h_window", 12, hoursAhead = 2),
        antigravityCard("antigravity_claude_gpt_weekly_window", 48, daysAhead = 5),
    )

    private fun antigravityCard(
        windowId: String,
        usedPercent: Int,
        hoursAhead: Int = 0,
        daysAhead: Int = 0,
        isPrimary: Boolean = false,
    ) = percentCard(
        windowId = windowId,
        titleResId = quotaWindowLabelRes(windowId),
        usedPercent = usedPercent,
        isPrimary = isPrimary,
        resetAt = fixedNow.plusSeconds((daysAhead * 24 + hoursAhead) * 3600L),
    )

    private fun homeState(
        contentStatus: HomeContentStatus,
        cards: List<HomeQuotaCardUi>,
        accountChoices: List<HomeAccountChoiceUi> = emptyList(),
        errorMessageResId: Int? = null,
    ): HomeUiState {
        val statusTitleResId = when (contentStatus) {
            HomeContentStatus.Fresh -> R.string.home_state_fresh_title
            HomeContentStatus.ErrorWithLastKnownGood -> R.string.home_state_error_lkg_title
            HomeContentStatus.AuthRequired -> R.string.home_state_auth_required_title
            else -> R.string.home_state_fresh_title
        }
        val statusDescriptionResId = when (contentStatus) {
            HomeContentStatus.Fresh -> R.string.home_state_fresh_description
            HomeContentStatus.ErrorWithLastKnownGood -> R.string.home_state_error_lkg_description
            HomeContentStatus.AuthRequired -> R.string.home_state_auth_required_description
            else -> R.string.home_state_fresh_description
        }
        val primaryAction = if (contentStatus == HomeContentStatus.AuthRequired) {
            HomeActionUi(
                kind = HomeActionKind.LoginToCodex,
                labelResId = R.string.home_login_to_codex,
            )
        } else {
            null
        }
        return HomeUiState(
            contentStatus = contentStatus,
            statusTitleResId = statusTitleResId,
            statusDescriptionResId = statusDescriptionResId,
            errorMessageResId = errorMessageResId,
            account = codexAccount(),
            accountChoices = accountChoices,
            quotaCards = cards,
            trend = HomeTrendUi(),
            loading = null,
            refresh = HomeRefreshUi(
                titleResId = statusTitleResId,
                descriptionResId = statusDescriptionResId,
                buttonTextResId = R.string.home_refresh,
                lastSuccessfulRefreshAt = fixedNow,
                lastAttemptFinishedAt = fixedNow,
            ),
            primaryAction = primaryAction,
            secondaryAction = null,
            isRefreshing = false,
        )
    }

    // endregion

    // region Home matrix

    @Test
    fun home_singleAccountCodexNormal() {
        forEachLocale { locale ->
            golden("home_codex_normal", locale) {
                HomeScreen(uiState = homeState(HomeContentStatus.Fresh, codexCards()))
            }
        }
    }

    @Test
    fun home_multiAccountSelector() {
        forEachLocale { locale ->
            golden("home_multi_account", locale) {
                HomeScreen(
                    uiState = homeState(
                        HomeContentStatus.Fresh,
                        codexCards(),
                        accountChoices = listOf(
                            accountChoice("demo-local-1", "codex", "Demo Main"),
                            accountChoice("demo-local-2", "claude", "Work Backup"),
                            accountChoice("demo-local-3", "antigravity", "Gemini Test"),
                        ),
                    ),
                )
            }
        }
    }

    @Test
    fun home_antigravityTwoByTwoGrid() {
        forEachLocale { locale ->
            golden("home_antigravity_2x2", locale, heightDp = 1200) {
                HomeScreen(uiState = homeState(HomeContentStatus.Fresh, antigravityCards()))
            }
        }
    }

    @Test
    fun home_nullRemainingFractionExhausted() {
        forEachLocale { locale ->
            // AntigravityQuotaMapper maps omitted proto3 `remainingFraction` to 100% used (0% left) + Depleted.
            golden("home_exhausted_null_fraction", locale) {
                HomeScreen(
                    uiState = homeState(
                        HomeContentStatus.Fresh,
                        listOf(
                            percentCard(
                                windowId = "antigravity_gemini_5h_window",
                                titleResId = quotaWindowLabelRes("antigravity_gemini_5h_window"),
                                usedPercent = 100,
                                status = HomeQuotaStatus.Exhausted,
                                tone = HomeStatusTone.Danger,
                                statusLabelResId = R.string.home_quota_status_exhausted,
                                isPrimary = true,
                            ),
                            percentCard(
                                windowId = "antigravity_gemini_weekly_window",
                                titleResId = quotaWindowLabelRes("antigravity_gemini_weekly_window"),
                                usedPercent = 66,
                            ),
                        ),
                    ),
                )
            }
        }
    }

    @Test
    fun home_errorWithLastKnownGood() {
        forEachLocale { locale ->
            golden("home_error_last_known_good", locale, heightDp = 1150) {
                HomeScreen(
                    uiState = homeState(
                        HomeContentStatus.ErrorWithLastKnownGood,
                        codexCards(),
                        errorMessageResId = R.string.error_network,
                    ),
                )
            }
        }
    }

    @Test
    fun home_errorState() {
        forEachLocale { locale ->
            golden("home_error_auth_required", locale) {
                HomeScreen(
                    uiState = homeState(
                        HomeContentStatus.AuthRequired,
                        emptyList(),
                        errorMessageResId = R.string.error_auth_required,
                    ),
                )
            }
        }
    }

    // endregion

    // region Account matrix

    private fun accountItem(
        id: String,
        name: String,
        isDefault: Boolean,
        status: AccountStatus,
        tone: AccountStatusTone,
        badgeLabelResId: Int,
        statusLabelResId: Int,
    ) = AccountItemUi(
        id = LocalAccountId(id),
        displayName = name,
        avatarInitial = name.first().uppercase(),
        avatarColorKey = id,
        isDefault = isDefault,
        status = status,
        tone = tone,
        badgeLabelResId = badgeLabelResId,
        statusLabelResId = statusLabelResId,
        refreshSummaryResId = R.string.account_last_refresh_format,
        lastSuccessfulRefreshAt = fixedNow,
        planType = "plus",
        credits = AccountCreditsUi.Unavailable,
        isExpanded = false,
        alertToggles = emptyList(),
        quotaSummaries = emptyList(),
        providerIconResId = ProviderRegistry.iconFor(ProviderId("codex")),
    )

    @Test
    fun account_savedAccountsList() {
        forEachLocale { locale ->
            golden("account_saved_list", locale) {
                AccountScreen(
                    uiState = AccountUiState.Empty.copy(
                        contentStatus = AccountContentStatus.HasAccounts,
                        accounts = listOf(
                            accountItem(
                                id = "demo-local-1",
                                name = "Demo Main",
                                isDefault = true,
                                status = AccountStatus.Active,
                                tone = AccountStatusTone.Success,
                                badgeLabelResId = R.string.account_badge_default,
                                statusLabelResId = R.string.account_status_active,
                            ),
                            accountItem(
                                id = "demo-local-2",
                                name = "Work Backup",
                                isDefault = false,
                                status = AccountStatus.NeedsReauth,
                                tone = AccountStatusTone.Warning,
                                badgeLabelResId = R.string.account_badge_needs_reauth,
                                statusLabelResId = R.string.account_status_needs_reauth,
                            ),
                        ),
                    ),
                )
            }
        }
    }

    // endregion

    // region Auth matrix

    @Test
    fun auth_providerSelectionList() {
        forEachLocale { locale ->
            golden("auth_provider_selection", locale, heightDp = 1500) {
                ProviderSelectionScreen(
                    onProviderSelected = {},
                    onBack = {},
                )
            }
        }
    }

    @Test
    fun auth_deviceCodePolling() {
        forEachLocale { locale ->
            golden("auth_device_code_polling", locale) {
                AddAccountScreen(
                    uiState = DeviceCodeLoginUiState.Empty.copy(
                        status = DeviceCodeLoginUiStatus.PollingAuthorization,
                        attemptId = "demo-attempt",
                        userCode = "DEMO-1234",
                        verificationUri = "https://example.test/device",
                        pollIntervalSeconds = 5,
                    ),
                )
            }
        }
    }

    @Test
    fun auth_claudeManualPasteEnabled() {
        forEachLocale { locale ->
            golden("auth_claude_manual_paste", locale) {
                WebViewAuthScreen(
                    config = WebViewAuthConfig.OAuthIntercept(
                        providerId = ProviderId("claude"),
                        authorizationUrl = "https://example.test/oauth/authorize",
                        redirectUriPrefix = "https://example.test/callback",
                        expectedState = "demo-state",
                        appendStateToCode = false,
                        manualPasteEnabled = true,
                        tipResId = null,
                    ),
                    onCredentialExtracted = { _, _ -> Result.success(Unit) },
                    onBack = {},
                )
            }
        }
    }

    // endregion

    // region Settings matrix

    @Test
    fun settings_defaultOverview() {
        forEachLocale { locale ->
            golden("settings_overview", locale, heightDp = 2500) {
                SettingsScreen(uiState = SettingsUiState())
            }
        }
    }

    // endregion

    private companion object {
        const val PHONE_WIDTH_DP = 412
        const val DEFAULT_HEIGHT_DP = 892
    }
}
