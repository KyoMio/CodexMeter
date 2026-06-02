package com.kmnexus.codexmeter.providers.minimax.session

import kotlinx.serialization.Serializable

@Serializable
data class MiniMaxSessionPayload(
    val apiKey: String,
    /** Region API base URL chosen at import. Null = legacy session → default (China) base. */
    val apiBaseUrl: String? = null,
)
