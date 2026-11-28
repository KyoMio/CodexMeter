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
import kotlinx.coroutines.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID
import java.time.Clock

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class Cm10R4OrderTest {
    @Test fun freshUuid() = exercise(existing=false, delete=false)
    @Test fun matchedLive() = exercise(existing=true, delete=false)
    @Test fun deleteDuringMatchedImport() = exercise(existing=true, delete=true)
    private fun exercise(existing:Boolean, delete:Boolean) = runBlocking {
        withTimeout(20_000) {
            val ctx:Context=RuntimeEnvironment.getApplication()
            val dir=File(ctx.filesDir,UUID.randomUUID().toString())
            val db=Room.inMemoryDatabaseBuilder(ctx,CodexMeterDatabase::class.java).allowMainThreadQueries().build()
            val store=CurrentAccountPreferences.create(File(dir,"prefs.preferences_pb"))
            val vault=FileSecureSessionStore(File(dir,"sessions"))
            val boundary=NotificationTargetBoundary()
            val guard=VersionedStateGuard()
            val dao=db.providerAccountDao()
            val p=ProviderId("codex")
            val a=FixtureAccounts.account(localAccountId="A",displayName="Alpha")
            val b=FixtureAccounts.account(localAccountId="B",displayName="Beta")
            val fresh="codex-"+UUID.randomUUID()
            val incoming=a.copy(localAccountId=fresh)
            fun env(id:String,v:Int)=ProviderSessionEnvelope("codex",id,"acct-A",1,byteArrayOf(v.toByte()),byteArrayOf(2),"fixture","fixture")
            val selection=if(existing) "A" else "B"
            dao.upsert(b)
            if(existing) { dao.upsert(a); vault.save(env("A",7)) }
            store.updateCurrentAccountSelection(CurrentAccountSelection(p, LocalAccountId(selection)))
            val saved=CompletableDeferred<Unit>()
            val release=CompletableDeferred<Unit>()
            val paused=object:SecureSessionStore by vault {
                override suspend fun save(envelope:ProviderSessionEnvelope) {
                    vault.save(envelope)
                    
                }
            }
            val imp=RoomCodexSessionImportPersistence(db,paused,store,guard,notificationTargetBoundary=boundary)
            val repo=CurrentQuotaStateRepository(store,dao,db.quotaSnapshotDao(),db.refreshAttemptDao(),clock=Clock.systemUTC())
            try {
                val job=async(Dispatchers.IO) { runCatching { imp.save(incoming.toDomain(),env(fresh,9),
                    FixtureAccounts.snapshot(localAccountId=fresh,snapshotId="new-import").toDomain(), allowNewRow = true, expectedLocalAccountId = null) } }
                if(delete) {
                    check(job.await().isSuccess)
                    AccountDeletionRepository(db,vault,store,NoopDeletedAccountStateCleaner,stateGuard=guard,notificationTargetBoundary=boundary).deleteAccount(p,LocalAccountId("A"))
                    check(store.currentAccountSelection()?.localAccountId?.value=="B")
                    check(repo.loadCurrentState().account?.localAccountId?.value=="B")
                    check(vault.load("codex","A")==null && dao.getById("A")==null)
                    println("delete returned: Home=B, rowA=false, envelopeA=false")
                    release.complete(Unit)
                }
                val result=job.await()
                val selected=store.currentAccountSelection()?.localAccountId?.value
                val loaded=repo.loadCurrentState().account?.localAccountId?.value
                val envelope=vault.load("codex","A")
                println("existing=$existing delete=$delete result="+(result.exceptionOrNull()?.message ?: "ok")+
                  " selected=$selected home=$loaded live="+dao.listAll().map{it.localAccountId}+" oldEnvelopeRestored="+(envelope?.payloadCiphertext?.contentEquals(byteArrayOf(7))==true))
                if(delete) {
                    check(result.isSuccess)
                    check(dao.getById("A")==null && db.quotaSnapshotDao().getLatestForAccount("codex","A")==null)
                    check(loaded=="B" && selected=="B" && envelope==null) { "Discard overwrote completed deletion: selected=$selected home=$loaded oldEnvelopePresent="+(envelope!=null) }
                } else {
                    val expected=if(existing) "A" else fresh
                    check(result.getOrThrow().account.localAccountId.value==expected && loaded==expected && selected==expected)
                    check(db.quotaSnapshotDao().getLatestForAccount("codex",expected)!=null)
                }
            } finally { release.complete(Unit); db.close() }
        }
        Unit
    }
}
