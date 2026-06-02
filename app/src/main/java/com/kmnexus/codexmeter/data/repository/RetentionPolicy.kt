package com.kmnexus.codexmeter.data.repository

import androidx.room.withTransaction
import com.kmnexus.codexmeter.data.local.db.CodexMeterDatabase
import com.kmnexus.codexmeter.domain.settings.RetentionPreference
import java.time.Duration
import java.time.Instant

data class RetentionCleanupPlan(
    val deleteHistoryBefore: Instant?,
    val deleteQuotaSnapshots: Boolean,
    val deleteRefreshAttempts: Boolean,
    val deleteSessions: Boolean,
)

class RetentionPolicy private constructor(
    private val retention: Duration?,
) {
    fun evaluate(now: Instant): RetentionCleanupPlan {
        val cutoff = retention?.let(now::minus)
        return RetentionCleanupPlan(
            deleteHistoryBefore = cutoff,
            deleteQuotaSnapshots = cutoff != null,
            deleteRefreshAttempts = cutoff != null,
            deleteSessions = false,
        )
    }

    companion object {
        fun forPreference(preference: RetentionPreference): RetentionPolicy =
            RetentionPolicy(
                retention = when (preference) {
                    RetentionPreference.SevenDays -> Duration.ofDays(7)
                    RetentionPreference.ThirtyDays -> Duration.ofDays(30)
                    RetentionPreference.NinetyDays -> Duration.ofDays(90)
                    RetentionPreference.Forever -> null
                },
            )
    }
}

data class RetentionCleanupResult(
    val deletedQuotaSnapshots: Int,
    val deletedRefreshAttempts: Int,
)

interface RetentionCleanup {
    suspend fun cleanup(
        preference: RetentionPreference,
        now: Instant,
    ): RetentionCleanupResult
}

class RetentionCleanupRepository(
    private val database: CodexMeterDatabase,
) : RetentionCleanup {
    override suspend fun cleanup(
        preference: RetentionPreference,
        now: Instant,
    ): RetentionCleanupResult {
        val plan = RetentionPolicy.forPreference(preference).evaluate(now)
        val cutoffMillis = plan.deleteHistoryBefore?.toEpochMilli()
            ?: return RetentionCleanupResult(
                deletedQuotaSnapshots = 0,
                deletedRefreshAttempts = 0,
            )

        return database.withTransaction {
            RetentionCleanupResult(
                deletedQuotaSnapshots = database.quotaSnapshotDao().deleteFetchedBefore(cutoffMillis),
                deletedRefreshAttempts = database.refreshAttemptDao().deleteStartedBefore(cutoffMillis),
            )
        }
    }
}
