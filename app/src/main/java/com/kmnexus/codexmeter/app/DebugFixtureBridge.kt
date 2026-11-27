package com.kmnexus.codexmeter.app

import com.kmnexus.codexmeter.data.local.db.CodexMeterDatabase
import com.kmnexus.codexmeter.data.preferences.CurrentAccountStore
import com.kmnexus.codexmeter.data.secure.SecureSessionStore
import java.time.Clock

/**
 * Minimal debug-only injection seam.
 *
 * Exposes raw storage handles (database, session store, current-account selection) so that
 * debug-build fixture tooling under `app/src/debug/` can seed sanitized fake accounts and
 * quota snapshots through `adb shell am broadcast` without a real provider account. This
 * type deliberately carries NO scenario knowledge: it is a plain handle bundle, and every
 * fixture/scenario definition lives in the debug source set, never here. Release builds
 * contain only this inert wiring; nothing in main references it beyond the handoff.
 */
class DebugFixtureBridge internal constructor(
    val database: CodexMeterDatabase,
    val sessionStore: SecureSessionStore,
    val currentAccountStore: CurrentAccountStore,
    val clock: Clock,
)
