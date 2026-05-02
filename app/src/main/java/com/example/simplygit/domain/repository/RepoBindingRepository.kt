package com.example.simplygit.domain.repository

import com.example.simplygit.domain.model.RepoBinding
import kotlinx.coroutines.flow.Flow

/** SPEC §6.2. */
interface RepoBindingRepository {
    fun observe(): Flow<RepoBinding?>

    /** @throws IllegalStateException when no binding has been established yet. */
    suspend fun requireCurrent(): RepoBinding

    /**
     * Returns the current binding, or `null` when no binding exists OR when
     * `localAbsPath` is still unresolved (SPEC §4.6 Iteration 2 mapping rule).
     * Preferred entry point for background workers: no Flow subscription.
     *
     * SPEC Iteration 2 (fix I-2): used by
     * [com.example.simplygit.domain.usecase.RunSyncUseCase] to short-circuit to
     * `RunSyncOutcome.NoBinding`.
     */
    suspend fun currentOrNull(): RepoBinding?

    /**
     * One-shot DataStore → Room migration entry (SPEC §4.6 Iteration 2 /
     * fix CR P2-01). Called from `SimplyGitApp.onCreate` in an async
     * `GlobalScope.launch` so the very first frame is not blocked; callers
     * that directly read Room (e.g. [observe]) see the migrated row as soon
     * as this completes.
     *
     * Idempotent: becomes a no-op once `migration_v1_done = true` or the
     * `repository` table already has a row. Failures bump a retry counter —
     * three consecutive failures flip the migration into a *disabled* state
     * exposed via [isMigrationDisabled], and Iteration-2 auto-sync features
     * must fall back to "guide the user to re-bind".
     */
    suspend fun migrateFromDataStoreIfNeeded()

    /**
     * Returns true when the DataStore → Room migration has failed
     * [MAX_MIGRATION_RETRIES] times and should be surfaced to the user as a
     * re-bind prompt (SPEC §4.6 Iteration 2 / fix CR P2-01).
     */
    suspend fun isMigrationDisabled(): Boolean

    suspend fun saveVault(treeUri: String, absPath: String)
    suspend fun saveRemote(url: String)
    suspend fun clear()

    companion object {
        /**
         * Maximum consecutive DataStore → Room migration attempts before the
         * migration is considered broken and the user must re-bind
         * (SPEC §4.6 Iteration 2 / fix CR P2-01).
         */
        const val MAX_MIGRATION_RETRIES: Int = 3
    }
}
