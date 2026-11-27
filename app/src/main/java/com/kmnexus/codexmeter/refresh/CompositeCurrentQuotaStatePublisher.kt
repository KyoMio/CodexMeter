package com.kmnexus.codexmeter.refresh

import com.kmnexus.codexmeter.domain.quota.CurrentQuotaState
import kotlinx.coroutines.CancellationException

class CompositeCurrentQuotaStatePublisher(
    private val publishers: List<CurrentQuotaStatePublisher>,
) : CurrentQuotaStatePublisher, PerAccountQuotaEffectPublisher {
    internal val members: List<CurrentQuotaStatePublisher>
        get() = publishers

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

    /**
     * Fan-in entry point: the coordinator is wired with this composite as BOTH the guarded
     * current-state publisher and the unguarded per-account effects publisher. Effects are
     * forwarded only to members that opted into the per-account channel, so a member can keep
     * pure current-state semantics without receiving background-account traffic.
     */
    override suspend fun publishAccountEffects(state: CurrentQuotaState) {
        publishers.forEach { publisher ->
            if (publisher !is PerAccountQuotaEffectPublisher) return@forEach
            try {
                publisher.publishAccountEffects(state)
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                // Presentation surfaces must not turn a successful refresh into a refresh failure.
            }
        }
    }
}
