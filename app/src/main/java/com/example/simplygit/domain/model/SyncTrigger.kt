package com.example.simplygit.domain.model

/**
 * Triggers that can invoke a sync run (SPEC §4.1 / §6.3 Iteration 2).
 *
 * - [PERIODIC]: enqueued via `PeriodicWorkRequest`.
 * - [CATCHUP]: enqueued via `OneTimeWorkRequest` on cold start when `lastSyncAt`
 *   is stale (> interval * 2).
 * - [MANUAL]: reserved for future manual triggers; not used by the Worker in
 *   Iteration 2 (Home's four manual buttons bypass RunSyncUseCase per I-7).
 */
enum class SyncTrigger { PERIODIC, CATCHUP, MANUAL }
