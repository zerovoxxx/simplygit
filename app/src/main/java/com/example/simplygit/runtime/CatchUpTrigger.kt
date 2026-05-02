package com.example.simplygit.runtime

import com.example.simplygit.domain.model.SyncPolicyModel
import com.example.simplygit.domain.repository.RepoBindingRepository
import com.example.simplygit.domain.repository.SyncLogRepository
import com.example.simplygit.domain.repository.SyncPolicyRepository
import com.example.simplygit.domain.service.SyncScheduler
import java.time.Clock
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cold-start catch-up trigger (SPEC §4.4 / G8 Iteration 2).
 *
 * Called from `MainActivity.onCreate`. Enqueues a one-shot
 * `GitSyncWorker` run when `lastSyncAt` is older than
 * `intervalMinutes * 2` — i.e. the device was Doze-locked long enough to miss
 * at least one full cycle.
 *
 * The call is async / fire-and-forget; the `UNIQUE_CATCHUP` queue uses
 * `ExistingWorkPolicy.KEEP` so double-invocations from rapid Activity recreate
 * are harmless.
 */
@Singleton
class CatchUpTrigger @Inject constructor(
    private val bindingRepo: RepoBindingRepository,
    private val policyRepo: SyncPolicyRepository,
    private val logRepo: SyncLogRepository,
    private val scheduler: SyncScheduler,
    private val clock: Clock,
) {

    suspend fun triggerIfStale() {
        val binding = bindingRepo.currentOrNull() ?: return
        val policy = policyRepo.current()
        if (policy.intervalMinutes == SyncPolicyModel.MANUAL_ONLY) return
        val state = logRepo.loadRepoState(binding.id)
        val lastSyncAt = state.lastSyncAt ?: return
        val threshold = Duration.ofMinutes(policy.intervalMinutes.toLong() * STALE_MULTIPLIER)
        val age = Duration.between(lastSyncAt, clock.instant())
        if (age > threshold) {
            scheduler.triggerCatchUpOnce()
        }
    }

    private companion object {
        const val STALE_MULTIPLIER: Long = 2
    }
}
