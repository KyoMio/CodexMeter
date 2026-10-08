package com.kmnexus.codexmeter.providers.kimicode.session

import kotlinx.serialization.Serializable

/** Encrypted at rest via [com.kmnexus.codexmeter.data.secure.PayloadCipher]; never logged. */
@Serializable
data class KimiCodeSessionPayload(
    val apiKey: String,
    /** Region API base URL chosen at import. Null = legacy session → default (China) base. */
    val apiBaseUrl: String? = null,
)
