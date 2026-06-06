package com.kmnexus.codexmeter.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import java.security.MessageDigest
import java.security.SecureRandom
import com.kmnexus.codexmeter.R
import com.kmnexus.codexmeter.domain.auth.DeviceCodeLoginController
import com.kmnexus.codexmeter.domain.auth.DeviceCodeLoginNotifier
import com.kmnexus.codexmeter.domain.auth.NoopDeviceCodeLoginController
import com.kmnexus.codexmeter.domain.auth.NoopDeviceCodeLoginNotifier
import com.kmnexus.codexmeter.domain.auth.ApiKeyLoginUseCase
import com.kmnexus.codexmeter.domain.auth.SessionLoginUseCase
import com.kmnexus.codexmeter.domain.account.AccountDeleteUseCase
import com.kmnexus.codexmeter.domain.account.AccountListUseCase
import com.kmnexus.codexmeter.domain.account.AccountRenameUseCase
import com.kmnexus.codexmeter.domain.account.AccountSwitchUseCase
import com.kmnexus.codexmeter.domain.account.NoopAccountRenameUseCase
import com.kmnexus.codexmeter.domain.account.NoopAccountSwitchUseCase
import com.kmnexus.codexmeter.domain.settings.NotificationPreferenceStore
import com.kmnexus.codexmeter.domain.settings.NoopQuotaHistoryClearUseCase
import com.kmnexus.codexmeter.domain.settings.QuotaHistoryClearUseCase
import com.kmnexus.codexmeter.domain.settings.RetentionPreferenceStore
import com.kmnexus.codexmeter.ui.account.AccountQuotaAlertEvaluationRequester
import com.kmnexus.codexmeter.ui.account.AccountRefreshAllUseCase
import com.kmnexus.codexmeter.ui.account.AccountRoute
import com.kmnexus.codexmeter.ui.account.NoopAccountQuotaAlertEvaluationRequester
import com.kmnexus.codexmeter.ui.account.NoopAccountRefreshAllUseCase
import com.kmnexus.codexmeter.ui.auth.AddAccountEntryMode
import com.kmnexus.codexmeter.ui.auth.AddAccountRoute
import com.kmnexus.codexmeter.ui.auth.ApiKeyAuthRegion
import com.kmnexus.codexmeter.ui.auth.ApiKeyAuthScreen
import com.kmnexus.codexmeter.refresh.RefreshWorkScheduler
import com.kmnexus.codexmeter.ui.auth.ProviderSelectionScreen
import com.kmnexus.codexmeter.ui.auth.ProviderSelectionSheet
import com.kmnexus.codexmeter.ui.auth.WebViewAuthConfig
import com.kmnexus.codexmeter.ui.auth.WebViewAuthScreen
import com.kmnexus.codexmeter.ui.components.CodexMeterBackdrop
import com.kmnexus.codexmeter.ui.components.LiquidGlassSurfaceRole
import com.kmnexus.codexmeter.ui.components.QmLiquidGlassSurface
import com.kmnexus.codexmeter.data.currency.ExchangeRateReader
import com.kmnexus.codexmeter.domain.currency.CurrencyPreferenceReader
import com.kmnexus.codexmeter.domain.currency.CurrencyPreferenceStore
import com.kmnexus.codexmeter.domain.currency.CurrencyPreferences
import com.kmnexus.codexmeter.ui.settings.NoopCurrencyPreferenceStore
import com.kmnexus.codexmeter.ui.home.HomeAppOpenRefreshUseCase
import com.kmnexus.codexmeter.ui.home.HomeCurrentQuotaStateLoader
import com.kmnexus.codexmeter.ui.home.HomeRefreshUseCase
import com.kmnexus.codexmeter.ui.home.HomeTrendHistoryLoader
import com.kmnexus.codexmeter.ui.home.HomeRoute
import com.kmnexus.codexmeter.ui.motion.CodexMeterMotion
import com.kmnexus.codexmeter.ui.motion.CodexMeterPageCascade
import com.kmnexus.codexmeter.ui.motion.rememberCodexMeterAnimatorsEnabled
import com.kmnexus.codexmeter.ui.theme.CodexMeterTheme
import com.kmnexus.codexmeter.ui.theme.CodexMeterShapes
import com.kmnexus.codexmeter.ui.theme.CodexMeterSpacing
import com.kmnexus.codexmeter.domain.update.AppUpdateCheckUseCase
import com.kmnexus.codexmeter.domain.update.AppUpdateDownloadUseCase
import com.kmnexus.codexmeter.domain.update.NoopAppUpdateCheckUseCase
import com.kmnexus.codexmeter.domain.update.NoopAppUpdateDownloadUseCase
import com.kmnexus.codexmeter.app.NotificationWindowChoicesLoader
import com.kmnexus.codexmeter.ui.settings.InMemoryNotificationPreferenceStore
import com.kmnexus.codexmeter.ui.settings.InMemoryRetentionPreferenceStore
import com.kmnexus.codexmeter.ui.settings.SettingsRoute
import com.kmnexus.codexmeter.ui.settings.SettingsDiagnosticsReader
import com.kmnexus.codexmeter.ui.settings.DefaultSettingsDiagnosticsReader

@Composable
fun CodexMeterNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    startDestination: String = CodexMeterRoute.Home.route,
    deviceCodeLoginController: DeviceCodeLoginController = NoopDeviceCodeLoginController,
    deviceCodeLoginNotifier: DeviceCodeLoginNotifier = NoopDeviceCodeLoginNotifier,
    accountListUseCase: AccountListUseCase,
    accountDeleteUseCase: AccountDeleteUseCase,
    accountSwitchUseCase: AccountSwitchUseCase = NoopAccountSwitchUseCase,
    accountRenameUseCase: AccountRenameUseCase = NoopAccountRenameUseCase,
    accountQuotaAlertEvaluationRequester: AccountQuotaAlertEvaluationRequester =
        NoopAccountQuotaAlertEvaluationRequester,
    accountRefreshAllUseCase: AccountRefreshAllUseCase = NoopAccountRefreshAllUseCase,
    homeCurrentQuotaStateLoader: HomeCurrentQuotaStateLoader,
    homeAppOpenRefreshUseCase: HomeAppOpenRefreshUseCase,
    homeRefreshUseCase: HomeRefreshUseCase,
    homeTrendHistoryLoader: HomeTrendHistoryLoader = HomeTrendHistoryLoader { _, _ -> emptyList() },
    currencyPreferenceReader: CurrencyPreferenceReader = object : CurrencyPreferenceReader {
        override suspend fun currencyPreferences() = CurrencyPreferences()
    },
    exchangeRateReader: ExchangeRateReader = object : ExchangeRateReader {
        override suspend fun currentRates() = null
    },

    retentionPreferenceStore: RetentionPreferenceStore = InMemoryRetentionPreferenceStore(),
    notificationPreferenceStore: NotificationPreferenceStore = InMemoryNotificationPreferenceStore(),
    backgroundRefreshStatusReader: com.kmnexus.codexmeter.ui.settings.SettingsBackgroundRefreshStatusReader =
        com.kmnexus.codexmeter.ui.settings.InMemoryBackgroundRefreshStatusReader(),
    settingsDiagnosticsReader: SettingsDiagnosticsReader = DefaultSettingsDiagnosticsReader,
    quotaHistoryClearUseCase: QuotaHistoryClearUseCase = NoopQuotaHistoryClearUseCase,
    appUpdateChecker: AppUpdateCheckUseCase = NoopAppUpdateCheckUseCase,
    appUpdateDownloader: AppUpdateDownloadUseCase = NoopAppUpdateDownloadUseCase,
    apiKeyLoginUseCase: SessionLoginUseCase? = null,
    notificationWindowChoicesLoader: NotificationWindowChoicesLoader =
        NotificationWindowChoicesLoader { _, _ -> emptyList() },
    currencyPreferenceStore: CurrencyPreferenceStore = NoopCurrencyPreferenceStore,
) {
    val tabs = CodexMeterRoute.bottomTabs
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route ?: CodexMeterRoute.Home.route

    // The provider picker is a bottom sheet that unfolds from the tab bar; hoisted here so the sheet
    // and the tab bar are siblings in the root Box (the bar must paint on top of the sheet).
    var providerSheetVisible by remember { mutableStateOf(false) }

    fun navigateToAddAccount(entryMode: AddAccountEntryMode) {
        navController.navigate(CodexMeterRoute.AddAccount.routeFor(entryMode))
    }

    fun navigateToProviderSelection() {
        providerSheetVisible = true
    }

    // After a successful add, land on the Account tab and clear the add-account flow off the stack.
    fun navigateToAccountAfterSave() {
        navController.navigate(CodexMeterRoute.Account.route) {
            popUpTo(CodexMeterRoute.Home.route) { inclusive = false }
            launchSingleTop = true
        }
    }

    fun navigateBasedOnAuthType(providerId: com.kmnexus.codexmeter.domain.model.ProviderId) {
        val config = com.kmnexus.codexmeter.providers.ProviderRegistry.configFor(providerId)
        val nextMode = when (config.authKind) {
            com.kmnexus.codexmeter.providers.ProviderAuthKind.OAuthWebView ->
                AddAccountEntryMode.LoginToCodex
            com.kmnexus.codexmeter.providers.ProviderAuthKind.ApiKeyImport ->
                AddAccountEntryMode.ApiKeyInput(providerId)
            com.kmnexus.codexmeter.providers.ProviderAuthKind.CookieAuth ->
                AddAccountEntryMode.WebViewCookieAuth(providerId)
            com.kmnexus.codexmeter.providers.ProviderAuthKind.OAuthPkceLogin ->
                AddAccountEntryMode.WebViewOAuthPkce(providerId)
        }
        navigateToAddAccount(nextMode)
    }

    // Re-login goes straight to the account's own provider sign-in (no provider picker), carrying the
    // target account so the new credential rebinds in place instead of creating a duplicate.
    fun navigateToRelogin(
        providerId: com.kmnexus.codexmeter.domain.model.ProviderId,
        localAccountId: com.kmnexus.codexmeter.domain.model.LocalAccountId,
        providerAccountId: String?,
    ) {
        val config = com.kmnexus.codexmeter.providers.ProviderRegistry.configFor(providerId)
        val nextMode = when (config.authKind) {
            com.kmnexus.codexmeter.providers.ProviderAuthKind.OAuthWebView ->
                AddAccountEntryMode.CodexRelogin(providerAccountId)
            com.kmnexus.codexmeter.providers.ProviderAuthKind.ApiKeyImport ->
                AddAccountEntryMode.ApiKeyInput(providerId, reloginAccountId = localAccountId)
            com.kmnexus.codexmeter.providers.ProviderAuthKind.CookieAuth ->
                AddAccountEntryMode.WebViewCookieAuth(providerId, reloginAccountId = localAccountId)
            com.kmnexus.codexmeter.providers.ProviderAuthKind.OAuthPkceLogin ->
                AddAccountEntryMode.WebViewOAuthPkce(providerId, reloginAccountId = localAccountId)
        }
        navigateToAddAccount(nextMode)
    }

    Box(modifier = modifier.fillMaxSize()) {
        CodexMeterBackdrop(modifier = Modifier.fillMaxSize())
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = Color.Transparent,
        ) { innerPadding ->
        @Composable
        fun AddAccountDestination(entryMode: AddAccountEntryMode) {
            when (entryMode) {
                is AddAccountEntryMode.ProviderSelection, AddAccountEntryMode.Choose -> {
                    CodexMeterDestination(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = innerPadding,
                    ) {
                        ProviderSelectionScreen(
                            onProviderSelected = { providerId ->
                                navigateBasedOnAuthType(providerId)
                            },
                            onBack = { navController.popBackStack() },
                        )
                    }
                }
                is AddAccountEntryMode.LoginToCodex, is AddAccountEntryMode.CodexRelogin -> {
                    CodexMeterDestination(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = innerPadding,
                    ) {
                        AddAccountRoute(
                            entryMode = entryMode,
                            deviceCodeLoginController = deviceCodeLoginController,
                            deviceCodeLoginNotifier = deviceCodeLoginNotifier,
                            onBackClick = { navController.popBackStack() },
                            onLoginSaved = { navigateToAccountAfterSave() },
                        )
                    }
                }
                is AddAccountEntryMode.ApiKeyInput -> {
                    CodexMeterDestination(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = innerPadding,
                    ) {
                        val useCase = apiKeyLoginUseCase
                        if (useCase != null) {
                            // Region options for providers whose balance/usage API differs by platform.
                            // Endpoint URLs are pending owner verification; persisted per account.
                            val regions = remember(entryMode.providerId) {
                                when (entryMode.providerId.value) {
                                    "zai" -> listOf(
                                        ApiKeyAuthRegion("🇨🇳 中国站 (bigmodel.cn)", "https://open.bigmodel.cn"),
                                        ApiKeyAuthRegion("🌍 国际站 (z.ai)", "https://api.z.ai"),
                                    )
                                    "minimax" -> listOf(
                                        ApiKeyAuthRegion("🌍 国际站 (api.minimax.io)", "https://api.minimax.io"),
                                        ApiKeyAuthRegion("🇨🇳 中国站 (api.minimaxi.com)", "https://api.minimaxi.com"),
                                    )
                                    else -> emptyList()
                                }
                            }
                            ApiKeyAuthScreen(
                                providerId = entryMode.providerId,
                                regions = regions,
                                onImportApiKey = { apiKey, label, apiBaseUrl ->
                                    val reloginAccountId = entryMode.reloginAccountId
                                    if (reloginAccountId != null) {
                                        useCase.reloginApiKey(
                                            localAccountId = reloginAccountId,
                                            apiKey = apiKey,
                                            apiBaseUrl = apiBaseUrl,
                                        ).map { }
                                    } else {
                                        useCase.importApiKey(
                                            providerId = entryMode.providerId,
                                            providerDisplayName = com.kmnexus.codexmeter.providers.ProviderRegistry
                                                .displayNameFor(entryMode.providerId),
                                            apiKey = apiKey,
                                            label = label,
                                            apiBaseUrl = apiBaseUrl,
                                        ).map { }
                                    }
                                },
                                onBack = { navController.popBackStack() },
                                onSaved = { navigateToAccountAfterSave() },
                            )
                        } else {
                            StubAuthScreen(
                                title = "API Key — ${entryMode.providerId.value}",
                                onBack = { navController.popBackStack() },
                            )
                        }
                    }
                }
                is AddAccountEntryMode.WebViewCookieAuth -> {
                    CodexMeterDestination(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = innerPadding,
                    ) {
                        val useCase = apiKeyLoginUseCase
                        if (useCase != null) {
                            val cookieConfig = remember(entryMode.providerId) {
                                when (entryMode.providerId.value) {
                                    "cursor" -> WebViewAuthConfig.Cookie(
                                        providerId = entryMode.providerId,
                                        loginUrl = "https://cursor.com",
                                        cookieDomain = "cursor.com",
                                        targetCookieNames = listOf("WorkosCursorSessionToken"),
                                    )
                                    // CodexBar uses a single www.kimi.com host (no region split);
                                    // the kimi-auth cookie is the JWT used for the billing API.
                                    // The coding console is where the kimi-auth session + billing API
                                    // live (www.kimi.com/ is just an SEO landing). kimi sets a guest
                                    // kimi-auth before login, so capture only on explicit "Done".
                                    "kimi" -> WebViewAuthConfig.Cookie(
                                        providerId = entryMode.providerId,
                                        loginUrl = "https://www.kimi.com/code/console",
                                        cookieDomain = "www.kimi.com",
                                        targetCookieNames = listOf("kimi-auth"),
                                        autoCapture = false,
                                        tipResId = R.string.auth_tip_kimi,
                                        // kimi's logged-out /code landing collapses to 0-height in a
                                        // WebView, but its login button opens a working modal — so open
                                        // it automatically (guarded so it won't re-trigger once shown).
                                        injectOnLoadJs = """
                                            if(!document.querySelector('input[type=tel]')){
                                              var t=[].slice.call(document.querySelectorAll('a,button,[role=button]'))
                                                .filter(function(e){return /登录|登入|log\s*in|sign\s*in/i.test(e.textContent||'')})[0];
                                              if(t)t.click();
                                            }
                                        """.trimIndent(),
                                    )
                                    else -> null
                                }
                            }
                            if (cookieConfig != null) {
                                WebViewAuthScreen(
                                    config = cookieConfig,
                                    onCredentialExtracted = { cookie, _ ->
                                        val reloginAccountId = entryMode.reloginAccountId
                                        if (reloginAccountId != null) {
                                            useCase.reloginCookie(
                                                localAccountId = reloginAccountId,
                                                cookieValue = cookie,
                                            ).map { }
                                        } else {
                                            useCase.importCookie(
                                                providerId = entryMode.providerId,
                                                providerDisplayName = com.kmnexus.codexmeter.providers.ProviderRegistry
                                                    .displayNameFor(entryMode.providerId),
                                                cookieValue = cookie,
                                            ).map { }
                                        }
                                    },
                                    onBack = { navController.popBackStack() },
                                    onSaved = { navigateToAccountAfterSave() },
                                )
                            } else {
                                StubAuthScreen(
                                    title = "Web Login — ${entryMode.providerId.value}",
                                    onBack = { navController.popBackStack() },
                                )
                            }
                        } else {
                            StubAuthScreen(
                                title = "Web Login — ${entryMode.providerId.value}",
                                onBack = { navController.popBackStack() },
                            )
                        }
                    }
                }
                is AddAccountEntryMode.WebViewOAuthPkce -> {
                    CodexMeterDestination(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = innerPadding,
                    ) {
                        val useCase = apiKeyLoginUseCase
                        if (useCase != null) {
                            val pkceParams = remember {
                                val verifier = generateCodeVerifier()
                                val challenge = generateCodeChallenge(verifier)
                                val state = generateRandomState()
                                Triple(verifier, challenge, state)
                            }
                            val (codeVerifier, codeChallenge, state) = pkceParams

                            val pkceConfig = remember(entryMode.providerId) {
                                when (entryMode.providerId.value) {
                                    "claude" -> {
                                        val authUrl = "https://claude.ai/oauth/authorize?" +
                                            "code=true" +
                                            "&client_id=9d1c250a-e61b-44d9-88ed-5944d1962f5e" +
                                            "&response_type=code" +
                                            "&redirect_uri=" + java.net.URLEncoder.encode(
                                                "https://console.anthropic.com/oauth/code/callback", "UTF-8") +
                                            "&scope=" + java.net.URLEncoder.encode(
                                                "org:create_api_key user:profile user:inference", "UTF-8") +
                                            "&state=$state" +
                                            "&code_challenge=$codeChallenge" +
                                            "&code_challenge_method=S256"
                                        WebViewAuthConfig.OAuthIntercept(
                                            providerId = entryMode.providerId,
                                            authorizationUrl = authUrl,
                                            redirectUriPrefix = "https://console.anthropic.com/oauth/code/callback",
                                            expectedState = state,
                                            appendStateToCode = true,
                                            tipResId = R.string.auth_tip_claude,
                                        )
                                    }
                                    // The embedded WebView now passes Google's bot/UA checks, so we
                                    // intercept the loopback redirect in-app instead of bouncing to an
                                    // external browser (which never returned the callback). Google's
                                    // loopback clients accept any 127.0.0.1 port; nothing actually binds
                                    // it — the redirect is caught before the WebView loads it.
                                    "antigravity" -> {
                                        val redirectUri = "http://127.0.0.1:8089/callback"
                                        val authUrl = buildOAuthUrl(
                                            "https://accounts.google.com/o/oauth2/v2/auth",
                                            "1071006060591-tmhssin2h21lcre235vtolojh4g403ep.apps.googleusercontent.com",
                                            redirectUri,
                                            "https://www.googleapis.com/auth/cloud-platform https://www.googleapis.com/auth/userinfo.email",
                                            codeChallenge,
                                            state,
                                        ) + "&access_type=offline"
                                        WebViewAuthConfig.OAuthIntercept(
                                            providerId = entryMode.providerId,
                                            authorizationUrl = authUrl,
                                            redirectUriPrefix = redirectUri,
                                            expectedState = state,
                                        )
                                    }
                                    else -> null
                                }
                            }
                            if (pkceConfig != null) {
                                WebViewAuthScreen(
                                    config = pkceConfig,
                                    onCredentialExtracted = { code, redirectUri ->
                                        val reloginAccountId = entryMode.reloginAccountId
                                        if (reloginAccountId != null) {
                                            useCase.reloginOAuthPkce(
                                                localAccountId = reloginAccountId,
                                                code = code,
                                                verifier = codeVerifier,
                                                redirectUri = redirectUri.orEmpty(),
                                            ).map { }
                                        } else {
                                            useCase.importOAuthPkce(
                                                providerId = entryMode.providerId,
                                                providerDisplayName = com.kmnexus.codexmeter.providers.ProviderRegistry
                                                    .displayNameFor(entryMode.providerId),
                                                code = code,
                                                verifier = codeVerifier,
                                                redirectUri = redirectUri.orEmpty(),
                                            ).map { }
                                        }
                                    },
                                    onBack = { navController.popBackStack() },
                                    onSaved = { navigateToAccountAfterSave() },
                                )
                            } else {
                                StubAuthScreen(
                                    title = "OAuth PKCE — ${entryMode.providerId.value}",
                                    onBack = { navController.popBackStack() },
                                )
                            }
                        } else {
                            StubAuthScreen(
                                title = "OAuth PKCE — ${entryMode.providerId.value}",
                                onBack = { navController.popBackStack() },
                            )
                        }
                    }
                }
            }
        }

        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.fillMaxSize(),
            enterTransition = { EnterTransition.None },
            exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { ExitTransition.None },
        ) {
            composable(CodexMeterRoute.Home.route) {
                CodexMeterDestination(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = innerPadding,
                ) {
                    HomeRoute(
                        currentQuotaStateLoader = homeCurrentQuotaStateLoader,
                        appOpenRefreshUseCase = homeAppOpenRefreshUseCase,
                        refreshUseCase = homeRefreshUseCase,
                        trendHistoryLoader = homeTrendHistoryLoader,
                        notificationPreferenceReader = notificationPreferenceStore,
                        currencyPreferenceReader = currencyPreferenceReader,
                        exchangeRateReader = exchangeRateReader,
                        onLoginClick = { navigateToProviderSelection() },
                    )
                }
            }
            composable(CodexMeterRoute.Account.route) {
                CodexMeterDestination(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = innerPadding,
                ) {
                    AccountRoute(
                        accountListUseCase = accountListUseCase,
                        accountDeleteUseCase = accountDeleteUseCase,
                        accountSwitchUseCase = accountSwitchUseCase,
                        accountRenameUseCase = accountRenameUseCase,
                        notificationPreferenceStore = notificationPreferenceStore,
                        quotaAlertEvaluationRequester = accountQuotaAlertEvaluationRequester,
                        refreshAllUseCase = accountRefreshAllUseCase,
                        currencyPreferenceReader = currencyPreferenceReader,
                        exchangeRateReader = exchangeRateReader,
                        onLoginToCodexClick = { navigateToProviderSelection() },
                        onAddAccountClick = { navigateToProviderSelection() },
                        onReloginAccount = { providerId, localAccountId, providerAccountId ->
                            navigateToRelogin(providerId, localAccountId, providerAccountId)
                        },
                    )
                }
            }
            composable(CodexMeterRoute.Settings.route) {
                val settingsContext = LocalContext.current
                CodexMeterDestination(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = innerPadding,
                ) {
                    SettingsRoute(
                        accountListUseCase = accountListUseCase,
                        retentionPreferenceStore = retentionPreferenceStore,
                        notificationPreferenceStore = notificationPreferenceStore,
                        backgroundRefreshStatusReader = backgroundRefreshStatusReader,
                        diagnosticsReader = settingsDiagnosticsReader,
                        quotaHistoryClearUseCase = quotaHistoryClearUseCase,
                        appUpdateChecker = appUpdateChecker,
                        appUpdateDownloader = appUpdateDownloader,
                        backgroundRefreshScheduler = { minutes ->
                            RefreshWorkScheduler.from(settingsContext).applyIntervalMinutes(minutes)
                        },
                        notificationWindowChoicesLoader = notificationWindowChoicesLoader,
                        currencyPreferenceStore = currencyPreferenceStore,
                    )
                }
            }
            composable(CodexMeterRoute.AddAccount.route) {
                AddAccountDestination(entryMode = AddAccountEntryMode.Choose)
            }
            composable(
                route = CodexMeterRoute.AddAccount.routeWithEntryMode,
                arguments = listOf(
                    navArgument(CodexMeterRoute.AddAccount.ENTRY_MODE_ARG) {
                        type = NavType.StringType
                    },
                ),
            ) { backStackEntry ->
                AddAccountDestination(
                    entryMode = AddAccountEntryMode.fromRouteValue(
                        backStackEntry.arguments?.getString(CodexMeterRoute.AddAccount.ENTRY_MODE_ARG),
                    ),
                )
            }
        }
        }
        // The per-provider login flow is a full-screen modal; hiding the tab bar there keeps its
        // actions and error text from being covered by the floating bar.
        val showBottomBar = tabs.any { it.route == currentRoute }
        if (showBottomBar) {
        CodexMeterBottomBar(
            modifier = Modifier.align(Alignment.BottomCenter),
            tabs = tabs,
            currentRoute = currentRoute,
            onTabSelected = { tab ->
                // Don't leave the picker hovering over a freshly switched tab.
                providerSheetVisible = false
                navController.navigate(tab.route) {
                    popUpTo(navController.graph.findStartDestination().id) {
                        saveState = true
                    }
                    launchSingleTop = true
                    restoreState = true
                }
            },
        )
        }
        // Provider picker sheet — rendered LAST so it paints over the tab bar: the panel grows up
        // out of the bar's position and covers it while open. Dismisses before branching into the
        // per-provider full-screen login.
        ProviderSelectionSheet(
            visible = providerSheetVisible,
            onProviderSelected = { providerId ->
                providerSheetVisible = false
                navigateBasedOnAuthType(providerId)
            },
            onDismiss = { providerSheetVisible = false },
        )
    }
}

@Composable
private fun CodexMeterDestination(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues,
    content: @Composable () -> Unit,
) {
    Box(modifier = modifier.fillMaxSize()) {
        CodexMeterBackdrop(modifier = Modifier.fillMaxSize())
        CodexMeterPageCascade(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
        ) {
            content()
        }
    }
}

@Composable
private fun CodexMeterBottomBar(
    modifier: Modifier = Modifier,
    tabs: List<CodexMeterRoute>,
    currentRoute: String,
    onTabSelected: (CodexMeterRoute) -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .background(
                Brush.verticalGradient(
                    0.00f to Color.Transparent,
                    0.24f to Color.Transparent,
                    0.58f to CodexMeterTheme.colors.neutral.copy(alpha = 0.54f),
                    1.00f to CodexMeterTheme.colors.neutralAlt.copy(alpha = 0.90f),
                ),
            )
            .padding(
                top = CodexMeterSpacing.xxl,
                start = CodexMeterSpacing.xl,
                end = CodexMeterSpacing.xl,
                bottom = CodexMeterSpacing.md,
            ),
        contentAlignment = Alignment.Center,
    ) {
        QmLiquidGlassSurface(
            modifier = Modifier
                .fillMaxWidth(0.86f)
                .height(68.dp),
            role = LiquidGlassSurfaceRole.Navigation,
            cornerRadius = 34.dp,
            contentPadding = PaddingValues(5.dp),
        ) {
            val currentIndex = tabs.indexOfFirst { it.route == currentRoute }.coerceAtLeast(0)
            val animatorsEnabled = rememberCodexMeterAnimatorsEnabled()
            BoxWithConstraints(
                modifier = Modifier.fillMaxSize(),
            ) {
                val tabSpacing = CodexMeterSpacing.xs
                val indicatorGeometry = CodexMeterMotion.bottomTabIndicatorGeometry(
                    selectedIndex = currentIndex,
                    tabCount = tabs.size,
                    containerWidth = maxWidth.value,
                    itemSpacing = tabSpacing.value,
                )
                val indicatorStyle = CodexMeterMotion.bottomTabIndicatorStyle()
                val indicatorOffset by animateDpAsState(
                    targetValue = indicatorGeometry.offset.dp,
                    animationSpec = if (animatorsEnabled) {
                        tween(
                            durationMillis = CodexMeterMotion.BottomTabDurationMillis,
                            easing = FastOutSlowInEasing,
                        )
                    } else {
                        snap()
                    },
                    label = "bottom_tab_indicator_offset",
                )
                val indicatorWidth by animateDpAsState(
                    targetValue = indicatorGeometry.width.dp,
                    animationSpec = if (animatorsEnabled) {
                        tween(
                            durationMillis = CodexMeterMotion.BottomTabDurationMillis,
                            easing = FastOutSlowInEasing,
                        )
                    } else {
                        snap()
                    },
                    label = "bottom_tab_indicator_width",
                )
                Box(
                    modifier = Modifier
                        .offset(x = indicatorOffset)
                        .width(indicatorWidth)
                        .height(56.dp)
                        .align(Alignment.CenterStart)
                        .clip(CodexMeterShapes.lg)
                        .background(indicatorStyle.color.copy(alpha = indicatorStyle.alpha)),
                )
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(tabSpacing),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    tabs.forEach { tab ->
                        val selected = currentRoute == tab.route
                        CodexMeterBottomBarItem(
                            tab = tab,
                            selected = selected,
                            onClick = {
                                if (!selected) {
                                    onTabSelected(tab)
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.CodexMeterBottomBarItem(
    tab: CodexMeterRoute,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val animatorsEnabled = rememberCodexMeterAnimatorsEnabled()
    val tabInteractionSource = remember { MutableInteractionSource() }
    val tabPressIndication = if (CodexMeterMotion.bottomTabPressIndicationEnabled()) {
        LocalIndication.current
    } else {
        null
    }
    val colorAnimationSpec = if (animatorsEnabled) {
        tween<Color>(
            durationMillis = CodexMeterMotion.BottomTabDurationMillis,
            easing = FastOutSlowInEasing,
        )
    } else {
        snap()
    }
    val contentColor by animateColorAsState(
        targetValue = if (selected) {
            CodexMeterTheme.colors.accent
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = colorAnimationSpec,
        label = "bottom_tab_content_color",
    )
    val iconScale by animateFloatAsState(
        targetValue = CodexMeterMotion.bottomTabScale(selected),
        animationSpec = if (animatorsEnabled) {
            tween(
                durationMillis = CodexMeterMotion.BottomTabDurationMillis,
                easing = FastOutSlowInEasing,
            )
        } else {
            snap()
        },
        label = "bottom_tab_icon_scale",
    )
    Column(
        modifier = Modifier
            .weight(1f)
            .height(56.dp)
            .clip(CodexMeterShapes.lg)
            .selectable(
                selected = selected,
                interactionSource = tabInteractionSource,
                indication = tabPressIndication,
                role = Role.Tab,
                onClick = onClick,
            )
            .padding(vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            painter = painterResource(tab.iconResId),
            contentDescription = stringResource(tab.contentDescriptionResId),
            modifier = Modifier
                .size(24.dp)
                .graphicsLayer {
                    scaleX = iconScale
                    scaleY = iconScale
                },
            tint = contentColor,
        )
        Text(
            text = stringResource(tab.labelResId),
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
            maxLines = 1,
        )
    }
}

private fun generateCodeVerifier(): String {
    val bytes = ByteArray(32)
    SecureRandom().nextBytes(bytes)
    return bytes.joinToString("") { "%02x".format(it) }
}

private fun generateCodeChallenge(verifier: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray())
    return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
}

private fun generateRandomState(): String {
    val bytes = ByteArray(16)
    SecureRandom().nextBytes(bytes)
    return bytes.joinToString("") { "%02x".format(it) }
}

private fun buildOAuthUrl(
    endpoint: String,
    clientId: String,
    redirectUri: String,
    scope: String,
    codeChallenge: String,
    state: String,
): String =
    "$endpoint?" +
        "client_id=${java.net.URLEncoder.encode(clientId, "UTF-8")}" +
        "&redirect_uri=${java.net.URLEncoder.encode(redirectUri, "UTF-8")}" +
        "&response_type=code" +
        "&scope=${java.net.URLEncoder.encode(scope, "UTF-8")}" +
        "&state=$state" +
        "&code_challenge=$codeChallenge" +
        "&code_challenge_method=S256"
