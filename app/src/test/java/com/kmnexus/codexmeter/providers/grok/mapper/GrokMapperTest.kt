package com.kmnexus.codexmeter.providers.grok.mapper

import com.kmnexus.codexmeter.domain.model.LocalAccountId
import com.kmnexus.codexmeter.domain.model.ProviderId
import com.kmnexus.codexmeter.domain.quota.QuotaSnapshotSource
import com.kmnexus.codexmeter.domain.quota.QuotaWindowAvailability
import com.kmnexus.codexmeter.domain.quota.QuotaWindowDisplayKind
import com.kmnexus.codexmeter.providers.grok.dto.GrokBillingPeriodDto
import com.kmnexus.codexmeter.providers.grok.dto.GrokBillingResponseDto
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GrokMapperTest {
    private val localAccountId = LocalAccountId("grok-1")
    private val fetchedAt = Instant.parse("2026-09-10T00:00:00Z")

    /** Golden sample from docs/research/2026-09-10-grok-provider-login-research.md section 2.3. */
    @Test
    fun `maps golden weekly sample into primary percent window`() {
        val snapshot = GrokMapper.map(
            dto = goldenSampleDto(),
            localAccountId = localAccountId,
            providerAccountId = null,
            fetchedAt = fetchedAt,
            source = QuotaSnapshotSource.DeviceCodeLogin,
        )

        assertEquals(ProviderId("grok"), snapshot.providerId)
        val window = snapshot.windows.single()
        assertEquals("weekly", window.windowId.value)
        assertEquals(1, window.usedPercent)
        assertEquals(Instant.parse("2026-08-24T17:33:48.278812Z"), window.resetAt)
        assertTrue(window.isPrimaryCandidate)
        assertEquals(QuotaWindowDisplayKind.Percent, window.displayKind)
        assertEquals(QuotaWindowAvailability.Available, window.availability)
        assertTrue(snapshot.snapshotId.value.startsWith("grok:grok-1:"))
    }

    @Test
    fun `golden sample maps prepaid balance into snapshot credits`() {
        val snapshot = GrokMapper.map(
            dto = goldenSampleDto().copy(prepaidBalance = 12.5),
            localAccountId = localAccountId,
            providerAccountId = null,
            fetchedAt = fetchedAt,
            source = QuotaSnapshotSource.DeviceCodeLogin,
        )

        val credits = snapshot.credits
        assertEquals(true, credits?.hasCredits)
        assertEquals(false, credits?.unlimited)
        assertEquals(12.5, credits?.balance!!, 0.0001)
    }

    /** Non-WEEKLY period types exist in the wild; they must still map (label follows the period type). */
    @Test
    fun `monthly period type still maps without crashing`() {
        val snapshot = GrokMapper.map(
            dto = goldenSampleDto().copy(
                currentPeriod = goldenPeriod(type = "USAGE_PERIOD_TYPE_MONTHLY"),
                creditUsagePercent = 42.5,
            ),
            localAccountId = localAccountId,
            providerAccountId = null,
            fetchedAt = fetchedAt,
            source = QuotaSnapshotSource.DeviceCodeLogin,
        )

        val window = snapshot.windows.single()
        assertEquals("monthly", window.windowId.value)
        assertEquals(42, window.usedPercent)
        assertEquals(Instant.parse("2026-08-24T17:33:48.278812Z"), window.resetAt)
        assertEquals(QuotaWindowAvailability.Available, window.availability)
    }

    /** Missing fields must degrade to "unavailable", never an estimate and never a throw. */
    @Test
    fun `empty projection maps to unavailable window without throwing`() {
        val snapshot = GrokMapper.map(
            dto = GrokBillingResponseDto(),
            localAccountId = localAccountId,
            providerAccountId = null,
            fetchedAt = fetchedAt,
            source = QuotaSnapshotSource.DeviceCodeLogin,
        )

        val window = snapshot.windows.single()
        assertEquals(QuotaWindowAvailability.Missing, window.availability)
        assertNull(window.usedPercent)
        assertNull(window.resetAt)
        assertNull(snapshot.credits)
    }

    @Test
    fun `missing period end keeps window unavailable`() {
        val snapshot = GrokMapper.map(
            dto = goldenSampleDto().copy(
                currentPeriod = goldenPeriod(type = "USAGE_PERIOD_TYPE_WEEKLY", end = null),
            ),
            localAccountId = localAccountId,
            providerAccountId = null,
            fetchedAt = fetchedAt,
            source = QuotaSnapshotSource.DeviceCodeLogin,
        )

        val window = snapshot.windows.single()
        assertEquals(QuotaWindowAvailability.Missing, window.availability)
        assertNull(window.usedPercent)
        assertNull(window.resetAt)
    }

    @Test
    fun `unparseable period end maps to decode failed`() {
        val snapshot = GrokMapper.map(
            dto = goldenSampleDto().copy(
                currentPeriod = goldenPeriod(type = "USAGE_PERIOD_TYPE_WEEKLY", end = "not-a-timestamp"),
            ),
            localAccountId = localAccountId,
            providerAccountId = null,
            fetchedAt = fetchedAt,
            source = QuotaSnapshotSource.DeviceCodeLogin,
        )

        val window = snapshot.windows.single()
        assertEquals(QuotaWindowAvailability.DecodeFailed, window.availability)
        assertNull(window.usedPercent)
        assertNull(window.resetAt)
    }

    @Test
    fun `out-of-range usage percent is clamped`() {
        val snapshot = GrokMapper.map(
            dto = goldenSampleDto().copy(creditUsagePercent = 250.0),
            localAccountId = localAccountId,
            providerAccountId = null,
            fetchedAt = fetchedAt,
            source = QuotaSnapshotSource.DeviceCodeLogin,
        )

        assertEquals(100, snapshot.windows.single().usedPercent)
    }

    private fun goldenSampleDto(): GrokBillingResponseDto =
        GrokBillingResponseDto(
            currentPeriod = goldenPeriod(type = "USAGE_PERIOD_TYPE_WEEKLY"),
            creditUsagePercent = 1.0,
            prepaidBalance = 0.0,
            billingPeriodStart = "2026-08-17T17:33:48.278812+00:00",
            billingPeriodEnd = "2026-08-24T17:33:48.278812+00:00",
        )

    private fun goldenPeriod(
        type: String,
        end: String? = "2026-08-24T17:33:48.278812+00:00",
    ): GrokBillingPeriodDto =
        GrokBillingPeriodDto(
            type = type,
            start = "2026-08-17T17:33:48.278812+00:00",
            end = end,
        )
}
