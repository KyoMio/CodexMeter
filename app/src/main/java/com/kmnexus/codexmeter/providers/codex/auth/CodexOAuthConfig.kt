package com.kmnexus.codexmeter.providers.codex.auth

internal object CodexOAuthConfig {
    const val ISSUER_URL = "https://auth.openai.com"
    const val TOKEN_ENDPOINT_URL = "$ISSUER_URL/oauth/token"
    const val DEVICE_USER_CODE_ENDPOINT_URL = "$ISSUER_URL/api/accounts/deviceauth/usercode"
    const val DEVICE_TOKEN_ENDPOINT_URL = "$ISSUER_URL/api/accounts/deviceauth/token"
    const val DEVICE_VERIFICATION_URL = "$ISSUER_URL/codex/device"
    const val DEVICE_REDIRECT_URI = "$ISSUER_URL/deviceauth/callback"
    const val SCOPE = "openid profile email offline_access"

    // Source: docs/ARCHITECTURE.md 11.2 and docs/SPEC.md 9.3 define this Codex OAuth client id.
    const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
}
