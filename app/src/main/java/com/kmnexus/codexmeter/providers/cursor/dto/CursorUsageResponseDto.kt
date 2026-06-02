package com.kmnexus.codexmeter.providers.cursor.dto

import kotlinx.serialization.Serializable

/**
 * Cursor `/api/usage-summary` response (CodexBar `CursorUsageSummary`). `plan` / `onDemand` /
 * `overall` report spend caps where `used` / `limit` / `remaining` are in CENTS.
 */
@Serializable
data class CursorUsageResponseDto(
    val individualUsage: IndividualUsage? = null,
    val membershipType: String? = null,
    val isUnlimited: Boolean? = null,
)

@Serializable
data class IndividualUsage(
    val plan: CursorUsageBucket? = null,
    val onDemand: CursorUsageBucket? = null,
    val overall: CursorUsageBucket? = null,
)

@Serializable
data class CursorUsageBucket(
    val enabled: Boolean? = null,
    val used: Int? = null,
    val limit: Int? = null,
    val remaining: Int? = null,
    // Free-tier plans report `limit == 0` but still expose a precomputed used-percent (0–100). When
    // present this is authoritative; `used / limit` is only the fallback for legacy cent-based plans.
    val totalPercentUsed: Double? = null,
    val autoPercentUsed: Double? = null,
    val apiPercentUsed: Double? = null,
)
