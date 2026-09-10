package com.kmnexus.codexmeter.providers.codex.session

import java.time.Instant

data class CodexSessionPayload(
    val accessToken: String,
    val refreshToken: String,
    val idToken: String?,
    val accountId: String?,
    val accountEmail: String? = null,
    val lastRefresh: Instant?,
    /**
     * When the access token stops being usable, from the token endpoint's `expires_in`. Null for
     * sessions saved before this field existed; those refresh once on next use and then carry it.
     */
    val tokenExpiresAtEpochSeconds: Long? = null,
)
