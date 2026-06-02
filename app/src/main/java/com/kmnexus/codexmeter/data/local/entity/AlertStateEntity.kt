package com.kmnexus.codexmeter.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

internal const val ALERT_STATES_TABLE_NAME = "alert_states"

private object AlertStateColumnNames {
    const val ALERT_STATE_ID = "alert_state_id"
    const val PROVIDER_ID = "provider_id"
    const val LOCAL_ACCOUNT_ID = "local_account_id"
    const val WINDOW_ID = "window_id"
    const val THRESHOLD = "threshold"
    const val RESET_AT = "reset_at"
    const val LAST_NOTIFIED_AT = "last_notified_at"
}

@Entity(
    tableName = ALERT_STATES_TABLE_NAME,
    indices = [
        Index(
            value = ["provider_id", "local_account_id", "window_id", "reset_at", "threshold"],
            unique = true,
        ),
    ],
)
data class AlertStateEntity(
    @field:PrimaryKey
    @field:ColumnInfo(name = AlertStateColumnNames.ALERT_STATE_ID)
    val alertStateId: String,
    @field:ColumnInfo(name = AlertStateColumnNames.PROVIDER_ID)
    val providerId: String,
    @field:ColumnInfo(name = AlertStateColumnNames.LOCAL_ACCOUNT_ID)
    val localAccountId: String,
    @field:ColumnInfo(name = AlertStateColumnNames.WINDOW_ID)
    val windowId: String,
    @field:ColumnInfo(name = AlertStateColumnNames.THRESHOLD)
    val threshold: Double,
    @field:ColumnInfo(name = AlertStateColumnNames.RESET_AT)
    val resetAt: String,
    @field:ColumnInfo(name = AlertStateColumnNames.LAST_NOTIFIED_AT)
    val lastNotifiedAt: String,
)
