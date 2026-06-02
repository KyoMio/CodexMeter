package com.kmnexus.codexmeter.data.local.dao

import androidx.room.Room
import com.kmnexus.codexmeter.data.local.db.CodexMeterDatabase
import com.kmnexus.codexmeter.data.local.entity.RefreshAttemptEntity
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
// Robolectric SDK 36 currently requires Java 21, while this project is pinned to Java 17.
@Config(sdk = [35])
class RefreshAttemptDaoTest {
    @Test
    fun `counts consecutive failures since latest success`() = runTest {
        withDao { dao ->
            dao.insert(attempt(id = "success-1", status = "success", startedAt = 1_000))
            dao.insert(attempt(id = "failure-1", status = "failed", startedAt = 2_000))
            dao.insert(attempt(id = "failure-2", status = "failed", startedAt = 3_000))
            dao.insert(attempt(id = "failure-3", status = "failed", startedAt = 4_000))

            val count = dao.countConsecutiveFailuresSinceLatestSuccess(
                providerId = "codex",
                localAccountId = "local-1",
            )

            assertEquals(3, count)
        }
    }

    @Test
    fun `success attempt resets repeated failure count`() = runTest {
        withDao { dao ->
            dao.insert(attempt(id = "failure-1", status = "failed", startedAt = 1_000))
            dao.insert(attempt(id = "failure-2", status = "failed", startedAt = 2_000))
            dao.insert(attempt(id = "success-1", status = "success", startedAt = 3_000))

            val count = dao.countConsecutiveFailuresSinceLatestSuccess(
                providerId = "codex",
                localAccountId = "local-1",
            )

            assertEquals(0, count)
        }
    }

    @Test
    fun `consecutive failure count is scoped per account`() = runTest {
        withDao { dao ->
            dao.insert(attempt(id = "local-1-failure-1", status = "failed", startedAt = 1_000, localAccountId = "local-1"))
            dao.insert(attempt(id = "local-1-failure-2", status = "failed", startedAt = 2_000, localAccountId = "local-1"))
            dao.insert(attempt(id = "local-2-failure-1", status = "failed", startedAt = 3_000, localAccountId = "local-2"))

            val count = dao.countConsecutiveFailuresSinceLatestSuccess(
                providerId = "codex",
                localAccountId = "local-2",
            )

            assertEquals(1, count)
        }
    }

    private suspend fun withDao(block: suspend (RefreshAttemptDao) -> Unit) {
        val db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            CodexMeterDatabase::class.java,
        ).build()
        try {
            block(db.refreshAttemptDao())
        } finally {
            db.close()
        }
    }

    private fun attempt(
        id: String,
        status: String,
        startedAt: Long,
        localAccountId: String = "local-1",
    ): RefreshAttemptEntity =
        RefreshAttemptEntity(
            attemptId = id,
            providerId = "codex",
            localAccountId = localAccountId,
            trigger = "periodic",
            startedAt = startedAt,
            finishedAt = Instant.ofEpochMilli(startedAt + 100).toEpochMilli(),
            status = status,
            errorCode = if (status == "failed") "network" else null,
            httpStatus = null,
            retryable = status == "failed",
            userActionRequired = false,
            diagnosticsDigest = "safe-digest",
        )
}
