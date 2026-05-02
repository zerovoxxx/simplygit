package com.example.simplygit.domain.usecase

import com.example.simplygit.domain.model.SyncPolicyModel
import com.example.simplygit.domain.repository.SyncPolicyRepository
import com.example.simplygit.domain.service.SyncScheduler
import javax.inject.Inject

/**
 * Updates the [SyncPolicyModel] and synchronises the WorkManager schedule
 * (SPEC §4.7 / §6.3 Iteration 2). Uses `ExistingPeriodicWorkPolicy.UPDATE`
 * inside the scheduler so the in-flight run finishes its current cycle before
 * the new constraints apply.
 */
class UpdateSyncPolicyUseCase @Inject constructor(
    private val policyRepo: SyncPolicyRepository,
    private val scheduler: SyncScheduler,
) {
    suspend operator fun invoke(policy: SyncPolicyModel) {
        require(policy.intervalMinutes in SyncPolicyModel.VALID_INTERVALS) {
            "intervalMinutes ${policy.intervalMinutes} is not one of ${SyncPolicyModel.VALID_INTERVALS}"
        }
        policyRepo.update(policy)
        scheduler.schedulePeriodic(policy)
    }
}
