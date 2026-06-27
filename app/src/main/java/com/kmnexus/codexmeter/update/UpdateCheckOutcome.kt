package com.kmnexus.codexmeter.update

import com.kmnexus.codexmeter.domain.update.AppUpdateCheckResult

enum class UpdateCheckOutcome {
    Success,
    Retry;

    companion object {
        suspend fun run(provider: UpdateCheckDependenciesProvider?): UpdateCheckOutcome {
            val deps = provider ?: return Success
            val prefs = deps.updatePreferenceStore.preferences()
            if (!prefs.autoCheckEnabled) return Success

            val result = runCatching {
                deps.appUpdateCheck.checkForUpdate(deps.currentVersionName)
            }.getOrElse { return Retry }

            return when (result) {
                is AppUpdateCheckResult.UpdateAvailable -> {
                    deps.updatePreferenceStore.setAvailableUpdate(result.update)
                    if (prefs.notifyOnUpdateEnabled &&
                        result.update.versionName != prefs.lastNotifiedVersionName
                    ) {
                        deps.appUpdateNotifier.notifyUpdateAvailable(result.update)
                        deps.updatePreferenceStore.setLastNotifiedVersion(result.update.versionName)
                    }
                    Success
                }
                AppUpdateCheckResult.UpToDate -> {
                    deps.updatePreferenceStore.setAvailableUpdate(null)
                    Success
                }
                // Do not clear a previously-found update here: NoRelease can be a transient API blip and
                // NoApkAsset still leaves a valid older downloadable build — only a definitive UpToDate clears.
                AppUpdateCheckResult.NoRelease,
                AppUpdateCheckResult.NoApkAsset,
                -> Success
                is AppUpdateCheckResult.Failure -> Retry
            }
        }
    }
}
