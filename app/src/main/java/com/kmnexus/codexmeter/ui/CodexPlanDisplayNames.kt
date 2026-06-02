package com.kmnexus.codexmeter.ui

import com.kmnexus.codexmeter.domain.model.ProviderId
import com.kmnexus.codexmeter.providers.ProviderRegistry
import java.util.Locale

/**
 * Provider-aware subscription/plan display name. Each provider exposes a different raw value, so we
 * mirror CodexBar's per-provider formatting instead of applying Codex's "Pro 20x" mapping to all:
 *
 * - Codex: `codexPlanDisplayName` (pro -> "Pro 20x", prolite -> "Pro 5x", else titlecase)
 * - Cursor: `formatMembershipType` ("pro" -> "Cursor Pro", "hobby" -> "Cursor Hobby", …)
 * - z.ai: raw `level`/`planName`, capitalized ("pro" -> "Pro")
 * - MiniMax (current_subscribe_title) and Antigravity (tier name): already human-facing, verbatim
 * - Kimi / Claude / DeepSeek: no plan value (returns null)
 */
fun providerPlanDisplayName(providerId: ProviderId, planType: String?): String? {
    val raw = planType?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return when (providerId) {
        ProviderRegistry.CODEX -> codexPlanDisplayName(raw)
        ProviderRegistry.CURSOR -> cursorPlanDisplayName(raw)
        ProviderRegistry.ZAI -> raw.replaceFirstChar { it.titlecase(Locale.ROOT) }
        else -> raw
    }
}

internal fun codexPlanDisplayName(planType: String?): String? {
    val raw = planType?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return when (raw.lowercase(Locale.ROOT)) {
        "pro" -> "Pro 20x"
        "prolite", "pro_lite", "pro-lite", "pro lite" -> "Pro 5x"
        else -> raw.replaceFirstChar { it.titlecase(Locale.ROOT) }
    }
}

private fun cursorPlanDisplayName(raw: String): String =
    when (raw.lowercase(Locale.ROOT)) {
        "enterprise" -> "Cursor Enterprise"
        "pro" -> "Cursor Pro"
        "hobby" -> "Cursor Hobby"
        "team" -> "Cursor Team"
        else -> "Cursor " + raw.replaceFirstChar { it.titlecase(Locale.ROOT) }
    }
