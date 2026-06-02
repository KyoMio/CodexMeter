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

class CodexOAuthTokenExchanger(
    private val httpClient: ProviderHttpClient,
    private val json: Json = defaultJson,
    private val endpointUrl: String = DEFAULT_ENDPOINT_URL,
    private val userAgent: String = DEFAULT_USER_AGENT,
    private val clock: Clock = Clock.systemUTC(),
    private val allowInsecureHttpForTests: Boolean = false,
) {
    suspend fun exchange(request: CodexOAuthTokenExchangeRequest): Result {
        val endpoint = endpointUrl.toHttpUrlOrNull()
            ?: return Result.Failure(
                QuotaError.Network(diagnosticsDigest = "codex_oauth_invalid_endpoint"),
            )
        if (endpoint.scheme != HTTPS_SCHEME && !(allowInsecureHttpForTests && endpoint.scheme == HTTP_SCHEME)) {
            return Result.Failure(
                QuotaError.Network(diagnosticsDigest = "codex_oauth_insecure_endpoint"),
            )
        }

        val response = try {
            httpClient.postForm(
                url = endpoint.toString(),
                formFields = exchangeFormFields(request),
                headers = exchangeHeaders(),
            )
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: IOException) {
            return Result.Failure(
                QuotaError.Network(diagnosticsDigest = "codex_oauth_network_error"),
            )
        } catch (_: IllegalArgumentException) {
            return Result.Failure(
                QuotaError.Network(diagnosticsDigest = "codex_oauth_invalid_endpoint"),
            )
        }

        return when (response.statusCode) {
            in SUCCESS_STATUS_RANGE -> decodeSuccess(response.body)
            else -> decodeFailure(
                statusCode = response.statusCode,
                body = response.body,
            )
        }
    }

    private fun exchangeFormFields(request: CodexOAuthTokenExchangeRequest): Map<String, String> =
        mapOf(
            CLIENT_ID_FIELD to CodexOAuthConfig.CLIENT_ID,
            GRANT_TYPE_FIELD to AUTHORIZATION_CODE_GRANT,
            CODE_FIELD to request.authorizationCode.value,
            CODE_VERIFIER_FIELD to request.pkceVerifier.value,
            REDIRECT_URI_FIELD to request.redirectUri,
        )

    private fun exchangeHeaders(): Map<String, String> =
        mapOf(
            ACCEPT_HEADER to APPLICATION_JSON,
            USER_AGENT_HEADER to userAgent,
        )

    private fun decodeSuccess(body: String): Result =
        try {
            val response = json.decodeFromString<CodexOAuthTokenResponseDto>(body)
            val accessToken = response.accessToken?.takeIf { it.isNotBlank() }
                ?: return Result.Failure(
                    QuotaError.Network(diagnosticsDigest = "codex_oauth_decode_error"),
                )
            val refreshToken = response.refreshToken?.takeIf { it.isNotBlank() }
                ?: return Result.Failure(
                    QuotaError.Network(diagnosticsDigest = "codex_oauth_decode_error"),
                )

            val idToken = response.idToken?.takeIf { it.isNotBlank() }
            val accountId = response.accountId?.takeIf { it.isNotBlank() }
                ?: CodexJwtClaims.chatGptAccountId(idToken)
            val accountEmail = response.email?.takeIf { it.isNotBlank() }
                ?: CodexJwtClaims.email(idToken)

            Result.Success(
                session = CodexSessionPayload(
                    accessToken = accessToken,
                    refreshToken = refreshToken,
                    idToken = idToken,
                    accountId = accountId,
                    accountEmail = accountEmail,
                    lastRefresh = clock.instant(),
                ),
            )
        } catch (_: SerializationException) {
            Result.Failure(
                QuotaError.Network(diagnosticsDigest = "codex_oauth_decode_error"),
            )
        } catch (_: IllegalArgumentException) {
            Result.Failure(
                QuotaError.Network(diagnosticsDigest = "codex_oauth_decode_error"),
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
                    diagnosticsDigest = "codex_oauth_auth_required_$statusCode",
                ),
            )
        }

        val errorCode = decodeErrorCode(body)
        if (errorCode in TERMINAL_AUTH_ERROR_CODES) {
            return Result.Failure(
                QuotaError.AuthRequired(
                    httpStatus = statusCode,
                    diagnosticsDigest = "codex_oauth_auth_required_$errorCode",
                ),
            )
        }

        return Result.Failure(
            QuotaError.Network(diagnosticsDigest = "codex_oauth_http_$statusCode"),
        )
    }

    private fun decodeErrorCode(body: String): String? =
        try {
            val root = json.parseToJsonElement(body) as? JsonObject ?: return null
            val error = root["error"]
            when (error) {
                is JsonPrimitive -> error.contentOrNull
                is JsonObject -> error.stringField("code") ?: error.stringField("type")
                else -> root.stringField("error_code") ?: root.stringField("code")
            }
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

    private fun JsonObject.stringField(name: String): String? =
        (this[name] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    sealed interface Result {
        class Success(
            val session: CodexSessionPayload,
        ) : Result {
            override fun toString(): String = "Success(session=[REDACTED])"
        }

        class Failure(
            val error: QuotaError,
        ) : Result {
            override fun toString(): String = "Failure(error=$error)"
        }
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
        private const val CODE_FIELD = "code"
        private const val CODE_VERIFIER_FIELD = "code_verifier"
        private const val REDIRECT_URI_FIELD = "redirect_uri"
        private const val AUTHORIZATION_CODE_GRANT = "authorization_code"
        private val SUCCESS_STATUS_RANGE = 200..299
        private val TERMINAL_AUTH_ERROR_CODES = setOf(
            "invalid_grant",
            "expired_token",
            "invalid_request",
            "access_denied",
        )
        private val defaultJson = Json {
            ignoreUnknownKeys = true
        }
    }
}

@Serializable
private data class CodexOAuthTokenResponseDto(
    @SerialName("access_token")
    val accessToken: String? = null,
    @SerialName("refresh_token")
    val refreshToken: String? = null,
    @SerialName("id_token")
    val idToken: String? = null,
    @SerialName("account_id")
    val accountId: String? = null,
    val email: String? = null,
)
