package com.kmnexus.codexmeter.providers.codex.auth

import com.kmnexus.codexmeter.domain.auth.DeviceCodeLoginController
import com.kmnexus.codexmeter.domain.auth.DeviceCodeLoginDiagnosticsReader
import com.kmnexus.codexmeter.domain.auth.DeviceCodeLoginDiagnosticsSnapshot
import com.kmnexus.codexmeter.domain.auth.DeviceCodeLoginResult
import com.kmnexus.codexmeter.domain.model.ProviderAccountId

class CodexDeviceCodeLoginController(
    private val useCase: CodexDeviceCodeLoginUseCase,
    private val onSaved: suspend (CodexDeviceCodeLoginState.Saved) -> Unit = {},
) : DeviceCodeLoginController, DeviceCodeLoginDiagnosticsReader {
    override suspend fun startLogin(): DeviceCodeLoginResult =
        useCase.startLogin().toDeviceCodeLoginResult(onSaved)

    override suspend fun startRelogin(expectedProviderAccountId: String): DeviceCodeLoginResult =
        useCase.startLogin(
            CodexDeviceCodeLoginMode.Relogin(ProviderAccountId(expectedProviderAccountId)),
        ).toDeviceCodeLoginResult(onSaved)

    override suspend fun confirmAddAccountFromMismatch(): DeviceCodeLoginResult =
        useCase.confirmAddAccountFromMismatch().toDeviceCodeLoginResult(onSaved)

    override suspend fun pollLatest(): DeviceCodeLoginResult =
        useCase.pollLatest().toDeviceCodeLoginResult(onSaved)

    override suspend fun retryValidation(): DeviceCodeLoginResult =
        useCase.retryValidation().toDeviceCodeLoginResult(onSaved)

    override suspend fun cancelLatest(): DeviceCodeLoginResult =
        useCase.cancelLatest().toDeviceCodeLoginResult(onSaved)

    override fun latestDeviceCodeLoginDiagnostics(): DeviceCodeLoginDiagnosticsSnapshot =
        useCase.currentState.toDeviceCodeLoginDiagnosticsSnapshot()
}

private fun CodexDeviceCodeLoginState.toDeviceCodeLoginDiagnosticsSnapshot(): DeviceCodeLoginDiagnosticsSnapshot =
    when (this) {
        CodexDeviceCodeLoginState.Idle -> DeviceCodeLoginDiagnosticsSnapshot(status = "idle")
        is CodexDeviceCodeLoginState.RequestingDeviceCode -> DeviceCodeLoginDiagnosticsSnapshot(
            status = "requesting_device_code",
            attemptId = attemptId.value,
        )
        is CodexDeviceCodeLoginState.AwaitingUserAuthorization -> DeviceCodeLoginDiagnosticsSnapshot(
            status = "awaiting_user_authorization",
            attemptId = attemptId.value,
            verificationUriStatus = verificationUri.hostStatus(),
            pollIntervalSeconds = pollIntervalSeconds,
            expiresAt = expiresAt,
        )
        is CodexDeviceCodeLoginState.PollingAuthorization -> DeviceCodeLoginDiagnosticsSnapshot(
            status = "polling_authorization",
            attemptId = attemptId.value,
            verificationUriStatus = verificationUri.hostStatus(),
            pollIntervalSeconds = pollIntervalSeconds,
            expiresAt = expiresAt,
        )
        is CodexDeviceCodeLoginState.ExchangingToken -> DeviceCodeLoginDiagnosticsSnapshot(
            status = "exchanging_token",
            attemptId = attemptId.value,
        )
        is CodexDeviceCodeLoginState.ValidatingUsage -> DeviceCodeLoginDiagnosticsSnapshot(
            status = "validating_usage",
            attemptId = attemptId.value,
        )
        is CodexDeviceCodeLoginState.ValidationFailed -> DeviceCodeLoginDiagnosticsSnapshot(
            status = "validation_failed",
            attemptId = attemptId.value,
            safeErrorCode = safeMessageKey,
        )
        is CodexDeviceCodeLoginState.Saved -> DeviceCodeLoginDiagnosticsSnapshot(
            status = "saved",
            attemptId = attemptId.value,
        )
        is CodexDeviceCodeLoginState.AccountMismatchDecision -> DeviceCodeLoginDiagnosticsSnapshot(
            status = "account_mismatch_decision",
            attemptId = attemptId.value,
            safeErrorCode = "error_device_code_account_mismatch",
        )
        is CodexDeviceCodeLoginState.Expired -> DeviceCodeLoginDiagnosticsSnapshot(
            status = "expired",
            attemptId = attemptId.value,
        )
        is CodexDeviceCodeLoginState.Cancelled -> DeviceCodeLoginDiagnosticsSnapshot(
            status = "cancelled",
            attemptId = attemptId.value,
        )
        is CodexDeviceCodeLoginState.Failed -> DeviceCodeLoginDiagnosticsSnapshot(
            status = "failed",
            attemptId = attemptId?.value,
            safeErrorCode = safeMessageKey,
        )
    }

private suspend fun CodexDeviceCodeLoginState.toDeviceCodeLoginResult(
    onSaved: suspend (CodexDeviceCodeLoginState.Saved) -> Unit,
): DeviceCodeLoginResult =
    when (this) {
        CodexDeviceCodeLoginState.Idle -> DeviceCodeLoginResult.Idle
        is CodexDeviceCodeLoginState.RequestingDeviceCode -> DeviceCodeLoginResult.RequestingDeviceCode(
            attemptId = attemptId.value,
        )
        is CodexDeviceCodeLoginState.AwaitingUserAuthorization -> DeviceCodeLoginResult.AwaitingUserAuthorization(
            attemptId = attemptId.value,
            userCode = userCode,
            verificationUri = verificationUri,
            pollIntervalSeconds = pollIntervalSeconds,
            expiresAt = expiresAt,
        )
        is CodexDeviceCodeLoginState.PollingAuthorization -> DeviceCodeLoginResult.PollingAuthorization(
            attemptId = attemptId.value,
            userCode = userCode,
            verificationUri = verificationUri,
            pollIntervalSeconds = pollIntervalSeconds,
            expiresAt = expiresAt,
        )
        is CodexDeviceCodeLoginState.ExchangingToken -> DeviceCodeLoginResult.ExchangingToken(
            attemptId = attemptId.value,
        )
        is CodexDeviceCodeLoginState.ValidatingUsage -> DeviceCodeLoginResult.ValidatingUsage(
            attemptId = attemptId.value,
        )
        is CodexDeviceCodeLoginState.ValidationFailed -> DeviceCodeLoginResult.ValidationFailed(
            attemptId = attemptId.value,
            safeMessageKey = safeMessageKey,
        )
        is CodexDeviceCodeLoginState.Saved -> {
            onSaved(this)
            DeviceCodeLoginResult.Saved(
                attemptId = attemptId.value,
                account = account,
            )
        }
        is CodexDeviceCodeLoginState.AccountMismatchDecision -> DeviceCodeLoginResult.AccountMismatchDecision(
            attemptId = attemptId.value,
        )
        is CodexDeviceCodeLoginState.Expired -> DeviceCodeLoginResult.Expired(
            attemptId = attemptId.value,
        )
        is CodexDeviceCodeLoginState.Cancelled -> DeviceCodeLoginResult.Cancelled(
            attemptId = attemptId.value,
        )
        is CodexDeviceCodeLoginState.Failed -> DeviceCodeLoginResult.Failed(
            attemptId = attemptId?.value,
            safeMessageKey = safeMessageKey,
        )
    }

private fun String.hostStatus(): String =
    when {
        startsWith("https://auth.openai.com/", ignoreCase = true) -> "official_auth_openai"
        startsWith("https://", ignoreCase = true) -> "https_other"
        else -> "invalid_or_insecure"
    }
