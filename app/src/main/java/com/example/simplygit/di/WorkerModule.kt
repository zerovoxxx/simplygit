package com.example.simplygit.di

import android.content.Context
import androidx.work.WorkManager
import com.example.simplygit.domain.service.SyncScheduler
import com.example.simplygit.runtime.SyncSchedulerImpl
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Provides WorkManager (SPEC §4.8 Iteration 2). */
@Module
@InstallIn(SingletonComponent::class)
object WorkerModule {

    @Provides
    @Singleton
    fun provideWorkManager(@ApplicationContext ctx: Context): WorkManager =
        WorkManager.getInstance(ctx)
}

/** Binds SyncScheduler → SyncSchedulerImpl (SPEC §4.8 Iteration 2). */
@Module
@InstallIn(SingletonComponent::class)
abstract class SyncSchedulerBindsModule {

    @Binds
    @Singleton
    abstract fun bindSyncScheduler(impl: SyncSchedulerImpl): SyncScheduler
}
