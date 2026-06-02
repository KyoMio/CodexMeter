package com.kmnexus.codexmeter.providers.codex.auth

import com.kmnexus.codexmeter.data.secure.ProviderSessionEnvelope
import com.kmnexus.codexmeter.domain.model.LocalAccountId
import com.kmnexus.codexmeter.domain.model.ProviderAccount
import com.kmnexus.codexmeter.domain.model.ProviderAccountId
import com.kmnexus.codexmeter.domain.quota.QuotaSnapshot
import com.kmnexus.codexmeter.domain.quota.QuotaSnapshotSource
import com.kmnexus.codexmeter.domain.refresh.QuotaError
import com.kmnexus.codexmeter.providers.codex.dto.CodexCreditsDto
import com.kmnexus.codexmeter.providers.codex.dto.CodexRateLimitDto
import com.kmnexus.codexmeter.providers.codex.dto.CodexUsageResponseDto
import com.kmnexus.codexmeter.providers.codex.dto.CodexWindowDto
import com.kmnexus.codexmeter.providers.codex.mapper.CodexUsageMapper
import com.kmnexus.codexmeter.providers.codex.network.CodexUsageClient
import com.kmnexus.codexmeter.providers.codex.session.CodexSessionPayload
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexSessionImporterTest {
    private val now = Instant.parse("2026-05-28T08:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    @Test
    fun `prepare validates device code session without saving raw session`() = runTest {
        val persistence = RecordingPersistence()
        val importer = importer(
            usageClient = RecordingUsageClient(successUsage()),
            persistence = persistence,
        )

        val result = importer.prepareDeviceCodeSession(session())

        assertTrue(result is CodexSessionImporter.PrepareResult.Success)
        val prepared = (result as CodexSessionImporter.PrepareResult.Success).preparedImport
        assertEquals("codex@example.test", prepared.account.displayName)
        assertEquals(ProviderAccountId("acct-123"), prepared.account.providerAccountId)
        assertEquals(QuotaSnapshotSource.DeviceCodeLogin, prepared.snapshot.source)
        assertEquals(0, persistence.saved.size)
    }

    @Test
    fun `commit prepared device code session saves sanitized account session and snapshot`() = runTest {
        val persistence = RecordingPersistence()
        val importer = importer(
            usageClient = RecordingUsageClient(successUsage()),
            persistence = persistence,
        )
        val prepared = (
            importer.prepareDeviceCodeSession(session()) as CodexSessionImporter.PrepareResult.Success
            ).preparedImport

        val result = importer.commitPreparedDeviceCodeSession(prepared)

        assertTrue(result is CodexSessionImporter.Result.Success)
        val success = result as CodexSessionImporter.Result.Success
        assertEquals("codex@example.test", success.account.displayName)
        assertEquals(prepared.snapshot.snapshotId, success.snapshot.snapshotId)
        assertEquals(1, persistence.saved.size)
    }

    @Test
    fun `usage validation failure does not prepare a saved import`() = runTest {
        val persistence = RecordingPersistence()
        val importer = importer(
            usageClient = RecordingUsageClient(CodexUsageClient.Result.Failure(QuotaError.Network("validation_failed"))),
            persistence = persistence,
        )

        val result = importer.prepareDeviceCodeSession(session())

        assertTrue(result is CodexSessionImporter.PrepareResult.Failure)
        val failure = result as CodexSessionImporter.PrepareResult.Failure
        assertEquals("error_network", failure.message)
        assertEquals(0, persistence.saved.size)
    }

    @Test
    fun `commit failure returns safe persistence error`() = runTest {
        val importer = importer(
            usageClient = RecordingUsageClient(successUsage()),
            persistence = ThrowingPersistence(),
        )
        val prepared = (
            importer.prepareDeviceCodeSession(session()) as CodexSessionImporter.PrepareResult.Success
            ).preparedImport

        val result = importer.commitPreparedDeviceCodeSession(prepared)

        assertEquals(
            CodexSessionImporter.Result.Failure(
                message = "error_session_persistence",
                quotaError = null,
            ),
            result,
        )
    }

    private fun importer(
        usageClient: CodexSessionImporter.UsageClient,
        persistence: CodexSessionImporter.ImportPersistence,
    ): CodexSessionImporter =
        CodexSessionImporter(
            usageClient = usageClient,
            mapper = CodexUsageMapper(),
            importPersistence = persistence,
            sessionEnvelopeFactory = RecordingEnvelopeFactory(),
            localAccountIdProvider = CodexSessionImporter.LocalAccountIdProvider {
                LocalAccountId("local-123")
            },
            defaultDisplayName = "Codex account",
            clock = clock,
        )

    private fun session(): CodexSessionPayload =
        CodexSessionPayload(
            accessToken = "access-token",
            refreshToken = "refresh-token",
            idToken = "id-token",
            accountId = "acct-123",
            accountEmail = "codex@example.test",
            lastRefresh = now,
        )

    private fun successUsage(): CodexUsageClient.Result.Success =
        CodexUsageClient.Result.Success(
            CodexUsageResponseDto(
                planType = "plus",
                rateLimit = CodexRateLimitDto(
                    primaryWindow = CodexWindowDto(
                        usedPercent = 42,
                        resetAt = Instant.parse("2026-05-28T10:00:00Z").epochSecond,
                        limitWindowSeconds = 18_000,
                    ),
                    secondaryWindow = CodexWindowDto(
                        usedPercent = 18,
                        resetAt = Instant.parse("2026-06-01T00:00:00Z").epochSecond,
                        limitWindowSeconds = 604_800,
                    ),
                ),
                credits = CodexCreditsDto(
                    hasCredits = true,
                    unlimited = false,
                    balance = 12.0,
                ),
            ),
        )

    private class RecordingUsageClient(
        private val result: CodexUsageClient.Result,
    ) : CodexSessionImporter.UsageClient {
        val calls = mutableListOf<Pair<String, String?>>()

        override suspend fun fetchUsage(
            accessToken: String,
            accountId: String?,
        ): CodexUsageClient.Result {
            calls += accessToken to accountId
            return result
        }
    }

    private class RecordingPersistence : CodexSessionImporter.ImportPersistence {
        val saved = mutableListOf<CodexSessionImporter.CommittedImport>()

        override suspend fun save(
            account: ProviderAccount,
            sessionEnvelope: ProviderSessionEnvelope,
            snapshot: QuotaSnapshot,
        ): CodexSessionImporter.CommittedImport {
            val committed = CodexSessionImporter.CommittedImport(
                account = account,
                sessionEnvelope = sessionEnvelope,
                snapshot = snapshot,
            )
            saved += committed
            return committed
        }
    }

    private class ThrowingPersistence : CodexSessionImporter.ImportPersistence {
        override suspend fun save(
            account: ProviderAccount,
            sessionEnvelope: ProviderSessionEnvelope,
            snapshot: QuotaSnapshot,
        ): CodexSessionImporter.CommittedImport {
            throw IllegalStateException("unsafe persistence detail")
        }
    }

    private class RecordingEnvelopeFactory : CodexSessionImporter.SessionEnvelopeFactory {
        override fun create(
            payload: CodexSessionPayload,
            localAccountId: LocalAccountId,
            providerAccountId: ProviderAccountId?,
            now: Instant,
        ): ProviderSessionEnvelope =
            ProviderSessionEnvelope(
                providerId = "codex",
                localAccountId = localAccountId.value,
                providerAccountId = providerAccountId?.value,
                schemaVersion = 1,
                payloadCiphertext = byteArrayOf(1, 2, 3),
                payloadNonce = byteArrayOf(4, 5, 6),
                createdAt = now.toString(),
                updatedAt = now.toString(),
            )
    }
}
