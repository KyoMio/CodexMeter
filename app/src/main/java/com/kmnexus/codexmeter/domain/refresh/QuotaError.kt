package com.kmnexus.codexmeter.domain.refresh

sealed class QuotaError {
    abstract val safeMessageKey: String
    abstract val httpStatus: Int?
    abstract val diagnosticsDigest: String?
    abstract val retryable: Boolean
    abstract val userActionRequired: Boolean

    data class AuthRequired(
        override val httpStatus: Int?,
        override val diagnosticsDigest: String?,
    ) : QuotaError() {
        override val safeMessageKey: String = "error_auth_required"
        override val retryable: Boolean = false
        override val userActionRequired: Boolean = true
    }

    data class Network(
        override val diagnosticsDigest: String?,
    ) : QuotaError() {
        override val safeMessageKey: String = "error_network"
        override val httpStatus: Int? = null
        override val retryable: Boolean = true
        override val userActionRequired: Boolean = false
    }
}
