package com.kmnexus.codexmeter.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.kmnexus.codexmeter.data.local.entity.QUOTA_SNAPSHOTS_TABLE_NAME
import com.kmnexus.codexmeter.data.local.entity.QuotaSnapshotEntity

@Dao
interface QuotaSnapshotDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(snapshot: QuotaSnapshotEntity)

    @Query(
        "SELECT * FROM " + QUOTA_SNAPSHOTS_TABLE_NAME +
            " WHERE provider_id = :providerId AND local_account_id = :localAccountId" +
            " ORDER BY fetched_at DESC LIMIT 1",
    )
    suspend fun getLatestForAccount(
        providerId: String,
        localAccountId: String,
    ): QuotaSnapshotEntity?

    @Query(
        "DELETE FROM " + QUOTA_SNAPSHOTS_TABLE_NAME +
            " WHERE provider_id = :providerId AND local_account_id = :localAccountId",
    )
    suspend fun deleteForAccount(
        providerId: String,
        localAccountId: String,
    ): Int

    @Query("DELETE FROM " + QUOTA_SNAPSHOTS_TABLE_NAME)
    suspend fun deleteAll(): Int

    @Query(
        "DELETE FROM " + QUOTA_SNAPSHOTS_TABLE_NAME +
            " WHERE fetched_at < :cutoffMillis",
    )
    suspend fun deleteFetchedBefore(cutoffMillis: Long): Int

    @Query(
        "SELECT * FROM " + QUOTA_SNAPSHOTS_TABLE_NAME +
            " WHERE provider_id = :providerId AND local_account_id = :localAccountId" +
            " AND fetched_at >= :sinceMillis" +
            " ORDER BY fetched_at ASC",
    )
    suspend fun listForAccountSince(
        providerId: String,
        localAccountId: String,
        sinceMillis: Long,
    ): List<QuotaSnapshotEntity>
}
