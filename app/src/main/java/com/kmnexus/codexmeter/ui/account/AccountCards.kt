package com.kmnexus.codexmeter.ui.account

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kmnexus.codexmeter.R
import com.kmnexus.codexmeter.domain.model.LocalAccountId
import com.kmnexus.codexmeter.domain.model.QuotaWindowId
import com.kmnexus.codexmeter.ui.motion.CodexMeterMotion
import com.kmnexus.codexmeter.ui.motion.rememberCodexMeterAnimatorsEnabled
import com.kmnexus.codexmeter.ui.theme.CodexMeterTheme
import com.kmnexus.codexmeter.ui.theme.CodexMeterShapes
import com.kmnexus.codexmeter.ui.theme.CodexMeterSpacing
import com.kmnexus.codexmeter.ui.theme.avatarColor
import com.kmnexus.codexmeter.ui.theme.avatarInitialColor
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@Composable
internal fun CurrentAccountCard(account: AccountItemUi) {
    AccountSurfaceCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(CodexMeterSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AccountAvatar(account = account, size = 48.dp)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(CodexMeterSpacing.xs),
            ) {
                Text(
                    text = account.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.account_current_card_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            StatusBadge(account = account)
        }
    }
}

@Composable
internal fun EmptyAccountsCard(onAddAccountClick: () -> Unit) {
    AccountSurfaceCard {
        Column(verticalArrangement = Arrangement.spacedBy(CodexMeterSpacing.md)) {
            Text(
                text = stringResource(R.string.account_no_accounts_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.account_no_accounts_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onAddAccountClick, shape = CodexMeterShapes.md) {
                Text(text = stringResource(R.string.account_add_account))
            }
        }
    }
}

@Composable
internal fun AccountCard(
    account: AccountItemUi,
    onSetCurrentClick: (LocalAccountId) -> Unit,
    onRenameClick: (LocalAccountId) -> Unit,
    onReloginClick: (LocalAccountId) -> Unit,
    onQuotaAlertToggle: (LocalAccountId, QuotaWindowId, Boolean) -> Unit,
    onDeleteClick: (LocalAccountId) -> Unit,
    onToggleExpanded: (LocalAccountId) -> Unit,
) {
    AccountSurfaceCard {
        val animatorsEnabled = rememberCodexMeterAnimatorsEnabled()
        Column(verticalArrangement = Arrangement.spacedBy(CodexMeterSpacing.md)) {
            val toggleLabel = stringResource(
                if (account.isExpanded) R.string.account_collapse else R.string.account_expand,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClickLabel = toggleLabel) { onToggleExpanded(account.id) },
                horizontalArrangement = Arrangement.spacedBy(CodexMeterSpacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AccountAvatar(account = account, size = 44.dp)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = account.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = stringResource(account.statusLabelResId),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = account.lastSuccessfulRefreshAt?.let {
                            stringResource(account.refreshSummaryResId, formattedInstant(it))
                        } ?: stringResource(account.refreshSummaryResId),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(CodexMeterSpacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusBadge(account = account)
                    AccountExpandIconButton(
                        isExpanded = account.isExpanded,
                        contentDescription = toggleLabel,
                        animatorsEnabled = animatorsEnabled,
                        onClick = { onToggleExpanded(account.id) },
                    )
                }
            }
            AccountDetailsDrawer(
                account = account,
                animatorsEnabled = animatorsEnabled,
                onSetCurrentClick = onSetCurrentClick,
                onRenameClick = onRenameClick,
                onReloginClick = onReloginClick,
                onQuotaAlertToggle = onQuotaAlertToggle,
                onDeleteClick = onDeleteClick,
            )
        }
    }
}

@Composable
private fun AccountExpandIconButton(
    isExpanded: Boolean,
    contentDescription: String,
    animatorsEnabled: Boolean,
    onClick: () -> Unit,
) {
    val rotation by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        animationSpec = if (animatorsEnabled) {
            tween(
                durationMillis = CodexMeterMotion.AccountDrawerDurationMillis,
                easing = FastOutSlowInEasing,
            )
        } else {
            snap()
        },
        label = "account_expand_icon_rotation",
    )
    IconButton(onClick = onClick) {
        Icon(
            painter = painterResource(R.drawable.ic_chevron_down),
            contentDescription = contentDescription,
            modifier = Modifier
                .size(20.dp)
                .graphicsLayer { rotationZ = rotation },
            tint = CodexMeterTheme.colors.accent,
        )
    }
}

@Composable
private fun AccountDetailsDrawer(
    account: AccountItemUi,
    animatorsEnabled: Boolean,
    onSetCurrentClick: (LocalAccountId) -> Unit,
    onRenameClick: (LocalAccountId) -> Unit,
    onReloginClick: (LocalAccountId) -> Unit,
    onQuotaAlertToggle: (LocalAccountId, QuotaWindowId, Boolean) -> Unit,
    onDeleteClick: (LocalAccountId) -> Unit,
) {
    AnimatedVisibility(
        visible = account.isExpanded,
        modifier = Modifier.clipToBounds(),
        enter = if (animatorsEnabled) {
            expandVertically(
                expandFrom = Alignment.Top,
                animationSpec = tween(
                    durationMillis = CodexMeterMotion.AccountDrawerDurationMillis,
                    easing = FastOutSlowInEasing,
                ),
            ) + fadeIn(
                animationSpec = tween(
                    durationMillis = CodexMeterMotion.AccountDrawerDurationMillis,
                    easing = FastOutSlowInEasing,
                ),
            )
        } else {
            EnterTransition.None
        },
        exit = if (animatorsEnabled) {
            shrinkVertically(
                shrinkTowards = Alignment.Top,
                animationSpec = tween(
                    durationMillis = CodexMeterMotion.AccountDrawerDurationMillis,
                    easing = FastOutSlowInEasing,
                ),
            ) + fadeOut(
                animationSpec = tween(
                    durationMillis = CodexMeterMotion.AccountDrawerDurationMillis,
                    easing = FastOutSlowInEasing,
                ),
            )
        } else {
            ExitTransition.None
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(CodexMeterSpacing.md)) {
            PlanCreditsRow(account = account)
            QuotaSummaryRow(account.quotaSummaries)
            if (account.modelFamilies.isNotEmpty()) {
                ModelDetailsSection(account.modelFamilies)
            }
            QuotaAlertSwitches(
                account = account,
                onQuotaAlertToggle = onQuotaAlertToggle,
            )
            AccountActions(
                account = account,
                onSetCurrentClick = onSetCurrentClick,
                onRenameClick = onRenameClick,
                onReloginClick = onReloginClick,
                onDeleteClick = onDeleteClick,
            )
        }
    }
}

@Composable
internal fun SectionLabel(resId: Int) {
    Text(
        text = stringResource(resId),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
internal fun AccountSurfaceCard(content: @Composable () -> Unit) {
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

@Composable
private fun PlanCreditsRow(account: AccountItemUi) {
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    // Providers expose different fields: show only the tiles that actually have data so that, e.g.,
    // Kimi (no plan/credits) or Antigravity (plan only) don't render "Unavailable" placeholders.
    val planType = account.planType
    val hasCredits = account.credits !is AccountCreditsUi.Unavailable
    if (planType == null && !hasCredits) return
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(CodexMeterSpacing.md),
    ) {
        if (planType != null) {
            AccountMetadataTile(
                label = stringResource(R.string.account_plan_label),
                value = planType,
                modifier = Modifier.weight(1f),
            )
        }
        if (hasCredits) {
            AccountMetadataTile(
                label = stringResource(R.string.account_credits_label),
                value = account.credits.displayText(locale),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun AccountMetadataTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(CodexMeterShapes.md)
            .background(CodexMeterTheme.colors.surfaceSoft)
            .padding(CodexMeterSpacing.md),
        verticalArrangement = Arrangement.spacedBy(CodexMeterSpacing.xs),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun QuotaSummaryRow(summaries: List<AccountQuotaSummaryUi>) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(CodexMeterSpacing.md),
    ) {
        summaries.forEach { summary ->
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(CodexMeterShapes.md)
                    .background(CodexMeterTheme.colors.surfaceSoft)
                    .padding(CodexMeterSpacing.md),
                verticalArrangement = Arrangement.spacedBy(CodexMeterSpacing.xs),
            ) {
                Text(
                    text = stringResource(summary.labelResId),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = summary.valueText
                        ?: summary.percent?.let {
                            stringResource(R.string.account_quota_percent_format, it)
                        }
                        ?: stringResource(R.string.account_quota_placeholder_no_data),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                // Original native balance (shown when conversion occurred)
                summary.originalValueText?.let { original ->
                    Text(
                        text = original,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // DeepSeek-style granted/topped-up breakdown (native currency)
                if (summary.grantedText != null || summary.toppedUpText != null) {
                    Text(
                        text = stringResource(
                            R.string.quota_balance_breakdown_format,
                            summary.grantedText.orEmpty(),
                            summary.toppedUpText.orEmpty(),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelDetailsSection(families: List<AccountModelFamilyUi>) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CodexMeterShapes.md)
            .background(CodexMeterTheme.colors.surfaceSoft)
            .padding(CodexMeterSpacing.md),
        verticalArrangement = Arrangement.spacedBy(CodexMeterSpacing.sm),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.account_model_details_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = if (expanded) "▾" else "▸",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (expanded) {
            families.forEach { family ->
                Text(
                    text = family.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                family.models.forEach { model ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = CodexMeterSpacing.sm),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = model.displayName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = stringResource(R.string.account_quota_percent_format, model.remainingPercent),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QuotaAlertSwitches(
    account: AccountItemUi,
    onQuotaAlertToggle: (LocalAccountId, QuotaWindowId, Boolean) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CodexMeterShapes.md)
            .background(CodexMeterTheme.colors.surfaceSoft)
            .padding(CodexMeterSpacing.md),
        verticalArrangement = Arrangement.spacedBy(CodexMeterSpacing.sm),
    ) {
        Text(
            text = stringResource(R.string.account_quota_alerts_label),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        account.alertToggles.forEach { toggle ->
            QuotaAlertSwitchLine(
                label = stringResource(toggle.labelResId),
                checked = toggle.enabled,
                onCheckedChange = { enabled ->
                    onQuotaAlertToggle(account.id, QuotaWindowId(toggle.windowId), enabled)
                },
            )
        }
    }
}

@Composable
private fun QuotaAlertSwitchLine(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
private fun AccountActions(
    account: AccountItemUi,
    onSetCurrentClick: (LocalAccountId) -> Unit,
    onRenameClick: (LocalAccountId) -> Unit,
    onReloginClick: (LocalAccountId) -> Unit,
    onDeleteClick: (LocalAccountId) -> Unit,
) {
    val actionButtonBorder = BorderStroke(1.dp, CodexMeterTheme.colors.border)
    Column(verticalArrangement = Arrangement.spacedBy(CodexMeterSpacing.sm)) {
        Row(horizontalArrangement = Arrangement.spacedBy(CodexMeterSpacing.sm)) {
            OutlinedButton(
                onClick = { onSetCurrentClick(account.id) },
                modifier = Modifier.weight(1f),
                enabled = !account.isCurrent,
                shape = CodexMeterShapes.md,
                border = actionButtonBorder,
            ) {
                Text(text = stringResource(R.string.account_set_current))
            }
            OutlinedButton(
                onClick = { onRenameClick(account.id) },
                modifier = Modifier.weight(1f),
                shape = CodexMeterShapes.md,
            ) {
                Text(text = stringResource(R.string.account_rename))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(CodexMeterSpacing.sm)) {
            OutlinedButton(
                onClick = { onReloginClick(account.id) },
                modifier = Modifier.weight(1f),
                shape = CodexMeterShapes.md,
            ) {
                Text(text = stringResource(R.string.account_relogin))
            }
            OutlinedButton(
                onClick = { onDeleteClick(account.id) },
                modifier = Modifier.weight(1f),
                shape = CodexMeterShapes.md,
                border = actionButtonBorder,
            ) {
                Text(
                    text = stringResource(R.string.account_delete),
                    color = CodexMeterTheme.colors.danger,
                )
            }
        }
    }
}


@Composable
private fun StatusBadge(account: AccountItemUi) {
    Box(
        modifier = Modifier
            .clip(CodexMeterShapes.pill)
            .background(account.tone.softColor())
            .padding(horizontal = CodexMeterSpacing.md, vertical = CodexMeterSpacing.sm),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(account.badgeLabelResId),
            style = MaterialTheme.typography.labelSmall,
            color = account.tone.color(),
            maxLines = 1,
        )
    }
}

@Composable
private fun AccountAvatar(account: AccountItemUi, size: Dp) {
    val iconRes = account.providerIconResId
    if (iconRes != null) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(CodexMeterTheme.colors.accentSoft),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = CodexMeterTheme.colors.primary,
                modifier = Modifier.size(size * 0.56f),
            )
        }
    } else {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(avatarColor(account.avatarColorKey)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = account.avatarInitial,
                style = MaterialTheme.typography.titleMedium,
                color = avatarInitialColor(),
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun formattedInstant(instant: Instant): String {
    val locale = LocalConfiguration.current.locales[0]
    return remember(instant, locale) {
        DateTimeFormatter
            .ofLocalizedDateTime(FormatStyle.SHORT)
            .withLocale(locale)
            .withZone(ZoneId.systemDefault())
            .format(instant)
    }
}

@Composable
private fun AccountCreditsUi.displayText(locale: Locale): String =
    when (this) {
        is AccountCreditsUi.Balance -> stringResource(
            R.string.account_credits_balance_format,
            formatCreditBalance(amount, locale),
        )
        AccountCreditsUi.Unlimited -> stringResource(R.string.account_credits_unlimited)
        AccountCreditsUi.Unavailable -> stringResource(R.string.account_credits_unavailable)
    }

private fun formatCreditBalance(amount: Double, locale: Locale): String =
    NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = 0
        maximumFractionDigits = 2
    }.format(amount)

@Composable
@ReadOnlyComposable
private fun AccountStatusTone.color(): Color =
    when (this) {
        AccountStatusTone.Neutral -> CodexMeterTheme.colors.secondary
        AccountStatusTone.Success -> CodexMeterTheme.colors.success
        AccountStatusTone.Warning -> CodexMeterTheme.colors.warning
        AccountStatusTone.Danger -> CodexMeterTheme.colors.danger
    }

@Composable
@ReadOnlyComposable
private fun AccountStatusTone.softColor(): Color =
    when (this) {
        AccountStatusTone.Neutral -> CodexMeterTheme.colors.neutralAlt
        AccountStatusTone.Success -> CodexMeterTheme.colors.successSoft
        AccountStatusTone.Warning -> CodexMeterTheme.colors.warningSoft
        AccountStatusTone.Danger -> CodexMeterTheme.colors.dangerSoft
    }
