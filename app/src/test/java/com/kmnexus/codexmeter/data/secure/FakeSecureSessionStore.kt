package com.kmnexus.codexmeter.data.secure

class FakeSecureSessionStore : SecureSessionStore {
    private val envelopes = mutableMapOf<Key, ProviderSessionEnvelope>()

    override suspend fun save(envelope: ProviderSessionEnvelope) {
        envelopes[Key(envelope.providerId, envelope.localAccountId)] = envelope.deepCopy()
    }

    override suspend fun load(
        providerId: String,
        localAccountId: String,
    ): ProviderSessionEnvelope? = envelopes[Key(providerId, localAccountId)]?.deepCopy()

    override suspend fun delete(providerId: String, localAccountId: String) {
        envelopes.remove(Key(providerId, localAccountId))
    }

    private fun ProviderSessionEnvelope.deepCopy(): ProviderSessionEnvelope =
        copy(
            payloadCiphertext = payloadCiphertext.copyOf(),
            payloadNonce = payloadNonce.copyOf(),
        )

    private data class Key(
        val providerId: String,
        val localAccountId: String,
    )
}
