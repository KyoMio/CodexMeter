package com.kmnexus.codexmeter.providers.grok.auth

import java.util.Base64
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal object GrokJwtClaims {
    private const val EMAIL_CLAIM = "email"
    private const val NAME_CLAIM = "name"
    private const val SUB_CLAIM = "sub"
    private const val EXPIRATION_CLAIM = "exp"

    private val json = Json {
        ignoreUnknownKeys = true
    }

    fun email(jwt: String?): String? = stringClaim(jwt, EMAIL_CLAIM)

    fun name(jwt: String?): String? = stringClaim(jwt, NAME_CLAIM)

    fun sub(jwt: String?): String? = stringClaim(jwt, SUB_CLAIM)

    fun expirationEpochSeconds(jwt: String?): Long? =
        rootClaims(jwt)
            ?.stringField(EXPIRATION_CLAIM)
            ?.toLongOrNull()
            ?.takeIf { it > 0 }

    private fun stringClaim(jwt: String?, name: String): String? =
        rootClaims(jwt)
            ?.stringField(name)
            ?.takeIf { it.isNotBlank() }

    private fun rootClaims(jwt: String?): JsonObject? {
        val payload = jwt
            ?.split('.')
            ?.takeIf { it.size == JWT_PART_COUNT }
            ?.get(1)
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val decoded = try {
            Base64.getUrlDecoder().decode(payload.paddedBase64Url())
        } catch (_: IllegalArgumentException) {
            return null
        }
        return try {
            json.parseToJsonElement(decoded.toString(Charsets.UTF_8)) as? JsonObject
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun JsonObject.stringField(name: String): String? =
        (this[name] as? JsonPrimitive)?.contentOrNull

    private fun String.paddedBase64Url(): String =
        this + "=".repeat((BASE64_BLOCK_SIZE - length % BASE64_BLOCK_SIZE) % BASE64_BLOCK_SIZE)

    private const val JWT_PART_COUNT = 3
    private const val BASE64_BLOCK_SIZE = 4
}
