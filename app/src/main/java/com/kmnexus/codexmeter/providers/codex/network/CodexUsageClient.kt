package com.kmnexus.codexmeter.providers.codex.network

import com.kmnexus.codexmeter.core.network.ProviderHttpClient
import com.kmnexus.codexmeter.domain.refresh.QuotaError
import com.kmnexus.codexmeter.providers.codex.dto.CodexUsageResponseDto
import java.io.IOException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class CodexUsageClient(
    private val httpClient: ProviderHttpClient,
    private val json: Json = defaultJson,
    private val endpointUrl: String = DEFAULT_ENDPOINT_URL,
    private val userAgent: String = DEFAULT_USER_AGENT,
    private val allowInsecureHttpForTests: Boolean = false,
) {
    suspend fun fetchUsage(
        accessToken: String,
        accountId: String?,
    ): Result {
        val endpoint = endpointUrl.toHttpUrlOrNull()
            ?: return Result.Failure(
                QuotaError.Network(diagnosticsDigest = "codex_usage_invalid_endpoint"),
            )
        if (endpoint.scheme != HTTPS_SCHEME && !(allowInsecureHttpForTests && endpoint.scheme == HTTP_SCHEME)) {
            return Result.Failure(
                QuotaError.Network(diagnosticsDigest = "codex_usage_insecure_endpoint"),
            )
        }

        val headers = buildMap {
            put(AUTHORIZATION_HEADER, "Bearer $accessToken")
            put(ACCEPT_HEADER, APPLICATION_JSON)
            put(USER_AGENT_HEADER, userAgent)
            accountId
                ?.takeIf { it.isNotBlank() }
                ?.let { put(ACCOUNT_ID_HEADER, it) }
        }

        val response = try {
            httpClient.get(
                url = endpoint.toString(),
                headers = headers,
            )
        } catch (_: IOException) {
            return Result.Failure(
                QuotaError.Network(diagnosticsDigest = "codex_usage_network_error"),
            )
        } catch (_: IllegalArgumentException) {
            return Result.Failure(
                QuotaError.Network(diagnosticsDigest = "codex_usage_invalid_endpoint"),
            )
        }

        return when (response.statusCode) {
            in SUCCESS_STATUS_RANGE -> decodeSuccess(response.body)
            HTTP_UNAUTHORIZED,
            HTTP_FORBIDDEN,
            -> Result.Failure(
                QuotaError.AuthRequired(
                    httpStatus = response.statusCode,
                    diagnosticsDigest = "codex_usage_auth_required_${response.statusCode}",
                ),
            )
            else -> Result.Failure(
                QuotaError.Network(diagnosticsDigest = "codex_usage_http_${response.statusCode}"),
            )
        }
    }

    private fun decodeSuccess(body: String): Result =
        try {
            val dto = json.decodeFromString<CodexUsageResponseDto>(body)
            if (dto.hasDecodeFailure()) {
                Result.Failure(
                    QuotaError.Network(diagnosticsDigest = "codex_usage_decode_error"),
                )
            } else {
                Result.Success(dto = dto)
            }
        } catch (_: SerializationException) {
            Result.Failure(
                QuotaError.Network(diagnosticsDigest = "codex_usage_decode_error"),
            )
        } catch (_: IllegalArgumentException) {
            Result.Failure(
                QuotaError.Network(diagnosticsDigest = "codex_usage_decode_error"),
            )
        }

    private fun CodexUsageResponseDto.hasDecodeFailure(): Boolean =
        rateLimit?.primaryWindowDecodeFailed == true ||
            rateLimit?.secondaryWindowDecodeFailed == true ||
            creditsDecodeFailed

    sealed interface Result {
        data class Success(
            val dto: CodexUsageResponseDto,
        ) : Result

        data class Failure(
            val error: QuotaError,
        ) : Result
    }

    companion object {
        const val DEFAULT_ENDPOINT_URL = "https://chatgpt.com/backend-api/wham/usage"
        const val DEFAULT_USER_AGENT = "CodexMeter/0.1.0"

        private const val AUTHORIZATION_HEADER = "Authorization"
        private const val ACCEPT_HEADER = "Accept"
        private const val USER_AGENT_HEADER = "User-Agent"
        private const val ACCOUNT_ID_HEADER = "ChatGPT-Account-Id"
        private const val APPLICATION_JSON = "application/json"
        private const val HTTPS_SCHEME = "https"
        private const val HTTP_SCHEME = "http"
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_FORBIDDEN = 403
        private val SUCCESS_STATUS_RANGE = 200..299
        private val defaultJson = Json {
            ignoreUnknownKeys = true
        }
    }
}
