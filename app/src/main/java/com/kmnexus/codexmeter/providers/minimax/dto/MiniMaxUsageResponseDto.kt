package com.kmnexus.codexmeter.providers.minimax.dto

import kotlinx.serialization.Serializable

/**
 * MiniMax coding-plan "remains" response. Mirrors CodexBar's `coding_plan/remains` payload: the
 * model list may sit at the top level or nested under `data`, and `base_resp.status_code` carries
 * the API status (0 = ok; 1004 / login messages = expired credentials).
 *
 * Counts semantics (matching CodexBar): `current_*_total_count` is the window limit and
 * `current_*_usage_count` is the REMAINING amount, so used = total - remaining.
 */
@Serializable
data class MiniMaxUsageResponseDto(
    val base_resp: BaseResp? = null,
    val data: DataNode? = null,
    val model_remains: List<ModelRemain> = emptyList(),
    val current_subscribe_title: String? = null,
    val plan_name: String? = null,
) {
    @Serializable
    data class BaseResp(
        val status_code: Int? = null,
        val status_msg: String? = null,
    )

    @Serializable
    data class DataNode(
        val base_resp: BaseResp? = null,
        val model_remains: List<ModelRemain> = emptyList(),
        val current_subscribe_title: String? = null,
        val plan_name: String? = null,
    )

    @Serializable
    data class ModelRemain(
        val model_name: String? = null,
        val current_interval_total_count: Int? = null,
        val current_interval_usage_count: Int? = null,
        val start_time: Long? = null,
        val end_time: Long? = null,
        val remains_time: Long? = null,
        val current_weekly_total_count: Int? = null,
        val current_weekly_usage_count: Int? = null,
        val weekly_start_time: Long? = null,
        val weekly_end_time: Long? = null,
        val weekly_remains_time: Long? = null,
        // Percent-based plans report `*_total_count == 0` and carry the remaining quota directly as a
        // 0–100 percent. Authoritative when present; the counts are the fallback for count-based plans.
        val current_interval_remaining_percent: Int? = null,
        val current_weekly_remaining_percent: Int? = null,
        // Per-capability access: 1 = entitled, 3 = no permission (capability not in the plan).
        val current_interval_status: Int? = null,
        val current_weekly_status: Int? = null,
    )

    val effectiveModelRemains: List<ModelRemain>
        get() = data?.model_remains?.takeIf { it.isNotEmpty() } ?: model_remains

    val statusCode: Int?
        get() = data?.base_resp?.status_code ?: base_resp?.status_code

    val statusMessage: String?
        get() = data?.base_resp?.status_msg ?: base_resp?.status_msg

    val planName: String?
        get() = (data?.current_subscribe_title ?: data?.plan_name ?: current_subscribe_title ?: plan_name)
            ?.takeIf { it.isNotBlank() }
}
