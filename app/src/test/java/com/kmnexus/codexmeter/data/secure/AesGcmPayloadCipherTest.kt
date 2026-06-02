package com.kmnexus.codexmeter.data.secure

import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AesGcmPayloadCipherTest {
    private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val cipher = AesGcmPayloadCipher { key }

    @Test
    fun encrypt_thenDecrypt_roundTripsPlaintext() {
        val plaintext = """{"apiKey":"sk-super-secret"}""".encodeToByteArray()

        val encrypted = cipher.encrypt(plaintext)
        val decrypted = cipher.decrypt(encrypted.ciphertext, encrypted.nonce)

        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun encrypt_doesNotStorePlaintext() {
        val plaintext = """{"cookieValue":"kimi-auth.jwt"}""".encodeToByteArray()

        val encrypted = cipher.encrypt(plaintext)

        assertFalse(
            "ciphertext must not equal plaintext",
            encrypted.ciphertext.contentEquals(plaintext),
        )
        assertTrue("nonce must be present for GCM", encrypted.nonce.isNotEmpty())
        assertFalse(
            "ciphertext must not contain the raw secret bytes",
            encrypted.ciphertext.decodeToString().contains("kimi-auth"),
        )
    }

    @Test
    fun encrypt_usesFreshNoncePerCall() {
        val plaintext = "token".encodeToByteArray()

        val first = cipher.encrypt(plaintext)
        val second = cipher.encrypt(plaintext)

        assertFalse(
            "GCM nonce must be randomized per encryption",
            first.nonce.contentEquals(second.nonce),
        )
    }

    @Test
    fun decrypt_withWrongNonce_fails() {
        val plaintext = "token".encodeToByteArray()
        val encrypted = cipher.encrypt(plaintext)
        val tamperedNonce = encrypted.nonce.copyOf().also { it[0] = (it[0] + 1).toByte() }

        var threw = false
        try {
            cipher.decrypt(encrypted.ciphertext, tamperedNonce)
        } catch (_: Exception) {
            threw = true
        }
        assertEquals(true, threw)
    }
}
