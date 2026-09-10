package com.kmnexus.codexmeter.providers.grok.auth

internal object GrokOAuthConfig {
    const val ISSUER_URL = "https://auth.x.ai"
    const val DISCOVERY_URL = "$ISSUER_URL/.well-known/openid-configuration"

    // Legacy endpoint kept only for detecting persisted sessions that need re-discovery.
    const val LEGACY_TOKEN_ENDPOINT_URL = "$ISSUER_URL/oauth/token"

    // Source: docs/plans/2026-09-10-grok-provider-plan.md Confirmed API facts (grok CLI shared client).
    const val CLIENT_ID = "b1a00492-073a-47ea-816f-4c329264a828"
    const val SCOPE = "openid profile email offline_access grok-cli:access api:access"
    const val DEVICE_CODE_GRANT_TYPE = "urn:ietf:params:oauth:grant-type:device_code"
}
