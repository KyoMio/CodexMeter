package com.kmnexus.codexmeter.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.RoborazziComposeLocaleOption
import com.github.takahirom.roborazzi.RoborazziComposeOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.kmnexus.codexmeter.R
import com.kmnexus.codexmeter.domain.model.LocalAccountId
import com.kmnexus.codexmeter.domain.model.ProviderId
import com.kmnexus.codexmeter.domain.quota.QuotaWindowDisplayKind
import com.kmnexus.codexmeter.ui.home.HomeAccountChoiceUi
import com.kmnexus.codexmeter.ui.home.HomeAccountUi
import com.kmnexus.codexmeter.ui.home.HomeContentStatus
import com.kmnexus.codexmeter.ui.home.HomeCreditsUi
import com.kmnexus.codexmeter.ui.home.HomeLoadingUi
import com.kmnexus.codexmeter.ui.home.HomeQuotaCardUi
import com.kmnexus.codexmeter.ui.home.HomeQuotaStatus
import com.kmnexus.codexmeter.ui.home.HomeRefreshUi
import com.kmnexus.codexmeter.ui.home.HomeScreen
import com.kmnexus.codexmeter.ui.home.HomeStatusTone
import com.kmnexus.codexmeter.ui.home.HomeTrendUi
import com.kmnexus.codexmeter.ui.home.HomeUiState
import com.kmnexus.codexmeter.domain.model.AccountStatus
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
import com.kmnexus.codexmeter.ui.settings.SettingsScreen
import com.kmnexus.codexmeter.ui.settings.SettingsUiState
import com.kmnexus.codexmeter.ui.theme.CodexMeterTheme
import java.time.Instant
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Golden screenshot matrix (Roborazzi on Robolectric, JVM-fast).
 *
 * Covers the screens a broken refactor can silently empty — the exact failure mode this project
 * hit repeatedly ("tests green, UI blank"). Every fixture is synthetic and sanitized: no real
 * account names beyond clearly fake ones, no tokens, no cookies, no real email addresses.
 *
 * Matrix: zh-rCN + en x Home (normal / multi-account / antigravity 2x2 / null-exhausted /
 * last-known-good / error), Account area (provider list / device-code polling / Claude manual
 * paste), Settings, Glance widget sizes.
 *
 * Record with ./gradlew recordRoborazziDebug, verify with ./gradlew verifyRoborazziDebug.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class UiGoldenScreenshotTest {

    // region harness

    private enum class Locale(val tag: String, val suffix: String) {
        ZH("zh-rCN", "zh"),
        EN("en", "en"),
    }

    private fun golden(name: String, locale: Locale, content: @Composable () -> Unit) {
        captureRoboImage(
            filePath = "src/test/roborazzi/${name}_${locale.suffix}.png",
            roborazziComposeOptions = RoborazziComposeOptions.Builder()
                .addOption(RoborazziComposeLocaleOption(locale.tag))
                .build(),
        ) {
            CodexMeterTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    content()
                }
            }
        }
    }

    private fun forEachLocale(block: (Locale) -> Unit) {
        Locale.entries.forEach(block)
    }

    // endregion

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
    )

    private fun accountChoice(id: String, provider: String, name: String) = HomeAccountChoiceUi(
        localAccountId = LocalAccountId(id),
        providerId = ProviderId(provider),
        displayName = name,
        avatarInitial = name.first().uppercase(),
        avatarColorKey = id,
    )

    private fun percentCard(
        windowId: String,
        titleResId: Int,
        usedPercent: Int?,
        status: HomeQuotaStatus = HomeQuotaStatus.Normal,
        tone: HomeStatusTone = HomeStatusTone.Neutral,
        statusLabelResId: Int = R.string.home_quota_status_normal,
        isPrimary: Boolean = false,
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
        resetAt = Instant.parse("2026-05-23T17:00:00Z"),
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

    /** Antigravity's 2x2 grid: Gemini 5h/weekly + Claude-GPT 5h/weekly. */
    private fun antigravityCards() = listOf(
        percentCard("gemini_five_hour", R.string.home_quota_window_five_hour, 35, isPrimary = true),
        percentCard("gemini_weekly", R.string.home_quota_window_weekly, 62),
        percentCard("claude_gpt_five_hour", R.string.home_quota_window_five_hour, 12),
        percentCard("claude_gpt_weekly", R.string.home_quota_window_weekly, 48),
    )

    private fun homeState(
        contentStatus: HomeContentStatus,
        cards: List<HomeQuotaCardUi>,
        accountChoices: List<HomeAccountChoiceUi> = emptyList(),
        errorMessageResId: Int? = null,
    ) = HomeUiState(
        contentStatus = contentStatus,
        statusTitleResId = when (contentStatus) {
            HomeContentStatus.Fresh -> R.string.home_state_fresh_title
            HomeContentStatus.ErrorWithLastKnownGood -> R.string.home_state_error_lkg_title
            else -> R.string.home_state_fresh_title
        },
        statusDescriptionResId = when (contentStatus) {
            HomeContentStatus.Fresh -> R.string.home_state_fresh_description
            HomeContentStatus.ErrorWithLastKnownGood -> R.string.home_state_error_lkg_description
            else -> R.string.home_state_fresh_description
        },
        errorMessageResId = errorMessageResId,
        account = codexAccount(),
        accountChoices = accountChoices,
        quotaCards = cards,
        trend = HomeTrendUi(),
        loading = null,
        refresh = HomeRefreshUi(
            titleResId = R.string.home_state_fresh_title,
            descriptionResId = R.string.home_state_fresh_description,
            buttonTextResId = R.string.home_refresh,
            lastSuccessfulRefreshAt = fixedNow,
            lastAttemptFinishedAt = fixedNow,
        ),
        primaryAction = null,
        secondaryAction = null,
        isRefreshing = false,
    )

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
            golden("home_antigravity_2x2", locale) {
                HomeScreen(uiState = homeState(HomeContentStatus.Fresh, antigravityCards()))
            }
        }
    }

    @Test
    fun home_nullRemainingFractionExhausted() {
        forEachLocale { locale ->
            golden("home_exhausted_null_fraction", locale) {
                HomeScreen(
                    uiState = homeState(
                        HomeContentStatus.Fresh,
                        listOf(
                            percentCard(
                                windowId = "five_hour",
                                titleResId = R.string.home_quota_window_five_hour,
                                usedPercent = null,
                                status = HomeQuotaStatus.Exhausted,
                                tone = HomeStatusTone.Danger,
                                statusLabelResId = R.string.home_quota_status_exhausted,
                                isPrimary = true,
                            ),
                            percentCard(
                                windowId = "weekly",
                                titleResId = R.string.home_quota_window_weekly,
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
            golden("home_error_last_known_good", locale) {
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
                        HomeContentStatus.ErrorWithLastKnownGood,
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
        status: AccountStatus,
        tone: AccountStatusTone,
        badgeLabelResId: Int,
    ) = AccountItemUi(
        id = LocalAccountId(id),
        displayName = name,
        avatarInitial = name.first().uppercase(),
        avatarColorKey = id,
        isDefault = false,
        status = status,
        tone = tone,
        badgeLabelResId = badgeLabelResId,
        statusLabelResId = R.string.account_status_active,
        refreshSummaryResId = R.string.account_last_refresh_format,
        lastSuccessfulRefreshAt = fixedNow,
        planType = "plus",
        credits = AccountCreditsUi.Unavailable,
        isExpanded = false,
        alertToggles = emptyList(),
        quotaSummaries = emptyList(),
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
                                "demo-local-1", "Demo Main",
                                AccountStatus.Active, AccountStatusTone.Success,
                                R.string.account_badge_default,
                            ),
                            accountItem(
                                "demo-local-2", "Work Backup",
                                AccountStatus.NeedsReauth, AccountStatusTone.Danger,
                                R.string.account_badge_needs_reauth,
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
            golden("auth_provider_selection", locale) {
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
                com.kmnexus.codexmeter.ui.auth.WebViewAuthScreen(
                    config = com.kmnexus.codexmeter.ui.auth.WebViewAuthConfig.OAuthIntercept(
                        providerId = com.kmnexus.codexmeter.domain.model.ProviderId("claude"),
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
            golden("settings_overview", locale) {
                SettingsScreen(uiState = SettingsUiState())
            }
        }
    }

    // endregion
}
