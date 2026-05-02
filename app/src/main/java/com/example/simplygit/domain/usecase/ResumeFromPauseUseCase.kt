package com.example.simplygit.domain.usecase

import com.example.simplygit.domain.model.SyncState
import com.example.simplygit.domain.repository.RepoBindingRepository
import com.example.simplygit.domain.repository.SyncLogRepository
import javax.inject.Inject

/**
 * Clears the paused state back to [SyncState.IDLE] (SPEC §4.5 Iteration 2).
 * Wired to the "Resume sync" button in Home's SyncStateBanner; the UI is
 * expected to surface a confirmation dialog before invoking this.
 *
 * This is the single legal path out of `PAUSED_*` / `BROKEN`; the Worker
 * itself short-circuits while in those states (SPEC §4.2 state machine).
 */
class ResumeFromPauseUseCase @Inject constructor(
    private val bindingRepo: RepoBindingRepository,
    private val syncLogRepo: SyncLogRepository,
) {
    suspend operator fun invoke() {
        val binding = bindingRepo.currentOrNull() ?: return
        syncLogRepo.updateSyncState(binding.id, SyncState.IDLE)
    }
}
