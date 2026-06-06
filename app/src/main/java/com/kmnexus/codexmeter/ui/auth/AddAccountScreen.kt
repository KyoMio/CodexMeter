package com.kmnexus.codexmeter.ui.auth

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kmnexus.codexmeter.R
import com.kmnexus.codexmeter.domain.auth.DeviceCodeLoginController
import com.kmnexus.codexmeter.domain.auth.DeviceCodeLoginNotifier
import com.kmnexus.codexmeter.domain.auth.NoopDeviceCodeLoginController
import com.kmnexus.codexmeter.domain.auth.NoopDeviceCodeLoginNotifier
import com.kmnexus.codexmeter.ui.theme.CodexMeterTheme
import com.kmnexus.codexmeter.ui.theme.CodexMeterShapes
import com.kmnexus.codexmeter.ui.theme.CodexMeterSpacing

@Composable
fun AddAccountRoute(
    modifier: Modifier = Modifier,
    entryMode: AddAccountEntryMode = AddAccountEntryMode.Choose,
    deviceCodeLoginController: DeviceCodeLoginController = NoopDeviceCodeLoginController,
    deviceCodeLoginNotifier: DeviceCodeLoginNotifier = NoopDeviceCodeLoginNotifier,
    viewModel: DeviceCodeLoginViewModel = viewModel(
        factory = DeviceCodeLoginViewModel.factory(
            controller = deviceCodeLoginController,
            notifier = deviceCodeLoginNotifier,
        ),
    ),
    onBackClick: () -> Unit = {},
    onLoginSaved: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    LaunchedEffect(entryMode, viewModel) {
        viewModel.applyEntryMode(entryMode)
    }
    LaunchedEffect(uiState.shouldNavigateHomeAfterSave, uiState.attemptId) {
        if (uiState.shouldNavigateHomeAfterSave) {
            onLoginSaved()
        }
    }

    AddAccountScreen(
        uiState = uiState,
        modifier = modifier,
        onBackClick = {
            viewModel.cancelLogin()
            onBackClick()
        },
        onStartLoginClick = viewModel::startCodexDeviceCodeLogin,
        onCopyCodeClick = { code -> context.copyToClipboard(code) },
        onOpenVerificationClick = { uri -> uriHandler.openUri(uri) },
        onRetryValidationClick = viewModel::retryValidation,
        onCancelLoginClick = viewModel::cancelLogin,
        onConfirmAddMismatchClick = viewModel::confirmAddAccountFromMismatch,
        onCancelMismatchClick = {
            viewModel.cancelLogin()
            onBackClick()
        },
    )
}

@Composable
fun AddAccountScreen(
    uiState: DeviceCodeLoginUiState,
    modifier: Modifier = Modifier,
    onBackClick: () -> Unit = {},
    onStartLoginClick: () -> Unit = {},
    onCopyCodeClick: (String) -> Unit = {},
    onOpenVerificationClick: (String) -> Unit = {},
    onRetryValidationClick: () -> Unit = {},
    onCancelLoginClick: () -> Unit = {},
    onConfirmAddMismatchClick: () -> Unit = {},
    onCancelMismatchClick: () -> Unit = {},
) {
    if (uiState.status == DeviceCodeLoginUiStatus.AccountMismatchDecision) {
        AccountMismatchDialog(
            onConfirm = onConfirmAddMismatchClick,
            onDismiss = onCancelMismatchClick,
        )
    }
    AuthScaffold(
        title = stringResource(R.string.add_account_title),
        onBack = onBackClick,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = CodexMeterSpacing.xl)
                .padding(top = CodexMeterSpacing.sm, bottom = CodexMeterSpacing.xxl),
            verticalArrangement = Arrangement.spacedBy(CodexMeterSpacing.lg),
        ) {
            Text(
                text = stringResource(R.string.add_account_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AddAccountDeviceCodeCard(
                uiState = uiState,
                onStartLoginClick = onStartLoginClick,
                onCopyCodeClick = onCopyCodeClick,
                onOpenVerificationClick = onOpenVerificationClick,
                onRetryValidationClick = onRetryValidationClick,
                onCancelLoginClick = onCancelLoginClick,
            )

            Spacer(modifier = Modifier.height(CodexMeterSpacing.bottomNavigationClearance))
        }
    }
}

@Composable
private fun AddAccountDeviceCodeCard(
    uiState: DeviceCodeLoginUiState,
    onStartLoginClick: () -> Unit,
    onCopyCodeClick: (String) -> Unit,
    onOpenVerificationClick: (String) -> Unit,
    onRetryValidationClick: () -> Unit,
    onCancelLoginClick: () -> Unit,
) {
    AddAccountSurfaceCard {
        Column(verticalArrangement = Arrangement.spacedBy(CodexMeterSpacing.md)) {
            Text(
                text = stringResource(R.string.add_account_codex_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            AddAccountStatusText(uiState = uiState)
            uiState.userCode?.let { code ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(CodexMeterSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.add_account_device_code_format, code),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    TextButton(onClick = { onCopyCodeClick(code) }) {
                        Text(text = stringResource(R.string.add_account_copy_code))
                    }
                }
            }
            uiState.verificationUri?.let { verificationUri ->
                Button(
                    onClick = { onOpenVerificationClick(verificationUri) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = CodexMeterShapes.md,
                ) {
                    Text(text = stringResource(R.string.add_account_open_verification_page))
                }
            }
            AddAccountActionRow(
                status = uiState.status,
                onStartLoginClick = onStartLoginClick,
                onRetryValidationClick = onRetryValidationClick,
                onCancelLoginClick = onCancelLoginClick,
            )
        }
    }
}

@Composable
private fun AddAccountStatusText(uiState: DeviceCodeLoginUiState) {
    val errorMessageResId = uiState.errorMessageResId
    val connectedAccount = uiState.connectedAccount
    val text = when {
        errorMessageResId != null -> stringResource(errorMessageResId)
        connectedAccount != null -> stringResource(R.string.add_account_device_code_connected, connectedAccount.displayName)
        else -> when (uiState.status) {
            DeviceCodeLoginUiStatus.Idle -> stringResource(R.string.add_account_device_code_idle)
            DeviceCodeLoginUiStatus.RequestingDeviceCode -> stringResource(R.string.add_account_device_code_requesting)
            DeviceCodeLoginUiStatus.AwaitingUserAuthorization -> stringResource(R.string.add_account_device_code_waiting)
            DeviceCodeLoginUiStatus.PollingAuthorization -> stringResource(R.string.add_account_device_code_polling)
            DeviceCodeLoginUiStatus.ExchangingToken -> stringResource(R.string.add_account_device_code_exchanging)
            DeviceCodeLoginUiStatus.ValidatingUsage -> stringResource(R.string.add_account_device_code_validating)
            DeviceCodeLoginUiStatus.ValidationFailed -> stringResource(R.string.add_account_device_code_validation_failed)
            DeviceCodeLoginUiStatus.Saved -> stringResource(R.string.add_account_device_code_saved)
            DeviceCodeLoginUiStatus.AccountMismatchDecision -> stringResource(R.string.add_account_device_code_account_mismatch)
            DeviceCodeLoginUiStatus.Expired -> stringResource(R.string.add_account_device_code_expired)
            DeviceCodeLoginUiStatus.Cancelled -> stringResource(R.string.add_account_device_code_cancelled)
            DeviceCodeLoginUiStatus.Failed -> stringResource(R.string.add_account_device_code_failed)
        }
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (errorMessageResId != null) {
            MaterialTheme.colorScheme.error
        } else if (connectedAccount != null || uiState.status == DeviceCodeLoginUiStatus.Saved) {
            CodexMeterTheme.colors.success
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
}

@Composable
private fun AddAccountActionRow(
    status: DeviceCodeLoginUiStatus,
    onStartLoginClick: () -> Unit,
    onRetryValidationClick: () -> Unit,
    onCancelLoginClick: () -> Unit,
) {
    when (status) {
        DeviceCodeLoginUiStatus.RequestingDeviceCode,
        DeviceCodeLoginUiStatus.ExchangingToken,
        DeviceCodeLoginUiStatus.ValidatingUsage -> Row(
            horizontalArrangement = Arrangement.spacedBy(CodexMeterSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Text(
                text = stringResource(R.string.add_account_device_code_in_progress),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DeviceCodeLoginUiStatus.AwaitingUserAuthorization,
        DeviceCodeLoginUiStatus.PollingAuthorization -> OutlinedButton(
            onClick = onCancelLoginClick,
            modifier = Modifier.fillMaxWidth(),
            shape = CodexMeterShapes.md,
        ) {
            Text(text = stringResource(R.string.add_account_cancel_login))
        }
        DeviceCodeLoginUiStatus.ValidationFailed -> Row(
            horizontalArrangement = Arrangement.spacedBy(CodexMeterSpacing.sm),
        ) {
            Button(onClick = onRetryValidationClick, shape = CodexMeterShapes.md) {
                Text(text = stringResource(R.string.add_account_retry_validation))
            }
            OutlinedButton(onClick = onStartLoginClick, shape = CodexMeterShapes.md) {
                Text(text = stringResource(R.string.add_account_restart_login))
            }
        }
        DeviceCodeLoginUiStatus.Idle,
        DeviceCodeLoginUiStatus.Expired,
        DeviceCodeLoginUiStatus.Cancelled,
        DeviceCodeLoginUiStatus.Failed,
        DeviceCodeLoginUiStatus.AccountMismatchDecision,
        DeviceCodeLoginUiStatus.Saved -> Button(
            onClick = onStartLoginClick,
            modifier = Modifier.fillMaxWidth(),
            shape = CodexMeterShapes.md,
        ) {
            Text(text = stringResource(R.string.add_account_start_device_code_login))
        }
    }
}

@Composable
private fun AccountMismatchDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.add_account_mismatch_dialog_title)) },
        text = {
            Text(
                text = stringResource(R.string.add_account_mismatch_dialog_message),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = stringResource(R.string.add_account_mismatch_dialog_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.add_account_mismatch_dialog_cancel))
            }
        },
    )
}

@Composable
private fun AddAccountSurfaceCard(content: @Composable () -> Unit) {
    Card(
        shape = CodexMeterShapes.xl,
        colors = CardDefaults.cardColors(containerColor = CodexMeterTheme.colors.surface),
        border = BorderStroke(1.dp, CodexMeterTheme.colors.border),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(CodexMeterSpacing.lg),
        ) {
            content()
        }
    }
}

private fun Context.copyToClipboard(text: String) {
    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.add_account_copy_code), text))
}
