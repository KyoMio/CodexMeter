package com.kmnexus.codexmeter.domain.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsRedactorTest {
    @Test
    fun `redacts bearer token`() {
        val redacted = DiagnosticsRedactor.redact("Authorization: Bearer secret-token")

        assertFalse(redacted.contains("secret-token"))
        assertTrue(redacted.contains("[REDACTED]"))
    }

    @Test
    fun `keeps safe diagnostic error code field while redacting standalone oauth code`() {
        val redacted = DiagnosticsRedactor.redact(
            "safeErrorCode=error_network\ndeviceCodeLoginSafeErrorCode=error_network\ncode=secret-code",
        )

        assertTrue(redacted.contains("safeErrorCode=error_network"))
        assertTrue(redacted.contains("deviceCodeLoginSafeErrorCode=error_network"))
        assertFalse(redacted.contains("secret-code"))
        assertTrue(redacted.contains("code=[REDACTED]"))
    }

    @Test
    fun `redacts auth json token fields`() {
        val redacted = DiagnosticsRedactor.redact("""{"access_token":"aaa","refresh_token":"bbb"}""")

        assertFalse(redacted.contains("aaa"))
        assertFalse(redacted.contains("bbb"))
    }

    @Test
    fun `collapses full auth json payload`() {
        val redacted = DiagnosticsRedactor.redact(
            """{"tokens":{"access_token":"dummy-access","refresh_token":"dummy-refresh"},"account_id":"dummy-account","email":"dummy@example.test"}""",
        )

        assertEquals("[REDACTED]", redacted)
        assertFalse(redacted.contains("dummy-access"))
        assertFalse(redacted.contains("dummy-refresh"))
        assertFalse(redacted.contains("dummy-account"))
        assertFalse(redacted.contains("dummy@example.test"))
    }

    @Test
    fun `collapses prefixed auth json payload`() {
        val redacted = DiagnosticsRedactor.redact(
            """auth.json: {"access_token":"dummy-access","refresh_token":"dummy-refresh","account_id":"dummy-account"}""",
        )

        assertFalse(redacted.contains("dummy-access"))
        assertFalse(redacted.contains("dummy-refresh"))
        assertFalse(redacted.contains("dummy-account"))
        assertTrue(redacted.contains("[REDACTED]"))
    }

    @Test
    fun `collapses multiline prefixed auth json payload`() {
        val redacted = DiagnosticsRedactor.redact(
            """
            auth.json: {
              "access_token":"dummy-access",
              "account_id":"dummy-account",
              "email":"dummy@example.test"
            }
            """.trimIndent(),
        )

        assertFalse(redacted.contains("dummy-access"))
        assertFalse(redacted.contains("dummy-account"))
        assertFalse(redacted.contains("dummy@example.test"))
        assertTrue(redacted.contains("[REDACTED]"))
    }

    @Test
    fun `collapses malformed labeled auth json payload`() {
        val redacted = DiagnosticsRedactor.redact(
            "auth.json: access_token=dummy-access refresh_token=dummy-refresh account=dummy-account",
        )

        assertFalse(redacted.contains("dummy-access"))
        assertFalse(redacted.contains("dummy-refresh"))
        assertFalse(redacted.contains("dummy-account"))
        assertTrue(redacted.contains("[REDACTED]"))
    }

    @Test
    fun `collapses raw provider json body`() {
        val redacted = DiagnosticsRedactor.redact(
            """{"rate_limit":{"used_percent":42},"plan":"dummy"}""",
        )

        assertEquals("[REDACTED]", redacted)
        assertFalse(redacted.contains("42"))
        assertFalse(redacted.contains("dummy"))
    }

    @Test
    fun `collapses unknown bare provider json body`() {
        val redacted = DiagnosticsRedactor.redact(
            """{"limits":{"percent":42},"plan":"dummy"}""",
        )

        assertFalse(redacted.contains("42"))
        assertFalse(redacted.contains("dummy"))
        assertTrue(redacted.contains("[REDACTED]"))
    }

    @Test
    fun `collapses malformed json like payload with oauth fragment`() {
        val redacted = DiagnosticsRedactor.redact("""{"provider":"codex",oauth=dummy}""")

        assertFalse(redacted.contains("oauth=dummy"))
        assertTrue(redacted.contains("[REDACTED]"))
    }

    @Test
    fun `collapses malformed json like payload with token fragment`() {
        val redacted = DiagnosticsRedactor.redact("""{"provider":"codex","status":"failed",token=dummy}""")

        assertFalse(redacted.contains("token=dummy"))
        assertTrue(redacted.contains("[REDACTED]"))
    }

    @Test
    fun `collapses truncated json like payload`() {
        val input = "{\"provider\":\"codex\",\"status\":\"failed\""
        val redacted = DiagnosticsRedactor.redact(input)

        assertFalse(redacted == input)
        assertTrue(redacted.contains("[REDACTED]"))
    }

    @Test
    fun `collapses prefixed provider response body`() {
        val redacted = DiagnosticsRedactor.redact(
            """response body: {"rate_limit":{"used_percent":42},"plan":"dummy"}""",
        )

        assertFalse(redacted.contains("42"))
        assertFalse(redacted.contains("dummy"))
        assertTrue(redacted.contains("[REDACTED]"))
    }

    @Test
    fun `collapses labeled response body with trailing payload`() {
        val redacted = DiagnosticsRedactor.redact("""response body: {"x":1} trailing-dummy""")

        assertFalse(redacted.contains("trailing-dummy"))
        assertTrue(redacted.contains("[REDACTED]"))
    }

    @Test
    fun `collapses labeled auth json with trailing payload`() {
        val redacted = DiagnosticsRedactor.redact("""auth.json: {"access_token":"dummy-access"} trailing-dummy""")

        assertFalse(redacted.contains("dummy-access"))
        assertFalse(redacted.contains("trailing-dummy"))
        assertTrue(redacted.contains("[REDACTED]"))
    }

    @Test
    fun `collapses multiline prefixed provider response body`() {
        val redacted = DiagnosticsRedactor.redact(
            """
            response body: {
              "rate_limit": { "used_percent": 42 },
              "plan": "dummy"
            }
            """.trimIndent(),
        )

        assertFalse(redacted.contains("42"))
        assertFalse(redacted.contains("dummy"))
        assertTrue(redacted.contains("[REDACTED]"))
    }

    @Test
    fun `collapses labeled non json response body`() {
        val redacted = DiagnosticsRedactor.redact("response body: dummy raw text with percent 42")

        assertFalse(redacted.contains("dummy"))
        assertFalse(redacted.contains("42"))
        assertTrue(redacted.contains("[REDACTED]"))
    }

    @Test
    fun `collapses multiline labeled non json response body`() {
        val redacted = DiagnosticsRedactor.redact(
            "response body: first dummy line\nsecond dummy line percent 42",
        )

        assertFalse(redacted.contains("first dummy"))
        assertFalse(redacted.contains("second dummy"))
        assertFalse(redacted.contains("42"))
        assertTrue(redacted.contains("[REDACTED]"))
    }

    @Test
    fun `collapses labeled non json raw response`() {
        val redacted = DiagnosticsRedactor.redact("raw response: dummy raw text")

        assertFalse(redacted.contains("dummy"))
        assertTrue(redacted.contains("[REDACTED]"))
    }

    @Test
    fun `collapses multiline labeled non json provider response`() {
        val redacted = DiagnosticsRedactor.redact(
            "provider response: first dummy line\nsecond dummy line",
        )

        assertFalse(redacted.contains("first dummy"))
        assertFalse(redacted.contains("second dummy"))
        assertTrue(redacted.contains("[REDACTED]"))
    }

    @Test
    fun `redacts cookie header`() {
        val redacted = DiagnosticsRedactor.redact("Cookie: session=dummy-cookie; theme=light")

        assertFalse(redacted.contains("dummy-cookie"))
        assertFalse(redacted.contains("theme=light"))
        assertTrue(redacted.contains("[REDACTED]"))
    }

    @Test
    fun `redacts auth code label`() {
        val redacted = DiagnosticsRedactor.redact("auth code: dummy-auth-code")

        assertFalse(redacted.contains("dummy-auth-code"))
        assertTrue(redacted.contains("[REDACTED]"))
    }

    @Test
    fun `redacts authorization code assignment`() {
        val redacted = DiagnosticsRedactor.redact("authorization code=dummy-auth-code")

        assertFalse(redacted.contains("dummy-auth-code"))
        assertTrue(redacted.contains("[REDACTED]"))
    }

    @Test
    fun `redacts full oauth callback query`() {
        val redacted = DiagnosticsRedactor.redact(
            oauthCallbackUrl(oauthQuery(CODE_KEY to DUMMY_CODE, STATE_KEY to DUMMY_STATE, SCOPE_KEY to OPENID_SCOPE)),
        )

        assertEquals("http://localhost:1455/auth/callback?[REDACTED]", redacted)
        assertFalse(redacted.contains("dummy-code"))
        assertFalse(redacted.contains("dummy-state"))
        assertFalse(redacted.contains("openid"))
    }

    @Test
    fun `redacts raw oauth callback query`() {
        val redacted = DiagnosticsRedactor.redact(
            oauthQuery(CODE_KEY to DUMMY_CODE, STATE_KEY to DUMMY_STATE, SCOPE_KEY to OPENID_SCOPE),
        )

        assertEquals("[REDACTED]", redacted)
        assertFalse(redacted.contains("dummy-code"))
        assertFalse(redacted.contains("dummy-state"))
        assertFalse(redacted.contains("openid"))
    }

    @Test
    fun `redacts raw state only callback query`() {
        val redacted = DiagnosticsRedactor.redact("state=dummy-state")

        assertFalse(redacted.contains("dummy-state"))
        assertTrue(redacted.contains("[REDACTED]"))
    }

    @Test
    fun `redacts raw state callback query with scope`() {
        val redacted = DiagnosticsRedactor.redact(oauthQuery(STATE_KEY to DUMMY_STATE, SCOPE_KEY to OPENID_SCOPE))

        assertEquals("[REDACTED]", redacted)
        assertFalse(redacted.contains("dummy-state"))
        assertFalse(redacted.contains("openid"))
    }

    @Test
    fun `redacts raw state callback query with prompt`() {
        val redacted = DiagnosticsRedactor.redact(oauthQuery(STATE_KEY to DUMMY_STATE, PROMPT_KEY to CONSENT_PROMPT))

        assertEquals("[REDACTED]", redacted)
        assertFalse(redacted.contains("dummy-state"))
        assertFalse(redacted.contains("consent"))
    }

    @Test
    fun `redacts raw prompt callback query with state`() {
        val redacted = DiagnosticsRedactor.redact(oauthQuery(PROMPT_KEY to CONSENT_PROMPT, STATE_KEY to DUMMY_STATE))

        assertEquals("[REDACTED]", redacted)
        assertFalse(redacted.contains("dummy-state"))
        assertFalse(redacted.contains("consent"))
    }

    @Test
    fun `redacts raw error callback query with state`() {
        val redacted = DiagnosticsRedactor.redact(oauthQuery(ERROR_KEY to ACCESS_DENIED_ERROR, STATE_KEY to DUMMY_STATE))

        assertEquals("[REDACTED]", redacted)
        assertFalse(redacted.contains("access_denied"))
        assertFalse(redacted.contains("dummy-state"))
    }

    @Test
    fun `redacts raw scope callback query with state`() {
        val redacted = DiagnosticsRedactor.redact(oauthQuery(SCOPE_KEY to OPENID_SCOPE, STATE_KEY to DUMMY_STATE))

        assertEquals("[REDACTED]", redacted)
        assertFalse(redacted.contains("openid"))
        assertFalse(redacted.contains("dummy-state"))
    }

    @Test
    fun `redacts callback query labeled state only fragment`() {
        val redacted = DiagnosticsRedactor.redact("callback query: state=dummy-state")

        assertFalse(redacted.contains("dummy-state"))
        assertTrue(redacted.contains("[REDACTED]"))
    }

    @Test
    fun `redacts callback query labeled state with scope`() {
        val redacted = DiagnosticsRedactor.redact(
            "callback query: ${oauthQuery(STATE_KEY to DUMMY_STATE, SCOPE_KEY to OPENID_SCOPE)}",
        )

        assertFalse(redacted.contains("dummy-state"))
        assertFalse(redacted.contains("openid"))
        assertTrue(redacted.contains("[REDACTED]"))
    }

    @Test
    fun `redacts callback query labeled error with state`() {
        val redacted = DiagnosticsRedactor.redact(
            "callback query: ${oauthQuery(ERROR_KEY to ACCESS_DENIED_ERROR, STATE_KEY to DUMMY_STATE)}",
        )

        assertFalse(redacted.contains("access_denied"))
        assertFalse(redacted.contains("dummy-state"))
        assertTrue(redacted.contains("[REDACTED]"))
    }

    @Test
    fun `redacts query assigned state only fragment`() {
        val redacted = DiagnosticsRedactor.redact("query=state=dummy-state")

        assertFalse(redacted.contains("dummy-state"))
        assertTrue(redacted.contains("[REDACTED]"))
    }

    @Test
    fun `redacts state only oauth callback url query`() {
        val redacted = DiagnosticsRedactor.redact(
            oauthCallbackUrl(oauthQuery(STATE_KEY to DUMMY_STATE, SCOPE_KEY to OPENID_SCOPE)),
        )

        assertEquals("http://localhost:1455/auth/callback?[REDACTED]", redacted)
        assertFalse(redacted.contains("dummy-state"))
        assertFalse(redacted.contains("openid"))
    }

    @Test
    fun `redacts error only oauth callback url query`() {
        val redacted = DiagnosticsRedactor.redact(
            oauthCallbackUrl(oauthQuery(ERROR_KEY to ACCESS_DENIED_ERROR)),
        )

        assertEquals("http://localhost:1455/auth/callback?[REDACTED]", redacted)
        assertFalse(redacted.contains("access_denied"))
    }

    @Test
    fun `redacts callback query labeled error only fragment`() {
        val redacted = DiagnosticsRedactor.redact("callback query: error=access_denied")

        assertEquals("callback query: [REDACTED]", redacted)
        assertFalse(redacted.contains("access_denied"))
    }

    @Test
    fun `redacts callback query labeled error description`() {
        val redacted = DiagnosticsRedactor.redact(
            "callback query: error=access_denied&error_description=dummy-denied",
        )

        assertFalse(redacted.contains("access_denied"))
        assertFalse(redacted.contains("dummy-denied"))
        assertTrue(redacted.contains("[REDACTED]"))
    }

    @Test
    fun `redacts oauth callback associated plain text state`() {
        val redacted = DiagnosticsRedactor.redact("oauth callback failed state=dummy-state")

        assertFalse(redacted.contains("dummy-state"))
        assertTrue(redacted.contains("[REDACTED]"))
    }

    @Test
    fun `redacts oauth state mismatch values`() {
        val redacted = DiagnosticsRedactor.redact("oauth state mismatch expected=dummy-state actual=other-state")

        assertFalse(redacted.contains("dummy-state"))
        assertFalse(redacted.contains("other-state"))
        assertTrue(redacted.contains("[REDACTED]"))
    }

    @Test
    fun `redacts callback state text`() {
        val redacted = DiagnosticsRedactor.redact("callback state=dummy-state")

        assertFalse(redacted.contains("dummy-state"))
        assertTrue(redacted.contains("[REDACTED]"))
    }

    @Test
    fun `leaves safe operational fields readable`() {
        val input = "provider=codex status=failed httpStatus=401"

        assertEquals(input, DiagnosticsRedactor.redact(input))
    }

    @Test
    fun `leaves safe operational json readable`() {
        val input = """{"provider":"codex","status":"failed","httpStatus":401}"""

        assertEquals(input, DiagnosticsRedactor.redact(input))
    }

    @Test
    fun `leaves rich safe diagnostics json readable`() {
        val input =
            """{"providerId":"codex","localAccountIdHash":"acct_hash","currentState":"authRequired","lastSuccessfulRefreshAt":"2026-05-22T00:00:00Z","lastRefreshStatus":"failed","appVersion":"0.1.0","buildType":"debug","diagnosticId":"diag-1","retryable":false,"userActionRequired":true}"""

        assertEquals(input, DiagnosticsRedactor.redact(input))
    }

    @Test
    fun `leaves widget status json readable`() {
        val input = """{"widgetStatus":"stale"}"""

        assertEquals(input, DiagnosticsRedactor.redact(input))
    }

    @Test
    fun `leaves safe operational state readable`() {
        val input = "provider=codex state=failed httpStatus=401"

        assertEquals(input, DiagnosticsRedactor.redact(input))
    }

    @Test
    fun `leaves standalone safe state readable`() {
        val input = "state=failed"

        assertEquals(input, DiagnosticsRedactor.redact(input))
    }

    @Test
    fun `leaves standalone auth required state readable`() {
        val input = "state=authRequired"

        assertEquals(input, DiagnosticsRedactor.redact(input))
    }

    @Test
    fun `leaves standalone stale state readable`() {
        val input = "state=stale"

        assertEquals(input, DiagnosticsRedactor.redact(input))
    }

    @Test
    fun `leaves ampersand delimited safe operational state readable`() {
        val input = "provider=codex&state=failed&httpStatus=401"

        assertEquals(input, DiagnosticsRedactor.redact(input))
    }

    private fun oauthCallbackUrl(query: String): String =
        "$CALLBACK_BASE?$query"

    private fun oauthQuery(vararg pairs: Pair<String, String>): String =
        pairs.joinToString("&") { (key, value) -> "$key=$value" }

    private companion object {
        const val CALLBACK_BASE = "http://localhost:1455/auth/callback"
        const val CODE_KEY = "code"
        const val STATE_KEY = "state"
        const val SCOPE_KEY = "scope"
        const val PROMPT_KEY = "prompt"
        const val ERROR_KEY = "error"
        const val DUMMY_CODE = "dummy-code"
        const val DUMMY_STATE = "dummy-state"
        const val OPENID_SCOPE = "openid"
        const val CONSENT_PROMPT = "consent"
        const val ACCESS_DENIED_ERROR = "access_denied"
    }
}
