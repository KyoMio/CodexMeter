package com.kmnexus.codexmeter.providers.codex.auth

@JvmInline
value class OAuthAuthorizationCode(val value: String) {
    override fun toString(): String = REDACTED_SECRET
}

@JvmInline
value class PkceVerifier(val value: String) {
    override fun toString(): String = REDACTED_SECRET
}

class CodexOAuthTokenExchangeRequest(
    val authorizationCode: OAuthAuthorizationCode,
    val pkceVerifier: PkceVerifier,
    val redirectUri: String = CodexOAuthConfig.DEVICE_REDIRECT_URI,
) {
    override fun toString(): String = "CodexOAuthTokenExchangeRequest([REDACTED])"
}

private const val REDACTED_SECRET = "[REDACTED]"
