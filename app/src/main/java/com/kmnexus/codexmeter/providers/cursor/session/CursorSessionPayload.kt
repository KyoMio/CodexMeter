package com.kmnexus.codexmeter.providers.cursor.session

import kotlinx.serialization.Serializable

@Serializable
data class CursorSessionPayload(
    val cookieValue: String,
    val accountId: String? = null,
)
