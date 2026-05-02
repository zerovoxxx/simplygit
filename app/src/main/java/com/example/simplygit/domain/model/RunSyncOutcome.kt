package com.example.simplygit.domain.model

/**
 * Outcome of a single `RunSyncUseCase` invocation (SPEC §4.3 / §6.1 Iteration 2).
 *
 * Consumed by `GitSyncWorker.doWork()` to decide between `Result.success()` and
 * `Result.retry()`. Paused outcomes return `success()` because the audit row and
 * the state transition are already persisted; we do not want WorkManager to keep
 * retrying a run that is waiting for user action.
 */
sealed interface RunSyncOutcome {
    data object Ok : RunSyncOutcome
    data object SkippedDebounce : RunSyncOutcome
    data class SkippedPaused(val state: SyncState) : RunSyncOutcome
    data object NoBinding : RunSyncOutcome
    data object MissingCredential : RunSyncOutcome
    data object NetworkErr : RunSyncOutcome
    data object PausedFs : RunSyncOutcome
    data object PausedAuth : RunSyncOutcome
    data class PausedConflict(val classification: ConflictClass) : RunSyncOutcome
    data object UnknownErr : RunSyncOutcome
}
