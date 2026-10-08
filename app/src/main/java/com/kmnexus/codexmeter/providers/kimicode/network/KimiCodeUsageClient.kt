package com.kmnexus.codexmeter.providers.kimicode.network

import com.kmnexus.codexmeter.core.network.ProviderHttpClient
import com.kmnexus.codexmeter.domain.refresh.QuotaError
import com.kmnexus.codexmeter.providers.kimicode.dto.KimiCodeUsageResponseDto
import com.kmnexus.codexmeter.providers.kimicode.mapper.KimiCodeUsageMapper
import java.io.IOException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Fetches Kimi Code usage quota (`GET /coding/v1/usages`, Bearer API key). No path logs or returns
 * the raw response body or the API key — failures carry a fixed diagnostics digest only.
 */
class KimiCodeUsageClient(private val httpClient: ProviderHttpClient) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetchUsage(apiKey: String, baseUrl: String = DEFAULT_BASE_URL): Result {
        val response = try {
            httpClient.get(
                url = usageUrl(baseUrl),
                headers = mapOf(
                    "Authorization" to "Bearer $apiKey",
                    "Accept" to "application/json",
                ),
            )
        } catch (_: IOException) {
            return Result.Failure(
                QuotaError.Network(diagnosticsDigest = "kimi_code_network_error"),
            )
        }

        return when (response.statusCode) {
            in 200..299 -> decodeSuccess(response.body)
            401, 403 -> Result.Failure(
                QuotaError.AuthRequired(
                    httpStatus = response.statusCode,
                    diagnosticsDigest = "kimi_code_auth_required_${response.statusCode}",
                ),
            )
            else -> Result.Failure(
                QuotaError.Network(diagnosticsDigest = "kimi_code_http_${response.statusCode}"),
            )
        }
    }

    /**
     * Appends `/coding/v1/usages` to the base URL, tolerating a trailing slash; a base that already
     * ends in `/coding/v1` only needs `/usages` (users sometimes paste the full console API root).
     */
    private fun usageUrl(baseUrl: String): String {
        val trimmed = baseUrl.trimEnd('/')
        return if (trimmed.endsWith(CODING_PATH)) "$trimmed/usages" else "$trimmed$CODING_PATH$USAGE_PATH"
    }

    private fun decodeSuccess(body: String): Result =
        try {
            val dto = json.decodeFromString<KimiCodeUsageResponseDto>(body)
            if (KimiCodeUsageMapper.buildWindows(dto).isEmpty()) {
                Result.Failure(
                    QuotaError.Network(diagnosticsDigest = "kimi_code_no_quota_windows"),
                )
            } else {
                Result.Success(dto)
            }
        } catch (_: SerializationException) {
            Result.Failure(
                QuotaError.Network(diagnosticsDigest = "kimi_code_decode_error"),
            )
        } catch (_: IllegalArgumentException) {
            Result.Failure(
                QuotaError.Network(diagnosticsDigest = "kimi_code_decode_error"),
            )
        }

    sealed interface Result {
        data class Success(val dto: KimiCodeUsageResponseDto) : Result
        data class Failure(val error: QuotaError) : Result
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.kimi.com"
        private const val CODING_PATH = "/coding/v1"
        private const val USAGE_PATH = "/usages"
    }
}
