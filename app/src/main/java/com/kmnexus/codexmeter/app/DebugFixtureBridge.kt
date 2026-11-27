package com.kmnexus.codexmeter.app

import com.kmnexus.codexmeter.core.concurrency.VersionedStateGuard
import com.kmnexus.codexmeter.data.local.db.CodexMeterDatabase
import com.kmnexus.codexmeter.data.preferences.CurrentAccountStore
import com.kmnexus.codexmeter.data.secure.SecureSessionStore
import com.kmnexus.codexmeter.domain.account.DeletedAccountStateCleaner
import com.kmnexus.codexmeter.domain.settings.NotificationPreferenceStore
import java.time.Clock

/**
 * Minimal debug-only injection seam.
 *
 * Exposes raw storage handles (database, session store, current-account selection, shared state
 * guard, widget/notification cleanup hooks) so that debug-build fixture tooling under
 * `app/src/debug/` can seed sanitized fake accounts and quota snapshots without a real provider
 * account. This type deliberately carries NO scenario knowledge: it is a plain handle bundle,
 * and every fixture/scenario definition lives in the debug source set, never here.
 *
 * [AppContainer] constructs this bridge only when `BuildConfig.DEBUG` is true, so release builds
 * never instantiate it and R8 strips the unused wiring.
 */
class DebugFixtureBridge internal constructor(
    val database: CodexMeterDatabase,
    val sessionStore: SecureSessionStore,
    val currentAccountStore: CurrentAccountStore,
    val clock: Clock,
    val stateGuard: VersionedStateGuard,
    val deletedAccountStateCleaner: DeletedAccountStateCleaner? = null,
    val notificationPreferenceStore: NotificationPreferenceStore? = null,
    val republishCurrentState: (suspend () -> Unit)? = null,
)
