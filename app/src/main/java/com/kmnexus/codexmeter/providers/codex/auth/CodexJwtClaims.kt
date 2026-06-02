package com.kmnexus.codexmeter.providers.codex.auth

import java.util.Base64
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal object CodexJwtClaims {
    private const val OPENAI_AUTH_CLAIMS_KEY = "https://api.openai.com/auth"
    private const val CHATGPT_ACCOUNT_ID_CLAIM = "chatgpt_account_id"
    private const val EMAIL_CLAIM = "email"

    private val json = Json {
        ignoreUnknownKeys = true
    }

    fun chatGptAccountId(jwt: String?): String? =
        authClaim(jwt, CHATGPT_ACCOUNT_ID_CLAIM)

    fun email(jwt: String?): String? =
        authClaim(jwt, EMAIL_CLAIM)
            ?: rootClaims(jwt)
                ?.stringField(EMAIL_CLAIM)
                ?.takeIf { it.isNotBlank() }

    private fun authClaim(jwt: String?, name: String): String? =
        authClaims(jwt)
            ?.stringField(name)
            ?.takeIf { it.isNotBlank() }

    private fun authClaims(jwt: String?): JsonObject? {
        val root = rootClaims(jwt) ?: return null
        return root.get(OPENAI_AUTH_CLAIMS_KEY) as? JsonObject
    }

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
        val root = try {
            json.parseToJsonElement(decoded.toString(Charsets.UTF_8)) as? JsonObject
        } catch (_: SerializationException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }
        return root
    }

    private fun JsonObject.stringField(name: String): String? =
        (this[name] as? JsonPrimitive)?.contentOrNull

    private fun String.paddedBase64Url(): String =
        this + "=".repeat((BASE64_BLOCK_SIZE - length % BASE64_BLOCK_SIZE) % BASE64_BLOCK_SIZE)

    private const val JWT_PART_COUNT = 3
    private const val BASE64_BLOCK_SIZE = 4
}
