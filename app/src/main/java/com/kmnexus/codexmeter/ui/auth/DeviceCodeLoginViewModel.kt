package com.kmnexus.codexmeter.ui.auth

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.kmnexus.codexmeter.R
import com.kmnexus.codexmeter.domain.auth.DeviceCodeLoginController
import com.kmnexus.codexmeter.domain.auth.DeviceCodeLoginNotifier
import com.kmnexus.codexmeter.domain.auth.DeviceCodeLoginResult
import com.kmnexus.codexmeter.domain.auth.NoopDeviceCodeLoginNotifier
import com.kmnexus.codexmeter.domain.model.ProviderAccount
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class DeviceCodeLoginUiStatus {
    Idle,
    RequestingDeviceCode,
    AwaitingUserAuthorization,
    PollingAuthorization,
    ExchangingToken,
    ValidatingUsage,
    ValidationFailed,
    Saved,
    AccountMismatchDecision,
    Expired,
    Cancelled,
    Failed,
}

data class DeviceCodeLoginUiState(
    val status: DeviceCodeLoginUiStatus,
    val attemptId: String?,
    val userCode: String?,
    val expiresAt: Instant?,
    val pollIntervalSeconds: Int?,
    val verificationUri: String?,
    val connectedAccount: ProviderAccount?,
    @get:StringRes val errorMessageResId: Int?,
    val shouldNavigateHomeAfterSave: Boolean,
) {
    companion object {
        val Empty = DeviceCodeLoginUiState(
            status = DeviceCodeLoginUiStatus.Idle,
            attemptId = null,
            userCode = null,
            expiresAt = null,
            pollIntervalSeconds = null,
            verificationUri = null,
            connectedAccount = null,
            errorMessageResId = null,
            shouldNavigateHomeAfterSave = false,
        )
    }
}

class DeviceCodeLoginViewModel(
    private val controller: DeviceCodeLoginController,
    private val notifier: DeviceCodeLoginNotifier = NoopDeviceCodeLoginNotifier,
) : ViewModel() {
    private val _uiState = MutableStateFlow(DeviceCodeLoginUiState.Empty)
    val uiState: StateFlow<DeviceCodeLoginUiState> = _uiState.asStateFlow()

    private var appliedEntryMode: AddAccountEntryMode? = null
    private var pollJob: Job? = null

    fun applyEntryMode(entryMode: AddAccountEntryMode) {
        if (entryMode is AddAccountEntryMode.ProviderSelection || appliedEntryMode == entryMode) {
            return
        }
        appliedEntryMode = entryMode
        when (entryMode) {
            is AddAccountEntryMode.ProviderSelection -> Unit
            is AddAccountEntryMode.LoginToCodex -> startCodexDeviceCodeLogin()
            is AddAccountEntryMode.CodexRelogin -> startCodexRelogin(entryMode.expectedProviderAccountId)
            is AddAccountEntryMode.ApiKeyInput -> Unit // handled by the API-key screen
            is AddAccountEntryMode.WebViewCookieAuth -> Unit // handled by the WebView screen
            is AddAccountEntryMode.WebViewOAuthPkce -> Unit // handled by the WebView screen
        }
    }

    fun startCodexDeviceCodeLogin() {
        pollJob?.cancel()
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                applyResult(controller.startLogin())
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                applyResult(DeviceCodeLoginResult.Failed(null, "error_unknown"))
            }
        }
    }

    /**
     * Re-login for an existing Codex account. When the account's identity is known we ask the controller
     * to flag a different-account sign-in; otherwise it degrades to a plain login.
     */
    fun startCodexRelogin(expectedProviderAccountId: String?) {
        pollJob?.cancel()
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val result = if (expectedProviderAccountId.isNullOrBlank()) {
                    controller.startLogin()
                } else {
                    controller.startRelogin(expectedProviderAccountId)
                }
                applyResult(result)
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                applyResult(DeviceCodeLoginResult.Failed(null, "error_unknown"))
            }
        }
    }

    /** User chose to add the (different) signed-in account after an account-mismatch decision. */
    fun confirmAddAccountFromMismatch() {
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                applyResult(controller.confirmAddAccountFromMismatch())
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                applyResult(DeviceCodeLoginResult.Failed(_uiState.value.attemptId, "error_unknown"))
            }
        }
    }

    fun retryValidation() {
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                applyResult(controller.retryValidation())
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                applyResult(DeviceCodeLoginResult.Failed(_uiState.value.attemptId, "error_unknown"))
            }
        }
    }

    fun cancelLogin() {
        pollJob?.cancel()
        pollJob = null
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                applyResult(controller.cancelLatest())
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                applyResult(DeviceCodeLoginResult.Cancelled(_uiState.value.attemptId.orEmpty()))
            }
        }
    }

    private fun schedulePoll(result: DeviceCodeLoginResult.AwaitingUserAuthorization) {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            var nextPoll = result
            while (true) {
                delay(nextPoll.pollIntervalSeconds.coerceAtLeast(1) * 1_000L)
                if (_uiState.value.attemptId != nextPoll.attemptId) {
                    return@launch
                }
                applyResult(DeviceCodeLoginResult.PollingAuthorization(
                    attemptId = nextPoll.attemptId,
                    userCode = nextPoll.userCode,
                    verificationUri = nextPoll.verificationUri,
                    pollIntervalSeconds = nextPoll.pollIntervalSeconds,
                    expiresAt = nextPoll.expiresAt,
                ))
                val pollResult = try {
                    controller.pollLatest()
                } catch (exception: CancellationException) {
                    throw exception
                } catch (_: Exception) {
                    DeviceCodeLoginResult.Failed(nextPoll.attemptId, "error_unknown")
                }
                if (_uiState.value.attemptId != nextPoll.attemptId) {
                    return@launch
                }
                if (pollResult is DeviceCodeLoginResult.AwaitingUserAuthorization) {
                    _uiState.value = _uiState.value.copy(
                        status = DeviceCodeLoginUiStatus.AwaitingUserAuthorization,
                        attemptId = pollResult.attemptId,
                        userCode = pollResult.userCode,
                        expiresAt = pollResult.expiresAt,
                        pollIntervalSeconds = pollResult.pollIntervalSeconds,
                        verificationUri = pollResult.verificationUri,
                        errorMessageResId = null,
                    )
                    nextPoll = pollResult
                } else {
                    applyResult(pollResult)
                    return@launch
                }
            }
        }
    }

    private fun applyResult(result: DeviceCodeLoginResult) {
        when (result) {
            DeviceCodeLoginResult.Idle -> {
                pollJob?.cancel()
                pollJob = null
                _uiState.value = DeviceCodeLoginUiState.Empty
            }
            is DeviceCodeLoginResult.RequestingDeviceCode -> {
                _uiState.value = DeviceCodeLoginUiState.Empty.copy(
                    status = DeviceCodeLoginUiStatus.RequestingDeviceCode,
                    attemptId = result.attemptId,
                )
            }
            is DeviceCodeLoginResult.AwaitingUserAuthorization -> {
                _uiState.value = _uiState.value.copy(
                    status = DeviceCodeLoginUiStatus.AwaitingUserAuthorization,
                    attemptId = result.attemptId,
                    userCode = result.userCode,
                    expiresAt = result.expiresAt,
                    pollIntervalSeconds = result.pollIntervalSeconds,
                    verificationUri = result.verificationUri,
                    connectedAccount = null,
                    errorMessageResId = null,
                )
                notifier.waitingForAuthorization(result.attemptId, result.userCode, result.verificationUri)
                schedulePoll(result)
            }
            is DeviceCodeLoginResult.PollingAuthorization -> {
                _uiState.value = _uiState.value.copy(
                    status = DeviceCodeLoginUiStatus.PollingAuthorization,
                    attemptId = result.attemptId,
                    userCode = result.userCode,
                    expiresAt = result.expiresAt,
                    pollIntervalSeconds = result.pollIntervalSeconds,
                    verificationUri = result.verificationUri,
                    errorMessageResId = null,
                )
            }
            is DeviceCodeLoginResult.ExchangingToken -> {
                _uiState.value = _uiState.value.copy(
                    status = DeviceCodeLoginUiStatus.ExchangingToken,
                    attemptId = result.attemptId,
                    errorMessageResId = null,
                )
            }
            is DeviceCodeLoginResult.ValidatingUsage -> {
                _uiState.value = _uiState.value.copy(
                    status = DeviceCodeLoginUiStatus.ValidatingUsage,
                    attemptId = result.attemptId,
                    errorMessageResId = null,
                )
            }
            is DeviceCodeLoginResult.ValidationFailed -> {
                pollJob?.cancel()
                pollJob = null
                _uiState.value = _uiState.value.copy(
                    status = DeviceCodeLoginUiStatus.ValidationFailed,
                    attemptId = result.attemptId,
                    errorMessageResId = result.safeMessageKey.toDeviceCodeLoginErrorResId(),
                )
                notifier.validationFailed(result.safeMessageKey)
            }
            is DeviceCodeLoginResult.Saved -> {
                pollJob?.cancel()
                pollJob = null
                _uiState.value = _uiState.value.copy(
                    status = DeviceCodeLoginUiStatus.Saved,
                    attemptId = result.attemptId,
                    verificationUri = null,
                    connectedAccount = result.account,
                    errorMessageResId = null,
                    shouldNavigateHomeAfterSave = true,
                )
                notifier.connected(result.account.displayName)
            }
            is DeviceCodeLoginResult.AccountMismatchDecision -> {
                pollJob?.cancel()
                pollJob = null
                _uiState.value = _uiState.value.copy(
                    status = DeviceCodeLoginUiStatus.AccountMismatchDecision,
                    attemptId = result.attemptId,
                    errorMessageResId = result.safeMessageKey.toDeviceCodeLoginErrorResId(),
                )
                notifier.failed(result.safeMessageKey)
            }
            is DeviceCodeLoginResult.Expired -> {
                pollJob?.cancel()
                pollJob = null
                _uiState.value = _uiState.value.copy(
                    status = DeviceCodeLoginUiStatus.Expired,
                    attemptId = result.attemptId,
                    verificationUri = null,
                    errorMessageResId = null,
                )
                notifier.expired()
            }
            is DeviceCodeLoginResult.Cancelled -> {
                pollJob?.cancel()
                pollJob = null
                _uiState.value = _uiState.value.copy(
                    status = DeviceCodeLoginUiStatus.Cancelled,
                    attemptId = result.attemptId,
                    userCode = null,
                    expiresAt = null,
                    pollIntervalSeconds = null,
                    verificationUri = null,
                    errorMessageResId = null,
                )
                notifier.cancelled()
            }
            is DeviceCodeLoginResult.Failed -> {
                pollJob?.cancel()
                pollJob = null
                _uiState.value = _uiState.value.copy(
                    status = DeviceCodeLoginUiStatus.Failed,
                    attemptId = result.attemptId ?: _uiState.value.attemptId,
                    verificationUri = null,
                    errorMessageResId = result.safeMessageKey.toDeviceCodeLoginErrorResId(),
                )
                notifier.failed(result.safeMessageKey)
            }
        }
    }

    override fun onCleared() {
        pollJob?.cancel()
        super.onCleared()
    }

    companion object {
        fun factory(
            controller: DeviceCodeLoginController,
            notifier: DeviceCodeLoginNotifier = NoopDeviceCodeLoginNotifier,
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    DeviceCodeLoginViewModel(
                        controller = controller,
                        notifier = notifier,
                    ) as T
            }
    }
}

@StringRes
internal fun String.toDeviceCodeLoginErrorResId(): Int =
    when (this) {
        "error_network" -> R.string.error_network
        "error_auth_required" -> R.string.error_auth_required
        "error_session_persistence" -> R.string.error_session_persistence
        "error_login_not_wired" -> R.string.error_login_not_wired
        "error_device_code_account_mismatch" -> R.string.add_account_device_code_account_mismatch
        else -> R.string.error_unknown
    }
