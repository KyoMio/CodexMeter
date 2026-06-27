package com.kmnexus.codexmeter.update

import com.kmnexus.codexmeter.domain.update.AppUpdateCheckResult
import com.kmnexus.codexmeter.domain.update.AppUpdateCheckUseCase
import com.kmnexus.codexmeter.domain.update.AppUpdateInfo
import com.kmnexus.codexmeter.domain.update.AppUpdateNotifier
import com.kmnexus.codexmeter.domain.update.UpdatePreferences
import com.kmnexus.codexmeter.domain.update.UpdatePreferenceStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckOutcomeTest {
    private val info = AppUpdateInfo("1.4.0", "https://r/page", "https://r/app.apk", "app.apk")

    @Test fun `null provider is a benign no-op success`() = runTest {
        assertEquals(UpdateCheckOutcome.Success, UpdateCheckOutcome.run(null))
    }

    @Test fun `auto-check disabled does nothing`() = runTest {
        val deps = FakeDeps(prefs = UpdatePreferences(autoCheckEnabled = false))
        assertEquals(UpdateCheckOutcome.Success, UpdateCheckOutcome.run(deps))
        assertTrue(deps.notifier.notified.isEmpty())
        assertNull(deps.store.savedAvailable)
        assertFalse(deps.store.clearedAvailable)
    }

    @Test fun `update available persists and notifies once`() = runTest {
        val deps = FakeDeps(
            prefs = UpdatePreferences(autoCheckEnabled = true, notifyOnUpdateEnabled = true),
            result = AppUpdateCheckResult.UpdateAvailable(info),
        )
        assertEquals(UpdateCheckOutcome.Success, UpdateCheckOutcome.run(deps))
        assertEquals(listOf(info), deps.notifier.notified)
        assertEquals(info, deps.store.savedAvailable)
        assertEquals("1.4.0", deps.store.savedLastNotified)
    }

    @Test fun `same version is not notified twice`() = runTest {
        val deps = FakeDeps(
            prefs = UpdatePreferences(notifyOnUpdateEnabled = true, lastNotifiedVersionName = "1.4.0"),
            result = AppUpdateCheckResult.UpdateAvailable(info),
        )
        UpdateCheckOutcome.run(deps)
        assertTrue(deps.notifier.notified.isEmpty())
        assertEquals(info, deps.store.savedAvailable) // still refreshes the badge state
    }

    @Test fun `notify disabled persists badge but does not notify`() = runTest {
        val deps = FakeDeps(
            prefs = UpdatePreferences(notifyOnUpdateEnabled = false),
            result = AppUpdateCheckResult.UpdateAvailable(info),
        )
        UpdateCheckOutcome.run(deps)
        assertTrue(deps.notifier.notified.isEmpty())
        assertEquals(info, deps.store.savedAvailable)
    }

    @Test fun `up to date clears available update`() = runTest {
        val deps = FakeDeps(result = AppUpdateCheckResult.UpToDate)
        UpdateCheckOutcome.run(deps)
        assertTrue(deps.store.clearedAvailable)
    }

    @Test fun `failure asks WorkManager to retry`() = runTest {
        val deps = FakeDeps(result = AppUpdateCheckResult.Failure("x"))
        assertEquals(UpdateCheckOutcome.Retry, UpdateCheckOutcome.run(deps))
    }

    @Test fun `thrown exception asks WorkManager to retry`() = runTest {
        val deps = FakeDeps(throwOnCheck = true)
        assertEquals(UpdateCheckOutcome.Retry, UpdateCheckOutcome.run(deps))
    }

    private class RecordingStore(private val initial: UpdatePreferences) : UpdatePreferenceStore {
        var savedAvailable: AppUpdateInfo? = null
        var clearedAvailable = false
        var savedLastNotified: String? = null
        override suspend fun preferences() = initial
        override suspend fun setAutoCheckEnabled(enabled: Boolean) = Unit
        override suspend fun setNotifyOnUpdateEnabled(enabled: Boolean) = Unit
        override suspend fun setLastNotifiedVersion(versionName: String?) { savedLastNotified = versionName }
        override suspend fun setAvailableUpdate(update: AppUpdateInfo?) {
            if (update == null) clearedAvailable = true else savedAvailable = update
        }
    }

    private class RecordingNotifier : AppUpdateNotifier {
        val notified = mutableListOf<AppUpdateInfo>()
        override fun notifyUpdateAvailable(update: AppUpdateInfo) { notified += update }
    }

    private class FakeDeps(
        prefs: UpdatePreferences = UpdatePreferences(),
        private val result: AppUpdateCheckResult = AppUpdateCheckResult.UpToDate,
        private val throwOnCheck: Boolean = false,
        val store: RecordingStore = RecordingStore(prefs),
        val notifier: RecordingNotifier = RecordingNotifier(),
    ) : UpdateCheckDependenciesProvider {
        override val appUpdateCheck = AppUpdateCheckUseCase {
            if (throwOnCheck) throw java.io.IOException("boom") else result
        }
        override val updatePreferenceStore: UpdatePreferenceStore = store
        override val appUpdateNotifier: AppUpdateNotifier = notifier
        override val currentVersionName = "1.0.0"
    }
}
