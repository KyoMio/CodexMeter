package com.kmnexus.codexmeter.providers.claude.network

import com.kmnexus.codexmeter.core.network.ProviderHttpClient
import com.kmnexus.codexmeter.domain.refresh.QuotaError
import com.kmnexus.codexmeter.providers.claude.dto.ClaudeUsageResponseDto
import java.io.IOException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

class ClaudeUsageClient(private val httpClient: ProviderHttpClient) {
    private val json = Json { ignoreUnknownKeys = true }
    // CodexBar's Claude OAuth usage endpoint; requires the oauth beta header + Claude Code UA.
    private val url = "https://api.anthropic.com/api/oauth/usage"

    suspend fun fetchUsage(accessToken: String): Result {
        val response = try {
            httpClient.get(
                url = url,
                headers = mapOf(
                    "Authorization" to "Bearer $accessToken",
                    "Accept" to "application/json",
                    "Content-Type" to "application/json",
                    "anthropic-beta" to "oauth-2025-04-20",
                    "User-Agent" to "claude-code/2.1.0",
                ),
            )
        } catch (_: IOException) {
            return Result.Failure(
                QuotaError.Network(diagnosticsDigest = "claude_network_error"),
            )
        }

        return when (response.statusCode) {
            in 200..299 -> decodeSuccess(response.body)
            401, 403 -> Result.Failure(
                QuotaError.AuthRequired(
                    httpStatus = response.statusCode,
                    diagnosticsDigest = "claude_auth_required_${response.statusCode}",
                ),
            )
            else -> Result.Failure(
                QuotaError.Network(diagnosticsDigest = "claude_http_${response.statusCode}"),
            )
        }
    }

    private fun decodeSuccess(body: String): Result =
        try {
            Result.Success(json.decodeFromString<ClaudeUsageResponseDto>(body))
        } catch (_: SerializationException) {
            Result.Failure(
                QuotaError.Network(diagnosticsDigest = "claude_decode_error"),
            )
        } catch (_: IllegalArgumentException) {
            Result.Failure(
                QuotaError.Network(diagnosticsDigest = "claude_decode_error"),
            )
        }

    sealed interface Result {
        data class Success(val dto: ClaudeUsageResponseDto) : Result
        data class Failure(val error: QuotaError) : Result
    }
}
