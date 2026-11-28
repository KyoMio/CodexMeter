package com.kmnexus.codexmeter.app

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.room.Room
import com.kmnexus.codexmeter.core.concurrency.VersionedStateGuard
import com.kmnexus.codexmeter.data.local.db.CodexMeterDatabase
import com.kmnexus.codexmeter.data.preferences.*
import com.kmnexus.codexmeter.data.repository.*
import com.kmnexus.codexmeter.data.secure.*
import com.kmnexus.codexmeter.domain.account.*
import com.kmnexus.codexmeter.domain.auth.SessionLoginUseCase
import com.kmnexus.codexmeter.domain.model.*
import com.kmnexus.codexmeter.domain.quota.QuotaSnapshot
import com.kmnexus.codexmeter.domain.settings.NotificationAccountSelection
import com.kmnexus.codexmeter.notification.*
import com.kmnexus.codexmeter.providers.SessionImportRouter
import com.kmnexus.codexmeter.providers.SessionImporter
import java.io.File
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class Cm10R3LateWriteTest {
    enum class Case { IMPORT, DELETE, ROLLBACK, REL0GIN }
    @Test fun importVsPost() = race(Case.IMPORT)
    @Test fun deletePinnedVsPost() = race(Case.DELETE)
    @Test fun restoreVsPost() = race(Case.ROLLBACK)
    @Test fun lateReloginVsPost() = race(Case.REL0GIN)

    private fun race(case: Case) = runBlocking {
        withTimeout(25_000) {
            val ctx: Context = RuntimeEnvironment.getApplication()
            val manager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            fun title() = manager.activeNotifications.singleOrNull { it.id==1001 }?.notification?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            check(title()==null)
            val db = Room.inMemoryDatabaseBuilder(ctx,CodexMeterDatabase::class.java).allowMainThreadQueries().build()
            val io = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
            val openSink = CountDownLatch(1)
            val resumeDeletion = CompletableDeferred<Unit>()
            val resumeRelogin = CompletableDeferred<Unit>()
            try {
                val guard = VersionedStateGuard()
                val boundary = NotificationTargetBoundary()
                val clock = Clock.systemUTC()
                val store = CurrentAccountPreferences.create(File(ctx.filesDir,UUID.randomUUID().toString()+".preferences_pb"))
                val prefs = NotificationPreferencesDataStore(store.dataStore)
                val aProvider = if(case==Case.REL0GIN) "deepseek" else "codex"
                val a = FixtureAccounts.account(localAccountId="A",displayName="Audit Alpha").copy(providerId=aProvider)
                val b = FixtureAccounts.account(localAccountId="B",displayName="Audit Beta")
                for (acct in listOf(a,b)) {
                    db.providerAccountDao().upsert(acct)
                    db.quotaSnapshotDao().insert(FixtureAccounts.snapshot(localAccountId=acct.localAccountId,snapshotId=acct.localAccountId).copy(providerId=acct.providerId))
                }
                val pinned = case in setOf(Case.DELETE,Case.REL0GIN)
                val default = if(pinned) b else a
                store.updateCurrentAccountSelection(CurrentAccountSelection(ProviderId(default.providerId),LocalAccountId(default.localAccountId)))
                prefs.updateNotificationPreferences(prefs.notificationPreferences().copy(persistentNotificationAccount=
                    NotificationAccountSelection(ProviderId(aProvider),LocalAccountId(if(pinned) "A" else "removed"))))
                val resolver = NotificationAccountTargetResolver(prefs,db.providerAccountDao(),store)
                suspend fun target() = resolver.resolveTargetSelection()?.localAccountId?.value
                val repo = CurrentQuotaStateRepository(store,db.providerAccountDao(),db.quotaSnapshotDao(),db.refreshAttemptDao(),clock=clock,
                    notificationTargetResolver=resolver,notificationPreferenceReader=prefs)
                val sink = AndroidNotificationSink(ctx)
                val atSink = CompletableDeferred<Unit>()
                val loaded = CompletableDeferred<Unit>()
                val snapshotAtPost = CompletableDeferred<Pair<String?,String?>>()
                val publisher = CurrentQuotaNotificationPublisher(
                    notificationSink=object: NotificationSink by sink {
                        override fun post(request: NotificationRequest) {
                            atSink.complete(Unit); check(openSink.await(9,TimeUnit.SECONDS))
                            val selection = runBlocking { target() }
                            sink.post(request); snapshotAtPost.complete(selection to title())
                        }
                    },
                    alertStateStore=object: NotificationAlertStateStore {
                        override suspend fun hasNotified(key: AlertDedupeKey)=false
                        override suspend fun markNotified(event: QuotaAlertEvent,notifiedAt: Instant)=Unit
                    },
                    optionsReader=NotificationRequestOptionsReader { NotificationRequestOptions(true,true,false,false) },
                    alertThresholdsReader=AlertThresholdsReader { AlertThresholds() },
                    statusNotificationStateLoader=StatusNotificationStateLoader { frame ->
                        repo.loadStatusNotificationState(frame).also { loaded.complete(Unit) }
                    },notificationTargetResolver=resolver,notificationTargetBoundary=boundary,clock=clock)
                val atProvider = CompletableDeferred<Unit>()
                val provider = object: SessionImporter {
                    override suspend fun importFromApiKey(apiKey: String,account: ProviderAccount,apiBaseUrl: String?): Result<QuotaSnapshot> {
                        atProvider.complete(Unit); resumeRelogin.await()
                        return Result.success(FixtureAccounts.snapshot(localAccountId="A",snapshotId="renewed").copy(providerId=aProvider).toDomain())
                    }
                    override suspend fun importFromCookie(cookieJson:String,account:ProviderAccount):Result<QuotaSnapshot> = error("unused")
                    override suspend fun importFromOAuthPkce(code:String,verifier:String,redirectUri:String,account:ProviderAccount):Result<QuotaSnapshot> = error("unused")
                }
                val login = SessionLoginUseCase(SessionImportRouter(mapOf(ProviderId(aProvider) to provider)),db,db.providerAccountDao(),
                    db.quotaSnapshotDao(),store,guard,clock, boundary)
                val sessions = FakeSecureSessionStore()
                val atWriter = CompletableDeferred<Unit>()
                val rowGone = CompletableDeferred<Unit>()
                val cleanup = object: DeletedAccountStateCleaner {
                    override suspend fun clearDeletedAccountState(providerId:ProviderId,localAccountId:LocalAccountId):DeletedAccountStateCleanup {
                        atWriter.complete(Unit)
                        return DeletedAccountStateCleanup {
                            if (case==Case.ROLLBACK) { rowGone.complete(Unit); resumeDeletion.await() }
                        }
                    }
                }
                val deleteRows = object: AccountLocalDataDeleter {
                    override suspend fun deleteLocalData(providerId:ProviderId,localAccountId:LocalAccountId) {
                        if(case==Case.ROLLBACK) error("controlled storage failure")
                        RoomAccountLocalDataDeleter(db).deleteLocalData(providerId,localAccountId)
                        if(case==Case.REL0GIN) { rowGone.complete(Unit); resumeDeletion.await() }
                    }
                }
                val deletion = AccountDeletionRepository(db,sessions,store,cleanup,deleteRows,prefs,guard,boundary)
                val relogin = if(case==Case.REL0GIN) async(Dispatchers.IO) {
                    // R3 改编：账号在验证期间被删除时重登结果按设计被丢弃（Result.failure），
                    // getOrThrow 会抛——writer 的「完成」语义以协程结束为准而非结果成功。
                    runCatching { login.reloginApiKey(LocalAccountId("A"),"fixture").getOrThrow() }
                } else null
                if(relogin!=null) atProvider.await()
                suspend fun deleteA() = deletion.deleteAccount(ProviderId(aProvider),LocalAccountId("A"))
                val earlyDelete = when(case) {
                    Case.ROLLBACK -> async(Dispatchers.IO) { check(runCatching { deleteA() }.exceptionOrNull()?.message=="controlled storage failure") }
                    Case.REL0GIN -> async(Dispatchers.IO) { deleteA() }
                    else -> null
                }
                if(earlyDelete!=null) { rowGone.await(); check(target()=="B") }
                val post = async(io) { guard.commitSerialized { publisher.publish(repo.loadCurrentState()) } }
                if(case==Case.REL0GIN) {
                    loaded.await()
                    withContext(io) { Unit } // Earlier publisher turn reached the mutex before the deletion releases it.
                    resumeDeletion.complete(Unit)
                }
                atSink.await()
                val writer = when(case) {
                    Case.IMPORT -> async(Dispatchers.IO) {
                        val ss=object:SecureSessionStore by sessions {
                            override suspend fun save(envelope:ProviderSessionEnvelope) { sessions.save(envelope); atWriter.complete(Unit) }
                        }
                        RoomCodexSessionImportPersistence(db,ss,store,guard,notificationTargetBoundary=boundary).save(
                            b.toDomain(),ProviderSessionEnvelope("codex","B","acct-B",1,byteArrayOf(1),byteArrayOf(2),"now","now"),
                            FixtureAccounts.snapshot(localAccountId="B",snapshotId="import-B").toDomain())
                    }
                    Case.DELETE -> async(Dispatchers.IO) { deleteA() }
                    Case.ROLLBACK -> { resumeDeletion.complete(Unit); earlyDelete!! }
                    Case.REL0GIN -> { resumeRelogin.complete(Unit); relogin!! }
                }
                if(case==Case.IMPORT || case==Case.DELETE) atWriter.await()
                val completed = withTimeoutOrNull(400) { writer.await(); true } == true
                val before = target()
                check(title()==null)
                openSink.countDown(); post.await(); writer.await(); earlyDelete?.await()
                val (actual,posted)=snapshotAtPost.await()
                println("case=$case writerCompletedBeforeRelease=$completed before=$before atNotify=$actual newTitle=$posted finalTarget="+target()+" rowA="+(db.providerAccountDao().getById("A")!=null))
                check(posted?.contains(if(actual=="A") "Audit Alpha" else "Audit Beta")==true) {
                    "wrong frame: target=$actual, title=$posted"
                }
                check(!completed) { "writer finished inside posting boundary" }
            } finally { openSink.countDown(); resumeDeletion.complete(Unit); resumeRelogin.complete(Unit); io.close(); db.close() }
            Unit
        }
    }
}
