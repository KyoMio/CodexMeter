package com.kmnexus.codexmeter.providers.zai.dto

import kotlinx.serialization.Serializable

/**
 * z.ai quota-limit response (`api/monitor/usage/quota/limit`), mirroring CodexBar's
 * `ZaiQuotaLimitResponse`. Success is `code == 200` (or `success == true`). Every field is optional
 * because the API omits quota fields for unused windows. Semantics: `usage` is the limit/total,
 * `currentValue` (or `usage − remaining`) is the used amount, and `percentage` is the used percent.
 */
@Serializable
data class ZaiQuotaResponseDto(
    val code: Int? = null,
    val msg: String? = null,
    val success: Boolean? = null,
    val data: QuotaData? = null,
) {
    @Serializable
    data class QuotaData(
        val limits: List<LimitItem> = emptyList(),
        val planName: String? = null,
        val level: String? = null, // e.g. "pro" — plan tier, not a number
    )

    @Serializable
    data class LimitItem(
        val type: String? = null,
        val unit: Int? = null,        // 1=days, 3=hours, 5=minutes, 6=weeks
        val number: Int? = null,
        val usage: Int? = null,       // limit / total
        val currentValue: Int? = null, // used
        val remaining: Int? = null,
        val percentage: Double? = null, // used percent (fallback)
        val nextResetTime: Long? = null, // unix ms
    )

    val isSuccess: Boolean
        get() = success == true || code == 200 || code == 0
}
