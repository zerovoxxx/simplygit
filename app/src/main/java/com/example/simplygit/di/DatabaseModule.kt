package com.example.simplygit.di

import android.content.Context
import androidx.room.Room
import com.example.simplygit.data.sync.FileTreeCacheDao
import com.example.simplygit.data.sync.RepositoryDao
import com.example.simplygit.data.sync.SimplygitDatabase
import com.example.simplygit.data.sync.SyncLogDao
import com.example.simplygit.data.sync.SyncPolicyDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Wires the Room database and its DAOs (SPEC §4.8 Iteration 2 / §6.1 Iteration 3).
 *
 * `fallbackToDestructiveMigration(false)` is the default. Registered migrations:
 *  - v1 → v2 (SPEC §4.7 Iteration 2 / fix CR P3-02): add `sync_log.errorType`.
 *  - v2 → v3 (SPEC §6.1 Iteration 3): add `file_tree_cache` table and
 *    `repository.auth_type` column.
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext ctx: Context): SimplygitDatabase =
        Room.databaseBuilder(ctx, SimplygitDatabase::class.java, "simplygit.db")
            .addMigrations(
                SimplygitDatabase.MIGRATION_1_2,
                SimplygitDatabase.MIGRATION_2_3,
            )
            .build()

    @Provides
    @Singleton
    fun provideRepositoryDao(db: SimplygitDatabase): RepositoryDao = db.repositoryDao()

    @Provides
    @Singleton
    fun provideSyncPolicyDao(db: SimplygitDatabase): SyncPolicyDao = db.syncPolicyDao()

    @Provides
    @Singleton
    fun provideSyncLogDao(db: SimplygitDatabase): SyncLogDao = db.syncLogDao()

    @Provides
    @Singleton
    fun provideFileTreeCacheDao(db: SimplygitDatabase): FileTreeCacheDao =
        db.fileTreeCacheDao()
}
