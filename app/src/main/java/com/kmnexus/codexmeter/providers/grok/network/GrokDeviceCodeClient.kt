package com.kmnexus.codexmeter.providers.grok.network

import com.kmnexus.codexmeter.core.network.ProviderHttpClient
import com.kmnexus.codexmeter.domain.refresh.QuotaError
import com.kmnexus.codexmeter.providers.grok.auth.GrokOAuthConfig
import com.kmnexus.codexmeter.providers.grok.auth.GrokOAuthToken
import com.kmnexus.codexmeter.providers.grok.auth.GrokJwtClaims
import java.io.IOException
import java.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class GrokDeviceCodeClient(
    private val httpClient: ProviderHttpClient,
    private val json: Json = defaultJson,
    private val userAgent: String = DEFAULT_USER_AGENT,
    private val clock: Clock = Clock.systemUTC(),
    private val allowInsecureHttpForTests: Boolean = false,
) {
    suspend fun requestDeviceCode(deviceAuthorizationEndpointUrl: String): Result<GrokDeviceCodeChallenge> {
        val endpoint = validatedEndpoint(
            endpointUrl = deviceAuthorizationEndpointUrl,
            invalidDigest = "grok_device_code_invalid_endpoint",
            insecureDigest = "grok_device_code_insecure_endpoint",
        ) ?: return Result.Failure(lastEndpointError)

        val response = try {
            httpClient.postForm(
                url = endpoint,
                formFields = mapOf(
                    CLIENT_ID_FIELD to GrokOAuthConfig.CLIENT_ID,
                    SCOPE_FIELD to GrokOAuthConfig.SCOPE,
                ),
                headers = requestHeaders(),
            )
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: IOException) {
            return Result.Failure(
                QuotaError.Network(diagnosticsDigest = "grok_device_code_network_error"),
            )
        } catch (_: IllegalArgumentException) {
            return Result.Failure(
                QuotaError.Network(diagnosticsDigest = "grok_device_code_invalid_endpoint"),
            )
        }

        return when (response.statusCode) {
            in SUCCESS_STATUS_RANGE -> decodeChallenge(response.body)
            else -> Result.Failure(
                QuotaError.Network(diagnosticsDigest = "grok_device_code_http_${response.statusCode}"),
            )
        }
    }

    /**
     * RFC 8628 token polling loop. The device code itself is the deadline: once expires_in elapses
     * without a token the attempt is [Result.Expired]. The deadline is checked against an injected
     * [clock] (never a withTimeout task, so structured cancellation is the only way to stop early).
     */
    suspend fun awaitAuthorization(
        challenge: GrokDeviceCodeChallenge,
        tokenEndpointUrl: String,
    ): Result<GrokOAuthToken> {
        val endpoint = validatedEndpoint(
            endpointUrl = tokenEndpointUrl,
            invalidDigest = "grok_token_invalid_endpoint",
            insecureDigest = "grok_token_insecure_endpoint",
        ) ?: return Result.Failure(lastEndpointError)

        return pollAuthorizationLoop(challenge, endpoint)
    }

    private suspend fun pollAuthorizationLoop(
        challenge: GrokDeviceCodeChallenge,
        tokenEndpoint: String,
    ): Result<GrokOAuthToken> {
        var intervalMillis = challenge.intervalSeconds.toLong() * MILLIS_PER_SECOND
        val deadlineMillis = clock.millis() + challenge.expiresInSeconds * MILLIS_PER_SECOND
        while (true) {
            if (clock.millis() >= deadlineMillis) {
                return Result.Expired
            }
            val response = try {
                httpClient.postForm(
                    url = tokenEndpoint,
                    formFields = mapOf(
                        GRANT_TYPE_FIELD to GrokOAuthConfig.DEVICE_CODE_GRANT_TYPE,
                        CLIENT_ID_FIELD to GrokOAuthConfig.CLIENT_ID,
                        DEVICE_CODE_FIELD to challenge.deviceCode,
                    ),
                    headers = requestHeaders(),
                )
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: IOException) {
                return Result.Failure(
                    QuotaError.Network(diagnosticsDigest = "grok_token_network_error"),
                )
            } catch (_: IllegalArgumentException) {
                return Result.Failure(
                    QuotaError.Network(diagnosticsDigest = "grok_token_invalid_endpoint"),
                )
            }

            if (response.statusCode in SUCCESS_STATUS_RANGE) {
                return decodeToken(response.body)
            }

            when (errorCode(response.body)) {
                AUTHORIZATION_PENDING_ERROR -> Unit
                SLOW_DOWN_ERROR -> intervalMillis += SLOW_DOWN_INCREMENT_MILLIS
                ACCESS_DENIED_ERROR, AUTHORIZATION_DENIED_ERROR -> return Result.Denied
                EXPIRED_TOKEN_ERROR -> return Result.Expired
                else -> return Result.Failure(
                    QuotaError.Network(diagnosticsDigest = "grok_token_http_${response.statusCode}"),
                )
            }

            // Never sleep past the deadline: wake up at the expiry boundary at the latest.
            val remainingMillis = deadlineMillis - clock.millis()
            if (remainingMillis <= 0) {
                return Result.Expired
            }
            delay(minOf(intervalMillis, remainingMillis))
        }
    }

    private var lastEndpointError: QuotaError = QuotaError.Network(
        diagnosticsDigest = "grok_device_code_invalid_endpoint",
    )

    private fun validatedEndpoint(
        endpointUrl: String,
        invalidDigest: String,
        insecureDigest: String,
    ): String? {
        val endpoint = endpointUrl.toHttpUrlOrNull()
        if (endpoint == null) {
            lastEndpointError = QuotaError.Network(diagnosticsDigest = invalidDigest)
            return null
        }
        if (endpoint.scheme != HTTPS_SCHEME && !(allowInsecureHttpForTests && endpoint.scheme == HTTP_SCHEME)) {
            lastEndpointError = QuotaError.Network(diagnosticsDigest = insecureDigest)
            return null
        }
        return endpoint.toString()
    }

    private fun requestHeaders(): Map<String, String> =
        mapOf(
            ACCEPT_HEADER to APPLICATION_JSON,
            USER_AGENT_HEADER to userAgent,
        )

    /** The verification page must stay on xAI hosts so a hostile discovery cannot redirect the user. */
    private fun trustedVerificationUri(candidate: String): String? {
        val candidateUrl = candidate.toHttpUrlOrNull() ?: return null
        if (candidateUrl.scheme != HTTPS_SCHEME) return null
        return candidateUrl.host.takeIf { it in TRUSTED_VERIFICATION_HOSTS }?.let { candidate }
    }

    private fun decodeChallenge(body: String): Result<GrokDeviceCodeChallenge> =
        try {
            val response = json.decodeFromString<GrokDeviceAuthorizationResponseDto>(body)
            val deviceCode = response.deviceCode?.takeIf { it.isNotBlank() }
                ?: return deviceCodeDecodeFailure()
            val userCode = response.userCode?.takeIf { it.isNotBlank() }
                ?: return deviceCodeDecodeFailure()
            val verificationUri = response.verificationUri?.takeIf { it.isNotBlank() }
                ?.let { unsafeCandidate ->
                    trustedVerificationUri(unsafeCandidate)
                        ?: return Result.Failure(
                            QuotaError.Network(
                                diagnosticsDigest = "grok_device_code_unsafe_verification_uri",
                            ),
                        )
                }
                ?: return deviceCodeDecodeFailure()
            val verificationUriComplete = response.verificationUriComplete?.takeIf { it.isNotBlank() }
                ?.let { unsafeCandidate ->
                    trustedVerificationUri(unsafeCandidate)
                        ?: return Result.Failure(
                            QuotaError.Network(
                                diagnosticsDigest = "grok_device_code_unsafe_verification_uri",
                            ),
                        )
                }
            val intervalSeconds = response.intervalSeconds.positiveIntOrNull()
                ?: DEFAULT_INTERVAL_SECONDS
            val expiresInSeconds = response.expiresInSeconds.positiveIntOrNull()
                ?: DEFAULT_EXPIRES_IN_SECONDS

            Result.Success(
                GrokDeviceCodeChallenge(
                    deviceCode = deviceCode,
                    userCode = userCode,
                    verificationUri = verificationUri,
                    verificationUriComplete = verificationUriComplete,
                    intervalSeconds = intervalSeconds.coerceAtLeast(MIN_INTERVAL_SECONDS),
                    expiresInSeconds = expiresInSeconds,
                ),
            )
        } catch (_: SerializationException) {
            deviceCodeDecodeFailure()
        } catch (_: IllegalArgumentException) {
            deviceCodeDecodeFailure()
        }

    private fun decodeToken(body: String): Result<GrokOAuthToken> =
        try {
            val response = json.decodeFromString<GrokTokenResponseDto>(body)
            val accessToken = response.accessToken?.takeIf { it.isNotBlank() }
                ?: return tokenDecodeFailure()
            val refreshToken = response.refreshToken?.takeIf { it.isNotBlank() }
                ?: return Result.Failure(
                    QuotaError.AuthRequired(
                        httpStatus = null,
                        // RFC 8628 requires a refresh_token; absence means the scope was denied.
                        diagnosticsDigest = "grok_refresh_token_missing",
                    ),
                )
            val idToken = response.idToken?.takeIf { it.isNotBlank() }
            val expiresAtEpochSeconds = response.expiresInSeconds.positiveLongOrNull()
                ?.let { clock.instant().epochSecond + it }
                // Never use the id_token exp here: it reflects the OIDC session, not the access token.
                ?: GrokJwtClaims.expirationEpochSeconds(accessToken)

            Result.Success(
                GrokOAuthToken(
                    accessToken = accessToken,
                    refreshToken = refreshToken,
                    idToken = idToken,
                    expiresAtEpochSeconds = expiresAtEpochSeconds,
                ),
            )
        } catch (_: SerializationException) {
            tokenDecodeFailure()
        } catch (_: IllegalArgumentException) {
            tokenDecodeFailure()
        }

    private fun errorCode(body: String): String? =
        try {
            val root = json.parseToJsonElement(body) as? JsonObject ?: return null
            (root[ERROR_FIELD] as? JsonPrimitive)?.contentOrNull
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

    private fun JsonElement?.positiveIntOrNull(): Int? =
        (this as? JsonPrimitive)
            ?.contentOrNull
            ?.toIntOrNull()
            ?.takeIf { it > 0 }

    private fun JsonElement?.positiveLongOrNull(): Long? =
        (this as? JsonPrimitive)
            ?.contentOrNull
            ?.toLongOrNull()
            ?.takeIf { it > 0 }

    private fun deviceCodeDecodeFailure(): Result.Failure =
        Result.Failure(
            QuotaError.Network(diagnosticsDigest = "grok_device_code_decode_error"),
        )

    private fun tokenDecodeFailure(): Result.Failure =
        Result.Failure(
            QuotaError.Network(diagnosticsDigest = "grok_token_decode_error"),
        )

    sealed interface Result<out T> {
        class Success<out T>(
            val value: T,
        ) : Result<T> {
            // Redact unconditionally: today's payloads override toString, but a future non-redacting
            // wrapper type must not start leaking token material through this default.
            override fun toString(): String = "Success(value=[REDACTED])"
        }

        object Denied : Result<Nothing> {
            override fun toString(): String = "Denied"
        }

        object Expired : Result<Nothing> {
            override fun toString(): String = "Expired"
        }

        data class Failure(
            val error: QuotaError,
        ) : Result<Nothing>
    }

    companion object {
        const val DEFAULT_USER_AGENT = "CodexMeter/0.1.0"

        private const val ACCEPT_HEADER = "Accept"
        private const val USER_AGENT_HEADER = "User-Agent"
        private const val APPLICATION_JSON = "application/json"
        private const val HTTPS_SCHEME = "https"
        private const val HTTP_SCHEME = "http"
        private const val CLIENT_ID_FIELD = "client_id"
        private const val SCOPE_FIELD = "scope"
        private const val GRANT_TYPE_FIELD = "grant_type"
        private const val DEVICE_CODE_FIELD = "device_code"
        private const val ERROR_FIELD = "error"
        private const val AUTHORIZATION_PENDING_ERROR = "authorization_pending"
        private const val SLOW_DOWN_ERROR = "slow_down"
        private const val ACCESS_DENIED_ERROR = "access_denied"
        private const val AUTHORIZATION_DENIED_ERROR = "authorization_denied"
        private const val EXPIRED_TOKEN_ERROR = "expired_token"
        private const val MILLIS_PER_SECOND = 1_000
        private const val SLOW_DOWN_INCREMENT_MILLIS = 5_000
        private const val MIN_INTERVAL_SECONDS = 1
        private const val DEFAULT_INTERVAL_SECONDS = 5
        private const val DEFAULT_EXPIRES_IN_SECONDS = 300
        private val SUCCESS_STATUS_RANGE = 200..299
        private val TRUSTED_VERIFICATION_HOSTS = setOf(
            GrokOAuthConfig.ISSUER_URL.toHttpUrl().host,
            "x.ai",
        )
        private val defaultJson = Json {
            ignoreUnknownKeys = true
        }
    }
}

data class GrokDeviceCodeChallenge(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val verificationUriComplete: String?,
    val intervalSeconds: Int,
    val expiresInSeconds: Int,
) {
    override fun toString(): String =
        "GrokDeviceCodeChallenge(" +
            "deviceCode=[REDACTED], " +
            "userCode=[REDACTED], " +
            "verificationUri=$verificationUri, " +
            "verificationUriComplete=[REDACTED], " +
            "intervalSeconds=$intervalSeconds, " +
            "expiresInSeconds=$expiresInSeconds" +
            ")"
}

@Serializable
private data class GrokDeviceAuthorizationResponseDto(
    @SerialName("device_code")
    val deviceCode: String? = null,
    @SerialName("user_code")
    val userCode: String? = null,
    @SerialName("verification_uri")
    val verificationUri: String? = null,
    @SerialName("verification_uri_complete")
    val verificationUriComplete: String? = null,
    @SerialName("interval")
    val intervalSeconds: JsonElement? = null,
    @SerialName("expires_in")
    val expiresInSeconds: JsonElement? = null,
)

@Serializable
private data class GrokTokenResponseDto(
    @SerialName("access_token")
    val accessToken: String? = null,
    @SerialName("refresh_token")
    val refreshToken: String? = null,
    @SerialName("id_token")
    val idToken: String? = null,
    @SerialName("expires_in")
    val expiresInSeconds: JsonElement? = null,
)
