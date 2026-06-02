package com.kmnexus.codexmeter.domain.auth

import com.kmnexus.codexmeter.domain.model.ProviderAccount
import java.time.Instant

sealed interface DeviceCodeLoginResult {
    data object Idle : DeviceCodeLoginResult

    data class RequestingDeviceCode(
        val attemptId: String,
    ) : DeviceCodeLoginResult

    data class AwaitingUserAuthorization(
        val attemptId: String,
        val userCode: String,
        val verificationUri: String,
        val pollIntervalSeconds: Int,
        val expiresAt: Instant,
    ) : DeviceCodeLoginResult

    data class PollingAuthorization(
        val attemptId: String,
        val userCode: String,
        val verificationUri: String,
        val pollIntervalSeconds: Int,
        val expiresAt: Instant,
    ) : DeviceCodeLoginResult

    data class ExchangingToken(
        val attemptId: String,
    ) : DeviceCodeLoginResult

    data class ValidatingUsage(
        val attemptId: String,
    ) : DeviceCodeLoginResult

    data class ValidationFailed(
        val attemptId: String,
        val safeMessageKey: String,
    ) : DeviceCodeLoginResult

    data class Saved(
        val attemptId: String,
        val account: ProviderAccount,
    ) : DeviceCodeLoginResult

    data class AccountMismatchDecision(
        val attemptId: String,
        val safeMessageKey: String = "error_device_code_account_mismatch",
    ) : DeviceCodeLoginResult

    data class Expired(
        val attemptId: String,
    ) : DeviceCodeLoginResult

    data class Cancelled(
        val attemptId: String,
    ) : DeviceCodeLoginResult

    data class Failed(
        val attemptId: String?,
        val safeMessageKey: String,
    ) : DeviceCodeLoginResult
}

interface DeviceCodeLoginController {
    suspend fun startLogin(): DeviceCodeLoginResult

    /** Re-authenticates an existing Codex account, flagging a mismatch if a different account signs in. */
    suspend fun startRelogin(expectedProviderAccountId: String): DeviceCodeLoginResult =
        DeviceCodeLoginResult.Failed(null, "error_login_not_wired")

    /** After an account-mismatch decision, imports the signed-in account as a new account. */
    suspend fun confirmAddAccountFromMismatch(): DeviceCodeLoginResult =
        DeviceCodeLoginResult.Failed(null, "error_login_not_wired")

    suspend fun pollLatest(): DeviceCodeLoginResult

    suspend fun retryValidation(): DeviceCodeLoginResult

    suspend fun cancelLatest(): DeviceCodeLoginResult
}

data class DeviceCodeLoginDiagnosticsSnapshot(
    val status: String,
    val attemptId: String? = null,
    val safeErrorCode: String? = null,
    val verificationUriStatus: String? = null,
    val pollIntervalSeconds: Int? = null,
    val expiresAt: Instant? = null,
)

fun interface DeviceCodeLoginDiagnosticsReader {
    fun latestDeviceCodeLoginDiagnostics(): DeviceCodeLoginDiagnosticsSnapshot
}

object NoopDeviceCodeLoginDiagnosticsReader : DeviceCodeLoginDiagnosticsReader {
    override fun latestDeviceCodeLoginDiagnostics(): DeviceCodeLoginDiagnosticsSnapshot =
        DeviceCodeLoginDiagnosticsSnapshot(status = "not_wired")
}

object NoopDeviceCodeLoginController : DeviceCodeLoginController {
    override suspend fun startLogin(): DeviceCodeLoginResult =
        DeviceCodeLoginResult.Failed(null, "error_login_not_wired")

    override suspend fun pollLatest(): DeviceCodeLoginResult =
        DeviceCodeLoginResult.Failed(null, "error_login_not_wired")

    override suspend fun retryValidation(): DeviceCodeLoginResult =
        DeviceCodeLoginResult.Failed(null, "error_login_not_wired")

    override suspend fun cancelLatest(): DeviceCodeLoginResult =
        DeviceCodeLoginResult.Cancelled("")
}

interface DeviceCodeLoginNotifier {
    fun waitingForAuthorization(attemptId: String, userCode: String, verificationUri: String)

    fun connected(accountDisplayName: String?)

    fun expired()

    fun failed(safeMessageKey: String)

    fun validationFailed(safeMessageKey: String)

    fun cancelled()
}

object NoopDeviceCodeLoginNotifier : DeviceCodeLoginNotifier {
    override fun waitingForAuthorization(attemptId: String, userCode: String, verificationUri: String) = Unit

    override fun connected(accountDisplayName: String?) = Unit

    override fun expired() = Unit

    override fun failed(safeMessageKey: String) = Unit

    override fun validationFailed(safeMessageKey: String) = Unit

    override fun cancelled() = Unit
}
