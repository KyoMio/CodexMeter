package com.kmnexus.codexmeter.ui.auth

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kmnexus.codexmeter.MainActivity
import com.kmnexus.codexmeter.domain.model.ProviderId
import com.kmnexus.codexmeter.ui.theme.CodexMeterTheme
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real Chromium regression: native view height alone does not prove CSS viewport units work. */
@RunWith(AndroidJUnit4::class)
class WebViewViewportTest {
    @Test
    fun cookieAndOAuthPagesKeepCssViewportHeight() {
        val page = "data:text/html," + android.net.Uri.encode("""
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <div id="viewport" style="height:100dvh"></div>
        """.trimIndent())
        val configs = listOf(
            WebViewAuthConfig.Cookie(
                providerId = ProviderId("kimi"),
                loginUrl = page,
                cookieDomain = "viewport.invalid",
                targetCookieNames = emptyList(),
                autoCapture = false,
            ),
            WebViewAuthConfig.OAuthIntercept(
                providerId = ProviderId("claude"),
                authorizationUrl = page,
                redirectUriPrefix = "https://viewport.invalid/callback",
                expectedState = "viewport-test",
            ),
        )
        for (config in configs) {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContent {
                        CodexMeterTheme {
                            WebViewAuthScreen(
                                config = config,
                                onCredentialExtracted = { _, _ -> error("No credentials in this test") },
                                onBack = {},
                            )
                        }
                    }
                }
                var validViewport = false
                val deadline = SystemClock.elapsedRealtime() + 10_000
                while (!validViewport && SystemClock.elapsedRealtime() < deadline) {
                    val evaluated = CountDownLatch(1)
                    scenario.onActivity { activity ->
                        val webView = findWebView(activity.window.decorView)
                        if (webView == null) {
                            evaluated.countDown()
                        } else {
                            webView.evaluateJavascript("""
                                (() => {
                                  const el = document.getElementById('viewport');
                                  return !!el && innerHeight > 0 &&
                                    Math.abs(el.getBoundingClientRect().height - innerHeight) < 2;
                                })()
                            """.trimIndent()) { result ->
                                validViewport = result == "true"
                                evaluated.countDown()
                            }
                        }
                    }
                    assertTrue("JavaScript callback timed out", evaluated.await(2, TimeUnit.SECONDS))
                    if (!validViewport) SystemClock.sleep(100)
                }
                assertTrue("CSS 100dvh collapsed for ${config.providerId.value}", validViewport)
                scenario.onActivity { activity ->
                    findWebView(activity.window.decorView)?.destroy()
                }
            }
        }
    }

    private fun findWebView(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findWebView(view.getChildAt(index))?.let { return it }
            }
        }
        return null
    }
}
