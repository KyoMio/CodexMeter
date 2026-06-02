package com.kmnexus.codexmeter.providers.claude.session

import kotlinx.serialization.Serializable

@Serializable
data class ClaudeSessionPayload(
    val accessToken: String,
    val refreshToken: String? = null,
    val tokenExpiresAtEpochSeconds: Long? = null,
    val organizationId: String? = null,
    val accountId: String? = null,
)
