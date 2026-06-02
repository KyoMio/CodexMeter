package com.kmnexus.codexmeter.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

internal const val PROVIDER_ACCOUNTS_TABLE_NAME = "provider_accounts"

private object ProviderAccountColumnNames {
    const val LOCAL_ACCOUNT_ID = "local_account_id"
    const val PROVIDER_ID = "provider_id"
    const val PROVIDER_ACCOUNT_ID = "provider_account_id"
    const val DISPLAY_NAME = "display_name"
    const val AVATAR_INITIAL = "avatar_initial"
    const val AVATAR_COLOR_KEY = "avatar_color_key"
    const val STATUS = "status"
    const val CREATED_AT = "created_at"
    const val UPDATED_AT = "updated_at"
    const val LAST_SUCCESSFUL_REFRESH_AT = "last_successful_refresh_at"
}

@Entity(
    tableName = PROVIDER_ACCOUNTS_TABLE_NAME,
    indices = [
        Index(value = ["provider_id", "provider_account_id"], unique = true),
        Index(value = ["provider_id", "local_account_id"]),
    ],
)
data class ProviderAccountEntity(
    @field:PrimaryKey
    @field:ColumnInfo(name = ProviderAccountColumnNames.LOCAL_ACCOUNT_ID)
    val localAccountId: String,
    @field:ColumnInfo(name = ProviderAccountColumnNames.PROVIDER_ID)
    val providerId: String,
    @field:ColumnInfo(name = ProviderAccountColumnNames.PROVIDER_ACCOUNT_ID)
    val providerAccountId: String?,
    @field:ColumnInfo(name = ProviderAccountColumnNames.DISPLAY_NAME)
    val displayName: String,
    @field:ColumnInfo(name = ProviderAccountColumnNames.AVATAR_INITIAL)
    val avatarInitial: String,
    @field:ColumnInfo(name = ProviderAccountColumnNames.AVATAR_COLOR_KEY)
    val avatarColorKey: String,
    @field:ColumnInfo(name = ProviderAccountColumnNames.STATUS)
    val status: String,
    @field:ColumnInfo(name = ProviderAccountColumnNames.CREATED_AT)
    val createdAt: Long,
    @field:ColumnInfo(name = ProviderAccountColumnNames.UPDATED_AT)
    val updatedAt: Long,
    @field:ColumnInfo(name = ProviderAccountColumnNames.LAST_SUCCESSFUL_REFRESH_AT)
    val lastSuccessfulRefreshAt: Long?,
)
