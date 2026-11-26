package com.kmnexus.codexmeter.ui.home

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.kmnexus.codexmeter.ui.components.GlassPullToRefreshIndicator
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kmnexus.codexmeter.R
import com.kmnexus.codexmeter.data.currency.ExchangeRateReader
import com.kmnexus.codexmeter.domain.account.AccountListUseCase
import com.kmnexus.codexmeter.domain.account.NoopAccountListUseCase
import com.kmnexus.codexmeter.domain.account.DefaultAccountSelectUseCase
import com.kmnexus.codexmeter.domain.account.NoopDefaultAccountSelectUseCase
import com.kmnexus.codexmeter.domain.currency.CurrencyPreferenceReader
import com.kmnexus.codexmeter.domain.model.LocalAccountId
import com.kmnexus.codexmeter.domain.model.ProviderId
import com.kmnexus.codexmeter.domain.settings.NotificationPreferenceReader
import com.kmnexus.codexmeter.ui.motion.rememberCodexMeterAnimatorsEnabled
import com.kmnexus.codexmeter.ui.theme.CodexMeterShapes
import com.kmnexus.codexmeter.ui.theme.CodexMeterSpacing
import com.kmnexus.codexmeter.ui.theme.CodexMeterTheme
import com.kmnexus.codexmeter.ui.theme.CodexMeterTypography

@Composable
fun HomeRoute(
    modifier: Modifier = Modifier,
    currentQuotaStateLoader: HomeCurrentQuotaStateLoader,
    refreshUseCase: HomeRefreshUseCase,
    trendHistoryLoader: HomeTrendHistoryLoader = HomeTrendHistoryLoader { _, _ -> emptyList() },
    notificationPreferenceReader: NotificationPreferenceReader,
    currencyPreferenceReader: CurrencyPreferenceReader,
    exchangeRateReader: ExchangeRateReader,
    accountListUseCase: AccountListUseCase = NoopAccountListUseCase,
    defaultAccountSelectUseCase: DefaultAccountSelectUseCase = NoopDefaultAccountSelectUseCase,
    viewModel: HomeViewModel = viewModel(
        factory = HomeViewModel.factory(
            currentQuotaStateLoader = currentQuotaStateLoader,
            refreshUseCase = refreshUseCase,
            trendHistoryLoader = trendHistoryLoader,
            notificationPreferenceReader = notificationPreferenceReader,
            currencyPreferenceReader = currencyPreferenceReader,
            exchangeRateReader = exchangeRateReader,
            accountListUseCase = accountListUseCase,
            defaultAccountSelectUseCase = defaultAccountSelectUseCase,
        ),
    ),
    onLoginClick: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(viewModel) {
        viewModel.loadCurrentState()
    }

    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.loadCurrentState()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    HomeScreen(
        uiState = uiState,
        modifier = modifier,
        onRefreshClick = viewModel::refreshNow,
        onLoginClick = onLoginClick,
        onAccountSelected = viewModel::selectAccount,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    uiState: HomeUiState,
    modifier: Modifier = Modifier,
    onRefreshClick: () -> Unit = {},
    onLoginClick: () -> Unit = {},
    onAccountSelected: (ProviderId, LocalAccountId) -> Unit = { _, _ -> },
) {
    // Pull-to-refresh refreshes only the selected (default) account — the Account screen refreshes all.
    val pullState = rememberPullToRefreshState()
    PullToRefreshBox(
        isRefreshing = uiState.isRefreshing,
        onRefresh = onRefreshClick,
        modifier = modifier.fillMaxSize(),
        state = pullState,
        indicator = {
            GlassPullToRefreshIndicator(
                isRefreshing = uiState.isRefreshing,
                state = pullState,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        },
    ) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = CodexMeterSpacing.xl, vertical = CodexMeterSpacing.xxl),
        verticalArrangement = Arrangement.spacedBy(CodexMeterSpacing.lg),
    ) {
        HomeHeader(
            uiState = uiState,
            onRefreshClick = onRefreshClick,
        )

        if (uiState.contentStatus != HomeContentStatus.Unauthenticated && uiState.accountChoices.size > 1) {
            HomeAccountSelector(
                uiState = uiState,
                onAccountSelected = onAccountSelected,
            )
        }

        if (uiState.contentStatus == HomeContentStatus.Unauthenticated) {
            HomeActionCard(
                uiState = uiState,
                onLoginClick = onLoginClick,
            )
        } else {
            HomeHeroGlassLayer(uiState = uiState)
            if (uiState.quotaCards.isNotEmpty()) {
                HomeQuotaCards(
                    quotaCards = uiState.quotaCards,
                )
                HomeTrendCard(trend = uiState.trend)
            } else {
                uiState.loading?.let { loading ->
                    HomeLoadingCard(loading = loading)
                }
            }
            HomeRefreshCard(
                uiState = uiState,
            )
            if (uiState.primaryAction != null || uiState.secondaryAction != null) {
                HomeActionCard(
                    uiState = uiState,
                    onLoginClick = onLoginClick,
                )
            }
        }

        Spacer(modifier = Modifier.height(CodexMeterSpacing.bottomNavigationClearance))
    }
    }
}

@Composable
private fun HomeHeader(
    uiState: HomeUiState,
    onRefreshClick: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(CodexMeterSpacing.sm)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(CodexMeterSpacing.md),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(CodexMeterSpacing.sm),
            ) {
                Text(
                    text = stringResource(uiState.titleResId),
                    style = CodexMeterTypography.current.display,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = stringResource(uiState.statusDescriptionResId),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (uiState.contentStatus != HomeContentStatus.Unauthenticated) {
                HomeRefreshIconButton(
                    isRefreshing = uiState.isRefreshing,
                    onRefreshClick = onRefreshClick,
                )
            }
        }
    }
}

/**
 * Compact default-account switcher: a pill showing the viewed account that expands into a
 * dropdown of every local account. Picking one is the single "default account" write — quota
 * cards, trend and pull-to-refresh all follow it.
 */
@Composable
private fun HomeAccountSelector(
    uiState: HomeUiState,
    onAccountSelected: (ProviderId, LocalAccountId) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedId = uiState.account?.localAccountId
    androidx.compose.foundation.layout.Box {
        Row(
            modifier = Modifier
                .clip(CodexMeterShapes.pill)
                .background(CodexMeterTheme.colors.surface)
                .clickable { expanded = true }
                .padding(
                    horizontal = CodexMeterSpacing.md,
                    vertical = CodexMeterSpacing.sm,
                ),
            horizontalArrangement = Arrangement.spacedBy(CodexMeterSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = uiState.account?.displayName
                    ?: stringResource(R.string.home_account_selector_placeholder),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 160.dp),
            )
            Icon(
                painter = painterResource(R.drawable.ic_chevron_down),
                contentDescription = stringResource(R.string.home_account_selector),
                tint = CodexMeterTheme.colors.accent,
                modifier = Modifier.size(16.dp),
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            uiState.accountChoices.forEach { choice ->
                val selected = choice.localAccountId == selectedId
                DropdownMenuItem(
                    text = {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(CodexMeterSpacing.sm),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            HomeAccountAvatarBadge(
                                providerIconResId = choice.providerIconResId,
                                avatarInitial = choice.avatarInitial,
                                avatarColorKey = choice.avatarColorKey,
                                size = 24.dp,
                                iconSize = 14.dp,
                                initialStyle = MaterialTheme.typography.labelSmall,
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = choice.displayName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (selected) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_action_done),
                                    contentDescription = null,
                                    tint = CodexMeterTheme.colors.accent,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    },
                    onClick = {
                        expanded = false
                        if (!selected) {
                            onAccountSelected(choice.providerId, choice.localAccountId)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun HomeRefreshIconButton(
    isRefreshing: Boolean,
    onRefreshClick: () -> Unit,
) {
    val animatorsEnabled = rememberCodexMeterAnimatorsEnabled()
    val rotation = if (isRefreshing && animatorsEnabled) {
        val transition = rememberInfiniteTransition(label = "home_refresh_icon")
        val animatedRotation by transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 900, easing = LinearEasing),
            ),
            label = "home_refresh_icon_rotation",
        )
        animatedRotation
    } else {
        0f
    }
    IconButton(
        onClick = onRefreshClick,
        enabled = !isRefreshing,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_action_refresh),
            contentDescription = stringResource(R.string.home_refresh),
            // Manual refresh is the primary header action; accent stays visible on both light and
            // dark backgrounds (the default content color could fall back to near-invisible in dark).
            tint = CodexMeterTheme.colors.accent,
            modifier = Modifier.graphicsLayer {
                rotationZ = if (isRefreshing) rotation else 0f
            },
        )
    }
}
