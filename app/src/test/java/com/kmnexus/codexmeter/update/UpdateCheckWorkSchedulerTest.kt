package com.kmnexus.codexmeter.update

import com.kmnexus.codexmeter.CodexMeterApp
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckWorkSchedulerTest {
    @Test fun `enabling schedules the daily unique work`() {
        val enqueuer = RecordingEnqueuer()
        UpdateCheckWorkScheduler(enqueuer).setAutoCheckEnabled(true)
        assertEquals("app_update_check", enqueuer.enqueued.single().uniqueWorkName)
        assertEquals(Duration.ofHours(24), enqueuer.enqueued.single().repeatInterval)
    }

    @Test fun `disabling cancels the unique work`() {
        val enqueuer = RecordingEnqueuer()
        UpdateCheckWorkScheduler(enqueuer).setAutoCheckEnabled(false)
        assertEquals(listOf("app_update_check"), enqueuer.cancelled)
        assertTrue(enqueuer.enqueued.isEmpty())
    }

    @Test fun `application exposes update-check dependencies`() {
        assertTrue(UpdateCheckDependenciesProvider::class.java.isAssignableFrom(CodexMeterApp::class.java))
    }

    private class RecordingEnqueuer : UpdateCheckWorkEnqueuer {
        val enqueued = mutableListOf<UpdateCheckWorkPlan>()
        val cancelled = mutableListOf<String>()
        override fun enqueue(plan: UpdateCheckWorkPlan) { enqueued += plan }
        override fun cancel(uniqueWorkName: String) { cancelled += uniqueWorkName }
    }
}
