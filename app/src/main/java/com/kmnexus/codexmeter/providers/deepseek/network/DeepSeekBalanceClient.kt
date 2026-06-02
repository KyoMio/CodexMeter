package com.kmnexus.codexmeter.providers.deepseek.network

import com.kmnexus.codexmeter.core.network.ProviderHttpClient
import com.kmnexus.codexmeter.domain.refresh.QuotaError
import com.kmnexus.codexmeter.providers.deepseek.dto.DeepSeekBalanceResponseDto
import java.io.IOException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

class DeepSeekBalanceClient(private val httpClient: ProviderHttpClient) {
    private val json = Json { ignoreUnknownKeys = true }
    private val url = "https://api.deepseek.com/user/balance"

    suspend fun fetchBalance(apiKey: String): Result {
        val response = try {
            httpClient.get(
                url = url,
                headers = mapOf("Authorization" to "Bearer $apiKey"),
            )
        } catch (_: IOException) {
            return Result.Failure(
                QuotaError.Network(diagnosticsDigest = "deepseek_network_error"),
            )
        }

        return when (response.statusCode) {
            in 200..299 -> decodeSuccess(response.body)
            401, 403 -> Result.Failure(
                QuotaError.AuthRequired(
                    httpStatus = response.statusCode,
                    diagnosticsDigest = "deepseek_auth_required_${response.statusCode}",
                ),
            )
            else -> Result.Failure(
                QuotaError.Network(diagnosticsDigest = "deepseek_http_${response.statusCode}"),
            )
        }
    }

    private fun decodeSuccess(body: String): Result =
        try {
            Result.Success(json.decodeFromString<DeepSeekBalanceResponseDto>(body))
        } catch (_: SerializationException) {
            Result.Failure(
                QuotaError.Network(diagnosticsDigest = "deepseek_decode_error"),
            )
        } catch (_: IllegalArgumentException) {
            Result.Failure(
                QuotaError.Network(diagnosticsDigest = "deepseek_decode_error"),
            )
        }

    sealed interface Result {
        data class Success(val dto: DeepSeekBalanceResponseDto) : Result
        data class Failure(val error: QuotaError) : Result
    }
}
