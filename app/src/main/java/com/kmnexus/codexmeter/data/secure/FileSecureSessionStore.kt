package com.kmnexus.codexmeter.data.secure

import android.util.AtomicFile
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class FileSecureSessionStore(
    private val directory: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val json: Json = defaultJson,
) : SecureSessionStore {
    override suspend fun save(envelope: ProviderSessionEnvelope) {
        withContext(ioDispatcher) {
            directory.mkdirs()
            val target = fileFor(envelope.providerId, envelope.localAccountId)
            AtomicFile(target).writeUtf8(json.encodeToString(envelope.toStored()))
        }
    }

    override suspend fun load(
        providerId: String,
        localAccountId: String,
    ): ProviderSessionEnvelope? =
        withContext(ioDispatcher) {
            val envelopeFile = AtomicFile(fileFor(providerId, localAccountId))
            runCatching {
                val content = envelopeFile.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
                json.decodeFromString<StoredProviderSessionEnvelope>(content).toDomain()
            }.getOrNull()
        }

    override suspend fun delete(
        providerId: String,
        localAccountId: String,
    ) {
        withContext(ioDispatcher) {
            AtomicFile(fileFor(providerId, localAccountId)).delete()
        }
    }

    private fun fileFor(
        providerId: String,
        localAccountId: String,
    ): File =
        File(directory, "${storageKey(providerId, localAccountId)}.json")

    private fun storageKey(
        providerId: String,
        localAccountId: String,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$providerId:$localAccountId".toByteArray())
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    private fun ProviderSessionEnvelope.toStored(): StoredProviderSessionEnvelope =
        StoredProviderSessionEnvelope(
            providerId = providerId,
            localAccountId = localAccountId,
            providerAccountId = providerAccountId,
            schemaVersion = schemaVersion,
            payloadCiphertext = Base64.getEncoder().encodeToString(payloadCiphertext),
            payloadNonce = Base64.getEncoder().encodeToString(payloadNonce),
            createdAt = createdAt,
            updatedAt = updatedAt,
        )

    private fun StoredProviderSessionEnvelope.toDomain(): ProviderSessionEnvelope =
        ProviderSessionEnvelope(
            providerId = providerId,
            localAccountId = localAccountId,
            providerAccountId = providerAccountId,
            schemaVersion = schemaVersion,
            payloadCiphertext = Base64.getDecoder().decode(payloadCiphertext),
            payloadNonce = Base64.getDecoder().decode(payloadNonce),
            createdAt = createdAt,
            updatedAt = updatedAt,
        )

    private companion object {
        val defaultJson = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}

private fun AtomicFile.writeUtf8(content: String) {
    val stream = startWrite()
    try {
        stream.write(content.toByteArray(Charsets.UTF_8))
        finishWrite(stream)
    } catch (throwable: Throwable) {
        failWrite(stream)
        throw throwable
    }
}

@Serializable
private data class StoredProviderSessionEnvelope(
    val providerId: String,
    val localAccountId: String,
    val providerAccountId: String?,
    val schemaVersion: Int,
    val payloadCiphertext: String,
    val payloadNonce: String,
    val createdAt: String,
    val updatedAt: String,
)
