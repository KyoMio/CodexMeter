package com.kmnexus.codexmeter.widget

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.kmnexus.codexmeter.R
import com.kmnexus.codexmeter.domain.model.ProviderAccount
import com.kmnexus.codexmeter.ui.theme.CodexMeterShapes
import com.kmnexus.codexmeter.ui.theme.CodexMeterSpacing

/** 多选切换：按 available（天然顺序）重排，已满 WIDGET_MAX_FIELDS 时忽略新增。 */
internal fun toggleWindowSelection(
    current: List<String>,
    windowId: String,
    available: List<String>,
): List<String> {
    val next = if (current.contains(windowId)) {
        current - windowId
    } else {
        if (current.size >= WIDGET_MAX_FIELDS) return current
        current + windowId
    }
    return available.filter { next.contains(it) }
}

/** 切换账号后，仅保留新账号可用的窗口，按 available 顺序。 */
internal fun retainAvailableWindowIds(current: List<String>, available: List<String>): List<String> =
    available.filter { current.contains(it) }

internal data class WidgetWindowOption(
    val windowId: String,
    @get:androidx.annotation.StringRes val labelResId: Int,
)

internal data class WidgetConfigurationScreenState(
    val accounts: List<ProviderAccount> = emptyList(),
    val selectedProviderId: String? = null,
    val selectedLocalAccountId: String? = null,
    val selectedWindowIds: List<String> = emptyList(),
    val defaultWindowId: String = FIVE_HOUR_WINDOW_ID,
    val currentAccountId: String? = null,
    val windowOptionsByAccount: Map<String, List<WidgetWindowOption>> = emptyMap(),
) {
    val selectedAccount: WidgetConfigurationAccountSelection?
        get() = if (selectedProviderId.isNullOrBlank() || selectedLocalAccountId.isNullOrBlank()) {
            null
        } else {
            WidgetConfigurationAccountSelection(selectedProviderId, selectedLocalAccountId)
        }

    /** Quota windows offered for the selected account, falling back to Codex 5h/weekly. */
    val windowOptions: List<WidgetWindowOption>
        get() = selectedLocalAccountId
            ?.let { windowOptionsByAccount[it] }
            ?.takeIf { it.isNotEmpty() }
            ?: DEFAULT_WINDOW_OPTIONS
}

private val DEFAULT_WINDOW_OPTIONS = listOf(
    WidgetWindowOption(FIVE_HOUR_WINDOW_ID, R.string.settings_primary_window_five_hour),
    WidgetWindowOption(WEEKLY_WINDOW_ID, R.string.settings_primary_window_weekly),
)

internal data class WidgetConfigurationAccountSelection(
    val providerId: String,
    val localAccountId: String,
)

@Composable
internal fun WidgetConfigurationRoute(
    appWidgetId: Int,
    loadState: suspend () -> WidgetConfigurationScreenState,
    onCancel: () -> Unit,
    onSave: (WidgetConfigurationAccountSelection?, List<String>) -> Unit,
) {
    var state by remember(appWidgetId) { mutableStateOf<WidgetConfigurationScreenState?>(null) }
    var isSaving by remember(appWidgetId) { mutableStateOf(false) }
    var expandedSection by remember(appWidgetId) {
        mutableStateOf<WidgetConfigurationSection?>(WidgetConfigurationSection.Account)
    }

    LaunchedEffect(appWidgetId) {
        state = loadState()
    }

    val currentState = state
    if (currentState == null) {
        LoadingConfigurationScreen()
    } else {
        WidgetConfigurationScreen(
            state = currentState,
            expandedSection = expandedSection,
            isSaving = isSaving,
            onSectionToggle = { section ->
                expandedSection = if (expandedSection == section) null else section
            },
            onAccountSelected = { account ->
                val options = currentState.windowOptionsByAccount[account.localAccountId.value]
                    ?.takeIf { it.isNotEmpty() }
                    ?: DEFAULT_WINDOW_OPTIONS
                val available = options.map { it.windowId }
                val retained = retainAvailableWindowIds(currentState.selectedWindowIds, available)
                val nextSelection = retained.ifEmpty {
                    listOfNotNull(available.firstOrNull { it == currentState.defaultWindowId } ?: available.firstOrNull())
                }
                state = currentState.copy(
                    selectedProviderId = account.providerId.value,
                    selectedLocalAccountId = account.localAccountId.value,
                    selectedWindowIds = nextSelection,
                )
            },
            onWindowToggled = { windowId ->
                val available = currentState.windowOptions.map { it.windowId }
                state = currentState.copy(
                    selectedWindowIds = toggleWindowSelection(currentState.selectedWindowIds, windowId, available),
                )
            },
            onCancel = onCancel,
            onSave = {
                isSaving = true
                onSave(currentState.selectedAccount, currentState.selectedWindowIds)
            },
        )
    }
}

@Composable
private fun LoadingConfigurationScreen() {
    WidgetConfigurationDialogContainer {
        Column(
            verticalArrangement = Arrangement.spacedBy(CodexMeterSpacing.md),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator()
            Text(text = stringResource(R.string.widget_config_loading))
        }
    }
}

@Composable
private fun WidgetConfigurationScreen(
    state: WidgetConfigurationScreenState,
    expandedSection: WidgetConfigurationSection?,
    isSaving: Boolean,
    onSectionToggle: (WidgetConfigurationSection) -> Unit,
    onAccountSelected: (ProviderAccount) -> Unit,
    onWindowToggled: (String) -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
) {
    WidgetConfigurationDialogContainer {
        Column(
            modifier = Modifier
                .heightIn(max = WIDGET_CONFIG_DIALOG_MAX_HEIGHT)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(CodexMeterSpacing.md),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(CodexMeterSpacing.xs)) {
                Text(
                    text = stringResource(R.string.widget_config_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.widget_config_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            WidgetConfigurationExpandableOption(
                title = stringResource(R.string.widget_config_account_section),
                summary = state.selectedAccountLabel(),
                expanded = expandedSection == WidgetConfigurationSection.Account,
                onToggle = { onSectionToggle(WidgetConfigurationSection.Account) },
            ) {
                if (state.accounts.isEmpty()) {
                    Text(
                        text = stringResource(R.string.widget_config_no_accounts),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(CodexMeterSpacing.xs)) {
                        state.accounts.forEach { account ->
                            AccountOptionRow(
                                account = account,
                                selected = account.providerId.value == state.selectedProviderId &&
                                    account.localAccountId.value == state.selectedLocalAccountId,
                                current = account.localAccountId.value == state.currentAccountId,
                                onClick = { onAccountSelected(account) },
                            )
                        }
                    }
                }
            }
            WidgetConfigurationExpandableOption(
                title = stringResource(R.string.widget_config_fields_section),
                summary = state.fieldsSummary(),
                expanded = expandedSection == WidgetConfigurationSection.DisplayFields,
                onToggle = { onSectionToggle(WidgetConfigurationSection.DisplayFields) },
            ) {
                state.windowOptions.forEach { option ->
                    val checked = state.selectedWindowIds.contains(option.windowId)
                    val enabled = checked || state.selectedWindowIds.size < WIDGET_MAX_FIELDS
                    FieldOptionRow(
                        label = stringResource(option.labelResId),
                        checked = checked,
                        enabled = enabled,
                        onClick = { onWindowToggled(option.windowId) },
                    )
                }
                Text(
                    text = stringResource(R.string.widget_config_fields_limit, WIDGET_MAX_FIELDS),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onCancel, enabled = !isSaving, shape = CodexMeterShapes.md) {
                    Text(text = stringResource(R.string.widget_config_cancel))
                }
                Button(
                    onClick = onSave,
                    enabled = !isSaving && state.selectedAccount != null && state.selectedWindowIds.isNotEmpty(),
                    shape = CodexMeterShapes.md,
                ) {
                    Text(
                        text = stringResource(
                            if (isSaving) R.string.widget_config_saving else R.string.widget_config_save,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun WidgetConfigurationDialogContainer(content: @Composable () -> Unit) {
    Surface(color = androidx.compose.ui.graphics.Color.Transparent) {
        Box(
            modifier = Modifier
                .widthIn(min = WIDGET_CONFIG_DIALOG_MIN_WIDTH, max = WIDGET_CONFIG_DIALOG_MAX_WIDTH)
                .padding(WIDGET_CONFIG_DIALOG_OUTER_PADDING),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = CodexMeterShapes.xl,
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 4.dp,
                shadowElevation = 16.dp,
            ) {
                Box(modifier = Modifier.padding(CodexMeterSpacing.lg)) {
                    content()
                }
            }
        }
    }
}

@Composable
private fun WidgetConfigurationExpandableOption(
    title: String,
    summary: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = CodexMeterShapes.lg,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(CodexMeterSpacing.md),
            verticalArrangement = Arrangement.spacedBy(CodexMeterSpacing.sm),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle),
                horizontalArrangement = Arrangement.spacedBy(CodexMeterSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = if (expanded) "⌃" else "⌄",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded) {
                Column(content = content)
            }
        }
    }
}

@Composable
private fun WidgetConfigurationScreenState.selectedAccountLabel(): String =
    accounts.firstOrNull {
        it.providerId.value == selectedProviderId && it.localAccountId.value == selectedLocalAccountId
    }?.displayName ?: stringResource(R.string.widget_config_account_none)

@Composable
private fun WidgetConfigurationScreenState.fieldsSummary(): String {
    val labels = selectedWindowIds.mapNotNull { id ->
        windowOptions.firstOrNull { it.windowId == id }?.let { stringResource(it.labelResId) }
    }
    return if (labels.isEmpty()) {
        stringResource(R.string.widget_config_account_none)
    } else {
        labels.joinToString(separator = " · ")
    }
}

@Composable
private fun AccountOptionRow(
    account: ProviderAccount,
    selected: Boolean,
    current: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(CodexMeterSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = account.displayName,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = if (current) {
                    stringResource(R.string.widget_config_account_current)
                } else {
                    account.providerId.value
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FieldOptionRow(label: String, checked: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = { onClick() })
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(CodexMeterSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, enabled = enabled, onCheckedChange = { onClick() })
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private val WIDGET_CONFIG_DIALOG_MIN_WIDTH = 300.dp
private val WIDGET_CONFIG_DIALOG_MAX_WIDTH = 420.dp
private val WIDGET_CONFIG_DIALOG_MAX_HEIGHT = 560.dp
private val WIDGET_CONFIG_DIALOG_OUTER_PADDING = 12.dp
private const val FIVE_HOUR_WINDOW_ID = "five_hour"
private const val WEEKLY_WINDOW_ID = "weekly"
