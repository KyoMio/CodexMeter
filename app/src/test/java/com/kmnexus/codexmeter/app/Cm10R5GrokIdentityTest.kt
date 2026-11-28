package com.kmnexus.codexmeter.app

import android.content.Context
import androidx.room.Room
import com.kmnexus.codexmeter.core.concurrency.VersionedStateGuard
import com.kmnexus.codexmeter.data.local.db.CodexMeterDatabase
import com.kmnexus.codexmeter.data.preferences.*
import com.kmnexus.codexmeter.data.repository.*
import com.kmnexus.codexmeter.data.secure.*
import com.kmnexus.codexmeter.domain.account.NoopDeletedAccountStateCleaner
import com.kmnexus.codexmeter.domain.auth.DeviceCodeLoginResult
import com.kmnexus.codexmeter.domain.model.*
import com.kmnexus.codexmeter.notification.NotificationTargetBoundary
import com.kmnexus.codexmeter.providers.grok.auth.*
import com.kmnexus.codexmeter.providers.grok.network.*
import com.kmnexus.codexmeter.providers.grok.dto.*
import kotlinx.coroutines.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID
import java.util.Base64
import java.time.Clock

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class Cm10R5GrokIdentityTest {
    @Test fun readded() = exercise(true)
    @Test fun deletedOnly() = exercise(false)
    private fun exercise(readd:Boolean) = runBlocking {
        withTimeout(20_000) {
            val ctx:Context=RuntimeEnvironment.getApplication()
            val dir=File(ctx.filesDir,UUID.randomUUID().toString())
            val db=Room.inMemoryDatabaseBuilder(ctx,CodexMeterDatabase::class.java).allowMainThreadQueries().build()
            val prefs=CurrentAccountPreferences.create(File(dir,"prefs.preferences_pb"))
            val vault=FileSecureSessionStore(File(dir,"sessions"))
            val boundary=NotificationTargetBoundary()
            val guard=VersionedStateGuard()
            val dao=db.providerAccountDao()
            val p=ProviderId("grok")
            val waitBilling=CompletableDeferred<Unit>()
            val resumeFirst=CompletableDeferred<Unit>()
            try {
                val id="acct-A"
                dao.upsert(FixtureAccounts.account(localAccountId="A",displayName="Original").copy(providerId="grok",providerAccountId=id))
                dao.upsert(FixtureAccounts.account(localAccountId="B",displayName="Other").copy(providerId="grok"))
                prefs.updateCurrentAccountSelection(CurrentAccountSelection(p,LocalAccountId("A")))
                val store=RoomCodexSessionImportPersistence(db,vault,prefs,guard,notificationTargetBoundary=boundary)
                val importer=GrokSessionImporter(
                    billingClient={token ->
                        if(token=="old-test") { waitBilling.complete(Unit); resumeFirst.await() }
                        GrokBillingClient.Result.Success(GrokBillingResponseDto(currentPeriod=GrokBillingPeriodDto(type="USAGE_PERIOD_TYPE_WEEKLY",start="2026-09-03T17:33:48Z",end="2026-09-10T17:33:48Z"),creditUsagePercent=23.0,prepaidBalance=12.5))
                    },
                    importPersistence={account,envelope,snapshot,allow,expectedLocal ->
                        val c=store.save(account,envelope,snapshot,allow,expectedLocal)
                        GrokSessionImporter.CommittedImport(c.account,c.sessionEnvelope,c.snapshot)
                    },sessionEnvelopeFactory={payload,local,provider,now ->
                        val marker=if(payload.accessToken=="old-test") 1 else 2
                        ProviderSessionEnvelope("grok",local.value,provider?.value,1,byteArrayOf(marker.toByte()),byteArrayOf(0),now.toString(),now.toString())
                    },localAccountIdProvider={ LocalAccountId("grok-"+UUID.randomUUID()) },defaultDisplayName="Audit",clock=Clock.systemUTC())
                var polls=0
                val client=object:GrokDeviceCodeLoginController.DeviceCodeClient {
                    override suspend fun requestDeviceCode(deviceAuthorizationEndpointUrl:String)=GrokDeviceCodeClient.Result.Success(GrokDeviceCodeChallenge(deviceCode="test",userCode="example",verificationUri="https://example.invalid",verificationUriComplete=null,intervalSeconds=1,expiresInSeconds=900))
                    override suspend fun awaitAuthorization(challenge:GrokDeviceCodeChallenge,tokenEndpointUrl:String):GrokDeviceCodeClient.Result<GrokOAuthToken> {
                        polls++
                        val sub=Base64.getUrlEncoder().withoutPadding().encodeToString("""{"sub":"acct-A"}""".toByteArray())
                        return GrokDeviceCodeClient.Result.Success(GrokOAuthToken(accessToken=if(polls==1) "old-test" else "new-test",refreshToken="synthetic-test",idToken="test.$sub.test",expiresAtEpochSeconds=2000000000L))
                    }
                }
                val login=GrokDeviceCodeLoginController(
                    discoveryClient={GrokOAuthDiscoveryClient.Result.Success(GrokOAuthEndpoints(deviceAuthorizationEndpoint="https://example.invalid/device",tokenEndpoint="https://example.invalid/token"))},
                    deviceCodeClient=client,
                    sessionImporter=object:GrokDeviceCodeLoginController.SessionImporter {
                        override suspend fun prepareDeviceCodeSession(oauthToken:GrokOAuthToken,identity:GrokLoginIdentity,tokenEndpoint:String?)=importer.prepareDeviceCodeSession(oauthToken,identity,tokenEndpoint)
                        override suspend fun commitPreparedDeviceCodeSession(preparedImport:GrokSessionImporter.PreparedImport, allowNewRow:Boolean)=importer.commitPreparedDeviceCodeSession(preparedImport,allowNewRow)
                    },attemptIdProvider={ GrokDeviceCodeLoginAttemptId(UUID.randomUUID().toString()) },clock=Clock.systemUTC())
                login.startRelogin(id,"A")
                val old=async(Dispatchers.IO) { login.pollLatest() }
                waitBilling.await()
                AccountDeletionRepository(db,vault,prefs,NoopDeletedAccountStateCleaner,stateGuard=guard,notificationTargetBoundary=boundary).deleteAccount(p,LocalAccountId("A"))
                check(dao.getByProviderAccountId("grok",id)==null)
                var replacement:String?=null
                if(readd) {
                    login.startLogin()
                    val added=login.pollLatest() as DeviceCodeLoginResult.Saved
                    replacement=added.account.localAccountId.value
                    check(replacement!="A" && vault.load("grok",replacement)?.payloadCiphertext?.first()?.toInt()==2)
                }
                resumeFirst.complete(Unit); old.await()
                val actual=dao.getByProviderAccountId("grok",id)?.localAccountId
                val marker=actual?.let{vault.load("grok",it)?.payloadCiphertext?.first()?.toInt()}
                println("sameController readd=$readd replacement=$replacement rowAfter=$actual beforeMarker="+(if(readd) 2 else null)+" afterMarker=$marker")
                check(actual==replacement && (if(readd) marker==2 else marker==null)) { "Old re-login overwrote replacement's credential" }
            } finally { resumeFirst.complete(Unit); db.close() }
        }; Unit
    }
}
