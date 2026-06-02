package com.kmnexus.codexmeter.data.repository

import com.kmnexus.codexmeter.domain.settings.RetentionPreference
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RetentionPolicyTest {
    @Test
    fun `seven day retention deletes history before cutoff only`() {
        val result = RetentionPolicy.forPreference(RetentionPreference.SevenDays).evaluate(NOW)

        assertEquals(Instant.parse("2026-05-17T12:00:00Z"), result.deleteHistoryBefore)
        assertTrue(result.deleteQuotaSnapshots)
        assertTrue(result.deleteRefreshAttempts)
        assertFalse(result.deleteSessions)
    }

    @Test
    fun `thirty day retention deletes history before cutoff only`() {
        val result = RetentionPolicy.forPreference(RetentionPreference.ThirtyDays).evaluate(NOW)

        assertEquals(Instant.parse("2026-04-24T12:00:00Z"), result.deleteHistoryBefore)
        assertTrue(result.deleteQuotaSnapshots)
        assertTrue(result.deleteRefreshAttempts)
        assertFalse(result.deleteSessions)
    }

    @Test
    fun `ninety day retention deletes history before cutoff only`() {
        val result = RetentionPolicy.forPreference(RetentionPreference.NinetyDays).evaluate(NOW)

        assertEquals(Instant.parse("2026-02-23T12:00:00Z"), result.deleteHistoryBefore)
        assertTrue(result.deleteQuotaSnapshots)
        assertTrue(result.deleteRefreshAttempts)
        assertFalse(result.deleteSessions)
    }

    @Test
    fun `forever retention does not delete history or sessions`() {
        val result = RetentionPolicy.forPreference(RetentionPreference.Forever).evaluate(NOW)

        assertNull(result.deleteHistoryBefore)
        assertFalse(result.deleteQuotaSnapshots)
        assertFalse(result.deleteRefreshAttempts)
        assertFalse(result.deleteSessions)
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-05-24T12:00:00Z")
    }
}
