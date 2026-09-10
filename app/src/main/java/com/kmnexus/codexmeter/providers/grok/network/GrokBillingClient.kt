package com.kmnexus.codexmeter.providers.grok.network

import com.kmnexus.codexmeter.core.network.ProviderHttpClient
import com.kmnexus.codexmeter.domain.refresh.QuotaError
import com.kmnexus.codexmeter.providers.grok.dto.GrokBillingPeriodDto
import com.kmnexus.codexmeter.providers.grok.dto.GrokBillingResponseDto
import com.kmnexus.codexmeter.providers.grok.dto.GrokProductUsageDto
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * Fetches the SuperGrok usage/billing report (`/v1/billing?format=credits`) with the OAuth access
 * token as `Authorization: Bearer`. The body is parsed defensively as a JSON tree (ZaiBalanceClient
 * style) because the endpoint is an undocumented internal contract: numeric fields may drift between
 * int / double / string and every field may be absent. Missing fields are not errors — the mapper
 * degrades them to an unavailable window.
 */
class GrokBillingClient(private val httpClient: ProviderHttpClient) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetchBilling(
        accessToken: String,
        baseUrl: String = DEFAULT_BASE_URL,
    ): Result {
        val url = "${baseUrl.trimEnd('/')}$BILLING_PATH"
        val response = try {
            httpClient.get(
                url = url,
                headers = mapOf(
                    AUTHORIZATION_HEADER to "Bearer $accessToken",
                    CLIENT_VERSION_HEADER to CLIENT_VERSION,
                ),
            )
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: IOException) {
            return Result.Failure(QuotaError.Network(diagnosticsDigest = "grok_billing_network_error"))
        }

        return when (response.statusCode) {
            in 200..299 -> decodeSuccess(response.body)
            401, 403 -> Result.Failure(
                QuotaError.AuthRequired(
                    httpStatus = response.statusCode,
                    diagnosticsDigest = "grok_billing_auth_required_${response.statusCode}",
                ),
            )
            else -> Result.Failure(
                QuotaError.Network(diagnosticsDigest = "grok_billing_http_${response.statusCode}"),
            )
        }
    }

    private fun decodeSuccess(body: String): Result {
        val root = try {
            json.parseToJsonElement(body) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return Result.Failure(QuotaError.Network(diagnosticsDigest = "grok_billing_decode_error"))

        val config = root["config"] as? JsonObject
        return Result.Success(
            GrokBillingResponseDto(
                currentPeriod = (config?.get("currentPeriod") as? JsonObject)?.let { period ->
                    GrokBillingPeriodDto(
                        type = period["type"].asString(),
                        start = period["start"].asString(),
                        end = period["end"].asString(),
                    )
                },
                creditUsagePercent = config?.get("creditUsagePercent").asDouble(),
                productUsage = (config?.get("productUsage") as? JsonArray)
                    ?.mapNotNull { entry -> entry as? JsonObject }
                    ?.map { product ->
                        GrokProductUsageDto(
                            product = product["product"].asString(),
                            usagePercent = product["usagePercent"].asDouble(),
                        )
                    }
                    ?: emptyList(),
                prepaidBalance = (config?.get("prepaidBalance") as? JsonObject)?.get("val")?.asDouble(),
                billingPeriodStart = config?.get("billingPeriodStart").asString(),
                billingPeriodEnd = config?.get("billingPeriodEnd").asString(),
            ),
        )
    }

    sealed interface Result {
        data class Success(val dto: GrokBillingResponseDto) : Result
        data class Failure(val error: QuotaError) : Result
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://cli-chat-proxy.grok.com"
        private const val BILLING_PATH = "/v1/billing?format=credits"
        private const val AUTHORIZATION_HEADER = "Authorization"
        // The chat proxy enforces a minimum CLI version header; billing carries it too (harmless).
        private const val CLIENT_VERSION_HEADER = "x-grok-client-version"
        private const val CLIENT_VERSION = "0.1.202"
    }
}

private fun JsonElement?.prim(): JsonPrimitive? = this as? JsonPrimitive

private fun JsonElement?.asDouble(): Double? =
    prim()?.let { it.doubleOrNull ?: it.contentOrNull?.trim()?.toDoubleOrNull() }

private fun JsonElement?.asString(): String? =
    prim()?.contentOrNull?.takeIf { it.isNotBlank() }
