package com.kmnexus.codexmeter.ui.auth

import java.net.URI
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/** Syntax/expiry screening only; the provider API must authenticate before anything is saved. */
internal object BrowserSessionToken {
    fun isTrustedUrl(url: String?, host: String): Boolean = runCatching {
        val uri = URI(url ?: return false)
        uri.scheme == "https" && uri.host == host && uri.userInfo == null &&
            (uri.port == -1 || uri.port == 443)
    }.getOrDefault(false)

    fun fromJavascript(raw: String, nowSeconds: Long = System.currentTimeMillis() / 1000): String? =
        runCatching {
            var token = (Json.parseToJsonElement(raw) as? JsonPrimitive)
                ?.takeIf { it.isString }?.content?.trim() ?: return null
            // evaluateJavascript encodes its result; some sites also JSON-encode the stored string.
            if (token.startsWith('"')) token = Json.decodeFromString<String>(token).trim()
            if (!token.matches(Regex("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+"))) return null
            val payload = Base64.getUrlDecoder().decode(token.split('.')[1]).toString(Charsets.UTF_8)
            val claims = Json.parseToJsonElement(payload) as? JsonObject ?: return null
            val expiry = (claims["exp"] as? JsonPrimitive)?.doubleOrNull ?: return null
            token.takeIf { expiry.isFinite() && expiry > nowSeconds }
        }.getOrNull()
}
