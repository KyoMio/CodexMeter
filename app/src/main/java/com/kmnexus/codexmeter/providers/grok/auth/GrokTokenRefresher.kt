package com.kmnexus.codexmeter.providers.grok.auth

import com.kmnexus.codexmeter.core.network.ProviderHttpClient
import com.kmnexus.codexmeter.domain.refresh.QuotaError
import com.kmnexus.codexmeter.providers.grok.network.GrokOAuthDiscoveryClient
import com.kmnexus.codexmeter.providers.grok.session.GrokSessionPayload
import java.io.IOException
import java.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Refreshes the grok access token with a single `refresh_token` grant. xAI rotates the refresh token
 * on every grant, so a transport-level retry could burn the replacement after a lost response — this
 * client therefore issues exactly one request and never retries (mirrors OpenClaw's xai-oauth note
 * and CodexMeter's CodexTokenRefresher rotation write-back policy). It defaults to
 * [ProviderHttpClient.noRetry]; any injected client must equally disable OkHttp transport retries.
 * A payload whose cached endpoint is missing or the retired `/oauth/token` value triggers
 * re-discovery first.
 */
class GrokTokenRefresher(
    private val httpClient: ProviderHttpClient = ProviderHttpClient.noRetry(),
    private val json: Json = defaultJson,
    private val clock: Clock = Clock.systemUTC(),
    private val allowInsecureHttpForTests: Boolean = false,
    // Returns the current token endpoint from OIDC discovery, or null when discovery failed.
    private val tokenEndpointDiscovery: suspend () -> String? = { discoverTokenEndpoint(httpClient) },
) {
    suspend fun refresh(session: GrokSessionPayload): Result {
        val cachedEndpoint = session.tokenEndpoint
            ?.takeIf { it.isNotBlank() && it != GrokOAuthConfig.LEGACY_TOKEN_ENDPOINT_URL }
        val endpoint = cachedEndpoint ?: tokenEndpointDiscovery()
            ?: return Result.Failure(
                QuotaError.Network(diagnosticsDigest = "grok_refresh_discovery_failed"),
            )
        val validatedEndpoint = validatedEndpoint(endpoint)
            ?: return Result.Failure(lastEndpointError)

        // Single attempt: a transport failure here may already have consumed the old refresh token.
        val response = try {
            httpClient.postForm(
                url = validatedEndpoint,
                formFields = mapOf(
                    GRANT_TYPE_FIELD to REFRESH_TOKEN_GRANT,
                    CLIENT_ID_FIELD to GrokOAuthConfig.CLIENT_ID,
                    REFRESH_TOKEN_FIELD to session.refreshToken,
                ),
            )
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: IOException) {
            return Result.Failure(
                QuotaError.Network(diagnosticsDigest = "grok_refresh_network_error"),
            )
        } catch (_: IllegalArgumentException) {
            return Result.Failure(
                QuotaError.Network(diagnosticsDigest = "grok_refresh_invalid_endpoint"),
            )
        }

        return when (response.statusCode) {
            in SUCCESS_STATUS_RANGE -> decodeSuccess(
                body = response.body,
                existingSession = session,
                tokenEndpoint = validatedEndpoint,
            )
            else -> decodeFailure(
                statusCode = response.statusCode,
                body = response.body,
            )
        }
    }

    private var lastEndpointError: QuotaError = QuotaError.Network(
        diagnosticsDigest = "grok_refresh_invalid_endpoint",
    )

    private fun validatedEndpoint(endpointUrl: String): String? {
        val endpoint = endpointUrl.toHttpUrlOrNull()
        if (endpoint == null) {
            lastEndpointError = QuotaError.Network(diagnosticsDigest = "grok_refresh_invalid_endpoint")
            return null
        }
        if (endpoint.scheme != HTTPS_SCHEME && !(allowInsecureHttpForTests && endpoint.scheme == HTTP_SCHEME)) {
            lastEndpointError = QuotaError.Network(diagnosticsDigest = "grok_refresh_insecure_endpoint")
            return null
        }
        if (!allowInsecureHttpForTests && endpoint.host != AUTHORITY_HOST) {
            lastEndpointError = QuotaError.Network(diagnosticsDigest = "grok_refresh_untrusted_host")
            return null
        }
        return endpoint.toString()
    }

    private fun decodeSuccess(
        body: String,
        existingSession: GrokSessionPayload,
        tokenEndpoint: String,
    ): Result =
        try {
            val response = json.decodeFromString<GrokTokenRefreshResponseDto>(body)
            val accessToken = response.accessToken?.takeIf { it.isNotBlank() }
                ?: return Result.Failure(
                    QuotaError.Network(diagnosticsDigest = "grok_refresh_decode_error"),
                )
            val refreshToken = response.refreshToken?.takeIf { it.isNotBlank() }
                ?: existingSession.refreshToken
            val idToken = response.idToken?.takeIf { it.isNotBlank() }
                ?: existingSession.idToken
            val now = clock.instant()
            Result.Success(
                session = existingSession.copy(
                    accessToken = accessToken,
                    refreshToken = refreshToken,
                    idToken = idToken,
                    tokenEndpoint = tokenEndpoint,
                    lastRefreshEpochSeconds = now.epochSecond,
                    // Knowing when the token dies lets callers skip refresh on most polls; a
                    // response without expires_in keeps it unknown (mirrors CodexTokenRefresher).
                    tokenExpiresAtEpochSeconds = response.expiresIn.positiveSecondsOrNull()
                        ?.let { now.epochSecond + it },
                ),
            )
        } catch (_: SerializationException) {
            Result.Failure(
                QuotaError.Network(diagnosticsDigest = "grok_refresh_decode_error"),
            )
        } catch (_: IllegalArgumentException) {
            Result.Failure(
                QuotaError.Network(diagnosticsDigest = "grok_refresh_decode_error"),
            )
        }

    private fun decodeFailure(
        statusCode: Int,
        body: String,
    ): Result {
        if (statusCode == HTTP_UNAUTHORIZED || statusCode == HTTP_FORBIDDEN) {
            return Result.Failure(
                QuotaError.AuthRequired(
                    httpStatus = statusCode,
                    diagnosticsDigest = "grok_refresh_auth_required_$statusCode",
                ),
            )
        }

        val errorCode = decodeErrorCode(body)
        if (errorCode in TERMINAL_AUTH_ERROR_CODES) {
            return Result.Failure(
                QuotaError.AuthRequired(
                    httpStatus = statusCode,
                    diagnosticsDigest = "grok_refresh_auth_required_$errorCode",
                ),
            )
        }

        return Result.Failure(
            QuotaError.Network(diagnosticsDigest = "grok_refresh_http_$statusCode"),
        )
    }

    private fun decodeErrorCode(body: String): String? =
        try {
            val root = json.parseToJsonElement(body) as? JsonObject ?: return null
            (root[ERROR_FIELD] as? JsonPrimitive)?.contentOrNull
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

    // expires_in has only been observed as an integer; tolerate numeric drift (e.g. 3600.0) or a
    // quoted string so a type change cannot make decodeSuccess discard an already-rotated token pair.
    private fun JsonElement?.positiveSecondsOrNull(): Long? =
        (this as? JsonPrimitive)
            ?.let { primitive ->
                primitive.longOrNull
                    ?: primitive.doubleOrNull?.toLong()
                    ?: primitive.contentOrNull?.trim()?.toLongOrNull()
            }
            ?.takeIf { it > 0 }

    sealed interface Result {
        data class Success(
            val session: GrokSessionPayload,
        ) : Result {
            override fun toString(): String = "Success(session=[REDACTED])"
        }

        data class Failure(
            val error: QuotaError,
        ) : Result
    }

    companion object {
        private const val HTTPS_SCHEME = "https"
        private const val HTTP_SCHEME = "http"
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_FORBIDDEN = 403
        private const val CLIENT_ID_FIELD = "client_id"
        private const val GRANT_TYPE_FIELD = "grant_type"
        private const val REFRESH_TOKEN_FIELD = "refresh_token"
        private const val REFRESH_TOKEN_GRANT = "refresh_token"
        private const val ERROR_FIELD = "error"
        private val SUCCESS_STATUS_RANGE = 200..299
        private val AUTHORITY_HOST = GrokOAuthConfig.ISSUER_URL.toHttpUrl().host
        private val TERMINAL_AUTH_ERROR_CODES = setOf(
            "refresh_token_expired",
            "refresh_token_reused",
            "invalid_grant",
            "refresh_token_invalidated",
        )

        // Internal for tests: exercised through a MockWebServer-fronted discovery URL so the real
        // discovery parse/trust path runs end to end (unit tests cannot reach the auth.x.ai host).
        internal suspend fun discoverTokenEndpoint(
            httpClient: ProviderHttpClient,
            discoveryUrl: String = GrokOAuthConfig.DISCOVERY_URL,
            allowInsecureHttpForTests: Boolean = false,
        ): String? =
            when (
                val endpoints = GrokOAuthDiscoveryClient(
                    httpClient = httpClient,
                    discoveryUrl = discoveryUrl,
                    allowInsecureHttpForTests = allowInsecureHttpForTests,
                ).fetchEndpoints()
            ) {
                is GrokOAuthDiscoveryClient.Result.Success -> endpoints.value.tokenEndpoint
                is GrokOAuthDiscoveryClient.Result.Failure -> null
            }

        private val defaultJson = Json {
            ignoreUnknownKeys = true
        }
    }
}

@Serializable
private data class GrokTokenRefreshResponseDto(
    @SerialName("access_token")
    val accessToken: String? = null,
    @SerialName("refresh_token")
    val refreshToken: String? = null,
    @SerialName("id_token")
    val idToken: String? = null,
    @SerialName("expires_in")
    val expiresIn: JsonElement? = null,
)
