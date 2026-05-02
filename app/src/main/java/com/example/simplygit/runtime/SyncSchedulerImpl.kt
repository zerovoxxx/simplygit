package com.example.simplygit.runtime

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.simplygit.domain.model.SyncPolicyModel
import com.example.simplygit.domain.model.SyncTrigger
import com.example.simplygit.domain.service.SyncScheduler
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WorkManager-backed [SyncScheduler] (SPEC §4.4 Iteration 2).
 *
 *  - `ExistingPeriodicWorkPolicy.UPDATE` lets the in-flight run finish the
 *    current cycle before new constraints apply (SPEC G2).
 *  - `BackoffPolicy.EXPONENTIAL` starting at 30s covers transient network
 *    failures; WM caps retries at 5h internally.
 *  - `setExpedited` is NOT used — expedited quotas are precious and the
 *    silent-sync path is inherently deferrable (SPEC §4.4 Doze fallback).
 */
@Singleton
class SyncSchedulerImpl @Inject constructor(
    private val wm: WorkManager,
) : SyncScheduler {

    override fun schedulePeriodic(policy: SyncPolicyModel) {
        if (policy.intervalMinutes == SyncPolicyModel.MANUAL_ONLY) {
            wm.cancelUniqueWork(WorkTags.UNIQUE_PERIODIC)
            return
        }

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(
                if (policy.requireUnmetered) NetworkType.UNMETERED else NetworkType.CONNECTED,
            )
            .setRequiresBatteryNotLow(true)
            .setRequiresStorageNotLow(true)
            .apply { if (policy.requireCharging) setRequiresCharging(true) }
            .build()

        val request = PeriodicWorkRequestBuilder<GitSyncWorker>(
            policy.intervalMinutes.toLong(),
            TimeUnit.MINUTES,
        )
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_INITIAL_SECS, TimeUnit.SECONDS)
            .setInputData(workDataOf(WorkTags.KEY_TRIGGER to SyncTrigger.PERIODIC.name))
            .build()

        wm.enqueueUniquePeriodicWork(
            WorkTags.UNIQUE_PERIODIC,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    override fun triggerCatchUpOnce() {
        val request = OneTimeWorkRequestBuilder<GitSyncWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setInputData(workDataOf(WorkTags.KEY_TRIGGER to SyncTrigger.CATCHUP.name))
            .build()
        wm.enqueueUniqueWork(
            WorkTags.UNIQUE_CATCHUP,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    override fun cancelAll() {
        wm.cancelUniqueWork(WorkTags.UNIQUE_PERIODIC)
        wm.cancelUniqueWork(WorkTags.UNIQUE_CATCHUP)
    }

    private companion object {
        const val BACKOFF_INITIAL_SECS: Long = 30
    }
}
