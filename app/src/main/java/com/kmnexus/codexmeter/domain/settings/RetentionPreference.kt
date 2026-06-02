package com.kmnexus.codexmeter.domain.settings

enum class RetentionPreference {
    SevenDays,
    ThirtyDays,
    NinetyDays,
    Forever,
}

interface RetentionPreferenceReader {
    suspend fun retentionPreference(): RetentionPreference
}

interface RetentionPreferenceStore : RetentionPreferenceReader {
    suspend fun updateRetentionPreference(preference: RetentionPreference)
}
