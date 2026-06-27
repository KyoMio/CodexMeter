package com.kmnexus.codexmeter.providers.zaibalance.mapper

import com.kmnexus.codexmeter.domain.model.LocalAccountId
import com.kmnexus.codexmeter.domain.quota.QuotaSnapshotSource
import com.kmnexus.codexmeter.domain.quota.QuotaWindowAvailability
import com.kmnexus.codexmeter.domain.quota.QuotaWindowDisplayKind
import com.kmnexus.codexmeter.providers.zaibalance.dto.ZaiBalanceResponseDto
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ZaiBalanceMapperTest {
    private val localAccountId = LocalAccountId("zaibal-1")
    private val fetchedAt = Instant.parse("2026-06-27T00:00:00Z")

    @Test
    fun mapsAvailableBalanceIntoBalanceWindow() {
        val dto = ZaiBalanceResponseDto(code = 200, success = true, availableBalance = 12.5)

        val snapshot = ZaiBalanceMapper.map(
            dto = dto,
            localAccountId = localAccountId,
            providerAccountId = null,
            fetchedAt = fetchedAt,
            source = QuotaSnapshotSource.CookieAuth,
        )

        assertEquals(1, snapshot.windows.size)
        val window = snapshot.windows.first()
        assertEquals(QuotaWindowDisplayKind.Balance, window.displayKind)
        assertEquals("balance", window.windowId.value)
        assertEquals("12.50", window.balanceAmount)
        assertEquals("CNY", window.balanceCurrency)
        assertEquals(QuotaWindowAvailability.Available, window.availability)
        assertNull(window.grantedBalance)
        assertNull(window.toppedUpBalance)
    }

    /** A zero / missing availableBalance must still surface a "¥0.00" balance window (never "No quota yet"). */
    @Test
    fun nullOrZeroBalanceStillProducesZeroWindow() {
        val dto = ZaiBalanceResponseDto(code = 200, success = true, availableBalance = null)

        val snapshot = ZaiBalanceMapper.map(
            dto = dto,
            localAccountId = localAccountId,
            providerAccountId = null,
            fetchedAt = fetchedAt,
            source = QuotaSnapshotSource.CookieAuth,
        )

        val window = snapshot.windows.single()
        assertEquals(QuotaWindowDisplayKind.Balance, window.displayKind)
        assertEquals("0.00", window.balanceAmount)
        assertEquals("CNY", window.balanceCurrency)
        assertEquals(QuotaWindowAvailability.Available, window.availability)
    }
}
