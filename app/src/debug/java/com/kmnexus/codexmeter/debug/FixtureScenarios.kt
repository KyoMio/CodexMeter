package com.kmnexus.codexmeter.debug

import com.kmnexus.codexmeter.data.secure.ProviderSessionEnvelope
import com.kmnexus.codexmeter.domain.model.AccountStatus
import com.kmnexus.codexmeter.domain.model.LocalAccountId
import com.kmnexus.codexmeter.domain.model.ProviderAccount
import com.kmnexus.codexmeter.domain.model.ProviderAccountId
import com.kmnexus.codexmeter.domain.model.ProviderId
import com.kmnexus.codexmeter.domain.model.QuotaWindowId
import com.kmnexus.codexmeter.domain.model.SnapshotId
import com.kmnexus.codexmeter.domain.quota.QuotaSnapshot
import com.kmnexus.codexmeter.domain.quota.QuotaSnapshotSource
import com.kmnexus.codexmeter.domain.quota.QuotaWindow
import com.kmnexus.codexmeter.domain.quota.QuotaWindowAvailability
import java.time.Instant

/**
 * Pure builders for the debug fixture scenarios.
 *
 * Everything here is sanitized fake data: display names always use the "Fixture ..." style,
 * tokens are literal "fixture-token-not-real" placeholder bytes, and no real email or
 * credential shape is ever produced. The functions are pure (the clock instant is injected)
 * so the scenario shapes can be unit-tested under testDebugUnitTest.
 */

/** One fixture account row plus its fake session envelope plus its canned quota snapshots. */
data class FixtureAccountSeed(
    val account: ProviderAccount,
    val sessionEnvelope: ProviderSessionEnvelope,
    val snapshots: List<QuotaSnapshot>,
)

/** A named scenario: accounts to seed and the account the app should end up pointing at. */
data class FixtureScenario(
    val name: String,
    val accounts: List<FixtureAccountSeed>,
    val selectedAccountIndex: Int,
) {
    val selection: ProviderAccount
        get() = accounts[selectedAccountIndex].account
}

/** Blueprint for one percent-based quota window; keeps the window builder's arity low. */
data class FixtureWindowSpec(
    val windowId: String,
    val usedPercent: Int,
    val limitWindowSeconds: Int?,
    val resetInSeconds: Long,
    val isPrimary: Boolean,
)

object FixtureScenarios {
    const val ANTIGRAVITY_2X2_DEPLETED = "antigravity_2x2_depleted"
    const val CODEX_CF_BLOCKED = "codex_cf_blocked"
    const val KIMI_ACTIVE = "kimi_active"
    const val MULTI_ACCOUNT_RACE = "multi_account_race"

    /**
     * Identity prefix every fixture account carries in its immutable localAccountId.
     * Cleanup keys on this (not on the display name) so renamed fixtures are still
     * collected and ordinary accounts that happen to be named "Fixture ..." survive.
     */
    const val FIXTURE_ID_PREFIX = "fixture-"

    /** Display-name prefix for human readability only — ownership never keys on this. */
    const val DISPLAY_NAME_PREFIX = "Fixture"

    val ALL: List<String> = listOf(
        ANTIGRAVITY_2X2_DEPLETED,
        CODEX_CF_BLOCKED,
        KIMI_ACTIVE,
        MULTI_ACCOUNT_RACE,
    )

    fun build(name: String, now: Instant): FixtureScenario? = when (name) {
        ANTIGRAVITY_2X2_DEPLETED -> antigravity2x2Depleted(now)
        CODEX_CF_BLOCKED -> codexCfBlocked(now)
        KIMI_ACTIVE -> kimiActive(now)
        MULTI_ACCOUNT_RACE -> multiAccountRace(now)
        else -> null
    }

    // region scenarios

    /**
     * One antigravity account with the full 2x2 grid (Gemini 5h/weekly + Claude-GPT 5h/weekly).
     * Both weekly windows are exhausted: proto3 JSON omits a fully-depleted `remainingFraction`
     * entirely (null), which the mapper folds to 0.0 remaining — i.e. usedPercent = 100 with
     * availability = Depleted. The 5h windows stay healthy so the grid mixes states.
     */
    private fun antigravity2x2Depleted(now: Instant): FixtureScenario {
        val account = account(
            provider = ANTIGRAVITY,
            localAccountId = "fixture-antigravity-1",
            displayName = "Fixture Antigravity Depleted",
            now = now,
        )
        val windows = listOf(
            antigravityWindow(family = "gemini", lane = "5h", usedPercent = 35, resetInSeconds = RESET_4H, now = now),
            antigravityWindow(family = "gemini", lane = "weekly", usedPercent = DEPLETED_USED_PERCENT, resetInSeconds = RESET_3D, now = now),
            antigravityWindow(family = "claude_gpt", lane = "5h", usedPercent = 12, resetInSeconds = RESET_2H, now = now),
            antigravityWindow(family = "claude_gpt", lane = "weekly", usedPercent = DEPLETED_USED_PERCENT, resetInSeconds = RESET_5D, now = now),
        )
        val snapshot = snapshot(account, windows, now, planType = "fixture-pro")
        return FixtureScenario(
            name = ANTIGRAVITY_2X2_DEPLETED,
            accounts = listOf(seed(account, now, listOf(snapshot))),
            selectedAccountIndex = 0,
        )
    }

    /**
     * One codex account in NeedsReauth, simulating the post-Cloudflare-block state. Only the
     * account status row is set; no login-flow code path is touched. A stale snapshot is kept
     * so the UI renders last-known-good data behind the reauth banner.
     */
    private fun codexCfBlocked(now: Instant): FixtureScenario {
        val account = account(
            provider = CODEX,
            localAccountId = "fixture-codex-1",
            displayName = "Fixture Codex CF Blocked",
            now = now,
            status = AccountStatus.NeedsReauth,
        )
        val snapshotAt = now.minusSeconds(STALE_SNAPSHOT_AGE_SECONDS)
        val windows = listOf(
            percentWindow(FixtureWindowSpec("five_hour", 42, FIVE_HOUR_SECONDS, RESET_4H, isPrimary = true), snapshotAt),
            percentWindow(FixtureWindowSpec("weekly", 18, WEEK_SECONDS, RESET_3D, isPrimary = false), snapshotAt),
        )
        val snapshot = snapshot(account, windows, snapshotAt, planType = "plus")
        return FixtureScenario(
            name = CODEX_CF_BLOCKED,
            accounts = listOf(seed(account, now, listOf(snapshot))),
            selectedAccountIndex = 0,
        )
    }

    /** One kimi_code account, Active, with healthy 5h/weekly windows. */
    private fun kimiActive(now: Instant): FixtureScenario {
        val account = account(
            provider = KIMI_CODE,
            localAccountId = "fixture-kimicode-1",
            displayName = "Fixture Kimi Active",
            now = now,
        )
        val windows = listOf(
            percentWindow(FixtureWindowSpec("kimi_code_5h", 23, FIVE_HOUR_SECONDS, RESET_4H, isPrimary = true), now),
            percentWindow(FixtureWindowSpec("kimi_code_7d", 41, WEEK_SECONDS, RESET_4D, isPrimary = false), now),
        )
        val snapshot = snapshot(account, windows, now, planType = null)
        return FixtureScenario(
            name = KIMI_ACTIVE,
            accounts = listOf(seed(account, now, listOf(snapshot))),
            selectedAccountIndex = 0,
        )
    }

    /** Two accounts (codex first, antigravity second); the selection points at the second one. */
    private fun multiAccountRace(now: Instant): FixtureScenario {
        val codex = account(
            provider = CODEX,
            localAccountId = "fixture-codex-1",
            displayName = "Fixture Codex Main",
            now = now,
        )
        val codexSnapshot = snapshot(
            codex,
            listOf(
                percentWindow(FixtureWindowSpec("five_hour", 55, FIVE_HOUR_SECONDS, RESET_4H, isPrimary = true), now),
                percentWindow(FixtureWindowSpec("weekly", 30, WEEK_SECONDS, RESET_2D, isPrimary = false), now),
            ),
            now,
            planType = "plus",
        )
        val antigravity = account(
            provider = ANTIGRAVITY,
            localAccountId = "fixture-antigravity-1",
            displayName = "Fixture Antigravity Second",
            now = now.plusSeconds(1),
        )
        val antigravitySnapshot = snapshot(
            antigravity,
            listOf(
                antigravityWindow(family = "gemini", lane = "5h", usedPercent = 61, resetInSeconds = RESET_3H, now = now),
                antigravityWindow(family = "gemini", lane = "weekly", usedPercent = 27, resetInSeconds = RESET_2D, now = now),
                antigravityWindow(family = "claude_gpt", lane = "5h", usedPercent = 8, resetInSeconds = RESET_2H, now = now),
                antigravityWindow(family = "claude_gpt", lane = "weekly", usedPercent = 44, resetInSeconds = RESET_4D, now = now),
            ),
            now,
            planType = "fixture-pro",
        )
        return FixtureScenario(
            name = MULTI_ACCOUNT_RACE,
            accounts = listOf(
                seed(codex, now, listOf(codexSnapshot)),
                seed(antigravity, now, listOf(antigravitySnapshot)),
            ),
            selectedAccountIndex = 1,
        )
    }

    // endregion

    // region builders

    private fun account(
        provider: ProviderId,
        localAccountId: String,
        displayName: String,
        now: Instant,
        status: AccountStatus = AccountStatus.Active,
    ): ProviderAccount {
        require(displayName.startsWith(DISPLAY_NAME_PREFIX)) {
            "Fixture display names must stay sanitized: $displayName"
        }
        // Ownership invariant: every fixture account MUST carry the id prefix cleanup keys on.
        // A fixture built without it would turn immortal — clean could never collect it.
        require(localAccountId.startsWith(FIXTURE_ID_PREFIX)) {
            "Fixture localAccountId must start with '$FIXTURE_ID_PREFIX' so cleanup owns it: $localAccountId"
        }
        return ProviderAccount(
            localAccountId = LocalAccountId(localAccountId),
            providerId = provider,
            providerAccountId = ProviderAccountId("fixture-pa-$localAccountId"),
            displayName = displayName,
            avatarInitial = displayName.first().uppercase(),
            avatarColorKey = localAccountId,
            status = status,
            createdAt = now,
            updatedAt = now,
            lastSuccessfulRefreshAt = now,
        )
    }

    private fun seed(
        account: ProviderAccount,
        now: Instant,
        snapshots: List<QuotaSnapshot>,
    ): FixtureAccountSeed = FixtureAccountSeed(
        account = account,
        sessionEnvelope = envelopeFor(account, now),
        snapshots = snapshots,
    )

    /**
     * A structurally valid envelope whose ciphertext bytes are the literal placeholder token.
     * The bytes are never decryptable; that is fine because fixture accounts are never meant
     * to hit the network — refresh attempts fail closed, which is correct debug behavior.
     */
    private fun envelopeFor(account: ProviderAccount, now: Instant): ProviderSessionEnvelope =
        ProviderSessionEnvelope(
            providerId = account.providerId.value,
            localAccountId = account.localAccountId.value,
            providerAccountId = account.providerAccountId?.value,
            schemaVersion = FIXTURE_SESSION_SCHEMA_VERSION,
            payloadCiphertext = FAKE_TOKEN_BYTES.copyOf(),
            payloadNonce = byteArrayOf(0),
            createdAt = now.toString(),
            updatedAt = now.toString(),
        )

    private fun antigravityWindow(
        family: String,
        lane: String,
        usedPercent: Int,
        resetInSeconds: Long,
        now: Instant,
    ): QuotaWindow {
        val windowId = "antigravity_${family}_${lane}_window"
        val laneSeconds = when (lane) {
            "5h" -> FIVE_HOUR_SECONDS
            "weekly" -> WEEK_SECONDS
            else -> null
        }
        return percentWindow(
            FixtureWindowSpec(
                windowId = windowId,
                usedPercent = usedPercent,
                limitWindowSeconds = laneSeconds,
                resetInSeconds = resetInSeconds,
                isPrimary = family == "gemini" && lane == "5h",
            ),
            now,
        )
    }

    private fun percentWindow(spec: FixtureWindowSpec, now: Instant): QuotaWindow = QuotaWindow(
        windowId = QuotaWindowId(spec.windowId),
        titleKey = spec.windowId,
        usedPercent = spec.usedPercent,
        resetAt = now.plusSeconds(spec.resetInSeconds),
        limitWindowSeconds = spec.limitWindowSeconds,
        isPrimaryCandidate = spec.isPrimary,
        availability = availabilityOf(spec.usedPercent),
    )

    private fun snapshot(
        account: ProviderAccount,
        windows: List<QuotaWindow>,
        fetchedAt: Instant,
        planType: String?,
    ): QuotaSnapshot = QuotaSnapshot(
        snapshotId = SnapshotId(
            "fixture:${account.providerId.value}:${account.localAccountId.value}:${fetchedAt.toEpochMilli()}",
        ),
        providerId = account.providerId,
        localAccountId = account.localAccountId,
        providerAccountId = account.providerAccountId,
        fetchedAt = fetchedAt,
        source = QuotaSnapshotSource.BackgroundRefresh,
        planType = planType,
        windows = windows,
        credits = null,
        responseDigest = null,
    )

    private fun availabilityOf(usedPercent: Int): QuotaWindowAvailability =
        if (usedPercent >= DEPLETED_USED_PERCENT) {
            QuotaWindowAvailability.Depleted
        } else {
            QuotaWindowAvailability.Available
        }

    // endregion

    private val CODEX = ProviderId("codex")
    private val ANTIGRAVITY = ProviderId("antigravity")
    private val KIMI_CODE = ProviderId("kimi_code")

    private val FAKE_TOKEN_BYTES = "fixture-token-not-real".toByteArray(Charsets.UTF_8)

    private const val FIXTURE_SESSION_SCHEMA_VERSION = 1
    private const val DEPLETED_USED_PERCENT = 100
    private const val FIVE_HOUR_SECONDS = 5 * 3600
    private const val WEEK_SECONDS = 7 * 86400
    private const val STALE_SNAPSHOT_AGE_SECONDS = 6 * 3600L

    private const val SECONDS_PER_HOUR = 3600L
    private const val SECONDS_PER_DAY = 86_400L
    private const val RESET_2H = 2 * SECONDS_PER_HOUR
    private const val RESET_3H = 3 * SECONDS_PER_HOUR
    private const val RESET_4H = 4 * SECONDS_PER_HOUR
    private const val RESET_2D = 2 * SECONDS_PER_DAY
    private const val RESET_3D = 3 * SECONDS_PER_DAY
    private const val RESET_4D = 4 * SECONDS_PER_DAY
    private const val RESET_5D = 5 * SECONDS_PER_DAY
}
