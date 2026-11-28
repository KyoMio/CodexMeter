package com.kmnexus.codexmeter.app

import android.content.Context
import androidx.room.Room
import com.kmnexus.codexmeter.core.concurrency.VersionedStateGuard
import com.kmnexus.codexmeter.data.local.db.CodexMeterDatabase
import com.kmnexus.codexmeter.data.preferences.*
import com.kmnexus.codexmeter.data.repository.*
import com.kmnexus.codexmeter.data.secure.*
import com.kmnexus.codexmeter.domain.account.NoopDeletedAccountStateCleaner
import com.kmnexus.codexmeter.domain.model.*
import com.kmnexus.codexmeter.notification.NotificationTargetBoundary
import com.kmnexus.codexmeter.providers.codex.auth.*
import com.kmnexus.codexmeter.providers.codex.dto.*
import com.kmnexus.codexmeter.providers.codex.mapper.*
import com.kmnexus.codexmeter.providers.codex.network.CodexUsageClient
import com.kmnexus.codexmeter.providers.codex.session.CodexSessionPayload
import kotlinx.coroutines.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID
import java.time.Clock
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class Cm10R4DeviceReloginTest {
    @Test fun deletedDuringUsage() = exercise(true)
    @Test fun liveDuringUsage() = exercise(false)
    private fun exercise(delete:Boolean) = runBlocking {
        withTimeout(20_000) {
            val ctx:Context=RuntimeEnvironment.getApplication()
            val dir=File(ctx.filesDir,UUID.randomUUID().toString())
            val db=Room.inMemoryDatabaseBuilder(ctx,CodexMeterDatabase::class.java).allowMainThreadQueries().build()
            val prefs=CurrentAccountPreferences.create(File(dir,"prefs.preferences_pb"))
            val vault=FileSecureSessionStore(File(dir,"sessions"))
            val boundary=NotificationTargetBoundary()
            val guard=VersionedStateGuard()
            val reached=CompletableDeferred<Unit>()
            val release=CompletableDeferred<Unit>()
            val p=ProviderId("codex")
            val dao=db.providerAccountDao()
            try {
                for(id in listOf("A","B")) dao.upsert(FixtureAccounts.account(localAccountId=id,displayName=id))
                prefs.updateCurrentAccountSelection(CurrentAccountSelection(p,LocalAccountId("A")))
                val importer=CodexSessionImporter(
                    usageClient=CodexSessionImporter.UsageClient { _,_ ->
                        reached.complete(Unit); release.await()
                        CodexUsageClient.Result.Success(CodexUsageResponseDto(planType="plus",rateLimit=CodexRateLimitDto(primaryWindow=CodexWindowDto(usedPercent=20))))
                    },
                    mapper=CodexUsageMapper(),importPersistence=RoomCodexSessionImportPersistence(db,vault,prefs,guard,notificationTargetBoundary=boundary),
                    sessionEnvelopeFactory=CodexSessionImporter.SessionEnvelopeFactory { _,id,provider,now ->
                        ProviderSessionEnvelope("codex",id.value,provider?.value,1,byteArrayOf(1),byteArrayOf(2),now.toString(),now.toString()) },
                    localAccountIdProvider=CodexSessionImporter.LocalAccountIdProvider { LocalAccountId("codex-"+UUID.randomUUID()) },
                    defaultDisplayName="Audit",clock=Clock.systemUTC())
                val login=CodexDeviceCodeLoginUseCase(
                    deviceCodeClient=object:CodexDeviceCodeLoginUseCase.DeviceCodeClient {
                        override suspend fun requestDeviceCode()=CodexDeviceCodeClient.Result.Success(DeviceCodeChallenge(userCode="demo",deviceAuthId="demo",verificationUri="https://example.invalid",intervalSeconds=5,expiresInSeconds=900))
                        override suspend fun pollAuthorization(challenge:DeviceCodeChallenge)=CodexDeviceCodeClient.Result.Success(DeviceCodeAuthorization("demo","demo"))
                    },
                    tokenExchanger=CodexDeviceCodeLoginUseCase.TokenExchanger { CodexOAuthTokenExchanger.Result.Success(CodexSessionPayload(accessToken="demo",refreshToken="demo",idToken="demo",accountId="acct-A",accountEmail="demo@example.invalid",lastRefresh=Instant.now())) },
                    sessionImporter=object:CodexDeviceCodeLoginUseCase.SessionImporter {
                        override suspend fun prepareDeviceCodeSession(session:CodexSessionPayload)=importer.prepareDeviceCodeSession(session)
                        override suspend fun commitPreparedDeviceCodeSession(preparedImport:CodexSessionImporter.PreparedImport,allowNewRow:Boolean)=importer.commitPreparedDeviceCodeSession(preparedImport,allowNewRow)
                    },attemptIdProvider={ DeviceCodeLoginAttemptId("demo") },clock=Clock.systemUTC())
                login.startLogin(CodexDeviceCodeLoginMode.Relogin(ProviderAccountId("acct-A")))
                val job=async(Dispatchers.IO) { login.pollLatest() }
                reached.await()
                if(delete) {
                    AccountDeletionRepository(db,vault,prefs,NoopDeletedAccountStateCleaner,stateGuard=guard,notificationTargetBoundary=boundary).deleteAccount(p,LocalAccountId("A"))
                    check(dao.getByProviderAccountId("codex","acct-A")==null && prefs.currentAccountSelection()?.localAccountId?.value=="B")
                }
                release.complete(Unit)
                val state=job.await()
                val found=dao.getByProviderAccountId("codex","acct-A")?.localAccountId
                val selected=prefs.currentAccountSelection()?.localAccountId?.value
                val snapshot=found?.let { db.quotaSnapshotDao().getLatestForAccount("codex",it) }!=null
                println("Relogin delete=$delete returned="+state::class.simpleName+" originalRow="+(dao.getById("A")!=null)+" providerRow=$found selected=$selected snapshot=$snapshot")
                if(delete) check(found==null && selected=="B") { "Relogin re-created the deleted provider account under $found" }
                else check(found=="A" && selected=="A" && state is CodexDeviceCodeLoginState.Saved && snapshot)
            } finally { release.complete(Unit); db.close() }
        }
        Unit
    }
}
