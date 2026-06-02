package com.kmnexus.codexmeter.ui.home

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kmnexus.codexmeter.R
import com.kmnexus.codexmeter.domain.quota.QuotaWindowDisplayKind
import com.kmnexus.codexmeter.ui.components.LiquidGlassSurfaceRole
import com.kmnexus.codexmeter.ui.components.QmLiquidGlassSurface
import com.kmnexus.codexmeter.ui.motion.AnimatedQuotaPercent
import com.kmnexus.codexmeter.ui.motion.CodexMeterMotion
import com.kmnexus.codexmeter.ui.motion.MotionQuotaRail
import com.kmnexus.codexmeter.ui.motion.rememberCodexMeterAnimatorsEnabled
import com.kmnexus.codexmeter.ui.theme.CodexMeterSpacing
import com.kmnexus.codexmeter.ui.theme.CodexMeterTypography

@Composable
internal fun HomeQuotaCards(quotaCards: List<HomeQuotaCardUi>) {
    if (quotaCards.isEmpty()) return
    // Two cards per row by default; an odd trailing card stretches to a full row.
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(CodexMeterSpacing.md),
    ) {
        quotaCards.chunked(2).forEach { rowCards ->
            if (rowCards.size == 2) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(CodexMeterSpacing.md),
                ) {
                    QuotaCard(
                        card = rowCards[0],
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                    QuotaCard(
                        card = rowCards[1],
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                }
            } else {
                QuotaCard(card = rowCards[0], modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun QuotaCard(
    card: HomeQuotaCardUi,
    modifier: Modifier = Modifier,
) {
    QmLiquidGlassSurface(
        modifier = modifier,
        role = LiquidGlassSurfaceRole.Card,
        cornerRadius = 28.dp,
        contentPadding = PaddingValues(CodexMeterSpacing.lg),
    ) {
        when (card.displayKind) {
            QuotaWindowDisplayKind.Percent -> PercentQuotaCard(card)
            QuotaWindowDisplayKind.Balance -> BalanceQuotaCard(card)
            QuotaWindowDisplayKind.UsageCount -> UsageCountQuotaCard(card)
            QuotaWindowDisplayKind.MultiModelFraction -> PercentQuotaCard(card) // fallback to percent rendering
        }
    }
}

@Composable
private fun PercentQuotaCard(card: HomeQuotaCardUi) {
    val tone = card.tone
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(CodexMeterSpacing.md),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(card.titleResId),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            StatusDot(tone = tone)
        }
        AnimatedQuotaPercent(
            percent = card.percent,
            style = CodexMeterTypography.current.number,
            color = MaterialTheme.colorScheme.onSurface,
        )
        MotionQuotaRail(
            percent = card.percent,
            color = toneColor(tone),
        )
        Text(
            text = card.resetAt?.let {
                stringResource(R.string.home_reset_at_format, formattedInstant(it))
            } ?: stringResource(R.string.home_reset_unavailable),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = stringResource(card.statusLabelResId),
            style = MaterialTheme.typography.labelMedium,
            color = toneColor(tone),
            maxLines = 1,
        )
    }
}

@Composable
private fun BalanceQuotaCard(card: HomeQuotaCardUi) {
    val tone = card.tone
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(CodexMeterSpacing.md),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(card.titleResId),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            StatusDot(tone = tone)
        }
        Text(
            text = buildString {
                append(
                    com.kmnexus.codexmeter.ui.quota.formatProviderBalance(
                        card.balanceAmount,
                        card.balanceCurrency,
                    ).orEmpty(),
                )
            }.trim(),
            style = CodexMeterTypography.current.number,
            color = MaterialTheme.colorScheme.onSurface,
        )
        val originalBalance = com.kmnexus.codexmeter.ui.quota.formatProviderBalance(
            card.originalBalanceAmount, card.originalBalanceCurrency,
        )
        if (originalBalance != null) {
            Text(
                text = originalBalance,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        if (card.grantedBalance != null || card.toppedUpBalance != null) {
            val nativeCurrency = card.originalBalanceCurrency ?: card.balanceCurrency
            val granted = com.kmnexus.codexmeter.ui.quota.formatProviderBalance(card.grantedBalance, nativeCurrency).orEmpty()
            val toppedUp = com.kmnexus.codexmeter.ui.quota.formatProviderBalance(card.toppedUpBalance, nativeCurrency).orEmpty()
            Text(
                text = stringResource(R.string.quota_balance_breakdown_format, granted, toppedUp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        card.subLabel?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Text(
            text = stringResource(card.statusLabelResId),
            style = MaterialTheme.typography.labelMedium,
            color = toneColor(tone),
            maxLines = 1,
        )
    }
}

@Composable
private fun UsageCountQuotaCard(card: HomeQuotaCardUi) {
    val tone = card.tone
    val percent = card.percent // derived from usedPercent
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(CodexMeterSpacing.md),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(card.titleResId),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            StatusDot(tone = tone)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${card.usedCount ?: "–"} / ${card.limitCount ?: "–"}",
                style = CodexMeterTypography.current.number,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        MotionQuotaRail(
            percent = percent,
            color = toneColor(tone),
        )
        Text(
            text = card.resetAt?.let {
                stringResource(R.string.home_reset_at_format, formattedInstant(it))
            } ?: stringResource(R.string.home_reset_unavailable),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = stringResource(card.statusLabelResId),
            style = MaterialTheme.typography.labelMedium,
            color = toneColor(tone),
            maxLines = 1,
        )
    }
}

@Composable
private fun StatusDot(tone: HomeStatusTone) {
    val animatorsEnabled = rememberCodexMeterAnimatorsEnabled()
    val shouldBreathe = CodexMeterMotion.statusDotBreathEnabled(
        isSemanticTone = tone != HomeStatusTone.Neutral,
        animatorsEnabled = animatorsEnabled,
    )
    val color = toneColor(tone)
    val haloScale: Float
    val haloAlpha: Float
    if (shouldBreathe) {
        val transition = rememberInfiniteTransition(label = "quota_status_dot_breath")
        val animatedScale by transition.animateFloat(
            initialValue = 1f,
            targetValue = CodexMeterMotion.StatusDotBreathMaxScale,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = CodexMeterMotion.StatusDotBreathDurationMillis,
                    easing = FastOutSlowInEasing,
                ),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "quota_status_dot_halo_scale",
        )
        val animatedAlpha by transition.animateFloat(
            initialValue = CodexMeterMotion.StatusDotBreathMinAlpha,
            targetValue = 0.08f,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = CodexMeterMotion.StatusDotBreathDurationMillis,
                    easing = FastOutSlowInEasing,
                ),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "quota_status_dot_halo_alpha",
        )
        haloScale = animatedScale
        haloAlpha = animatedAlpha
    } else {
        haloScale = 1f
        haloAlpha = 0f
    }

    Box(
        modifier = Modifier
            .size(15.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (haloAlpha > 0f) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .graphicsLayer {
                        scaleX = haloScale
                        scaleY = haloScale
                        alpha = haloAlpha
                    }
                    .clip(CircleShape)
                    .background(color),
            )
        }
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color),
        )
    }
}
