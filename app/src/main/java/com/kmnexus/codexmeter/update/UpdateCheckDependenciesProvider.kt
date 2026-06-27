package com.kmnexus.codexmeter.update

import com.kmnexus.codexmeter.domain.update.AppUpdateCheckUseCase
import com.kmnexus.codexmeter.domain.update.AppUpdateNotifier
import com.kmnexus.codexmeter.domain.update.UpdatePreferenceStore

/** Implemented by the Application so [UpdateCheckWorker] can reach its dependencies. */
interface UpdateCheckDependenciesProvider {
    val appUpdateCheck: AppUpdateCheckUseCase
    val updatePreferenceStore: UpdatePreferenceStore
    val appUpdateNotifier: AppUpdateNotifier
    val currentVersionName: String
}
