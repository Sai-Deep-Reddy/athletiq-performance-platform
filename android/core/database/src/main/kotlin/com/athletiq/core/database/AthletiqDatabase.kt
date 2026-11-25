package com.athletiq.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [
        StudentEntity::class,
        CalibrationProfileEntity::class,
        ExaminationEntity::class,
        AttemptEntity::class,
        ScoringStandardEntity::class,
        SyncOutboxEntity::class,
        LocalAuditEventEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AthletiqDatabase : RoomDatabase() {
    abstract fun studentDao(): StudentDao
    abstract fun calibrationDao(): CalibrationDao
    abstract fun examinationDao(): ExaminationDao
    abstract fun attemptDao(): AttemptDao
    abstract fun scoringStandardDao(): ScoringStandardDao
    abstract fun syncOutboxDao(): SyncOutboxDao
    abstract fun localAuditDao(): LocalAuditDao

    suspend fun recordAttemptWithOutboxAndAudit(
        attempt: AttemptEntity,
        outboxItem: SyncOutboxEntity,
        auditEvent: LocalAuditEventEntity,
    ) {
        withTransaction {
            attemptDao().insertIfAbsent(attempt)
            syncOutboxDao().enqueue(outboxItem)
            localAuditDao().append(auditEvent)
        }
    }

    companion object {
        @Volatile
        private var instance: AthletiqDatabase? = null

        fun getInstance(context: Context): AthletiqDatabase {
            return instance ?: synchronized(this) {
                instance ?: buildEncryptedDatabase(context.applicationContext).also { instance = it }
            }
        }

        private fun buildEncryptedDatabase(context: Context): AthletiqDatabase {
            System.loadLibrary("sqlcipher")
            val rawPassphrase = SecureKeyManager.getOrCreateDatabasePassphrase(context)
            val factory = try {
                SupportOpenHelperFactory(rawPassphrase)
            } finally {
                rawPassphrase.fill(0)
            }

            return Room.databaseBuilder(
                context,
                AthletiqDatabase::class.java,
                "athletiq_encrypted.db",
            )
                .openHelperFactory(factory)
                .build()
        }
    }
}
