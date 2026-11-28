package com.kmnexus.codexmeter.app

import android.content.Context
import androidx.room.Room
import com.kmnexus.codexmeter.core.concurrency.VersionedStateGuard
import com.kmnexus.codexmeter.data.local.db.CodexMeterDatabase
import com.kmnexus.codexmeter.data.preferences.CurrentAccountPreferences
import com.kmnexus.codexmeter.data.preferences.CurrentAccountSelection
import com.kmnexus.codexmeter.data.repository.AccountDeletionRepository
import com.kmnexus.codexmeter.data.repository.RoomCodexSessionImportPersistence
import com.kmnexus.codexmeter.data.secure.FileSecureSessionStore
import com.kmnexus.codexmeter.data.secure.ProviderSessionEnvelope
import com.kmnexus.codexmeter.domain.account.NoopDeletedAccountStateCleaner
import com.kmnexus.codexmeter.domain.auth.DeviceCodeLoginResult
import com.kmnexus.codexmeter.domain.model.LocalAccountId
import com.kmnexus.codexmeter.domain.model.ProviderId
import com.kmnexus.codexmeter.notification.NotificationTargetBoundary
import com.kmnexus.codexmeter.providers.grok.auth.GrokDeviceCodeLoginAttemptId
import com.kmnexus.codexmeter.providers.grok.auth.GrokDeviceCodeLoginController
import com.kmnexus.codexmeter.providers.grok.auth.GrokLoginIdentity
import com.kmnexus.codexmeter.providers.grok.auth.GrokOAuthToken
import com.kmnexus.codexmeter.providers.grok.auth.GrokSessionImporter
import com.kmnexus.codexmeter.providers.grok.dto.GrokBillingPeriodDto
import com.kmnexus.codexmeter.providers.grok.dto.GrokBillingResponseDto
import com.kmnexus.codexmeter.providers.grok.network.GrokBillingClient
import com.kmnexus.codexmeter.providers.grok.network.GrokDeviceCodeChallenge
import com.kmnexus.codexmeter.providers.grok.network.GrokDeviceCodeClient
import com.kmnexus.codexmeter.providers.grok.network.GrokOAuthDiscoveryClient
import com.kmnexus.codexmeter.providers.grok.network.GrokOAuthEndpoints
import java.io.File
import java.time.Clock
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Cm10R5GrokIdentityTest {
    @Test fun readded() = exercise(true)
    @Test fun deletedOnly() = exercise(false)

    private fun exercise(readd: Boolean) = runBlocking {
        withTimeout(20_000) {
            val env = GrokIdentityEnv().apply { seed() }
            try {
                val importer = buildImporter(env.store, env.waitBilling, env.resumeFirst)
                val login = buildLoginController(importer)
                login.startRelogin(env.providerAccountId, "A")
                val old = async(Dispatchers.IO) { login.pollLatest() }
                env.waitBilling.await()
                env.deleteAccountA()
                var replacement: String? = null
                if (readd) {
                    login.startLogin()
                    val added = login.pollLatest() as DeviceCodeLoginResult.Saved
                    replacement = added.account.localAccountId.value
                    val marker = env.vault.load("grok", replacement)?.payloadCiphertext?.first()?.toInt()
                    check(replacement != "A" && marker == 2)
                }
                env.resumeFirst.complete(Unit)
                old.await()
                assertIdentityOutcome(env, readd, replacement)
            } finally {
                env.resumeFirst.complete(Unit)
                env.db.close()
            }
        }
        Unit
    }

    private suspend fun assertIdentityOutcome(
        env: GrokIdentityEnv,
        readd: Boolean,
        replacement: String?,
    ) {
        val actual = env.dao.getByProviderAccountId("grok", env.providerAccountId)?.localAccountId
        val marker = actual?.let { env.vault.load("grok", it)?.payloadCiphertext?.first()?.toInt() }
        val beforeMarker = if (readd) 2 else null
        println(
            "sameController readd=$readd replacement=$replacement rowAfter=$actual " +
                "beforeMarker=$beforeMarker afterMarker=$marker",
        )
        check(actual == replacement && (if (readd) marker == 2 else marker == null)) {
            "Old re-login overwrote replacement's credential"
        }
    }

    private class GrokIdentityEnv {
        private val ctx: Context = RuntimeEnvironment.getApplication()
        private val dir = File(ctx.filesDir, UUID.randomUUID().toString())
        val db = Room.inMemoryDatabaseBuilder(ctx, CodexMeterDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        private val prefs = CurrentAccountPreferences.create(File(dir, "prefs.preferences_pb"))
        val vault = FileSecureSessionStore(File(dir, "sessions"))
        private val boundary = NotificationTargetBoundary()
        private val guard = VersionedStateGuard()
        val dao = db.providerAccountDao()
        private val p = ProviderId("grok")
        val waitBilling = CompletableDeferred<Unit>()
        val resumeFirst = CompletableDeferred<Unit>()
        val providerAccountId = "acct-A"
        val store = RoomCodexSessionImportPersistence(
            db,
            vault,
            prefs,
            guard,
            notificationTargetBoundary = boundary,
        )

        suspend fun seed() {
            val original = FixtureAccounts.account(localAccountId = "A", displayName = "Original")
                .copy(providerId = "grok", providerAccountId = providerAccountId)
            val other = FixtureAccounts.account(localAccountId = "B", displayName = "Other")
                .copy(providerId = "grok")
            dao.upsert(original)
            dao.upsert(other)
            prefs.updateCurrentAccountSelection(CurrentAccountSelection(p, LocalAccountId("A")))
        }

        suspend fun deleteAccountA() {
            AccountDeletionRepository(
                db,
                vault,
                prefs,
                NoopDeletedAccountStateCleaner,
                stateGuard = guard,
                notificationTargetBoundary = boundary,
            ).deleteAccount(p, LocalAccountId("A"))
            check(dao.getByProviderAccountId("grok", providerAccountId) == null)
        }
    }

    private fun buildImporter(
        store: RoomCodexSessionImportPersistence,
        waitBilling: CompletableDeferred<Unit>,
        resumeFirst: CompletableDeferred<Unit>,
    ) = GrokSessionImporter(
        billingClient = { token ->
            if (token == "old-test") {
                waitBilling.complete(Unit)
                resumeFirst.await()
            }
            GrokBillingClient.Result.Success(
                GrokBillingResponseDto(
                    currentPeriod = GrokBillingPeriodDto(
                        type = "USAGE_PERIOD_TYPE_WEEKLY",
                        start = "2026-09-03T17:33:48Z",
                        end = "2026-09-10T17:33:48Z",
                    ),
                    creditUsagePercent = 23.0,
                    prepaidBalance = 12.5,
                ),
            )
        },
        importPersistence = { account, envelope, snapshot, allow, expectedLocal ->
            val c = store.save(account, envelope, snapshot, allow, expectedLocal)
            GrokSessionImporter.CommittedImport(c.account, c.sessionEnvelope, c.snapshot)
        },
        sessionEnvelopeFactory = { payload, local, provider, now ->
            val marker = if (payload.accessToken == "old-test") 1 else 2
            ProviderSessionEnvelope(
                "grok",
                local.value,
                provider?.value,
                1,
                byteArrayOf(marker.toByte()),
                byteArrayOf(0),
                now.toString(),
                now.toString(),
            )
        },
        localAccountIdProvider = { LocalAccountId("grok-" + UUID.randomUUID()) },
        defaultDisplayName = "Audit",
        clock = Clock.systemUTC(),
    )

    private fun buildLoginController(importer: GrokSessionImporter): GrokDeviceCodeLoginController {
        var polls = 0
        val client = object : GrokDeviceCodeLoginController.DeviceCodeClient {
            override suspend fun requestDeviceCode(deviceAuthorizationEndpointUrl: String) =
                GrokDeviceCodeClient.Result.Success(
                    GrokDeviceCodeChallenge(
                        deviceCode = "test",
                        userCode = "example",
                        verificationUri = "https://example.invalid",
                        verificationUriComplete = null,
                        intervalSeconds = 1,
                        expiresInSeconds = 900,
                    ),
                )

            override suspend fun awaitAuthorization(
                challenge: GrokDeviceCodeChallenge,
                tokenEndpointUrl: String,
            ): GrokDeviceCodeClient.Result<GrokOAuthToken> {
                polls++
                val sub = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString("""{"sub":"acct-A"}""".toByteArray())
                return GrokDeviceCodeClient.Result.Success(
                    GrokOAuthToken(
                        accessToken = if (polls == 1) "old-test" else "new-test",
                        refreshToken = "synthetic-test",
                        idToken = "test.$sub.test",
                        expiresAtEpochSeconds = 2_000_000_000L,
                    ),
                )
            }
        }
        return GrokDeviceCodeLoginController(
            discoveryClient = {
                GrokOAuthDiscoveryClient.Result.Success(
                    GrokOAuthEndpoints(
                        deviceAuthorizationEndpoint = "https://example.invalid/device",
                        tokenEndpoint = "https://example.invalid/token",
                    ),
                )
            },
            deviceCodeClient = client,
            sessionImporter = object : GrokDeviceCodeLoginController.SessionImporter {
                override suspend fun prepareDeviceCodeSession(
                    oauthToken: GrokOAuthToken,
                    identity: GrokLoginIdentity,
                    tokenEndpoint: String?,
                ) = importer.prepareDeviceCodeSession(oauthToken, identity, tokenEndpoint)

                override suspend fun commitPreparedDeviceCodeSession(
                    preparedImport: GrokSessionImporter.PreparedImport,
                    allowNewRow: Boolean,
                ) = importer.commitPreparedDeviceCodeSession(preparedImport, allowNewRow)
            },
            attemptIdProvider = { GrokDeviceCodeLoginAttemptId(UUID.randomUUID().toString()) },
            clock = Clock.systemUTC(),
        )
    }
}
