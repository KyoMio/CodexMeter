package com.kmnexus.codexmeter.providers.kimi.auth

import java.util.Base64
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Extracts the session identifiers CodexBar sends with the Kimi GetUsages call from the `kimi-auth`
 * JWT: `device_id` → x-msh-device-id, `ssid` → x-msh-session-id, `sub` → x-traffic-id. Pure JVM
 * (java.util.Base64 + kotlinx) so it runs in unit tests without Android.
 */
internal object KimiJwtClaims {
    private val json = Json { ignoreUnknownKeys = true }

    data class Session(
        val deviceId: String?,
        val sessionId: String?,
        val trafficId: String?,
    )

    fun session(jwt: String?): Session {
        val root = rootClaims(jwt)
        return Session(
            deviceId = root?.stringField("device_id"),
            sessionId = root?.stringField("ssid"),
            trafficId = root?.stringField("sub"),
        )
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
        return try {
            json.parseToJsonElement(decoded.toString(Charsets.UTF_8)) as? JsonObject
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun JsonObject.stringField(name: String): String? =
        (this[name] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun String.paddedBase64Url(): String =
        this + "=".repeat((BASE64_BLOCK_SIZE - length % BASE64_BLOCK_SIZE) % BASE64_BLOCK_SIZE)

    private const val JWT_PART_COUNT = 3
    private const val BASE64_BLOCK_SIZE = 4
}
