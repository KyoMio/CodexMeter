package com.kmnexus.codexmeter.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertStateEntityTest {
    @Test
    fun `alert dedupe key includes account window reset and threshold`() {
        val entity = AlertStateEntity(
            alertStateId = "codex-local-five-2026-05-22-90",
            providerId = "codex",
            localAccountId = "local",
            windowId = "five_hour",
            threshold = 90.0,
            resetAt = "2026-05-22T05:00:00Z",
            lastNotifiedAt = "2026-05-22T01:00:00Z",
        )

        assertEquals("codex", entity.providerId)
        assertEquals("local", entity.localAccountId)
        assertEquals("five_hour", entity.windowId)
        assertEquals("2026-05-22T05:00:00Z", entity.resetAt)
        assertEquals(90.0, entity.threshold, 0.0)
    }

    @Test
    fun `alert state room metadata matches schema contract`() {
        assertRoomEntityMetadata(
            entityClass = AlertStateEntity::class.java,
            expectedTableName = "alert_states",
            expectedIndices = listOf(
                ExpectedIndex(
                    columns = listOf(
                        "provider_id",
                        "local_account_id",
                        "window_id",
                        "reset_at",
                        "threshold",
                    ),
                    unique = true,
                ),
            ),
            expectedPrimaryKeyField = "alertStateId",
            expectedColumnNames = alertStateColumns,
        )
    }

    @Test
    fun `refresh attempt room metadata matches schema contract`() {
        assertRoomEntityMetadata(
            entityClass = RefreshAttemptEntity::class.java,
            expectedTableName = "refresh_attempts",
            expectedIndices = listOf(
                ExpectedIndex(
                    columns = listOf("provider_id", "local_account_id", "started_at"),
                    unique = false,
                ),
            ),
            expectedPrimaryKeyField = "attemptId",
            expectedColumnNames = refreshAttemptColumns,
        )
    }

    @Test
    fun `refresh attempt and alert state entities do not define sensitive fields or columns`() {
        listOf(
            RefreshAttemptEntity::class.java,
            AlertStateEntity::class.java,
        ).forEach { entityClass ->
            val metadata = ClassFileMetadata.read(entityClass)
            val fieldsByName = metadata.schemaFieldsByName()

            fieldsByName.values.forEach { field ->
                assertNoSensitiveIdentifier(entityClass.simpleName, field.name)

                val columnName = field.requireAnnotation(ColumnInfo::class.java).stringValue("name")
                assertNoSensitiveIdentifier(entityClass.simpleName, columnName)
            }
        }
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
        assertEquals(expectedIndices, entityAnnotation.indices())

        val fieldsByName = metadata.schemaFieldsByName()
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
            val columnName = field.requireAnnotation(ColumnInfo::class.java).stringValue("name")
            assertTrue(
                "${entityClass.simpleName}.${field.name} maps to unexpected Room column '$columnName'",
                columnName in expectedColumns,
            )
        }
    }

    private fun ClassFileMetadata.schemaFieldsByName(): Map<String, FieldMetadata> =
        fields
            .filterNot { field -> field.name.startsWith("$") }
            .associateBy { field -> field.name }

    private fun AnnotationMetadata.indices(): List<ExpectedIndex> =
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

    private fun assertNoSensitiveIdentifier(entityName: String, identifier: String) {
        val normalized = identifier.normalizedIdentifier()
        prohibitedIdentifierFragments.forEach { prohibitedFragment ->
            assertFalse(
                "$entityName exposes sensitive identifier '$identifier'",
                normalized.contains(prohibitedFragment),
            )
        }
    }

    private data class ExpectedIndex(
        val columns: List<String>,
        val unique: Boolean,
    )

    private companion object {
        val prohibitedIdentifierFragments = listOf(
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

        val refreshAttemptColumns = linkedMapOf(
            "attemptId" to "attempt_id",
            "providerId" to "provider_id",
            "localAccountId" to "local_account_id",
            "trigger" to "trigger",
            "startedAt" to "started_at",
            "finishedAt" to "finished_at",
            "status" to "status",
            "errorCode" to "error_code",
            "httpStatus" to "http_status",
            "retryable" to "retryable",
            "userActionRequired" to "user_action_required",
            "diagnosticsDigest" to "diagnostics_digest",
        )

        val alertStateColumns = linkedMapOf(
            "alertStateId" to "alert_state_id",
            "providerId" to "provider_id",
            "localAccountId" to "local_account_id",
            "windowId" to "window_id",
            "threshold" to "threshold",
            "resetAt" to "reset_at",
            "lastNotifiedAt" to "last_notified_at",
        )
    }
}

private fun String.normalizedIdentifier(): String =
    lowercase().filter { character -> character.isLetterOrDigit() }
