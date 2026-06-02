package com.kmnexus.codexmeter.providers.deepseek.mapper

import com.kmnexus.codexmeter.domain.model.LocalAccountId
import com.kmnexus.codexmeter.domain.quota.QuotaSnapshotSource
import com.kmnexus.codexmeter.domain.quota.QuotaWindowAvailability
import com.kmnexus.codexmeter.domain.quota.QuotaWindowDisplayKind
import com.kmnexus.codexmeter.providers.deepseek.dto.DeepSeekBalanceResponseDto
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeepSeekBalanceMapperTest {
    private val localAccountId = LocalAccountId("deepseek-1")
    private val fetchedAt = Instant.parse("2026-05-31T00:00:00Z")

    @Test
    fun mapsBalanceInfoIntoBalanceWindow() {
        val dto = DeepSeekBalanceResponseDto(
            is_available = true,
            balance_infos = listOf(
                DeepSeekBalanceResponseDto.BalanceInfo(
                    currency = "CNY",
                    total_balance = "9.49",
                    granted_balance = "0.00",
                    topped_up_balance = "9.49",
                ),
            ),
        )

        val snapshot = DeepSeekBalanceMapper.map(
            dto = dto,
            localAccountId = localAccountId,
            providerAccountId = null,
            fetchedAt = fetchedAt,
            source = QuotaSnapshotSource.ApiKeyImport,
        )

        assertEquals(1, snapshot.windows.size)
        val window = snapshot.windows.first()
        assertEquals(QuotaWindowDisplayKind.Balance, window.displayKind)
        assertEquals("9.49", window.balanceAmount)
        assertEquals("CNY", window.balanceCurrency)
        assertEquals(QuotaWindowAvailability.Available, window.availability)
        assertNull(window.subLabel)
        assertEquals("0.00", window.grantedBalance)
        assertEquals("9.49", window.toppedUpBalance)
    }

    /**
     * A successful fetch with an empty balance_infos (e.g. a fresh/zero-balance account) must still
     * surface a balance window. Otherwise the account imports but Home reads as "No quota yet".
     */
    @Test
    fun emptyBalanceInfosStillProducesBalanceWindow() {
        val dto = DeepSeekBalanceResponseDto(is_available = true, balance_infos = emptyList())

        val snapshot = DeepSeekBalanceMapper.map(
            dto = dto,
            localAccountId = localAccountId,
            providerAccountId = null,
            fetchedAt = fetchedAt,
            source = QuotaSnapshotSource.ApiKeyImport,
        )

        assertEquals(1, snapshot.windows.size)
        val window = snapshot.windows.first()
        assertEquals(QuotaWindowDisplayKind.Balance, window.displayKind)
        assertEquals("0", window.balanceAmount)
        assertNull(window.balanceCurrency)
        assertEquals(QuotaWindowAvailability.Available, window.availability)
        assertNull(window.subLabel)
        assertNull(window.grantedBalance)
        assertNull(window.toppedUpBalance)
    }
}
