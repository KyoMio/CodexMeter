package com.kmnexus.codexmeter.providers.cursor.network

import com.kmnexus.codexmeter.core.network.ProviderHttpClient
import com.kmnexus.codexmeter.domain.refresh.QuotaError
import com.kmnexus.codexmeter.providers.cursor.dto.CursorUsageResponseDto
import java.io.IOException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

class CursorUsageClient(private val httpClient: ProviderHttpClient) {
    private val json = Json { ignoreUnknownKeys = true }
    // CodexBar's token-based usage summary endpoint (cents-based plan / on-demand caps).
    private val url = "https://www.cursor.com/api/usage-summary"

    suspend fun fetchUsage(cookieValue: String): Result {
        // The WebView captures only the cookie value; Cursor's API needs the full named cookie.
        val cookieHeader = if (cookieValue.contains('=')) {
            cookieValue
        } else {
            "WorkosCursorSessionToken=$cookieValue"
        }
        val response = try {
            httpClient.get(
                url = url,
                headers = mapOf(
                    "Cookie" to cookieHeader,
                    "Accept" to "application/json",
                ),
            )
        } catch (_: IOException) {
            return Result.Failure(
                QuotaError.Network(diagnosticsDigest = "cursor_network_error"),
            )
        }

        return when (response.statusCode) {
            in 200..299 -> decodeSuccess(response.body)
            401, 403 -> Result.Failure(
                QuotaError.AuthRequired(
                    httpStatus = response.statusCode,
                    diagnosticsDigest = "cursor_auth_required_${response.statusCode}",
                ),
            )
            else -> Result.Failure(
                QuotaError.Network(diagnosticsDigest = "cursor_http_${response.statusCode}"),
            )
        }
    }

    private fun decodeSuccess(body: String): Result =
        try {
            Result.Success(json.decodeFromString<CursorUsageResponseDto>(body))
        } catch (_: SerializationException) {
            Result.Failure(
                QuotaError.Network(diagnosticsDigest = "cursor_decode_error"),
            )
        } catch (_: IllegalArgumentException) {
            Result.Failure(
                QuotaError.Network(diagnosticsDigest = "cursor_decode_error"),
            )
        }

    sealed interface Result {
        data class Success(val dto: CursorUsageResponseDto) : Result
        data class Failure(val error: QuotaError) : Result
    }
}
