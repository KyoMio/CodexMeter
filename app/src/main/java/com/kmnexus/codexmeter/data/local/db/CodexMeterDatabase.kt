package com.kmnexus.codexmeter.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.kmnexus.codexmeter.data.local.dao.AlertStateDao
import com.kmnexus.codexmeter.data.local.dao.ProviderAccountDao
import com.kmnexus.codexmeter.data.local.dao.QuotaSnapshotDao
import com.kmnexus.codexmeter.data.local.dao.RefreshAttemptDao
import com.kmnexus.codexmeter.data.local.entity.AlertStateEntity
import com.kmnexus.codexmeter.data.local.entity.ProviderAccountEntity
import com.kmnexus.codexmeter.data.local.entity.QuotaSnapshotEntity
import com.kmnexus.codexmeter.data.local.entity.RefreshAttemptEntity

const val CODEXMETER_DB_SCHEMA_VERSION = 1

@Database(
    entities = [
        ProviderAccountEntity::class,
        QuotaSnapshotEntity::class,
        RefreshAttemptEntity::class,
        AlertStateEntity::class,
    ],
    version = CODEXMETER_DB_SCHEMA_VERSION,
    exportSchema = true,
)
abstract class CodexMeterDatabase : RoomDatabase() {
    abstract fun providerAccountDao(): ProviderAccountDao

    abstract fun quotaSnapshotDao(): QuotaSnapshotDao

    abstract fun refreshAttemptDao(): RefreshAttemptDao

    abstract fun alertStateDao(): AlertStateDao

    companion object {
        const val DATABASE_NAME = "codexmeter.db"
    }
}
