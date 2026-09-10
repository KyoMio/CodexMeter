package com.kmnexus.codexmeter.providers.grok.dto

import kotlinx.serialization.Serializable

/**
 * Defensive projection of the grok CLI billing endpoint (`/v1/billing?format=credits`, see
 * docs/research/2026-09-10-grok-provider-login-research.md section 2.3). The endpoint is an
 * undocumented internal contract, so every field is nullable and the client tree-parses instead of
 * binding strictly; missing fields degrade to "unavailable" in the mapper, never to a crash.
 * Timestamps stay as raw strings here — ISO-8601 parsing belongs to the mapper.
 */
@Serializable
data class GrokBillingResponseDto(
    val currentPeriod: GrokBillingPeriodDto? = null,
    val creditUsagePercent: Double? = null,
    val productUsage: List<GrokProductUsageDto> = emptyList(),
    val prepaidBalance: Double? = null,
    val billingPeriodStart: String? = null,
    val billingPeriodEnd: String? = null,
)

@Serializable
data class GrokBillingPeriodDto(
    val type: String? = null,
    val start: String? = null,
    val end: String? = null,
)

@Serializable
data class GrokProductUsageDto(
    val product: String? = null,
    val usagePercent: Double? = null,
)
