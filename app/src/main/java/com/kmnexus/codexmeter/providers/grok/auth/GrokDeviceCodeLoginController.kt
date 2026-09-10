package com.kmnexus.codexmeter.providers.grok.auth

import com.kmnexus.codexmeter.domain.auth.DeviceCodeLoginController
import com.kmnexus.codexmeter.domain.auth.DeviceCodeLoginDiagnosticsReader
import com.kmnexus.codexmeter.domain.auth.DeviceCodeLoginDiagnosticsSnapshot
import com.kmnexus.codexmeter.domain.auth.DeviceCodeLoginResult
import com.kmnexus.codexmeter.domain.model.ProviderAccount
import com.kmnexus.codexmeter.domain.model.ProviderAccountId
import com.kmnexus.codexmeter.domain.quota.QuotaSnapshot
import com.kmnexus.codexmeter.domain.refresh.QuotaError
import com.kmnexus.codexmeter.providers.grok.network.GrokDeviceCodeChallenge
import com.kmnexus.codexmeter.providers.grok.network.GrokDeviceCodeClient
import com.kmnexus.codexmeter.providers.grok.network.GrokOAuthEndpoints
import com.kmnexus.codexmeter.providers.grok.network.GrokOAuthDiscoveryClient
import java.time.Clock
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@JvmInline
value class GrokDeviceCodeLoginAttemptId(val value: String) {
    override fun toString(): String = value
}

sealed interface GrokDeviceCodeLoginMode {
    data object AddAccount : GrokDeviceCodeLoginMode

    data class Relogin(
        val expectedProviderAccountId: ProviderAccountId,
    ) : GrokDeviceCodeLoginMode
}

sealed interface GrokDeviceCodeLoginState {
    data object Idle : GrokDeviceCodeLoginState

    data class RequestingDeviceCode(
        val attemptId: GrokDeviceCodeLoginAttemptId,
    ) : GrokDeviceCodeLoginState

    data class AwaitingUserAuthorization(
        val attemptId: GrokDeviceCodeLoginAttemptId,
        val userCode: String,
        val verificationUri: String,
        val pollIntervalSeconds: Int,
        val expiresAt: Instant,
    ) : GrokDeviceCodeLoginState {
        override fun toString(): String =
            "AwaitingUserAuthorization(" +
                "attemptId=$attemptId, " +
                "userCode=[REDACTED], " +
                "verificationUri=$verificationUri, " +
                "pollIntervalSeconds=$pollIntervalSeconds, " +
                "expiresAt=$expiresAt" +
                ")"
    }

    data class PollingAuthorization(
        val attemptId: GrokDeviceCodeLoginAttemptId,
        val userCode: String,
        val verificationUri: String,
        val pollIntervalSeconds: Int,
        val expiresAt: Instant,
    ) : GrokDeviceCodeLoginState {
        override fun toString(): String =
            "PollingAuthorization(" +
                "attemptId=$attemptId, " +
                "userCode=[REDACTED], " +
                "verificationUri=$verificationUri, " +
                "pollIntervalSeconds=$pollIntervalSeconds, " +
                "expiresAt=$expiresAt" +
                ")"
    }

    data class ValidatingUsage(
        val attemptId: GrokDeviceCodeLoginAttemptId,
    ) : GrokDeviceCodeLoginState

    data class ValidationFailed(
        val attemptId: GrokDeviceCodeLoginAttemptId,
        val safeMessageKey: String,
    ) : GrokDeviceCodeLoginState

    data class Saved(
        val attemptId: GrokDeviceCodeLoginAttemptId,
        val account: ProviderAccount,
        val snapshot: QuotaSnapshot,
    ) : GrokDeviceCodeLoginState

    data class AccountMismatchDecision(
        val attemptId: GrokDeviceCodeLoginAttemptId,
        val expectedProviderAccountId: ProviderAccountId,
        val actualProviderAccountId: ProviderAccountId?,
    ) : GrokDeviceCodeLoginState

    data class Expired(
        val attemptId: GrokDeviceCodeLoginAttemptId,
    ) : GrokDeviceCodeLoginState

    data class Cancelled(
        val attemptId: GrokDeviceCodeLoginAttemptId,
    ) : GrokDeviceCodeLoginState

    data class Failed(
        val attemptId: GrokDeviceCodeLoginAttemptId?,
        val safeMessageKey: String,
    ) : GrokDeviceCodeLoginState
}

/**
 * Device-code login state machine for Grok, mirroring CodexDeviceCodeLoginUseCase's caller-driven
 * shape. Grok is simpler: the RFC 8628 polling loop inside [DeviceCodeClient.awaitAuthorization]
 * suspends until a terminal result, so [pollLatest] performs no separate authorization-code token
 * exchange and jumps straight from the polled token to billing validation and the two-phase import.
 *
 * Cancellation of the polling loop is structural: the caller (e.g. DeviceCodeLoginViewModel) cancels
 * the coroutine running [pollLatest], which propagates into awaitAuthorization; [cancelLatest]
 * itself only discards the attempt so late results cannot overwrite the cancelled state.
 */
class GrokDeviceCodeLoginController(
    private val discoveryClient: DiscoveryClient,
    private val deviceCodeClient: DeviceCodeClient,
    private val sessionImporter: SessionImporter,
    private val attemptIdProvider: () -> GrokDeviceCodeLoginAttemptId,
    private val clock: Clock = Clock.systemUTC(),
    private val onSaved: suspend (GrokDeviceCodeLoginState.Saved) -> Unit = {},
) : DeviceCodeLoginController, DeviceCodeLoginDiagnosticsReader {
    var currentState: GrokDeviceCodeLoginState = GrokDeviceCodeLoginState.Idle
        private set

    private var activeAttempt: ActiveAttempt? = null
    private var pollInFlightAttemptId: GrokDeviceCodeLoginAttemptId? = null
    private val stateMutex = Mutex()

    override suspend fun startLogin(): DeviceCodeLoginResult =
        start(GrokDeviceCodeLoginMode.AddAccount).toDeviceCodeLoginResult(onSaved)

    override suspend fun startRelogin(expectedProviderAccountId: String): DeviceCodeLoginResult =
        start(
            GrokDeviceCodeLoginMode.Relogin(ProviderAccountId(expectedProviderAccountId)),
        ).toDeviceCodeLoginResult(onSaved)

    /**
     * Resolves an [GrokDeviceCodeLoginState.AccountMismatchDecision] by importing the held token as
     * a new account: the importer reconciles by the JWT `sub`, so this connects the account the
     * user actually signed into rather than the re-login target.
     */
    override suspend fun confirmAddAccountFromMismatch(): DeviceCodeLoginResult {
        val pending = stateMutex.withLock {
            activeAttempt as? ActiveAttempt.MismatchPending
        } ?: return currentState.toDeviceCodeLoginResult(onSaved)
        return validateAndCommit(
            attemptId = pending.attemptId,
            oauthToken = pending.oauthToken,
            identity = pending.identity,
            tokenEndpoint = pending.tokenEndpoint,
        ).toDeviceCodeLoginResult(onSaved)
    }

    override suspend fun pollLatest(): DeviceCodeLoginResult {
        val awaiting = stateMutex.withLock {
            val active = activeAttempt as? ActiveAttempt.Awaiting ?: return@withLock null
            if (pollInFlightAttemptId == active.attemptId) {
                return currentState.toDeviceCodeLoginResult(onSaved)
            }
            if (!clock.instant().isBefore(active.expiresAt)) {
                currentState = GrokDeviceCodeLoginState.Expired(active.attemptId)
                activeAttempt = null
                pollInFlightAttemptId = null
                return currentState.toDeviceCodeLoginResult(onSaved)
            }
            pollInFlightAttemptId = active.attemptId
            currentState = active.toPollingState()
            active
        } ?: return currentState.toDeviceCodeLoginResult(onSaved)

        return try {
            when (val result = deviceCodeClient.awaitAuthorization(awaiting.challenge, awaiting.tokenEndpoint)) {
                is GrokDeviceCodeClient.Result.Failure -> {
                    if (result.error.isTransientPollNetworkError()) {
                        return stateMutex.withLock {
                            if (isLatestLocked(awaiting.attemptId)) {
                                currentState = awaiting.toAwaitingState()
                            }
                            currentState.toDeviceCodeLoginResult(onSaved)
                        }
                    }
                    return setFailedIfLatest(
                        attemptId = awaiting.attemptId,
                        safeMessageKey = result.error.safeMessageKey,
                    ).toDeviceCodeLoginResult(onSaved)
                }
                GrokDeviceCodeClient.Result.Denied -> {
                    return setFailedIfLatest(
                        attemptId = awaiting.attemptId,
                        safeMessageKey = ACCESS_DENIED_MESSAGE_KEY,
                    ).toDeviceCodeLoginResult(onSaved)
                }
                GrokDeviceCodeClient.Result.Expired -> {
                    return stateMutex.withLock {
                        if (isLatestLocked(awaiting.attemptId)) {
                            currentState = GrokDeviceCodeLoginState.Expired(awaiting.attemptId)
                            activeAttempt = null
                            pollInFlightAttemptId = null
                        }
                        currentState.toDeviceCodeLoginResult(onSaved)
                    }
                }
                is GrokDeviceCodeClient.Result.Success -> result.value
            }.let { oauthToken ->
                val identity = identityFor(oauthToken)
                stateMutex.withLock {
                    if (!isLatestLocked(awaiting.attemptId)) {
                        return currentState.toDeviceCodeLoginResult(onSaved)
                    }
                    val mismatchState = accountMismatchState(awaiting, identity)
                    if (mismatchState != null) {
                        // Re-login landed on a different Grok account. Hold the authorized token so
                        // the user can confirm adding it as a NEW account (or cancel); nothing is
                        // imported until then.
                        currentState = mismatchState
                        activeAttempt = ActiveAttempt.MismatchPending(
                            attemptId = awaiting.attemptId,
                            mode = awaiting.mode,
                            oauthToken = oauthToken,
                            identity = identity,
                            tokenEndpoint = awaiting.tokenEndpoint,
                        )
                        pollInFlightAttemptId = null
                        return currentState.toDeviceCodeLoginResult(onSaved)
                    }
                    activeAttempt = ActiveAttempt.ReadyToValidate(
                        attemptId = awaiting.attemptId,
                        mode = awaiting.mode,
                        oauthToken = oauthToken,
                        identity = identity,
                        tokenEndpoint = awaiting.tokenEndpoint,
                    )
                }
                validateAndCommit(
                    attemptId = awaiting.attemptId,
                    oauthToken = oauthToken,
                    identity = identity,
                    tokenEndpoint = awaiting.tokenEndpoint,
                )
            }.toDeviceCodeLoginResult(onSaved)
        } finally {
            stateMutex.withLock {
                if (pollInFlightAttemptId == awaiting.attemptId) {
                    pollInFlightAttemptId = null
                }
            }
        }
    }

    override suspend fun retryValidation(): DeviceCodeLoginResult {
        val ready = stateMutex.withLock {
            activeAttempt as? ActiveAttempt.ReadyToValidate
        } ?: return currentState.toDeviceCodeLoginResult(onSaved)
        return validateAndCommit(
            attemptId = ready.attemptId,
            oauthToken = ready.oauthToken,
            identity = ready.identity,
            tokenEndpoint = ready.tokenEndpoint,
        ).toDeviceCodeLoginResult(onSaved)
    }

    override suspend fun cancelLatest(): DeviceCodeLoginResult =
        stateMutex.withLock {
            val attemptId = activeAttempt?.attemptId ?: return@withLock currentState
            activeAttempt = null
            pollInFlightAttemptId = null
            currentState = GrokDeviceCodeLoginState.Cancelled(attemptId)
            currentState
        }.toDeviceCodeLoginResult(onSaved)

    override fun latestDeviceCodeLoginDiagnostics(): DeviceCodeLoginDiagnosticsSnapshot =
        currentState.toDeviceCodeLoginDiagnosticsSnapshot()

    private suspend fun start(mode: GrokDeviceCodeLoginMode): GrokDeviceCodeLoginState {
        val attemptId = attemptIdProvider()
        stateMutex.withLock {
            activeAttempt = ActiveAttempt.Requesting(
                attemptId = attemptId,
                mode = mode,
            )
            pollInFlightAttemptId = null
            currentState = GrokDeviceCodeLoginState.RequestingDeviceCode(attemptId)
        }

        val endpoints = when (val result = discoveryClient.fetchEndpoints()) {
            is GrokOAuthDiscoveryClient.Result.Failure -> {
                return setFailedIfLatest(
                    attemptId = attemptId,
                    safeMessageKey = result.error.safeMessageKey,
                )
            }
            is GrokOAuthDiscoveryClient.Result.Success -> result.value
        }

        val challenge = when (
            val result = deviceCodeClient.requestDeviceCode(endpoints.deviceAuthorizationEndpoint)
        ) {
            is GrokDeviceCodeClient.Result.Failure -> {
                return setFailedIfLatest(
                    attemptId = attemptId,
                    safeMessageKey = result.error.safeMessageKey,
                )
            }
            // A device-code request cannot legitimately be denied or expired; treat defensively.
            GrokDeviceCodeClient.Result.Denied -> {
                return setFailedIfLatest(
                    attemptId = attemptId,
                    safeMessageKey = ACCESS_DENIED_MESSAGE_KEY,
                )
            }
            GrokDeviceCodeClient.Result.Expired -> {
                return setFailedIfLatest(
                    attemptId = attemptId,
                    safeMessageKey = "error_network",
                )
            }
            is GrokDeviceCodeClient.Result.Success -> result.value
        }

        val awaiting = ActiveAttempt.Awaiting(
            attemptId = attemptId,
            mode = mode,
            challenge = challenge,
            tokenEndpoint = endpoints.tokenEndpoint,
            expiresAt = clock.instant().plusSeconds(challenge.expiresInSeconds.toLong()),
        )
        return stateMutex.withLock {
            if (!isLatestLocked(attemptId)) {
                currentState
            } else {
                activeAttempt = awaiting
                currentState = awaiting.toAwaitingState()
                currentState
            }
        }
    }

    private fun accountMismatchState(
        attempt: ActiveAttempt.Awaiting,
        identity: GrokLoginIdentity,
    ): GrokDeviceCodeLoginState.AccountMismatchDecision? {
        val mode = attempt.mode as? GrokDeviceCodeLoginMode.Relogin ?: return null
        val actualProviderAccountId = identity.accountId
            ?.takeIf { it.isNotBlank() }
            ?.let(::ProviderAccountId)
        return if (actualProviderAccountId == mode.expectedProviderAccountId) {
            null
        } else {
            GrokDeviceCodeLoginState.AccountMismatchDecision(
                attemptId = attempt.attemptId,
                expectedProviderAccountId = mode.expectedProviderAccountId,
                actualProviderAccountId = actualProviderAccountId,
            )
        }
    }

    private suspend fun validateAndCommit(
        attemptId: GrokDeviceCodeLoginAttemptId,
        oauthToken: GrokOAuthToken,
        identity: GrokLoginIdentity,
        tokenEndpoint: String,
    ): GrokDeviceCodeLoginState {
        stateMutex.withLock {
            if (!isLatestLocked(attemptId)) {
                return currentState
            }
            currentState = GrokDeviceCodeLoginState.ValidatingUsage(attemptId)
        }

        val prepared = try {
            sessionImporter.prepareDeviceCodeSession(
                oauthToken = oauthToken,
                identity = identity,
                tokenEndpoint = tokenEndpoint,
            )
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            return stateMutex.withLock {
                if (isLatestLocked(attemptId)) {
                    currentState = GrokDeviceCodeLoginState.ValidationFailed(attemptId, "error_network")
                }
                currentState
            }
        }

        // Commit runs the billing validation network call, so it stays OUTSIDE the state lock —
        // holding the lock across it would block cancelLatest until the call finishes and let a
        // late cancel land as Saved instead of Cancelled. Only the resulting state write is locked.
        val commitResult = try {
            sessionImporter.commitPreparedDeviceCodeSession(prepared)
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            null
        }

        return stateMutex.withLock {
            if (!isLatestLocked(attemptId)) {
                return@withLock currentState
            }
            when (commitResult) {
                null ->
                    GrokDeviceCodeLoginState.ValidationFailed(attemptId, "error_network")
                is GrokSessionImporter.Result.Failure ->
                    GrokDeviceCodeLoginState.ValidationFailed(attemptId, commitResult.message)
                is GrokSessionImporter.Result.Success -> {
                    activeAttempt = null
                    pollInFlightAttemptId = null
                    GrokDeviceCodeLoginState.Saved(
                        attemptId = attemptId,
                        account = commitResult.account,
                        snapshot = commitResult.snapshot,
                    )
                }
            }.also { currentState = it }
        }
    }

    private suspend fun setFailedIfLatest(
        attemptId: GrokDeviceCodeLoginAttemptId,
        safeMessageKey: String,
    ): GrokDeviceCodeLoginState =
        stateMutex.withLock {
            if (isLatestLocked(attemptId)) {
                currentState = GrokDeviceCodeLoginState.Failed(attemptId, safeMessageKey)
                activeAttempt = null
                pollInFlightAttemptId = null
            }
            currentState
        }

    private fun isLatestLocked(attemptId: GrokDeviceCodeLoginAttemptId): Boolean =
        activeAttempt?.attemptId == attemptId

    private fun QuotaError.isTransientPollNetworkError(): Boolean =
        this is QuotaError.Network &&
            diagnosticsDigest == TRANSIENT_TOKEN_POLL_DIGEST

    private sealed interface ActiveAttempt {
        val attemptId: GrokDeviceCodeLoginAttemptId
        val mode: GrokDeviceCodeLoginMode

        data class Requesting(
            override val attemptId: GrokDeviceCodeLoginAttemptId,
            override val mode: GrokDeviceCodeLoginMode,
        ) : ActiveAttempt

        data class Awaiting(
            override val attemptId: GrokDeviceCodeLoginAttemptId,
            override val mode: GrokDeviceCodeLoginMode,
            val challenge: GrokDeviceCodeChallenge,
            val tokenEndpoint: String,
            val expiresAt: Instant,
        ) : ActiveAttempt {
            fun toAwaitingState(): GrokDeviceCodeLoginState.AwaitingUserAuthorization =
                GrokDeviceCodeLoginState.AwaitingUserAuthorization(
                    attemptId = attemptId,
                    userCode = challenge.userCode,
                    verificationUri = challenge.verificationUri,
                    pollIntervalSeconds = challenge.intervalSeconds,
                    expiresAt = expiresAt,
                )

            fun toPollingState(): GrokDeviceCodeLoginState.PollingAuthorization =
                GrokDeviceCodeLoginState.PollingAuthorization(
                    attemptId = attemptId,
                    userCode = challenge.userCode,
                    verificationUri = challenge.verificationUri,
                    pollIntervalSeconds = challenge.intervalSeconds,
                    expiresAt = expiresAt,
                )
        }

        data class ReadyToValidate(
            override val attemptId: GrokDeviceCodeLoginAttemptId,
            override val mode: GrokDeviceCodeLoginMode,
            val oauthToken: GrokOAuthToken,
            val identity: GrokLoginIdentity,
            val tokenEndpoint: String,
        ) : ActiveAttempt

        /** A re-login whose account differs from the target, awaiting the user's add-or-cancel choice. */
        data class MismatchPending(
            override val attemptId: GrokDeviceCodeLoginAttemptId,
            override val mode: GrokDeviceCodeLoginMode,
            val oauthToken: GrokOAuthToken,
            val identity: GrokLoginIdentity,
            val tokenEndpoint: String,
        ) : ActiveAttempt
    }

    fun interface DiscoveryClient {
        suspend fun fetchEndpoints(): GrokOAuthDiscoveryClient.Result<GrokOAuthEndpoints>
    }

    interface DeviceCodeClient {
        suspend fun requestDeviceCode(
            deviceAuthorizationEndpointUrl: String,
        ): GrokDeviceCodeClient.Result<GrokDeviceCodeChallenge>

        suspend fun awaitAuthorization(
            challenge: GrokDeviceCodeChallenge,
            tokenEndpointUrl: String,
        ): GrokDeviceCodeClient.Result<GrokOAuthToken>
    }

    interface SessionImporter {
        suspend fun prepareDeviceCodeSession(
            oauthToken: GrokOAuthToken,
            identity: GrokLoginIdentity,
            tokenEndpoint: String?,
        ): GrokSessionImporter.PreparedImport

        suspend fun commitPreparedDeviceCodeSession(
            preparedImport: GrokSessionImporter.PreparedImport,
        ): GrokSessionImporter.Result
    }

    private companion object {
        const val ACCESS_DENIED_MESSAGE_KEY = "error_grok_access_denied"
        const val TRANSIENT_TOKEN_POLL_DIGEST = "grok_token_network_error"
    }
}

/** Identity comes from the id_token JWT claims with the access_token as fallback. */
private fun identityFor(oauthToken: GrokOAuthToken): GrokLoginIdentity =
    GrokLoginIdentity(
        accountId = GrokJwtClaims.sub(oauthToken.idToken) ?: GrokJwtClaims.sub(oauthToken.accessToken),
        email = GrokJwtClaims.email(oauthToken.idToken) ?: GrokJwtClaims.email(oauthToken.accessToken),
        name = GrokJwtClaims.name(oauthToken.idToken) ?: GrokJwtClaims.name(oauthToken.accessToken),
    )

private fun GrokDeviceCodeLoginState.toDeviceCodeLoginDiagnosticsSnapshot(): DeviceCodeLoginDiagnosticsSnapshot =
    when (this) {
        GrokDeviceCodeLoginState.Idle -> DeviceCodeLoginDiagnosticsSnapshot(status = "idle")
        is GrokDeviceCodeLoginState.RequestingDeviceCode -> DeviceCodeLoginDiagnosticsSnapshot(
            status = "requesting_device_code",
            attemptId = attemptId.value,
        )
        is GrokDeviceCodeLoginState.AwaitingUserAuthorization -> DeviceCodeLoginDiagnosticsSnapshot(
            status = "awaiting_user_authorization",
            attemptId = attemptId.value,
            verificationUriStatus = verificationUri.hostStatus(),
            pollIntervalSeconds = pollIntervalSeconds,
            expiresAt = expiresAt,
        )
        is GrokDeviceCodeLoginState.PollingAuthorization -> DeviceCodeLoginDiagnosticsSnapshot(
            status = "polling_authorization",
            attemptId = attemptId.value,
            verificationUriStatus = verificationUri.hostStatus(),
            pollIntervalSeconds = pollIntervalSeconds,
            expiresAt = expiresAt,
        )
        is GrokDeviceCodeLoginState.ValidatingUsage -> DeviceCodeLoginDiagnosticsSnapshot(
            status = "validating_usage",
            attemptId = attemptId.value,
        )
        is GrokDeviceCodeLoginState.ValidationFailed -> DeviceCodeLoginDiagnosticsSnapshot(
            status = "validation_failed",
            attemptId = attemptId.value,
            safeErrorCode = safeMessageKey,
        )
        is GrokDeviceCodeLoginState.Saved -> DeviceCodeLoginDiagnosticsSnapshot(
            status = "saved",
            attemptId = attemptId.value,
        )
        is GrokDeviceCodeLoginState.AccountMismatchDecision -> DeviceCodeLoginDiagnosticsSnapshot(
            status = "account_mismatch_decision",
            attemptId = attemptId.value,
            safeErrorCode = "error_device_code_account_mismatch",
        )
        is GrokDeviceCodeLoginState.Expired -> DeviceCodeLoginDiagnosticsSnapshot(
            status = "expired",
            attemptId = attemptId.value,
        )
        is GrokDeviceCodeLoginState.Cancelled -> DeviceCodeLoginDiagnosticsSnapshot(
            status = "cancelled",
            attemptId = attemptId.value,
        )
        is GrokDeviceCodeLoginState.Failed -> DeviceCodeLoginDiagnosticsSnapshot(
            status = "failed",
            attemptId = attemptId?.value,
            safeErrorCode = safeMessageKey,
        )
    }

private suspend fun GrokDeviceCodeLoginState.toDeviceCodeLoginResult(
    onSaved: suspend (GrokDeviceCodeLoginState.Saved) -> Unit,
): DeviceCodeLoginResult =
    when (this) {
        GrokDeviceCodeLoginState.Idle -> DeviceCodeLoginResult.Idle
        is GrokDeviceCodeLoginState.RequestingDeviceCode -> DeviceCodeLoginResult.RequestingDeviceCode(
            attemptId = attemptId.value,
        )
        is GrokDeviceCodeLoginState.AwaitingUserAuthorization -> DeviceCodeLoginResult.AwaitingUserAuthorization(
            attemptId = attemptId.value,
            userCode = userCode,
            verificationUri = verificationUri,
            pollIntervalSeconds = pollIntervalSeconds,
            expiresAt = expiresAt,
        )
        is GrokDeviceCodeLoginState.PollingAuthorization -> DeviceCodeLoginResult.PollingAuthorization(
            attemptId = attemptId.value,
            userCode = userCode,
            verificationUri = verificationUri,
            pollIntervalSeconds = pollIntervalSeconds,
            expiresAt = expiresAt,
        )
        is GrokDeviceCodeLoginState.ValidatingUsage -> DeviceCodeLoginResult.ValidatingUsage(
            attemptId = attemptId.value,
        )
        is GrokDeviceCodeLoginState.ValidationFailed -> DeviceCodeLoginResult.ValidationFailed(
            attemptId = attemptId.value,
            safeMessageKey = safeMessageKey,
        )
        is GrokDeviceCodeLoginState.Saved -> {
            onSaved(this)
            DeviceCodeLoginResult.Saved(
                attemptId = attemptId.value,
                account = account,
            )
        }
        is GrokDeviceCodeLoginState.AccountMismatchDecision -> DeviceCodeLoginResult.AccountMismatchDecision(
            attemptId = attemptId.value,
        )
        is GrokDeviceCodeLoginState.Expired -> DeviceCodeLoginResult.Expired(
            attemptId = attemptId.value,
        )
        is GrokDeviceCodeLoginState.Cancelled -> DeviceCodeLoginResult.Cancelled(
            attemptId = attemptId.value,
        )
        is GrokDeviceCodeLoginState.Failed -> DeviceCodeLoginResult.Failed(
            attemptId = attemptId?.value,
            safeMessageKey = safeMessageKey,
        )
    }

private fun String.hostStatus(): String =
    when {
        startsWith("https://auth.x.ai/", ignoreCase = true) -> "official_auth_x_ai"
        startsWith("https://", ignoreCase = true) -> "https_other"
        else -> "invalid_or_insecure"
    }
