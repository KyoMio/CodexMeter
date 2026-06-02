package com.kmnexus.codexmeter.data.secure

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Test

class FakeSecureSessionStoreTest {
    @Test
    fun `fake store saves and loads encrypted payload bytes`() = runTest {
        val store = FakeSecureSessionStore()
        val envelope = ProviderSessionEnvelope(
            providerId = "codex",
            localAccountId = "local",
            providerAccountId = "acct",
            schemaVersion = 1,
            payloadCiphertext = byteArrayOf(1, 2, 3),
            payloadNonce = byteArrayOf(4, 5, 6),
            createdAt = "2026-05-22T00:00:00Z",
            updatedAt = "2026-05-22T00:00:00Z",
        )

        store.save(envelope)

        assertEquals("codex", store.load("codex", "local")!!.providerId)
    }

    @Test
    fun `fake store copies byte arrays on save and load`() = runTest {
        val store = FakeSecureSessionStore()
        val ciphertext = byteArrayOf(1, 2, 3)
        val nonce = byteArrayOf(4, 5, 6)
        val envelope = ProviderSessionEnvelope(
            providerId = "codex",
            localAccountId = "local",
            providerAccountId = "acct",
            schemaVersion = 1,
            payloadCiphertext = ciphertext,
            payloadNonce = nonce,
            createdAt = "2026-05-22T00:00:00Z",
            updatedAt = "2026-05-22T00:00:00Z",
        )

        store.save(envelope)
        ciphertext[0] = 9
        nonce[0] = 8

        val loaded = store.load("codex", "local")!!

        assertArrayEquals(byteArrayOf(1, 2, 3), loaded.payloadCiphertext)
        assertArrayEquals(byteArrayOf(4, 5, 6), loaded.payloadNonce)
        assertNotSame(ciphertext, loaded.payloadCiphertext)
        assertNotSame(nonce, loaded.payloadNonce)

        loaded.payloadCiphertext[1] = 7
        loaded.payloadNonce[1] = 9

        val reloaded = store.load("codex", "local")!!

        assertArrayEquals(byteArrayOf(1, 2, 3), reloaded.payloadCiphertext)
        assertArrayEquals(byteArrayOf(4, 5, 6), reloaded.payloadNonce)
    }
}
