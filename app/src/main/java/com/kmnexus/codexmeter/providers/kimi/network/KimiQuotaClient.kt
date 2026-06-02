package com.kmnexus.codexmeter.providers.kimi.network

import com.kmnexus.codexmeter.core.network.ProviderHttpClient
import com.kmnexus.codexmeter.domain.refresh.QuotaError
import com.kmnexus.codexmeter.providers.kimi.auth.KimiJwtClaims
import com.kmnexus.codexmeter.providers.kimi.dto.KimiQuotaResponseDto
import java.io.IOException
import java.util.TimeZone
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Fetches Kimi coding usage from the www.kimi.com Connect-RPC billing service, matching CodexBar's
 * [KimiUsageFetcher]. The captured `kimi-auth` value is a JWT used both as the Bearer token and the
 * cookie; session identifiers are derived from it. There is a single global host (no region split).
 */
class KimiQuotaClient(private val httpClient: ProviderHttpClient) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetchQuota(authToken: String): Result {
        val session = KimiJwtClaims.session(authToken)
        val headers = buildMap {
            put("Authorization", "Bearer $authToken")
            put("Cookie", "kimi-auth=$authToken")
            put("Origin", ORIGIN)
            put("Referer", "$ORIGIN/code/console")
            put("Accept", "*/*")
            put("Accept-Language", "en-US,en;q=0.9")
            put("User-Agent", USER_AGENT)
            put("connect-protocol-version", "1")
            put("x-language", "en-US")
            put("x-msh-platform", "web")
            put("r-timezone", TimeZone.getDefault().id)
            session.deviceId?.let { put("x-msh-device-id", it) }
            session.sessionId?.let { put("x-msh-session-id", it) }
            session.trafficId?.let { put("x-traffic-id", it) }
        }

        val response = try {
            httpClient.postJson(url = USAGE_URL, jsonBody = REQUEST_BODY, headers = headers)
        } catch (_: IOException) {
            return Result.Failure(QuotaError.Network(diagnosticsDigest = "kimi_network_error"))
        }

        return when (response.statusCode) {
            in 200..299 -> decodeSuccess(response.body)
            401, 403 -> Result.Failure(
                QuotaError.AuthRequired(
                    httpStatus = response.statusCode,
                    diagnosticsDigest = "kimi_auth_required_${response.statusCode}",
                ),
            )
            else -> Result.Failure(
                QuotaError.Network(diagnosticsDigest = "kimi_http_${response.statusCode}"),
            )
        }
    }

    private fun decodeSuccess(body: String): Result {
        if (body.isBlank() || body.trim() == "{}") {
            return Result.Failure(
                QuotaError.AuthRequired(
                    httpStatus = null,
                    diagnosticsDigest = "kimi_empty_response_auth_required",
                ),
            )
        }
        return try {
            val dto = json.decodeFromString<KimiQuotaResponseDto>(body)
            if (dto.codingUsage() == null) {
                Result.Failure(QuotaError.Network(diagnosticsDigest = "kimi_no_coding_scope"))
            } else {
                Result.Success(dto)
            }
        } catch (_: SerializationException) {
            Result.Failure(QuotaError.Network(diagnosticsDigest = "kimi_decode_error"))
        } catch (_: IllegalArgumentException) {
            Result.Failure(QuotaError.Network(diagnosticsDigest = "kimi_decode_error"))
        }
    }

    sealed interface Result {
        data class Success(val dto: KimiQuotaResponseDto) : Result
        data class Failure(val error: QuotaError) : Result
    }

    private companion object {
        const val ORIGIN = "https://www.kimi.com"
        const val USAGE_URL =
            "https://www.kimi.com/apiv2/kimi.gateway.billing.v1.BillingService/GetUsages"
        const val REQUEST_BODY = """{"scope":["FEATURE_CODING"]}"""
        const val USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36"
    }
}
