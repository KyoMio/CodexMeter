package com.kmnexus.codexmeter.app

import androidx.room.Room
import com.kmnexus.codexmeter.data.local.db.CodexMeterDatabase
import com.kmnexus.codexmeter.data.local.entity.ProviderAccountEntity
import com.kmnexus.codexmeter.data.preferences.CurrentAccountReader
import com.kmnexus.codexmeter.data.preferences.CurrentAccountSelection
import com.kmnexus.codexmeter.domain.model.LocalAccountId
import com.kmnexus.codexmeter.domain.model.ProviderId
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
// Robolectric SDK 36 currently requires Java 21, while this project is pinned to Java 17.
@Config(sdk = [35])
class CurrentQuotaRefreshAccountStoreTest {
    @Test
    fun `active accounts returns every active codex account`() = runTest {
        withStore { db, store ->
            db.providerAccountDao().upsert(account("local-1", "codex", "active"))
            db.providerAccountDao().upsert(account("local-2", "codex", "active"))

            val accounts = store.activeAccounts()

            assertEquals(listOf("local-1", "local-2"), accounts.map { it.localAccountId.value })
        }
    }

    @Test
    fun `active accounts span every provider and skip reauth disabled deleted`() = runTest {
        withStore { db, store ->
            db.providerAccountDao().upsert(account("local-active", "codex", "active"))
            db.providerAccountDao().upsert(account("local-reauth", "codex", "needs_reauth"))
            db.providerAccountDao().upsert(account("local-disabled", "codex", "disabled"))
            db.providerAccountDao().upsert(account("local-deleted", "codex", "deleted"))
            db.providerAccountDao().upsert(account("local-deepseek", "deepseek", "active"))
            db.providerAccountDao().upsert(account("local-claude", "claude", "active"))

            val accounts = store.activeAccounts()

            // Background refresh covers every provider's Active accounts, not just Codex.
            assertEquals(
                setOf("local-active", "local-deepseek", "local-claude"),
                accounts.map { it.localAccountId.value }.toSet(),
            )
        }
    }

    private suspend fun withStore(
        block: suspend (CodexMeterDatabase, CurrentQuotaRefreshAccountStore) -> Unit,
    ) {
        val db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            CodexMeterDatabase::class.java,
        ).build()
        val store = CurrentQuotaRefreshAccountStore(
            currentAccountReader = object : CurrentAccountReader {
                override suspend fun currentAccountSelection(): CurrentAccountSelection? =
                    CurrentAccountSelection(ProviderId("codex"), LocalAccountId("local-active"))
            },
            providerAccountDao = db.providerAccountDao(),
        )

        try {
            block(db, store)
        } finally {
            db.close()
        }
    }

    private fun account(
        localAccountId: String,
        providerId: String,
        status: String,
    ): ProviderAccountEntity =
        ProviderAccountEntity(
            localAccountId = localAccountId,
            providerId = providerId,
            providerAccountId = "acct-$localAccountId",
            displayName = localAccountId,
            avatarInitial = localAccountId.first().uppercase(),
            avatarColorKey = localAccountId,
            status = status,
            createdAt = Instant.parse("2026-05-23T11:00:00Z").toEpochMilli(),
            updatedAt = Instant.parse("2026-05-23T11:30:00Z").toEpochMilli(),
            lastSuccessfulRefreshAt = null,
        )
}
