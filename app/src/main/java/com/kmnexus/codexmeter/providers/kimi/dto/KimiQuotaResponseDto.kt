package com.kmnexus.codexmeter.providers.kimi.dto

import kotlinx.serialization.Serializable

/**
 * Response of `kimi.gateway.billing.v1.BillingService/GetUsages` (www.kimi.com), mirroring CodexBar's
 * `KimiUsageResponse`. The coding scope's `detail` is the weekly quota; `limits[].detail` carries the
 * short rate-limit window. Counts arrive as strings; `resetTime` is ISO-8601.
 */
@Serializable
data class KimiQuotaResponseDto(
    val usages: List<Usage> = emptyList(),
) {
    @Serializable
    data class Usage(
        val scope: String? = null,
        val detail: Detail? = null,
        val limits: List<RateLimit> = emptyList(),
    )

    @Serializable
    data class Detail(
        val limit: String? = null,
        val used: String? = null,
        val remaining: String? = null,
        val resetTime: String? = null,
    )

    @Serializable
    data class RateLimit(
        val window: Window? = null,
        val detail: Detail? = null,
    )

    @Serializable
    data class Window(
        val duration: Int? = null,
        val timeUnit: String? = null,
    )

    /** The coding-feature usage, falling back to the first entry. */
    fun codingUsage(): Usage? =
        usages.firstOrNull { it.scope == FEATURE_CODING } ?: usages.firstOrNull()

    companion object {
        const val FEATURE_CODING = "FEATURE_CODING"
    }
}
