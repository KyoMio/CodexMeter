package com.kmnexus.codexmeter.providers.codex.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Transient
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

@Serializable(with = CodexUsageResponseDtoSerializer::class)
data class CodexUsageResponseDto(
    @SerialName("plan_type")
    val planType: String? = null,
    @SerialName("rate_limit")
    val rateLimit: CodexRateLimitDto? = null,
    val credits: CodexCreditsDto? = null,
    @Transient
    val creditsDecodeFailed: Boolean = false,
)

@Serializable
data class CodexRateLimitDto(
    @SerialName("primary_window")
    val primaryWindow: CodexWindowDto? = null,
    @SerialName("secondary_window")
    val secondaryWindow: CodexWindowDto? = null,
    @Transient
    val primaryWindowDecodeFailed: Boolean = false,
    @Transient
    val secondaryWindowDecodeFailed: Boolean = false,
)

@Serializable
data class CodexWindowDto(
    @SerialName("used_percent")
    val usedPercent: Int? = null,
    @SerialName("reset_at")
    val resetAt: Long? = null,
    @SerialName("limit_window_seconds")
    val limitWindowSeconds: Int? = null,
)

@Serializable
data class CodexCreditsDto(
    @SerialName("has_credits")
    val hasCredits: Boolean? = null,
    val unlimited: Boolean? = null,
    val balance: Double? = null,
)

object CodexUsageResponseDtoSerializer : kotlinx.serialization.KSerializer<CodexUsageResponseDto> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("CodexUsageResponseDto")

    override fun deserialize(decoder: Decoder): CodexUsageResponseDto {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw SerializationException("CodexUsageResponseDto can be decoded only from JSON.")
        val root = jsonDecoder.decodeJsonElement().jsonObject
        val rateLimitResult = root.rateLimitDecodeResult("rate_limit")
        val creditsResult = root.creditsDecodeResult("credits")

        return CodexUsageResponseDto(
            planType = root.stringOrNull("plan_type"),
            rateLimit = rateLimitResult.rateLimit,
            credits = creditsResult.credits,
            creditsDecodeFailed = creditsResult.decodeFailed,
        )
    }

    override fun serialize(
        encoder: Encoder,
        value: CodexUsageResponseDto,
    ) {
        throw SerializationException("CodexUsageResponseDto serialization is not supported.")
    }
}

private fun JsonObject.rateLimitDecodeResult(key: String): RateLimitDecodeResult {
    val element = this[key] ?: return RateLimitDecodeResult(rateLimit = null)
    if (element is JsonNull) {
        return RateLimitDecodeResult(rateLimit = null)
    }

    val rateLimitObject = element as? JsonObject
        ?: return RateLimitDecodeResult(
            rateLimit = CodexRateLimitDto(
                primaryWindowDecodeFailed = true,
                secondaryWindowDecodeFailed = true,
            ),
        )
    return RateLimitDecodeResult(rateLimit = rateLimitObject.toRateLimitDto())
}

private fun JsonObject.toRateLimitDto(): CodexRateLimitDto =
    windowDecodeResult("primary_window").let { primaryWindowResult ->
        windowDecodeResult("secondary_window").let { secondaryWindowResult ->
            CodexRateLimitDto(
                primaryWindow = primaryWindowResult.window,
                secondaryWindow = secondaryWindowResult.window,
                primaryWindowDecodeFailed = primaryWindowResult.decodeFailed,
                secondaryWindowDecodeFailed = secondaryWindowResult.decodeFailed,
            )
        }
    }

private fun JsonObject.windowDecodeResult(key: String): WindowDecodeResult {
    val element = this[key] ?: return WindowDecodeResult(window = null, decodeFailed = false)
    if (element is JsonNull) {
        return WindowDecodeResult(window = null, decodeFailed = false)
    }

    val windowObject = element as? JsonObject ?: return WindowDecodeResult(window = null, decodeFailed = true)
    return windowObject.toWindowDecodeResult()
}

private fun JsonObject.toWindowDecodeResult(): WindowDecodeResult {
    val usedPercent = intField("used_percent")
    val resetAt = longField("reset_at")
    val limitWindowSeconds = intField("limit_window_seconds")
    if (usedPercent.decodeFailed || resetAt.decodeFailed || limitWindowSeconds.decodeFailed) {
        return WindowDecodeResult(window = null, decodeFailed = true)
    }

    return WindowDecodeResult(
        window = CodexWindowDto(
            usedPercent = usedPercent.value,
            resetAt = resetAt.value,
            limitWindowSeconds = limitWindowSeconds.value,
        ),
        decodeFailed = false,
    )
}

private fun JsonObject.creditsDecodeResult(key: String): CreditsDecodeResult {
    val element = this[key] ?: return CreditsDecodeResult(credits = null, decodeFailed = false)
    if (element is JsonNull) {
        return CreditsDecodeResult(credits = null, decodeFailed = false)
    }

    val creditsObject = element as? JsonObject
        ?: return CreditsDecodeResult(credits = null, decodeFailed = true)
    return creditsObject.toCreditsDecodeResult()
}

private fun JsonObject.toCreditsDecodeResult(): CreditsDecodeResult {
    val hasCredits = booleanField("has_credits")
    val unlimited = booleanField("unlimited")
    val balance = balanceField("balance")

    return CreditsDecodeResult(
        credits = CodexCreditsDto(
            hasCredits = hasCredits.value,
            unlimited = unlimited.value,
            balance = balance.value,
        ),
        decodeFailed = hasCredits.decodeFailed || unlimited.decodeFailed || balance.decodeFailed,
    )
}

private fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)
        ?.takeIf { it.isString }
        ?.contentOrNull

private fun JsonObject.intField(key: String): ScalarDecodeResult<Int> =
    scalarField(key = key, allowString = false) { it.toIntOrNull() }

private fun JsonObject.longField(key: String): ScalarDecodeResult<Long> =
    scalarField(key = key, allowString = false) { it.toLongOrNull() }

private fun JsonObject.booleanField(key: String): ScalarDecodeResult<Boolean> =
    scalarField(key = key, allowString = false) { it.toBooleanStrictOrNull() }

private fun JsonObject.balanceField(key: String): ScalarDecodeResult<Double> =
    scalarField(key = key, allowString = true) { value ->
        value.toDoubleOrNull()?.takeIf { it.isFinite() }
    }

private fun <T> JsonObject.scalarField(
    key: String,
    allowString: Boolean,
    parser: (String) -> T?,
): ScalarDecodeResult<T> {
    val element = this[key] ?: return ScalarDecodeResult(value = null, decodeFailed = false)
    if (element is JsonNull) {
        return ScalarDecodeResult(value = null, decodeFailed = false)
    }

    val primitive = element as? JsonPrimitive ?: return ScalarDecodeResult(value = null, decodeFailed = true)
    if (primitive.isString && !allowString) {
        return ScalarDecodeResult(value = null, decodeFailed = true)
    }
    val value = primitive.contentOrNull?.let(parser) ?: return ScalarDecodeResult(value = null, decodeFailed = true)
    return ScalarDecodeResult(value = value, decodeFailed = false)
}

private val JsonPrimitive.contentOrNull: String?
    get() = content

private data class WindowDecodeResult(
    val window: CodexWindowDto?,
    val decodeFailed: Boolean,
)

private data class RateLimitDecodeResult(
    val rateLimit: CodexRateLimitDto?,
)

private data class CreditsDecodeResult(
    val credits: CodexCreditsDto?,
    val decodeFailed: Boolean,
)

private data class ScalarDecodeResult<T>(
    val value: T?,
    val decodeFailed: Boolean,
)
