package com.kmnexus.codexmeter.app

import android.content.Context
import android.app.Notification
import android.app.NotificationManager
import com.kmnexus.codexmeter.notification.*
import com.kmnexus.codexmeter.domain.settings.NotificationAccountSelection
import java.util.concurrent.Executors
import java.time.Instant
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
class Cm10R4GapTest {
    @Test fun freshTarget() = exercise(false)
    @Test fun matchedTarget() = exercise(true)
    private fun exercise(existing:Boolean) = runBlocking {
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
            val io=Executors.newSingleThreadExecutor().asCoroutineDispatcher()
            val staged=CompletableDeferred<Unit>()
            val release=CompletableDeferred<Unit>()
            val loaded=CompletableDeferred<Unit>()
            val observed=mutableListOf<String?>()
            val options=NotificationPreferencesDataStore(store.dataStore)
            val resolver=NotificationAccountTargetResolver(options,dao,store)
            val repo=CurrentQuotaStateRepository(store,dao,db.quotaSnapshotDao(),db.refreshAttemptDao(),clock=Clock.systemUTC(),
                notificationTargetResolver=resolver,notificationPreferenceReader=options)
            val manager=ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            fun title()=manager.activeNotifications.singleOrNull{it.id==1001}?.notification?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            val sink=AndroidNotificationSink(ctx)
            val pub=CurrentQuotaNotificationPublisher(
                notificationSink=object:NotificationSink by sink {
                    override fun post(request:NotificationRequest) { sink.post(request); observed.add(title()) }
                }, alertStateStore=object:NotificationAlertStateStore {
                    override suspend fun hasNotified(key:AlertDedupeKey)=false
                    override suspend fun markNotified(event:QuotaAlertEvent,notifiedAt:Instant)=Unit
                },optionsReader=NotificationRequestOptionsReader { NotificationRequestOptions(true,true,false,false) },
                alertThresholdsReader=AlertThresholdsReader { AlertThresholds() },
                statusNotificationStateLoader=StatusNotificationStateLoader { frame ->
                    repo.loadStatusNotificationState(frame).also { println("gap frame="+it.account?.localAccountId?.value); loaded.complete(Unit) }
                }, notificationTargetResolver=resolver,notificationTargetBoundary=boundary,clock=Clock.systemUTC())
            try {
                dao.upsert(b)
                if(existing) { dao.upsert(a); db.quotaSnapshotDao().insert(FixtureAccounts.snapshot(localAccountId="A",snapshotId="first")) }
                store.updateCurrentAccountSelection(CurrentAccountSelection(p,LocalAccountId("B")))
                options.updateNotificationPreferences(options.notificationPreferences().copy(persistentNotificationAccount=null))
                val writer=object:CurrentAccountStore by store {
                    override suspend fun updateCurrentAccountSelection(selection:CurrentAccountSelection?) {
                        store.updateCurrentAccountSelection(selection); staged.complete(Unit); release.await()
                    }
                }
                val imp=RoomCodexSessionImportPersistence(db,vault,writer,guard,notificationTargetBoundary=boundary)
                val op=async(Dispatchers.IO) { imp.save(incoming.toDomain(),env(fresh,9),FixtureAccounts.snapshot(localAccountId=fresh,snapshotId="new").toDomain(), allowNewRow = true) }
                staged.await()
                val expected=if(existing) "A" else fresh
                check(resolver.resolveTargetSelection()?.localAccountId?.value==expected)
                val posting=async(io) { pub.publish(repo.loadCurrentState()) }
                loaded.await(); withContext(io) { Unit }; release.complete(Unit)
                posting.await(); op.await()
                val gap=observed.toList()
                pub.publish(repo.loadCurrentState())
                println("existing=$existing gapPosts=$gap afterCommit="+observed.lastOrNull())
                check(if(existing) gap.single()?.contains("Alpha")==true else gap.isEmpty())
                check(observed.lastOrNull()?.contains("Alpha")==true)
            } finally { release.complete(Unit); io.close(); db.close() }
        }
        Unit
    }
}
