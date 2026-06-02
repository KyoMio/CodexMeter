package com.kmnexus.codexmeter.data.secure

import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
// Robolectric SDK 36 currently requires Java 21, while this project is pinned to Java 17.
@Config(sdk = [35])
class FileSecureSessionStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `corrupt envelope file loads as missing session`() = runTest {
        val directory = temporaryFolder.newFolder()
        val store = FileSecureSessionStore(directory = directory)
        store.save(envelope())
        directory.listFiles()!!.single().writeText("{")

        val loaded = store.load(providerId = "codex", localAccountId = "local")

        assertNull(loaded)
    }

    @Test
    fun `load recovers last committed envelope from atomic backup file`() = runTest {
        val directory = temporaryFolder.newFolder()
        val store = FileSecureSessionStore(directory = directory)
        val expected = envelope()
        store.save(expected)
        val savedFile = directory.listFiles()!!.single()
        val backupFile = File("${savedFile.path}.bak")
        assertTrue(savedFile.renameTo(backupFile))

        val loaded = store.load(providerId = "codex", localAccountId = "local")

        assertEnvelopeEquals(expected, loaded!!)
        assertTrue(savedFile.exists())
    }

    @Test
    fun `save uses a hashed storage name without raw account identifiers`() = runTest {
        val directory = temporaryFolder.newFolder()
        val store = FileSecureSessionStore(directory = directory)

        store.save(envelope())

        val savedFile = directory.listFiles()!!.single()
        assertTrue(savedFile.name.endsWith(".json"))
        assertEquals(false, savedFile.name.contains("codex"))
        assertEquals(false, savedFile.name.contains("local"))
    }

    private fun envelope(): ProviderSessionEnvelope =
        ProviderSessionEnvelope(
            providerId = "codex",
            localAccountId = "local",
            providerAccountId = "acct",
            schemaVersion = 1,
            payloadCiphertext = byteArrayOf(1, 2, 3),
            payloadNonce = byteArrayOf(4, 5, 6),
            createdAt = "2026-05-23T12:00:00Z",
            updatedAt = "2026-05-23T12:00:00Z",
        )

    private fun assertEnvelopeEquals(
        expected: ProviderSessionEnvelope,
        actual: ProviderSessionEnvelope,
    ) {
        assertEquals(expected.providerId, actual.providerId)
        assertEquals(expected.localAccountId, actual.localAccountId)
        assertEquals(expected.providerAccountId, actual.providerAccountId)
        assertEquals(expected.schemaVersion, actual.schemaVersion)
        assertArrayEquals(expected.payloadCiphertext, actual.payloadCiphertext)
        assertArrayEquals(expected.payloadNonce, actual.payloadNonce)
        assertEquals(expected.createdAt, actual.createdAt)
        assertEquals(expected.updatedAt, actual.updatedAt)
    }
}
