package com.kmnexus.codexmeter.providers

import com.kmnexus.codexmeter.domain.model.ProviderId

object ProviderRegistry {
    /** Known provider ID constants. */
    val CODEX = ProviderId("codex")
    val DEEPSEEK = ProviderId("deepseek")
    val ZAI = ProviderId("zai")
    val MINIMAX = ProviderId("minimax")
    val CURSOR = ProviderId("cursor")
    val KIMI = ProviderId("kimi")
    val CLAUDE = ProviderId("claude")
    val ANTIGRAVITY = ProviderId("antigravity")
    val ZAI_BALANCE = ProviderId("zai_balance")

    /**
     * All registered providers, ordered alphabetically by display name (the order shown in the
     * provider picker). The default provider is identified by the [ProviderConfig.isDefault] flag,
     * not by list position.
     */
    val all: List<ProviderConfig> = listOf(
        ProviderConfig(
            providerId = ANTIGRAVITY,
            displayName = "Antigravity",
            iconResId = com.kmnexus.codexmeter.R.drawable.ic_brand_antigravity,
            authKind = ProviderAuthKind.OAuthPkceLogin,
            supportsBalance = false,
            supportsRefill = false,
            supportsInviteLimitSnooping = false,
            isDefault = false,
        ),
        ProviderConfig(
            providerId = CLAUDE,
            displayName = "Claude",
            iconResId = com.kmnexus.codexmeter.R.drawable.ic_brand_claude,
            authKind = ProviderAuthKind.OAuthPkceLogin,
            supportsBalance = false,
            supportsRefill = false,
            supportsInviteLimitSnooping = false,
            isDefault = false,
        ),
        ProviderConfig(
            providerId = CODEX,
            displayName = "Codex",
            iconResId = com.kmnexus.codexmeter.R.drawable.ic_brand_codex,
            authKind = ProviderAuthKind.OAuthWebView,
            supportsBalance = false,
            supportsRefill = false,
            supportsInviteLimitSnooping = false,
            isDefault = true,
        ),
        ProviderConfig(
            providerId = CURSOR,
            displayName = "Cursor",
            iconResId = com.kmnexus.codexmeter.R.drawable.ic_brand_cursor,
            authKind = ProviderAuthKind.CookieAuth,
            supportsBalance = false,
            supportsRefill = false,
            supportsInviteLimitSnooping = false,
            isDefault = false,
        ),
        ProviderConfig(
            providerId = DEEPSEEK,
            displayName = "DeepSeek",
            iconResId = com.kmnexus.codexmeter.R.drawable.ic_brand_deepseek,
            authKind = ProviderAuthKind.ApiKeyImport,
            supportsBalance = true,
            supportsRefill = false,
            supportsInviteLimitSnooping = false,
            isDefault = false,
        ),
        ProviderConfig(
            providerId = KIMI,
            displayName = "Kimi",
            iconResId = com.kmnexus.codexmeter.R.drawable.ic_brand_kimi,
            authKind = ProviderAuthKind.CookieAuth,
            supportsBalance = false,
            supportsRefill = false,
            supportsInviteLimitSnooping = false,
            isDefault = false,
        ),
        ProviderConfig(
            providerId = MINIMAX,
            displayName = "MiniMax",
            iconResId = com.kmnexus.codexmeter.R.drawable.ic_brand_minimax,
            authKind = ProviderAuthKind.ApiKeyImport,
            supportsBalance = false,
            supportsRefill = false,
            supportsInviteLimitSnooping = false,
            isDefault = false,
        ),
        ProviderConfig(
            providerId = ZAI_BALANCE,
            displayName = "z.ai API",
            iconResId = com.kmnexus.codexmeter.R.drawable.ic_brand_zai,
            authKind = ProviderAuthKind.ApiKeyImport,
            supportsBalance = true,
            supportsRefill = false,
            supportsInviteLimitSnooping = false,
            isDefault = false,
        ),
        ProviderConfig(
            providerId = ZAI,
            displayName = "z.ai Coding Plan",
            iconResId = com.kmnexus.codexmeter.R.drawable.ic_brand_zai,
            authKind = ProviderAuthKind.ApiKeyImport,
            supportsBalance = false,
            supportsRefill = false,
            supportsInviteLimitSnooping = false,
            isDefault = false,
        ),
    )

    fun configFor(providerId: ProviderId): ProviderConfig =
        all.first { it.providerId == providerId }

    /** Brand icon for a provider, or null if the provider id is unknown (e.g. legacy data). */
    @androidx.annotation.DrawableRes
    fun iconFor(providerId: ProviderId): Int? =
        all.firstOrNull { it.providerId == providerId }?.iconResId

    /** Human-facing display name for a provider, falling back to the raw id when unknown. */
    fun displayNameFor(providerId: ProviderId): String =
        all.firstOrNull { it.providerId == providerId }?.displayName ?: providerId.value

    fun defaultProvider(): ProviderConfig = all.first { it.isDefault }
}

enum class ProviderAuthKind {
    OAuthWebView,
    ApiKeyImport,
    CookieAuth,
    OAuthPkceLogin,
}

data class ProviderConfig(
    val providerId: ProviderId,
    val displayName: String,
    @androidx.annotation.DrawableRes val iconResId: Int,
    val authKind: ProviderAuthKind,
    val supportsBalance: Boolean,
    val supportsRefill: Boolean,
    val supportsInviteLimitSnooping: Boolean,
    val isDefault: Boolean,
)
