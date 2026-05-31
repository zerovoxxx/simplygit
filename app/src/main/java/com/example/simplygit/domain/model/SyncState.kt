package com.example.simplygit.domain.model

/**
 * Sync state machine (SPEC §4.2 Iteration 2).
 *
 * Transitions are centralised in `RunSyncUseCase` and `ResumeFromPauseUseCase`:
 *
 * ```
 * IDLE            --(worker starts)-->     RUNNING
 * RUNNING         --(ok)-->                IDLE
 * RUNNING         --(pause cause)-->       PAUSED_* / BROKEN
 * PAUSED_* / BROKEN --(user "Resume")-->   IDLE
 * ```
 *
 * Workers spawned while the state is in [PAUSED_STATES] must return
 * `Result.success()` immediately without touching Git (SPEC §4.5 "no auto-push
 * until user clears the pause").
 */
enum class SyncState {
    IDLE,
    RUNNING,
    PAUSED_CONFLICT,
    PAUSED_AUTH,
    PAUSED_FS,
    BROKEN,
}

/** States that short-circuit the worker until the user explicitly resumes. */
val PAUSED_STATES: Set<SyncState> = setOf(
    SyncState.PAUSED_CONFLICT,
    SyncState.PAUSED_AUTH,
    SyncState.PAUSED_FS,
    SyncState.BROKEN,
)
