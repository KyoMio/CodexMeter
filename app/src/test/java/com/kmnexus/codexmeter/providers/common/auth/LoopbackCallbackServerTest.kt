package com.kmnexus.codexmeter.providers.common.auth

import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LoopbackCallbackServerTest {
    @Test
    fun awaitCode_returnsCode_whenStateMatches() = runBlocking {
        val server = LoopbackCallbackServer(expectedState = "state123", ioDispatcher = Dispatchers.IO)
        server.start()
        val deferred = async(Dispatchers.IO) { server.awaitCode() }

        sendCallback(server.port, "GET /callback?code=auth-code-xyz&state=state123 HTTP/1.1")

        val result = deferred.await()
        assertTrue(result.isSuccess)
        assertEquals("auth-code-xyz", result.getOrNull())
    }

    @Test
    fun awaitCode_fails_whenStateMismatches() = runBlocking {
        val server = LoopbackCallbackServer(expectedState = "expected", ioDispatcher = Dispatchers.IO)
        server.start()
        val deferred = async(Dispatchers.IO) { server.awaitCode() }

        sendCallback(server.port, "GET /callback?code=abc&state=attacker HTTP/1.1")

        val result = deferred.await()
        assertTrue(result.isFailure)
    }

    @Test
    fun start_bindsLoopbackOnly() {
        val server = LoopbackCallbackServer(expectedState = "s")
        server.start()
        assertTrue("ephemeral port must be assigned", server.port > 0)
        assertTrue(server.redirectUri.startsWith("http://127.0.0.1:"))
        server.close()
    }

    private fun sendCallback(port: Int, requestLine: String) = runBlocking {
        // Give the server a moment to reach accept().
        delay(100)
        Socket("127.0.0.1", port).use { socket ->
            socket.getOutputStream().bufferedWriter().apply {
                write("$requestLine\r\n")
                write("Host: 127.0.0.1\r\n")
                write("\r\n")
                flush()
            }
            // Drain response so the server can finish writing.
            socket.getInputStream().bufferedReader().readText()
        }
    }
}
