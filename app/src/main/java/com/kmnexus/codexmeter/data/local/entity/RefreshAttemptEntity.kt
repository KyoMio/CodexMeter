package com.kmnexus.codexmeter.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

internal const val REFRESH_ATTEMPTS_TABLE_NAME = "refresh_attempts"

private object RefreshAttemptColumnNames {
    const val ATTEMPT_ID = "attempt_id"
    const val PROVIDER_ID = "provider_id"
    const val LOCAL_ACCOUNT_ID = "local_account_id"
    const val TRIGGER = "trigger"
    const val STARTED_AT = "started_at"
    const val FINISHED_AT = "finished_at"
    const val STATUS = "status"
    const val ERROR_CODE = "error_code"
    const val HTTP_STATUS = "http_status"
    const val RETRYABLE = "retryable"
    const val USER_ACTION_REQUIRED = "user_action_required"
    const val DIAGNOSTICS_DIGEST = "diagnostics_digest"
}

@Entity(
    tableName = REFRESH_ATTEMPTS_TABLE_NAME,
    indices = [
        Index(value = ["provider_id", "local_account_id", "started_at"]),
    ],
)
data class RefreshAttemptEntity(
    @field:PrimaryKey
    @field:ColumnInfo(name = RefreshAttemptColumnNames.ATTEMPT_ID)
    val attemptId: String,
    @field:ColumnInfo(name = RefreshAttemptColumnNames.PROVIDER_ID)
    val providerId: String,
    @field:ColumnInfo(name = RefreshAttemptColumnNames.LOCAL_ACCOUNT_ID)
    val localAccountId: String,
    @field:ColumnInfo(name = RefreshAttemptColumnNames.TRIGGER)
    val trigger: String,
    @field:ColumnInfo(name = RefreshAttemptColumnNames.STARTED_AT)
    val startedAt: Long,
    @field:ColumnInfo(name = RefreshAttemptColumnNames.FINISHED_AT)
    val finishedAt: Long?,
    @field:ColumnInfo(name = RefreshAttemptColumnNames.STATUS)
    val status: String,
    @field:ColumnInfo(name = RefreshAttemptColumnNames.ERROR_CODE)
    val errorCode: String?,
    @field:ColumnInfo(name = RefreshAttemptColumnNames.HTTP_STATUS)
    val httpStatus: Int?,
    @field:ColumnInfo(name = RefreshAttemptColumnNames.RETRYABLE)
    val retryable: Boolean?,
    @field:ColumnInfo(name = RefreshAttemptColumnNames.USER_ACTION_REQUIRED)
    val userActionRequired: Boolean?,
    @field:ColumnInfo(name = RefreshAttemptColumnNames.DIAGNOSTICS_DIGEST)
    val diagnosticsDigest: String?,
)
