package com.kmnexus.codexmeter.providers.antigravity.dto

import kotlinx.serialization.Serializable

/**
 * Antigravity (Gemini Code Assist) quota, assembled from CodexBar's cloudcode-pa endpoints: the tier
 * comes from `:loadCodeAssist` and the per-model remaining fractions from `:retrieveUserQuota`'s
 * `buckets`. `remainingFraction` is 0–1; `resetTime` is ISO-8601.
 */
@Serializable
data class AntigravityQuotaResponseDto(
    val tier: String? = null,
    val buckets: List<QuotaBucket> = emptyList(),
) {
    @Serializable
    data class QuotaBucket(
        val modelId: String? = null,
        val remainingFraction: Double? = null,
        val resetTime: String? = null,
    )
}
