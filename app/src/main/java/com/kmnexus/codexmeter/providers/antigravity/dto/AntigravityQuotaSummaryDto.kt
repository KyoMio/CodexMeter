package com.kmnexus.codexmeter.providers.antigravity.dto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * `:retrieveUserQuotaSummary` response from daily-cloudcode-pa.googleapis.com, CodexBar's primary
 * Antigravity quota source. The envelope carries its payload either under `response` / `summary` or
 * as bare top-level `groups` + `description`; groups hold per-lane quota buckets (5-hour session,
 * weekly, ...). All fields optional with unknown keys ignored — proto3 JSON omits default values.
 */
@Serializable
data class AntigravityQuotaSummaryDto(
    val code: JsonElement? = null,
    val response: SummaryPayload? = null,
    val summary: SummaryPayload? = null,
    val description: String? = null,
    val groups: List<QuotaGroup> = emptyList(),
) {
    /** Resolved groups: `response` wins over `summary`, which wins over bare top-level groups. */
    fun payload(): SummaryPayload =
        response ?: summary ?: SummaryPayload(description = description, groups = groups)

    /**
     * True when some non-disabled bucket reports a remaining fraction at all. Without it the
     * summary carries no usable quota and callers fall back to the per-model endpoints.
     */
    fun hasKnownQuotaSummary(): Boolean =
        payload().groups.any { group ->
            group.buckets.any { bucket -> bucket.disabled != true && bucket.remainingFractionOrNull() != null }
        }

    @Serializable
    data class SummaryPayload(
        val description: String? = null,
        val groups: List<QuotaGroup> = emptyList(),
    )

    @Serializable
    data class QuotaGroup(
        val displayName: String? = null,
        val name: String? = null,
        val description: String? = null,
        val buckets: List<QuotaSummaryBucket> = emptyList(),
    )

    @Serializable
    data class QuotaSummaryBucket(
        val bucketId: String? = null,
        val id: String? = null,
        val displayName: String? = null,
        val name: String? = null,
        val remainingFraction: Double? = null,
        val remaining: Remaining? = null,
        val resetTime: String? = null,
        val description: String? = null,
        val disabled: Boolean? = null,
        val window: String? = null,
    ) {
        /** proto3 JSON sometimes wraps the fraction in a nested `remaining` message. */
        fun remainingFractionOrNull(): Double? = remainingFraction ?: remaining?.remainingFraction

        @Serializable
        data class Remaining(val remainingFraction: Double? = null)
    }
}
