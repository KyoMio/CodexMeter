package com.kmnexus.codexmeter.providers.zai.network

import com.kmnexus.codexmeter.core.network.ProviderHttpClient
import com.kmnexus.codexmeter.domain.refresh.QuotaError
import com.kmnexus.codexmeter.providers.zai.dto.ZaiQuotaResponseDto
import java.io.IOException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

class ZaiQuotaClient(private val httpClient: ProviderHttpClient) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetchQuota(apiKey: String, baseUrl: String = DEFAULT_BASE_URL): Result {
        val url = "${baseUrl.trimEnd('/')}$QUOTA_PATH"
        val response = try {
            httpClient.get(
                url = url,
                headers = mapOf("Authorization" to "Bearer $apiKey"),
            )
        } catch (_: IOException) {
            return Result.Failure(
                QuotaError.Network(diagnosticsDigest = "zai_network_error"),
            )
        }

        return when (response.statusCode) {
            in 200..299 -> decodeSuccess(response.body)
            401, 403 -> Result.Failure(
                QuotaError.AuthRequired(
                    httpStatus = response.statusCode,
                    diagnosticsDigest = "zai_auth_required_${response.statusCode}",
                ),
            )
            else -> Result.Failure(
                QuotaError.Network(diagnosticsDigest = "zai_http_${response.statusCode}"),
            )
        }
    }

    /**
     * Parses defensively from the JSON tree rather than a strict @Serializable schema: z.ai's field
     * types vary by account/plan (numbers as strings, omitted fields, extra keys), so strict decoding
     * threw `zai_decode_error`. Reading each field tolerantly never throws on a type mismatch.
     */
    private fun decodeSuccess(body: String): Result {
        val root = try {
            json.parseToJsonElement(body) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return Result.Failure(QuotaError.Network(diagnosticsDigest = "zai_decode_error"))

        val code = root["code"].asInt()
        val success = root["success"].asBool()
        val msg = root["msg"].asString() ?: root["message"].asString()
        val isSuccess = success == true || code == 200 || code == 0
        if (!isSuccess) {
            return Result.Failure(
                QuotaError.Network(diagnosticsDigest = "zai_api_error_${code}_${msg ?: ""}"),
            )
        }

        val dataObj = root["data"] as? JsonObject
        val limits = (dataObj?.get("limits") as? JsonArray).orEmpty().mapNotNull { element ->
            (element as? JsonObject)?.let { item ->
                ZaiQuotaResponseDto.LimitItem(
                    type = item["type"].asString(),
                    unit = item["unit"].asInt(),
                    number = item["number"].asInt(),
                    usage = item["usage"].asInt(),
                    currentValue = item["currentValue"].asInt(),
                    remaining = item["remaining"].asInt(),
                    percentage = item["percentage"].asDouble(),
                    nextResetTime = item["nextResetTime"].asLong(),
                )
            }
        }

        return Result.Success(
            ZaiQuotaResponseDto(
                code = code,
                msg = msg,
                success = success,
                data = ZaiQuotaResponseDto.QuotaData(
                    limits = limits,
                    planName = dataObj?.get("planName").asString(),
                    level = dataObj?.get("level").asString(),
                ),
            ),
        )
    }

    sealed interface Result {
        data class Success(val dto: ZaiQuotaResponseDto) : Result
        data class Failure(val error: QuotaError) : Result
    }

    companion object {
        /** China platform (智谱 / bigmodel). International accounts must select api.z.ai. */
        const val DEFAULT_BASE_URL = "https://open.bigmodel.cn"
        private const val QUOTA_PATH = "/api/monitor/usage/quota/limit"
    }
}

private fun JsonElement?.prim(): JsonPrimitive? = this as? JsonPrimitive

private fun JsonElement?.asString(): String? = prim()?.contentOrNull

private fun JsonElement?.asInt(): Int? =
    prim()?.let { it.intOrNull ?: it.doubleOrNull?.toInt() ?: it.contentOrNull?.trim()?.toIntOrNull() }

private fun JsonElement?.asLong(): Long? =
    prim()?.let { it.longOrNull ?: it.doubleOrNull?.toLong() ?: it.contentOrNull?.trim()?.toLongOrNull() }

private fun JsonElement?.asDouble(): Double? =
    prim()?.let { it.doubleOrNull ?: it.contentOrNull?.trim()?.toDoubleOrNull() }

private fun JsonElement?.asBool(): Boolean? =
    prim()?.let { it.booleanOrNull ?: it.contentOrNull?.trim()?.toBooleanStrictOrNull() }
