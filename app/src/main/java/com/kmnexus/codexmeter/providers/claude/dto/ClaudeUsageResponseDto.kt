package com.kmnexus.codexmeter.providers.claude.dto

import kotlinx.serialization.Serializable

/**
 * Claude OAuth usage response (`/api/oauth/usage`), mirroring CodexBar's `OAuthUsageResponse`. Each
 * window reports a `utilization` percentage (0–100) and an ISO-8601 `resets_at`.
 */
@Serializable
data class ClaudeUsageResponseDto(
    val five_hour: Window? = null,
    val seven_day: Window? = null,
    val seven_day_opus: Window? = null,
    val seven_day_sonnet: Window? = null,
    val extra_usage: ExtraUsage? = null,
) {
    @Serializable
    data class Window(
        val utilization: Double? = null,
        val resets_at: String? = null,
    )

    /**
     * Pay-as-you-go spend beyond the subscription, mirroring CodexBar's `OAuthExtraUsage`. Amounts
     * are in cents; `utilization` is a 0–100 percent of the monthly cap.
     */
    @Serializable
    data class ExtraUsage(
        val is_enabled: Boolean? = null,
        val monthly_limit: Double? = null,
        val used_credits: Double? = null,
        val utilization: Double? = null,
        val currency: String? = null,
    )
}
