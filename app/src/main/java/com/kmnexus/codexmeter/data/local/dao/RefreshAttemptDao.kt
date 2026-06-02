package com.kmnexus.codexmeter.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.kmnexus.codexmeter.data.local.entity.REFRESH_ATTEMPTS_TABLE_NAME
import com.kmnexus.codexmeter.data.local.entity.RefreshAttemptEntity

@Dao
interface RefreshAttemptDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(attempt: RefreshAttemptEntity)

    @Query(
        "SELECT * FROM " + REFRESH_ATTEMPTS_TABLE_NAME +
            " WHERE provider_id = :providerId AND local_account_id = :localAccountId" +
            " ORDER BY started_at DESC LIMIT 1",
    )
    suspend fun getLatestForAccount(
        providerId: String,
        localAccountId: String,
    ): RefreshAttemptEntity?

    @Query(
        "SELECT * FROM " + REFRESH_ATTEMPTS_TABLE_NAME +
            " WHERE provider_id = :providerId AND local_account_id = :localAccountId AND trigger = :trigger" +
            " ORDER BY started_at DESC LIMIT 1",
    )
    suspend fun getLatestForAccountByTrigger(
        providerId: String,
        localAccountId: String,
        trigger: String,
    ): RefreshAttemptEntity?

    @Query(
        "SELECT COUNT(*) FROM " + REFRESH_ATTEMPTS_TABLE_NAME +
            " WHERE provider_id = :providerId AND local_account_id = :localAccountId" +
            " AND status = 'failed'" +
            " AND started_at > IFNULL((" +
            " SELECT MAX(started_at) FROM " + REFRESH_ATTEMPTS_TABLE_NAME +
            " WHERE provider_id = :providerId AND local_account_id = :localAccountId" +
            " AND status = 'success'" +
            "), -1)",
    )
    suspend fun countConsecutiveFailuresSinceLatestSuccess(
        providerId: String,
        localAccountId: String,
    ): Int

    @Query(
        "DELETE FROM " + REFRESH_ATTEMPTS_TABLE_NAME +
            " WHERE provider_id = :providerId AND local_account_id = :localAccountId",
    )
    suspend fun deleteForAccount(
        providerId: String,
        localAccountId: String,
    ): Int

    @Query("DELETE FROM " + REFRESH_ATTEMPTS_TABLE_NAME)
    suspend fun deleteAll(): Int

    @Query(
        "DELETE FROM " + REFRESH_ATTEMPTS_TABLE_NAME +
            " WHERE started_at < :cutoffMillis AND status = 'success'",
    )
    suspend fun deleteStartedBefore(cutoffMillis: Long): Int
}
