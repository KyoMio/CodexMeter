package com.kmnexus.codexmeter.providers.deepseek.dto

import kotlinx.serialization.Serializable

@Serializable
data class DeepSeekBalanceResponseDto(
    val is_available: Boolean,
    val balance_infos: List<BalanceInfo>,
) {
    @Serializable
    data class BalanceInfo(
        val currency: String,
        val total_balance: String,
        val granted_balance: String,
        val topped_up_balance: String,
    )
}
