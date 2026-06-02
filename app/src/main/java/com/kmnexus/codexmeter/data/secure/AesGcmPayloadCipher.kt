package com.kmnexus.codexmeter.data.secure

import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-GCM implementation of [PayloadCipher].
 *
 * The secret key is supplied lazily so the crypto logic stays platform-independent: production wires
 * an Android Keystore-backed provider ([AndroidKeystoreSecretKeyProvider]); tests can supply a plain
 * in-memory AES key to exercise the real encrypt/decrypt path on the JVM.
 */
class AesGcmPayloadCipher(
    private val secretKeyProvider: () -> SecretKey,
) : PayloadCipher {
    override fun encrypt(plaintext: ByteArray): EncryptedPayload {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKeyProvider())
        val ciphertext = cipher.doFinal(plaintext)
        return EncryptedPayload(ciphertext = ciphertext, nonce = cipher.iv.copyOf())
    }

    override fun decrypt(ciphertext: ByteArray, nonce: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKeyProvider(), GCMParameterSpec(TAG_BITS, nonce))
        return cipher.doFinal(ciphertext)
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
    }
}
