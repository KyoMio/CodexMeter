package com.kmnexus.codexmeter.providers.antigravity.network

import com.kmnexus.codexmeter.core.network.ProviderHttpClient
import com.kmnexus.codexmeter.domain.refresh.QuotaError
import com.kmnexus.codexmeter.providers.antigravity.dto.AntigravityQuotaResponseDto
import com.kmnexus.codexmeter.providers.antigravity.dto.AntigravityQuotaSummaryDto
import kotlinx.coroutines.delay
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * One per-model quota entry resolved by the client (from `:fetchAvailableModels`, optionally merged
 * with `:retrieveUserQuota` buckets). `label` is the display name served by the endpoint, falling
 * back to the raw model id; `remainingFraction` is 0–1, null meaning fully used; `resetTime` is the
 * raw ISO-8601 string as reported.
 */
data class AntigravityModelQuota(
    val modelId: String,
    val label: String,
    val remainingFraction: Double?,
    val resetTime: String?,
)

/**
 * Fetches Antigravity quota from Google's Cloud Code PA endpoints, mirroring CodexBar's
 * `AntigravityRemoteUsageFetcher.fetch()` orchestration:
 *
 * 1. `:loadCodeAssist` resolves the tier and, through `:onboardUser` plus a bounded re-query loop,
 *    the `cloudaicompanionProject` that scopes every quota request. Without a project the requests
 *    degrade to an empty body (the pre-T-504 behavior).
 * 2. The primary data source is `:retrieveUserQuotaSummary` on daily-cloudcode-pa, whose grouped
 *    buckets carry the 5-hour / weekly windows. A summary without known quota — request, HTTP or
 *    decode failure alike — silently falls back to per-model instead of failing.
 * 3. The per-model path uses `:fetchAvailableModels`; a suspiciously all-full placeholder result is
 *    verified against `:retrieveUserQuota` and merged with its buckets.
 *
 * All requests are POST JSON with a `Bearer` token and the Hub client-family user agent, which the
 * quota endpoints require. Any endpoint answering 401/403 maps to [QuotaError.AuthRequired]
 * immediately; other failures on the per-model path keep the `antigravity_*` digests.
 */
class AntigravityQuotaClient(
    private val httpClient: ProviderHttpClient,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val summaryBaseUrl: String = DEFAULT_SUMMARY_BASE_URL,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetchQuota(accessToken: String): Result {
        val headers = mapOf(
            "Authorization" to "Bearer $accessToken",
            "Content-Type" to "application/json",
            "User-Agent" to USER_AGENT,
        )

        val codeAssist = try {
            val response = httpClient.postJson(loadCodeAssistUrl(), LOAD_CODE_ASSIST_BODY, headers)
            when {
                response.statusCode == 401 || response.statusCode == 403 ->
                    return authRequired(response.statusCode)
                response.statusCode in 200..299 -> decode<CodeAssistResponse>(response.body)
                else -> null
            }
        } catch (_: Exception) {
            null
        }

        var project: String? = null
        when (val resolution = resolveProject(codeAssist, headers)) {
            is ProjectResolution.Auth -> return authRequired(resolution.statusCode)
            is ProjectResolution.Found -> project = resolution.project
            ProjectResolution.Unavailable -> Unit
        }
        val tier = codeAssist?.currentTier?.name?.takeIf { it.isNotBlank() }
            ?: codeAssist?.currentTier?.id?.takeIf { it.isNotBlank() }

        val summary = try {
            val response = httpClient.postJson(summaryQuotaUrl(), projectBody(project), headers)
            when {
                response.statusCode == 401 || response.statusCode == 403 ->
                    return authRequired(response.statusCode)
                response.statusCode in 200..299 -> decode<AntigravityQuotaSummaryDto>(response.body)
                else -> null
            }
        } catch (_: Exception) {
            null
        }
        if (summary != null && summary.hasKnownQuotaSummary()) {
            return Result.Success(tier = tier, summary = summary, models = emptyList())
        }

        return fetchPerModelQuota(project, tier, headers)
    }

    /** Onboards the account if needed, then polls loadCodeAssist for the project, bounded. */
    private suspend fun resolveProject(
        codeAssist: CodeAssistResponse?,
        headers: Map<String, String>,
    ): ProjectResolution {
        codeAssist?.cloudaicompanionProject?.value?.takeIf { it.isNotBlank() }?.let {
            return ProjectResolution.Found(it)
        }

        val onboardedProject: String? = try {
            val response = httpClient.postJson(onboardUrl(), onboardBody(pickOnboardTier(codeAssist)), headers)
            when {
                response.statusCode == 401 || response.statusCode == 403 ->
                    return ProjectResolution.Auth(response.statusCode)
                response.statusCode in 200..299 -> decode<OnboardResponse>(response.body)
                else -> null
            }?.response?.cloudaicompanionProject?.value?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
        if (onboardedProject != null) return ProjectResolution.Found(onboardedProject)

        repeat(PROJECT_POLL_ATTEMPTS) {
            delay(PROJECT_POLL_DELAY_MS)
            val body = try {
                val response = httpClient.postJson(loadCodeAssistUrl(), LOAD_CODE_ASSIST_BODY, headers)
                when {
                    response.statusCode == 401 || response.statusCode == 403 ->
                        return ProjectResolution.Auth(response.statusCode)
                    response.statusCode in 200..299 -> response.body
                    else -> null
                }
            } catch (_: Exception) {
                null
            }
            val project = body?.let { decode<CodeAssistResponse>(it) }?.cloudaicompanionProject?.value
                ?.takeIf { it.isNotBlank() }
            if (project != null) return ProjectResolution.Found(project)
        }
        return ProjectResolution.Unavailable
    }

    private suspend fun fetchPerModelQuota(
        project: String?,
        tier: String?,
        headers: Map<String, String>,
    ): Result {
        val models = try {
            val response = httpClient.postJson(fetchAvailableModelsUrl(), projectBody(project), headers)
            when {
                response.statusCode == 401 || response.statusCode == 403 ->
                    return authRequired(response.statusCode)
                response.statusCode in 200..299 -> parseAvailableModels(response.body)
                    ?: return decodeFailure()
                else -> return Result.Failure(
                    QuotaError.Network(diagnosticsDigest = "antigravity_http_${response.statusCode}"),
                )
            }
        } catch (_: Exception) {
            return Result.Failure(QuotaError.Network(diagnosticsDigest = "antigravity_network_error"))
        }
        if (models.isEmpty()) {
            return Result.Success(tier = tier, summary = null, models = models)
        }

        // An all-full result is indistinguishable from the placeholder served to unknown projects,
        // so verify it against the per-model quota endpoint and merge the two views.
        val allFull = models.all { (it.remainingFraction ?: 0.0) >= ALL_FULL_PLACEHOLDER_FRACTION }
        if (!allFull) {
            return Result.Success(tier = tier, summary = null, models = models)
        }

        val buckets = try {
            val response = httpClient.postJson(retrieveQuotaUrl(), projectBody(project), headers)
            when {
                response.statusCode == 401 || response.statusCode == 403 ->
                    return authRequired(response.statusCode)
                response.statusCode in 200..299 -> decode<AntigravityQuotaResponseDto>(response.body)?.buckets
                    ?: return decodeFailure()
                else -> return Result.Failure(
                    QuotaError.Network(diagnosticsDigest = "antigravity_http_${response.statusCode}"),
                )
            }
        } catch (_: Exception) {
            return Result.Failure(QuotaError.Network(diagnosticsDigest = "antigravity_network_error"))
        }
        if (buckets.none { it.remainingFraction != null }) {
            return Result.Success(tier = tier, summary = null, models = emptyList())
        }
        return Result.Success(tier = tier, summary = null, models = mergeModels(models, buckets))
    }

    private fun mergeModels(
        models: List<AntigravityModelQuota>,
        buckets: List<AntigravityQuotaResponseDto.QuotaBucket>,
    ): List<AntigravityModelQuota> {
        val bucketByModel = linkedMapOf<String, AntigravityQuotaResponseDto.QuotaBucket>()
        for (bucket in buckets) {
            val modelId = bucket.modelId ?: continue
            bucketByModel.putIfAbsent(modelId, bucket)
        }
        val merged = models.map { model ->
            val bucket = bucketByModel[model.modelId] ?: return@map model
            model.copy(
                remainingFraction = bucket.remainingFraction ?: model.remainingFraction,
                resetTime = bucket.resetTime ?: model.resetTime,
            )
        }.toMutableList()
        val modelIds = models.mapTo(HashSet()) { it.modelId }
        for ((modelId, bucket) in bucketByModel) {
            if (modelId in modelIds) continue
            val fraction = bucket.remainingFraction ?: continue
            merged += AntigravityModelQuota(
                modelId = modelId,
                label = modelId,
                remainingFraction = fraction,
                resetTime = bucket.resetTime,
            )
        }
        return merged
    }

    /** First default tier with an id, then any tier with an id, then paid/current tier ids. */
    private fun pickOnboardTier(codeAssist: CodeAssistResponse?): String? {
        if (codeAssist == null) return null
        return codeAssist.allowedTiers.firstOrNull { it.isDefault == true && !it.id.isNullOrBlank() }?.id
            ?: codeAssist.allowedTiers.firstOrNull { !it.id.isNullOrBlank() }?.id
            ?: codeAssist.paidTier?.id?.takeIf { it.isNotBlank() }
            ?: codeAssist.currentTier?.id?.takeIf { it.isNotBlank() }
    }

    private fun parseAvailableModels(body: String): List<AntigravityModelQuota>? {
        val parsed = decode<AvailableModelsResponse>(body) ?: return null
        return parsed.models.mapNotNull { (modelId, entry) ->
            val quota = entry.quotaInfo ?: return@mapNotNull null
            AntigravityModelQuota(
                modelId = modelId,
                label = entry.displayName?.takeIf { it.isNotBlank() }
                    ?: entry.label?.takeIf { it.isNotBlank() }
                    ?: modelId,
                remainingFraction = quota.remainingFraction,
                resetTime = quota.resetTime,
            )
        }
    }

    private inline fun <reified T> decode(body: String): T? =
        try {
            json.decodeFromString<T>(body)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

    private fun projectBody(project: String?): String {
        if (project == null) return EMPTY_BODY
        return buildJsonObject { put("project", project) }.toString()
    }

    private fun onboardBody(tierId: String?): String = buildJsonObject {
        tierId?.let { put("tierId", it) }
        put(
            "metadata",
            buildJsonObject {
                put("ideType", "ANTIGRAVITY")
                put("platform", "PLATFORM_UNSPECIFIED")
                put("pluginType", "GEMINI")
            },
        )
    }.toString()

    private fun authRequired(statusCode: Int): Result.Failure =
        Result.Failure(
            QuotaError.AuthRequired(
                httpStatus = statusCode,
                diagnosticsDigest = "antigravity_auth_required_$statusCode",
            ),
        )

    private fun decodeFailure(): Result.Failure =
        Result.Failure(QuotaError.Network(diagnosticsDigest = "antigravity_decode_error"))

    private fun loadCodeAssistUrl(): String = "$baseUrl/v1internal:loadCodeAssist"

    private fun onboardUrl(): String = "$baseUrl/v1internal:onboardUser"

    private fun summaryQuotaUrl(): String = "$summaryBaseUrl/v1internal:retrieveUserQuotaSummary"

    private fun fetchAvailableModelsUrl(): String = "$baseUrl/v1internal:fetchAvailableModels"

    private fun retrieveQuotaUrl(): String = "$baseUrl/v1internal:retrieveUserQuota"

    private sealed interface ProjectResolution {
        data class Found(val project: String) : ProjectResolution
        data object Unavailable : ProjectResolution
        data class Auth(val statusCode: Int) : ProjectResolution
    }

    @kotlinx.serialization.Serializable
    private data class CodeAssistResponse(
        val currentTier: TierInfo? = null,
        val paidTier: TierInfo? = null,
        val allowedTiers: List<TierInfo> = emptyList(),
        val cloudaicompanionProject: ProjectRef? = null,
        val planInfo: PlanInfo? = null,
    ) {
        @kotlinx.serialization.Serializable
        data class TierInfo(val id: String? = null, val name: String? = null, val isDefault: Boolean? = null)

        @kotlinx.serialization.Serializable
        data class ProjectRef(val value: String? = null)

        @kotlinx.serialization.Serializable
        data class PlanInfo(val planType: String? = null)
    }

    @kotlinx.serialization.Serializable
    private data class OnboardResponse(val response: OnboardPayload? = null) {
        @kotlinx.serialization.Serializable
        data class OnboardPayload(val cloudaicompanionProject: CodeAssistResponse.ProjectRef? = null)
    }

    @kotlinx.serialization.Serializable
    private data class AvailableModelsResponse(val models: Map<String, ModelEntry> = emptyMap()) {
        @kotlinx.serialization.Serializable
        data class ModelEntry(
            val displayName: String? = null,
            val label: String? = null,
            val quotaInfo: QuotaInfo? = null,
        ) {
            @kotlinx.serialization.Serializable
            data class QuotaInfo(val remainingFraction: Double? = null, val resetTime: String? = null)
        }
    }

    sealed interface Result {
        data class Success(
            val tier: String?,
            val summary: AntigravityQuotaSummaryDto?,
            val models: List<AntigravityModelQuota>,
        ) : Result

        data class Failure(val error: QuotaError) : Result
    }

    companion object {
        private const val DEFAULT_BASE_URL = "https://cloudcode-pa.googleapis.com"
        private const val DEFAULT_SUMMARY_BASE_URL = "https://daily-cloudcode-pa.googleapis.com"

        /** The quota endpoints require the Hub client family (Cloud Code) user agent. */
        const val USER_AGENT = "antigravity/hub/2.9.1 android/arm64"
        private const val LOAD_CODE_ASSIST_BODY =
            """{"metadata":{"ideType":"ANTIGRAVITY","platform":"PLATFORM_UNSPECIFIED","pluginType":"GEMINI"}}"""
        private const val EMPTY_BODY = "{}"
        private const val ALL_FULL_PLACEHOLDER_FRACTION = 0.999
        private const val PROJECT_POLL_ATTEMPTS = 5
        private const val PROJECT_POLL_DELAY_MS = 2_000L
    }
}
