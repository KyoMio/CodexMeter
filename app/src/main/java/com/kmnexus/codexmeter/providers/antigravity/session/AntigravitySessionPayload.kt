package com.kmnexus.codexmeter.providers.antigravity.session

import kotlinx.serialization.Serializable

@Serializable
data class AntigravitySessionPayload(
    val accessToken: String,
    val refreshToken: String? = null,
    val tokenExpiresAtEpochSeconds: Long? = null,
    val planTier: String? = null,
)
