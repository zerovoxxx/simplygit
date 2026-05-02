package com.example.simplygit.di

import android.content.Context
import androidx.room.Room
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
 * Wires the Room database and its DAOs (SPEC §4.8 Iteration 2).
 *
 * `fallbackToDestructiveMigration(false)` is the default. The v1 → v2
 * migration (add `sync_log.errorType`, SPEC §4.7 fix CR P3-02) is
 * registered explicitly so existing installs keep their audit rows.
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext ctx: Context): SimplygitDatabase =
        Room.databaseBuilder(ctx, SimplygitDatabase::class.java, "simplygit.db")
            .addMigrations(SimplygitDatabase.MIGRATION_1_2)
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
}
