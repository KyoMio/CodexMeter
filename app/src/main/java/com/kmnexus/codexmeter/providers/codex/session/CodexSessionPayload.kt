package com.kmnexus.codexmeter.providers.codex.session

import java.time.Instant

data class CodexSessionPayload(
    val accessToken: String,
    val refreshToken: String,
    val idToken: String?,
    val accountId: String?,
    val accountEmail: String? = null,
    val lastRefresh: Instant?,
)
