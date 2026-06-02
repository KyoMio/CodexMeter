package com.kmnexus.codexmeter.providers.codex.session

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.kmnexus.codexmeter.data.secure.ProviderSessionEnvelope
import com.kmnexus.codexmeter.providers.codex.CodexSessionCipher
import java.security.KeyStore
import java.time.Instant
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class AndroidKeystoreCodexSessionCipher(
    private val keyAlias: String = DEFAULT_KEY_ALIAS,
    private val json: Json = defaultJson,
) : CodexSessionCipher {
    override fun decrypt(envelope: ProviderSessionEnvelope): Result<CodexSessionPayload> =
        runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), javax.crypto.spec.GCMParameterSpec(TAG_BITS, envelope.payloadNonce))
            val plaintext = cipher.doFinal(envelope.payloadCiphertext).decodeToString()
            json.decodeFromString<StoredCodexSessionPayload>(plaintext).toDomain()
        }.recoverCatching {
            throw IllegalStateException("Unable to decrypt Codex session.")
        }

    override fun encrypt(
        session: CodexSessionPayload,
        envelope: ProviderSessionEnvelope,
        updatedAt: Instant,
    ): ProviderSessionEnvelope {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val plaintext = json.encodeToString(session.toStored()).toByteArray()
        return envelope.copy(
            schemaVersion = CODEX_SESSION_SCHEMA_VERSION,
            payloadCiphertext = cipher.doFinal(plaintext),
            payloadNonce = cipher.iv.copyOf(),
            updatedAt = updatedAt.toString(),
        )
    }

    private fun secretKey(): SecretKey {
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

    private fun CodexSessionPayload.toStored(): StoredCodexSessionPayload =
        StoredCodexSessionPayload(
            accessToken = accessToken,
            refreshToken = refreshToken,
            idToken = idToken,
            accountId = accountId,
            accountEmail = accountEmail,
            lastRefresh = lastRefresh?.toString(),
        )

    private fun StoredCodexSessionPayload.toDomain(): CodexSessionPayload =
        CodexSessionPayload(
            accessToken = accessToken,
            refreshToken = refreshToken,
            idToken = idToken,
            accountId = accountId,
            accountEmail = accountEmail,
            lastRefresh = lastRefresh?.let(Instant::parse),
        )

    private companion object {
        const val CODEX_SESSION_SCHEMA_VERSION = 1
        const val DEFAULT_KEY_ALIAS = "codexmeter_codex_session_key"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128

        val defaultJson = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}

@Serializable
private data class StoredCodexSessionPayload(
    val accessToken: String,
    val refreshToken: String,
    val idToken: String?,
    val accountId: String?,
    val accountEmail: String? = null,
    val lastRefresh: String?,
)
