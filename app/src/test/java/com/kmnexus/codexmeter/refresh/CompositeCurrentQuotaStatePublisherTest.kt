package com.kmnexus.codexmeter.refresh

import com.kmnexus.codexmeter.domain.quota.CurrentQuotaFreshness
import com.kmnexus.codexmeter.domain.quota.CurrentQuotaState
import com.kmnexus.codexmeter.domain.quota.CurrentQuotaStatus
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class CompositeCurrentQuotaStatePublisherTest {
    @Test
    fun `publisher failure does not stop later publishers`() = runTest {
        val first = ThrowingPublisher()
        val second = RecordingPublisher()
        val composite = CompositeCurrentQuotaStatePublisher(listOf(first, second))
        val state = state()

        composite.publish(state)

        assertEquals(listOf(state), second.publishedStates)
    }

    private fun state(): CurrentQuotaState =
        CurrentQuotaState(
            status = CurrentQuotaStatus.NoData,
            freshness = CurrentQuotaFreshness.Unknown,
            account = null,
            snapshot = null,
            latestAttempt = null,
            primaryWindow = null,
            secondaryWindows = emptyList(),
            primaryWindowCanAlert = false,
            error = null,
        )

    private class ThrowingPublisher : CurrentQuotaStatePublisher {
        override suspend fun publish(state: CurrentQuotaState) {
            throw IllegalStateException("presentation failed")
        }
    }

    private class RecordingPublisher : CurrentQuotaStatePublisher {
        val publishedStates = mutableListOf<CurrentQuotaState>()

        override suspend fun publish(state: CurrentQuotaState) {
            publishedStates += state
        }
    }
}
