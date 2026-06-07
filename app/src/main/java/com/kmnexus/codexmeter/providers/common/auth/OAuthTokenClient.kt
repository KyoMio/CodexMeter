package com.kmnexus.codexmeter.providers.common.auth

import com.kmnexus.codexmeter.core.network.ProviderHttpClient
import com.kmnexus.codexmeter.domain.refresh.QuotaError
import java.io.IOException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Generic OAuth 2.0 token endpoint client (authorization-code exchange + refresh) shared by the
 * PKCE providers (Claude, Antigravity).
 *
 * The authorization `code` returned from the consent flow is NOT an access token; it must be
 * exchanged here for `{access_token, refresh_token, expires_in}` before any usage API call.
 */
class OAuthTokenClient(
    private val httpClient: ProviderHttpClient,
    private val tokenEndpoint: String,
    private val clientId: String,
    private val clientSecret: String? = null,
    private val diagnosticsPrefix: String = "oauth",
    // Anthropic's /v1/oauth/token expects a JSON body (form-encoded → HTTP 400); Google's expects
    // form-urlencoded. Default to form for back-compat (Antigravity); Claude opts into JSON.
    private val useJsonBody: Boolean = false,
    private val userAgent: String? = null,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    suspend fun exchangeAuthorizationCode(
        code: String,
        codeVerifier: String,
        redirectUri: String,
        state: String? = null,
    ): Result =
        post(
            buildMap {
                put("grant_type", "authorization_code")
                put("client_id", clientId)
                clientSecret?.let { put("client_secret", it) }
                put("code", code)
                // Anthropic validates `state` in the token exchange (mirrors Hermes / Claude Code).
                state?.let { put("state", it) }
                put("code_verifier", codeVerifier)
                put("redirect_uri", redirectUri)
            },
            stage = "exchange",
        )

    suspend fun refresh(refreshToken: String): Result =
        post(
            buildMap {
                put("grant_type", "refresh_token")
                put("client_id", clientId)
                clientSecret?.let { put("client_secret", it) }
                put("refresh_token", refreshToken)
            },
            stage = "refresh",
        )

    private suspend fun post(fields: Map<String, String>, stage: String): Result {
        val headers = buildMap {
            put("Accept", "application/json")
            userAgent?.let { put("User-Agent", it) }
        }
        val response = try {
            if (useJsonBody) {
                val body = JsonObject(fields.mapValues { JsonPrimitive(it.value) }).toString()
                httpClient.postJson(url = tokenEndpoint, jsonBody = body, headers = headers)
            } else {
                httpClient.postForm(url = tokenEndpoint, formFields = fields, headers = headers)
            }
        } catch (_: IOException) {
            return Result.Failure(QuotaError.Network(diagnosticsDigest = "${diagnosticsPrefix}_${stage}_network"))
        }
        return when (response.statusCode) {
            in 200..299 -> parse(response.body, stage)
            401, 403 -> Result.Failure(
                QuotaError.AuthRequired(
                    httpStatus = response.statusCode,
                    diagnosticsDigest = "${diagnosticsPrefix}_${stage}_auth_${response.statusCode}",
                ),
            )
            else -> Result.Failure(
                QuotaError.Network(diagnosticsDigest = "${diagnosticsPrefix}_${stage}_http_${response.statusCode}"),
            )
        }
    }

    internal fun parse(body: String, stage: String): Result =
        try {
            val dto = json.decodeFromString<TokenResponseDto>(body)
            val accessToken = dto.accessToken?.takeIf { it.isNotBlank() }
                ?: return Result.Failure(
                    // A 2xx response with no usable access token is a malformed/transient server
                    // response, not proof the credential is invalid (that is 401/403, handled above).
                    // Treat it as retryable Network — matching CodexTokenRefresher and the decode-error
                    // branch below — so a transient glitch never forces a needs-reauth.
                    QuotaError.Network(
                        diagnosticsDigest = "${diagnosticsPrefix}_${stage}_no_access_token",
                    ),
                )
            Result.Success(
                OAuthTokens(
                    accessToken = accessToken,
                    refreshToken = dto.refreshToken?.takeIf { it.isNotBlank() },
                    expiresInSeconds = dto.expiresIn,
                    idToken = dto.idToken?.takeIf { it.isNotBlank() },
                ),
            )
        } catch (_: SerializationException) {
            Result.Failure(QuotaError.Network(diagnosticsDigest = "${diagnosticsPrefix}_${stage}_decode_error"))
        } catch (_: IllegalArgumentException) {
            Result.Failure(QuotaError.Network(diagnosticsDigest = "${diagnosticsPrefix}_${stage}_decode_error"))
        }

    data class OAuthTokens(
        val accessToken: String,
        val refreshToken: String?,
        val expiresInSeconds: Long?,
        val idToken: String?,
    )

    sealed interface Result {
        data class Success(val tokens: OAuthTokens) : Result
        data class Failure(val error: QuotaError) : Result
    }

    @Serializable
    private data class TokenResponseDto(
        @SerialName("access_token") val accessToken: String? = null,
        @SerialName("refresh_token") val refreshToken: String? = null,
        @SerialName("expires_in") val expiresIn: Long? = null,
        @SerialName("id_token") val idToken: String? = null,
    )
}
