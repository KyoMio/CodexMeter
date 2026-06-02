package com.kmnexus.codexmeter.providers.codex.auth

import com.kmnexus.codexmeter.core.network.ProviderHttpClient
import com.kmnexus.codexmeter.domain.refresh.QuotaError
import com.kmnexus.codexmeter.providers.codex.session.CodexSessionPayload
import java.io.IOException
import java.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class CodexTokenRefresher(
    private val httpClient: ProviderHttpClient,
    private val json: Json = defaultJson,
    private val endpointUrl: String = DEFAULT_ENDPOINT_URL,
    private val userAgent: String = DEFAULT_USER_AGENT,
    private val clock: Clock = Clock.systemUTC(),
    private val allowInsecureHttpForTests: Boolean = false,
) {
    suspend fun refresh(session: CodexSessionPayload): Result {
        val endpoint = endpointUrl.toHttpUrlOrNull()
            ?: return Result.Failure(
                QuotaError.Network(diagnosticsDigest = "codex_refresh_invalid_endpoint"),
            )
        if (endpoint.scheme != HTTPS_SCHEME && !(allowInsecureHttpForTests && endpoint.scheme == HTTP_SCHEME)) {
            return Result.Failure(
                QuotaError.Network(diagnosticsDigest = "codex_refresh_insecure_endpoint"),
            )
        }

        val response = try {
            httpClient.postForm(
                url = endpoint.toString(),
                formFields = refreshFormFields(session.refreshToken),
                headers = refreshHeaders(),
            )
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: IOException) {
            return Result.Failure(
                QuotaError.Network(diagnosticsDigest = "codex_refresh_network_error"),
            )
        } catch (_: IllegalArgumentException) {
            return Result.Failure(
                QuotaError.Network(diagnosticsDigest = "codex_refresh_invalid_endpoint"),
            )
        }

        return when (response.statusCode) {
            in SUCCESS_STATUS_RANGE -> decodeSuccess(
                body = response.body,
                existingSession = session,
            )
            else -> decodeFailure(
                statusCode = response.statusCode,
                body = response.body,
            )
        }
    }

    private fun refreshFormFields(refreshToken: String): Map<String, String> =
        mapOf(
            CLIENT_ID_FIELD to CodexOAuthConfig.CLIENT_ID,
            GRANT_TYPE_FIELD to REFRESH_TOKEN_GRANT,
            REFRESH_TOKEN_FIELD to refreshToken,
            SCOPE_FIELD to REFRESH_SCOPE,
        )

    private fun refreshHeaders(): Map<String, String> =
        mapOf(
            ACCEPT_HEADER to APPLICATION_JSON,
            USER_AGENT_HEADER to userAgent,
        )

    private fun decodeSuccess(
        body: String,
        existingSession: CodexSessionPayload,
    ): Result =
        try {
            val response = json.decodeFromString<CodexTokenRefreshResponseDto>(body)
            val accessToken = response.accessToken?.takeIf { it.isNotBlank() }
                ?: return Result.Failure(
                    QuotaError.Network(diagnosticsDigest = "codex_refresh_decode_error"),
                )
            val refreshToken = response.refreshToken?.takeIf { it.isNotBlank() }
                ?: existingSession.refreshToken

            val refreshedIdToken = response.idToken?.takeIf { it.isNotBlank() } ?: existingSession.idToken
            Result.Success(
                session = existingSession.copy(
                    accessToken = accessToken,
                    refreshToken = refreshToken,
                    idToken = refreshedIdToken,
                    accountId = existingSession.accountId
                        ?: CodexJwtClaims.chatGptAccountId(refreshedIdToken),
                    accountEmail = existingSession.accountEmail
                        ?: CodexJwtClaims.email(refreshedIdToken),
                    lastRefresh = clock.instant(),
                ),
            )
        } catch (_: SerializationException) {
            Result.Failure(
                QuotaError.Network(diagnosticsDigest = "codex_refresh_decode_error"),
            )
        } catch (_: IllegalArgumentException) {
            Result.Failure(
                QuotaError.Network(diagnosticsDigest = "codex_refresh_decode_error"),
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
                    diagnosticsDigest = "codex_refresh_auth_required_$statusCode",
                ),
            )
        }

        val errorCode = decodeErrorCode(body)
        if (errorCode in TERMINAL_AUTH_ERROR_CODES) {
            return Result.Failure(
                QuotaError.AuthRequired(
                    httpStatus = statusCode,
                    diagnosticsDigest = "codex_refresh_auth_required_$errorCode",
                ),
            )
        }

        return Result.Failure(
            QuotaError.Network(diagnosticsDigest = "codex_refresh_http_$statusCode"),
        )
    }

    private fun decodeErrorCode(body: String): String? =
        try {
            val root = json.parseToJsonElement(body) as? JsonObject ?: return null
            when (val error = root["error"]) {
                is JsonPrimitive -> error.contentOrNull
                is JsonObject -> (error["code"] as? JsonPrimitive)?.contentOrNull
                else -> null
            }
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

    sealed interface Result {
        data class Success(
            val session: CodexSessionPayload,
        ) : Result {
            override fun toString(): String = "Success(session=[REDACTED])"
        }

        data class Failure(
            val error: QuotaError,
        ) : Result
    }

    companion object {
        const val DEFAULT_ENDPOINT_URL = CodexOAuthConfig.TOKEN_ENDPOINT_URL
        const val DEFAULT_USER_AGENT = "CodexMeter/0.1.0"

        private const val ACCEPT_HEADER = "Accept"
        private const val USER_AGENT_HEADER = "User-Agent"
        private const val APPLICATION_JSON = "application/json"
        private const val HTTPS_SCHEME = "https"
        private const val HTTP_SCHEME = "http"
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_FORBIDDEN = 403
        private const val CLIENT_ID_FIELD = "client_id"
        private const val GRANT_TYPE_FIELD = "grant_type"
        private const val REFRESH_TOKEN_FIELD = "refresh_token"
        private const val SCOPE_FIELD = "scope"
        private const val REFRESH_TOKEN_GRANT = "refresh_token"
        private const val REFRESH_SCOPE = "openid profile email"
        private val SUCCESS_STATUS_RANGE = 200..299
        private val TERMINAL_AUTH_ERROR_CODES = setOf(
            "refresh_token_expired",
            "refresh_token_reused",
            "invalid_grant",
            "refresh_token_invalidated",
        )
        private val defaultJson = Json {
            ignoreUnknownKeys = true
        }
    }
}

@Serializable
private data class CodexTokenRefreshResponseDto(
    @SerialName("access_token")
    val accessToken: String? = null,
    @SerialName("refresh_token")
    val refreshToken: String? = null,
    @SerialName("id_token")
    val idToken: String? = null,
)
