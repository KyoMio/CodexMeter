package com.kmnexus.codexmeter.ui.home

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.kmnexus.codexmeter.R
import com.kmnexus.codexmeter.domain.quota.QuotaWindowDisplayKind
import java.time.Instant

enum class HomeContentStatus {
    Unauthenticated,
    Loading,
    Fresh,
    PossiblyStale,
    Expired,
    AuthRequired,
    ErrorWithLastKnownGood,
    NoData,
}

enum class HomeQuotaStatus {
    Normal,
    Caution,
    Warning,
    Exhausted,
    Unavailable,
}

enum class HomeStatusTone {
    Neutral,
    Success,
    Warning,
    Danger,
}

enum class HomeActionKind {
    LoginToCodex,
}

data class HomeAccountUi(
    val displayName: String,
    val avatarInitial: String,
    val avatarColorKey: String,
    val planType: String?,
    val credits: HomeCreditsUi,
    @param:DrawableRes val providerIconResId: Int? = null,
)

sealed class HomeCreditsUi {
    data class Balance(val amount: Double) : HomeCreditsUi()
    object Unlimited : HomeCreditsUi()
    object Unavailable : HomeCreditsUi()
}

data class HomeActionUi(
    val kind: HomeActionKind,
    @get:StringRes val labelResId: Int,
)

data class HomeQuotaCardUi(
    val windowId: String,
    @get:StringRes val titleResId: Int,
    val displayKind: QuotaWindowDisplayKind,
    val usedPercent: Int?,
    val balanceAmount: String?,
    val balanceCurrency: String?,
    val usedCount: Int?,
    val limitCount: Int?,
    val subLabel: String?,
    val isPrimary: Boolean,
    val resetAt: Instant?,
    val status: HomeQuotaStatus,
    val tone: HomeStatusTone,
    @get:StringRes val statusLabelResId: Int,
    val originalBalanceAmount: String? = null,
    val originalBalanceCurrency: String? = null,
    val grantedBalance: String? = null,
    val toppedUpBalance: String? = null,
) {
    /** Remaining percent for PercentQuotaCard rendering (100 - usedPercent). */
    val percent: Int? get() = usedPercent?.let { 100 - it.coerceIn(0, 100) }
}

data class HomeTrendUi(
    @get:StringRes val titleResId: Int = R.string.home_trend_placeholder_title,
    @get:StringRes val descriptionResId: Int = R.string.home_trend_placeholder_description,
    @get:StringRes val metricLabelResId: Int = R.string.home_trend_metric_usage,
    val points: List<HomeTrendPointUi> = emptyList(),
)

data class HomeTrendPointUi(
    val capturedAt: Instant,
    val usageValue: Double,
    val xPositionInWindow: Float? = null,
)

data class HomeLoadingUi(
    @get:StringRes val titleResId: Int,
    @get:StringRes val descriptionResId: Int,
)

data class HomeRefreshUi(
    @get:StringRes val titleResId: Int,
    @get:StringRes val descriptionResId: Int,
    @get:StringRes val buttonTextResId: Int,
    val lastSuccessfulRefreshAt: Instant?,
    val lastAttemptFinishedAt: Instant?,
)

data class HomeUiState(
    val contentStatus: HomeContentStatus,
    @get:StringRes val titleResId: Int = R.string.app_name,
    @get:StringRes val statusTitleResId: Int,
    @get:StringRes val statusDescriptionResId: Int,
    @get:StringRes val errorMessageResId: Int?,
    val account: HomeAccountUi?,
    val quotaCards: List<HomeQuotaCardUi> = emptyList(),
    val trend: HomeTrendUi,
    val loading: HomeLoadingUi?,
    val refresh: HomeRefreshUi,
    val primaryAction: HomeActionUi?,
    val secondaryAction: HomeActionUi?,
    val isRefreshing: Boolean,
    val manualRefreshSuccessCount: Int = 0,
) {
    /** Backward-compat computed property for the five-hour window card. */
    val fiveHourCard: HomeQuotaCardUi? get() = quotaCards.firstOrNull { it.windowId == "five_hour" }

    /** Backward-compat computed property for the weekly window card. */
    val weeklyCard: HomeQuotaCardUi? get() = quotaCards.firstOrNull { it.windowId == "weekly" }

    companion object {
        fun loading(): HomeUiState =
            HomeUiState(
                contentStatus = HomeContentStatus.Loading,
                statusTitleResId = R.string.home_state_loading_title,
                statusDescriptionResId = R.string.home_state_loading_description,
                errorMessageResId = null,
                account = null,
                quotaCards = emptyList(),
                trend = HomeTrendUi(),
                loading = HomeLoadingUi(
                    titleResId = R.string.home_loading_card_title,
                    descriptionResId = R.string.home_loading_card_description,
                ),
                refresh = HomeRefreshUi(
                    titleResId = R.string.home_state_loading_title,
                    descriptionResId = R.string.home_state_loading_description,
                    buttonTextResId = R.string.home_refreshing,
                    lastSuccessfulRefreshAt = null,
                    lastAttemptFinishedAt = null,
                ),
                primaryAction = null,
                secondaryAction = null,
                isRefreshing = true,
                manualRefreshSuccessCount = 0,
            )

        fun unauthenticated(isRefreshing: Boolean = false): HomeUiState =
            HomeUiState(
                contentStatus = HomeContentStatus.Unauthenticated,
                statusTitleResId = R.string.home_state_unauthenticated_title,
                statusDescriptionResId = R.string.home_state_unauthenticated_description,
                errorMessageResId = null,
                account = null,
                quotaCards = emptyList(),
                trend = HomeTrendUi(),
                loading = null,
                refresh = HomeRefreshUi(
                    titleResId = R.string.home_state_unauthenticated_title,
                    descriptionResId = R.string.home_state_unauthenticated_description,
                    buttonTextResId = if (isRefreshing) R.string.home_refreshing else R.string.home_refresh,
                    lastSuccessfulRefreshAt = null,
                    lastAttemptFinishedAt = null,
                ),
                primaryAction = HomeActionUi(
                    kind = HomeActionKind.LoginToCodex,
                    labelResId = R.string.home_login_to_codex,
                ),
                secondaryAction = null,
                isRefreshing = isRefreshing,
                manualRefreshSuccessCount = 0,
            )
    }
}
