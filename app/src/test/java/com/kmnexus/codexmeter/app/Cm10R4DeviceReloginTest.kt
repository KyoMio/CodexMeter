package com.kmnexus.codexmeter.app

import android.content.Context
import androidx.room.Room
import com.kmnexus.codexmeter.core.concurrency.VersionedStateGuard
import com.kmnexus.codexmeter.data.local.db.CodexMeterDatabase
import com.kmnexus.codexmeter.data.preferences.CurrentAccountPreferences
import com.kmnexus.codexmeter.data.preferences.CurrentAccountSelection
import com.kmnexus.codexmeter.data.repository.AccountDeletionRepository
import com.kmnexus.codexmeter.data.repository.RoomCodexSessionImportPersistence
import com.kmnexus.codexmeter.data.repository.toDomain
import com.kmnexus.codexmeter.data.secure.FileSecureSessionStore
import com.kmnexus.codexmeter.data.secure.ProviderSessionEnvelope
import com.kmnexus.codexmeter.domain.account.NoopDeletedAccountStateCleaner
import com.kmnexus.codexmeter.domain.model.LocalAccountId
import com.kmnexus.codexmeter.domain.model.ProviderAccountId
import com.kmnexus.codexmeter.domain.model.ProviderId
import com.kmnexus.codexmeter.notification.NotificationTargetBoundary
import com.kmnexus.codexmeter.providers.codex.auth.CodexDeviceCodeClient
import com.kmnexus.codexmeter.providers.codex.auth.CodexDeviceCodeLoginMode
import com.kmnexus.codexmeter.providers.codex.auth.CodexDeviceCodeLoginState
import com.kmnexus.codexmeter.providers.codex.auth.CodexDeviceCodeLoginUseCase
import com.kmnexus.codexmeter.providers.codex.auth.CodexOAuthTokenExchanger
import com.kmnexus.codexmeter.providers.codex.auth.CodexSessionImporter
import com.kmnexus.codexmeter.providers.codex.auth.DeviceCodeAuthorization
import com.kmnexus.codexmeter.providers.codex.auth.DeviceCodeChallenge
import com.kmnexus.codexmeter.providers.codex.auth.DeviceCodeLoginAttemptId
import com.kmnexus.codexmeter.providers.codex.dto.CodexRateLimitDto
import com.kmnexus.codexmeter.providers.codex.dto.CodexUsageResponseDto
import com.kmnexus.codexmeter.providers.codex.dto.CodexWindowDto
import com.kmnexus.codexmeter.providers.codex.mapper.CodexUsageMapper
import com.kmnexus.codexmeter.providers.codex.network.CodexUsageClient
import com.kmnexus.codexmeter.providers.codex.session.CodexSessionPayload
import java.io.File
import java.time.Clock
import java.time.Instant
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
class Cm10R4DeviceReloginTest {
    @Test fun deletedDuringUsage() = exercise(true)
    @Test fun liveDuringUsage() = exercise(false)

    private fun exercise(delete: Boolean) = runBlocking {
        withTimeout(20_000) {
            val env = ReloginEnv().apply { seed() }
            try {
                val login = buildLoginUseCase(env.buildImporter())
                login.startLogin(CodexDeviceCodeLoginMode.Relogin(ProviderAccountId("acct-A")))
                val job = async(Dispatchers.IO) { login.pollLatest() }
                env.reached.await()
                if (delete) {
                    AccountDeletionRepository(
                        env.db,
                        env.vault,
                        env.prefs,
                        NoopDeletedAccountStateCleaner,
                        stateGuard = env.guard,
                        notificationTargetBoundary = env.boundary,
                    ).deleteAccount(env.p, LocalAccountId("A"))
                    val gone = env.dao.getByProviderAccountId("codex", "acct-A") == null
                    check(gone && env.prefs.currentAccountSelection()?.localAccountId?.value == "B")
                }
                env.release.complete(Unit)
                assertOutcome(env, delete, job.await())
            } finally {
                env.release.complete(Unit)
                env.db.close()
            }
        }
        Unit
    }

    private suspend fun assertOutcome(
        env: ReloginEnv,
        delete: Boolean,
        state: CodexDeviceCodeLoginState,
    ) {
        val found = env.dao.getByProviderAccountId("codex", "acct-A")?.localAccountId
        val selected = env.prefs.currentAccountSelection()?.localAccountId?.value
        val snapshot = found?.let { env.db.quotaSnapshotDao().getLatestForAccount("codex", it) } != null
        println(
            "Relogin delete=$delete returned=${state::class.simpleName} " +
                "originalRow=${env.dao.getById("A") != null} providerRow=$found " +
                "selected=$selected snapshot=$snapshot",
        )
        if (delete) {
            check(found == null && selected == "B") {
                "Relogin re-created the deleted provider account under $found"
            }
        } else {
            check(found == "A" && selected == "A" && state is CodexDeviceCodeLoginState.Saved && snapshot)
        }
    }

    private fun buildLoginUseCase(importer: CodexSessionImporter) = CodexDeviceCodeLoginUseCase(
        deviceCodeClient = object : CodexDeviceCodeLoginUseCase.DeviceCodeClient {
            override suspend fun requestDeviceCode() = CodexDeviceCodeClient.Result.Success(
                DeviceCodeChallenge(
                    userCode = "demo",
                    deviceAuthId = "demo",
                    verificationUri = "https://example.invalid",
                    intervalSeconds = 5,
                    expiresInSeconds = 900,
                ),
            )

            override suspend fun pollAuthorization(challenge: DeviceCodeChallenge) =
                CodexDeviceCodeClient.Result.Success(DeviceCodeAuthorization("demo", "demo"))
        },
        tokenExchanger = CodexDeviceCodeLoginUseCase.TokenExchanger {
            CodexOAuthTokenExchanger.Result.Success(
                CodexSessionPayload(
                    accessToken = "demo",
                    refreshToken = "demo",
                    idToken = "demo",
                    accountId = "acct-A",
                    accountEmail = "demo@example.invalid",
                    lastRefresh = Instant.now(),
                ),
            )
        },
        sessionImporter = object : CodexDeviceCodeLoginUseCase.SessionImporter {
            override suspend fun prepareDeviceCodeSession(session: CodexSessionPayload) =
                importer.prepareDeviceCodeSession(session)

            override suspend fun commitPreparedDeviceCodeSession(
                preparedImport: CodexSessionImporter.PreparedImport,
                allowNewRow: Boolean,
            ) = importer.commitPreparedDeviceCodeSession(preparedImport, allowNewRow)
        },
        attemptIdProvider = { DeviceCodeLoginAttemptId("demo") },
        clock = Clock.systemUTC(),
    )

    private class ReloginEnv {
        private val ctx: Context = RuntimeEnvironment.getApplication()
        private val dir = File(ctx.filesDir, UUID.randomUUID().toString())
        val db = Room.inMemoryDatabaseBuilder(ctx, CodexMeterDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val prefs = CurrentAccountPreferences.create(File(dir, "prefs.preferences_pb"))
        val vault = FileSecureSessionStore(File(dir, "sessions"))
        val boundary = NotificationTargetBoundary()
        val guard = VersionedStateGuard()
        val reached = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val p = ProviderId("codex")
        val dao = db.providerAccountDao()

        suspend fun seed() {
            for (id in listOf("A", "B")) dao.upsert(FixtureAccounts.account(localAccountId = id, displayName = id))
            prefs.updateCurrentAccountSelection(CurrentAccountSelection(p, LocalAccountId("A")))
        }

        fun buildImporter() = CodexSessionImporter(
            usageClient = CodexSessionImporter.UsageClient { _, _ ->
                reached.complete(Unit)
                release.await()
                CodexUsageClient.Result.Success(
                    CodexUsageResponseDto(
                        planType = "plus",
                        rateLimit = CodexRateLimitDto(primaryWindow = CodexWindowDto(usedPercent = 20)),
                    ),
                )
            },
            mapper = CodexUsageMapper(),
            importPersistence = RoomCodexSessionImportPersistence(
                db,
                vault,
                prefs,
                guard,
                notificationTargetBoundary = boundary,
            ),
            sessionEnvelopeFactory = CodexSessionImporter.SessionEnvelopeFactory { _, id, provider, now ->
                ProviderSessionEnvelope(
                    "codex",
                    id.value,
                    provider?.value,
                    1,
                    byteArrayOf(1),
                    byteArrayOf(2),
                    now.toString(),
                    now.toString(),
                )
            },
            localAccountIdProvider = CodexSessionImporter.LocalAccountIdProvider {
                LocalAccountId("codex-" + UUID.randomUUID())
            },
            defaultDisplayName = "Audit",
            clock = Clock.systemUTC(),
        )
    }
}
