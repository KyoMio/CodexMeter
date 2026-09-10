package com.kmnexus.codexmeter.providers.grok.auth

import com.kmnexus.codexmeter.domain.auth.DeviceCodeLoginResult
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
import com.kmnexus.codexmeter.providers.grok.network.GrokDeviceCodeChallenge
import com.kmnexus.codexmeter.providers.grok.network.GrokDeviceCodeClient
import com.kmnexus.codexmeter.providers.grok.network.GrokOAuthEndpoints
import com.kmnexus.codexmeter.providers.grok.network.GrokOAuthDiscoveryClient
import com.kmnexus.codexmeter.providers.grok.session.GrokSessionPayload
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GrokDeviceCodeLoginControllerTest {
    private val clock = Clock.fixed(NOW, ZoneOffset.UTC)

    @Test
    fun `start login success creates awaiting state and redacts device code`() = runTest {
        val controller = newController(
            deviceClient = RecordingDeviceCodeClient(
                requestResults = ArrayDeque(listOf(GrokDeviceCodeClient.Result.Success(challenge()))),
            ),
        )

        val result = controller.startLogin()

        assertTrue(result is DeviceCodeLoginResult.AwaitingUserAuthorization)
        val awaiting = result as DeviceCodeLoginResult.AwaitingUserAuthorization
        assertEquals("attempt-1", awaiting.attemptId)
        assertEquals("ABCD-EFGH", awaiting.userCode)
        assertEquals("https://auth.x.ai/device", awaiting.verificationUri)
        assertEquals(5, awaiting.pollIntervalSeconds)
        assertEquals(NOW.plusSeconds(900), awaiting.expiresAt)
        assertFalse(controller.currentState.toString().contains("ABCD-EFGH"))
        assertFalse(controller.currentState.toString().contains("raw-device-code"))
    }

    @Test
    fun `start login discovery failure maps safe failure`() = runTest {
        val controller = newController(
            discoveryResult = GrokOAuthDiscoveryClient.Result.Failure(
                QuotaError.Network("grok_discovery_network_error"),
            ),
        )

        val result = controller.startLogin()

        assertEquals(
            DeviceCodeLoginResult.Failed(attemptId = "attempt-1", safeMessageKey = "error_network"),
            result,
        )
    }

    @Test
    fun `transient network failure while polling keeps awaiting state for next poll`() = runTest {
        val deviceClient = RecordingDeviceCodeClient(
            requestResults = ArrayDeque(listOf(GrokDeviceCodeClient.Result.Success(challenge()))),
            awaitResults = ArrayDeque(
                listOf(
                    GrokDeviceCodeClient.Result.Failure(
                        QuotaError.Network("grok_token_network_error"),
                    ),
                    GrokDeviceCodeClient.Result.Success(oauthToken()),
                ),
            ),
        )
        val sessionImporter = RecordingSessionImporter(importSuccess())
        val controller = newController(
            deviceClient = deviceClient,
            sessionImporter = sessionImporter,
        )

        controller.startLogin()
        val firstPoll = controller.pollLatest()
        val secondPoll = controller.pollLatest()

        assertTrue(firstPoll is DeviceCodeLoginResult.AwaitingUserAuthorization)
        assertTrue(secondPoll is DeviceCodeLoginResult.Saved)
        assertEquals(2, deviceClient.awaitChallenges.size)
        assertEquals(1, sessionImporter.committedImports.size)
    }

    @Test
    fun `authorized poll imports device code session with identity from id token`() = runTest {
        val deviceClient = RecordingDeviceCodeClient(
            requestResults = ArrayDeque(listOf(GrokDeviceCodeClient.Result.Success(challenge()))),
            awaitResults = ArrayDeque(listOf(GrokDeviceCodeClient.Result.Success(oauthToken()))),
        )
        val sessionImporter = RecordingSessionImporter(importSuccess())
        val controller = newController(
            deviceClient = deviceClient,
            sessionImporter = sessionImporter,
        )

        controller.startLogin()
        val result = controller.pollLatest()

        assertTrue(result is DeviceCodeLoginResult.Saved)
        val saved = result as DeviceCodeLoginResult.Saved
        assertEquals(LocalAccountId("device-local"), saved.account.localAccountId)
        assertEquals("actual-account", sessionImporter.preparedIdentities.single().accountId)
        assertEquals(TOKEN_ENDPOINT, sessionImporter.preparedTokenEndpoints.single())
        assertEquals("actual-account", sessionImporter.committedImports.single().session.accountId)
        assertEquals(1, sessionImporter.committedImports.size)
    }

    @Test
    fun `denied authorization maps to safe denied failure`() = runTest {
        val controller = newController(
            deviceClient = RecordingDeviceCodeClient(
                requestResults = ArrayDeque(listOf(GrokDeviceCodeClient.Result.Success(challenge()))),
                awaitResults = ArrayDeque(listOf(GrokDeviceCodeClient.Result.Denied)),
            ),
        )

        controller.startLogin()
        val result = controller.pollLatest()

        assertEquals(
            DeviceCodeLoginResult.Failed(attemptId = "attempt-1", safeMessageKey = "error_grok_access_denied"),
            result,
        )
    }

    @Test
    fun `expired device code maps to expired state`() = runTest {
        val controller = newController(
            deviceClient = RecordingDeviceCodeClient(
                requestResults = ArrayDeque(listOf(GrokDeviceCodeClient.Result.Success(challenge()))),
                awaitResults = ArrayDeque(listOf(GrokDeviceCodeClient.Result.Expired)),
            ),
        )

        controller.startLogin()
        val result = controller.pollLatest()

        assertEquals(DeviceCodeLoginResult.Expired(attemptId = "attempt-1"), result)
    }

    @Test
    fun `attempt past its deadline stops before polling again`() = runTest {
        val deviceClient = RecordingDeviceCodeClient(
            requestResults = ArrayDeque(
                listOf(GrokDeviceCodeClient.Result.Success(challenge(expiresInSeconds = 0))),
            ),
        )
        val controller = newController(deviceClient = deviceClient)

        controller.startLogin()
        val result = controller.pollLatest()

        assertEquals(DeviceCodeLoginResult.Expired(attemptId = "attempt-1"), result)
        assertTrue(deviceClient.awaitChallenges.isEmpty())
    }

    @Test
    fun `billing validation failure exposes retryable validation failure and does not commit`() = runTest {
        val sessionImporter = RetryingSessionImporter(
            commitResults = ArrayDeque(
                listOf(
                    GrokSessionImporter.Result.Failure("error_network", QuotaError.Network("validation_failed")),
                    importSuccess(),
                ),
            ),
        )
        val controller = newController(sessionImporter = sessionImporter)

        controller.startLogin()
        val firstState = controller.pollLatest()
        val retryState = controller.retryValidation()

        assertEquals(
            DeviceCodeLoginResult.ValidationFailed(attemptId = "attempt-1", safeMessageKey = "error_network"),
            firstState,
        )
        assertTrue(retryState is DeviceCodeLoginResult.Saved)
        assertEquals(2, sessionImporter.committedImports.size)
    }

    @Test
    fun `relogin account mismatch asks for decision before importing`() = runTest {
        val sessionImporter = RecordingSessionImporter(importSuccess())
        val controller = newController(sessionImporter = sessionImporter)

        controller.startRelogin(expectedProviderAccountId = "expected-account")
        val result = controller.pollLatest()

        val mismatch = result as DeviceCodeLoginResult.AccountMismatchDecision
        assertEquals("attempt-1", mismatch.attemptId)
        assertEquals("error_device_code_account_mismatch", mismatch.safeMessageKey)
        assertTrue(sessionImporter.committedImports.isEmpty())
    }

    @Test
    fun `confirming account mismatch imports the signed-in account as a new account`() = runTest {
        val sessionImporter = RecordingSessionImporter(importSuccess())
        val controller = newController(sessionImporter = sessionImporter)

        controller.startRelogin(expectedProviderAccountId = "expected-account")
        controller.pollLatest()
        val result = controller.confirmAddAccountFromMismatch()

        assertTrue(result is DeviceCodeLoginResult.Saved)
        assertEquals(1, sessionImporter.committedImports.size)
    }

    @Test
    fun `cancel discards the attempt without importing`() = runTest {
        val sessionImporter = RecordingSessionImporter(importSuccess())
        val controller = newController(sessionImporter = sessionImporter)

        controller.startLogin()
        val result = controller.cancelLatest()

        assertEquals(DeviceCodeLoginResult.Cancelled(attemptId = "attempt-1"), result)
        assertTrue(sessionImporter.committedImports.isEmpty())
    }

    @Test
    fun `late await result after cancel does not override cancelled state`() = runTest {
        val awaitResult = CompletableDeferred<GrokDeviceCodeClient.Result<GrokOAuthToken>>()
        val sessionImporter = RecordingSessionImporter(importSuccess())
        val controller = newController(
            deviceClient = DeferredAwaitDeviceCodeClient(awaitResult),
            sessionImporter = sessionImporter,
        )

        controller.startLogin()
        val poll = async { controller.pollLatest() }
        yield()
        val cancelled = controller.cancelLatest()
        awaitResult.complete(GrokDeviceCodeClient.Result.Success(oauthToken()))
        val staleState = poll.await()

        assertEquals(DeviceCodeLoginResult.Cancelled(attemptId = "attempt-1"), cancelled)
        assertEquals(cancelled, staleState)
        assertTrue(sessionImporter.committedImports.isEmpty())
    }

    private fun newController(
        discoveryResult: GrokOAuthDiscoveryClient.Result<GrokOAuthEndpoints> =
            GrokOAuthDiscoveryClient.Result.Success(endpoints()),
        deviceClient: GrokDeviceCodeLoginController.DeviceCodeClient = RecordingDeviceCodeClient(
            requestResults = ArrayDeque(listOf(GrokDeviceCodeClient.Result.Success(challenge()))),
            awaitResults = ArrayDeque(listOf(GrokDeviceCodeClient.Result.Success(oauthToken()))),
        ),
        sessionImporter: GrokDeviceCodeLoginController.SessionImporter = RecordingSessionImporter(importSuccess()),
    ): GrokDeviceCodeLoginController =
        GrokDeviceCodeLoginController(
            discoveryClient = { discoveryResult },
            deviceCodeClient = deviceClient,
            sessionImporter = sessionImporter,
            attemptIdProvider = AttemptIdProvider(),
            clock = clock,
        )

    private class AttemptIdProvider : () -> GrokDeviceCodeLoginAttemptId {
        private var next = 1
        override fun invoke(): GrokDeviceCodeLoginAttemptId = GrokDeviceCodeLoginAttemptId("attempt-${next++}")
    }

    private class RecordingDeviceCodeClient(
        private val requestResults: ArrayDeque<GrokDeviceCodeClient.Result<GrokDeviceCodeChallenge>>,
        private val awaitResults: ArrayDeque<GrokDeviceCodeClient.Result<GrokOAuthToken>> = ArrayDeque(),
    ) : GrokDeviceCodeLoginController.DeviceCodeClient {
        val awaitChallenges = mutableListOf<GrokDeviceCodeChallenge>()

        override suspend fun requestDeviceCode(
            deviceAuthorizationEndpointUrl: String,
        ): GrokDeviceCodeClient.Result<GrokDeviceCodeChallenge> = requestResults.removeFirst()

        override suspend fun awaitAuthorization(
            challenge: GrokDeviceCodeChallenge,
            tokenEndpointUrl: String,
        ): GrokDeviceCodeClient.Result<GrokOAuthToken> {
            awaitChallenges += challenge
            return awaitResults.removeFirst()
        }
    }

    private class DeferredAwaitDeviceCodeClient(
        private val awaitResult: CompletableDeferred<GrokDeviceCodeClient.Result<GrokOAuthToken>>,
    ) : GrokDeviceCodeLoginController.DeviceCodeClient {
        override suspend fun requestDeviceCode(
            deviceAuthorizationEndpointUrl: String,
        ): GrokDeviceCodeClient.Result<GrokDeviceCodeChallenge> =
            GrokDeviceCodeClient.Result.Success(challenge())

        override suspend fun awaitAuthorization(
            challenge: GrokDeviceCodeChallenge,
            tokenEndpointUrl: String,
        ): GrokDeviceCodeClient.Result<GrokOAuthToken> = awaitResult.await()
    }

    private class RecordingSessionImporter(
        private val result: GrokSessionImporter.Result,
    ) : GrokDeviceCodeLoginController.SessionImporter {
        val preparedIdentities = mutableListOf<GrokLoginIdentity>()
        val preparedTokenEndpoints = mutableListOf<String?>()
        val committedImports = mutableListOf<GrokSessionImporter.PreparedImport>()

        override suspend fun prepareDeviceCodeSession(
            oauthToken: GrokOAuthToken,
            identity: GrokLoginIdentity,
            tokenEndpoint: String?,
        ): GrokSessionImporter.PreparedImport {
            preparedIdentities += identity
            preparedTokenEndpoints += tokenEndpoint
            return GrokSessionImporter.PreparedImport(
                session = GrokSessionPayload(
                    accessToken = oauthToken.accessToken,
                    refreshToken = oauthToken.refreshToken,
                    idToken = oauthToken.idToken,
                    tokenExpiresAtEpochSeconds = oauthToken.expiresAtEpochSeconds,
                    tokenEndpoint = tokenEndpoint,
                    accountId = identity.accountId,
                    email = identity.email,
                ),
            )
        }

        override suspend fun commitPreparedDeviceCodeSession(
            preparedImport: GrokSessionImporter.PreparedImport,
        ): GrokSessionImporter.Result {
            committedImports += preparedImport
            return result
        }
    }

    private class RetryingSessionImporter(
        private val commitResults: ArrayDeque<GrokSessionImporter.Result>,
    ) : GrokDeviceCodeLoginController.SessionImporter {
        val committedImports = mutableListOf<GrokSessionImporter.PreparedImport>()

        override suspend fun prepareDeviceCodeSession(
            oauthToken: GrokOAuthToken,
            identity: GrokLoginIdentity,
            tokenEndpoint: String?,
        ): GrokSessionImporter.PreparedImport =
            GrokSessionImporter.PreparedImport(
                session = GrokSessionPayload(
                    accessToken = oauthToken.accessToken,
                    refreshToken = oauthToken.refreshToken,
                ),
            )

        override suspend fun commitPreparedDeviceCodeSession(
            preparedImport: GrokSessionImporter.PreparedImport,
        ): GrokSessionImporter.Result {
            committedImports += preparedImport
            return commitResults.removeFirst()
        }
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-10T06:00:00Z")
        const val TOKEN_ENDPOINT = "https://auth.x.ai/oauth2/token"

        fun endpoints(): GrokOAuthEndpoints =
            GrokOAuthEndpoints(
                deviceAuthorizationEndpoint = "https://auth.x.ai/oauth2/device/authorize",
                tokenEndpoint = TOKEN_ENDPOINT,
            )

        fun challenge(expiresInSeconds: Int = 900): GrokDeviceCodeChallenge =
            GrokDeviceCodeChallenge(
                deviceCode = "raw-device-code",
                userCode = "ABCD-EFGH",
                verificationUri = "https://auth.x.ai/device",
                verificationUriComplete = null,
                intervalSeconds = 5,
                expiresInSeconds = expiresInSeconds,
            )

        fun oauthToken(idTokenClaims: String = """{"sub":"actual-account","email":"grok@example.test"}"""): GrokOAuthToken =
            GrokOAuthToken(
                accessToken = "synthetic-access-token",
                refreshToken = "synthetic-refresh-token",
                idToken = jwt(idTokenClaims),
                expiresAtEpochSeconds = NOW.epochSecond + 3_600,
            )

        fun jwt(claims: String): String {
            val payload = Base64.getUrlEncoder().withoutPadding().encodeToString(claims.toByteArray())
            return "synthetic-header.$payload.synthetic-signature"
        }

        fun importSuccess(): GrokSessionImporter.Result.Success {
            val account = ProviderAccount.createNew(
                localAccountId = LocalAccountId("device-local"),
                providerId = ProviderId("grok"),
                providerAccountId = ProviderAccountId("actual-account"),
                displayName = "Grok Device",
                now = NOW,
            )
            val snapshot = QuotaSnapshot(
                snapshotId = SnapshotId("snapshot"),
                providerId = ProviderId("grok"),
                localAccountId = account.localAccountId,
                providerAccountId = account.providerAccountId,
                fetchedAt = NOW,
                source = QuotaSnapshotSource.DeviceCodeLogin,
                planType = null,
                windows = listOf(
                    QuotaWindow(
                        windowId = QuotaWindowId("weekly"),
                        titleKey = "weekly",
                        usedPercent = 42,
                        resetAt = NOW.plusSeconds(3600),
                        limitWindowSeconds = null,
                        isPrimaryCandidate = true,
                        availability = QuotaWindowAvailability.Available,
                    ),
                ),
                credits = null,
                responseDigest = null,
            )
            return GrokSessionImporter.Result.Success(
                account = account,
                displayNameSuggestion = "Grok Device",
                sessionEnvelopeReference = GrokSessionImporter.SessionEnvelopeReference(
                    providerId = "grok",
                    localAccountId = "device-local",
                    providerAccountId = "actual-account",
                ),
                snapshot = snapshot,
            )
        }
    }
}
