@file:Suppress("LongParameterList", "TooManyFunctions")

package com.example.simplygit.domain.repository

import com.example.simplygit.domain.model.ConflictClass
import com.example.simplygit.domain.model.RepositoryStateSnapshot
import com.example.simplygit.domain.model.SyncLogModel
import com.example.simplygit.domain.model.SyncResult
import com.example.simplygit.domain.model.SyncState
import com.example.simplygit.domain.model.SyncTrigger
import kotlinx.coroutines.flow.Flow
import java.time.Instant

/** SPEC §6.2 Iteration 2. */
interface SyncLogRepository {
    fun observeRecent(limit: Int = 30): Flow<List<SyncLogModel>>

    /**
     * Observes the per-repository state snapshot.
     *
     * **N4 single-repo contract (SPEC §2.4 / §4.6 Iteration 2):** the underlying
     * `repository` table is capped at one row. Implementations MAY ignore
     * [repoId] and read the first (only) row, returning its id in the snapshot.
     * The [repoId] parameter is kept in the signature so the multi-repo
     * extension in Phase 3+ does not require a source-incompatible change.
     */
    fun observeRepoState(repoId: Long): Flow<RepositoryStateSnapshot>
    suspend fun loadById(id: Long): SyncLogModel?
    suspend fun loadRepoState(repoId: Long): RepositoryStateSnapshot
    suspend fun startLog(repoId: Long, trigger: SyncTrigger, now: Instant): Long
    suspend fun finishLog(
        logId: Long,
        result: SyncResult,
        endedAt: Instant,
        commitsPulled: Int = 0,
        commitsPushed: Int = 0,
        filesChanged: Int = 0,
        conflictClass: ConflictClass? = null,
        errorMsg: String? = null,
        errorType: String? = null,
    )
    suspend fun updateSyncState(repoId: Long, state: SyncState)
    suspend fun pauseAndFinish(
        repoId: Long,
        logId: Long,
        state: SyncState,
        result: SyncResult,
        endedAt: Instant,
        conflictClass: ConflictClass? = null,
        errorMsg: String? = null,
        errorType: String? = null,
    )
    suspend fun recentConsecutiveFailures(repoId: Long): Int
    suspend fun pruneExpired(now: Instant)
    suspend fun loadRecentForExport(limit: Int = 500): List<SyncLogModel>
}
