package com.kmnexus.codexmeter.providers.codex.dto

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexUsageResponseDtoTest {
    private val json = Json {
        ignoreUnknownKeys = true
    }

    @Test
    fun `decodes primary and secondary windows`() {
        val dto = decode(
            """
            {
              "plan_type":"plus",
              "rate_limit":{
                "primary_window":{"used_percent":62,"reset_at":1770000000,"limit_window_seconds":18000},
                "secondary_window":{"used_percent":41,"reset_at":1770500000,"limit_window_seconds":604800}
              },
              "credits":{"has_credits":true,"unlimited":false,"balance":"12.5"}
            }
            """.trimIndent(),
        )

        assertEquals("plus", dto.planType)
        assertEquals(62, dto.rateLimit?.primaryWindow?.usedPercent)
        assertEquals(1770000000L, dto.rateLimit?.primaryWindow?.resetAt)
        assertEquals(18000, dto.rateLimit?.primaryWindow?.limitWindowSeconds)
        assertEquals(41, dto.rateLimit?.secondaryWindow?.usedPercent)
        assertEquals(1770500000L, dto.rateLimit?.secondaryWindow?.resetAt)
        assertEquals(604800, dto.rateLimit?.secondaryWindow?.limitWindowSeconds)
        assertTrue(dto.credits?.hasCredits == true)
        assertTrue(dto.credits?.unlimited == false)
        assertEquals(12.5, dto.credits?.balance ?: 0.0, 0.0)
    }

    @Test
    fun `decodes credits balance from numeric string and json number`() {
        val stringBalanceDto = decode("""{"credits":{"balance":"12.5"}}""")
        val numberBalanceDto = decode("""{"credits":{"balance":7.25}}""")

        assertEquals(12.5, stringBalanceDto.credits?.balance ?: 0.0, 0.0)
        assertEquals(7.25, numberBalanceDto.credits?.balance ?: 0.0, 0.0)
    }

    @Test
    fun `malformed or non finite credits balance decodes to null`() {
        val nanDto = decode("""{"credits":{"balance":"NaN"}}""")
        val infinityDto = decode("""{"credits":{"balance":"Infinity"}}""")
        val overflowDto = decode("""{"credits":{"balance":"1e309"}}""")
        val junkDto = decode("""{"credits":{"balance":"definitely-not-a-number"}}""")

        assertNull(nanDto.credits?.balance)
        assertNull(infinityDto.credits?.balance)
        assertNull(overflowDto.credits?.balance)
        assertNull(junkDto.credits?.balance)
        assertTrue(nanDto.creditsDecodeFailed)
        assertTrue(infinityDto.creditsDecodeFailed)
        assertTrue(overflowDto.creditsDecodeFailed)
        assertTrue(junkDto.creditsDecodeFailed)
    }

    @Test
    fun `missing fields decode to null`() {
        val dto = decode(
            """
            {
              "plan_type":"pro",
              "rate_limit":{
                "primary_window":{"used_percent":18,"limit_window_seconds":18000}
              }
            }
            """.trimIndent(),
        )

        assertEquals("pro", dto.planType)
        assertNotNull(dto.rateLimit)
        assertEquals(18, dto.rateLimit?.primaryWindow?.usedPercent)
        assertNull(dto.rateLimit?.primaryWindow?.resetAt)
        assertEquals(18000, dto.rateLimit?.primaryWindow?.limitWindowSeconds)
        assertFalse(dto.rateLimit?.primaryWindowDecodeFailed ?: true)
        assertNull(dto.rateLimit?.secondaryWindow)
        assertFalse(dto.rateLimit?.secondaryWindowDecodeFailed ?: true)
        assertNull(dto.credits)
    }

    @Test
    fun `ignores unknown fields`() {
        val dto = decode(
            """
            {
              "plan_type":"enterprise",
              "unexpected_root":"ignored",
              "rate_limit":{
                "primary_window":{
                  "used_percent":80,
                  "reset_at":1770000000,
                  "limit_window_seconds":18000,
                  "unexpected_nested":"ignored"
                }
              },
              "credits":{
                "has_credits":false,
                "extra":{"nested":"ignored"}
              }
            }
            """.trimIndent(),
        )

        assertEquals("enterprise", dto.planType)
        assertEquals(80, dto.rateLimit?.primaryWindow?.usedPercent)
        assertFalse(dto.credits?.hasCredits ?: true)
    }

    @Test
    fun `malformed primary window does not prevent secondary window decode`() {
        val dto = decode(
            """
            {
              "rate_limit":{
                "primary_window":"unexpected",
                "secondary_window":{
                  "used_percent":41,
                  "reset_at":1770500000,
                  "limit_window_seconds":604800
                }
              }
            }
            """.trimIndent(),
        )

        assertNotNull(dto.rateLimit)
        assertNull(dto.rateLimit?.primaryWindow)
        assertTrue(dto.rateLimit?.primaryWindowDecodeFailed == true)
        assertEquals(41, dto.rateLimit?.secondaryWindow?.usedPercent)
        assertEquals(1770500000L, dto.rateLimit?.secondaryWindow?.resetAt)
        assertEquals(604800, dto.rateLimit?.secondaryWindow?.limitWindowSeconds)
        assertFalse(dto.rateLimit?.secondaryWindowDecodeFailed ?: true)
    }

    @Test
    fun `malformed rate limit container marks both windows decode failed`() {
        val dto = decode("""{"rate_limit":"unexpected"}""")

        assertNotNull(dto.rateLimit)
        assertNull(dto.rateLimit?.primaryWindow)
        assertTrue(dto.rateLimit?.primaryWindowDecodeFailed == true)
        assertNull(dto.rateLimit?.secondaryWindow)
        assertTrue(dto.rateLimit?.secondaryWindowDecodeFailed == true)
    }

    @Test
    fun `malformed primary window scalar marks decode failure while secondary still decodes`() {
        val dto = decode(
            """
            {
              "rate_limit":{
                "primary_window":{
                  "used_percent":"bad",
                  "reset_at":1770000000,
                  "limit_window_seconds":18000
                },
                "secondary_window":{
                  "used_percent":41,
                  "reset_at":1770500000,
                  "limit_window_seconds":604800
                }
              }
            }
            """.trimIndent(),
        )

        assertNotNull(dto.rateLimit)
        assertNull(dto.rateLimit?.primaryWindow)
        assertTrue(dto.rateLimit?.primaryWindowDecodeFailed == true)
        assertEquals(41, dto.rateLimit?.secondaryWindow?.usedPercent)
        assertEquals(1770500000L, dto.rateLimit?.secondaryWindow?.resetAt)
        assertEquals(604800, dto.rateLimit?.secondaryWindow?.limitWindowSeconds)
        assertFalse(dto.rateLimit?.secondaryWindowDecodeFailed ?: true)
    }

    @Test
    fun `quoted window numbers mark decode failure`() {
        val usedPercentDto = decode(
            """
            {
              "rate_limit":{
                "primary_window":{"used_percent":"62","reset_at":1770000000,"limit_window_seconds":18000}
              }
            }
            """.trimIndent(),
        )
        val resetAtDto = decode(
            """
            {
              "rate_limit":{
                "primary_window":{"used_percent":62,"reset_at":"1770000000","limit_window_seconds":18000}
              }
            }
            """.trimIndent(),
        )
        val limitWindowDto = decode(
            """
            {
              "rate_limit":{
                "primary_window":{"used_percent":62,"reset_at":1770000000,"limit_window_seconds":"18000"}
              }
            }
            """.trimIndent(),
        )

        assertNull(usedPercentDto.rateLimit?.primaryWindow)
        assertTrue(usedPercentDto.rateLimit?.primaryWindowDecodeFailed == true)
        assertNull(resetAtDto.rateLimit?.primaryWindow)
        assertTrue(resetAtDto.rateLimit?.primaryWindowDecodeFailed == true)
        assertNull(limitWindowDto.rateLimit?.primaryWindow)
        assertTrue(limitWindowDto.rateLimit?.primaryWindowDecodeFailed == true)
    }

    @Test
    fun `malformed credits container marks decode failure`() {
        val dto = decode("""{"credits":"unexpected"}""")

        assertNull(dto.credits)
        assertTrue(dto.creditsDecodeFailed)
    }

    @Test
    fun `malformed credits boolean marks decode failure while preserving valid balance`() {
        val dto = decode(
            """
            {
              "credits":{"has_credits":"yes","unlimited":false,"balance":"12.5"}
            }
            """.trimIndent(),
        )

        assertNotNull(dto.credits)
        assertNull(dto.credits?.hasCredits)
        assertFalse(dto.credits?.unlimited ?: true)
        assertEquals(12.5, dto.credits?.balance ?: 0.0, 0.0)
        assertTrue(dto.creditsDecodeFailed)
    }

    @Test
    fun `quoted credits booleans mark decode failure while preserving numeric balance`() {
        val hasCreditsDto = decode("""{"credits":{"has_credits":"false","unlimited":false,"balance":12.5}}""")
        val unlimitedDto = decode("""{"credits":{"has_credits":true,"unlimited":"false","balance":12.5}}""")

        assertNull(hasCreditsDto.credits?.hasCredits)
        assertFalse(hasCreditsDto.credits?.unlimited ?: true)
        assertEquals(12.5, hasCreditsDto.credits?.balance ?: 0.0, 0.0)
        assertTrue(hasCreditsDto.creditsDecodeFailed)
        assertTrue(unlimitedDto.credits?.hasCredits == true)
        assertNull(unlimitedDto.credits?.unlimited)
        assertEquals(12.5, unlimitedDto.credits?.balance ?: 0.0, 0.0)
        assertTrue(unlimitedDto.creditsDecodeFailed)
    }

    private fun decode(jsonString: String): CodexUsageResponseDto =
        json.decodeFromString(jsonString)
}
