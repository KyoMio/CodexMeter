package com.kmnexus.codexmeter.refresh

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.time.Duration
import java.util.concurrent.TimeUnit

data class RefreshWorkPlan(
    val uniqueWorkName: String,
    val repeatInterval: Duration,
    val requiresConnectedNetwork: Boolean,
)

interface RefreshWorkEnqueuer {
    fun enqueue(plan: RefreshWorkPlan)

    fun cancel(uniqueWorkName: String)
}

class RefreshWorkScheduler(
    private val enqueuer: RefreshWorkEnqueuer,
) {
    fun schedulePeriodicRefresh(): RefreshWorkPlan {
        val plan = periodicRefreshPlan()
        enqueuer.enqueue(plan)
        return plan
    }

    /**
     * Reschedule the periodic refresh to [intervalMinutes], or cancel it entirely when the user picks
     * "Manual" (<= 0). WorkManager floors periodic work at 15 minutes, so shorter values are clamped.
     */
    fun applyIntervalMinutes(intervalMinutes: Int) {
        if (intervalMinutes <= 0) {
            enqueuer.cancel(UNIQUE_PERIODIC_WORK_NAME)
        } else {
            enqueuer.enqueue(planForMinutes(intervalMinutes))
        }
    }

    companion object {
        const val UNIQUE_PERIODIC_WORK_NAME = "quota_periodic_refresh"
        val PERIODIC_REFRESH_INTERVAL: Duration = Duration.ofMinutes(15)

        fun periodicRefreshPlan(): RefreshWorkPlan =
            RefreshWorkPlan(
                uniqueWorkName = UNIQUE_PERIODIC_WORK_NAME,
                repeatInterval = PERIODIC_REFRESH_INTERVAL,
                requiresConnectedNetwork = true,
            )

        fun planForMinutes(intervalMinutes: Int): RefreshWorkPlan =
            RefreshWorkPlan(
                uniqueWorkName = UNIQUE_PERIODIC_WORK_NAME,
                repeatInterval = Duration.ofMinutes(intervalMinutes.toLong()).coerceAtLeast(PERIODIC_REFRESH_INTERVAL),
                requiresConnectedNetwork = true,
            )

        fun from(context: Context): RefreshWorkScheduler =
            RefreshWorkScheduler(
                WorkManagerRefreshWorkEnqueuer(
                    workManager = WorkManager.getInstance(context),
                ),
            )
    }
}

private class WorkManagerRefreshWorkEnqueuer(
    private val workManager: WorkManager,
) : RefreshWorkEnqueuer {
    override fun enqueue(plan: RefreshWorkPlan) {
        val request = PeriodicWorkRequestBuilder<QuotaRefreshWorker>(
            plan.repeatInterval.toMillis(),
            TimeUnit.MILLISECONDS,
        ).setConstraints(
            Constraints.Builder()
                .setRequiredNetworkType(
                    if (plan.requiresConnectedNetwork) {
                        NetworkType.CONNECTED
                    } else {
                        NetworkType.NOT_REQUIRED
                    },
                )
                .build(),
        ).build()

        workManager.enqueueUniquePeriodicWork(
            plan.uniqueWorkName,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    override fun cancel(uniqueWorkName: String) {
        workManager.cancelUniqueWork(uniqueWorkName)
    }
}
