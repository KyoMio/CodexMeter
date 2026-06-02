package com.kmnexus.codexmeter.providers.codex.auth

import com.kmnexus.codexmeter.data.secure.ProviderSessionEnvelope
import com.kmnexus.codexmeter.domain.model.LocalAccountId
import com.kmnexus.codexmeter.domain.model.ProviderAccount
import com.kmnexus.codexmeter.domain.model.ProviderAccountId
import com.kmnexus.codexmeter.domain.model.ProviderId
import com.kmnexus.codexmeter.domain.model.QuotaWindowId
import com.kmnexus.codexmeter.domain.model.SnapshotId
import com.kmnexus.codexmeter.domain.quota.QuotaSnapshot
import com.kmnexus.codexmeter.domain.quota.QuotaSnapshotSource
import com.kmnexus.codexmeter.domain.quota.QuotaWindow
import com.kmnexus.codexmeter.domain.quota.QuotaWindowAvailability
import com.kmnexus.codexmeter.domain.refresh.QuotaError
import com.kmnexus.codexmeter.providers.codex.session.CodexSessionPayload
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexDeviceCodeLoginUseCaseTest {
    private val clock = Clock.fixed(NOW, ZoneOffset.UTC)

    @Test
    fun `start login success creates awaiting state and redacts device auth id`() = runTest {
        val deviceClient = RecordingDeviceCodeClient(
            requestResults = ArrayDeque(listOf(CodexDeviceCodeClient.Result.Success(challenge(deviceAuthId = "raw-device-auth-id")))),
        )
        val useCase = newUseCase(deviceClient = deviceClient)

        val state = useCase.startLogin()

        assertTrue(state is CodexDeviceCodeLoginState.AwaitingUserAuthorization)
        val awaiting = state as CodexDeviceCodeLoginState.AwaitingUserAuthorization
        assertEquals(DeviceCodeLoginAttemptId("attempt-1"), awaiting.attemptId)
        assertEquals("ABCD-EFGH", awaiting.userCode)
        assertEquals("https://auth.openai.com/codex/device", awaiting.verificationUri)
        assertEquals(5, awaiting.pollIntervalSeconds)
        assertEquals(NOW.plusSeconds(900), awaiting.expiresAt)
        assertFalse(awaiting.toString().contains("ABCD-EFGH"))
        assertFalse(awaiting.toString().contains("raw-device-auth-id"))
    }

    @Test
    fun `start login failure maps safe failure`() = runTest {
        val deviceClient = RecordingDeviceCodeClient(
            requestResults = ArrayDeque(
                listOf(CodexDeviceCodeClient.Result.Failure(QuotaError.Network("codex_device_code_network_error"))),
            ),
        )
        val useCase = newUseCase(deviceClient = deviceClient)

        val state = useCase.startLogin()

        assertEquals(
            CodexDeviceCodeLoginState.Failed(
                attemptId = DeviceCodeLoginAttemptId("attempt-1"),
                safeMessageKey = "error_network",
            ),
            state,
        )
    }

    @Test
    fun `new attempt makes older delayed device code result stale`() = runTest {
        val firstResult = CompletableDeferred<CodexDeviceCodeClient.Result<DeviceCodeChallenge>>()
        val secondResult = CompletableDeferred<CodexDeviceCodeClient.Result<DeviceCodeChallenge>>(
            CodexDeviceCodeClient.Result.Success(challenge(userCode = "WXYZ-1234", deviceAuthId = "second-device-auth")),
        )
        val deviceClient = DeferredDeviceCodeClient(ArrayDeque(listOf(firstResult, secondResult)))
        val useCase = newUseCase(deviceClient = deviceClient)

        val firstStart = async { useCase.startLogin() }
        yield()
        val secondState = useCase.startLogin()
        firstResult.complete(CodexDeviceCodeClient.Result.Success(challenge(userCode = "ABCD-EFGH", deviceAuthId = "first-device-auth")))
        val firstState = firstStart.await()

        assertTrue(secondState is CodexDeviceCodeLoginState.AwaitingUserAuthorization)
        assertEquals("WXYZ-1234", (secondState as CodexDeviceCodeLoginState.AwaitingUserAuthorization).userCode)
        assertEquals(secondState, useCase.currentState)
        assertEquals(secondState, firstState)
    }

    @Test
    fun `pending poll keeps awaiting state and does not exchange or import`() = runTest {
        val deviceClient = RecordingDeviceCodeClient(
            requestResults = ArrayDeque(listOf(CodexDeviceCodeClient.Result.Success(challenge()))),
            pollResults = ArrayDeque(listOf(CodexDeviceCodeClient.Result.Pending)),
        )
        val tokenExchanger = RecordingTokenExchanger(CodexOAuthTokenExchanger.Result.Success(session()))
        val sessionImporter = RecordingSessionImporter(importSuccess(source = QuotaSnapshotSource.DeviceCodeLogin))
        val useCase = newUseCase(
            deviceClient = deviceClient,
            tokenExchanger = tokenExchanger,
            sessionImporter = sessionImporter,
        )

        useCase.startLogin()
        val state = useCase.pollLatest()

        assertTrue(state is CodexDeviceCodeLoginState.AwaitingUserAuthorization)
        assertEquals(1, deviceClient.pollChallenges.size)
        assertTrue(tokenExchanger.requests.isEmpty())
        assertTrue(sessionImporter.sessions.isEmpty())
        assertTrue(sessionImporter.committedImports.isEmpty())
    }

    @Test
    fun `transient network failure while polling keeps awaiting state for next poll`() = runTest {
        val deviceClient = RecordingDeviceCodeClient(
            requestResults = ArrayDeque(listOf(CodexDeviceCodeClient.Result.Success(challenge()))),
            pollResults = ArrayDeque(
                listOf(
                    CodexDeviceCodeClient.Result.Failure(
                        QuotaError.Network("codex_device_authorization_network_error"),
                    ),
                    CodexDeviceCodeClient.Result.Success(DeviceCodeAuthorization("authorization-code", "code-verifier")),
                ),
            ),
        )
        val tokenExchanger = RecordingTokenExchanger(CodexOAuthTokenExchanger.Result.Success(session()))
        val sessionImporter = RecordingSessionImporter(importSuccess(source = QuotaSnapshotSource.DeviceCodeLogin))
        val useCase = newUseCase(
            deviceClient = deviceClient,
            tokenExchanger = tokenExchanger,
            sessionImporter = sessionImporter,
        )

        useCase.startLogin()
        val firstPoll = useCase.pollLatest()
        val secondPoll = useCase.pollLatest()

        assertTrue(firstPoll is CodexDeviceCodeLoginState.AwaitingUserAuthorization)
        assertTrue(secondPoll is CodexDeviceCodeLoginState.Saved)
        assertEquals(2, deviceClient.pollChallenges.size)
        assertEquals(1, tokenExchanger.requests.size)
        assertEquals(1, sessionImporter.committedImports.size)
    }

    @Test
    fun `expired attempt stops before network poll`() = runTest {
        val useCase = newUseCase(
            deviceClient = RecordingDeviceCodeClient(
                requestResults = ArrayDeque(listOf(CodexDeviceCodeClient.Result.Success(challenge(expiresInSeconds = 0)))),
            ),
        )

        useCase.startLogin()
        val state = useCase.pollLatest()

        assertEquals(CodexDeviceCodeLoginState.Expired(DeviceCodeLoginAttemptId("attempt-1")), state)
    }

    @Test
    fun `authorized poll exchanges token and imports device code session`() = runTest {
        val deviceClient = RecordingDeviceCodeClient(
            requestResults = ArrayDeque(listOf(CodexDeviceCodeClient.Result.Success(challenge()))),
            pollResults = ArrayDeque(
                listOf(CodexDeviceCodeClient.Result.Success(DeviceCodeAuthorization("authorization-code", "code-verifier"))),
            ),
        )
        val tokenExchanger = RecordingTokenExchanger(CodexOAuthTokenExchanger.Result.Success(session(accountId = "provider-account")))
        val sessionImporter = RecordingSessionImporter(importSuccess(source = QuotaSnapshotSource.DeviceCodeLogin))
        val useCase = newUseCase(
            deviceClient = deviceClient,
            tokenExchanger = tokenExchanger,
            sessionImporter = sessionImporter,
        )

        useCase.startLogin()
        val state = useCase.pollLatest()

        assertTrue(state is CodexDeviceCodeLoginState.Saved)
        val saved = state as CodexDeviceCodeLoginState.Saved
        assertEquals(LocalAccountId("device-local"), saved.account.localAccountId)
        assertEquals(QuotaSnapshotSource.DeviceCodeLogin, saved.snapshot.source)
        assertEquals(listOf("authorization-code"), tokenExchanger.requests.map { it.authorizationCode.value })
        assertEquals(listOf("code-verifier"), tokenExchanger.requests.map { it.pkceVerifier.value })
        assertEquals(listOf(CodexOAuthConfig.DEVICE_REDIRECT_URI), tokenExchanger.requests.map { it.redirectUri })
        assertEquals(listOf(session(accountId = "provider-account")), sessionImporter.sessions)
        assertEquals(1, sessionImporter.committedImports.size)
        assertFalse(state.toString().contains("authorization-code"))
        assertFalse(state.toString().contains("code-verifier"))
    }

    @Test
    fun `token exchange failure does not import session`() = runTest {
        val deviceClient = RecordingDeviceCodeClient(
            requestResults = ArrayDeque(listOf(CodexDeviceCodeClient.Result.Success(challenge()))),
            pollResults = ArrayDeque(
                listOf(CodexDeviceCodeClient.Result.Success(DeviceCodeAuthorization("authorization-code", "code-verifier"))),
            ),
        )
        val sessionImporter = RecordingSessionImporter(importSuccess(source = QuotaSnapshotSource.DeviceCodeLogin))
        val useCase = newUseCase(
            deviceClient = deviceClient,
            tokenExchanger = RecordingTokenExchanger(
                CodexOAuthTokenExchanger.Result.Failure(QuotaError.AuthRequired(400, "codex_oauth_auth_required_invalid_grant")),
            ),
            sessionImporter = sessionImporter,
        )

        useCase.startLogin()
        val state = useCase.pollLatest()

        assertEquals(
            CodexDeviceCodeLoginState.Failed(DeviceCodeLoginAttemptId("attempt-1"), "error_auth_required"),
            state,
        )
        assertTrue(sessionImporter.sessions.isEmpty())
        assertTrue(sessionImporter.committedImports.isEmpty())
    }

    @Test
    fun `usage validation failure exposes retryable validation failure and does not commit`() = runTest {
        val deviceClient = RecordingDeviceCodeClient(
            requestResults = ArrayDeque(listOf(CodexDeviceCodeClient.Result.Success(challenge()))),
            pollResults = ArrayDeque(
                listOf(CodexDeviceCodeClient.Result.Success(DeviceCodeAuthorization("authorization-code", "code-verifier"))),
            ),
        )
        val sessionImporter = RecordingSessionImporter(CodexSessionImporter.Result.Failure("error_network", QuotaError.Network("validation_failed")))
        val useCase = newUseCase(
            deviceClient = deviceClient,
            sessionImporter = sessionImporter,
        )

        useCase.startLogin()
        val state = useCase.pollLatest()

        assertEquals(CodexDeviceCodeLoginState.ValidationFailed(DeviceCodeLoginAttemptId("attempt-1"), "error_network"), state)
        assertEquals(listOf(session()), sessionImporter.sessions)
        assertTrue(sessionImporter.committedImports.isEmpty())
    }

    @Test
    fun `usage validation failure can retry validation with exchanged session`() = runTest {
        val deviceClient = RecordingDeviceCodeClient(
            requestResults = ArrayDeque(listOf(CodexDeviceCodeClient.Result.Success(challenge()))),
            pollResults = ArrayDeque(
                listOf(CodexDeviceCodeClient.Result.Success(DeviceCodeAuthorization("authorization-code", "code-verifier"))),
            ),
        )
        val sessionImporter = RetryingSessionImporter(
            prepareResults = ArrayDeque(
                listOf(
                    CodexSessionImporter.PrepareResult.Failure("error_network", QuotaError.Network("validation_failed")),
                    CodexSessionImporter.PrepareResult.Success(importSuccess(QuotaSnapshotSource.DeviceCodeLogin).toPreparedImport()),
                ),
            ),
            commitResult = importSuccess(QuotaSnapshotSource.DeviceCodeLogin),
        )
        val useCase = newUseCase(
            deviceClient = deviceClient,
            sessionImporter = sessionImporter,
        )

        useCase.startLogin()
        val firstState = useCase.pollLatest()
        val retryState = useCase.retryValidation()

        assertEquals(CodexDeviceCodeLoginState.ValidationFailed(DeviceCodeLoginAttemptId("attempt-1"), "error_network"), firstState)
        assertTrue(retryState is CodexDeviceCodeLoginState.Saved)
        assertEquals(listOf(session(), session()), sessionImporter.sessions)
        assertEquals(1, sessionImporter.committedImports.size)
    }

    @Test
    fun `new attempt during validation makes older prepared result stale and prevents commit`() = runTest {
        val firstPrepare = CompletableDeferred<CodexSessionImporter.PrepareResult>()
        val sessionImporter = DeferredPrepareSessionImporter(firstPrepare)
        val useCase = newUseCase(
            deviceClient = RecordingDeviceCodeClient(
                requestResults = ArrayDeque(
                    listOf(
                        CodexDeviceCodeClient.Result.Success(challenge(userCode = "FIRST-CODE")),
                        CodexDeviceCodeClient.Result.Success(challenge(userCode = "SECOND-CODE")),
                    ),
                ),
                pollResults = ArrayDeque(
                    listOf(CodexDeviceCodeClient.Result.Success(DeviceCodeAuthorization("authorization-code", "code-verifier"))),
                ),
            ),
            sessionImporter = sessionImporter,
        )

        useCase.startLogin()
        val firstPoll = async { useCase.pollLatest() }
        yield()
        val secondState = useCase.startLogin()
        firstPrepare.complete(CodexSessionImporter.PrepareResult.Success(importSuccess(QuotaSnapshotSource.DeviceCodeLogin).toPreparedImport()))
        val staleState = firstPoll.await()

        assertTrue(secondState is CodexDeviceCodeLoginState.AwaitingUserAuthorization)
        assertEquals(secondState, staleState)
        assertTrue(sessionImporter.committedImports.isEmpty())
    }

    @Test
    fun `overlapping polls for same attempt do not duplicate exchange or commit`() = runTest {
        val pollResult = CompletableDeferred<CodexDeviceCodeClient.Result<DeviceCodeAuthorization>>()
        val deviceClient = DeferredPollDeviceCodeClient(pollResult)
        val tokenExchanger = RecordingTokenExchanger(CodexOAuthTokenExchanger.Result.Success(session()))
        val sessionImporter = RecordingSessionImporter(importSuccess(source = QuotaSnapshotSource.DeviceCodeLogin))
        val useCase = newUseCase(
            deviceClient = deviceClient,
            tokenExchanger = tokenExchanger,
            sessionImporter = sessionImporter,
        )

        useCase.startLogin()
        val firstPoll = async { useCase.pollLatest() }
        yield()
        val secondPoll = async { useCase.pollLatest() }
        yield()
        pollResult.complete(CodexDeviceCodeClient.Result.Success(DeviceCodeAuthorization("authorization-code", "code-verifier")))

        val secondState = secondPoll.await()
        val firstState = firstPoll.await()

        assertTrue(firstState is CodexDeviceCodeLoginState.Saved)
        assertTrue(secondState is CodexDeviceCodeLoginState.PollingAuthorization)
        assertEquals(1, deviceClient.pollCount)
        assertEquals(1, tokenExchanger.requests.size)
        assertEquals(1, sessionImporter.sessions.size)
        assertEquals(1, sessionImporter.committedImports.size)
    }

    @Test
    fun `relogin account mismatch asks for decision before importing`() = runTest {
        val deviceClient = RecordingDeviceCodeClient(
            requestResults = ArrayDeque(listOf(CodexDeviceCodeClient.Result.Success(challenge()))),
            pollResults = ArrayDeque(
                listOf(CodexDeviceCodeClient.Result.Success(DeviceCodeAuthorization("authorization-code", "code-verifier"))),
            ),
        )
        val sessionImporter = RecordingSessionImporter(importSuccess(source = QuotaSnapshotSource.DeviceCodeLogin))
        val useCase = newUseCase(
            deviceClient = deviceClient,
            tokenExchanger = RecordingTokenExchanger(CodexOAuthTokenExchanger.Result.Success(session(accountId = "actual-account"))),
            sessionImporter = sessionImporter,
        )

        useCase.startLogin(CodexDeviceCodeLoginMode.Relogin(expectedProviderAccountId = ProviderAccountId("expected-account")))
        val state = useCase.pollLatest()

        assertEquals(
            CodexDeviceCodeLoginState.AccountMismatchDecision(
                attemptId = DeviceCodeLoginAttemptId("attempt-1"),
                expectedProviderAccountId = ProviderAccountId("expected-account"),
                actualProviderAccountId = ProviderAccountId("actual-account"),
            ),
            state,
        )
        assertTrue(sessionImporter.sessions.isEmpty())
        assertTrue(sessionImporter.committedImports.isEmpty())
    }

    @Test
    fun `confirming account mismatch imports the signed-in account as a new account`() = runTest {
        val deviceClient = RecordingDeviceCodeClient(
            requestResults = ArrayDeque(listOf(CodexDeviceCodeClient.Result.Success(challenge()))),
            pollResults = ArrayDeque(
                listOf(CodexDeviceCodeClient.Result.Success(DeviceCodeAuthorization("authorization-code", "code-verifier"))),
            ),
        )
        val sessionImporter = RecordingSessionImporter(importSuccess(source = QuotaSnapshotSource.DeviceCodeLogin))
        val useCase = newUseCase(
            deviceClient = deviceClient,
            tokenExchanger = RecordingTokenExchanger(CodexOAuthTokenExchanger.Result.Success(session(accountId = "actual-account"))),
            sessionImporter = sessionImporter,
        )

        useCase.startLogin(CodexDeviceCodeLoginMode.Relogin(expectedProviderAccountId = ProviderAccountId("expected-account")))
        useCase.pollLatest()
        val state = useCase.confirmAddAccountFromMismatch()

        assertTrue(state is CodexDeviceCodeLoginState.Saved)
        assertEquals(1, sessionImporter.committedImports.size)
    }

    @Test
    fun `cancelling an account mismatch discards the session without importing`() = runTest {
        val deviceClient = RecordingDeviceCodeClient(
            requestResults = ArrayDeque(listOf(CodexDeviceCodeClient.Result.Success(challenge()))),
            pollResults = ArrayDeque(
                listOf(CodexDeviceCodeClient.Result.Success(DeviceCodeAuthorization("authorization-code", "code-verifier"))),
            ),
        )
        val sessionImporter = RecordingSessionImporter(importSuccess(source = QuotaSnapshotSource.DeviceCodeLogin))
        val useCase = newUseCase(
            deviceClient = deviceClient,
            tokenExchanger = RecordingTokenExchanger(CodexOAuthTokenExchanger.Result.Success(session(accountId = "actual-account"))),
            sessionImporter = sessionImporter,
        )

        useCase.startLogin(CodexDeviceCodeLoginMode.Relogin(expectedProviderAccountId = ProviderAccountId("expected-account")))
        useCase.pollLatest()
        val state = useCase.cancelLatest()

        assertTrue(state is CodexDeviceCodeLoginState.Cancelled)
        assertTrue(sessionImporter.committedImports.isEmpty())
    }

    private fun newUseCase(
        deviceClient: CodexDeviceCodeLoginUseCase.DeviceCodeClient = RecordingDeviceCodeClient(
            requestResults = ArrayDeque(listOf(CodexDeviceCodeClient.Result.Success(challenge()))),
        ),
        tokenExchanger: CodexDeviceCodeLoginUseCase.TokenExchanger = RecordingTokenExchanger(
            CodexOAuthTokenExchanger.Result.Success(session()),
        ),
        sessionImporter: CodexDeviceCodeLoginUseCase.SessionImporter = RecordingSessionImporter(
            importSuccess(source = QuotaSnapshotSource.DeviceCodeLogin),
        ),
    ): CodexDeviceCodeLoginUseCase =
        CodexDeviceCodeLoginUseCase(
            deviceCodeClient = deviceClient,
            tokenExchanger = tokenExchanger,
            sessionImporter = sessionImporter,
            attemptIdProvider = AttemptIdProvider(),
            clock = clock,
        )

    private class AttemptIdProvider : () -> DeviceCodeLoginAttemptId {
        private var next = 1
        override fun invoke(): DeviceCodeLoginAttemptId = DeviceCodeLoginAttemptId("attempt-${next++}")
    }

    private class RecordingDeviceCodeClient(
        private val requestResults: ArrayDeque<CodexDeviceCodeClient.Result<DeviceCodeChallenge>> = ArrayDeque(),
        private val pollResults: ArrayDeque<CodexDeviceCodeClient.Result<DeviceCodeAuthorization>> = ArrayDeque(),
    ) : CodexDeviceCodeLoginUseCase.DeviceCodeClient {
        val pollChallenges = mutableListOf<DeviceCodeChallenge>()
        override suspend fun requestDeviceCode(): CodexDeviceCodeClient.Result<DeviceCodeChallenge> = requestResults.removeFirst()
        override suspend fun pollAuthorization(challenge: DeviceCodeChallenge): CodexDeviceCodeClient.Result<DeviceCodeAuthorization> {
            pollChallenges += challenge
            return pollResults.removeFirst()
        }
    }

    private class DeferredDeviceCodeClient(
        private val requestResults: ArrayDeque<CompletableDeferred<CodexDeviceCodeClient.Result<DeviceCodeChallenge>>>,
    ) : CodexDeviceCodeLoginUseCase.DeviceCodeClient {
        override suspend fun requestDeviceCode(): CodexDeviceCodeClient.Result<DeviceCodeChallenge> = requestResults.removeFirst().await()
        override suspend fun pollAuthorization(challenge: DeviceCodeChallenge): CodexDeviceCodeClient.Result<DeviceCodeAuthorization> =
            error("poll should not be called")
    }

    private class DeferredPollDeviceCodeClient(
        private val pollResult: CompletableDeferred<CodexDeviceCodeClient.Result<DeviceCodeAuthorization>>,
    ) : CodexDeviceCodeLoginUseCase.DeviceCodeClient {
        var pollCount = 0
            private set

        override suspend fun requestDeviceCode(): CodexDeviceCodeClient.Result<DeviceCodeChallenge> =
            CodexDeviceCodeClient.Result.Success(challenge())

        override suspend fun pollAuthorization(challenge: DeviceCodeChallenge): CodexDeviceCodeClient.Result<DeviceCodeAuthorization> {
            pollCount += 1
            return pollResult.await()
        }
    }

    private class RecordingTokenExchanger(
        private val result: CodexOAuthTokenExchanger.Result,
    ) : CodexDeviceCodeLoginUseCase.TokenExchanger {
        val requests = mutableListOf<CodexOAuthTokenExchangeRequest>()
        override suspend fun exchange(request: CodexOAuthTokenExchangeRequest): CodexOAuthTokenExchanger.Result {
            requests += request
            return result
        }
    }

    private class RecordingSessionImporter(
        private val result: CodexSessionImporter.Result,
    ) : CodexDeviceCodeLoginUseCase.SessionImporter {
        val sessions = mutableListOf<CodexSessionPayload>()
        val committedImports = mutableListOf<CodexSessionImporter.PreparedImport>()

        override suspend fun prepareDeviceCodeSession(session: CodexSessionPayload): CodexSessionImporter.PrepareResult {
            sessions += session
            return when (result) {
                is CodexSessionImporter.Result.Failure -> CodexSessionImporter.PrepareResult.Failure(
                    message = result.message,
                    quotaError = result.quotaError,
                )
                is CodexSessionImporter.Result.Success -> CodexSessionImporter.PrepareResult.Success(result.toPreparedImport())
            }
        }

        override suspend fun commitPreparedDeviceCodeSession(
            preparedImport: CodexSessionImporter.PreparedImport,
        ): CodexSessionImporter.Result {
            committedImports += preparedImport
            return result
        }
    }

    private class RetryingSessionImporter(
        private val prepareResults: ArrayDeque<CodexSessionImporter.PrepareResult>,
        private val commitResult: CodexSessionImporter.Result,
    ) : CodexDeviceCodeLoginUseCase.SessionImporter {
        val sessions = mutableListOf<CodexSessionPayload>()
        val committedImports = mutableListOf<CodexSessionImporter.PreparedImport>()

        override suspend fun prepareDeviceCodeSession(session: CodexSessionPayload): CodexSessionImporter.PrepareResult {
            sessions += session
            return prepareResults.removeFirst()
        }

        override suspend fun commitPreparedDeviceCodeSession(
            preparedImport: CodexSessionImporter.PreparedImport,
        ): CodexSessionImporter.Result {
            committedImports += preparedImport
            return commitResult
        }
    }

    private class DeferredPrepareSessionImporter(
        private val prepareResult: CompletableDeferred<CodexSessionImporter.PrepareResult>,
    ) : CodexDeviceCodeLoginUseCase.SessionImporter {
        val committedImports = mutableListOf<CodexSessionImporter.PreparedImport>()

        override suspend fun prepareDeviceCodeSession(session: CodexSessionPayload): CodexSessionImporter.PrepareResult =
            prepareResult.await()

        override suspend fun commitPreparedDeviceCodeSession(
            preparedImport: CodexSessionImporter.PreparedImport,
        ): CodexSessionImporter.Result {
            committedImports += preparedImport
            return importSuccess(QuotaSnapshotSource.DeviceCodeLogin)
        }
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-05-24T06:00:00Z")

        fun challenge(
            userCode: String = "ABCD-EFGH",
            deviceAuthId: String = "device-auth-id",
            expiresInSeconds: Int = 900,
        ): DeviceCodeChallenge = DeviceCodeChallenge(
            userCode = userCode,
            deviceAuthId = deviceAuthId,
            verificationUri = "https://auth.openai.com/codex/device",
            intervalSeconds = 5,
            expiresInSeconds = expiresInSeconds,
        )

        fun session(accountId: String = "provider-account"): CodexSessionPayload = CodexSessionPayload(
            accessToken = "access-token",
            refreshToken = "refresh-token",
            idToken = "id-token",
            accountId = accountId,
            accountEmail = "codex@example.com",
            lastRefresh = NOW,
        )

        fun importSuccess(source: QuotaSnapshotSource): CodexSessionImporter.Result.Success {
            val account = ProviderAccount.createNew(
                localAccountId = LocalAccountId("device-local"),
                providerId = ProviderId("codex"),
                providerAccountId = ProviderAccountId("provider-account"),
                displayName = "Codex Device",
                now = NOW,
            )
            val snapshot = QuotaSnapshot(
                snapshotId = SnapshotId("snapshot"),
                providerId = ProviderId("codex"),
                localAccountId = account.localAccountId,
                providerAccountId = account.providerAccountId,
                fetchedAt = NOW,
                source = source,
                planType = "plus",
                windows = listOf(
                    QuotaWindow(
                        windowId = QuotaWindowId("primary"),
                        titleKey = "quota_window_primary",
                        usedPercent = 42,
                        resetAt = NOW.plusSeconds(3600),
                        limitWindowSeconds = 18_000,
                        isPrimaryCandidate = true,
                        availability = QuotaWindowAvailability.Available,
                    ),
                ),
                credits = null,
                responseDigest = "digest",
            )
            return CodexSessionImporter.Result.Success(
                account = account,
                displayNameSuggestion = "Codex Device",
                sessionEnvelopeReference = CodexSessionImporter.SessionEnvelopeReference(
                    providerId = "codex",
                    localAccountId = "device-local",
                    providerAccountId = "provider-account",
                ),
                snapshot = snapshot,
            )
        }

        fun CodexSessionImporter.Result.Success.toPreparedImport(): CodexSessionImporter.PreparedImport =
            CodexSessionImporter.PreparedImport(
                account = account,
                displayNameSuggestion = displayNameSuggestion,
                sessionEnvelope = ProviderSessionEnvelope(
                    providerId = sessionEnvelopeReference.providerId,
                    localAccountId = sessionEnvelopeReference.localAccountId,
                    providerAccountId = sessionEnvelopeReference.providerAccountId,
                    schemaVersion = 1,
                    payloadCiphertext = byteArrayOf(1),
                    payloadNonce = byteArrayOf(2),
                    createdAt = NOW.toString(),
                    updatedAt = NOW.toString(),
                ),
                snapshot = snapshot,
            )
    }
}
