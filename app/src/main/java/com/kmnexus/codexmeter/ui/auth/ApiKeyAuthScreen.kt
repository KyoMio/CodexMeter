package com.kmnexus.codexmeter.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.kmnexus.codexmeter.R
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.kmnexus.codexmeter.domain.model.ProviderId
import com.kmnexus.codexmeter.providers.ProviderRegistry
import com.kmnexus.codexmeter.ui.theme.CodexMeterTheme
import com.kmnexus.codexmeter.ui.theme.CodexMeterShapes
import com.kmnexus.codexmeter.ui.theme.CodexMeterSpacing
import kotlinx.coroutines.launch

/** A region option for API-key providers whose balance/usage API differs by platform (z.ai, MiniMax). */
data class ApiKeyAuthRegion(
    val label: String,
    val apiBaseUrl: String,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ApiKeyAuthScreen(
    providerId: ProviderId,
    onImportApiKey: suspend (apiKey: String, label: String?, apiBaseUrl: String?) -> Result<Unit>,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    regions: List<ApiKeyAuthRegion> = emptyList(),
    onSaved: () -> Unit = onBack,
) {
    val config = remember(providerId) { ProviderRegistry.configFor(providerId) }
    var apiKey by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    var keyVisible by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var selectedRegion by remember(providerId) { mutableStateOf(regions.firstOrNull()) }
    val scope = rememberCoroutineScope()
    val verificationFailed = stringResource(R.string.auth_verification_failed)
    val unexpectedError = stringResource(R.string.auth_error_unexpected)

    fun doImport() {
        if (apiKey.isBlank() || isLoading) return
        isLoading = true
        errorMessage = null
        scope.launch {
            try {
                val result = onImportApiKey(apiKey, label.ifBlank { null }, selectedRegion?.apiBaseUrl)
                if (result.isSuccess) {
                    onSaved()
                } else {
                    errorMessage = result.exceptionOrNull()?.message ?: verificationFailed
                }
            } catch (e: Exception) {
                errorMessage = e.message ?: unexpectedError
            } finally {
                isLoading = false
            }
        }
    }

    AuthScaffold(
        title = config.displayName,
        onBack = onBack,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = CodexMeterSpacing.xl)
                .padding(bottom = CodexMeterSpacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top,
        ) {
            Spacer(modifier = Modifier.height(CodexMeterSpacing.lg))

            Box(
                modifier = Modifier
                    .size(84.dp)
                    .clip(CircleShape)
                    .background(CodexMeterTheme.colors.accentSoft),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(id = config.iconResId),
                    contentDescription = config.displayName,
                    modifier = Modifier.size(44.dp),
                    tint = CodexMeterTheme.colors.primary,
                )
            }

            Spacer(modifier = Modifier.height(CodexMeterSpacing.md))

            Text(
                text = config.displayName,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )

            Spacer(modifier = Modifier.height(CodexMeterSpacing.sm))

            Text(
                text = stringResource(R.string.auth_api_key_hint_format, config.displayName),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (regions.isNotEmpty()) {
                Spacer(modifier = Modifier.height(CodexMeterSpacing.xl))
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.auth_select_region),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(CodexMeterSpacing.sm))
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(CodexMeterSpacing.sm),
                        verticalArrangement = Arrangement.spacedBy(CodexMeterSpacing.sm),
                    ) {
                        regions.forEach { region ->
                            RegionChip(
                                region = region,
                                selected = selectedRegion == region,
                                enabled = !isLoading,
                                onClick = { selectedRegion = region },
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(CodexMeterSpacing.xl))

            OutlinedTextField(
                value = apiKey,
                onValueChange = {
                    apiKey = it
                    errorMessage = null
                },
                label = { Text(stringResource(R.string.auth_api_key_field)) },
                placeholder = { Text("sk-…") },
                singleLine = true,
                visualTransformation = if (keyVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(
                    onDone = { doImport() },
                ),
                trailingIcon = {
                    TextButton(onClick = { keyVisible = !keyVisible }) {
                        Text(
                            if (keyVisible) {
                                stringResource(R.string.auth_toggle_hide)
                            } else {
                                stringResource(R.string.auth_toggle_show)
                            },
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = CodexMeterShapes.md,
                enabled = !isLoading,
            )

            Spacer(modifier = Modifier.height(CodexMeterSpacing.md))

            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text(stringResource(R.string.auth_api_key_label_optional)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { doImport() }),
                modifier = Modifier.fillMaxWidth(),
                shape = CodexMeterShapes.md,
                enabled = !isLoading,
            )

            Spacer(modifier = Modifier.height(CodexMeterSpacing.lg))

            if (errorMessage != null) {
                Text(
                    text = errorMessage!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = CodexMeterSpacing.md),
                )
            }

            Button(
                onClick = { doImport() },
                enabled = apiKey.isNotBlank() && !isLoading,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = CodexMeterShapes.md,
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text(stringResource(R.string.auth_action_verify_save))
                }
            }
        }
    }
}

@Composable
private fun RegionChip(
    region: ApiKeyAuthRegion,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(CodexMeterShapes.pill)
            .background(if (selected) CodexMeterTheme.colors.accentSoft else CodexMeterTheme.colors.surface)
            .border(
                width = 1.dp,
                color = if (selected) CodexMeterTheme.colors.accent else CodexMeterTheme.colors.border,
                shape = CodexMeterShapes.pill,
            )
            .selectable(selected = selected, enabled = enabled, onClick = onClick)
            .padding(horizontal = CodexMeterSpacing.lg, vertical = CodexMeterSpacing.sm),
    ) {
        Text(
            text = region.label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) CodexMeterTheme.colors.accent else MaterialTheme.colorScheme.onSurface,
        )
    }
}
