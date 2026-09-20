package com.kmnexus.codexmeter.ui.quota

import androidx.annotation.StringRes
import com.kmnexus.codexmeter.R

/**
 * Maps a provider's `QuotaWindow.windowId` to a localized display label, so Home/Account/Widget
 * never surface raw mapper keys like `zai_5h_window`. Unknown ids fall back to a generic label.
 */
/**
 * Formats a balance amount with a currency symbol (¥ / $) consistently across Home, Account and the
 * widget. Unknown currencies keep their ISO code as a prefix. Returns null for a blank amount.
 */
fun formatProviderBalance(amount: String?, currency: String?): String? {
    if (amount.isNullOrBlank()) return null
    val symbol = currencySymbol(currency)
    return "$symbol$amount"
}

/** Symbol for an ISO currency code, matching formatProviderBalance's mapping. */
fun currencySymbol(currency: String?): String = when (currency?.uppercase()) {
    "CNY", "RMB" -> "¥"
    "USD" -> "$"
    "EUR" -> "€"
    "GBP" -> "£"
    "JPY" -> "JP¥"
    null, "" -> ""
    else -> "$currency "
}

/**
 * Codex is the only provider that names its windows by position: the usage API exposes
 * `primary_window` / `secondary_window`, so the mapper can only guess `five_hour` / `weekly` from
 * the slot an entry arrived in. That guess broke when Codex dropped the 5-hour window and its
 * `primary_window` started carrying the weekly quota. For these two ids the window duration the
 * provider reports is the truth. Every other provider names its own windows, so their ids keep
 * winning — Claude's 7-day Opus and Sonnet windows share one duration but must stay distinguishable.
 */
private val POSITIONAL_WINDOW_IDS = setOf("five_hour", "weekly")

private const val FIVE_HOUR_SECONDS = 5 * 60 * 60
private const val DAY_SECONDS = 24 * 60 * 60
private const val WEEK_SECONDS = 7 * DAY_SECONDS

@StringRes
fun quotaWindowLabelRes(windowId: String, limitWindowSeconds: Int? = null): Int {
    if (windowId in POSITIONAL_WINDOW_IDS) {
        // A duration we cannot name (or a missing window that reports none) is still better served by
        // the neutral label than by a positional guess that would state a window length the provider
        // never reported — and could collide with the sibling slot's real label.
        return limitWindowSeconds?.let(::windowDurationLabelRes) ?: R.string.window_label_generic
    }
    return providerNamedLabelRes(windowId)
}

@StringRes
private fun windowDurationLabelRes(limitWindowSeconds: Int): Int? = when (limitWindowSeconds) {
    FIVE_HOUR_SECONDS -> R.string.account_quota_five_hour_label
    DAY_SECONDS -> R.string.window_label_daily
    WEEK_SECONDS -> R.string.account_quota_weekly_label
    else -> null
}

@StringRes
private fun providerNamedLabelRes(windowId: String): Int = when (windowId) {
    "zai_5h_window", "claude_5h_window", "kimi_rate_window", "minimax_interval" ->
        R.string.account_quota_five_hour_label
    "zai_weekly_window", "kimi_weekly_window", "minimax_weekly" -> R.string.account_quota_weekly_label
    "claude_extra_usage" -> R.string.window_label_extra_usage
    "balance" -> R.string.window_label_balance
    "cursor_plan" -> R.string.window_label_plan
    "cursor_on_demand" -> R.string.window_label_on_demand
    "minimax_interval" -> R.string.window_label_interval
    "kimi_daily_window" -> R.string.window_label_daily
    "monthly", "kimi_monthly_window" -> R.string.window_label_monthly
    "claude_7d_window" -> R.string.window_label_7d
    "claude_7d_opus_window" -> R.string.window_label_7d_opus
    "claude_7d_sonnet_window" -> R.string.window_label_7d_sonnet
    "antigravity_overview_window" -> R.string.window_label_overview
    "antigravity_claude_window" -> R.string.window_label_family_claude
    "antigravity_gemini_pro_window" -> R.string.window_label_family_gemini_pro
    "antigravity_gemini_flash_window" -> R.string.window_label_family_gemini_flash
    "antigravity_gpt_oss_window" -> R.string.window_label_family_gpt_oss
    else -> when {
        windowId.contains("time_limit", ignoreCase = true) ||
            windowId.contains("mcp", ignoreCase = true) -> R.string.window_label_mcp
        else -> R.string.window_label_generic
    }
}
