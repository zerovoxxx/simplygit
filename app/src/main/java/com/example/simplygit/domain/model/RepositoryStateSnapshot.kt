package com.example.simplygit.domain.model

import java.time.Instant

/**
 * Projection of the per-repository state columns from `RepositoryEntity`
 * (SPEC §6.1 Iteration 2).
 *
 * Consumed by:
 *  - Home `SyncStateBanner` to pick colour / text / "Resume" button visibility;
 *  - `RunSyncUseCase` to short-circuit when `syncState in PAUSED_STATES`.
 */
data class RepositoryStateSnapshot(
    val repoId: Long,
    val syncState: SyncState,
    val lastSyncAt: Instant?,
    val lastSyncResult: SyncResult?,
)
