package com.kmnexus.codexmeter.providers.antigravity.network

import com.kmnexus.codexmeter.core.network.ProviderHttpClient
import com.kmnexus.codexmeter.domain.refresh.QuotaError
import com.kmnexus.codexmeter.providers.antigravity.dto.AntigravityQuotaResponseDto
import java.io.IOException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Fetches Antigravity quota from Google's Cloud Code PA endpoints (CodexBar
 * `AntigravityRemoteUsageFetcher`): tier from `:loadCodeAssist`, per-model remaining fractions from
 * `:retrieveUserQuota`. Both are POST with a `Bearer` token and the `antigravity` user agent.
 */
class AntigravityQuotaClient(private val httpClient: ProviderHttpClient) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetchQuota(accessToken: String): Result {
        val headers = mapOf(
            "Authorization" to "Bearer $accessToken",
            "Content-Type" to "application/json",
            "User-Agent" to USER_AGENT,
        )

        val tier = try {
            val tierResponse = httpClient.postJson(LOAD_CODE_ASSIST_URL, LOAD_CODE_ASSIST_BODY, headers)
            if (tierResponse.statusCode in 200..299) parseTier(tierResponse.body) else null
        } catch (_: Exception) {
            null
        }

        val quotaResponse = try {
            httpClient.postJson(RETRIEVE_QUOTA_URL, EMPTY_BODY, headers)
        } catch (_: IOException) {
            return Result.Failure(QuotaError.Network(diagnosticsDigest = "antigravity_network_error"))
        }

        return when (quotaResponse.statusCode) {
            in 200..299 -> decodeSuccess(quotaResponse.body, tier)
            401, 403 -> Result.Failure(
                QuotaError.AuthRequired(
                    httpStatus = quotaResponse.statusCode,
                    diagnosticsDigest = "antigravity_auth_required_${quotaResponse.statusCode}",
                ),
            )
            else -> Result.Failure(
                QuotaError.Network(diagnosticsDigest = "antigravity_http_${quotaResponse.statusCode}"),
            )
        }
    }

    private fun parseTier(body: String): String? {
        if (body.isBlank()) return null
        return try {
            val resp = json.decodeFromString<CodeAssistResponse>(body)
            (resp.currentTier?.name ?: resp.currentTier?.id)?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeSuccess(body: String, tier: String?): Result =
        try {
            val parsed = json.decodeFromString<AntigravityQuotaResponseDto>(body)
            Result.Success(AntigravityQuotaResponseDto(tier = tier, buckets = parsed.buckets))
        } catch (_: SerializationException) {
            Result.Failure(QuotaError.Network(diagnosticsDigest = "antigravity_decode_error"))
        } catch (_: IllegalArgumentException) {
            Result.Failure(QuotaError.Network(diagnosticsDigest = "antigravity_decode_error"))
        }

    @kotlinx.serialization.Serializable
    private data class CodeAssistResponse(val currentTier: TierInfo? = null) {
        @kotlinx.serialization.Serializable
        data class TierInfo(val id: String? = null, val name: String? = null)
    }

    sealed interface Result {
        data class Success(val dto: AntigravityQuotaResponseDto) : Result
        data class Failure(val error: QuotaError) : Result
    }

    private companion object {
        const val BASE_URL = "https://cloudcode-pa.googleapis.com"
        const val LOAD_CODE_ASSIST_URL = "$BASE_URL/v1internal:loadCodeAssist"
        const val RETRIEVE_QUOTA_URL = "$BASE_URL/v1internal:retrieveUserQuota"
        const val USER_AGENT = "antigravity"
        const val LOAD_CODE_ASSIST_BODY =
            """{"metadata":{"ideType":"ANTIGRAVITY","platform":"PLATFORM_UNSPECIFIED","pluginType":"GEMINI"}}"""
        const val EMPTY_BODY = "{}"
    }
}
