package com.kmnexus.codexmeter.ui.auth

import java.util.Base64
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class BrowserSessionTokenTest {
    @Test
    fun acceptsOnlyLiveJwtAndExactHttpsOrigin() {
        val payload = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""{"exp":2000}""".toByteArray())
        val token = "e30.$payload.signature"
        val callback = JsonPrimitive(token).toString()
        assertEquals(token, BrowserSessionToken.fromJavascript(callback, 1000))
        assertEquals(token, BrowserSessionToken.fromJavascript(JsonPrimitive(callback).toString(), 1000))
        for (invalid in listOf("null", "{}", "\"bad\"", "\"\"")) {
            assertNull(BrowserSessionToken.fromJavascript(invalid, 1000))
        }
        assertNull(BrowserSessionToken.fromJavascript(callback, 2000))
        assertNull(BrowserSessionToken.fromJavascript("\"e30.e30.signature\"", 1000))
        assertTrue(BrowserSessionToken.isTrustedUrl("https://www.kimi.com/code/console", "www.kimi.com"))
        for (url in listOf("http://www.kimi.com", "https://www.kimi.com.evil.test", "https://evil.test", "https://www.kimi.com:444", "https://user@www.kimi.com", "not a url")) {
            assertFalse(BrowserSessionToken.isTrustedUrl(url, "www.kimi.com"))
        }
    }
}
