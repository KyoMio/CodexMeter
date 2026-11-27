package com.kmnexus.codexmeter.debug

import com.kmnexus.codexmeter.domain.model.AccountStatus
import com.kmnexus.codexmeter.domain.quota.QuotaWindowAvailability
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the debug fixture scenario shapes from testDebugUnitTest. This file existing and
 * compiling at all is itself the proof that the debug source set is visible to testDebug —
 * the classes under test only exist in `app/src/debug/`.
 */
class FixtureScenariosTest {
    private val now: Instant = Instant.parse("2026-11-27T12:00:00Z")

    @Test
    fun `antigravity scenario carries the full two-by-two window id grid`() {
        val scenario = requireNotNull(FixtureScenarios.build(FixtureScenarios.ANTIGRAVITY_2X2_DEPLETED, now))

        val windowIds = scenario.accounts.single().snapshots.single().windows.map { it.windowId.value }
        assertEquals(
            listOf(
                "antigravity_gemini_5h_window",
                "antigravity_gemini_weekly_window",
                "antigravity_claude_gpt_5h_window",
                "antigravity_claude_gpt_weekly_window",
            ),
            windowIds,
        )
    }

    @Test
    fun `antigravity weekly windows encode the proto3 null-fraction depletion semantic`() {
        // Proto3 JSON omits a fully-depleted remainingFraction entirely (null in the wire
        // payload); the mapper folds that to 0.0 remaining, i.e. 100% used + Depleted.
        val scenario = requireNotNull(FixtureScenarios.build(FixtureScenarios.ANTIGRAVITY_2X2_DEPLETED, now))

        val weeklyWindows = scenario.accounts.single().snapshots.single().windows
            .filter { it.windowId.value.contains("weekly") }
        assertEquals(2, weeklyWindows.size)
        weeklyWindows.forEach { window ->
            assertEquals(100, window.usedPercent)
            assertEquals(QuotaWindowAvailability.Depleted, window.availability)
        }
        // The 5h windows stay healthy so the grid mixes states.
        val fiveHourWindows = scenario.accounts.single().snapshots.single().windows
            .filter { it.windowId.value.contains("_5h_") }
        fiveHourWindows.forEach { window ->
            assertEquals(QuotaWindowAvailability.Available, window.availability)
        }
        // Exactly one primary candidate, on the Gemini 5h window.
        assertEquals(
            listOf("antigravity_gemini_5h_window"),
            scenario.accounts.single().snapshots.single().windows
                .filter { it.isPrimaryCandidate }
                .map { it.windowId.value },
        )
    }

    @Test
    fun `codex cf blocked scenario only marks the account status`() {
        val scenario = requireNotNull(FixtureScenarios.build(FixtureScenarios.CODEX_CF_BLOCKED, now))

        assertEquals(AccountStatus.NeedsReauth, scenario.accounts.single().account.status)
        assertEquals("codex", scenario.accounts.single().account.providerId.value)
        // A stale last-known-good snapshot is still present for the UI to render behind the banner.
        assertTrue(scenario.accounts.single().snapshots.single().windows.isNotEmpty())
    }

    @Test
    fun `multi account race selects the second account`() {
        val scenario = requireNotNull(FixtureScenarios.build(FixtureScenarios.MULTI_ACCOUNT_RACE, now))

        assertEquals(2, scenario.accounts.size)
        assertEquals(1, scenario.selectedAccountIndex)
        assertEquals(
            scenario.accounts[1].account.localAccountId,
            scenario.selection.localAccountId,
        )
        assertEquals("antigravity", scenario.selection.providerId.value)
    }

    @Test
    fun `all scenarios stay sanitized and carry fake session envelopes`() {
        FixtureScenarios.ALL.forEach { name ->
            val scenario = requireNotNull(FixtureScenarios.build(name, now))
            scenario.accounts.forEach { fixtureAccount ->
                assertTrue(
                    "display name must carry the Fixture marker prefix",
                    fixtureAccount.account.displayName.startsWith(FixtureScenarios.DISPLAY_NAME_PREFIX),
                )
                assertEquals(
                    "fixture-token-not-real",
                    String(fixtureAccount.sessionEnvelope.payloadCiphertext, Charsets.UTF_8),
                )
                assertNotNull(fixtureAccount.sessionEnvelope.providerAccountId)
            }
        }
    }

    @Test
    fun `unknown scenario name yields null`() {
        assertEquals(null, FixtureScenarios.build("definitely_not_a_scenario", now))
    }
}
