package com.kmnexus.codexmeter.ui.motion

import androidx.compose.ui.graphics.Color

object CodexMeterMotion {
    const val PercentDurationMillis = 620
    const val ProgressRailDurationMillis = 620
    const val BottomTabDurationMillis = 240
    const val TrendRedrawDurationMillis = 560
    const val RefreshSweepDurationMillis = 920
    const val RefreshSweepStartProgress = -0.35f
    const val RefreshSweepEndProgress = 1.35f
    const val RefreshSweepVisibleUntilProgress = 1.22f
    const val StatusDotBreathDurationMillis = 1500
    const val StatusDotBreathMaxScale = 1.32f
    const val StatusDotBreathMinAlpha = 0.36f
    const val PageCascadeDurationMillis = 420
    const val PageCascadeInitialOffsetDp = 14f
    const val AccountDrawerDurationMillis = 200

    fun shouldAnimatePercent(oldPercent: Int?, newPercent: Int?, animatorsEnabled: Boolean): Boolean =
        animatorsEnabled && newPercent != null && oldPercent != newPercent

    fun progressFraction(percent: Int?): Float =
        ((percent ?: 0).toFloat() / 100f).coerceIn(0f, 1f)

    fun initialPercentTarget(percent: Int?, animatorsEnabled: Boolean): Int =
        if (animatorsEnabled && percent != null) 0 else percent ?: 0

    fun initialProgressTarget(percent: Int?, animatorsEnabled: Boolean): Float =
        if (animatorsEnabled && percent != null) 0f else progressFraction(percent)

    fun initialTrendRevealTarget(animatorsEnabled: Boolean): Float =
        if (animatorsEnabled) 0f else 1f

    const val TrendHourlyBucketCount = 24

    fun trendHourlyUsageBars(
        rawPoints: List<TrendUsagePoint>,
        bucketCount: Int = TrendHourlyBucketCount,
    ): List<TrendUsageBar> {
        if (bucketCount <= 0) return emptyList()
        val sums = FloatArray(bucketCount)
        rawPoints.forEach { point ->
            val bucketIndex = trendHourBucketIndex(point.x, bucketCount)
            sums[bucketIndex] += point.value.coerceAtLeast(0f)
        }
        return (0 until bucketCount).map { bucketIndex ->
            TrendUsageBar(
                bucketIndex = bucketIndex,
                x = (bucketIndex.toFloat() + 0.5f) / bucketCount.toFloat(),
                value = sums[bucketIndex],
            )
        }
    }

    fun trendUsageBarY(
        value: Float,
        maxValue: Float,
        height: Float,
        topInset: Float,
        bottomInset: Float,
    ): Float {
        val usableHeight = (height - topInset - bottomInset).coerceAtLeast(0f)
        val normalized = if (maxValue <= 0f) 0f else (value / maxValue).coerceIn(0f, 1f)
        return topInset + usableHeight * (1f - normalized)
    }

    fun trendBarWidth(
        availableWidth: Float,
        barCount: Int,
        minWidth: Float,
        maxWidth: Float,
    ): Float {
        if (availableWidth <= 0f || barCount <= 0) return 0f
        val lower = minOf(minWidth, maxWidth).coerceAtLeast(0f)
        val upper = maxOf(minWidth, maxWidth).coerceAtLeast(0f)
        if (upper <= 0f) return 0f
        return (availableWidth / (barCount.toFloat() * 1.8f)).coerceIn(lower, upper)
    }

    fun trendBarCenterX(normalizedX: Float, width: Float, barWidth: Float): Float {
        if (width <= 0f) return 0f
        val visibleBarWidth = barWidth.coerceIn(0f, width)
        val horizontalInset = visibleBarWidth / 2f
        return horizontalInset + (width - visibleBarWidth) * normalizedX.coerceIn(0f, 1f)
    }

    fun trendBarTopCornerRadius(barWidth: Float, barHeight: Float): Float =
        minOf(barWidth / 2f, barHeight / 2f).coerceAtLeast(0f)

    fun refreshSweepShouldDraw(progress: Float): Boolean =
        progress < RefreshSweepVisibleUntilProgress

    fun bottomTabScale(selected: Boolean): Float =
        if (selected) 1.06f else 1f

    fun bottomTabIndicatorOffsetFraction(selectedIndex: Int, tabCount: Int): Float {
        if (tabCount <= 0) return 0f
        return selectedIndex.coerceIn(0, tabCount - 1).toFloat() / tabCount.toFloat()
    }

    fun bottomTabIndicatorGeometry(
        selectedIndex: Int,
        tabCount: Int,
        containerWidth: Float,
        itemSpacing: Float,
    ): BottomTabIndicatorGeometry {
        if (tabCount <= 0 || containerWidth <= 0f) {
            return BottomTabIndicatorGeometry(width = 0f, offset = 0f)
        }
        val spacing = itemSpacing.coerceAtLeast(0f)
        val totalSpacing = spacing * (tabCount - 1).coerceAtLeast(0)
        val width = ((containerWidth - totalSpacing) / tabCount).coerceAtLeast(0f)
        val index = selectedIndex.coerceIn(0, tabCount - 1)
        return BottomTabIndicatorGeometry(
            width = width,
            offset = (width + spacing) * index,
        )
    }

    fun bottomTabIndicatorStyle(): BottomTabIndicatorStyle =
        BottomTabIndicatorStyle(
            color = Color(0xFFF5F7FB),
            alpha = 0.90f,
            borderWidthDp = 0f,
        )

    fun bottomTabPressIndicationEnabled(): Boolean = false

    fun shouldRunRefreshSweep(previousSuccessCount: Int, nextSuccessCount: Int, animatorsEnabled: Boolean): Boolean =
        animatorsEnabled && nextSuccessCount > previousSuccessCount

    fun statusDotBreathEnabled(isSemanticTone: Boolean, animatorsEnabled: Boolean): Boolean =
        animatorsEnabled && isSemanticTone

    fun pageCascadeInitialOffsetDp(animatorsEnabled: Boolean): Float =
        if (animatorsEnabled) PageCascadeInitialOffsetDp else 0f

    private fun trendHourBucketIndex(normalizedX: Float, bucketCount: Int): Int {
        if (bucketCount <= 1) return 0
        val clamped = normalizedX.coerceIn(0f, 1f)
        return minOf((clamped * bucketCount.toFloat()).toInt(), bucketCount - 1)
    }
}

data class TrendUsagePoint(
    val x: Float,
    val value: Float,
)

data class TrendUsageBar(
    val bucketIndex: Int,
    val x: Float,
    val value: Float,
)

data class BottomTabIndicatorGeometry(
    val width: Float,
    val offset: Float,
)

data class BottomTabIndicatorStyle(
    val color: Color,
    val alpha: Float,
    val borderWidthDp: Float,
)
