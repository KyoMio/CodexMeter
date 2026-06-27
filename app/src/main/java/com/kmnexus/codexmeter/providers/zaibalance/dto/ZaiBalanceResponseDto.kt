package com.kmnexus.codexmeter.providers.zaibalance.dto

import kotlinx.serialization.Serializable

/**
 * Minimal projection of bigmodel's `query-customer-account-report` response. Per product decision
 * only [availableBalance] (可用余额) is surfaced; all other money fields are intentionally dropped.
 * Amounts arrive as BigDecimal JSON numbers (may use scientific notation, e.g. `0E-9`).
 */
@Serializable
data class ZaiBalanceResponseDto(
    val code: Int? = null,
    val success: Boolean? = null,
    val availableBalance: Double? = null,
)
