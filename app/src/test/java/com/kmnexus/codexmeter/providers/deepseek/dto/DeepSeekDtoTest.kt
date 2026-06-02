package com.kmnexus.codexmeter.providers.deepseek.dto

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepSeekDtoTest {
    @Test
    fun deserializeBalanceResponse() {
        val json = """{"is_available":true,"balance_infos":[{"currency":"CNY","total_balance":"9.49","granted_balance":"0.00","topped_up_balance":"9.49"}]}"""
        val dto = Json { ignoreUnknownKeys = true }.decodeFromString<DeepSeekBalanceResponseDto>(json)
        assertTrue(dto.is_available)
        assertEquals("9.49", dto.balance_infos[0].total_balance)
    }
}
