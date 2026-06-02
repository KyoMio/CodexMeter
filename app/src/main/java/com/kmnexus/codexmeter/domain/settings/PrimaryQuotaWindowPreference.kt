package com.kmnexus.codexmeter.domain.settings

import com.kmnexus.codexmeter.domain.model.QuotaWindowId

interface PrimaryQuotaWindowPreferenceReader {
    suspend fun primaryQuotaWindowId(): QuotaWindowId
}

interface PrimaryQuotaWindowPreferenceStore : PrimaryQuotaWindowPreferenceReader {
    suspend fun updatePrimaryQuotaWindowId(windowId: QuotaWindowId)
}

object DefaultPrimaryQuotaWindowPreferenceReader : PrimaryQuotaWindowPreferenceReader {
    override suspend fun primaryQuotaWindowId(): QuotaWindowId = DEFAULT_PRIMARY_QUOTA_WINDOW_ID
}

val DEFAULT_PRIMARY_QUOTA_WINDOW_ID = QuotaWindowId("five_hour")
val SUPPORTED_PRIMARY_QUOTA_WINDOW_IDS = setOf(
    DEFAULT_PRIMARY_QUOTA_WINDOW_ID,
    QuotaWindowId("weekly"),
)
