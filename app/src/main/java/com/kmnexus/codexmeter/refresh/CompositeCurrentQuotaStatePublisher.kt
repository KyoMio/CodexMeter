package com.kmnexus.codexmeter.refresh

import com.kmnexus.codexmeter.domain.quota.CurrentQuotaState
import kotlinx.coroutines.CancellationException

class CompositeCurrentQuotaStatePublisher(
    private val publishers: List<CurrentQuotaStatePublisher>,
) : CurrentQuotaStatePublisher {
    override suspend fun publish(state: CurrentQuotaState) {
        publishers.forEach { publisher ->
            try {
                publisher.publish(state)
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                // Presentation surfaces must not turn a successful refresh into a refresh failure.
            }
        }
    }
}
