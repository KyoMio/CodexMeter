package com.kmnexus.codexmeter.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.RowScope
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.kmnexus.codexmeter.MainActivity
import com.kmnexus.codexmeter.R
import com.kmnexus.codexmeter.ui.navigation.CodexMeterLaunchDestination
import com.kmnexus.codexmeter.ui.navigation.CodexMeterRoute
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

class CodexMeterWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Responsive(WidgetLayoutVariant.referenceSizes)
    override val stateDefinition = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val preferences = currentState<Preferences>()
            val widgetSize = LocalSize.current
            CodexMeterWidgetContent(
                context = context,
                state = preferences.toWidgetQuotaState(),
                widgetSize = widgetSize,
                layoutVariant = WidgetLayoutVariant.fromSize(widgetSize),
            )
        }
    }
}

@Composable
internal fun CodexMeterWidgetContent(
    context: Context,
    state: WidgetQuotaState,
    widgetSize: DpSize,
    layoutVariant: WidgetLayoutVariant,
) {
    val launchIntent = state.launchIntent(context)
    val glassBackdrop = LiquidGlassWidgetBackdropRenderer.render(context, widgetSize, state.tone)
    Box(
        modifier = GlanceModifier.fillMaxSize().clickable(actionStartActivity(launchIntent)),
        contentAlignment = Alignment.CenterStart,
    ) {
        Image(
            provider = ImageProvider(glassBackdrop),
            contentDescription = null,
            modifier = GlanceModifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds,
        )
        Box(
            modifier = GlanceModifier.fillMaxSize().padding(
                start = layoutVariant.horizontalContentPadding(),
                top = layoutVariant.verticalContentPadding(),
                end = layoutVariant.horizontalContentPadding(),
                bottom = layoutVariant.verticalContentPadding(),
            ),
            contentAlignment = Alignment.CenterStart,
        ) {
            when {
                state.isUnconfigured -> UnconfiguredWidgetContent(context, state)
                else -> when (layoutVariant) {
                    WidgetLayoutVariant.ThreeByOne -> ThreeByOneContent(context, state)
                    WidgetLayoutVariant.FourByOne -> FourByOneContent(context, state)
                    WidgetLayoutVariant.ThreeByTwo -> ThreeByTwoContent(context, state)
                    WidgetLayoutVariant.FourByTwo -> FourByTwoContent(context, state)
                }
            }
        }
    }
}

/** 取该尺寸要展示的字段（前 N 个）。 */
private fun WidgetQuotaState.displayedFields(variant: WidgetLayoutVariant): List<WidgetField> =
    fields.take(variant.fieldCapacity)

// ---------- 3×1：维持现状（光条 + 头部 + 单字段大百分比） ----------
@Composable
private fun ThreeByOneContent(context: Context, state: WidgetQuotaState) {
    val statusGlow = WidgetStatusGlowRenderer.render(context, state.tone)
    val field = state.displayedFields(WidgetLayoutVariant.ThreeByOne).firstOrNull()
    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.Vertical.CenterVertically,
    ) {
        Image(
            provider = ImageProvider(statusGlow),
            contentDescription = null,
            modifier = GlanceModifier.width(9.dp).height(44.dp),
            contentScale = ContentScale.FillBounds,
        )
        Spacer(GlanceModifier.width(9.dp))
        WidgetBrandIcon(state, sizeDp = 18)
        Column(modifier = GlanceModifier.defaultWeight()) {
            Text(
                text = state.accountName ?: state.providerName,
                style = TextStyle(color = ColorProvider(WIDGET_TEXT), fontSize = 13.sp, fontWeight = FontWeight.Medium),
                maxLines = 1,
            )
            Text(
                text = state.headerStatusResetLine(context, field),
                modifier = GlanceModifier.padding(top = 2.dp),
                style = TextStyle(color = ColorProvider(WIDGET_MUTED_TEXT), fontSize = 10.sp, fontWeight = FontWeight.Medium),
                maxLines = 1,
            )
        }
        Spacer(GlanceModifier.width(10.dp))
        if (field == null) {
            EmptyFieldsHint(context, modifier = GlanceModifier.padding(end = 6.dp))
        } else {
            FieldValueColumn(
                context, field, valueSizeSp = 30, alignment = Alignment.Horizontal.End,
                modifier = GlanceModifier.padding(end = 10.dp),
            )
        }
    }
}

// ---------- 4×1：保持现状（头部行 + 两字段并排） ----------
@Composable
private fun FourByOneContent(context: Context, state: WidgetQuotaState) {
    val fields = state.displayedFields(WidgetLayoutVariant.FourByOne)
    Column(modifier = GlanceModifier.fillMaxWidth()) {
        WidgetHeaderRow(context, state)
        Row(
            modifier = GlanceModifier.fillMaxWidth().padding(top = 2.dp),
            verticalAlignment = Alignment.Vertical.CenterVertically,
        ) {
            FieldCellOrHint(context, fields.getOrNull(0))
            Spacer(GlanceModifier.width(8.dp))
            VerticalDivider()
            Spacer(GlanceModifier.width(8.dp))
            FieldCellOrHint(context, fields.getOrNull(1))
        }
    }
}

// ---------- 3×2：左上头部 + 3 字段，轻分隔（短·低对比·内缩，向 4×1 语言靠拢） ----------
@Composable
private fun ThreeByTwoContent(context: Context, state: WidgetQuotaState) {
    val fields = state.displayedFields(WidgetLayoutVariant.ThreeByTwo)
    Column(modifier = GlanceModifier.fillMaxSize()) {
        Row(
            modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
            verticalAlignment = Alignment.Vertical.CenterVertically,
        ) {
            QuadrantHeader(context, state, GlanceModifier.defaultWeight())
            SoftColumnDivider()
            GridFieldCell(context, fields.getOrNull(0), GlanceModifier.defaultWeight(), stacked = true)
        }
        SoftRowDivider()
        Row(
            modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
            verticalAlignment = Alignment.Vertical.CenterVertically,
        ) {
            GridFieldCell(context, fields.getOrNull(1), GlanceModifier.defaultWeight(), stacked = true)
            SoftColumnDivider()
            GridFieldCell(context, fields.getOrNull(2), GlanceModifier.defaultWeight(), stacked = true)
        }
    }
}

// ---------- 4×2：整行头部 + 2×2 字段，轻分隔 ----------
@Composable
private fun FourByTwoContent(context: Context, state: WidgetQuotaState) {
    val fields = state.displayedFields(WidgetLayoutVariant.FourByTwo)
    Column(modifier = GlanceModifier.fillMaxSize()) {
        // Grid uses a small outer inset to fill, but the header sits in the top rounded corners,
        // so give it extra horizontal + top inset to keep account/status inside the card.
        WidgetHeaderRow(context, state, GlanceModifier.padding(horizontal = 8.dp).padding(top = 6.dp))
        Spacer(GlanceModifier.height(6.dp))
        Row(
            modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
            verticalAlignment = Alignment.Vertical.CenterVertically,
        ) {
            GridFieldCell(context, fields.getOrNull(0), GlanceModifier.defaultWeight())
            SoftColumnDivider()
            GridFieldCell(context, fields.getOrNull(1), GlanceModifier.defaultWeight())
        }
        SoftRowDivider()
        Row(
            modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
            verticalAlignment = Alignment.Vertical.CenterVertically,
        ) {
            GridFieldCell(context, fields.getOrNull(2), GlanceModifier.defaultWeight())
            SoftColumnDivider()
            GridFieldCell(context, fields.getOrNull(3), GlanceModifier.defaultWeight())
        }
    }
}

/** Short, low-contrast vertical hairline between two columns — echoes the 4x1 divider. */
@Composable
private fun SoftColumnDivider() {
    Box(
        modifier = GlanceModifier.width(1.dp).height(46.dp).background(ColorProvider(WIDGET_DIVIDER_SOFT)),
    ) {}
}

/** Inset, low-contrast horizontal hairline between two rows — suggests the split without boxing. */
@Composable
private fun SoftRowDivider() {
    Box(modifier = GlanceModifier.fillMaxWidth().padding(horizontal = 28.dp)) {
        Box(
            modifier = GlanceModifier.fillMaxWidth().height(1.dp).background(ColorProvider(WIDGET_DIVIDER_SOFT)),
        ) {}
    }
}

@Composable
private fun WidgetHeaderRow(
    context: Context,
    state: WidgetQuotaState,
    modifier: GlanceModifier = GlanceModifier,
) {
    Row(
        // Inset from the card's rounded corners so the account name / status don't hug the edge.
        modifier = modifier.fillMaxWidth().padding(horizontal = 6.dp),
        verticalAlignment = Alignment.Vertical.CenterVertically,
    ) {
        WidgetBrandIcon(state, sizeDp = 18)
        Text(
            text = state.accountName ?: state.providerName,
            modifier = GlanceModifier.defaultWeight(),
            style = TextStyle(color = ColorProvider(WIDGET_TEXT), fontSize = 12.sp, fontWeight = FontWeight.Medium),
            maxLines = 1,
        )
        Text(
            text = state.statusLabel(context),
            style = TextStyle(color = ColorProvider(state.tone.statusAccentColor()), fontSize = 10.sp, fontWeight = FontWeight.Medium),
            maxLines = 1,
        )
    }
}

@Composable
private fun QuadrantHeader(context: Context, state: WidgetQuotaState, modifier: GlanceModifier) {
    // Centered to match the stacked field cells; account name stays single-line (ellipsized) so a
    // long name can't wrap and shove the layout around.
    Box(
        modifier = modifier.fillMaxHeight().padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.Horizontal.CenterHorizontally) {
            Row(verticalAlignment = Alignment.Vertical.CenterVertically) {
                WidgetBrandIcon(state, sizeDp = 16)
                Text(
                    text = state.accountName ?: state.providerName,
                    style = TextStyle(color = ColorProvider(WIDGET_TEXT), fontSize = 12.sp, fontWeight = FontWeight.Medium),
                    maxLines = 1,
                )
            }
            Text(
                text = state.statusLabel(context),
                modifier = GlanceModifier.padding(top = 4.dp),
                style = TextStyle(color = ColorProvider(state.tone.statusAccentColor()), fontSize = 10.sp, fontWeight = FontWeight.Medium),
                maxLines = 1,
            )
        }
    }
}

/**
 * Grid field cell mirroring the 4x1 card: label/reset on the left, the big value pushed to the
 * right edge so the cell fills its width instead of leaving the right side empty.
 */
@Composable
private fun GridFieldCell(
    context: Context,
    field: WidgetField?,
    modifier: GlanceModifier,
    stacked: Boolean = false,
) {
    if (field == null) {
        Box(
            modifier = modifier.fillMaxHeight().padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            EmptyFieldsHint(context)
        }
        return
    }
    if (stacked) {
        // Narrow (3-col) cells: label / big value / reset stacked and centered, so the value
        // fills the cell width instead of leaving the right side empty. Use the bare reset time
        // (no "Reset" prefix) and minimal padding so the label/time aren't ellipsized.
        Box(
            modifier = modifier.fillMaxHeight().padding(horizontal = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.Horizontal.CenterHorizontally) {
                Text(
                    text = context.getString(field.titleResId()),
                    style = TextStyle(color = ColorProvider(WIDGET_MUTED_TEXT), fontSize = 9.sp, fontWeight = FontWeight.Medium),
                    maxLines = 1,
                )
                Text(
                    text = field.valueText(context),
                    modifier = GlanceModifier.padding(top = 2.dp),
                    style = TextStyle(color = ColorProvider(field.tone.percentColor()), fontSize = 30.sp, fontWeight = FontWeight.Bold),
                    maxLines = 1,
                )
                Text(
                    text = field.compactResetTime(context).orEmpty(),
                    modifier = GlanceModifier.padding(top = 1.dp),
                    style = TextStyle(color = ColorProvider(WIDGET_MUTED_TEXT), fontSize = 9.sp, fontWeight = FontWeight.Medium),
                    maxLines = 1,
                )
            }
        }
        return
    }
    Row(
        modifier = modifier.fillMaxHeight().padding(horizontal = 12.dp),
        verticalAlignment = Alignment.Vertical.CenterVertically,
    ) {
        Column(modifier = GlanceModifier.defaultWeight(), verticalAlignment = Alignment.Vertical.CenterVertically) {
            Text(
                text = context.getString(field.titleResId()),
                style = TextStyle(color = ColorProvider(WIDGET_MUTED_TEXT), fontSize = 9.sp, fontWeight = FontWeight.Medium),
                maxLines = 1,
            )
            // Blank reset slot kept for height parity so big numbers line up across cells.
            Text(
                text = field.resetLine(context).orEmpty(),
                modifier = GlanceModifier.padding(top = 1.dp),
                style = TextStyle(color = ColorProvider(WIDGET_MUTED_TEXT), fontSize = 9.sp, fontWeight = FontWeight.Medium),
                maxLines = 1,
            )
        }
        Spacer(GlanceModifier.width(6.dp))
        Text(
            text = field.valueText(context),
            style = TextStyle(color = ColorProvider(field.tone.percentColor()), fontSize = 28.sp, fontWeight = FontWeight.Bold),
            maxLines = 1,
        )
    }
}

/** 4×1 / 4×2 中一格：左标题/重置 + 右大数字；无字段则提示。 */
@Composable
private fun RowScope.FieldCellOrHint(context: Context, field: WidgetField?) {
    if (field == null) {
        Box(modifier = GlanceModifier.defaultWeight(), contentAlignment = Alignment.Center) {
            EmptyFieldsHint(context)
        }
        return
    }
    Row(
        modifier = GlanceModifier.defaultWeight(),
        verticalAlignment = Alignment.Vertical.CenterVertically,
    ) {
        Column(modifier = GlanceModifier.defaultWeight()) {
            Text(
                text = context.getString(field.titleResId()),
                style = TextStyle(color = ColorProvider(WIDGET_MUTED_TEXT), fontSize = 9.sp, fontWeight = FontWeight.Medium),
                maxLines = 1,
            )
            // Blank reset slot kept for height parity so big numbers line up across cells.
            Text(
                text = field.resetLine(context).orEmpty(),
                modifier = GlanceModifier.padding(top = 1.dp),
                style = TextStyle(color = ColorProvider(WIDGET_MUTED_TEXT), fontSize = 9.sp, fontWeight = FontWeight.Medium),
                maxLines = 1,
            )
        }
        Spacer(GlanceModifier.width(4.dp))
        Text(
            text = field.valueText(context),
            style = TextStyle(color = ColorProvider(field.tone.percentColor()), fontSize = 28.sp, fontWeight = FontWeight.Bold),
            maxLines = 1,
        )
    }
}

@Composable
private fun FieldValueColumn(
    context: Context,
    field: WidgetField,
    valueSizeSp: Int,
    alignment: Alignment.Horizontal,
    modifier: GlanceModifier = GlanceModifier,
) {
    Column(modifier = modifier, horizontalAlignment = alignment, verticalAlignment = Alignment.Vertical.CenterVertically) {
        Text(
            text = context.getString(field.titleResId()),
            style = TextStyle(color = ColorProvider(WIDGET_MUTED_TEXT), fontSize = 8.sp, fontWeight = FontWeight.Medium),
            maxLines = 1,
        )
        Text(
            text = field.valueText(context),
            style = TextStyle(color = ColorProvider(field.tone.percentColor()), fontSize = valueSizeSp.sp, fontWeight = FontWeight.Bold),
            maxLines = 1,
        )
    }
}

@Composable
private fun VerticalDivider() {
    Box(modifier = GlanceModifier.width(1.dp).height(34.dp).background(ColorProvider(WIDGET_DIVIDER))) {}
}

@Composable
private fun EmptyFieldsHint(context: Context, modifier: GlanceModifier = GlanceModifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.Horizontal.CenterHorizontally) {
        Text(
            text = context.getString(R.string.widget_no_more_fields),
            style = TextStyle(color = ColorProvider(WIDGET_MUTED_TEXT), fontSize = 8.sp, fontWeight = FontWeight.Medium),
            maxLines = 2,
        )
        Text(
            text = context.getString(R.string.widget_no_more_fields_hint),
            style = TextStyle(color = ColorProvider(WIDGET_PERCENT_MUTED_TEXT), fontSize = 8.sp, fontWeight = FontWeight.Medium),
            maxLines = 2,
        )
    }
}

@Composable
private fun UnconfiguredWidgetContent(context: Context, state: WidgetQuotaState) {
    Column(
        modifier = GlanceModifier.fillMaxSize(),
        verticalAlignment = Alignment.Vertical.CenterVertically,
        horizontalAlignment = Alignment.Horizontal.CenterHorizontally,
    ) {
        WidgetBrandIcon(state, sizeDp = 22)
        Spacer(GlanceModifier.height(6.dp))
        Text(
            text = context.getString(R.string.widget_unconfigured_title),
            style = TextStyle(color = ColorProvider(WIDGET_TEXT), fontSize = 12.sp, fontWeight = FontWeight.Bold),
            maxLines = 1,
        )
        Spacer(GlanceModifier.height(2.dp))
        Text(
            text = context.getString(
                if (state.hasAccounts) R.string.widget_unconfigured_hint else R.string.widget_no_accounts_hint,
            ),
            style = TextStyle(color = ColorProvider(WIDGET_MUTED_TEXT), fontSize = 9.sp, fontWeight = FontWeight.Medium),
            maxLines = 2,
        )
    }
}

@Composable
private fun WidgetBrandIcon(state: WidgetQuotaState, sizeDp: Int) {
    val iconRes = state.providerIconRes ?: return
    Image(
        provider = ImageProvider(iconRes),
        contentDescription = null,
        modifier = GlanceModifier.width(sizeDp.dp).height(sizeDp.dp),
        colorFilter = ColorFilter.tint(ColorProvider(WIDGET_TEXT)),
    )
    Spacer(modifier = GlanceModifier.width(6.dp))
}

private fun WidgetField.titleResId(): Int =
    com.kmnexus.codexmeter.ui.quota.quotaWindowLabelRes(windowId)

private fun WidgetField.valueText(context: Context): String =
    if (isBalance) {
        com.kmnexus.codexmeter.ui.quota.formatProviderBalance(balanceAmount, balanceCurrency)
            ?: context.getString(R.string.widget_percent_unavailable)
    } else {
        percent?.let { context.getString(R.string.widget_percent_format, it) }
            ?: context.getString(R.string.widget_percent_unavailable)
    }

private fun WidgetField.resetLine(context: Context): String? =
    resetAt?.let { context.getString(R.string.widget_reset_at_compact_format, it.formatCompactTime(context)) }

/** Bare reset time without the "Reset" prefix — for narrow grid cells where space is tight. */
private fun WidgetField.compactResetTime(context: Context): String? =
    resetAt?.formatCompactTime(context)

private fun WidgetQuotaState.headerStatusResetLine(context: Context, field: WidgetField?): String {
    val status = context.getString(statusLabelResId())
    // Bare reset time (no "Reset" prefix) so the enlarged 3x1 secondary line stays on one line.
    val reset = field?.compactResetTime(context)
    return if (reset != null) context.getString(R.string.widget_provider_status_line_format, status, reset) else status
}

private fun WidgetQuotaState.statusLabel(context: Context): String = context.getString(statusLabelResId())

private fun WidgetQuotaState.launchIntent(context: Context): Intent =
    Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        val launchDestination = when (clickTarget) {
            WidgetClickTarget.Home -> CodexMeterLaunchDestination.Home
            WidgetClickTarget.AddAccount -> CodexMeterLaunchDestination.AddAccount
        }
        putExtra(CodexMeterRoute.EXTRA_LAUNCH_DESTINATION, launchDestination.value)
    }

internal fun WidgetQuotaState.statusLabelResId(): Int =
    when (status) {
        WidgetQuotaStatus.NoAccount -> R.string.widget_connect_codex
        WidgetQuotaStatus.Fresh -> when (tone) {
            WidgetQuotaTone.Danger -> R.string.home_quota_status_warning
            WidgetQuotaTone.Warning -> R.string.home_quota_status_caution
            WidgetQuotaTone.Success -> R.string.home_quota_status_normal
            WidgetQuotaTone.Neutral -> R.string.home_quota_status_unavailable
        }
        WidgetQuotaStatus.PossiblyStale -> R.string.widget_status_possibly_stale
        WidgetQuotaStatus.Expired -> R.string.widget_status_expired
        WidgetQuotaStatus.AuthRequired -> R.string.widget_status_auth_required
        WidgetQuotaStatus.ErrorWithLastKnownGood -> R.string.widget_status_refresh_failed
        WidgetQuotaStatus.NoData -> R.string.widget_status_no_data
    }

internal fun WidgetQuotaTone.statusAccentColor(): Color =
    when (this) {
        WidgetQuotaTone.Neutral -> WIDGET_ACCENT
        WidgetQuotaTone.Success -> WIDGET_SUCCESS
        WidgetQuotaTone.Warning -> WIDGET_WARNING
        WidgetQuotaTone.Danger -> WIDGET_DANGER
    }

internal fun WidgetQuotaTone.statusGlowColor(): Color =
    when (this) {
        WidgetQuotaTone.Neutral -> WIDGET_ACCENT_GLOW
        WidgetQuotaTone.Success -> WIDGET_SUCCESS_GLOW
        WidgetQuotaTone.Warning -> WIDGET_WARNING_GLOW
        WidgetQuotaTone.Danger -> WIDGET_DANGER_GLOW
    }

internal fun WidgetQuotaTone.statusAccentArgb(): Int =
    statusAccentColor().toArgb()

internal fun WidgetQuotaTone.statusGlowArgb(): Int =
    statusGlowColor().toArgb()

internal fun WidgetQuotaTone.percentColor(): Color =
    when (this) {
        WidgetQuotaTone.Neutral -> WIDGET_PERCENT_MUTED_TEXT
        WidgetQuotaTone.Success -> WIDGET_TEXT
        WidgetQuotaTone.Warning -> WIDGET_WARNING
        WidgetQuotaTone.Danger -> WIDGET_DANGER
    }

private fun Instant.formatCompactTime(context: Context): String {
    val locale = context.resources.configuration.locales[0]
    return DateTimeFormatter
        .ofLocalizedTime(FormatStyle.SHORT)
        .withLocale(locale)
        .withZone(ZoneId.systemDefault())
        .format(this)
}

private fun WidgetLayoutVariant.horizontalContentPadding() = when (this) {
    WidgetLayoutVariant.ThreeByOne -> 16.dp
    WidgetLayoutVariant.FourByOne -> 16.dp
    // Grid variants: small outer inset (cells supply their own inner padding) so the
    // 田字格 dividers reach close to the card edges and the grid fills the space.
    WidgetLayoutVariant.ThreeByTwo -> 6.dp
    WidgetLayoutVariant.FourByTwo -> 8.dp
}

private fun WidgetLayoutVariant.verticalContentPadding() = when (this) {
    WidgetLayoutVariant.ThreeByOne -> 8.dp
    WidgetLayoutVariant.FourByOne -> 9.dp
    WidgetLayoutVariant.ThreeByTwo -> 8.dp
    WidgetLayoutVariant.FourByTwo -> 8.dp
}

private val WIDGET_TEXT = Color(0xFFFFFFFF)
private val WIDGET_MUTED_TEXT = Color(0xE6FFFFFF)
private val WIDGET_PERCENT_MUTED_TEXT = Color(0xCCFFFFFF)
private val WIDGET_ACCENT = Color(0xFF86C5FF)
private val WIDGET_SUCCESS = Color(0xFF5CF2A6)
private val WIDGET_WARNING = Color(0xFFFFC85A)
private val WIDGET_DANGER = Color(0xFFFF6B7A)
private val WIDGET_ACCENT_GLOW = Color(0x3386C5FF)
private val WIDGET_SUCCESS_GLOW = Color(0x335CF2A6)
private val WIDGET_WARNING_GLOW = Color(0x33FFC85A)
private val WIDGET_DANGER_GLOW = Color(0x33FF6B7A)
private val WIDGET_DIVIDER = Color(0x99B9D8FF)
// Lighter, "suggestive" separator for the 2-row grids — softer than the 4x1 divider.
private val WIDGET_DIVIDER_SOFT = Color(0x33B9D8FF)
