package com.kmnexus.codexmeter.domain.model

import com.kmnexus.codexmeter.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Instant

class ProviderAccountTest {
    @Test
    fun `default avatar initial uses first display character`() {
        val account = ProviderAccount.createNew(
            localAccountId = LocalAccountId("local-1"),
            providerId = ProviderId("codex"),
            providerAccountId = ProviderAccountId("acct-1"),
            displayName = "翔哥",
            now = Instant.parse("2026-05-22T00:00:00Z"),
        )

        assertEquals("翔", account.avatarInitial)
        assertEquals(AccountStatus.Active, account.status)
    }

    @Test
    fun `display name is trimmed`() {
        val account = createAccount(displayName = "  Codex Main  ")

        assertEquals("Codex Main", account.displayName)
        assertEquals("C", account.avatarInitial)
    }

    @Test
    fun `blank display name is rejected so callers provide localized fallback`() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            createAccount(displayName = "   ")
        }

        assertEquals("displayName must not be blank.", exception.message)
    }

    @Test
    fun `timestamps use creation instant`() {
        val now = Instant.parse("2026-05-22T01:02:03Z")
        val account = createAccount(now = now)

        assertEquals(now, account.createdAt)
        assertEquals(now, account.updatedAt)
    }

    @Test
    fun `last successful refresh starts empty`() {
        val account = createAccount()

        assertNull(account.lastSuccessfulRefreshAt)
    }

    @Test
    fun `renaming trims display name recomputes avatar and advances updated time`() {
        val account = createAccount()
        val renamedAt = Instant.parse("2026-05-23T12:00:00Z")

        val renamed = account.renamedTo("  Work Codex  ", renamedAt)

        assertEquals("Work Codex", renamed.displayName)
        assertEquals("W", renamed.avatarInitial)
        assertEquals(account.localAccountId, renamed.localAccountId)
        assertEquals(account.providerId, renamed.providerId)
        assertEquals(account.providerAccountId, renamed.providerAccountId)
        assertEquals(account.avatarColorKey, renamed.avatarColorKey)
        assertEquals(account.status, renamed.status)
        assertEquals(account.createdAt, renamed.createdAt)
        assertEquals(account.lastSuccessfulRefreshAt, renamed.lastSuccessfulRefreshAt)
        assertEquals(renamedAt, renamed.updatedAt)
    }

    @Test
    fun `avatar initial preserves complete emoji display character`() {
        val account = createAccount(displayName = "😀 Dev")

        assertEquals("😀", account.avatarInitial)
    }

    @Test
    fun `avatar initial preserves emoji zwj sequence`() {
        val account = createAccount(displayName = "👩‍💻 Dev")

        assertEquals("👩‍💻", account.avatarInitial)
    }

    @Test
    fun `avatar initial preserves regional indicator flag pair`() {
        val account = createAccount(displayName = "🇺🇸 Flag")

        assertEquals("🇺🇸", account.avatarInitial)
    }

    @Test
    fun providerAccount_providerIconResId_carriesIcon() {
        val account = ProviderAccount.createNew(
            localAccountId = LocalAccountId("acc_1"),
            providerId = ProviderId("deepseek"),
            providerAccountId = null,
            displayName = "Test Account",
            now = Instant.EPOCH,
        ).copy(providerIconResId = R.drawable.ic_brand_deepseek)
        assertEquals(R.drawable.ic_brand_deepseek, account.providerIconResId)
    }

    private fun createAccount(
        displayName: String = "Codex Main",
        now: Instant = Instant.parse("2026-05-22T00:00:00Z"),
    ): ProviderAccount =
        ProviderAccount.createNew(
            localAccountId = LocalAccountId("local-1"),
            providerId = ProviderId("codex"),
            providerAccountId = ProviderAccountId("acct-1"),
            displayName = displayName,
            now = now,
        )
}
