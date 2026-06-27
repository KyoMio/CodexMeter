package com.kmnexus.codexmeter.update

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class UpdateCheckWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): Result =
        when (UpdateCheckOutcome.run(applicationContext as? UpdateCheckDependenciesProvider)) {
            UpdateCheckOutcome.Success -> Result.success()
            UpdateCheckOutcome.Retry -> Result.retry()
        }
}
