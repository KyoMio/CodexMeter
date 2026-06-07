package com.kmnexus.codexmeter.ui.auth

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kmnexus.codexmeter.R
import com.kmnexus.codexmeter.ui.theme.CodexMeterTheme
import com.kmnexus.codexmeter.ui.theme.CodexMeterSpacing

/**
 * Shared chrome for every provider sign-in screen (Codex device-code, WebView cookie / OAuth, API
 * key). A single fixed-height top bar with a left-aligned back control and a centered-baseline title
 * keeps the header visually identical across providers and removes the per-screen misalignment that
 * came from ad-hoc [Row] headers where a [androidx.compose.material3.TextButton] and a [Text] sat on
 * different vertical baselines. Background is supplied by the navigation destination's backdrop, so
 * this scaffold does not draw its own.
 */
@Composable
fun AuthScaffold(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxSize()) {
        AuthTopBar(title = title, onBack = onBack, actions = actions)
        content()
    }
}

@Composable
private fun AuthTopBar(
    title: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = CodexMeterSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                // Reuse the chevron asset, rotated to point left as a back affordance.
                painter = painterResource(R.drawable.ic_chevron_down),
                contentDescription = stringResource(R.string.auth_back),
                tint = CodexMeterTheme.colors.primary,
                modifier = Modifier
                    .size(22.dp)
                    .rotate(90f),
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = CodexMeterTheme.colors.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(start = CodexMeterSpacing.xs, end = CodexMeterSpacing.sm),
        )
        actions()
    }
}
