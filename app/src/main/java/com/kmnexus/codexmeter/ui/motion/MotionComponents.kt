package com.kmnexus.codexmeter.ui.motion

import android.animation.ValueAnimator
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kmnexus.codexmeter.R
import com.kmnexus.codexmeter.ui.theme.CodexMeterTheme
import com.kmnexus.codexmeter.ui.theme.CodexMeterShapes

@Composable
internal fun rememberCodexMeterAnimatorsEnabled(): Boolean =
    remember { ValueAnimator.areAnimatorsEnabled() }

@Composable
internal fun CodexMeterPageCascade(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val animatorsEnabled = rememberCodexMeterAnimatorsEnabled()
    val initialOffsetPx = with(LocalDensity.current) {
        CodexMeterMotion.pageCascadeInitialOffsetDp(animatorsEnabled).dp.toPx()
    }
    val translationY = remember { Animatable(initialOffsetPx) }

    LaunchedEffect(animatorsEnabled, initialOffsetPx) {
        if (animatorsEnabled) {
            translationY.snapTo(initialOffsetPx)
            translationY.animateTo(
                targetValue = 0f,
                animationSpec = tween(
                    durationMillis = CodexMeterMotion.PageCascadeDurationMillis,
                    easing = FastOutSlowInEasing,
                ),
            )
        } else {
            translationY.snapTo(0f)
        }
    }

    Box(
        modifier = modifier.graphicsLayer {
            this.translationY = translationY.value
        },
    ) {
        content()
    }
}

@Composable
internal fun AnimatedQuotaPercent(
    percent: Int?,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val animatorsEnabled = rememberCodexMeterAnimatorsEnabled()
    var targetPercent by remember {
        mutableStateOf(CodexMeterMotion.initialPercentTarget(percent, animatorsEnabled))
    }
    LaunchedEffect(percent, animatorsEnabled) {
        targetPercent = percent ?: 0
    }
    val displayedPercent by if (animatorsEnabled) {
        animateIntAsState(
            targetValue = targetPercent,
            animationSpec = tween(
                durationMillis = CodexMeterMotion.PercentDurationMillis,
                easing = FastOutSlowInEasing,
            ),
            label = "quota_percent",
        )
    } else {
        rememberUpdatedState(targetPercent)
    }

    Text(
        text = percent?.let {
            stringResource(R.string.home_quota_percent_format, displayedPercent)
        } ?: stringResource(R.string.home_quota_percent_unavailable),
        modifier = modifier,
        style = style,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Clip,
    )
}

@Composable
internal fun MotionQuotaRail(
    percent: Int?,
    color: Color,
    modifier: Modifier = Modifier,
    trackColor: Color = CodexMeterTheme.colors.neutralAlt,
) {
    val animatorsEnabled = rememberCodexMeterAnimatorsEnabled()
    var previousPercent by remember { mutableStateOf<Int?>(null) }
    var targetFraction by remember {
        mutableStateOf(CodexMeterMotion.initialProgressTarget(percent, animatorsEnabled))
    }
    LaunchedEffect(percent, animatorsEnabled) {
        if (animatorsEnabled && previousPercent == null && percent != null) {
            targetFraction = 0f
            withFrameNanos { }
        }
        targetFraction = CodexMeterMotion.progressFraction(percent)
        previousPercent = percent
    }
    val animatedFraction by animateFloatAsState(
        targetValue = targetFraction,
        animationSpec = if (animatorsEnabled) {
            tween(
                durationMillis = CodexMeterMotion.ProgressRailDurationMillis,
                easing = FastOutSlowInEasing,
            )
        } else {
            snap()
        },
        label = "quota_progress",
    )

    LinearProgressIndicator(
        progress = { animatedFraction },
        modifier = modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(CodexMeterShapes.pill),
        color = color,
        trackColor = trackColor,
    )
}
