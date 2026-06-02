package com.kmnexus.codexmeter.domain.diagnostics

import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/**
 * Pure helpers for rendering epoch-millis timestamps into human-readable diagnostics text.
 * UTC ISO keeps output deterministic and avoids leaking the device's precise timezone.
 */
object DiagnosticsTimeFormatter {
    fun render(epochMillisText: String?, nowMillis: Long?): String? {
        if (epochMillisText == null) return null
        val millis = epochMillisText.toLongOrNull() ?: return epochMillisText
        val iso = Instant.ofEpochMilli(millis)
            .atOffset(ZoneOffset.UTC)
            .truncatedTo(ChronoUnit.SECONDS)
            .toInstant()
            .toString()
        val age = nowMillis?.let { formatAge(it - millis) }
        return if (age != null) "$epochMillisText ($iso, $age)" else "$epochMillisText ($iso)"
    }

    fun renderAgeOnly(epochMillisText: String?, nowMillis: Long?): String? {
        if (epochMillisText == null) return null
        val millis = epochMillisText.toLongOrNull() ?: return epochMillisText
        return if (nowMillis != null) formatAge(nowMillis - millis) else epochMillisText
    }

    fun formatAge(deltaMillis: Long): String {
        if (deltaMillis < 0) return "in the future"
        val seconds = deltaMillis / 1000
        return when {
            seconds < 60 -> "${seconds}s ago"
            seconds < 3600 -> "${seconds / 60}m ago"
            seconds < 86_400 -> "${seconds / 3600}h ago"
            else -> "${seconds / 86_400}d ago"
        }
    }
}
