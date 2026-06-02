package com.kmnexus.codexmeter.app

import android.util.Log
import com.kmnexus.codexmeter.data.repository.RetentionCleanup
import com.kmnexus.codexmeter.domain.settings.RetentionPreferenceReader
import java.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class StartupMaintenance(
    private val retentionPreferenceReader: RetentionPreferenceReader,
    private val retentionCleanup: RetentionCleanup,
    private val reporter: StartupMaintenanceReporter = NoopStartupMaintenanceReporter,
    private val clock: Clock,
) {
    fun start(scope: CoroutineScope) {
        scope.launch {
            runCatching {
                retentionCleanup.cleanup(
                    preference = retentionPreferenceReader.retentionPreference(),
                    now = clock.instant(),
                )
            }.onFailure {
                reporter.report(StartupMaintenanceEvent.RetentionCleanupFailed)
            }
        }
    }
}

enum class StartupMaintenanceEvent {
    RetentionCleanupFailed,
}

interface StartupMaintenanceReporter {
    fun report(event: StartupMaintenanceEvent)
}

object NoopStartupMaintenanceReporter : StartupMaintenanceReporter {
    override fun report(event: StartupMaintenanceEvent) = Unit
}

object AndroidStartupMaintenanceReporter : StartupMaintenanceReporter {
    override fun report(event: StartupMaintenanceEvent) {
        Log.w(TAG, "startup_maintenance_${event.name}")
    }

    private const val TAG = "CodexMeterStartup"
}
