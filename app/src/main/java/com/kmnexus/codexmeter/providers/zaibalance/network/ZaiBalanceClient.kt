package com.kmnexus.codexmeter.providers.zaibalance.network

import com.kmnexus.codexmeter.core.network.ProviderHttpClient
import com.kmnexus.codexmeter.domain.refresh.QuotaError
import com.kmnexus.codexmeter.providers.zaibalance.dto.ZaiBalanceResponseDto
import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Fetches the bigmodel account balance report with the z.ai API key sent as `Authorization: Bearer
 * <apiKey>` (verified: the plain `{id}.{secret}` key authorizes this endpoint directly, no JWT or
 * cookie). bigmodel returns HTTP 200 even on auth failure, with a body code of 1001 (missing key),
 * 1000 (auth failed) or 401 (bad/expired token) — all mapped to AuthRequired. Amounts may be
 * scientific-notation BigDecimals (e.g. `0E-9`); parsed defensively so a type variation never throws.
 */
class ZaiBalanceClient(private val httpClient: ProviderHttpClient) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetchBalance(apiKey: String, baseUrl: String = DEFAULT_BASE_URL): Result {
        val url = "${baseUrl.trimEnd('/')}$BALANCE_PATH"
        val response = try {
            httpClient.get(url = url, headers = mapOf("Authorization" to "Bearer $apiKey"))
        } catch (_: IOException) {
            return Result.Failure(QuotaError.Network(diagnosticsDigest = "zai_balance_network_error"))
        }

        return when (response.statusCode) {
            in 200..299 -> decodeSuccess(response.body)
            401, 403 -> Result.Failure(
                QuotaError.AuthRequired(
                    httpStatus = response.statusCode,
                    diagnosticsDigest = "zai_balance_auth_required_${response.statusCode}",
                ),
            )
            else -> Result.Failure(
                QuotaError.Network(diagnosticsDigest = "zai_balance_http_${response.statusCode}"),
            )
        }
    }

    private fun decodeSuccess(body: String): Result {
        val root = try {
            json.parseToJsonElement(body) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return Result.Failure(QuotaError.Network(diagnosticsDigest = "zai_balance_decode_error"))

        val code = root["code"].asInt()
        // bigmodel returns HTTP 200 with an auth-failure body code when the API key is missing (1001),
        // invalid (1000) or the token is bad/expired (401) -> surface as re-auth, not a transient error.
        if (code == 1001 || code == 1000 || code == 401) {
            return Result.Failure(
                QuotaError.AuthRequired(httpStatus = null, diagnosticsDigest = "zai_balance_auth_failed_$code"),
            )
        }
        val success = root["success"].asBool()
        val isSuccess = success == true || code == 200 || code == 0
        if (!isSuccess) {
            return Result.Failure(QuotaError.Network(diagnosticsDigest = "zai_balance_api_error_$code"))
        }

        val dataObj = root["data"] as? JsonObject
        return Result.Success(
            ZaiBalanceResponseDto(
                code = code,
                success = success,
                availableBalance = dataObj?.get("availableBalance").asDouble(),
            ),
        )
    }

    sealed interface Result {
        data class Success(val dto: ZaiBalanceResponseDto) : Result
        data class Failure(val error: QuotaError) : Result
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://open.bigmodel.cn"
        private const val BALANCE_PATH = "/api/biz/account/query-customer-account-report"
    }
}

private fun JsonElement?.prim(): JsonPrimitive? = this as? JsonPrimitive

private fun JsonElement?.asInt(): Int? =
    prim()?.let { it.intOrNull ?: it.doubleOrNull?.toInt() ?: it.contentOrNull?.trim()?.toIntOrNull() }

private fun JsonElement?.asDouble(): Double? =
    prim()?.let { it.doubleOrNull ?: it.contentOrNull?.trim()?.toDoubleOrNull() }

private fun JsonElement?.asBool(): Boolean? =
    prim()?.let { it.booleanOrNull ?: it.contentOrNull?.trim()?.toBooleanStrictOrNull() }
