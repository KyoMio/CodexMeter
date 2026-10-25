package com.kmnexus.codexmeter.ui.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OAuthCallbackDecisionTest {
    private val prefix = "https://console.anthropic.com/oauth/code/callback"

    @Test
    fun nonCallbackUrlsPassThrough() {
        assertEquals(
            OAuthCallbackDecision.NotCallback,
            evaluateOAuthCallback("https://claude.ai/oauth/authorize?code=true", prefix, "st1", true),
        )
        assertEquals(
            OAuthCallbackDecision.NotCallback,
            evaluateOAuthCallback("https://evil.test/callback?code=c1&state=st1", prefix, "st1", false),
        )
    }

    @Test
    fun matchingCodeAndStateCaptures() {
        assertEquals(
            OAuthCallbackDecision.Capture("c1#st1"),
            evaluateOAuthCallback("$prefix?code=c1&state=st1", prefix, "st1", appendStateToCode = true),
        )
        assertEquals(
            OAuthCallbackDecision.Capture("c1"),
            evaluateOAuthCallback("$prefix?code=c1&state=st1", prefix, "st1", appendStateToCode = false),
        )
    }

    @Test
    fun errorParameterDeniesRegardlessOfCode() {
        assertEquals(
            OAuthCallbackDecision.Denied("access_denied"),
            evaluateOAuthCallback("$prefix?error=access_denied", prefix, "st1", false),
        )
        assertEquals(
            OAuthCallbackDecision.Denied("access_denied"),
            evaluateOAuthCallback("$prefix?code=c1&error=access_denied", prefix, "st1", false),
        )
        // A denial wins even over a matching code+state, and an empty error value has no code.
        assertEquals(
            OAuthCallbackDecision.Denied(null),
            evaluateOAuthCallback("$prefix?code=c1&state=st1&error=", prefix, "st1", false),
        )
    }

    @Test
    fun codeWithWrongOrMissingStateIsMismatch() {
        assertEquals(
            OAuthCallbackDecision.StateMismatch,
            evaluateOAuthCallback("$prefix?code=c1&state=other", prefix, "st1", false),
        )
        assertEquals(
            OAuthCallbackDecision.StateMismatch,
            evaluateOAuthCallback("$prefix?code=c1", prefix, "st1", false),
        )
    }

    @Test
    fun callbackWithoutCodeOrErrorIsMismatch() {
        assertEquals(OAuthCallbackDecision.StateMismatch, evaluateOAuthCallback(prefix, prefix, "st1", false))
        assertEquals(
            OAuthCallbackDecision.StateMismatch,
            evaluateOAuthCallback("$prefix?foo=bar", prefix, "st1", false),
        )
    }

    @Test
    fun decodesPercentEncodedStateBeforeComparing() {
        // %41%42%43 == "ABC": both the comparison and the credential use the decoded values.
        assertEquals(
            OAuthCallbackDecision.Capture("c1#ABC"),
            evaluateOAuthCallback("$prefix?code=c1&state=%41%42%43", prefix, "ABC", appendStateToCode = true),
        )
        // The raw encoded text must not satisfy an expectedState that is itself the encoded form.
        assertEquals(
            OAuthCallbackDecision.StateMismatch,
            evaluateOAuthCallback("$prefix?code=c1&state=%41%42%43", prefix, "%41%42%43", appendStateToCode = true),
        )
    }
}

class ParseManualAuthCodeTest {
    private val state = "expected-state"

    @Test
    fun acceptsCodeWithMatchingState() {
        assertEquals("abc#expected-state", parseManualAuthCode("abc#expected-state", state))
    }

    @Test
    fun stripsSurroundingWhitespaceAndZeroWidthChars() {
        assertEquals(
            "abc#expected-state",
            parseManualAuthCode(" \u200Babc#expected-state\u200C\n\uFEFF", state),
        )
    }

    @Test
    fun rejectsMissingSeparator() {
        assertNull(parseManualAuthCode("abcexpected-state", state))
    }

    @Test
    fun rejectsWrongStateSuffix() {
        assertNull(parseManualAuthCode("abc#other-state", state))
    }

    @Test
    fun rejectsMultipleSeparators() {
        assertNull(parseManualAuthCode("a#b#expected-state", state))
    }

    @Test
    fun rejectsJunkAfterMatchingStateSegment() {
        // The middle segment equals the expected state exactly; the trailing `#junk` must still
        // fail the "exactly one #" rule (not just the state-equality rule).
        assertNull(parseManualAuthCode("code#expected-state#junk", state))
    }

    @Test
    fun rejectsEmptyOrCodelessInput() {
        assertNull(parseManualAuthCode("", state))
        assertNull(parseManualAuthCode("   ", state))
        assertNull(parseManualAuthCode("#expected-state", state))
    }
}
