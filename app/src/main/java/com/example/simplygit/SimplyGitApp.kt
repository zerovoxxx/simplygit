package com.example.simplygit

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.example.simplygit.domain.repository.RepoBindingRepository
import com.example.simplygit.notification.NotificationChannels
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Application entry point (SPEC §4.4 / §4.6 / §4.8 Iteration 2).
 *
 * Implements [Configuration.Provider] so `@HiltWorker` classes can be
 * instantiated with their injected dependencies. The default
 * `WorkManagerInitializer` Startup provider is removed in the manifest; this
 * class takes over via the on-demand initialisation path.
 *
 * Cold-start responsibilities:
 *  - Create notification channels (idempotent, cheap).
 *  - Kick off the DataStore → Room migration asynchronously (SPEC §4.6
 *    step 1 / fix CR P2-01). The scope uses a [SupervisorJob] so a failure
 *    in migration never takes the process down; failures bump a retry
 *    counter inside [RepoBindingRepository.migrateFromDataStoreIfNeeded].
 */
@HiltAndroidApp
class SimplyGitApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Inject lateinit var repoBindingRepository: RepoBindingRepository

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(Log.INFO)
            .build()

    override fun onCreate() {
        super.onCreate()
        NotificationChannels.createAll(this)
        // SPEC §4.6 Iteration 2 (fix CR P2-01): run migration off the main
        // thread so the first frame is never blocked. The repository
        // implementation is idempotent and guards against concurrent callers
        // (e.g. CatchUpTrigger invoking `currentOrNull()` during startup).
        appScope.launch { runCatching { repoBindingRepository.migrateFromDataStoreIfNeeded() } }
    }
}
