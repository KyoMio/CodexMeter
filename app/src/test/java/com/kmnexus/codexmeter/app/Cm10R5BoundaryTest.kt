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

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class Cm10R5BoundaryTest {
    @Test fun cancelAfterDelete() = exercise(true)
    @Test fun finishAfterDelete() = exercise(false)

    private fun exercise(cancel:Boolean) = runBlocking {
        withTimeout(15_000) {
            val ctx:Context=RuntimeEnvironment.getApplication()
            val dir=File(ctx.filesDir,UUID.randomUUID().toString())
            val db=Room.inMemoryDatabaseBuilder(ctx,CodexMeterDatabase::class.java).allowMainThreadQueries().build()
            val prefs=CurrentAccountPreferences.create(File(dir,"prefs.preferences_pb"))
            val vaultDir=File(dir,"sessions")
            val vault=FileSecureSessionStore(vaultDir)
            val boundary=NotificationTargetBoundary()
            val guard=VersionedStateGuard()
            val dao=db.providerAccountDao()
            val a=FixtureAccounts.account(localAccountId="A",displayName="alpha")
            val b=FixtureAccounts.account(localAccountId="B",displayName="beta")
            dao.upsert(a); dao.upsert(b)
            val p=ProviderId("codex")
            prefs.updateCurrentAccountSelection(CurrentAccountSelection(p,LocalAccountId("A")))
            fun env(v:Int)=ProviderSessionEnvelope("codex","A","acct-A",1,byteArrayOf(v.toByte()),byteArrayOf(1),"fixture","fixture")
            vault.save(env(3))
            val aboutToSave=CompletableDeferred<Unit>()
            val resumeIO=CompletableDeferred<Unit>()
            val written=CompletableDeferred<Unit>()
            val wrapped=object:SecureSessionStore by vault {
                override suspend fun save(envelope:ProviderSessionEnvelope) {
                    aboutToSave.complete(Unit); resumeIO.await()
                    vault.save(envelope); written.complete(Unit)
                }
            }
            val worker=ExecutorsHolder()
            try {
                val store=RoomCodexSessionImportPersistence(db,wrapped,prefs,guard,notificationTargetBoundary=boundary)
                val job=async(worker.io) { runCatching {
                    store.save(a.copy(localAccountId="fresh").toDomain(),env(9),FixtureAccounts.snapshot(localAccountId="fresh",snapshotId="audit").toDomain(),allowNewRow=false,expectedLocalAccountId=null)
                } }
                aboutToSave.await()
                AccountDeletionRepository(db,vault,prefs,NoopDeletedAccountStateCleaner,stateGuard=guard,notificationTargetBoundary=boundary).deleteAccount(p,LocalAccountId("A"))
                check(dao.getById("A")==null && vault.load("codex","A")==null)
                boundary.serialize {
                    resumeIO.complete(Unit); written.await()
                    withContext(worker.io) { Unit } // writer yielded waiting for the actual mutex
                    if(cancel) job.cancelAndJoin()
                }
                if(!cancel) check(job.await().exceptionOrNull()?.message?.startsWith("Import discarded:")==true)
                val stored=FileSecureSessionStore(vaultDir).load("codex","A")
                val selected=prefs.currentAccountSelection()?.localAccountId?.value
                println("cancel=$cancel selected=$selected rowA="+(dao.getById("A")!=null)+" persistedSyntheticMarker="+stored?.payloadCiphertext?.first())
                check(selected=="B" && dao.getById("A")==null && stored==null) { "Deleted account still has a staged credential after cancellation" }
            } finally { resumeIO.complete(Unit); worker.io.close(); db.close() }
        }
        Unit
    }
    class ExecutorsHolder { val io=java.util.concurrent.Executors.newSingleThreadExecutor().asCoroutineDispatcher() }
}
