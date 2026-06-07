package com.kmnexus.codexmeter.ui.auth

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kmnexus.codexmeter.R
import com.kmnexus.codexmeter.domain.model.ProviderId
import com.kmnexus.codexmeter.providers.ProviderRegistry
import com.kmnexus.codexmeter.ui.theme.CodexMeterTheme
import com.kmnexus.codexmeter.ui.theme.CodexMeterSpacing
import com.kmnexus.codexmeter.ui.theme.CodexMeterTypography

private const val SHEET_DURATION_MILLIS = 320
private const val SHEET_EXIT_DURATION_MILLIS = 250

/**
 * Provider picker presented as a bottom sheet that grows up and out of the floating tab bar and
 * collapses back down into it on dismiss. Painted over the tab bar so the panel covers it while
 * open, reinforcing the "unfolding out of the bar" motion. Reuses [ProviderListItem] so the list
 * rows stay identical to the rest of the add-account flow.
 */
@Composable
fun ProviderSelectionSheet(
    visible: Boolean,
    onProviderSelected: (ProviderId) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(SHEET_DURATION_MILLIS)),
            exit = fadeOut(tween(SHEET_EXIT_DURATION_MILLIS)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(CodexMeterTheme.colors.glassInk.copy(alpha = 0.30f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    ),
            )
        }
        AnimatedVisibility(
            visible = visible,
            modifier = Modifier.align(Alignment.BottomCenter),
            // Expand/scale from the bottom edge so the panel appears to unfold out of the tab bar.
            enter = expandVertically(
                animationSpec = tween(SHEET_DURATION_MILLIS, easing = FastOutSlowInEasing),
                expandFrom = Alignment.Bottom,
            ) + scaleIn(
                animationSpec = tween(SHEET_DURATION_MILLIS, easing = FastOutSlowInEasing),
                initialScale = 0.88f,
                transformOrigin = TransformOrigin(0.5f, 1f),
            ) + fadeIn(tween(SHEET_DURATION_MILLIS)),
            exit = shrinkVertically(
                animationSpec = tween(SHEET_EXIT_DURATION_MILLIS, easing = FastOutSlowInEasing),
                shrinkTowards = Alignment.Bottom,
            ) + scaleOut(
                animationSpec = tween(SHEET_EXIT_DURATION_MILLIS, easing = FastOutSlowInEasing),
                targetScale = 0.88f,
                transformOrigin = TransformOrigin(0.5f, 1f),
            ) + fadeOut(tween(SHEET_EXIT_DURATION_MILLIS)),
        ) {
            ProviderSheetPanel(onProviderSelected = onProviderSelected)
        }
    }
}

@Composable
private fun ProviderSheetPanel(
    onProviderSelected: (ProviderId) -> Unit,
) {
    val providers = ProviderRegistry.all
    val screenHeightDp = LocalConfiguration.current.screenHeightDp
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
            .background(
                Brush.verticalGradient(
                    listOf(CodexMeterTheme.colors.surface, CodexMeterTheme.colors.surfaceSoft),
                ),
            )
            .navigationBarsPadding()
            .padding(horizontal = CodexMeterSpacing.xl)
            // The sheet now covers the floating tab bar, so it reaches the navigation bar directly
            // and only needs comfortable inner breathing room at the bottom.
            .padding(top = CodexMeterSpacing.md, bottom = CodexMeterSpacing.xxl),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .width(40.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(CodexMeterTheme.colors.border),
        )
        Spacer(modifier = Modifier.height(CodexMeterSpacing.md))
        Text(
            text = stringResource(R.string.add_provider_title),
            style = CodexMeterTypography.current.title,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(modifier = Modifier.height(CodexMeterSpacing.xs))
        Text(
            text = stringResource(R.string.add_provider_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(CodexMeterSpacing.lg))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // Cap the list so a long provider roster scrolls instead of pushing the sheet
                // taller than the screen.
                .heightIn(max = (screenHeightDp * 0.52f).dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(CodexMeterSpacing.md),
        ) {
            providers.forEach { provider ->
                ProviderListItem(
                    provider = provider,
                    onClick = { onProviderSelected(provider.providerId) },
                )
            }
        }
    }
}
