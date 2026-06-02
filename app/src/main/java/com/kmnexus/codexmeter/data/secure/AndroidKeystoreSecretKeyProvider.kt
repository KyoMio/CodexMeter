package com.kmnexus.codexmeter.data.secure

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Supplies an AES-256 [SecretKey] held in the Android Keystore, creating it on first use.
 *
 * Used by [AesGcmPayloadCipher] in production so provider credentials are encrypted with a
 * hardware-backed key that never leaves the Keystore.
 */
class AndroidKeystoreSecretKeyProvider(
    private val keyAlias: String = DEFAULT_KEY_ALIAS,
) : () -> SecretKey {
    override fun invoke(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry
        if (existing != null) {
            return existing.secretKey
        }
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        keyGenerator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return keyGenerator.generateKey()
    }

    private companion object {
        const val DEFAULT_KEY_ALIAS = "codexmeter_provider_session_key"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }
}
