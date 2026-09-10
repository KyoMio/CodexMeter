package com.kmnexus.codexmeter.providers.grok.network

import com.kmnexus.codexmeter.core.network.ProviderHttpClient
import com.kmnexus.codexmeter.domain.refresh.QuotaError
import com.kmnexus.codexmeter.providers.grok.auth.GrokOAuthConfig
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class GrokOAuthDiscoveryClient(
    private val httpClient: ProviderHttpClient,
    private val json: Json = defaultJson,
    private val discoveryUrl: String = GrokOAuthConfig.DISCOVERY_URL,
    private val userAgent: String = DEFAULT_USER_AGENT,
    private val allowInsecureHttpForTests: Boolean = false,
) {
    suspend fun fetchEndpoints(): Result<GrokOAuthEndpoints> {
        val endpoint = validatedDiscoveryUrl() ?: return Result.Failure(lastEndpointError)

        val response = try {
            httpClient.get(
                url = endpoint,
                headers = requestHeaders(),
            )
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: IOException) {
            return Result.Failure(
                QuotaError.Network(diagnosticsDigest = "grok_discovery_network_error"),
            )
        } catch (_: IllegalArgumentException) {
            return Result.Failure(
                QuotaError.Network(diagnosticsDigest = "grok_discovery_invalid_endpoint"),
            )
        }

        return when (response.statusCode) {
            in SUCCESS_STATUS_RANGE -> decodeEndpoints(response.body)
            else -> Result.Failure(
                QuotaError.Network(diagnosticsDigest = "grok_discovery_http_${response.statusCode}"),
            )
        }
    }

    private var lastEndpointError: QuotaError = QuotaError.Network(
        diagnosticsDigest = "grok_discovery_invalid_endpoint",
    )

    private fun validatedDiscoveryUrl(): String? {
        val endpoint = discoveryUrl.toHttpUrlOrNull()
        if (endpoint == null) {
            lastEndpointError = QuotaError.Network(diagnosticsDigest = "grok_discovery_invalid_endpoint")
            return null
        }
        if (endpoint.scheme != HTTPS_SCHEME && !(allowInsecureHttpForTests && endpoint.scheme == HTTP_SCHEME)) {
            lastEndpointError = QuotaError.Network(diagnosticsDigest = "grok_discovery_insecure_endpoint")
            return null
        }
        return endpoint.toString()
    }

    private fun requestHeaders(): Map<String, String> =
        mapOf(
            ACCEPT_HEADER to APPLICATION_JSON,
            USER_AGENT_HEADER to userAgent,
        )

    private fun decodeEndpoints(body: String): Result<GrokOAuthEndpoints> =
        try {
            val response = json.decodeFromString<GrokDiscoveryResponseDto>(body)
            val deviceAuthorizationEndpoint = response.deviceAuthorizationEndpoint?.takeIf { it.isNotBlank() }
                ?: return Result.Failure(
                    QuotaError.Network(
                        diagnosticsDigest = "grok_discovery_missing_device_authorization_endpoint",
                    ),
                )
            val tokenEndpoint = response.tokenEndpoint?.takeIf { it.isNotBlank() }
                ?: return Result.Failure(
                    QuotaError.Network(
                        diagnosticsDigest = "grok_discovery_missing_token_endpoint",
                    ),
                )
            if (!isTrustedEndpointUrl(deviceAuthorizationEndpoint) || !isTrustedEndpointUrl(tokenEndpoint)) {
                return Result.Failure(
                    QuotaError.Network(diagnosticsDigest = "grok_discovery_untrusted_host"),
                )
            }

            Result.Success(
                GrokOAuthEndpoints(
                    deviceAuthorizationEndpoint = deviceAuthorizationEndpoint,
                    tokenEndpoint = tokenEndpoint,
                ),
            )
        } catch (_: SerializationException) {
            discoveryDecodeFailure()
        } catch (_: IllegalArgumentException) {
            discoveryDecodeFailure()
        }

    /** Both endpoints must stay on the xAI authority host so redirects cannot hijack OAuth traffic. */
    private fun isTrustedEndpointUrl(candidate: String): Boolean {
        val candidateUrl = candidate.toHttpUrlOrNull() ?: return false
        if (candidateUrl.scheme != HTTPS_SCHEME) return false
        return candidateUrl.host == AUTHORITY_HOST
    }

    private fun discoveryDecodeFailure(): Result.Failure =
        Result.Failure(
            QuotaError.Network(diagnosticsDigest = "grok_discovery_decode_error"),
        )

    sealed interface Result<out T> {
        class Success<out T>(
            val value: T,
        ) : Result<T> {
            override fun toString(): String = "Success(value=$value)"
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
        private val SUCCESS_STATUS_RANGE = 200..299
        private val AUTHORITY_HOST = GrokOAuthConfig.ISSUER_URL.toHttpUrl().host
        private val defaultJson = Json {
            ignoreUnknownKeys = true
        }
    }
}

data class GrokOAuthEndpoints(
    val deviceAuthorizationEndpoint: String,
    val tokenEndpoint: String,
)

@Serializable
private data class GrokDiscoveryResponseDto(
    @SerialName("device_authorization_endpoint")
    val deviceAuthorizationEndpoint: String? = null,
    @SerialName("token_endpoint")
    val tokenEndpoint: String? = null,
)
