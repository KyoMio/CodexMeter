package com.kmnexus.codexmeter.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kmnexus.codexmeter.R
import com.kmnexus.codexmeter.ui.components.LiquidGlassSurfaceRole
import com.kmnexus.codexmeter.ui.components.QmLiquidGlassSurface
import com.kmnexus.codexmeter.ui.motion.CodexMeterMotion
import com.kmnexus.codexmeter.ui.motion.rememberCodexMeterAnimatorsEnabled
import com.kmnexus.codexmeter.ui.theme.CodexMeterColors
import com.kmnexus.codexmeter.ui.theme.CodexMeterShapes
import com.kmnexus.codexmeter.ui.theme.CodexMeterSpacing
import java.text.NumberFormat
import java.util.Locale

@Composable
internal fun HomeHeroGlassLayer(uiState: HomeUiState) {
    val account = uiState.account ?: return
    val primaryCard = uiState.fiveHourCard ?: uiState.weeklyCard
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    Box(modifier = Modifier.fillMaxWidth()) {
        QmLiquidGlassSurface(
            modifier = Modifier.fillMaxWidth(),
            role = LiquidGlassSurfaceRole.Hero,
            cornerRadius = 28.dp,
            contentPadding = PaddingValues(CodexMeterSpacing.lg),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(CodexMeterSpacing.lg),
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(CodexMeterSpacing.md),
                ) {
                    HeroAccountAvatar(account)
                    Column(verticalArrangement = Arrangement.spacedBy(CodexMeterSpacing.xs)) {
                        Text(
                            text = account.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            color = CodexMeterColors.glassInk,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = stringResource(R.string.home_current_account),
                            style = MaterialTheme.typography.labelSmall,
                            color = CodexMeterColors.secondary,
                            maxLines = 1,
                        )
                        Text(
                            text = stringResource(uiState.statusTitleResId),
                            style = MaterialTheme.typography.labelSmall,
                            color = toneColor(primaryCard?.tone ?: HomeStatusTone.Neutral),
                            maxLines = 1,
                        )
                    }
                }
                val planText = account.planType
                val hasCredits = account.credits !is HomeCreditsUi.Unavailable
                if (planText != null || hasCredits) {
                    Column(
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(CodexMeterSpacing.xs),
                    ) {
                        if (planText != null) {
                            Text(
                                text = stringResource(R.string.home_account_plan_label),
                                style = MaterialTheme.typography.labelSmall,
                                color = CodexMeterColors.secondary,
                                maxLines = 1,
                            )
                            Text(
                                text = planText,
                                style = MaterialTheme.typography.titleMedium,
                                color = CodexMeterColors.glassInk,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (hasCredits) {
                            Text(
                                text = stringResource(R.string.home_account_credits_label),
                                style = MaterialTheme.typography.labelSmall,
                                color = CodexMeterColors.secondary,
                                maxLines = 1,
                            )
                            Text(
                                text = account.credits.displayText(locale),
                                style = MaterialTheme.typography.titleMedium,
                                color = CodexMeterColors.glassInk,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
        RefreshSuccessSweep(
            successCount = uiState.manualRefreshSuccessCount,
            modifier = Modifier.matchParentSize(),
        )
    }
}

@Composable
private fun HeroAccountAvatar(account: HomeAccountUi) {
    val iconRes = account.providerIconResId
    if (iconRes != null) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(CodexMeterColors.surface)
                .border(
                    width = 1.dp,
                    color = CodexMeterColors.secondary.copy(alpha = 0.16f),
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(iconRes),
                contentDescription = null,
                modifier = Modifier.size(26.dp),
            )
        }
    } else {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(avatarColor(account.avatarColorKey)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = account.avatarInitial,
                style = MaterialTheme.typography.labelLarge,
                color = CodexMeterColors.surface,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun RefreshSuccessSweep(successCount: Int, modifier: Modifier = Modifier) {
    val animatorsEnabled = rememberCodexMeterAnimatorsEnabled()
    var previousSuccessCount by remember { mutableStateOf(successCount) }
    val progress = remember { Animatable(CodexMeterMotion.RefreshSweepEndProgress) }
    LaunchedEffect(successCount, animatorsEnabled) {
        if (CodexMeterMotion.shouldRunRefreshSweep(
                previousSuccessCount = previousSuccessCount,
                nextSuccessCount = successCount,
                animatorsEnabled = animatorsEnabled,
            )
        ) {
            progress.snapTo(CodexMeterMotion.RefreshSweepStartProgress)
            progress.animateTo(
                targetValue = CodexMeterMotion.RefreshSweepEndProgress,
                animationSpec = tween(
                    durationMillis = CodexMeterMotion.RefreshSweepDurationMillis,
                    easing = FastOutSlowInEasing,
                ),
            )
        } else if (!animatorsEnabled) {
            progress.snapTo(CodexMeterMotion.RefreshSweepEndProgress)
        }
        previousSuccessCount = successCount
    }

    if (CodexMeterMotion.refreshSweepShouldDraw(progress.value)) {
        Canvas(
            modifier = modifier
                .clip(CodexMeterShapes.xl),
        ) {
            val centerX = size.width * progress.value
            val bandWidth = size.width * 0.52f
            drawRect(
                brush = Brush.linearGradient(
                    0.00f to Color.Transparent,
                    0.30f to Color.White.copy(alpha = 0.00f),
                    0.45f to Color.White.copy(alpha = 0.48f),
                    0.56f to CodexMeterColors.glassTintCyan.copy(alpha = 0.32f),
                    0.68f to CodexMeterColors.glassTintViolet.copy(alpha = 0.16f),
                    1.00f to Color.Transparent,
                    start = Offset(centerX - bandWidth, 0f),
                    end = Offset(centerX + bandWidth, size.height),
                ),
            )
        }
    }
}

@Composable
private fun HomeCreditsUi.displayText(locale: Locale): String =
    when (this) {
        is HomeCreditsUi.Balance -> stringResource(
            R.string.home_account_credits_balance_format,
            formatCreditBalance(amount, locale),
        )
        HomeCreditsUi.Unlimited -> stringResource(R.string.home_account_credits_unlimited)
        HomeCreditsUi.Unavailable -> stringResource(R.string.home_account_credits_unavailable)
    }

private fun formatCreditBalance(amount: Double, locale: Locale): String =
    NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = 0
        maximumFractionDigits = 2
    }.format(amount)
