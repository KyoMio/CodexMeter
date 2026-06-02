package com.kmnexus.codexmeter.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

internal const val QUOTA_SNAPSHOTS_TABLE_NAME = "quota_snapshots"

private object QuotaSnapshotColumnNames {
    const val SNAPSHOT_ID = "snapshot_id"
    const val PROVIDER_ID = "provider_id"
    const val LOCAL_ACCOUNT_ID = "local_account_id"
    const val PROVIDER_ACCOUNT_ID = "provider_account_id"
    const val FETCHED_AT = "fetched_at"
    const val SOURCE = "source"
    const val PLAN_TYPE = "plan_type"
    const val WINDOWS_JSON = "windows_json"
    const val CREDITS_JSON = "credits_json"
    const val RESPONSE_DIGEST = "response_digest"
}

@Entity(
    tableName = QUOTA_SNAPSHOTS_TABLE_NAME,
    indices = [
        Index(value = ["provider_id", "local_account_id", "fetched_at"]),
    ],
)
data class QuotaSnapshotEntity(
    @field:PrimaryKey
    @field:ColumnInfo(name = QuotaSnapshotColumnNames.SNAPSHOT_ID)
    val snapshotId: String,
    @field:ColumnInfo(name = QuotaSnapshotColumnNames.PROVIDER_ID)
    val providerId: String,
    @field:ColumnInfo(name = QuotaSnapshotColumnNames.LOCAL_ACCOUNT_ID)
    val localAccountId: String,
    @field:ColumnInfo(name = QuotaSnapshotColumnNames.PROVIDER_ACCOUNT_ID)
    val providerAccountId: String?,
    @field:ColumnInfo(name = QuotaSnapshotColumnNames.FETCHED_AT)
    val fetchedAt: Long,
    @field:ColumnInfo(name = QuotaSnapshotColumnNames.SOURCE)
    val source: String,
    @field:ColumnInfo(name = QuotaSnapshotColumnNames.PLAN_TYPE)
    val planType: String?,
    @field:ColumnInfo(name = QuotaSnapshotColumnNames.WINDOWS_JSON)
    val windowsJson: String,
    @field:ColumnInfo(name = QuotaSnapshotColumnNames.CREDITS_JSON)
    val creditsJson: String?,
    @field:ColumnInfo(name = QuotaSnapshotColumnNames.RESPONSE_DIGEST)
    val responseDigest: String?,
)
