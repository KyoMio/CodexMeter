package com.kmnexus.codexmeter.app
import android.content.Context
import androidx.room.Room
import com.kmnexus.codexmeter.core.concurrency.VersionedStateGuard
import com.kmnexus.codexmeter.data.local.db.CodexMeterDatabase
import com.kmnexus.codexmeter.data.preferences.*
import com.kmnexus.codexmeter.data.repository.*
import com.kmnexus.codexmeter.data.secure.*
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
class Cm10R5MergeTest {
    @Test fun bothCommit()=exercise(false)
    @Test fun laterCommitFails()=exercise(true)
    private fun exercise(fail:Boolean)=runBlocking {
        withTimeout(15_000){
            val ctx:Context=RuntimeEnvironment.getApplication()
            val dir=File(ctx.filesDir,UUID.randomUUID().toString())
            val db=Room.inMemoryDatabaseBuilder(ctx,CodexMeterDatabase::class.java).allowMainThreadQueries().build()
            val prefs=CurrentAccountPreferences.create(File(dir,"prefs.preferences_pb"))
            val vault=FileSecureSessionStore(File(dir,"sessions"))
            val guard=VersionedStateGuard(); val boundary=NotificationTargetBoundary()
            val staged=CompletableDeferred<Unit>(); val release=CompletableDeferred<Unit>()
            val paused=object:SecureSessionStore by vault {
                override suspend fun save(envelope:ProviderSessionEnvelope) {
                    vault.save(envelope)
                    if(!staged.isCompleted) { staged.complete(Unit); release.await() }
                }
            }
            val first=RoomCodexSessionImportPersistence(db,paused,prefs,guard,beforeRoomCommit={if(fail) error("synthetic Room write failure")},notificationTargetBoundary=boundary)
            val second=RoomCodexSessionImportPersistence(db,vault,prefs,guard,notificationTargetBoundary=boundary)
            suspend fun add(store:RoomCodexSessionImportPersistence,id:String,marker:Int)=store.save(
                FixtureAccounts.account(localAccountId=id,displayName="Audit").copy(providerAccountId="same").toDomain(),
                ProviderSessionEnvelope("grok",id,"same",1,byteArrayOf(marker.toByte()),byteArrayOf(0),"fixture","fixture").copy(providerId="codex"),
                FixtureAccounts.snapshot(localAccountId=id,snapshotId=id).toDomain(),allowNewRow=true,expectedLocalAccountId=null)
            try {
                val slow=async(Dispatchers.IO){runCatching{add(first,"pending",1)}}
                staged.await()
                val winner=add(second,"winner",2)
                val id=winner.account.localAccountId.value
                check(vault.load("codex",id)?.payloadCiphertext?.first()?.toInt()==2)
                release.complete(Unit)
                check(slow.await().isFailure==fail)
                val marker=vault.load("codex",id)?.payloadCiphertext?.first()?.toInt()
                val row=db.providerAccountDao().getByProviderAccountId("codex","same")?.localAccountId
                println("injectedFailure=$fail existingRow=$row beforeMarker=2 afterMarker=$marker")
                check(row=="winner" && marker==(if(fail) 2 else 1)) { "Rollback removed the successful import's credential" }
            } finally { release.complete(Unit); db.close() }
        };Unit
    }
}
