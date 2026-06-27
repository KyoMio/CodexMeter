package com.kmnexus.codexmeter.providers.zaibalance.network

import com.kmnexus.codexmeter.core.network.ProviderHttpClient
import com.kmnexus.codexmeter.domain.refresh.QuotaError
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ZaiBalanceClientDecodeTest {
    private val server = MockWebServer()

    @Before fun setUp() = server.start()
    @After fun tearDown() = server.close()

    private fun client() = ZaiBalanceClient(ProviderHttpClient())
    private fun baseUrl() = server.url("/").toString()

    @Test
    fun parsesScientificNotationBalance() = runTest {
        val body = """{"code":200,"msg":"操作成功","success":true,"data":{"availableBalance":0E-9}}"""
        server.enqueue(MockResponse.Builder().code(200).body(body).build())

        val result = client().fetchBalance(apiKey = "id.secret", baseUrl = baseUrl())

        assertTrue(result is ZaiBalanceClient.Result.Success)
        assertEquals(0.0, (result as ZaiBalanceClient.Result.Success).dto.availableBalance!!, 0.0)
    }

    @Test
    fun parsesDecimalBalance() = runTest {
        val body = """{"code":200,"success":true,"data":{"availableBalance":12.34}}"""
        server.enqueue(MockResponse.Builder().code(200).body(body).build())

        val result = client().fetchBalance(apiKey = "id.secret", baseUrl = baseUrl())

        assertEquals(12.34, (result as ZaiBalanceClient.Result.Success).dto.availableBalance!!, 0.0001)
    }

    /** bigmodel returns HTTP 200 with code:1001 when the Authorization header is missing. */
    @Test
    fun code1001MapsToAuthRequired() = runTest {
        val body = """{"code":1001,"msg":"Header中未收到Authorization参数，无法进行身份验证。","success":false}"""
        server.enqueue(MockResponse.Builder().code(200).body(body).build())

        val result = client().fetchBalance(apiKey = "", baseUrl = baseUrl())

        assertTrue((result as ZaiBalanceClient.Result.Failure).error is QuotaError.AuthRequired)
    }

    /** code:1000 = invalid API key (verified: tampered key -> "身份验证失败") -> re-auth. */
    @Test
    fun code1000MapsToAuthRequired() = runTest {
        val body = """{"code":1000,"msg":"身份验证失败。","success":false}"""
        server.enqueue(MockResponse.Builder().code(200).body(body).build())

        val result = client().fetchBalance(apiKey = "wrong.key", baseUrl = baseUrl())

        assertTrue((result as ZaiBalanceClient.Result.Failure).error is QuotaError.AuthRequired)
    }

    /** code:401 = bad/expired token (verified: empty bearer -> "令牌已过期或验证不正确") -> re-auth. */
    @Test
    fun code401BodyMapsToAuthRequired() = runTest {
        val body = """{"code":401,"msg":"令牌已过期或验证不正确","success":false}"""
        server.enqueue(MockResponse.Builder().code(200).body(body).build())

        val result = client().fetchBalance(apiKey = "bad", baseUrl = baseUrl())

        assertTrue((result as ZaiBalanceClient.Result.Failure).error is QuotaError.AuthRequired)
    }

    @Test
    fun http401MapsToAuthRequired() = runTest {
        server.enqueue(MockResponse.Builder().code(401).body("").build())

        val result = client().fetchBalance(apiKey = "id.secret", baseUrl = baseUrl())

        assertTrue((result as ZaiBalanceClient.Result.Failure).error is QuotaError.AuthRequired)
    }

    /** Other business errors (e.g. code:500 系统异常) are transient -> Network, so last-good is kept. */
    @Test
    fun businessErrorMapsToNetwork() = runTest {
        val body = """{"code":500,"msg":"系统异常","success":false}"""
        server.enqueue(MockResponse.Builder().code(200).body(body).build())

        val result = client().fetchBalance(apiKey = "id.secret", baseUrl = baseUrl())

        assertTrue((result as ZaiBalanceClient.Result.Failure).error is QuotaError.Network)
    }

    /** Redaction: the API key must never appear in a diagnostics digest. */
    @Test
    fun digestNeverContainsApiKey() = runTest {
        server.enqueue(MockResponse.Builder().code(401).body("").build())
        val secret = "cb21d30c.super-secret-key"

        val result = client().fetchBalance(apiKey = secret, baseUrl = baseUrl())

        val digest = (result as ZaiBalanceClient.Result.Failure).error.diagnosticsDigest ?: ""
        assertFalse(digest.contains(secret))
    }
}
