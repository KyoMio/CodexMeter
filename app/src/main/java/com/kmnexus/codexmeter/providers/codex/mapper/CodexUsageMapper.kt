package com.kmnexus.codexmeter.providers.codex.mapper

import com.kmnexus.codexmeter.domain.model.LocalAccountId
import com.kmnexus.codexmeter.domain.model.ProviderAccountId
import com.kmnexus.codexmeter.domain.model.ProviderId
import com.kmnexus.codexmeter.domain.model.QuotaWindowId
import com.kmnexus.codexmeter.domain.model.SnapshotId
import com.kmnexus.codexmeter.domain.quota.Credits
import com.kmnexus.codexmeter.domain.quota.QuotaSnapshot
import com.kmnexus.codexmeter.domain.quota.QuotaSnapshotSource
import com.kmnexus.codexmeter.domain.quota.QuotaWindow
import com.kmnexus.codexmeter.domain.quota.QuotaWindowAvailability
import com.kmnexus.codexmeter.providers.codex.dto.CodexCreditsDto
import com.kmnexus.codexmeter.providers.codex.dto.CodexUsageResponseDto
import com.kmnexus.codexmeter.providers.codex.dto.CodexWindowDto
import java.time.DateTimeException
import java.time.Instant

class CodexUsageMapper {
    fun map(
        dto: CodexUsageResponseDto,
        localAccountId: LocalAccountId,
        providerAccountId: ProviderAccountId?,
        fetchedAt: Instant,
        source: QuotaSnapshotSource,
    ): QuotaSnapshot =
        QuotaSnapshot(
            snapshotId = SnapshotId(
                "codex:${localAccountId.value}:${fetchedAt.toEpochMilli()}:${source.name}",
            ),
            providerId = codexProviderId,
            localAccountId = localAccountId,
            providerAccountId = providerAccountId,
            fetchedAt = fetchedAt,
            source = source,
            planType = dto.planType,
            windows = listOf(
                toQuotaWindow(
                    windowId = QuotaWindowId(FIVE_HOUR_WINDOW_ID),
                    titleKey = FIVE_HOUR_TITLE_KEY,
                    providerWindow = dto.rateLimit?.primaryWindow,
                    decodeFailed = dto.rateLimit?.primaryWindowDecodeFailed == true,
                ),
                toQuotaWindow(
                    windowId = QuotaWindowId(WEEKLY_WINDOW_ID),
                    titleKey = WEEKLY_TITLE_KEY,
                    providerWindow = dto.rateLimit?.secondaryWindow,
                    decodeFailed = dto.rateLimit?.secondaryWindowDecodeFailed == true,
                ),
            ),
            credits = dto.toCredits(),
            responseDigest = null,
        )

    private fun toQuotaWindow(
        windowId: QuotaWindowId,
        titleKey: String,
        providerWindow: CodexWindowDto?,
        decodeFailed: Boolean,
    ): QuotaWindow {
        val resetAt = providerWindow?.resetAt?.toInstantOrNull()
        val resetDecodeFailed = providerWindow?.resetAt != null && resetAt == null
        val mappedWindow = providerWindow?.takeUnless { decodeFailed || resetDecodeFailed }
        val availability = when {
            decodeFailed || resetDecodeFailed -> QuotaWindowAvailability.DecodeFailed
            mappedWindow == null -> QuotaWindowAvailability.Missing
            else -> QuotaWindowAvailability.Available
        }

        return QuotaWindow(
            windowId = windowId,
            titleKey = titleKey,
            usedPercent = mappedWindow?.usedPercent,
            resetAt = mappedWindow?.let { resetAt },
            limitWindowSeconds = mappedWindow?.limitWindowSeconds,
            isPrimaryCandidate = true,
            availability = availability,
        )
    }

    private fun CodexUsageResponseDto.toCredits(): Credits? {
        if (creditsDecodeFailed) {
            return null
        }

        return credits?.toCredits()
    }

    private fun CodexCreditsDto.toCredits(): Credits? {
        val hasCredits = hasCredits ?: return null
        val unlimited = unlimited ?: return null
        return Credits(
            hasCredits = hasCredits,
            unlimited = unlimited,
            balance = balance,
        )
    }

    private fun Long.toInstantOrNull(): Instant? =
        try {
            Instant.ofEpochSecond(this)
        } catch (_: DateTimeException) {
            null
        }

    private companion object {
        val codexProviderId = ProviderId("codex")
        const val FIVE_HOUR_WINDOW_ID = "five_hour"
        const val FIVE_HOUR_TITLE_KEY = "quota_window_five_hour"
        const val WEEKLY_WINDOW_ID = "weekly"
        const val WEEKLY_TITLE_KEY = "quota_window_weekly"
    }
}
