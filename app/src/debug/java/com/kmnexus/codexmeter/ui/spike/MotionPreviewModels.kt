package com.kmnexus.codexmeter.ui.spike

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.kmnexus.codexmeter.R
import com.kmnexus.codexmeter.ui.theme.CodexMeterTheme

internal object MotionPreviewFonts {
    val geistSans = FontFamily(
        Font(R.font.geist_variable, weight = FontWeight.Normal),
        Font(R.font.geist_variable, weight = FontWeight.SemiBold),
        Font(R.font.geist_variable, weight = FontWeight.Bold),
        Font(R.font.geist_variable, weight = FontWeight.ExtraBold),
    )

    val geistMono = FontFamily(
        Font(R.font.geist_mono_variable, weight = FontWeight.Normal),
        Font(R.font.geist_mono_variable, weight = FontWeight.SemiBold),
        Font(R.font.geist_mono_variable, weight = FontWeight.Bold),
        Font(R.font.geist_mono_variable, weight = FontWeight.ExtraBold),
    )

    val inter = FontFamily(
        Font(R.font.inter_variable, weight = FontWeight.Normal),
        Font(R.font.inter_variable, weight = FontWeight.SemiBold),
        Font(R.font.inter_variable, weight = FontWeight.Bold),
        Font(R.font.inter_variable, weight = FontWeight.ExtraBold),
    )

    val jetBrainsMono = FontFamily(
        Font(R.font.jetbrains_mono_variable, weight = FontWeight.Normal),
        Font(R.font.jetbrains_mono_variable, weight = FontWeight.SemiBold),
        Font(R.font.jetbrains_mono_variable, weight = FontWeight.Bold),
        Font(R.font.jetbrains_mono_variable, weight = FontWeight.ExtraBold),
    )
}

internal enum class MotionPreviewTone {
    Normal,
    Watch,
    Critical,
    Stale,
}

@Composable
@ReadOnlyComposable
internal fun MotionPreviewTone.statusColor(): Color = when (this) {
    MotionPreviewTone.Normal -> CodexMeterTheme.colors.success
    MotionPreviewTone.Watch -> CodexMeterTheme.colors.warning
    MotionPreviewTone.Critical -> CodexMeterTheme.colors.danger
    MotionPreviewTone.Stale -> CodexMeterTheme.colors.tertiary
}

internal fun MotionPreviewTone.statusLabel(): String = when (this) {
    MotionPreviewTone.Normal -> "数据新鲜"
    MotionPreviewTone.Watch -> "注意额度"
    MotionPreviewTone.Critical -> "额度紧张"
    MotionPreviewTone.Stale -> "可能已过期"
}

internal data class MotionPreviewQuota(
    val fiveHourPercent: Int,
    val weeklyPercent: Int,
    val tone: MotionPreviewTone,
    val statusLine: String,
    val trend: List<Float>,
)

internal val MotionPreviewScenarios = listOf(
    MotionPreviewQuota(
        fiveHourPercent = 84,
        weeklyPercent = 76,
        tone = MotionPreviewTone.Normal,
        statusLine = "主号 · 刚刚刷新",
        trend = listOf(0.82f, 0.80f, 0.79f, 0.77f, 0.76f, 0.74f, 0.72f, 0.71f),
    ),
    MotionPreviewQuota(
        fiveHourPercent = 62,
        weeklyPercent = 71,
        tone = MotionPreviewTone.Normal,
        statusLine = "主号 · 1 分钟前刷新",
        trend = listOf(0.86f, 0.82f, 0.79f, 0.72f, 0.69f, 0.66f, 0.64f, 0.62f),
    ),
    MotionPreviewQuota(
        fiveHourPercent = 18,
        weeklyPercent = 54,
        tone = MotionPreviewTone.Watch,
        statusLine = "主号 · 接近注意阈值",
        trend = listOf(0.74f, 0.68f, 0.58f, 0.46f, 0.38f, 0.30f, 0.24f, 0.18f),
    ),
    MotionPreviewQuota(
        fiveHourPercent = 4,
        weeklyPercent = 49,
        tone = MotionPreviewTone.Critical,
        statusLine = "主号 · 紧张，建议放缓",
        trend = listOf(0.61f, 0.50f, 0.38f, 0.24f, 0.16f, 0.10f, 0.07f, 0.04f),
    ),
)
