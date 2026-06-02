package com.kmnexus.codexmeter.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EntitySafetyTest {
    @Test
    fun `room entities do not define token fields`() {
        val names = listOf(
            ProviderAccountEntity::class.java,
            QuotaSnapshotEntity::class.java,
        )
            .flatMap { entityClass -> entityClass.declaredFields.map { it.name.lowercase() } }
            .joinToString(" ")

        prohibitedFieldNames.forEach { prohibitedName ->
            assertFalse(
                "Room entities must not define sensitive field '$prohibitedName'",
                names.contains(prohibitedName),
            )
        }
    }

    @Test
    fun `provider account entity metadata matches schema contract`() {
        assertRoomEntityMetadata(
            entityClass = ProviderAccountEntity::class.java,
            expectedTableName = "provider_accounts",
            expectedIndices = listOf(
                ExpectedIndex(
                    columns = listOf("provider_id", "provider_account_id"),
                    unique = true,
                ),
                ExpectedIndex(
                    columns = listOf("provider_id", "local_account_id"),
                    unique = false,
                ),
            ),
            expectedPrimaryKeyField = "localAccountId",
            expectedColumnNames = providerAccountColumns,
        )
    }

    @Test
    fun `quota snapshot entity metadata matches schema contract`() {
        assertRoomEntityMetadata(
            entityClass = QuotaSnapshotEntity::class.java,
            expectedTableName = "quota_snapshots",
            expectedIndices = listOf(
                ExpectedIndex(
                    columns = listOf("provider_id", "local_account_id", "fetched_at"),
                    unique = false,
                ),
            ),
            expectedPrimaryKeyField = "snapshotId",
            expectedColumnNames = quotaSnapshotColumns,
        )
    }

    private fun assertRoomEntityMetadata(
        entityClass: Class<*>,
        expectedTableName: String,
        expectedIndices: List<ExpectedIndex>,
        expectedPrimaryKeyField: String,
        expectedColumnNames: Map<String, String>,
    ) {
        val metadata = ClassFileMetadata.read(entityClass)
        val entityAnnotation = metadata.requireClassAnnotation(Entity::class.java)

        assertEquals(expectedTableName, entityAnnotation.stringValue("tableName"))
        assertEquals(expectedIndices, entityAnnotation.indexValues())

        val fieldsByName = metadata.fields
            .filterNot { field -> field.name.startsWith("$") }
            .associateBy { field -> field.name }
        assertEquals(expectedColumnNames.keys, fieldsByName.keys)

        val primaryKeyField = fieldsByName.getValue(expectedPrimaryKeyField)
        assertTrue(
            "$expectedPrimaryKeyField must be annotated with @PrimaryKey",
            primaryKeyField.hasAnnotation(PrimaryKey::class.java),
        )

        val expectedColumns = expectedColumnNames.values.toSet()
        expectedColumnNames.forEach { (fieldName, columnName) ->
            val field = fieldsByName.getValue(fieldName)
            assertEquals(columnName, field.requireAnnotation(ColumnInfo::class.java).stringValue("name"))
        }

        fieldsByName.values.forEach { field ->
            val columnAnnotation = field.requireAnnotation(ColumnInfo::class.java)
            val columnName = columnAnnotation.stringValue("name")

            assertTrue(
                "${entityClass.simpleName}.${field.name} maps to unexpected Room column '$columnName'",
                columnName in expectedColumns,
            )
            prohibitedColumnNameFragments.forEach { prohibitedFragment ->
                assertFalse(
                    "${entityClass.simpleName}.${field.name} maps to sensitive Room column '$columnName'",
                    columnName.normalizedIdentifier().contains(prohibitedFragment),
                )
            }
        }
    }

    private fun AnnotationMetadata.indexValues(): List<ExpectedIndex> =
        annotationArray("indices", Index::class.java)
            .map { indexAnnotation ->
                ExpectedIndex(
                    columns = indexAnnotation.stringArray("value"),
                    unique = indexAnnotation.booleanValue("unique"),
                )
            }

    private fun AnnotationMetadata.booleanValue(name: String): Boolean =
        when (val value = values[name]) {
            null -> false
            is AnnotationValue.Other -> when (value.value) {
                "1", "true" -> true
                "0", "false" -> false
                else -> error("$descriptor has unsupported boolean value '$value' for '$name'")
            }
            else -> error("$descriptor has non-boolean value '$value' for '$name'")
        }

    private data class ExpectedIndex(
        val columns: List<String>,
        val unique: Boolean,
    )

    private companion object {
        val prohibitedFieldNames = listOf(
            "accesstoken",
            "refreshtoken",
            "idtoken",
            "cookie",
            "authcode",
            "authjson",
            "rawresponse",
            "rawcallback",
        )

        val prohibitedColumnNameFragments = listOf(
            "accesstoken",
            "refreshtoken",
            "idtoken",
            "cookie",
            "authcode",
            "authjson",
            "rawresponse",
            "rawcallback",
            "oauthcallback",
            "apiresponsebody",
        )

        val providerAccountColumns = linkedMapOf(
            "localAccountId" to "local_account_id",
            "providerId" to "provider_id",
            "providerAccountId" to "provider_account_id",
            "displayName" to "display_name",
            "avatarInitial" to "avatar_initial",
            "avatarColorKey" to "avatar_color_key",
            "status" to "status",
            "createdAt" to "created_at",
            "updatedAt" to "updated_at",
            "lastSuccessfulRefreshAt" to "last_successful_refresh_at",
        )

        val quotaSnapshotColumns = linkedMapOf(
            "snapshotId" to "snapshot_id",
            "providerId" to "provider_id",
            "localAccountId" to "local_account_id",
            "providerAccountId" to "provider_account_id",
            "fetchedAt" to "fetched_at",
            "source" to "source",
            "planType" to "plan_type",
            "windowsJson" to "windows_json",
            "creditsJson" to "credits_json",
            "responseDigest" to "response_digest",
        )
    }
}

private fun String.normalizedIdentifier(): String =
    lowercase().filter { character -> character.isLetterOrDigit() }
