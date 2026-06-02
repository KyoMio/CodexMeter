package com.kmnexus.codexmeter.app

import com.kmnexus.codexmeter.data.repository.RetentionCleanupResult
import com.kmnexus.codexmeter.data.repository.RetentionCleanup
import com.kmnexus.codexmeter.domain.settings.RetentionPreference
import com.kmnexus.codexmeter.domain.settings.RetentionPreferenceReader
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class StartupMaintenanceTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `startup maintenance runs default retention cleanup`() = runTest {
        val cleanup = RecordingStartupRetentionCleanup()
        val maintenance = StartupMaintenance(
            retentionPreferenceReader = StaticRetentionPreferenceReader(RetentionPreference.SevenDays),
            retentionCleanup = cleanup,
            clock = Clock.fixed(NOW, ZoneOffset.UTC),
        )

        maintenance.start(this)
        runCurrent()

        assertEquals(listOf(RetentionCleanupRequest(RetentionPreference.SevenDays, NOW)), cleanup.requests)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `startup maintenance swallows cleanup failure`() = runTest {
        val cleanup = ThrowingRetentionCleanup()
        val reporter = RecordingStartupMaintenanceReporter()
        val maintenance = StartupMaintenance(
            retentionPreferenceReader = StaticRetentionPreferenceReader(RetentionPreference.Forever),
            retentionCleanup = cleanup,
            reporter = reporter,
            clock = Clock.fixed(NOW, ZoneOffset.UTC),
        )

        maintenance.start(this)
        runCurrent()

        assertEquals(listOf(RetentionPreference.Forever), cleanup.requests)
        assertEquals(listOf(StartupMaintenanceEvent.RetentionCleanupFailed), reporter.events)
    }

    private class StaticRetentionPreferenceReader(
        private val preference: RetentionPreference,
    ) : RetentionPreferenceReader {
        override suspend fun retentionPreference(): RetentionPreference = preference
    }

    private class RecordingStartupRetentionCleanup : RetentionCleanup {
        val requests = mutableListOf<RetentionCleanupRequest>()

        override suspend fun cleanup(
            preference: RetentionPreference,
            now: Instant,
        ): RetentionCleanupResult {
            requests += RetentionCleanupRequest(preference = preference, now = now)
            return RetentionCleanupResult(deletedQuotaSnapshots = 0, deletedRefreshAttempts = 0)
        }
    }

    private class ThrowingRetentionCleanup : RetentionCleanup {
        val requests = mutableListOf<RetentionPreference>()

        override suspend fun cleanup(
            preference: RetentionPreference,
            now: Instant,
        ): RetentionCleanupResult {
            requests += preference
            throw IllegalStateException("cleanup failed")
        }
    }

    private class RecordingStartupMaintenanceReporter : StartupMaintenanceReporter {
        val events = mutableListOf<StartupMaintenanceEvent>()

        override fun report(event: StartupMaintenanceEvent) {
            events += event
        }
    }

    private data class RetentionCleanupRequest(
        val preference: RetentionPreference,
        val now: Instant,
    )

    private companion object {
        val NOW: Instant = Instant.parse("2026-05-24T12:00:00Z")
    }
}
