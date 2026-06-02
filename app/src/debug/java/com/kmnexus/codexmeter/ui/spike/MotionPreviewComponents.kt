package com.kmnexus.codexmeter.ui.spike

import android.animation.ValueAnimator
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kmnexus.codexmeter.R
import com.kmnexus.codexmeter.ui.components.LiquidGlassSurfaceRole
import com.kmnexus.codexmeter.ui.components.QmLiquidGlassSurface
import com.kmnexus.codexmeter.ui.theme.CodexMeterColors
import com.kmnexus.codexmeter.ui.theme.CodexMeterShapes
import com.kmnexus.codexmeter.ui.theme.CodexMeterSpacing
import com.kmnexus.codexmeter.ui.theme.CodexMeterTypography
import kotlin.math.roundToInt

@Composable
internal fun MotionPreviewHero(
    scenario: MotionPreviewQuota,
    pulseKey: Int,
    modifier: Modifier = Modifier,
) {
    val motionEnabled = remember { ValueAnimator.areAnimatorsEnabled() }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(CodexMeterShapes.xl),
    ) {
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
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(CodexMeterSpacing.xs),
                ) {
                    Text(
                        text = "CodexMeter",
                        style = TextStyle(
                            fontFamily = MotionPreviewFonts.geistSans,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 31.sp,
                            lineHeight = 34.sp,
                            letterSpacing = (-0.7).sp,
                        ),
                        color = CodexMeterColors.glassInk,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        MotionStatusDot(tone = scenario.tone, size = 9.dp)
                        Text(
                            text = scenario.statusLine,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        text = "刷新成功时玻璃只回光一次，不持续 shimmer",
                        style = MaterialTheme.typography.labelSmall,
                        color = CodexMeterColors.tertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(CodexMeterSpacing.xs),
                ) {
                    Text(
                        text = "5h quota",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    AnimatedPercentText(
                        targetPercent = scenario.fiveHourPercent,
                        fontFamily = MotionPreviewFonts.geistMono,
                        motionEnabled = motionEnabled,
                        color = scenario.tone.statusColor(),
                        fontSize = 48,
                    )
                    Text(
                        text = scenario.tone.statusLabel(),
                        style = MaterialTheme.typography.labelSmall,
                        color = scenario.tone.statusColor(),
                    )
                }
            }
        }
        if (motionEnabled) {
            GlassSweepOverlay(
                pulseKey = pulseKey,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}

@Composable
internal fun FontComparisonSection(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(CodexMeterSpacing.md),
    ) {
        SectionLabel(title = "Font candidates · 本地打包字体样板")
        FontSpecimenCard(
            title = "A · Geist Sans + Geist Mono",
            bodyFont = MotionPreviewFonts.geistSans,
            numberFont = MotionPreviewFonts.geistMono,
            note = "推荐候选：现代开发者工具感，数字最利落。",
        )
        FontSpecimenCard(
            title = "B · Inter + JetBrains Mono",
            bodyFont = MotionPreviewFonts.inter,
            numberFont = MotionPreviewFonts.jetBrainsMono,
            note = "更通用、更稳，技术味略强。",
        )
        FontSpecimenCard(
            title = "C · System Chinese + Geist Mono",
            bodyFont = FontFamily.Default,
            numberFont = MotionPreviewFonts.geistMono,
            note = "中文最自然，数字明显升级，体积最克制。",
        )
    }
}

@Composable
private fun FontSpecimenCard(
    title: String,
    bodyFont: FontFamily,
    numberFont: FontFamily,
    note: String,
) {
    QmLiquidGlassSurface(
        modifier = Modifier.fillMaxWidth(),
        role = LiquidGlassSurfaceRole.Card,
        cornerRadius = 24.dp,
        contentPadding = PaddingValues(CodexMeterSpacing.lg),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(CodexMeterSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(
                    text = title,
                    style = TextStyle(
                        fontFamily = bodyFont,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        lineHeight = 20.sp,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "5小时额度 · Weekly quota · 数据新鲜",
                    style = TextStyle(
                        fontFamily = bodyFont,
                        fontWeight = FontWeight.Medium,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "USER-CODE 8F42 · $note",
                    style = TextStyle(
                        fontFamily = numberFont,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                    ),
                    color = CodexMeterColors.tertiary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = "62%",
                style = TextStyle(
                    fontFamily = numberFont,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 42.sp,
                    lineHeight = 42.sp,
                    letterSpacing = (-1.2).sp,
                    fontFeatureSettings = "tnum",
                ),
                color = CodexMeterColors.glassInk,
            )
        }
    }
}

@Composable
internal fun QuotaMotionSection(
    scenario: MotionPreviewQuota,
    modifier: Modifier = Modifier,
) {
    val motionEnabled = remember { ValueAnimator.areAnimatorsEnabled() }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(CodexMeterSpacing.md),
    ) {
        SectionLabel(title = "Quota motion · 数字滚动 + 液体进度条 + 状态点")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(CodexMeterSpacing.md),
        ) {
            QuotaPreviewCard(
                title = "5小时额度",
                percent = scenario.fiveHourPercent,
                tone = scenario.tone,
                motionEnabled = motionEnabled,
                modifier = Modifier.weight(1f),
            )
            QuotaPreviewCard(
                title = "1周额度",
                percent = scenario.weeklyPercent,
                tone = if (scenario.tone == MotionPreviewTone.Critical) MotionPreviewTone.Normal else scenario.tone,
                motionEnabled = motionEnabled,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun QuotaPreviewCard(
    title: String,
    percent: Int,
    tone: MotionPreviewTone,
    motionEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val animatedProgress by animateFloatAsState(
        targetValue = percent / 100f,
        animationSpec = tween(durationMillis = if (motionEnabled) 520 else 0, easing = FastOutSlowInEasing),
        label = "quota_progress",
    )
    QmLiquidGlassSurface(
        modifier = modifier,
        role = LiquidGlassSurfaceRole.Card,
        cornerRadius = 24.dp,
        contentPadding = PaddingValues(CodexMeterSpacing.lg),
    ) {
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
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                MotionStatusDot(tone = tone, size = 9.dp)
            }
            AnimatedPercentText(
                targetPercent = percent,
                fontFamily = MotionPreviewFonts.geistMono,
                motionEnabled = motionEnabled,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 42,
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(CodexMeterShapes.pill)
                    .background(CodexMeterColors.neutralAlt),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(animatedProgress.coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .clip(CodexMeterShapes.pill)
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(
                                    tone.statusColor().copy(alpha = 0.82f),
                                    tone.statusColor(),
                                    Color.White.copy(alpha = 0.55f),
                                ),
                            ),
                        ),
                )
            }
            Text(
                text = tone.statusLabel(),
                style = MaterialTheme.typography.labelMedium,
                color = tone.statusColor(),
                maxLines = 1,
            )
        }
    }
}

@Composable
internal fun BottomBarMotionPreview(
    selectedIndex: Int,
    modifier: Modifier = Modifier,
) {
    val tabs = listOf(
        Triple("首页", R.drawable.ic_tab_home, "Home"),
        Triple("账号", R.drawable.ic_tab_account, "Account"),
        Triple("设置", R.drawable.ic_tab_settings, "Settings"),
    )
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(CodexMeterSpacing.md),
    ) {
        SectionLabel(title = "Bottom tab motion · 选中胶囊滑动")
        QmLiquidGlassSurface(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp),
            role = LiquidGlassSurfaceRole.Navigation,
            cornerRadius = 36.dp,
            contentPadding = PaddingValues(6.dp),
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val tabWidth = maxWidth / tabs.size
                val targetColor by animateColorAsState(
                    targetValue = CodexMeterColors.accentSoft.copy(alpha = 0.92f),
                    animationSpec = tween(durationMillis = 220),
                    label = "bottom_tab_indicator_color",
                )
                Box(
                    modifier = Modifier
                        .offset(x = tabWidth * selectedIndex.toFloat())
                        .size(width = tabWidth, height = 60.dp)
                        .clip(CodexMeterShapes.lg)
                        .background(targetColor)
                        .border(
                            width = 1.dp,
                            color = Color.White.copy(alpha = 0.70f),
                            shape = CodexMeterShapes.lg,
                        ),
                )
                Row(modifier = Modifier.fillMaxSize()) {
                    tabs.forEachIndexed { index, tab ->
                        val selected = index == selectedIndex
                        val scale by animateFloatAsState(
                            targetValue = if (selected) 1.08f else 1.0f,
                            animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
                            label = "bottom_tab_icon_scale",
                        )
                        val contentColor by animateColorAsState(
                            targetValue = if (selected) CodexMeterColors.accent else MaterialTheme.colorScheme.onSurfaceVariant,
                            animationSpec = tween(durationMillis = 180),
                            label = "bottom_tab_content_color",
                        )
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .padding(vertical = 7.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Icon(
                                painter = painterResource(tab.second),
                                contentDescription = tab.third,
                                tint = contentColor,
                                modifier = Modifier
                                    .size(24.dp)
                                    .graphicsLayer {
                                        scaleX = scale
                                        scaleY = scale
                                    },
                            )
                            Text(
                                text = tab.first,
                                style = MaterialTheme.typography.labelSmall,
                                color = contentColor,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun TrendMotionPreview(
    points: List<Float>,
    scenarioKey: Int,
    modifier: Modifier = Modifier,
) {
    val motionEnabled = remember { ValueAnimator.areAnimatorsEnabled() }
    val reveal = remember { Animatable(1f) }
    LaunchedEffect(scenarioKey) {
        if (motionEnabled) {
            reveal.snapTo(0f)
            reveal.animateTo(1f, tween(durationMillis = 620, easing = FastOutSlowInEasing))
        } else {
            reveal.snapTo(1f)
        }
    }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(CodexMeterSpacing.md),
    ) {
        SectionLabel(title = "Trend motion · 折线重绘，不做复杂图表")
        QmLiquidGlassSurface(
            modifier = Modifier
                .fillMaxWidth()
                .height(152.dp),
            role = LiquidGlassSurfaceRole.Card,
            cornerRadius = 24.dp,
            contentPadding = PaddingValues(CodexMeterSpacing.lg),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "最近 24 小时",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "5h / Weekly",
                        style = MaterialTheme.typography.labelSmall,
                        color = CodexMeterColors.tertiary,
                    )
                }
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(80.dp),
                ) {
                    if (points.size < 2) return@Canvas
                    val path = Path()
                    points.forEachIndexed { index, value ->
                        val x = size.width * index / (points.size - 1)
                        val y = size.height * (1f - value.coerceIn(0f, 1f))
                        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    val fillPath = Path().apply {
                        addPath(path)
                        lineTo(size.width, size.height)
                        lineTo(0f, size.height)
                        close()
                    }
                    clipRect(right = size.width * reveal.value) {
                        drawPath(
                            path = fillPath,
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    CodexMeterColors.accent.copy(alpha = 0.16f),
                                    CodexMeterColors.accent.copy(alpha = 0.02f),
                                ),
                            ),
                        )
                        drawPath(
                            path = path,
                            color = CodexMeterColors.accent,
                            style = Stroke(
                                width = 3.dp.toPx(),
                                cap = StrokeCap.Round,
                                join = StrokeJoin.Round,
                            ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AnimatedPercentText(
    targetPercent: Int,
    fontFamily: FontFamily,
    motionEnabled: Boolean,
    color: Color,
    fontSize: Int,
) {
    val animatedValue by animateFloatAsState(
        targetValue = targetPercent.toFloat(),
        animationSpec = tween(durationMillis = if (motionEnabled) 560 else 0, easing = FastOutSlowInEasing),
        label = "animated_percent",
    )
    Text(
        text = "${animatedValue.roundToInt()}%",
        style = CodexMeterTypography.number.copy(
            fontFamily = fontFamily,
            fontSize = fontSize.sp,
            lineHeight = fontSize.sp,
            letterSpacing = (-1.3).sp,
            fontFeatureSettings = "tnum",
        ),
        color = color,
        maxLines = 1,
    )
}

@Composable
private fun MotionStatusDot(
    tone: MotionPreviewTone,
    size: androidx.compose.ui.unit.Dp,
) {
    val shouldPulse = tone == MotionPreviewTone.Watch || tone == MotionPreviewTone.Critical
    val transition = rememberInfiniteTransition(label = "status_dot_pulse")
    val pulse by transition.animateFloat(
        initialValue = 0.86f,
        targetValue = 1.18f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (tone == MotionPreviewTone.Critical) 820 else 1250, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "status_dot_scale",
    )
    val alpha by transition.animateFloat(
        initialValue = 0.62f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (tone == MotionPreviewTone.Critical) 820 else 1250, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "status_dot_alpha",
    )
    Box(
        modifier = Modifier
            .size(size)
            .graphicsLayer {
                scaleX = if (shouldPulse) pulse else 1f
                scaleY = if (shouldPulse) pulse else 1f
                this.alpha = if (shouldPulse) alpha else 1f
            }
            .clip(CircleShape)
            .background(tone.statusColor()),
    )
}

@Composable
private fun GlassSweepOverlay(
    pulseKey: Int,
    modifier: Modifier = Modifier,
) {
    val sweep = remember { Animatable(1.8f) }
    LaunchedEffect(pulseKey) {
        sweep.snapTo(-0.9f)
        sweep.animateTo(1.8f, tween(durationMillis = 720, easing = FastOutSlowInEasing))
    }
    Canvas(modifier = modifier) {
        val center = size.width * sweep.value
        drawRect(
            brush = Brush.linearGradient(
                colors = listOf(
                    Color.Transparent,
                    Color.White.copy(alpha = 0.00f),
                    Color.White.copy(alpha = 0.42f),
                    CodexMeterColors.glassTintCyan.copy(alpha = 0.14f),
                    Color.White.copy(alpha = 0.00f),
                    Color.Transparent,
                ),
                start = Offset(center - size.width * 0.32f, 0f),
                end = Offset(center + size.width * 0.32f, size.height),
            ),
            size = Size(size.width, size.height),
        )
    }
}

@Composable
internal fun SectionLabel(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = CodexMeterColors.secondary,
    )
}

@Composable
internal fun PreviewControlRow(
    onRefreshClick: () -> Unit,
    onNextClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(CodexMeterSpacing.md),
    ) {
        Button(
            modifier = Modifier.weight(1f),
            onClick = onRefreshClick,
        ) {
            Text("刷新回光")
        }
        Button(
            modifier = Modifier.weight(1f),
            onClick = onNextClick,
        ) {
            Text("切换状态")
        }
    }
}

@Composable
internal fun MotionPreviewSpacer() {
    Spacer(modifier = Modifier.height(CodexMeterSpacing.lg))
}
