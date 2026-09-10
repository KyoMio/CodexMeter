package com.kmnexus.codexmeter.providers.grok.session

import kotlinx.serialization.Serializable

/**
 * Persisted grok OAuth session. `tokenEndpoint` caches the OIDC-discovered token endpoint so refresh
 * does not re-discover every time; a null or legacy `https://auth.x.ai/oauth/token` value makes the
 * refresher re-run discovery and write the current endpoint back.
 */
@Serializable
data class GrokSessionPayload(
    val accessToken: String,
    val refreshToken: String,
    val idToken: String? = null,
    val tokenExpiresAtEpochSeconds: Long? = null,
    val tokenEndpoint: String? = null,
    val accountId: String? = null,
    val email: String? = null,
    val lastRefreshEpochSeconds: Long? = null,
) {
    override fun toString(): String =
        "GrokSessionPayload(" +
            "accessToken=[REDACTED], " +
            "refreshToken=[REDACTED], " +
            "idToken=[REDACTED], " +
            "tokenExpiresAtEpochSeconds=$tokenExpiresAtEpochSeconds, " +
            "tokenEndpoint=$tokenEndpoint, " +
            "accountId=[REDACTED], " +
            "email=[REDACTED], " +
            "lastRefreshEpochSeconds=$lastRefreshEpochSeconds" +
            ")"
}
