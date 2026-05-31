package com.example.simplygit.data.sync

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room database hosting binding + policy + audit + file-tree tables
 * (SPEC §4.6 Iteration 2 / §4.1.1 + §6.1 Iteration 3).
 *
 * Version history:
 *  - v1: initial schema (Iteration 2 release).
 *  - v2 (SPEC §4.7 Iteration 2 / fix CR P3-02): adds `sync_log.errorType`.
 *  - v3 (SPEC §6.1 Iteration 3): adds `file_tree_cache` table **and** an
 *    `auth_type` column on `repository` so SSH-authenticated repos can
 *    coexist with PAT-authenticated ones (default `'PAT'` keeps legacy rows
 *    valid).
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
        FileTreeCacheEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class SimplygitDatabase : RoomDatabase() {
    abstract fun repositoryDao(): RepositoryDao
    abstract fun syncPolicyDao(): SyncPolicyDao
    abstract fun syncLogDao(): SyncLogDao
    abstract fun fileTreeCacheDao(): FileTreeCacheDao

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

        /**
         * v2 → v3 (SPEC §6.1 Iteration 3):
         *  (a) create `file_tree_cache` table + indices;
         *  (b) add `repository.auth_type TEXT NOT NULL DEFAULT 'PAT'` so
         *      existing PAT-bound rows keep working without code changes.
         */
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `file_tree_cache` (
                      `repo_id` INTEGER NOT NULL,
                      `path` TEXT NOT NULL,
                      `parent_path` TEXT NOT NULL,
                      `type` TEXT NOT NULL,
                      `git_status` TEXT NOT NULL,
                      `size` INTEGER NOT NULL,
                      `last_modified` INTEGER NOT NULL,
                      PRIMARY KEY(`repo_id`, `path`),
                      FOREIGN KEY(`repo_id`) REFERENCES `repository`(`id`) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_file_tree_cache_repo_id` " +
                        "ON `file_tree_cache` (`repo_id`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_file_tree_cache_repo_id_parent_path` " +
                        "ON `file_tree_cache` (`repo_id`, `parent_path`)"
                )
                db.execSQL(
                    "ALTER TABLE `repository` ADD COLUMN `auth_type` TEXT NOT NULL DEFAULT 'PAT'"
                )
            }
        }
    }
}
