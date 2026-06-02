package com.kmnexus.codexmeter.providers.codex.auth

import com.kmnexus.codexmeter.core.network.ProviderHttpClient
import com.kmnexus.codexmeter.domain.refresh.QuotaError
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class CodexDeviceCodeClient(
    private val httpClient: ProviderHttpClient,
    private val json: Json = defaultJson,
    private val userCodeEndpointUrl: String = CodexOAuthConfig.DEVICE_USER_CODE_ENDPOINT_URL,
    private val tokenEndpointUrl: String = CodexOAuthConfig.DEVICE_TOKEN_ENDPOINT_URL,
    private val userAgent: String = DEFAULT_USER_AGENT,
    private val clock: Clock = Clock.systemUTC(),
    private val allowInsecureHttpForTests: Boolean = false,
) {
    suspend fun requestDeviceCode(): Result<DeviceCodeChallenge> {
        val endpoint = validatedEndpoint(
            endpointUrl = userCodeEndpointUrl,
            invalidDigest = "codex_device_code_invalid_endpoint",
            insecureDigest = "codex_device_code_insecure_endpoint",
        ) ?: return Result.Failure(lastEndpointError)

        val response = try {
            httpClient.postJson(
                url = endpoint,
                jsonBody = json.encodeToString(
                    DeviceCodeRequestDto.serializer(),
                    DeviceCodeRequestDto(
                        clientId = CodexOAuthConfig.CLIENT_ID,
                        scope = CodexOAuthConfig.SCOPE,
                        redirectUri = CodexOAuthConfig.DEVICE_REDIRECT_URI,
                    ),
                ),
                headers = requestHeaders(),
            )
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: IOException) {
            return Result.Failure(
                QuotaError.Network(diagnosticsDigest = "codex_device_code_network_error"),
            )
        } catch (_: IllegalArgumentException) {
            return Result.Failure(
                QuotaError.Network(diagnosticsDigest = "codex_device_code_invalid_endpoint"),
            )
        }

        return when (response.statusCode) {
            in SUCCESS_STATUS_RANGE -> decodeDeviceCodeChallenge(response.body)
            else -> Result.Failure(
                QuotaError.Network(diagnosticsDigest = "codex_device_code_http_${response.statusCode}"),
            )
        }
    }

    suspend fun pollAuthorization(challenge: DeviceCodeChallenge): Result<DeviceCodeAuthorization> {
        if (challenge.deviceAuthId.isBlank()) {
            return Result.Failure(
                QuotaError.Network(diagnosticsDigest = "codex_device_authorization_invalid_challenge"),
            )
        }

        val endpoint = validatedEndpoint(
            endpointUrl = tokenEndpointUrl,
            invalidDigest = "codex_device_authorization_invalid_endpoint",
            insecureDigest = "codex_device_authorization_insecure_endpoint",
        ) ?: return Result.Failure(lastEndpointError)

        val response = try {
            httpClient.postJson(
                url = endpoint,
                jsonBody = json.encodeToString(
                    DeviceAuthorizationPollRequestDto.serializer(),
                    DeviceAuthorizationPollRequestDto(
                        clientId = CodexOAuthConfig.CLIENT_ID,
                        userCode = challenge.userCode,
                        deviceAuthId = challenge.deviceAuthId,
                        redirectUri = CodexOAuthConfig.DEVICE_REDIRECT_URI,
                    ),
                ),
                headers = requestHeaders(),
            )
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: IOException) {
            return Result.Failure(
                QuotaError.Network(diagnosticsDigest = "codex_device_authorization_network_error"),
            )
        } catch (_: IllegalArgumentException) {
            return Result.Failure(
                QuotaError.Network(diagnosticsDigest = "codex_device_authorization_invalid_endpoint"),
            )
        }

        if (response.statusCode == HTTP_FORBIDDEN || response.statusCode == HTTP_NOT_FOUND) {
            return Result.Pending
        }

        return when (response.statusCode) {
            in SUCCESS_STATUS_RANGE -> decodeAuthorization(response.body)
            else -> {
                if (isPendingStatusBody(response.body)) {
                    Result.Pending
                } else {
                    Result.Failure(
                        QuotaError.Network(
                            diagnosticsDigest = "codex_device_authorization_http_${response.statusCode}",
                        ),
                    )
                }
            }
        }
    }

    private var lastEndpointError: QuotaError = QuotaError.Network(
        diagnosticsDigest = "codex_device_auth_invalid_endpoint",
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

    private fun validatedVerificationUri(candidate: String): String? {
        val candidateUrl = candidate.toHttpUrlOrNull() ?: return null
        val expectedUrl = CodexOAuthConfig.DEVICE_VERIFICATION_URL.toHttpUrlOrNull() ?: return null
        if (candidateUrl.scheme != expectedUrl.scheme) return null
        if (candidateUrl.host != expectedUrl.host) return null
        if (candidateUrl.encodedPath != expectedUrl.encodedPath) return null
        if (candidateUrl.encodedQuery != null || candidateUrl.encodedFragment != null) return null
        return expectedUrl.toString()
    }

    private fun decodeDeviceCodeChallenge(body: String): Result<DeviceCodeChallenge> =
        try {
            val response = json.decodeFromString<DeviceCodeChallengeResponseDto>(body)
            val userCode = response.userCode?.takeIf { it.isNotBlank() }
                ?: return deviceCodeDecodeFailure()
            val deviceAuthId = response.deviceAuthId?.takeIf { it.isNotBlank() }
                ?: return deviceCodeDecodeFailure()
            val intervalSeconds = response.intervalSeconds.positiveIntOrNull()
                ?: return deviceCodeDecodeFailure()
            val expiresInSeconds = response.expiresInSeconds.positiveIntOrNull()
                ?: response.expiresAt?.expiresInSecondsOrNull()
                ?: return deviceCodeDecodeFailure()
            val verificationUri = response.verificationUri?.takeIf { it.isNotBlank() }
                ?.let { unsafeCandidate ->
                    validatedVerificationUri(unsafeCandidate)
                        ?: return Result.Failure(
                            QuotaError.Network(
                                diagnosticsDigest = "codex_device_code_unsafe_verification_uri",
                            ),
                        )
                }
                ?: CodexOAuthConfig.DEVICE_VERIFICATION_URL

            Result.Success(
                DeviceCodeChallenge(
                    userCode = userCode,
                    deviceAuthId = deviceAuthId,
                    verificationUri = verificationUri,
                    intervalSeconds = intervalSeconds,
                    expiresInSeconds = expiresInSeconds,
                ),
            )
        } catch (_: SerializationException) {
            deviceCodeDecodeFailure()
        } catch (_: IllegalArgumentException) {
            deviceCodeDecodeFailure()
        }

    private fun decodeAuthorization(body: String): Result<DeviceCodeAuthorization> =
        try {
            if (isPendingStatusBody(body)) {
                return Result.Pending
            }
            val response = json.decodeFromString<DeviceAuthorizationResponseDto>(body)
            val authorizationCode = response.authorizationCode?.takeIf { it.isNotBlank() }
                ?: return authorizationDecodeFailure()
            val codeVerifier = response.codeVerifier?.takeIf { it.isNotBlank() }
                ?: return authorizationDecodeFailure()

            Result.Success(
                DeviceCodeAuthorization(
                    authorizationCode = authorizationCode,
                    codeVerifier = codeVerifier,
                ),
            )
        } catch (_: SerializationException) {
            authorizationDecodeFailure()
        } catch (_: IllegalArgumentException) {
            authorizationDecodeFailure()
        }

    private fun isPendingStatusBody(body: String): Boolean =
        try {
            val root = json.parseToJsonElement(body) as? JsonObject ?: return false
            root.stringValue("status") in PENDING_STATUSES ||
                root.stringValue("error") in PENDING_STATUSES ||
                root.nestedStringValue(parent = "error", child = "code") in PENDING_STATUSES
        } catch (_: SerializationException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        }

    private fun JsonObject.stringValue(name: String): String? =
        (this[name] as? JsonPrimitive)
            ?.contentOrNull
            ?.lowercase()

    private fun JsonObject.nestedStringValue(parent: String, child: String): String? =
        ((this[parent] as? JsonObject)?.get(child) as? JsonPrimitive)
            ?.contentOrNull
            ?.lowercase()

    private fun JsonElement?.positiveIntOrNull(): Int? =
        (this as? JsonPrimitive)
            ?.contentOrNull
            ?.toIntOrNull()
            ?.takeIf { it > 0 }

    private fun String.expiresInSecondsOrNull(): Int? =
        try {
            Duration.between(clock.instant(), Instant.parse(this))
                .seconds
                .takeIf { it > 0 && it <= Int.MAX_VALUE }
                ?.toInt()
        } catch (_: Exception) {
            null
        }

    private fun deviceCodeDecodeFailure(): Result.Failure =
        Result.Failure(
            QuotaError.Network(diagnosticsDigest = "codex_device_code_decode_error"),
        )

    private fun authorizationDecodeFailure(): Result.Failure =
        Result.Failure(
            QuotaError.Network(diagnosticsDigest = "codex_device_authorization_decode_error"),
        )

    sealed interface Result<out T> {
        class Success<out T>(
            val value: T,
        ) : Result<T> {
            override fun toString(): String = "Success(value=$value)"
        }

        object Pending : Result<Nothing> {
            override fun toString(): String = "Pending"
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
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_NOT_FOUND = 404
        private val SUCCESS_STATUS_RANGE = 200..299
        private val PENDING_STATUSES = setOf(
            "authorization_pending",
            "deviceauth_authorization_pending",
            "pending",
            "slow_down",
        )
        private val defaultJson = Json {
            ignoreUnknownKeys = true
        }
    }
}

data class DeviceCodeChallenge(
    val userCode: String,
    val deviceAuthId: String,
    val verificationUri: String,
    val intervalSeconds: Int,
    val expiresInSeconds: Int,
) {
    override fun toString(): String =
        "DeviceCodeChallenge(" +
            "userCode=[REDACTED], " +
            "deviceAuthId=[REDACTED], " +
            "verificationUri=$verificationUri, " +
            "intervalSeconds=$intervalSeconds, " +
            "expiresInSeconds=$expiresInSeconds" +
            ")"
}

data class DeviceCodeAuthorization(
    val authorizationCode: String,
    val codeVerifier: String,
) {
    override fun toString(): String =
        "DeviceCodeAuthorization(authorizationCode=[REDACTED], codeVerifier=[REDACTED])"
}

@Serializable
private data class DeviceCodeRequestDto(
    @SerialName("client_id")
    val clientId: String,
    val scope: String,
    @SerialName("redirect_uri")
    val redirectUri: String,
)

@Serializable
private data class DeviceAuthorizationPollRequestDto(
    @SerialName("client_id")
    val clientId: String,
    @SerialName("user_code")
    val userCode: String,
    @SerialName("device_auth_id")
    val deviceAuthId: String,
    @SerialName("redirect_uri")
    val redirectUri: String,
)

@Serializable
private data class DeviceCodeChallengeResponseDto(
    @SerialName("user_code")
    val userCode: String? = null,
    @SerialName("device_auth_id")
    val deviceAuthId: String? = null,
    @SerialName("verification_uri")
    val verificationUri: String? = null,
    @SerialName("interval")
    val intervalSeconds: JsonElement? = null,
    @SerialName("expires_in")
    val expiresInSeconds: JsonElement? = null,
    @SerialName("expires_at")
    val expiresAt: String? = null,
)

@Serializable
private data class DeviceAuthorizationResponseDto(
    @SerialName("authorization_code")
    val authorizationCode: String? = null,
    @SerialName("code_verifier")
    val codeVerifier: String? = null,
)
