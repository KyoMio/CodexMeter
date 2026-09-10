package com.kmnexus.codexmeter.refresh

import com.kmnexus.codexmeter.domain.refresh.QuotaError
import com.kmnexus.codexmeter.domain.refresh.RefreshAttempt
import com.kmnexus.codexmeter.domain.refresh.RefreshAttemptStatus

/** Auth statuses a provider also returns transiently, so a single occurrence proves nothing. */
private val RETRYABLE_AUTH_HTTP_STATUSES = setOf(401, 403)

/**
 * Whether an auth failure is trustworthy enough to lock the account into NeedsReauth.
 *
 * NeedsReauth removes the account from background refresh until the user pulls to refresh by hand,
 * so a provider hiccup or a token-rotation race that returns one 401 would otherwise read to them as
 * "the login keeps expiring". A 401/403 therefore has to repeat on consecutive attempts before it
 * counts. An auth error that no retry can fix — a missing or undecryptable session, a rejected
 * refresh token — carries no such status and still flags on the spot.
 *
 * [previousAttempt] is the account's last attempt before this failure; null (no history, or a store
 * that does not keep any) counts as "nothing failed before this one".
 */
internal fun needsReauthAfterAuthFailure(
    previousAttempt: RefreshAttempt?,
    error: QuotaError,
): Boolean {
    if (error.httpStatus !in RETRYABLE_AUTH_HTTP_STATUSES) return true
    val previous = previousAttempt ?: return false
    return previous.status == RefreshAttemptStatus.Failed && previous.userActionRequired == true
}
