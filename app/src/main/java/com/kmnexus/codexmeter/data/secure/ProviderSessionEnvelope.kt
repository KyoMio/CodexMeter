package com.kmnexus.codexmeter.data.secure

/**
 * Public metadata wrapper around provider-private encrypted session payload bytes.
 *
 * This boundary exists so common layers can reason about session ownership and lifecycle
 * without ever reading raw provider tokens or other decrypted OAuth fields.
 */
data class ProviderSessionEnvelope(
    val providerId: String,
    val localAccountId: String,
    val providerAccountId: String?,
    val schemaVersion: Int,
    val payloadCiphertext: ByteArray,
    val payloadNonce: ByteArray,
    val createdAt: String,
    val updatedAt: String,
)
