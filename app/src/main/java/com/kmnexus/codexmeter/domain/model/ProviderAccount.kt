package com.kmnexus.codexmeter.domain.model

import androidx.annotation.DrawableRes
import java.time.Instant

enum class AccountStatus { Active, NeedsReauth, Disabled, Deleted }

data class ProviderAccount(
    val localAccountId: LocalAccountId,
    val providerId: ProviderId,
    val providerAccountId: ProviderAccountId?,
    val displayName: String,
    val avatarInitial: String,
    val avatarColorKey: String,
    val status: AccountStatus,
    val createdAt: Instant,
    val updatedAt: Instant,
    val lastSuccessfulRefreshAt: Instant?,
    @param:DrawableRes val providerIconResId: Int? = null,
) {
    companion object {
        private const val ZERO_WIDTH_JOINER = 0x200D

        fun createNew(
            localAccountId: LocalAccountId,
            providerId: ProviderId,
            providerAccountId: ProviderAccountId?,
            displayName: String,
            now: Instant,
        ): ProviderAccount {
            val safeName = displayName.trim()
            require(safeName.isNotEmpty()) { "displayName must not be blank." }
            return ProviderAccount(
                localAccountId = localAccountId,
                providerId = providerId,
                providerAccountId = providerAccountId,
                displayName = safeName,
                avatarInitial = safeName.firstDisplayCharacter(),
                avatarColorKey = localAccountId.value,
                status = AccountStatus.Active,
                createdAt = now,
                updatedAt = now,
                lastSuccessfulRefreshAt = null,
            )
        }

        private fun String.firstDisplayCharacter(): String {
            val firstCodePoint = codePointAt(0)
            var end = Character.charCount(firstCodePoint)
            end = indexAfterGraphemeExtenders(end)

            if (firstCodePoint.isRegionalIndicator()) {
                if (end < length) {
                    val nextCodePoint = codePointAt(end)
                    if (nextCodePoint.isRegionalIndicator()) {
                        end += Character.charCount(nextCodePoint)
                        end = indexAfterGraphemeExtenders(end)
                    }
                }
                return substring(0, end)
            }

            while (end < length && codePointAt(end) == ZERO_WIDTH_JOINER) {
                end += Character.charCount(ZERO_WIDTH_JOINER)
                if (end >= length) {
                    return substring(0, end)
                }
                end += Character.charCount(codePointAt(end))
                end = indexAfterGraphemeExtenders(end)
            }

            return substring(0, end)
        }

        private fun String.indexAfterGraphemeExtenders(start: Int): Int {
            var end = start
            while (end < length) {
                val codePoint = codePointAt(end)
                if (!codePoint.isGraphemeExtender()) {
                    return end
                }
                end += Character.charCount(codePoint)
            }
            return end
        }

        private fun Int.isGraphemeExtender(): Boolean =
            isCombiningMark() || isEmojiModifier() || isVariationSelector()

        private fun Int.isCombiningMark(): Boolean {
            val type = Character.getType(this)
            return type == Character.NON_SPACING_MARK.toInt() ||
                type == Character.COMBINING_SPACING_MARK.toInt() ||
                type == Character.ENCLOSING_MARK.toInt()
        }

        private fun Int.isEmojiModifier(): Boolean = this in 0x1F3FB..0x1F3FF

        private fun Int.isRegionalIndicator(): Boolean = this in 0x1F1E6..0x1F1FF

        private fun Int.isVariationSelector(): Boolean =
            this in 0xFE00..0xFE0F || this in 0xE0100..0xE01EF
    }

    fun renamedTo(displayName: String, updatedAt: Instant): ProviderAccount {
        val renamed = createNew(
            localAccountId = localAccountId,
            providerId = providerId,
            providerAccountId = providerAccountId,
            displayName = displayName,
            now = updatedAt,
        )
        return renamed.copy(
            avatarColorKey = avatarColorKey,
            status = status,
            createdAt = createdAt,
            lastSuccessfulRefreshAt = lastSuccessfulRefreshAt,
            providerIconResId = providerIconResId,
        )
    }
}
