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

@StringRes
fun quotaWindowLabelRes(windowId: String): Int = when (windowId) {
    "five_hour", "zai_5h_window", "claude_5h_window", "kimi_rate_window", "minimax_interval" ->
        R.string.account_quota_five_hour_label
    "weekly", "zai_weekly_window", "kimi_weekly_window", "minimax_weekly" -> R.string.account_quota_weekly_label
    "claude_extra_usage" -> R.string.window_label_extra_usage
    "balance" -> R.string.window_label_balance
    "cursor_plan" -> R.string.window_label_plan
    "cursor_on_demand" -> R.string.window_label_on_demand
    "minimax_interval" -> R.string.window_label_interval
    "kimi_daily_window" -> R.string.window_label_daily
    "kimi_monthly_window" -> R.string.window_label_monthly
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
