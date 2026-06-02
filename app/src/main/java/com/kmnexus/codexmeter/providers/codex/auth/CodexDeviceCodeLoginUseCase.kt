package com.kmnexus.codexmeter.providers.codex.auth

import com.kmnexus.codexmeter.domain.model.ProviderAccount
import com.kmnexus.codexmeter.domain.model.ProviderAccountId
import com.kmnexus.codexmeter.domain.quota.QuotaSnapshot
import com.kmnexus.codexmeter.domain.refresh.QuotaError
import com.kmnexus.codexmeter.providers.codex.session.CodexSessionPayload
import java.time.Clock
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@JvmInline
value class DeviceCodeLoginAttemptId(val value: String) {
    override fun toString(): String = value
}

sealed interface CodexDeviceCodeLoginMode {
    data object AddAccount : CodexDeviceCodeLoginMode

    data class Relogin(
        val expectedProviderAccountId: ProviderAccountId,
    ) : CodexDeviceCodeLoginMode
}

sealed interface CodexDeviceCodeLoginState {
    data object Idle : CodexDeviceCodeLoginState

    data class RequestingDeviceCode(
        val attemptId: DeviceCodeLoginAttemptId,
    ) : CodexDeviceCodeLoginState

    data class AwaitingUserAuthorization(
        val attemptId: DeviceCodeLoginAttemptId,
        val userCode: String,
        val verificationUri: String,
        val pollIntervalSeconds: Int,
        val expiresAt: Instant,
    ) : CodexDeviceCodeLoginState {
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
        val attemptId: DeviceCodeLoginAttemptId,
        val userCode: String,
        val verificationUri: String,
        val pollIntervalSeconds: Int,
        val expiresAt: Instant,
    ) : CodexDeviceCodeLoginState {
        override fun toString(): String =
            "PollingAuthorization(" +
                "attemptId=$attemptId, " +
                "userCode=[REDACTED], " +
                "verificationUri=$verificationUri, " +
                "pollIntervalSeconds=$pollIntervalSeconds, " +
                "expiresAt=$expiresAt" +
                ")"
    }

    data class ExchangingToken(
        val attemptId: DeviceCodeLoginAttemptId,
    ) : CodexDeviceCodeLoginState

    data class ValidatingUsage(
        val attemptId: DeviceCodeLoginAttemptId,
    ) : CodexDeviceCodeLoginState

    data class ValidationFailed(
        val attemptId: DeviceCodeLoginAttemptId,
        val safeMessageKey: String,
    ) : CodexDeviceCodeLoginState

    data class Saved(
        val attemptId: DeviceCodeLoginAttemptId,
        val account: ProviderAccount,
        val snapshot: QuotaSnapshot,
    ) : CodexDeviceCodeLoginState

    data class AccountMismatchDecision(
        val attemptId: DeviceCodeLoginAttemptId,
        val expectedProviderAccountId: ProviderAccountId,
        val actualProviderAccountId: ProviderAccountId?,
    ) : CodexDeviceCodeLoginState

    data class Expired(
        val attemptId: DeviceCodeLoginAttemptId,
    ) : CodexDeviceCodeLoginState

    data class Cancelled(
        val attemptId: DeviceCodeLoginAttemptId,
    ) : CodexDeviceCodeLoginState

    data class Failed(
        val attemptId: DeviceCodeLoginAttemptId?,
        val safeMessageKey: String,
    ) : CodexDeviceCodeLoginState
}

class CodexDeviceCodeLoginUseCase(
    private val deviceCodeClient: DeviceCodeClient,
    private val tokenExchanger: TokenExchanger,
    private val sessionImporter: SessionImporter,
    private val attemptIdProvider: () -> DeviceCodeLoginAttemptId,
    private val clock: Clock = Clock.systemUTC(),
) {
    var currentState: CodexDeviceCodeLoginState = CodexDeviceCodeLoginState.Idle
        private set

    private var activeAttempt: ActiveAttempt? = null
    private var pollInFlightAttemptId: DeviceCodeLoginAttemptId? = null
    private val stateMutex = Mutex()

    suspend fun startLogin(
        mode: CodexDeviceCodeLoginMode = CodexDeviceCodeLoginMode.AddAccount,
    ): CodexDeviceCodeLoginState {
        val attemptId = attemptIdProvider()
        stateMutex.withLock {
            activeAttempt = ActiveAttempt.Requesting(
                attemptId = attemptId,
                mode = mode,
            )
            pollInFlightAttemptId = null
            currentState = CodexDeviceCodeLoginState.RequestingDeviceCode(attemptId)
        }

        val challenge = when (val result = deviceCodeClient.requestDeviceCode()) {
            is CodexDeviceCodeClient.Result.Failure -> {
                return setFailedIfLatest(
                    attemptId = attemptId,
                    safeMessageKey = result.error.safeMessageKey,
                )
            }
            CodexDeviceCodeClient.Result.Pending -> {
                return setFailedIfLatest(
                    attemptId = attemptId,
                    safeMessageKey = "error_network",
                )
            }
            is CodexDeviceCodeClient.Result.Success -> result.value
        }

        val awaiting = ActiveAttempt.Awaiting(
            attemptId = attemptId,
            mode = mode,
            challenge = challenge,
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

    suspend fun pollLatest(): CodexDeviceCodeLoginState {
        val awaiting = stateMutex.withLock {
            val active = activeAttempt as? ActiveAttempt.Awaiting ?: return@withLock null
            if (pollInFlightAttemptId == active.attemptId) {
                return currentState
            }
            if (!clock.instant().isBefore(active.expiresAt)) {
                currentState = CodexDeviceCodeLoginState.Expired(active.attemptId)
                activeAttempt = null
                pollInFlightAttemptId = null
                return currentState
            }
            pollInFlightAttemptId = active.attemptId
            currentState = active.toPollingState()
            active
        } ?: return currentState

        return try {
            val authorization = when (val result = deviceCodeClient.pollAuthorization(awaiting.challenge)) {
                is CodexDeviceCodeClient.Result.Failure -> {
                    if (result.error.isTransientPollNetworkError()) {
                        return stateMutex.withLock {
                            if (isLatestLocked(awaiting.attemptId)) {
                                currentState = awaiting.toAwaitingState()
                            }
                            currentState
                        }
                    }
                    return setFailedIfLatest(
                        attemptId = awaiting.attemptId,
                        safeMessageKey = result.error.safeMessageKey,
                    )
                }
                CodexDeviceCodeClient.Result.Pending -> {
                    return stateMutex.withLock {
                        if (isLatestLocked(awaiting.attemptId)) {
                            currentState = awaiting.toAwaitingState()
                        }
                        currentState
                    }
                }
                is CodexDeviceCodeClient.Result.Success -> result.value
            }

            if (!stateMutex.withLock { isLatestLocked(awaiting.attemptId) }) {
                return currentState
            }
            exchangeAndValidate(
                attempt = awaiting,
                authorization = authorization,
            )
        } finally {
            stateMutex.withLock {
                if (pollInFlightAttemptId == awaiting.attemptId) {
                    pollInFlightAttemptId = null
                }
            }
        }
    }

    suspend fun retryValidation(): CodexDeviceCodeLoginState {
        val ready = stateMutex.withLock {
            activeAttempt as? ActiveAttempt.ReadyToValidate
        } ?: return currentState
        return validateAndCommit(
            attemptId = ready.attemptId,
            session = ready.session,
        )
    }

    /**
     * Resolves an [CodexDeviceCodeLoginState.AccountMismatchDecision] by importing the held session as
     * a new account. The importer reconciles by the actual chatgpt_account_id, so this connects (or
     * rebinds to) the account the user actually signed into rather than the re-login target.
     */
    suspend fun confirmAddAccountFromMismatch(): CodexDeviceCodeLoginState {
        val pending = stateMutex.withLock {
            activeAttempt as? ActiveAttempt.MismatchPending
        } ?: return currentState
        return validateAndCommit(
            attemptId = pending.attemptId,
            session = pending.session,
        )
    }

    suspend fun cancelLatest(): CodexDeviceCodeLoginState =
        stateMutex.withLock {
            val attemptId = activeAttempt?.attemptId ?: return@withLock currentState
            activeAttempt = null
            pollInFlightAttemptId = null
            currentState = CodexDeviceCodeLoginState.Cancelled(attemptId)
            currentState
        }

    private suspend fun exchangeAndValidate(
        attempt: ActiveAttempt.Awaiting,
        authorization: DeviceCodeAuthorization,
    ): CodexDeviceCodeLoginState {
        stateMutex.withLock {
            if (!isLatestLocked(attempt.attemptId)) {
                return currentState
            }
            currentState = CodexDeviceCodeLoginState.ExchangingToken(attempt.attemptId)
        }

        val session = when (
            val result = tokenExchanger.exchange(
                CodexOAuthTokenExchangeRequest(
                    authorizationCode = OAuthAuthorizationCode(authorization.authorizationCode),
                    pkceVerifier = PkceVerifier(authorization.codeVerifier),
                    redirectUri = CodexOAuthConfig.DEVICE_REDIRECT_URI,
                ),
            )
        ) {
            is CodexOAuthTokenExchanger.Result.Failure -> {
                return setFailedIfLatest(
                    attemptId = attempt.attemptId,
                    safeMessageKey = result.error.safeMessageKey,
                )
            }
            is CodexOAuthTokenExchanger.Result.Success -> result.session
        }

        stateMutex.withLock {
            if (!isLatestLocked(attempt.attemptId)) {
                return currentState
            }
            val mismatchState = accountMismatchState(attempt, session)
            if (mismatchState != null) {
                // Re-login landed on a different Codex account. Hold the validated session so the user
                // can confirm adding it as a NEW account (or cancel); nothing is imported until then.
                currentState = mismatchState
                activeAttempt = ActiveAttempt.MismatchPending(
                    attemptId = attempt.attemptId,
                    mode = attempt.mode,
                    session = session,
                )
                pollInFlightAttemptId = null
                return currentState
            }
            activeAttempt = ActiveAttempt.ReadyToValidate(
                attemptId = attempt.attemptId,
                mode = attempt.mode,
                session = session,
            )
        }
        return validateAndCommit(
            attemptId = attempt.attemptId,
            session = session,
        )
    }

    private fun accountMismatchState(
        attempt: ActiveAttempt.Awaiting,
        session: CodexSessionPayload,
    ): CodexDeviceCodeLoginState.AccountMismatchDecision? {
        val mode = attempt.mode as? CodexDeviceCodeLoginMode.Relogin ?: return null
        val actualProviderAccountId = session.accountId
            ?.takeIf { it.isNotBlank() }
            ?.let(::ProviderAccountId)
        return if (actualProviderAccountId == mode.expectedProviderAccountId) {
            null
        } else {
            CodexDeviceCodeLoginState.AccountMismatchDecision(
                attemptId = attempt.attemptId,
                expectedProviderAccountId = mode.expectedProviderAccountId,
                actualProviderAccountId = actualProviderAccountId,
            )
        }
    }

    private suspend fun validateAndCommit(
        attemptId: DeviceCodeLoginAttemptId,
        session: CodexSessionPayload,
    ): CodexDeviceCodeLoginState {
        stateMutex.withLock {
            if (!isLatestLocked(attemptId)) {
                return currentState
            }
            currentState = CodexDeviceCodeLoginState.ValidatingUsage(attemptId)
        }

        val prepared = try {
            sessionImporter.prepareDeviceCodeSession(session)
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            CodexSessionImporter.PrepareResult.Failure("error_network", null)
        }

        return when (prepared) {
            is CodexSessionImporter.PrepareResult.Failure ->
                stateMutex.withLock {
                    if (isLatestLocked(attemptId)) {
                        currentState = CodexDeviceCodeLoginState.ValidationFailed(attemptId, prepared.message)
                    }
                    currentState
                }
            is CodexSessionImporter.PrepareResult.Success -> commitPreparedImport(
                attemptId = attemptId,
                preparedImport = prepared.preparedImport,
            )
        }
    }

    private suspend fun commitPreparedImport(
        attemptId: DeviceCodeLoginAttemptId,
        preparedImport: CodexSessionImporter.PreparedImport,
    ): CodexDeviceCodeLoginState =
        stateMutex.withLock {
            if (!isLatestLocked(attemptId)) {
                return@withLock currentState
            }
            try {
                when (val result = sessionImporter.commitPreparedDeviceCodeSession(preparedImport)) {
                    is CodexSessionImporter.Result.Failure -> {
                        currentState = CodexDeviceCodeLoginState.ValidationFailed(attemptId, result.message)
                        currentState
                    }
                    is CodexSessionImporter.Result.Success -> {
                        currentState = CodexDeviceCodeLoginState.Saved(
                            attemptId = attemptId,
                            account = result.account,
                            snapshot = result.snapshot,
                        )
                        activeAttempt = null
                        pollInFlightAttemptId = null
                        currentState
                    }
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                currentState = CodexDeviceCodeLoginState.ValidationFailed(attemptId, "error_network")
                currentState
            }
        }

    private suspend fun setFailedIfLatest(
        attemptId: DeviceCodeLoginAttemptId,
        safeMessageKey: String,
    ): CodexDeviceCodeLoginState =
        stateMutex.withLock {
            if (isLatestLocked(attemptId)) {
                currentState = CodexDeviceCodeLoginState.Failed(attemptId, safeMessageKey)
                activeAttempt = null
                pollInFlightAttemptId = null
            }
            currentState
        }

    private fun isLatestLocked(attemptId: DeviceCodeLoginAttemptId): Boolean =
        activeAttempt?.attemptId == attemptId

    private fun QuotaError.isTransientPollNetworkError(): Boolean =
        this is QuotaError.Network &&
            diagnosticsDigest == "codex_device_authorization_network_error"

    private sealed interface ActiveAttempt {
        val attemptId: DeviceCodeLoginAttemptId
        val mode: CodexDeviceCodeLoginMode

        data class Requesting(
            override val attemptId: DeviceCodeLoginAttemptId,
            override val mode: CodexDeviceCodeLoginMode,
        ) : ActiveAttempt

        data class Awaiting(
            override val attemptId: DeviceCodeLoginAttemptId,
            override val mode: CodexDeviceCodeLoginMode,
            val challenge: DeviceCodeChallenge,
            val expiresAt: Instant,
        ) : ActiveAttempt {
            fun toAwaitingState(): CodexDeviceCodeLoginState.AwaitingUserAuthorization =
                CodexDeviceCodeLoginState.AwaitingUserAuthorization(
                    attemptId = attemptId,
                    userCode = challenge.userCode,
                    verificationUri = challenge.verificationUri,
                    pollIntervalSeconds = challenge.intervalSeconds,
                    expiresAt = expiresAt,
                )

            fun toPollingState(): CodexDeviceCodeLoginState.PollingAuthorization =
                CodexDeviceCodeLoginState.PollingAuthorization(
                    attemptId = attemptId,
                    userCode = challenge.userCode,
                    verificationUri = challenge.verificationUri,
                    pollIntervalSeconds = challenge.intervalSeconds,
                    expiresAt = expiresAt,
                )
        }

        data class ReadyToValidate(
            override val attemptId: DeviceCodeLoginAttemptId,
            override val mode: CodexDeviceCodeLoginMode,
            val session: CodexSessionPayload,
        ) : ActiveAttempt

        /** A re-login whose account differs from the target, awaiting the user's add-or-cancel choice. */
        data class MismatchPending(
            override val attemptId: DeviceCodeLoginAttemptId,
            override val mode: CodexDeviceCodeLoginMode,
            val session: CodexSessionPayload,
        ) : ActiveAttempt
    }

    interface DeviceCodeClient {
        suspend fun requestDeviceCode(): CodexDeviceCodeClient.Result<DeviceCodeChallenge>

        suspend fun pollAuthorization(challenge: DeviceCodeChallenge): CodexDeviceCodeClient.Result<DeviceCodeAuthorization>
    }

    fun interface TokenExchanger {
        suspend fun exchange(request: CodexOAuthTokenExchangeRequest): CodexOAuthTokenExchanger.Result
    }

    interface SessionImporter {
        suspend fun prepareDeviceCodeSession(session: CodexSessionPayload): CodexSessionImporter.PrepareResult

        suspend fun commitPreparedDeviceCodeSession(
            preparedImport: CodexSessionImporter.PreparedImport,
        ): CodexSessionImporter.Result
    }
}
