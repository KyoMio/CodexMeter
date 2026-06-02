package com.kmnexus.codexmeter.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.kmnexus.codexmeter.data.local.entity.PROVIDER_ACCOUNTS_TABLE_NAME
import com.kmnexus.codexmeter.data.local.entity.ProviderAccountEntity
import com.kmnexus.codexmeter.data.local.entity.QUOTA_SNAPSHOTS_TABLE_NAME

@Dao
abstract class ProviderAccountDao {
    @Transaction
    open suspend fun upsert(account: ProviderAccountEntity) {
        val knownProviderAccountId = account.providerAccountId
        if (knownProviderAccountId == null) {
            val existingLocalAccount = getById(account.localAccountId)
            val accountToUpsert = if (existingLocalAccount?.providerAccountId != null) {
                account.copy(providerAccountId = existingLocalAccount.providerAccountId)
            } else {
                account
            }
            upsertByLocalAccountId(accountToUpsert)
            return
        }

        val existingAccount = getByProviderAccountId(
            providerId = account.providerId,
            providerAccountId = knownProviderAccountId,
        )

        if (existingAccount == null) {
            upsertByLocalAccountId(account)
            return
        }

        if (existingAccount.localAccountId == account.localAccountId) {
            upsertByLocalAccountId(
                existingAccount.copy(
                    status = account.status,
                    updatedAt = account.updatedAt,
                    lastSuccessfulRefreshAt = account.lastSuccessfulRefreshAt,
                ),
            )
            return
        }

        if (getById(account.localAccountId) != null) {
            throw IllegalStateException("Provider account local identity conflict")
        }

        upsertByLocalAccountId(
            existingAccount.copy(
                updatedAt = account.updatedAt,
            ),
        )
    }

    @Upsert
    protected abstract suspend fun upsertByLocalAccountId(account: ProviderAccountEntity)

    @Query(
        "SELECT * FROM " + PROVIDER_ACCOUNTS_TABLE_NAME +
            " WHERE provider_id = :providerId AND provider_account_id = :providerAccountId LIMIT 1",
    )
    abstract suspend fun getByProviderAccountId(
        providerId: String,
        providerAccountId: String,
    ): ProviderAccountEntity?

    @Query(
        "SELECT * FROM " + PROVIDER_ACCOUNTS_TABLE_NAME +
            " WHERE local_account_id = :localAccountId LIMIT 1",
    )
    abstract suspend fun getById(localAccountId: String): ProviderAccountEntity?

    @Query(
        "SELECT local_account_id, provider_id, provider_account_id, display_name, avatar_initial, avatar_color_key," +
            " status, created_at, updated_at," +
            " COALESCE(" +
            " (SELECT MAX(fetched_at) FROM " + QUOTA_SNAPSHOTS_TABLE_NAME +
            " WHERE " + QUOTA_SNAPSHOTS_TABLE_NAME + ".provider_id = " + PROVIDER_ACCOUNTS_TABLE_NAME + ".provider_id" +
            " AND " + QUOTA_SNAPSHOTS_TABLE_NAME + ".local_account_id = " + PROVIDER_ACCOUNTS_TABLE_NAME + ".local_account_id)," +
            " last_successful_refresh_at" +
            " ) AS last_successful_refresh_at" +
            " FROM " + PROVIDER_ACCOUNTS_TABLE_NAME +
            " WHERE provider_id = :providerId" +
            " ORDER BY created_at ASC",
    )
    abstract suspend fun listByProvider(providerId: String): List<ProviderAccountEntity>

    @Query("SELECT * FROM $PROVIDER_ACCOUNTS_TABLE_NAME ORDER BY created_at ASC")
    abstract suspend fun listAll(): List<ProviderAccountEntity>

    @Delete
    abstract suspend fun delete(account: ProviderAccountEntity)

    @Query(
        "UPDATE " + PROVIDER_ACCOUNTS_TABLE_NAME +
            " SET status = :status, updated_at = :updatedAt" +
            " WHERE provider_id = :providerId AND local_account_id = :localAccountId",
    )
    abstract suspend fun updateStatus(
        providerId: String,
        localAccountId: String,
        status: String,
        updatedAt: Long,
    )

    @Query(
        "UPDATE " + PROVIDER_ACCOUNTS_TABLE_NAME +
            " SET display_name = :displayName, avatar_initial = :avatarInitial, updated_at = :updatedAt" +
            " WHERE provider_id = :providerId AND local_account_id = :localAccountId",
    )
    abstract suspend fun updateDisplayName(
        providerId: String,
        localAccountId: String,
        displayName: String,
        avatarInitial: String,
        updatedAt: Long,
    ): Int
}
