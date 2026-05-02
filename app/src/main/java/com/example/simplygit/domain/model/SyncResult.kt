package com.example.simplygit.domain.model

/** Outcome persisted to the `sync_log` table for each run (SPEC §6.1 Iteration 2). */
enum class SyncResult {
    OK,
    CONFLICT,
    NETWORK_ERR,
    AUTH_ERR,
    FS_ERR,
    ABORTED,
    SKIPPED_DEBOUNCE,
    SKIPPED_PAUSED,
}
