package com.example.simplygit.data.sync

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room database hosting Iteration 2's binding + policy + audit tables
 * (SPEC §4.6 Iteration 2).
 *
 * - v1: initial schema (Iteration 2 release).
 * - v2 (SPEC §4.7 Iteration 2 / fix CR P3-02): adds `sync_log.errorType`
 *   column so the Audit detail screen can show the original Throwable
 *   class name alongside the sanitized message.
 *
 * Iteration 1's binding lived in DataStore Preferences; the one-shot
 * migration into this database is handled lazily in
 * [com.example.simplygit.data.binding.RepoBindingRepositoryImpl].
 */
@Database(
    entities = [
        RepositoryEntity::class,
        SyncPolicyEntity::class,
        SyncLogEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class SimplygitDatabase : RoomDatabase() {
    abstract fun repositoryDao(): RepositoryDao
    abstract fun syncPolicyDao(): SyncPolicyDao
    abstract fun syncLogDao(): SyncLogDao

    companion object {
        /**
         * v1 → v2: add `sync_log.errorType TEXT` (nullable, default null).
         * Existing rows keep `errorType = null` — UI hides the row when null,
         * so legacy rows render exactly as before.
         */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sync_log ADD COLUMN errorType TEXT")
            }
        }
    }
}
