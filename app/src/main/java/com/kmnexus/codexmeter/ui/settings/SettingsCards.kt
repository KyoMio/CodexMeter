package com.kmnexus.codexmeter.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kmnexus.codexmeter.R
import com.kmnexus.codexmeter.domain.theme.ThemeMode
import com.kmnexus.codexmeter.ui.motion.rememberCodexMeterAnimatorsEnabled
import com.kmnexus.codexmeter.ui.theme.CodexMeterTheme
import com.kmnexus.codexmeter.ui.theme.CodexMeterShapes
import com.kmnexus.codexmeter.ui.theme.CodexMeterSpacing

@Composable
internal fun AppearanceCard(
    appearance: SettingsAppearanceUi,
    onThemeModeSelected: (ThemeMode) -> Unit,
) {
    SettingsSurfaceCard {
        Column(verticalArrangement = Arrangement.spacedBy(CodexMeterSpacing.md)) {
            ThemeModeSegmentedControl(
                selected = appearance.themeMode,
                onSelected = onThemeModeSelected,
            )
            Text(
                text = stringResource(R.string.settings_appearance_widget_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ThemeModeSegmentedControl(
    selected: ThemeMode,
    onSelected: (ThemeMode) -> Unit,
) {
    val options = listOf(
        ThemeMode.LIGHT to R.string.settings_appearance_light,
        ThemeMode.DARK to R.string.settings_appearance_dark,
        ThemeMode.SYSTEM to R.string.settings_appearance_system,
    )
    val pillShape = RoundedCornerShape(999.dp)
    val trackColor = CodexMeterTheme.colors.neutralAlt
    val selectedColor = CodexMeterTheme.colors.accent
    // Dark ink reads better on the brighter dark-mode accent; white on the darker light-mode accent.
    val onSelectedColor = if (CodexMeterTheme.colors.isDark) Color(0xFF08121F) else Color.White
    val onUnselectedColor = MaterialTheme.colorScheme.onSurfaceVariant
    val animatorsEnabled = rememberCodexMeterAnimatorsEnabled()
    val selectedIndex = options.indexOfFirst { it.first == selected }.coerceAtLeast(0)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(trackColor, pillShape)
            .border(1.dp, CodexMeterTheme.colors.border, pillShape)
            .padding(4.dp),
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val segmentWidth = maxWidth / options.size
            val indicatorOffset by animateDpAsState(
                targetValue = segmentWidth * selectedIndex,
                animationSpec = if (animatorsEnabled) {
                    tween(durationMillis = 260, easing = FastOutSlowInEasing)
                } else {
                    snap()
                },
                label = "appearance_indicator_offset",
            )
            // Single sliding selected pill that animates between segments (like the bottom tab bar).
            Box(
                modifier = Modifier
                    .offset(x = indicatorOffset)
                    .width(segmentWidth)
                    .height(48.dp)
                    .background(selectedColor, pillShape),
            )
            Row(modifier = Modifier.fillMaxWidth().selectableGroup()) {
                options.forEach { (mode, labelResId) ->
                    val isSelected = selected == mode
                    val textColor by animateColorAsState(
                        targetValue = if (isSelected) onSelectedColor else onUnselectedColor,
                        animationSpec = if (animatorsEnabled) tween(durationMillis = 260) else snap(),
                        label = "appearance_text_color",
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .selectable(
                                selected = isSelected,
                                role = Role.RadioButton,
                                onClick = { onSelected(mode) },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(labelResId),
                            style = MaterialTheme.typography.labelMedium,
                            color = textColor,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun PersistentNotificationCard(
    persistentNotification: SettingsPersistentNotificationUi,
    onEnabledChanged: (Boolean) -> Unit,
    onAccountClick: () -> Unit,
    onWindowClick: () -> Unit,
) {
    SettingsSurfaceCard {
        Column(verticalArrangement = Arrangement.spacedBy(CodexMeterSpacing.md)) {
            SwitchRow(
                titleResId = R.string.settings_status_notification_title,
                descriptionResId = R.string.settings_status_notification_description,
                checked = persistentNotification.enabled,
                onCheckedChange = onEnabledChanged,
            )
            ChoiceSummaryTextRow(
                titleResId = R.string.settings_notification_account_title,
                descriptionResId = null,
                valueText = persistentNotification.accountSelection.displayLabel(),
                onClick = onAccountClick,
            )
            ChoiceSummaryTextRow(
                titleResId = R.string.settings_notification_display_quota_title,
                descriptionResId = null,
                valueText = persistentNotification.windowChoices
                    .firstOrNull { it.windowId == persistentNotification.selectedWindowId }
                    ?.let { stringResource(it.labelResId) }
                    ?: stringResource(R.string.settings_value_placeholder),
                onClick = onWindowClick,
            )
        }
    }
}

@Composable
internal fun AlertsCard(
    alerts: SettingsAlertsUi,
    targetCurrency: String,
    onAccountErrorsChanged: (Boolean) -> Unit,
    onCautionThresholdChanged: (Int) -> Unit,
    onWarningThresholdChanged: (Int) -> Unit,
    onBalanceCautionChanged: (Double) -> Unit,
    onBalanceWarningChanged: (Double) -> Unit,
    onCurrencyTargetClick: () -> Unit,
) {
    SettingsSurfaceCard {
        Column(verticalArrangement = Arrangement.spacedBy(CodexMeterSpacing.md)) {
            SwitchRow(
                titleResId = R.string.settings_account_errors_title,
                descriptionResId = R.string.settings_account_errors_description,
                checked = alerts.accountErrorsEnabled,
                onCheckedChange = onAccountErrorsChanged,
            )
            ThresholdSlider(
                titleResId = R.string.settings_threshold_caution,
                value = alerts.thresholds.caution,
                valueRange = 2f..99f,
                color = CodexMeterTheme.colors.warning,
                onValueChange = onCautionThresholdChanged,
            )
            ThresholdSlider(
                titleResId = R.string.settings_threshold_warning,
                value = alerts.thresholds.warning,
                valueRange = 1f..98f,
                color = CodexMeterTheme.colors.danger,
                onValueChange = onWarningThresholdChanged,
            )
            Text(
                text = stringResource(R.string.settings_threshold_limit_format, alerts.thresholds.limit),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Balance-type providers (DeepSeek) alert on a currency amount instead of a percent.
            // Display currency is shown inline so the unit is obvious alongside the thresholds.
            ChoiceSummaryTextRow(
                titleResId = R.string.settings_currency_target_title,
                descriptionResId = R.string.settings_currency_target_description,
                valueText = targetCurrency,
                onClick = onCurrencyTargetClick,
            )
            Text(
                text = stringResource(R.string.settings_balance_threshold_caption),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val symbol = com.kmnexus.codexmeter.ui.quota.currencySymbol(targetCurrency)
            BalanceStepperRow(
                titleResId = R.string.settings_threshold_caution,
                color = CodexMeterTheme.colors.warning,
                currencySymbol = symbol,
                value = alerts.balanceCaution,
                onDecrement = { onBalanceCautionChanged(alerts.balanceCaution - 1) },
                onIncrement = { onBalanceCautionChanged(alerts.balanceCaution + 1) },
            )
            BalanceStepperRow(
                titleResId = R.string.settings_threshold_warning,
                color = CodexMeterTheme.colors.danger,
                currencySymbol = symbol,
                value = alerts.balanceWarning,
                onDecrement = { onBalanceWarningChanged(alerts.balanceWarning - 1) },
                onIncrement = { onBalanceWarningChanged(alerts.balanceWarning + 1) },
            )
        }
    }
}

@Composable
private fun BalanceStepperRow(
    titleResId: Int,
    color: Color,
    currencySymbol: String,
    value: Double,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(CodexMeterSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(titleResId) + "  " + stringResource(R.string.settings_balance_amount_format, currencySymbol, value.toInt()),
            style = MaterialTheme.typography.bodyMedium,
            color = color,
            modifier = Modifier.weight(1f),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(CodexMeterSpacing.sm),
        ) {
            OutlinedButton(onClick = onDecrement, shape = CircleShape, contentPadding = StepperButtonPadding) {
                Text(text = "−")
            }
            OutlinedButton(onClick = onIncrement, shape = CircleShape, contentPadding = StepperButtonPadding) {
                Text(text = "+")
            }
        }
    }
}

private val StepperButtonPadding = androidx.compose.foundation.layout.PaddingValues(CodexMeterSpacing.sm)

@Composable
internal fun RefreshCard(
    refresh: SettingsRefreshUi,
    onBackgroundRefreshChanged: (Boolean) -> Unit,
    onIntervalClick: () -> Unit,
    showBatteryOptimizationHint: Boolean = false,
    onBatteryOptimizationClick: () -> Unit = {},
) {
    SettingsSurfaceCard {
        Column(verticalArrangement = Arrangement.spacedBy(CodexMeterSpacing.md)) {
            SwitchRow(
                titleResId = R.string.settings_background_refresh_title,
                descriptionResId = R.string.settings_background_refresh_description,
                checked = refresh.backgroundRefreshEnabled,
                onCheckedChange = onBackgroundRefreshChanged,
            )
            ChoiceSummaryRow(
                titleResId = R.string.settings_refresh_interval_title,
                descriptionResId = null,
                valueResId = refresh.interval.labelResId,
                onClick = onIntervalClick,
            )
            Text(
                text = stringResource(
                    R.string.settings_refresh_last_result_format,
                    stringResource(refresh.lastResultLabelResId),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (showBatteryOptimizationHint) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(CodexMeterSpacing.md),
                ) {
                    SettingsItemText(
                        titleResId = R.string.settings_battery_optimization_title,
                        descriptionResId = R.string.settings_battery_optimization_description,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onBatteryOptimizationClick, shape = CodexMeterShapes.md) {
                        Text(text = stringResource(R.string.settings_battery_optimization_action))
                    }
                }
            }
        }
    }
}

@Composable
internal fun DataCard(
    data: SettingsDataUi,
    onRetentionClick: () -> Unit,
    onClearCurrentHistoryClick: () -> Unit,
    onClearAllHistoryClick: () -> Unit,
) {
    SettingsSurfaceCard {
        Column(verticalArrangement = Arrangement.spacedBy(CodexMeterSpacing.md)) {
            ChoiceSummaryRow(
                titleResId = R.string.settings_retention_title,
                descriptionResId = R.string.settings_retention_description,
                valueResId = data.retention.labelResId,
                onClick = onRetentionClick,
            )
            DestructiveActionRow(
                titleResId = R.string.settings_data_clear_current_title,
                descriptionResId = R.string.settings_data_clear_current_description,
                onClick = onClearCurrentHistoryClick,
            )
            DestructiveActionRow(
                titleResId = R.string.settings_data_clear_all_title,
                descriptionResId = R.string.settings_data_clear_all_description,
                onClick = onClearAllHistoryClick,
            )
        }
    }
}

@Composable
internal fun ChoiceSummaryRow(
    titleResId: Int,
    descriptionResId: Int?,
    valueResId: Int,
    onClick: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(CodexMeterSpacing.md)) {
        SettingsItemText(titleResId, descriptionResId, modifier = Modifier.weight(1f))
        TextButton(onClick = onClick, shape = CodexMeterShapes.md) {
            Text(text = stringResource(valueResId))
        }
    }
}

@Composable
internal fun ChoiceSummaryTextRow(
    titleResId: Int,
    descriptionResId: Int?,
    valueText: String,
    onClick: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(CodexMeterSpacing.md)) {
        SettingsItemText(titleResId, descriptionResId, modifier = Modifier.weight(1f))
        TextButton(onClick = onClick, shape = CodexMeterShapes.md) {
            Text(text = valueText)
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun ThresholdSlider(
    titleResId: Int,
    value: Int,
    valueRange: ClosedFloatingPointRange<Float>,
    color: Color,
    onValueChange: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(CodexMeterSpacing.xs)) {
        Text(
            text = stringResource(R.string.settings_threshold_value_format, stringResource(titleResId), value),
            style = MaterialTheme.typography.bodyMedium,
            color = color,
        )
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.toInt()) },
            valueRange = valueRange,
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = color,
                inactiveTrackColor = CodexMeterTheme.colors.neutralAlt,
            ),
            thumb = {
                AppleThresholdThumb(color)
            },
            track = { sliderState ->
                AppleThresholdTrack(
                    value = sliderState.value,
                    valueRange = valueRange,
                    color = color,
                )
            },
        )
    }
}

@Composable
internal fun SettingsNotificationAccountSelection.displayLabel(): String =
    when (this) {
        SettingsNotificationAccountSelection.FollowCurrent ->
            stringResource(R.string.settings_notification_account_follow_current)
        is SettingsNotificationAccountSelection.Account -> displayName
    }

@Composable
private fun AppleThresholdThumb(color: Color) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .shadow(
                elevation = 3.dp,
                shape = CircleShape,
                ambientColor = Color.Black.copy(alpha = 0.14f),
                spotColor = Color.Black.copy(alpha = 0.16f),
            )
            .background(Color.White, CircleShape)
            .border(1.dp, color.copy(alpha = 0.18f), CircleShape),
    )
}

@Composable
private fun AppleThresholdTrack(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    color: Color,
) {
    val trackColor = CodexMeterTheme.colors.neutralAlt
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp),
    ) {
        val trackHeight = 7.dp.toPx()
        val trackTop = (size.height - trackHeight) / 2f
        val corner = CornerRadius(trackHeight / 2f, trackHeight / 2f)
        val fraction = ((value - valueRange.start) / (valueRange.endInclusive - valueRange.start))
            .coerceIn(0f, 1f)

        drawRoundRect(
            color = trackColor,
            topLeft = Offset(0f, trackTop),
            size = Size(size.width, trackHeight),
            cornerRadius = corner,
        )
        drawRoundRect(
            color = color,
            topLeft = Offset(0f, trackTop),
            size = Size(size.width * fraction, trackHeight),
            cornerRadius = corner,
        )
    }
}

@Composable
internal fun DiagnosticsCard(
    diagnostics: SettingsDiagnosticsUi,
    onDiagnosticsToggle: () -> Unit,
    onDiagnosticsCopyClick: () -> Unit,
    onDiagnosticsRecheckClick: () -> Unit,
) {
    SettingsSurfaceCard {
        Column(verticalArrangement = Arrangement.spacedBy(CodexMeterSpacing.md)) {
            SettingsItemText(
                titleResId = R.string.settings_diagnostics_title,
                descriptionResId = R.string.settings_diagnostics_description,
            )
            TextButton(onClick = onDiagnosticsToggle, shape = CodexMeterShapes.md) {
                val labelResId = if (diagnostics.isExpanded) {
                    R.string.settings_diagnostics_hide
                } else {
                    R.string.settings_diagnostics_show
                }
                Text(text = stringResource(labelResId))
            }
            if (diagnostics.isExpanded) {
                Text(
                    text = diagnostics.copyModel?.text
                        ?: stringResource(R.string.settings_diagnostics_collapsed_state),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(CodexMeterSpacing.sm)) {
                    OutlinedButton(
                        onClick = onDiagnosticsCopyClick,
                        modifier = Modifier.weight(1f),
                        shape = CodexMeterShapes.md,
                    ) {
                        Text(text = stringResource(R.string.settings_diagnostics_copy))
                    }
                    OutlinedButton(
                        onClick = onDiagnosticsRecheckClick,
                        modifier = Modifier.weight(1f),
                        shape = CodexMeterShapes.md,
                    ) {
                        Text(text = stringResource(R.string.settings_diagnostics_recheck))
                    }
                }
            }
        }
    }
}

@Composable
internal fun AboutCard(
    about: SettingsAboutUi,
    onRepositoryClick: () -> Unit,
) {
    SettingsSurfaceCard {
        Column(verticalArrangement = Arrangement.spacedBy(CodexMeterSpacing.md)) {
            Text(
                text = stringResource(
                    R.string.settings_about_version_format,
                    about.versionName,
                    about.buildName,
                ),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.settings_about_data_source),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.settings_about_privacy),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val repositoryContentDescription = stringResource(R.string.settings_about_repository_content_description)
            OutlinedButton(
                onClick = onRepositoryClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = repositoryContentDescription },
                shape = CodexMeterShapes.md,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(CodexMeterSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_brand_github),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(text = stringResource(R.string.settings_about_repository_label))
                }
            }
        }
    }
}

@Composable
internal fun AppUpdateCard(
    update: SettingsUpdateUi,
    onCheckClick: () -> Unit,
) {
    val animatorsEnabled = rememberCodexMeterAnimatorsEnabled()
    val rotation = if (update.isChecking && animatorsEnabled) {
        val transition = rememberInfiniteTransition(label = "settings_update_refresh_icon")
        val animatedRotation by transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 900, easing = LinearEasing),
            ),
            label = "settings_update_refresh_icon_rotation",
        )
        animatedRotation
    } else {
        0f
    }

    SettingsSurfaceCard {
        Column(verticalArrangement = Arrangement.spacedBy(CodexMeterSpacing.md)) {
            SettingsItemText(
                titleResId = R.string.settings_update_title,
                descriptionResId = R.string.settings_update_description,
            )
            OutlinedButton(
                onClick = onCheckClick,
                enabled = !update.isChecking,
                modifier = Modifier.fillMaxWidth(),
                shape = CodexMeterShapes.md,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(CodexMeterSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_action_refresh),
                        contentDescription = null,
                        modifier = Modifier.graphicsLayer {
                            rotationZ = if (update.isChecking) rotation else 0f
                        },
                    )
                    Text(
                        text = stringResource(
                            if (update.isChecking) R.string.settings_update_checking else R.string.settings_update_check,
                        ),
                    )
                }
            }
            update.statusLabelResId?.let { statusLabelResId ->
                Text(
                    text = stringResource(statusLabelResId),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
