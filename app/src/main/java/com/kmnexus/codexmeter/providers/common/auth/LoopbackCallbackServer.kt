package com.kmnexus.codexmeter.providers.common.auth

import java.net.InetAddress
import java.net.ServerSocket
import java.net.URLDecoder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Single-use loopback HTTP server for OAuth redirects that cannot be intercepted inside a WebView
 * (Google blocks OAuth in embedded WebViews, so Antigravity must use an external browser that
 * redirects to `http://127.0.0.1:{port}/callback`).
 *
 * Security: binds to `127.0.0.1` only (never `0.0.0.0`), uses an ephemeral port, validates the
 * OAuth `state`, serves exactly one request and then closes. Lifecycle is owned by the OAuth screen.
 */
class LoopbackCallbackServer(
    private val expectedState: String,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private var serverSocket: ServerSocket? = null

    val port: Int
        get() = serverSocket?.localPort ?: error("LoopbackCallbackServer not started")

    val redirectUri: String
        get() = "http://127.0.0.1:$port/callback"

    fun start() {
        if (serverSocket == null) {
            serverSocket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        }
    }

    /**
     * Suspends until the single OAuth callback arrives, returning the authorization code on success.
     * Always closes the server before returning.
     */
    suspend fun awaitCode(): Result<String> = withContext(ioDispatcher) {
        val socket = serverSocket
            ?: return@withContext Result.failure(IllegalStateException("Loopback server not started"))
        try {
            socket.accept().use { client ->
                val requestLine = client.getInputStream().bufferedReader().readLine().orEmpty()
                val params = parseQuery(requestLine)
                val state = params["state"]
                val code = params["code"]
                val ok = state == expectedState && !code.isNullOrBlank()
                writeResponse(client, ok)
                when {
                    state != expectedState ->
                        Result.failure(IllegalStateException("OAuth state mismatch"))
                    code.isNullOrBlank() ->
                        Result.failure(IllegalStateException("OAuth callback missing code"))
                    else -> Result.success(code)
                }
            }
        } catch (exception: Exception) {
            Result.failure(exception)
        } finally {
            close()
        }
    }

    fun close() {
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    private fun writeResponse(client: java.net.Socket, success: Boolean) {
        val body = if (success) {
            "<html><body><h3>Login complete</h3><p>Return to CodexMeter.</p></body></html>"
        } else {
            "<html><body><h3>Login failed</h3><p>Please try again in CodexMeter.</p></body></html>"
        }
        runCatching {
            client.getOutputStream().bufferedWriter().apply {
                write("HTTP/1.1 200 OK\r\n")
                write("Content-Type: text/html; charset=utf-8\r\n")
                write("Connection: close\r\n")
                write("Content-Length: ${body.toByteArray().size}\r\n")
                write("\r\n")
                write(body)
                flush()
            }
        }
    }

    private companion object {
        /** Parses `GET /callback?code=...&state=... HTTP/1.1` into decoded query params. */
        fun parseQuery(requestLine: String): Map<String, String> {
            val path = requestLine.split(" ").getOrNull(1) ?: return emptyMap()
            val query = path.substringAfter('?', "")
            if (query.isEmpty()) return emptyMap()
            return query.split("&").mapNotNull { pair ->
                val parts = pair.split("=", limit = 2)
                val key = parts.getOrNull(0)?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val value = parts.getOrNull(1).orEmpty()
                key to runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
            }.toMap()
        }
    }
}
