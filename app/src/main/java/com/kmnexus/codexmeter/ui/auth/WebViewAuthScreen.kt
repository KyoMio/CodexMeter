package com.kmnexus.codexmeter.ui.auth

import android.content.Intent
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebChromeClient
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
import com.kmnexus.codexmeter.ui.theme.CodexMeterColors
import com.kmnexus.codexmeter.ui.theme.CodexMeterSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Auth UI for non-Codex providers that require a browser-based login.
 *
 * - [Cookie] hosts an embedded WebView and auto-captures the target session cookie once login
 *   completes (Kimi, Cursor).
 * - [OAuthIntercept] hosts an embedded WebView and intercepts the OAuth redirect to extract `code`
 *   without ever surfacing the callback URL (Claude).
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
                tint = CodexMeterColors.accent,
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
            tint = CodexMeterColors.accent,
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
            val value = readTargetCookie("https://$cookieDomain", config.targetCookieNames)
            if (value != null) onCredential(value, null) else onError(noSession)
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
    onWebViewReady: (WebView) -> Unit,
    modifier: Modifier = Modifier,
) {
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

private class RedirectInterceptClient(
    private val redirectUriPrefix: String,
    private val expectedState: String,
    private val appendStateToCode: Boolean,
    private val onCode: (String) -> Unit,
) : WebViewClient() {
    private var handled = false

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        val url = request?.url?.toString() ?: return false
        if (!url.startsWith(redirectUriPrefix)) return false
        maybeCapture(view, url)
        return true
    }

    // Server-side 302 redirects to the (loopback / callback) URI aren't always routed through
    // shouldOverrideUrlLoading, so also catch the navigation here and stop the doomed load.
    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
        if (url != null && url.startsWith(redirectUriPrefix)) {
            view?.stopLoading()
            maybeCapture(view, url)
        }
    }

    // Give the loaded page window focus so consent screens that gate their primary button on
    // document.hasFocus() (Claude's "Authorize") enable without a manual tap.
    override fun onPageFinished(view: WebView?, url: String?) {
        view?.requestFocus()
    }

    private fun maybeCapture(view: WebView?, url: String) {
        if (handled) return
        val uri = Uri.parse(url)
        val code = uri.getQueryParameter("code")
        val state = uri.getQueryParameter("state")
        if (code != null && state == expectedState) {
            handled = true
            view?.stopLoading()
            onCode(if (appendStateToCode) "$code#$state" else code)
        }
    }
}
