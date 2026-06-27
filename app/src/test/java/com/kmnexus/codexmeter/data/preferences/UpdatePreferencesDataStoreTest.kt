package com.kmnexus.codexmeter.data.preferences

import com.kmnexus.codexmeter.domain.update.AppUpdateInfo
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import androidx.datastore.preferences.preferencesDataStoreFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UpdatePreferencesDataStoreTest {
    private fun store(file: String) = UpdatePreferencesDataStore.create(
        file = RuntimeEnvironment.getApplication().preferencesDataStoreFile(file),
    )

    @Test fun `defaults both toggles on and no available update`() = runTest {
        val prefs = store("update-default.preferences_pb").preferences()
        assertTrue(prefs.autoCheckEnabled)
        assertTrue(prefs.notifyOnUpdateEnabled)
        assertNull(prefs.availableUpdate)
        assertNull(prefs.lastNotifiedVersionName)
    }

    @Test fun `persists toggles and last notified version`() = runTest {
        val subject = store("update-toggles.preferences_pb")
        subject.setAutoCheckEnabled(false)
        subject.setNotifyOnUpdateEnabled(false)
        subject.setLastNotifiedVersion("1.2.3")
        val prefs = subject.preferences()
        assertFalse(prefs.autoCheckEnabled)
        assertFalse(prefs.notifyOnUpdateEnabled)
        assertEquals("1.2.3", prefs.lastNotifiedVersionName)
    }

    @Test fun `persists and clears available update`() = runTest {
        val subject = store("update-available.preferences_pb")
        val info = AppUpdateInfo("1.4.0", "https://r/page", "https://r/app.apk", "app.apk", "## notes")
        subject.setAvailableUpdate(info)
        assertEquals(info, subject.preferences().availableUpdate)
        assertEquals("## notes", subject.preferences().availableUpdate?.releaseNotes)
        subject.setAvailableUpdate(null)
        assertNull(subject.preferences().availableUpdate)
    }

    @Test fun `clears last notified version on null or blank`() = runTest {
        val subject = store("update-last-notified-clear.preferences_pb")
        subject.setLastNotifiedVersion("1.2.3")
        assertEquals("1.2.3", subject.preferences().lastNotifiedVersionName)
        subject.setLastNotifiedVersion(null)
        assertNull(subject.preferences().lastNotifiedVersionName)
        subject.setLastNotifiedVersion("1.2.3")
        assertEquals("1.2.3", subject.preferences().lastNotifiedVersionName)
        subject.setLastNotifiedVersion("")
        assertNull(subject.preferences().lastNotifiedVersionName)
    }
}
