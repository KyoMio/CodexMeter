package com.kmnexus.codexmeter.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.kmnexus.codexmeter.data.local.entity.ALERT_STATES_TABLE_NAME
import com.kmnexus.codexmeter.data.local.entity.AlertStateEntity

@Dao
interface AlertStateDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(alertState: AlertStateEntity)

    @Query(
        "SELECT * FROM " + ALERT_STATES_TABLE_NAME +
            " WHERE provider_id = :providerId" +
            " AND local_account_id = :localAccountId" +
            " AND window_id = :windowId" +
            " AND reset_at = :resetAt" +
            " AND threshold = :threshold" +
            " LIMIT 1",
    )
    suspend fun getByDedupeKey(
        providerId: String,
        localAccountId: String,
        windowId: String,
        resetAt: String,
        threshold: Double,
    ): AlertStateEntity?

    @Query(
        "DELETE FROM " + ALERT_STATES_TABLE_NAME +
            " WHERE provider_id = :providerId AND local_account_id = :localAccountId",
    )
    suspend fun deleteForAccount(
        providerId: String,
        localAccountId: String,
    ): Int

    @Query("DELETE FROM " + ALERT_STATES_TABLE_NAME)
    suspend fun deleteAll(): Int
}
