package com.example.simplygit.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.time.Clock
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultDispatcher

/**
 * SPEC R7 / §4.4.1 Iteration 3: an Application-scoped [CoroutineScope] for
 * deferred safe-cleanup work (e.g. SSH passphrase TTL) that must NOT be tied
 * to UI lifecycle.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

/**
 * Provides coroutine dispatchers (SPEC §4.4: all JGit calls must run on [IoDispatcher])
 * and a shared [Clock] instance (SPEC §4.3 / §4.9.1 Iteration 2: used by
 * [com.example.simplygit.domain.usecase.RunSyncUseCase],
 * [com.example.simplygit.data.diagnostics.DiagnosticsLogger] and
 * [com.example.simplygit.runtime.CatchUpTrigger] so unit tests can swap in a
 * deterministic clock).
 */
@Module
@InstallIn(SingletonComponent::class)
object DispatcherModule {

    @Provides
    @Singleton
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @Singleton
    @DefaultDispatcher
    fun provideDefaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

    @Provides
    @Singleton
    fun provideClock(): Clock = Clock.systemUTC()

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
