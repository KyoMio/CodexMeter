package com.kmnexus.codexmeter.update

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.time.Duration
import java.util.concurrent.TimeUnit

data class UpdateCheckWorkPlan(
    val uniqueWorkName: String,
    val repeatInterval: Duration,
)

interface UpdateCheckWorkEnqueuer {
    fun enqueue(plan: UpdateCheckWorkPlan)
    fun cancel(uniqueWorkName: String)
}

class UpdateCheckWorkScheduler(
    private val enqueuer: UpdateCheckWorkEnqueuer,
) {
    fun setAutoCheckEnabled(enabled: Boolean) {
        if (enabled) enqueuer.enqueue(dailyPlan()) else enqueuer.cancel(UNIQUE_WORK_NAME)
    }

    companion object {
        const val UNIQUE_WORK_NAME = "app_update_check"
        val DAILY_INTERVAL: Duration = Duration.ofHours(24)

        fun dailyPlan(): UpdateCheckWorkPlan =
            UpdateCheckWorkPlan(uniqueWorkName = UNIQUE_WORK_NAME, repeatInterval = DAILY_INTERVAL)

        fun from(context: Context): UpdateCheckWorkScheduler =
            UpdateCheckWorkScheduler(
                WorkManagerUpdateCheckWorkEnqueuer(WorkManager.getInstance(context)),
            )
    }
}

private class WorkManagerUpdateCheckWorkEnqueuer(
    private val workManager: WorkManager,
) : UpdateCheckWorkEnqueuer {
    override fun enqueue(plan: UpdateCheckWorkPlan) {
        val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(
            plan.repeatInterval.toMillis(),
            TimeUnit.MILLISECONDS,
        ).setConstraints(
            // The daily update check always requires a connected network by design; this constraint is
            // intentionally fixed and not configurable (unlike RefreshWorkPlan.requiresConnectedNetwork).
            Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
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
