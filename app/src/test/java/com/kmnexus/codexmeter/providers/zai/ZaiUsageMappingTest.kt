package com.kmnexus.codexmeter.providers.zai

import com.kmnexus.codexmeter.domain.model.LocalAccountId
import com.kmnexus.codexmeter.domain.quota.QuotaSnapshotSource
import com.kmnexus.codexmeter.providers.zai.dto.ZaiQuotaResponseDto
import com.kmnexus.codexmeter.providers.zai.mapper.ZaiQuotaMapper
import java.time.Instant
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ZaiUsageMappingTest {
    private val json = Json { ignoreUnknownKeys = true }

    /** Real z.ai responses use code 200 and omit quota fields on some windows; must decode + map. */
    @Test
    fun decodesCode200ResponseAndMapsUsedPercent() {
        val body = """
            {
              "code": 200,
              "success": true,
              "msg": "ok",
              "data": {
                "limits": [
                  {"type":"TOKENS_LIMIT","unit":3,"number":5,"usage":1000,"currentValue":620,"remaining":380,"percentage":62,"nextResetTime":1772000000000},
                  {"type":"TOKENS_LIMIT","unit":6,"number":1,"percentage":40}
                ]
              }
            }
        """.trimIndent()

        val dto = json.decodeFromString<ZaiQuotaResponseDto>(body)
        assertTrue(dto.isSuccess)

        val snapshot = ZaiQuotaMapper.map(
            dto = dto,
            localAccountId = LocalAccountId("zai-1"),
            providerAccountId = null,
            fetchedAt = Instant.parse("2026-06-01T00:00:00Z"),
            source = QuotaSnapshotSource.ApiKeyImport,
        )

        // unit 3 → used 620/1000 = 62%; unit 6 → falls back to percentage 40%.
        assertEquals(62, snapshot.windows.first { it.windowId.value == "zai_5h_window" }.usedPercent)
        assertEquals(40, snapshot.windows.first { it.windowId.value == "zai_weekly_window" }.usedPercent)
    }
}
