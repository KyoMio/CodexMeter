package com.kmnexus.codexmeter.domain.diagnostics

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Central redaction boundary for diagnostic text before it can reach copyable output or logs.
 */
object DiagnosticsRedactor {
    private const val REDACTED = "[REDACTED]"
    private val safeStandaloneStateValues = setOf("failed", "authRequired", "stale")
    private val json = Json {
        ignoreUnknownKeys = false
    }

    private val labeledJsonPrefix = Regex(
        "\\b(auth\\.json|response body|raw response|provider response)\\s*:\\s*",
        RegexOption.IGNORE_CASE,
    )
    private val callbackQueryWithState = Regex(
        "(callback query:\\s*)[^\\n]*\\b(state|error)=[^\\n]+",
        RegexOption.IGNORE_CASE,
    )
    private val queryAssignedStateOnly = Regex(
        "(query=)state=[^&\\s]+",
        RegexOption.IGNORE_CASE,
    )
    private val oauthCallbackState = Regex(
        "((?:\\boauth\\b|\\bcallback\\b)[^\\n]*?\\bstate=)[^&\\s]+",
        RegexOption.IGNORE_CASE,
    )
    private val oauthStateContextValue = Regex("\\b(expected|actual|state)=([^&\\s]+)", RegexOption.IGNORE_CASE)
    private val rawProviderResponsePayload = Regex(
        "\\b(response body|raw response|provider response)\\s*:\\s*[^\\n]+",
        RegexOption.IGNORE_CASE,
    )
    private val safeOperationalJsonKeys = setOf(
        "provider",
        "providerId",
        "accountIdHash",
        "localAccountIdHash",
        "accountLocalIdHash",
        "status",
        "currentState",
        "currentStateCategory",
        "stateCategory",
        "sessionStatus",
        "lastRefreshStatus",
        "httpStatus",
        "errorType",
        "safeMessageKey",
        "retryable",
        "userActionRequired",
        "timestamp",
        "createdAt",
        "updatedAt",
        "lastSuccessfulRefreshAt",
        "lastFailedRefreshAt",
        "lastRefreshAt",
        "widgetLastUpdatedAt",
        "widgetStatus",
        "appVersion",
        "androidVersion",
        "buildType",
        "diagnosticId",
        "workManagerStatus",
        "notificationPermissionStatus",
    )

    private val patterns = listOf(
        Regex(
            "[^\\s?]*(?:auth|oauth|callback)[^\\s?]*\\?(?=[^\\s]*\\berror=)[^\\s]+",
            RegexOption.IGNORE_CASE,
        ),
        Regex(
            "[^\\s?]+\\?(?=[^\\s]*\\b(code|state|access_token|refresh_token|id_token)=)[^\\s]+",
            RegexOption.IGNORE_CASE,
        ),
        Regex(
            "(?=[^\\s]*(?<![A-Za-z0-9_])(code|access_token|refresh_token|id_token)=)([A-Za-z_][A-Za-z0-9_]*=[^&\\s]+&?)+",
            RegexOption.IGNORE_CASE,
        ),
        Regex("\\b(auth(?:orization)? code)\\s*[:=]\\s*[^\\s,}]+", RegexOption.IGNORE_CASE),
        Regex("Bearer\\s+[^\\s,}]+", RegexOption.IGNORE_CASE),
        Regex(
            "\\\"(access_token|refresh_token|id_token|accessToken|refreshToken|idToken|code|cookie)\\\"\\s*:\\s*\\\"[^\\\"]*\\\"",
            RegexOption.IGNORE_CASE,
        ),
        Regex("(Authorization|Cookie):\\s*[^\\n]+", RegexOption.IGNORE_CASE),
        Regex("(?<![A-Za-z0-9_])(code|access_token|refresh_token|id_token)=([^&\\s]+)", RegexOption.IGNORE_CASE),
    )

    fun redact(input: String): String {
        val trimmed = input.trim()
        if (trimmed.isSensitiveJsonPayload()) {
            return REDACTED
        }
        if (trimmed.isSensitiveRawStateCallbackQuery()) {
            return REDACTED
        }

        val inputWithoutLabeledJson = input.redactLabeledJsonPayloads()
        val inputWithoutRawResponse = inputWithoutLabeledJson.redactRawProviderResponsePayloads()
        val inputWithoutCallbackState = inputWithoutRawResponse.redactCallbackStateFragments()

        return patterns.fold(inputWithoutCallbackState) { current, pattern ->
            current.replace(pattern) { match ->
                val text = match.value
                when {
                    text.contains("?") && text.contains("=") -> text.substringBefore("?") + "?$REDACTED"
                    text.contains("&") && text.contains("=") -> REDACTED
                    text.contains(":") && text.contains("\"") -> {
                        val key = text.substringBefore(":")
                        "$key:\"$REDACTED\""
                    }
                    text.contains(":") -> text.substringBefore(":") + ": $REDACTED"
                    text.contains("=") -> text.substringBefore("=") + "=$REDACTED"
                    else -> "Bearer $REDACTED"
                }
            }
        }
    }

    private fun String.isJsonLikePayload(): Boolean =
        startsWith("{") || startsWith("[")

    private fun String.isSensitiveJsonPayload(): Boolean =
        isJsonLikePayload() && !isSafeOperationalJsonPayload()

    private fun String.isSafeOperationalJsonPayload(): Boolean {
        if (!(startsWith("{") && endsWith("}"))) {
            return false
        }

        val payload = runCatching { json.parseToJsonElement(this) }.getOrNull() as? JsonObject
            ?: return false

        return payload.isNotEmpty() &&
            payload.all { (key, value) -> key in safeOperationalJsonKeys && value is JsonPrimitive }
    }

    private fun String.redactLabeledJsonPayloads(): String {
        val match = labeledJsonPrefix.find(this) ?: return this
        return substring(0, match.range.last + 1) + REDACTED
    }

    private fun String.redactRawProviderResponsePayloads(): String =
        rawProviderResponsePayload.replace(this) { match ->
            "${match.groupValues[1]}: $REDACTED"
        }

    private fun String.redactCallbackStateFragments(): String =
        callbackQueryWithState.replace(this) { match ->
            "${match.groupValues[1]}$REDACTED"
        }.let { current ->
            queryAssignedStateOnly.replace(current) { match ->
                "${match.groupValues[1]}state=$REDACTED"
            }
        }.let { current ->
            oauthCallbackState.replace(current) { match ->
                "${match.groupValues[1]}$REDACTED"
            }
        }.redactOauthStateContextValues()

    private fun String.redactOauthStateContextValues(): String {
        if (!contains(Regex("\\b(oauth|callback)\\b", RegexOption.IGNORE_CASE)) ||
            !contains(Regex("\\bstate\\b", RegexOption.IGNORE_CASE))
        ) {
            return this
        }

        return oauthStateContextValue.replace(this) { match ->
            "${match.groupValues[1]}=$REDACTED"
        }
    }

    private fun String.isSensitiveRawStateCallbackQuery(): Boolean {
        val params = parseAmpersandParameters() ?: return false
        if (!params.containsKey("state")) {
            return false
        }

        return !params.isSafeOperationalStateQuery()
    }

    private fun String.parseAmpersandParameters(): Map<String, String>? {
        if (contains("?") || any { it.isWhitespace() }) {
            return null
        }

        val params = mutableMapOf<String, String>()
        for (part in split("&")) {
            val key = part.substringBefore("=")
            val value = part.substringAfter("=", missingDelimiterValue = "")
            if (key.isEmpty() || value.isEmpty() || key == part) {
                return null
            }
            params[key] = value
        }

        return params
    }

    private fun Map<String, String>.isSafeOperationalStateQuery(): Boolean {
        val state = this["state"] ?: return false
        if (state !in safeStandaloneStateValues) {
            return false
        }

        if (size == 1) {
            return true
        }

        return keys == setOf("provider", "state", "httpStatus") &&
            this["provider"] == "codex" &&
            this["httpStatus"]?.all { it.isDigit() } == true
    }
}
