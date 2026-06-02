package com.kmnexus.codexmeter.data.secure

/**
 * Encrypts opaque provider session payload bytes (API keys, cookies, OAuth tokens) at rest.
 *
 * Security invariant: provider credentials must never be persisted in plaintext. Every importer
 * encrypts its serialized payload through this cipher before building a [ProviderSessionEnvelope],
 * and every refresh provider decrypts through it before deserializing.
 */
interface PayloadCipher {
    fun encrypt(plaintext: ByteArray): EncryptedPayload

    fun decrypt(ciphertext: ByteArray, nonce: ByteArray): ByteArray
}

/** Result of [PayloadCipher.encrypt]: the AES-GCM ciphertext and the nonce/IV used to produce it. */
class EncryptedPayload(
    val ciphertext: ByteArray,
    val nonce: ByteArray,
)
