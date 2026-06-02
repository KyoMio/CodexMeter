package com.kmnexus.codexmeter.providers.minimax.network

import com.kmnexus.codexmeter.core.network.ProviderHttpClient
import com.kmnexus.codexmeter.domain.refresh.QuotaError
import com.kmnexus.codexmeter.providers.minimax.dto.MiniMaxUsageResponseDto
import java.io.IOException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

class MiniMaxUsageClient(private val httpClient: ProviderHttpClient) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetchUsage(apiKey: String, baseUrl: String = DEFAULT_BASE_URL): Result {
        val url = "${baseUrl.trimEnd('/')}$USAGE_PATH"
        val response = try {
            httpClient.get(
                url = url,
                headers = mapOf(
                    "Authorization" to "Bearer $apiKey",
                    "Accept" to "application/json",
                    "Content-Type" to "application/json",
                    "MM-API-Source" to "CodexMeter",
                ),
            )
        } catch (_: IOException) {
            return Result.Failure(
                QuotaError.Network(diagnosticsDigest = "minimax_network_error"),
            )
        }

        return when (response.statusCode) {
            in 200..299 -> decodeSuccess(response.body)
            401, 403 -> Result.Failure(
                QuotaError.AuthRequired(
                    httpStatus = response.statusCode,
                    diagnosticsDigest = "minimax_auth_required_${response.statusCode}",
                ),
            )
            else -> Result.Failure(
                QuotaError.Network(diagnosticsDigest = "minimax_http_${response.statusCode}"),
            )
        }
    }

    private fun decodeSuccess(body: String): Result =
        try {
            val dto = json.decodeFromString<MiniMaxUsageResponseDto>(body)
            val status = dto.statusCode
            when {
                status == null || status == 0 -> Result.Success(dto)
                // 1004 / login-related messages mean the credential is rejected.
                status == 1004 ||
                    dto.statusMessage?.contains("login", ignoreCase = true) == true ->
                    Result.Failure(
                        QuotaError.AuthRequired(
                            httpStatus = null,
                            diagnosticsDigest = "minimax_status_${status}",
                        ),
                    )
                else -> Result.Failure(
                    QuotaError.Network(diagnosticsDigest = "minimax_status_${status}"),
                )
            }
        } catch (_: SerializationException) {
            Result.Failure(
                QuotaError.Network(diagnosticsDigest = "minimax_decode_error"),
            )
        } catch (_: IllegalArgumentException) {
            Result.Failure(
                QuotaError.Network(diagnosticsDigest = "minimax_decode_error"),
            )
        }

    sealed interface Result {
        data class Success(val dto: MiniMaxUsageResponseDto) : Result
        data class Failure(val error: QuotaError) : Result
    }

    companion object {
        /** Global platform (api.minimax.io). China-mainland accounts must select api.minimaxi.com. */
        const val DEFAULT_BASE_URL = "https://api.minimax.io"
        private const val USAGE_PATH = "/v1/api/openplatform/coding_plan/remains"
    }
}
