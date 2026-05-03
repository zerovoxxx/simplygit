package com.example.simplygit.domain.model

/**
 * Outcome persisted to the `sync_log` table for each run (SPEC §6.1
 * Iteration 2, §4.3.1 Iteration 3).
 *
 * Iteration 3 (SPEC §4.3.1) adds [CONFLICT_RESOLVED] for audit rows produced
 * by `ClearConflictPauseUseCase`. The column is `TEXT` so no DB migration is
 * required — the enum `.name` string just becomes a new legal value.
 */
enum class SyncResult {
    OK,
    CONFLICT,

    /**
     * Conflict was resolved by the user inside the app (integer/ours pick +
     * local commit + push). Surfaces as "✓ 冲突已解决" in the audit screen.
     */
    CONFLICT_RESOLVED,

    NETWORK_ERR,
    AUTH_ERR,
    FS_ERR,
    ABORTED,
    SKIPPED_DEBOUNCE,
    SKIPPED_PAUSED,
}
