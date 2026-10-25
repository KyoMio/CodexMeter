package com.kmnexus.codexmeter.ui.auth

import android.content.Intent
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.kmnexus.codexmeter.R
import com.kmnexus.codexmeter.domain.model.ProviderId
import com.kmnexus.codexmeter.providers.ProviderRegistry
import com.kmnexus.codexmeter.providers.common.auth.LoopbackCallbackServer
import com.kmnexus.codexmeter.ui.theme.CodexMeterTheme
import com.kmnexus.codexmeter.ui.theme.CodexMeterSpacing
import java.net.URLDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Auth UI for non-Codex providers that require a browser-based login.
 *
 * - [Cookie] hosts an embedded WebView and auto-captures the target session cookie once login
 *   completes (Kimi, Cursor).
 * - [OAuthIntercept] hosts an embedded WebView and intercepts the OAuth redirect to extract `code`
 *   without ever surfacing the callback URL (Claude, Antigravity). Denials / state mismatches /
 *   load failures surface as messages; the manual `code#state` paste block below the WebView is a
 *   per-provider opt-in last-resort channel ([WebViewAuthConfig.OAuthIntercept.manualPasteEnabled]).
 * - [OAuthLoopback] opens an external browser (Google blocks OAuth inside WebViews) and captures the
 *   `code` via a single-use [LoopbackCallbackServer] (Antigravity).
 *
 * The authorization `code` / cookie is handed to [onCredentialExtracted]; the callback URL and raw
 * credential are never displayed.
 */
sealed interface WebViewAuthConfig {
    val providerId: ProviderId

    data class LoginRegion(
        val label: String,
        val loginUrl: String,
        val cookieDomain: String,
    )

    data class Cookie(
        override val providerId: ProviderId,
        val loginUrl: String,
        val cookieDomain: String,
        val targetCookieNames: List<String>,
        val regions: List<LoginRegion> = emptyList(),
        /**
         * When false, the target cookie is captured only when the user taps "Done" — for sites that
         * set a guest/anonymous value of the cookie before login (e.g. Kimi), so auto-capture won't
         * submit a pre-login token and fail with 401.
         */
        val autoCapture: Boolean = true,
        /** Explicit trusted paths for cookies not visible at the domain root. */
        val cookiePaths: List<String> = emptyList(),
        /** Optional web-session JWT source, read only on explicit confirmation at cookieDomain. */
        val localStorageTokenKey: String? = null,
        /** Optional JS run on every page load — e.g. to open a provider's login modal automatically. */
        val injectOnLoadJs: String? = null,
        /** Optional one-time tip dialog shown when the screen opens (string resource id). */
        val tipResId: Int? = null,
    ) : WebViewAuthConfig

    data class OAuthIntercept(
        override val providerId: ProviderId,
        val authorizationUrl: String,
        val redirectUriPrefix: String,
        val expectedState: String,
        // Anthropic validates `state` in the token exchange, so pass the captured callback as
        // `code#state` (Claude). Google (Antigravity) needs only the bare code.
        val appendStateToCode: Boolean = false,
        /**
         * Shows the manual `code#state` paste fallback below the WebView. Only meaningful when the
         * provider console renders the grant as copyable text (Claude `code=true`); Google's
         * loopback redirect lands on a dead port with nothing to copy, so Antigravity keeps it off.
         */
        val manualPasteEnabled: Boolean = false,
        /** Optional one-time tip dialog shown when the screen opens (string resource id). */
        val tipResId: Int? = null,
    ) : WebViewAuthConfig

    data class OAuthLoopback(
        override val providerId: ProviderId,
        val expectedState: String,
        val authorizationUrlForRedirect: (redirectUri: String) -> String,
    ) : WebViewAuthConfig
}

@Composable
fun WebViewAuthScreen(
    config: WebViewAuthConfig,
    onCredentialExtracted: suspend (credential: String, redirectUri: String?) -> Result<Unit>,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onSaved: () -> Unit = onBack,
) {
    val providerConfig = remember(config.providerId) { ProviderRegistry.configFor(config.providerId) }
    val context = LocalContext.current
    var isVerifying by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val verificationFailed = stringResource(R.string.auth_verification_failed)
    val scope = rememberCoroutineScope()

    // Single-shot guard so cookie/redirect callbacks fire the import exactly once.
    var consumed by remember { mutableStateOf(false) }

    fun submit(credential: String, redirectUri: String?) {
        if (consumed || credential.isBlank()) return
        consumed = true
        isVerifying = true
        errorMessage = null
        scope.launch {
            val result = runCatching { onCredentialExtracted(credential, redirectUri) }
                .getOrElse { Result.failure(it) }
            if (result.isSuccess) {
                onSaved()
            } else {
                consumed = false
                isVerifying = false
                errorMessage = result.exceptionOrNull()?.message ?: verificationFailed
            }
        }
    }

    val tipResId = when (config) {
        is WebViewAuthConfig.Cookie -> config.tipResId
        is WebViewAuthConfig.OAuthIntercept -> config.tipResId
        is WebViewAuthConfig.OAuthLoopback -> null
    }
    var showTip by remember(config) { mutableStateOf(tipResId != null) }
    // WebView control actions live in the top bar as compact icons, set once the WebView exists.
    // reload: re-open the OAuth page without clearing cookies (Claude). clear: wipe the session and
    // restart. confirm: capture the cookie on demand (Kimi/Cursor).
    var webViewReload by remember(config) { mutableStateOf<(() -> Unit)?>(null) }
    var clearSession by remember(config) { mutableStateOf<(() -> Unit)?>(null) }
    var confirmLogin by remember(config) { mutableStateOf<(() -> Unit)?>(null) }

    AuthScaffold(
        title = providerConfig.displayName,
        onBack = onBack,
        modifier = modifier,
        actions = {
            webViewReload?.let { reload ->
                AuthActionIcon(R.drawable.ic_action_refresh, R.string.auth_reload, reload)
            }
            clearSession?.let { clear ->
                AuthActionIcon(R.drawable.ic_action_clear, R.string.auth_clear_cookies, clear)
            }
            confirmLogin?.let { confirm ->
                AuthActionIcon(R.drawable.ic_action_done, R.string.auth_confirm_login, confirm)
            }
        },
    ) {
        when (config) {
            is WebViewAuthConfig.Cookie -> CookieAuthBody(
                modifier = Modifier.weight(1f),
                config = config,
                isVerifying = isVerifying,
                errorMessage = errorMessage,
                onCredential = ::submit,
                onError = { errorMessage = it },
                onClearAction = { clearSession = it },
                onConfirmAction = { confirmLogin = it },
            )
            is WebViewAuthConfig.OAuthIntercept -> OAuthInterceptBody(
                modifier = Modifier.weight(1f),
                config = config,
                isVerifying = isVerifying,
                errorMessage = errorMessage,
                onCredential = ::submit,
                onFailure = { failure ->
                    errorMessage = when (failure) {
                        is AuthFlowFailure.Denied ->
                            if (failure.errorCode != null) {
                                context.getString(R.string.auth_callback_denied, failure.errorCode)
                            } else {
                                context.getString(R.string.auth_callback_denied_no_code)
                            }
                        AuthFlowFailure.StateMismatch -> context.getString(R.string.auth_callback_state_mismatch)
                        is AuthFlowFailure.PageLoadFailed ->
                            context.getString(R.string.auth_page_load_failed, failure.description)
                        AuthFlowFailure.ManualCodeInvalid -> context.getString(R.string.auth_manual_code_invalid)
                    }
                },
                onWebViewReady = { webView ->
                    webViewReload = { webView.loadUrl(config.authorizationUrl) }
                    clearSession = {
                        val cm = CookieManager.getInstance()
                        cm.removeAllCookies(null)
                        cm.flush()
                        android.webkit.WebStorage.getInstance().deleteAllData()
                        webView.clearHistory()
                        webView.clearCache(true)
                        webView.loadUrl(config.authorizationUrl)
                    }
                },
            )
            is WebViewAuthConfig.OAuthLoopback -> OAuthLoopbackBody(
                modifier = Modifier.weight(1f),
                config = config,
                isVerifying = isVerifying,
                errorMessage = errorMessage,
                onCredential = ::submit,
                onError = { errorMessage = it },
            )
        }
    }

    if (showTip && tipResId != null) {
        // The tip references the top-bar action, which is now an icon; render that icon inline where
        // the string's "%1$s" placeholder sits so the text matches what the user actually taps.
        val tipIconResId = when (config) {
            is WebViewAuthConfig.Cookie -> R.drawable.ic_action_done
            is WebViewAuthConfig.OAuthIntercept -> R.drawable.ic_action_refresh
            is WebViewAuthConfig.OAuthLoopback -> null
        }
        AlertDialog(
            onDismissRequest = { showTip = false },
            confirmButton = {
                TextButton(onClick = { showTip = false }) {
                    Text(stringResource(R.string.auth_tip_got_it))
                }
            },
            title = { Text(stringResource(R.string.auth_tip_title)) },
            text = { AuthTipText(stringResource(tipResId), tipIconResId) },
        )
    }
}

/** Tip body that swaps the string's "%1$s" placeholder for the inline top-bar action icon. */
@Composable
private fun AuthTipText(raw: String, iconResId: Int?) {
    if (iconResId == null) {
        Text(raw)
        return
    }
    val parts = remember(raw) { raw.split("%1\$s", limit = 2) }
    val inlineContent = mapOf(
        "icon" to InlineTextContent(
            Placeholder(20.sp, 20.sp, PlaceholderVerticalAlign.TextCenter),
        ) {
            Icon(
                painter = painterResource(iconResId),
                contentDescription = null,
                tint = CodexMeterTheme.colors.accent,
                modifier = Modifier.size(18.dp),
            )
        },
    )
    val annotated = buildAnnotatedString {
        append(parts[0])
        appendInlineContent("icon", "[icon]")
        if (parts.size > 1) append(parts[1])
    }
    Text(text = annotated, inlineContent = inlineContent)
}

/** Compact icon action for the auth top bar (reload / clear session / confirm login). */
@Composable
private fun AuthActionIcon(iconResId: Int, contentDescriptionResId: Int, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            painter = painterResource(iconResId),
            contentDescription = stringResource(contentDescriptionResId),
            tint = CodexMeterTheme.colors.accent,
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
private fun CookieAuthBody(
    config: WebViewAuthConfig.Cookie,
    isVerifying: Boolean,
    errorMessage: String?,
    onCredential: (String, String?) -> Unit,
    onError: (String) -> Unit,
    onClearAction: (() -> Unit) -> Unit,
    onConfirmAction: (() -> Unit) -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedRegion by remember {
        mutableStateOf(config.regions.firstOrNull())
    }
    val loginUrl = selectedRegion?.loginUrl ?: config.loginUrl
    val cookieDomain = selectedRegion?.cookieDomain ?: config.cookieDomain
    val noSession = stringResource(R.string.auth_cookie_no_session)
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    DisposableEffect(loginUrl) {
        onDispose { webViewRef = null }
    }

    // Publish the top-bar actions; re-published when the active region (and thus login URL / cookie
    // domain) changes. `webViewRef` is read lazily inside the clear lambda so it picks up the WebView
    // once the factory has created it.
    LaunchedEffect(loginUrl, cookieDomain) {
        onClearAction {
            val cm = CookieManager.getInstance()
            cm.removeAllCookies(null)
            cm.flush()
            android.webkit.WebStorage.getInstance().deleteAllData()
            webViewRef?.apply {
                clearHistory()
                clearCache(true)
                loadUrl(loginUrl)
            }
        }
        onConfirmAction {
            CookieManager.getInstance().flush()
            fun captureCookie() {
                val value = (config.cookiePaths + "").firstNotNullOfOrNull { path ->
                    readTargetCookie("https://$cookieDomain$path", config.targetCookieNames)
                }
                if (value != null) onCredential(value, null) else onError(noSession)
            }
            val view = webViewRef
            val storageKey = config.localStorageTokenKey
            if (storageKey != null && view != null &&
                BrowserSessionToken.isTrustedUrl(view.url, cookieDomain)
            ) {
                val origin = org.json.JSONObject.quote("https://$cookieDomain")
                val key = org.json.JSONObject.quote(storageKey)
                // Check inside JS too: navigation may occur between the native check and evaluation.
                view.evaluateJavascript(
                    """(() => { try {
                        if (location.origin !== $origin) return null;
                        return localStorage.getItem($key);
                    } catch (_) { return null; } })()""".trimIndent(),
                ) { result ->
                    if (webViewRef !== view || !BrowserSessionToken.isTrustedUrl(view.url, cookieDomain)) {
                        onError(noSession)
                    } else {
                        // Prefer the live web token over a potentially stale anonymous cookie.
                        val token = BrowserSessionToken.fromJavascript(result)
                        if (token != null) onCredential(token, null) else captureCookie()
                    }
                }
            } else {
                captureCookie()
            }
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        if (config.regions.isNotEmpty()) {
            Text(
                text = stringResource(R.string.auth_select_region),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = CodexMeterSpacing.xl, vertical = CodexMeterSpacing.xs),
            )
            config.regions.forEach { region ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = selectedRegion == region,
                            onClick = { selectedRegion = region },
                        )
                        .padding(horizontal = CodexMeterSpacing.xl, vertical = CodexMeterSpacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = selectedRegion == region, onClick = { selectedRegion = region })
                    Text(region.label, modifier = Modifier.padding(start = CodexMeterSpacing.sm))
                }
            }
        }

        Text(
            text = stringResource(R.string.auth_cookie_instruction),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = CodexMeterSpacing.xl, vertical = CodexMeterSpacing.sm),
        )

        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            key(loginUrl) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            configureAuthWebView(this, useSoftwareLayer = true)
                            webViewClient = CookieCaptureClient(
                                // Null capture URL = manual-only (user taps "Done"); avoids submitting
                                // a pre-login guest cookie.
                                cookieUrl = if (config.autoCapture) "https://$cookieDomain" else null,
                                targetCookieNames = config.targetCookieNames,
                                onLoadJs = config.injectOnLoadJs,
                                onCookie = { onCredential(it, null) },
                            )
                            webViewRef = this
                            loadUrl(loginUrl)
                        }
                    },
                )
            }
            if (isVerifying) VerifyingOverlay()
        }

        errorMessage?.let { ErrorLine(it) }
    }
}

@Composable
private fun OAuthInterceptBody(
    config: WebViewAuthConfig.OAuthIntercept,
    isVerifying: Boolean,
    errorMessage: String?,
    onCredential: (String, String?) -> Unit,
    onFailure: (AuthFlowFailure) -> Unit,
    onWebViewReady: (WebView) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var manualCode by remember { mutableStateOf("") }

    fun openAuthorizationPage() {
        runCatching {
            CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(config.authorizationUrl))
        }.onFailure {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(config.authorizationUrl)))
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        // Hardware layer (the default) here: forcing a software layer makes Google's
                        // tall sign-in page (Antigravity) render vertically compressed with dead space
                        // below the fold. The cookie pages still use the software workaround.
                        configureAuthWebView(this, useSoftwareLayer = false)
                        webViewClient = RedirectInterceptClient(
                            redirectUriPrefix = config.redirectUriPrefix,
                            expectedState = config.expectedState,
                            appendStateToCode = config.appendStateToCode,
                            onCode = { onCredential(it, config.redirectUriPrefix) },
                            onFailure = onFailure,
                        )
                        onWebViewReady(this)
                        loadUrl(config.authorizationUrl)
                    }
                },
            )
            if (isVerifying) VerifyingOverlay()
            errorMessage?.let {
                Column(modifier = Modifier.align(Alignment.BottomStart)) { ErrorLine(it) }
            }
        }
        // Manual fallback for pages the embedded WebView can't finish: the provider console renders
        // the grant as copyable `code#state` text (Claude `code=true`), so the same submit chain is
        // reachable without the WebView round-trip. Gated by manualPasteEnabled (Claude only).
        if (config.manualPasteEnabled) {
            Text(
                text = stringResource(R.string.auth_manual_paste_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = CodexMeterSpacing.xl, vertical = CodexMeterSpacing.xs),
            )
            TextButton(
                onClick = { openAuthorizationPage() },
                modifier = Modifier.padding(horizontal = CodexMeterSpacing.xl),
            ) { Text(stringResource(R.string.auth_open_in_browser)) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = CodexMeterSpacing.xl, vertical = CodexMeterSpacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = manualCode,
                    onValueChange = { manualCode = it },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = {
                        val credential = parseManualAuthCode(manualCode, config.expectedState)
                        if (credential == null) {
                            onFailure(AuthFlowFailure.ManualCodeInvalid)
                        } else {
                            onCredential(credential, config.redirectUriPrefix)
                        }
                    },
                ) { Text(stringResource(R.string.auth_submit_code)) }
            }
        }
    }
}

@Composable
private fun OAuthLoopbackBody(
    config: WebViewAuthConfig.OAuthLoopback,
    isVerifying: Boolean,
    errorMessage: String?,
    onCredential: (String, String?) -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val server = remember(config.expectedState) { LoopbackCallbackServer(config.expectedState) }
    val verificationFailed = stringResource(R.string.auth_verification_failed)

    fun openBrowser() {
        val url = config.authorizationUrlForRedirect(server.redirectUri)
        runCatching {
            CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(url))
        }.onFailure {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }

    DisposableEffect(server) {
        server.start()
        onDispose { server.close() }
    }

    LaunchedEffect(server) {
        openBrowser()
        val result = withContext(Dispatchers.IO) { server.awaitCode() }
        result.onSuccess { onCredential(it, server.redirectUri) }
            .onFailure { onError(it.message ?: verificationFailed) }
    }

    Column(
        modifier = modifier.fillMaxWidth().padding(CodexMeterSpacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (isVerifying) {
            VerifyingOverlay()
        } else {
            CircularProgressIndicator(modifier = Modifier.size(40.dp))
            Text(
                text = stringResource(R.string.auth_oauth_waiting),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = CodexMeterSpacing.md),
            )
            Text(
                text = stringResource(R.string.auth_oauth_browser_instruction),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = CodexMeterSpacing.sm),
            )
            TextButton(
                onClick = { openBrowser() },
                modifier = Modifier.padding(top = CodexMeterSpacing.lg),
            ) { Text(stringResource(R.string.auth_reopen_browser)) }
        }
        errorMessage?.let { ErrorLine(it) }
    }
}

@Composable
private fun VerifyingOverlay() {
    Column(
        modifier = Modifier.fillMaxSize().padding(CodexMeterSpacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(40.dp))
        Text(
            text = stringResource(R.string.auth_verifying),
            modifier = Modifier.padding(top = CodexMeterSpacing.md),
        )
    }
}

@Composable
private fun ErrorLine(message: String) {
    Text(
        text = message,
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(CodexMeterSpacing.md),
    )
}

/**
 * Anti-detection configuration shared by the embedded auth WebViews (Cursor/Kimi cookie capture,
 * Claude OAuth intercept). Android's default WebView user-agent carries a `; wv` token and a
 * `Version/x.y` marker that Google's sign-in ("disallowed_useragent") and Cloudflare's managed
 * challenge use to reject embedded browsers. Presenting as plain Chrome — while keeping the device's
 * real Chrome version — plus enabling storage/cookies lets those checks pass. This is standard
 * compatibility configuration, not credential or detection tampering.
 */
private fun configureAuthWebView(webView: WebView, useSoftwareLayer: Boolean) {
    // WRAP_CONTENT under AndroidView can make Chromium resolve CSS vh/dvh to 0.
    // Kimi's height:100dvh page then collapses even though the native view has height.
    webView.layoutParams = android.view.ViewGroup.LayoutParams(
        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
    )
    val cookieManager = CookieManager.getInstance()
    cookieManager.setAcceptCookie(true)
    cookieManager.setAcceptThirdPartyCookies(webView, true)
    // Some login pages (e.g. Google sign-in) need a chrome client to render / drive JS dialogs.
    webView.webChromeClient = WebChromeClient()
    // Cookie-capture pages (Kimi/Cursor) can paint blank on a hardware layer in a Compose
    // AndroidView, so they render on a software layer. The OAuth pages keep the hardware layer:
    // forcing software there compresses Google's tall sign-in page vertically.
    if (useSoftwareLayer) {
        webView.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null)
    }
    // Claude's OAuth consent disables "Authorize" while document.hasFocus() is false; an embedded
    // WebView in a Compose AndroidView doesn't always grab window focus, leaving the button dead.
    webView.isFocusable = true
    webView.isFocusableInTouchMode = true
    webView.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        javaScriptCanOpenWindowsAutomatically = true
        loadsImagesAutomatically = true
        useWideViewPort = true
        loadWithOverviewMode = true
        mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        // Present as Chrome (drop the WebView "wv"/"Version" tokens) so Google sign-in and Cloudflare
        // accept the client. (Confirmed not the cause of Kimi's blank render.)
        userAgentString = userAgentString
            .replace("; wv", "")
            .replace(Regex("Version/\\d+\\.\\d+ "), "")
    }
}

private class CookieCaptureClient(
    private val cookieUrl: String?,
    private val targetCookieNames: List<String>,
    private val onLoadJs: String?,
    private val onCookie: (String) -> Unit,
) : WebViewClient() {
    override fun onPageFinished(view: WebView?, url: String?) {
        onLoadJs?.let { view?.evaluateJavascript(it, null) }
        tryExtract()
    }

    override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
        tryExtract()
    }

    private fun tryExtract() {
        // cookieUrl == null means manual-only capture (the user taps "Done").
        val target = cookieUrl ?: return
        val value = readTargetCookie(target, targetCookieNames) ?: return
        CookieManager.getInstance().flush()
        onCookie(value)
    }
}

/** Reads the first non-empty target cookie value for [cookieUrl] from the shared cookie store. */
private fun readTargetCookie(cookieUrl: String, targetCookieNames: List<String>): String? {
    val raw = CookieManager.getInstance().getCookie(cookieUrl) ?: return null
    val cookies = raw.split(";").mapNotNull { part ->
        val pair = part.trim().split("=", limit = 2)
        val name = pair.getOrNull(0)?.trim().orEmpty()
        if (name.isEmpty()) null else name to pair.getOrNull(1)?.trim().orEmpty()
    }.toMap()
    return targetCookieNames.firstNotNullOfOrNull { cookies[it]?.takeIf { v -> v.isNotEmpty() } }
}

/** Outcome of classifying an OAuth callback URL against the expected redirect prefix and state. */
sealed interface OAuthCallbackDecision {
    /** Valid grant; [credential] is ready for the import chain (already `code#state` when needed). */
    data class Capture(val credential: String) : OAuthCallbackDecision

    /** The provider reported an authorization error, with its public [errorCode] if any. */
    data class Denied(val errorCode: String?) : OAuthCallbackDecision

    data object StateMismatch : OAuthCallbackDecision

    data object NotCallback : OAuthCallbackDecision
}

/**
 * Structured failures of the OAuth intercept flow, mapped to localized copy at the screen level.
 * Only provider-published error codes / load descriptions may be shown — never URLs, codes, states
 * or cookies.
 */
sealed interface AuthFlowFailure {
    data class Denied(val errorCode: String?) : AuthFlowFailure

    data object StateMismatch : AuthFlowFailure

    data class PageLoadFailed(val description: String) : AuthFlowFailure

    data object ManualCodeInvalid : AuthFlowFailure
}

/**
 * Classifies an OAuth redirect/callback URL. Precedence: an `error` parameter always denies (even
 * alongside a matching code+state); then a non-empty `code` with the exact expected `state`
 * captures; anything else on the callback path is a stale or foreign grant.
 */
internal fun evaluateOAuthCallback(
    url: String,
    redirectUriPrefix: String,
    expectedState: String,
    appendStateToCode: Boolean,
): OAuthCallbackDecision {
    if (!url.startsWith(redirectUriPrefix)) return OAuthCallbackDecision.NotCallback
    val params = callbackQueryParams(url)
    if (params.containsKey("error")) {
        return OAuthCallbackDecision.Denied(params["error"]?.takeIf { it.isNotEmpty() })
    }
    val code = params["code"]?.takeIf { it.isNotEmpty() } ?: return OAuthCallbackDecision.StateMismatch
    val state = params["state"] ?: return OAuthCallbackDecision.StateMismatch
    if (state != expectedState) return OAuthCallbackDecision.StateMismatch
    return OAuthCallbackDecision.Capture(if (appendStateToCode) "$code#$state" else code)
}

// Invisible characters clipboard apps can insert around copied text (zero-width space / ZWNJ / ZWJ / BOM).
private val MANUAL_CODE_ZERO_WIDTH_CHARS = charArrayOf('\u200B', '\u200C', '\u200D', '\uFEFF')

/**
 * Cleans a hand-pasted `code#state` grant: drops zero-width characters and surrounding whitespace,
 * then requires exactly one `#` whose suffix equals [expectedState] and a non-empty code. Returns
 * the cleaned `code#state`, or null when the input is not a valid grant.
 */
internal fun parseManualAuthCode(input: String, expectedState: String): String? {
    val cleaned = input.filter { it !in MANUAL_CODE_ZERO_WIDTH_CHARS }.trim()
    val parts = cleaned.split('#')
    if (parts.size != 2) return null
    val (code, state) = parts
    if (code.isEmpty() || state != expectedState) return null
    return cleaned
}

/** First-value query parameter map, percent-decoded (mirrors `Uri.getQueryParameter` semantics). */
private fun callbackQueryParams(url: String): Map<String, String> {
    val query = url.substringAfter('?', "")
    if (query.isEmpty()) return emptyMap()
    val params = linkedMapOf<String, String>()
    for (part in query.split('&')) {
        if (part.isEmpty()) continue
        val name = part.substringBefore('=')
        val rawValue = part.substringAfter('=', "")
        val value = runCatching { URLDecoder.decode(rawValue, "UTF-8") }.getOrDefault(rawValue)
        params.putIfAbsent(name, value)
    }
    return params
}

private class RedirectInterceptClient(
    private val redirectUriPrefix: String,
    private val expectedState: String,
    private val appendStateToCode: Boolean,
    private val onCode: (String) -> Unit,
    private val onFailure: (AuthFlowFailure) -> Unit,
) : WebViewClient() {
    private var handled = false

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        val url = request?.url?.toString() ?: return false
        return when (
            val decision =
                evaluateOAuthCallback(url, redirectUriPrefix, expectedState, appendStateToCode)
        ) {
            is OAuthCallbackDecision.NotCallback -> false
            is OAuthCallbackDecision.Capture -> {
                if (!handled) {
                    handled = true
                    onCode(decision.credential)
                }
                true
            }
            is OAuthCallbackDecision.Denied -> {
                reportFailure(AuthFlowFailure.Denied(decision.errorCode))
                // Let the provider's own error page render instead of swallowing the navigation.
                false
            }
            OAuthCallbackDecision.StateMismatch -> {
                reportFailure(AuthFlowFailure.StateMismatch)
                false
            }
        }
    }

    // Server-side 302 redirects to the callback URI aren't always routed through
    // shouldOverrideUrlLoading, so also catch them here. Only a capture stops the load; failures
    // are reported but the navigation proceeds.
    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
        val decision =
            evaluateOAuthCallback(
                url ?: return,
                redirectUriPrefix,
                expectedState,
                appendStateToCode,
            )
        when (decision) {
            // A fresh non-callback navigation (reload / clear-and-restart) re-arms capture so the
            // user can sign in again after a failure or a failed verification.
            is OAuthCallbackDecision.NotCallback -> handled = false
            is OAuthCallbackDecision.Capture -> if (!handled) {
                handled = true
                view?.stopLoading()
                onCode(decision.credential)
            }
            is OAuthCallbackDecision.Denied -> reportFailure(AuthFlowFailure.Denied(decision.errorCode))
            OAuthCallbackDecision.StateMismatch -> reportFailure(AuthFlowFailure.StateMismatch)
        }
    }

    // Give the loaded page window focus so consent screens that gate their primary button on
    // document.hasFocus() (Claude's "Authorize") enable without a manual tap.
    override fun onPageFinished(view: WebView?, url: String?) {
        view?.requestFocus()
    }

    // Surface main-frame load failures (blocked network, protocol errors, …) instead of leaving a
    // silent blank page; subresource errors are ignored.
    override fun onReceivedError(view: WebView?, request: WebResourceRequest, error: WebResourceError) {
        if (!request.isForMainFrame) return
        val description = error.description?.toString()?.takeIf { it.isNotBlank() }
            ?: "error ${error.errorCode}"
        reportFailure(AuthFlowFailure.PageLoadFailed(description))
    }

    // After a capture the verification overlay owns the UI; later reports would only overwrite it.
    private fun reportFailure(failure: AuthFlowFailure) {
        if (!handled) onFailure(failure)
    }
}
