package com.example.simplygit.domain.service

import com.example.simplygit.domain.model.SyncPolicyModel

/**
 * Scheduler abstraction over WorkManager (SPEC §6.2 Iteration 2).
 *
 * The only concrete implementation lives in the `runtime` package; domain-layer
 * callers (UseCases / ViewModels) depend on this interface so unit tests can
 * inject a fake without pulling in WorkManager's internals.
 */
interface SyncScheduler {
    /**
     * Installs / replaces the periodic worker request according to [policy].
     * `MANUAL_ONLY` cancels the request. Calls use
     * `ExistingPeriodicWorkPolicy.UPDATE` so in-flight runs finish the current
     * period before the new constraints apply (SPEC G2).
     */
    fun schedulePeriodic(policy: SyncPolicyModel)

    /** Enqueues a one-shot catch-up request (SPEC §4.4 Iteration 2). */
    fun triggerCatchUpOnce()

    fun cancelAll()
}
