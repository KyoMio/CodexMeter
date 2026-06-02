package com.kmnexus.codexmeter.domain.refresh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuotaErrorTest {
    @Test
    fun `unauthorized error requires user action`() {
        val error = QuotaError.AuthRequired(httpStatus = 401, diagnosticsDigest = "safe")

        assertTrue(error.userActionRequired)
        assertTrue(!error.retryable)
    }

    @Test
    fun `logically identical auth required errors compare equal`() {
        assertEquals(
            QuotaError.AuthRequired(httpStatus = 401, diagnosticsDigest = "safe"),
            QuotaError.AuthRequired(httpStatus = 401, diagnosticsDigest = "safe"),
        )
    }

    @Test
    fun `logically identical network errors compare equal`() {
        assertEquals(
            QuotaError.Network(diagnosticsDigest = "safe"),
            QuotaError.Network(diagnosticsDigest = "safe"),
        )
    }

    @Test
    fun `network error is retryable without user action`() {
        val error = QuotaError.Network(diagnosticsDigest = "safe")

        assertTrue(error.retryable)
        assertTrue(!error.userActionRequired)
    }

    @Test
    fun `network error has no http status`() {
        assertNull(QuotaError.Network(diagnosticsDigest = "safe").httpStatus)
    }

    @Test
    fun `auth required error uses safe message key`() {
        assertEquals(
            "error_auth_required",
            QuotaError.AuthRequired(httpStatus = 401, diagnosticsDigest = "safe").safeMessageKey,
        )
    }

    @Test
    fun `network error uses safe message key`() {
        assertEquals("error_network", QuotaError.Network(diagnosticsDigest = "safe").safeMessageKey)
    }

    @Test
    fun `auth required constructor values are preserved`() {
        val error = QuotaError.AuthRequired(httpStatus = 401, diagnosticsDigest = "safe")

        assertEquals(401, error.httpStatus)
        assertEquals("safe", error.diagnosticsDigest)
    }

    @Test
    fun `network constructor values are preserved`() {
        val error = QuotaError.Network(diagnosticsDigest = "safe")

        assertNull(error.httpStatus)
        assertEquals("safe", error.diagnosticsDigest)
    }
}
