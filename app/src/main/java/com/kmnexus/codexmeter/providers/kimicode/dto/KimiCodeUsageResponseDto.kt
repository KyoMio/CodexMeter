package com.kmnexus.codexmeter.providers.kimicode.dto

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.json.JsonPrimitive

/**
 * Kimi Code `GET /coding/v1/usages` response (CodexBar-shaped, not captured from production):
 * every field is optional and unknown keys are ignored, so a payload that drifts from the fixture
 * degrades to missing windows instead of failing the whole fetch. The `user` block is unused and
 * simply ignored.
 */
@Serializable
data class KimiCodeUsageResponseDto(
    val usage: UsageDetailDto? = null,
    val usages: RatioPoolDto? = null,
    val limits: List<LimitDto> = emptyList(),
) {
    /**
     * Count-based detail. The count fields may arrive as JSON strings, ints or doubles, so they are
     * kept raw and converted by the mapper; `resetTime` appears under several alias keys upstream.
     */
    @OptIn(ExperimentalSerializationApi::class)
    @Serializable
    data class UsageDetailDto(
        val limit: JsonPrimitive? = null,
        val used: JsonPrimitive? = null,
        val remaining: JsonPrimitive? = null,
        @JsonNames("resetAt", "reset_time", "reset_at")
        val resetTime: String? = null,
    )

    /** One ratio-pool entry: `used_ratio` is a 0–1 fraction that may go out of bounds. */
    @OptIn(ExperimentalSerializationApi::class)
    @Serializable
    data class RatioDto(
        @SerialName("used_ratio") val usedRatio: Double? = null,
        @SerialName("reset_time")
        @JsonNames("resetAt", "reset_at")
        val resetTime: String? = null,
    )

    @Serializable
    data class RatioPoolDto(
        @SerialName("limit_5h") val limit5h: RatioDto? = null,
        @SerialName("limit_7d") val limit7d: RatioDto? = null,
        @SerialName("limit_month_total") val limitMonthTotal: RatioDto? = null,
    )

    @Serializable
    data class LimitDto(
        val window: WindowDto? = null,
        val detail: UsageDetailDto? = null,
    )

    @Serializable
    data class WindowDto(
        val duration: Int? = null,
        val timeUnit: String? = null,
    )
}
