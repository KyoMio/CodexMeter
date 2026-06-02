package com.kmnexus.codexmeter.providers.zai.session

import kotlinx.serialization.Serializable

@Serializable
data class ZaiSessionPayload(
    val apiKey: String,
    val accountId: String? = null,
    /** Region API base URL chosen at import. Null = legacy session → default (China) base. */
    val apiBaseUrl: String? = null,
)
