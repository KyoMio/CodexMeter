package com.kmnexus.codexmeter.providers.kimicode.mapper

import com.kmnexus.codexmeter.domain.model.LocalAccountId
import com.kmnexus.codexmeter.domain.model.ProviderAccountId
import com.kmnexus.codexmeter.domain.model.ProviderId
import com.kmnexus.codexmeter.domain.model.QuotaWindowId
import com.kmnexus.codexmeter.domain.model.SnapshotId
import com.kmnexus.codexmeter.domain.quota.QuotaSnapshot
import com.kmnexus.codexmeter.domain.quota.QuotaSnapshotSource
import com.kmnexus.codexmeter.domain.quota.QuotaWindow
import com.kmnexus.codexmeter.domain.quota.QuotaWindowAvailability
import com.kmnexus.codexmeter.domain.quota.QuotaWindowDisplayKind
import com.kmnexus.codexmeter.providers.kimicode.dto.KimiCodeUsageResponseDto
import com.kmnexus.codexmeter.providers.kimicode.dto.KimiCodeUsageResponseDto.RatioDto
import com.kmnexus.codexmeter.providers.kimicode.dto.KimiCodeUsageResponseDto.UsageDetailDto
import com.kmnexus.codexmeter.providers.kimicode.dto.KimiCodeUsageResponseDto.WindowDto
import java.time.Instant
import java.time.OffsetDateTime
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * Maps the Kimi Code usage response into quota windows. Ratio pools win per slot (`limit_5h` /
 * `limit_7d`); the count-based `limits[0]` and top-level `usage` are only fallbacks for their slot
 * and are labeled by real duration / neutral "cycle" — never an inferred weekly or monthly period
 * (the Kimi Cookie provider's cycle-period lesson).
 */
object KimiCodeUsageMapper {
    private const val WINDOW_5H = "kimi_code_5h"
    private const val WINDOW_7D = "kimi_code_7d"
    private const val WINDOW_MONTH_TOTAL = "kimi_code_month_total"
    private const val WINDOW_RATE = "kimi_code_rate"
    private const val WINDOW_USAGE = "kimi_code_usage"

    private const val FIVE_HOUR_SECONDS = 5 * 3600
    private const val WEEK_SECONDS = 7 * 86400

    /**
     * Quota windows for a decoded response, without account identity. The usage client uses this to
     * reject zero-window payloads before a snapshot is produced.
     */
    fun buildWindows(dto: KimiCodeUsageResponseDto): List<QuotaWindow> = buildList {
        val ratioPool = dto.usages
        val fiveHour = ratioPool?.limit5h
        if (fiveHour?.usedRatio != null) {
            add(ratioWindow(WINDOW_5H, fiveHour, FIVE_HOUR_SECONDS, isPrimary = true))
        } else {
            dto.limits.firstOrNull()?.let { limit ->
                val window = limit.window ?: return@let
                countWindow(
                    detail = limit.detail ?: return@let,
                    windowId = WINDOW_RATE,
                    limitWindowSeconds = window.windowSeconds(),
                    isPrimary = true,
                )?.let(::add)
            }
        }

        val weekly = ratioPool?.limit7d
        if (weekly?.usedRatio != null) {
            add(ratioWindow(WINDOW_7D, weekly, WEEK_SECONDS, isPrimary = false))
        } else {
            dto.usage?.let { usage ->
                // The top-level usage block has no verified window length; never infer one.
                countWindow(usage, WINDOW_USAGE, limitWindowSeconds = null, isPrimary = false)?.let(::add)
            }
        }

        val monthly = ratioPool?.limitMonthTotal
        if (monthly?.usedRatio != null) {
            // Month-total usage spans a calendar month, so no fixed duration is assumed.
            add(ratioWindow(WINDOW_MONTH_TOTAL, monthly, null, isPrimary = false))
        }
    }

    fun map(
        dto: KimiCodeUsageResponseDto,
        localAccountId: LocalAccountId,
        providerAccountId: ProviderAccountId?,
        fetchedAt: Instant,
        source: QuotaSnapshotSource,
    ): QuotaSnapshot = QuotaSnapshot(
        snapshotId = SnapshotId("kimi_code_${fetchedAt}"),
        providerId = KIMI_CODE_PROVIDER_ID,
        localAccountId = localAccountId,
        providerAccountId = providerAccountId,
        fetchedAt = fetchedAt,
        source = source,
        planType = null,
        windows = buildWindows(dto),
        credits = null,
        responseDigest = null,
    )

    private fun ratioWindow(
        windowId: String,
        ratio: RatioDto,
        limitWindowSeconds: Int?,
        isPrimary: Boolean,
    ): QuotaWindow {
        val usedPercent = (((ratio.usedRatio ?: 0.0) * 100).toInt()).coerceIn(0, 100)
        return QuotaWindow(
            windowId = QuotaWindowId(windowId),
            titleKey = windowId,
            usedPercent = usedPercent,
            resetAt = parseIso(ratio.resetTime),
            limitWindowSeconds = limitWindowSeconds,
            isPrimaryCandidate = isPrimary,
            availability = if (usedPercent >= 100) {
                QuotaWindowAvailability.Depleted
            } else {
                QuotaWindowAvailability.Available
            },
            displayKind = QuotaWindowDisplayKind.Percent,
        )
    }

    private fun countWindow(
        detail: UsageDetailDto,
        windowId: String,
        limitWindowSeconds: Int?,
        isPrimary: Boolean,
    ): QuotaWindow? {
        val limit = countValue(detail.limit) ?: return null
        if (limit <= 0) return null
        val remaining = countValue(detail.remaining)
        val used = countValue(detail.used)
            ?: remaining?.let { (limit - it).coerceAtLeast(0.0) }
            ?: 0.0
        val usedPercent = ((used / limit) * 100).toInt().coerceIn(0, 100)
        val availability = when {
            remaining != null && remaining <= 0 -> QuotaWindowAvailability.Depleted
            used >= limit -> QuotaWindowAvailability.Depleted
            else -> QuotaWindowAvailability.Available
        }

        return QuotaWindow(
            windowId = QuotaWindowId(windowId),
            titleKey = windowId,
            usedPercent = usedPercent,
            resetAt = parseIso(detail.resetTime),
            limitWindowSeconds = limitWindowSeconds,
            isPrimaryCandidate = isPrimary,
            availability = availability,
            displayKind = QuotaWindowDisplayKind.Percent,
            usedCount = used.toInt(),
            limitCount = limit.toInt(),
        )
    }

    /** Count fields may arrive as JSON strings, ints or doubles; anything else is missing. */
    private fun countValue(value: JsonPrimitive?): Double? {
        if (value == null || value is JsonNull) return null
        return value.content.toDoubleOrNull()
    }

    private fun WindowDto.windowSeconds(): Int? {
        val duration = duration ?: return null
        if (duration <= 0) return null
        val multiplier = when (timeUnit) {
            "TIME_UNIT_MINUTE" -> 60L
            "TIME_UNIT_HOUR" -> 3600L
            "TIME_UNIT_DAY" -> 86400L
            else -> return null
        }
        return (duration * multiplier).takeIf { it <= Int.MAX_VALUE }?.toInt()
    }

    private fun parseIso(value: String?): Instant? {
        if (value.isNullOrBlank()) return null
        return runCatching { Instant.parse(value) }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull()
    }

    private val KIMI_CODE_PROVIDER_ID = ProviderId("kimi_code")
}
