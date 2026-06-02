package com.kmnexus.codexmeter.data.secure

interface SecureSessionStore {
    suspend fun save(envelope: ProviderSessionEnvelope)

    suspend fun load(
        providerId: String,
        localAccountId: String,
    ): ProviderSessionEnvelope?

    suspend fun delete(
        providerId: String,
        localAccountId: String,
    )
}
