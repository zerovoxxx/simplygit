@file:Suppress("LongParameterList", "TooManyFunctions")

package com.example.simplygit.data.sync

import androidx.room.withTransaction
import com.example.simplygit.domain.model.ConflictClass
import com.example.simplygit.domain.model.RepositoryStateSnapshot
import com.example.simplygit.domain.model.SyncLogModel
import com.example.simplygit.domain.model.SyncResult
import com.example.simplygit.domain.model.SyncState
import com.example.simplygit.domain.model.SyncTrigger
import com.example.simplygit.domain.repository.SyncLogRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Room-backed [SyncLogRepository] (SPEC §4.3 / §4.6 / §6.1 Iteration 2).
 *
 * Transaction boundaries follow SPEC §4.3:
 *  - `startLog` is a standalone INSERT (we do not hold a transaction open
 *    across a multi-second Git run).
 *  - `finishLog` / `updateSyncState` / `pauseAndFinish` combine the log
 *    completion and the repository state transition in one
 *    [androidx.room.withTransaction] block so they cannot drift apart.
 */
@Singleton
class SyncLogRepositoryImpl @Inject constructor(
    private val db: SimplygitDatabase,
    private val logDao: SyncLogDao,
    private val repoDao: RepositoryDao,
) : SyncLogRepository {

    override fun observeRecent(limit: Int): Flow<List<SyncLogModel>> =
        logDao.observeRecentAll(limit).map { rows -> rows.map { it.toModel() } }

    override fun observeRepoState(repoId: Long): Flow<RepositoryStateSnapshot> =
        repoDao.observeFirst().map { entity -> entity.toStateSnapshot(repoId) }

    override suspend fun loadById(id: Long): SyncLogModel? =
        logDao.findById(id)?.toModel()

    override suspend fun loadRepoState(repoId: Long): RepositoryStateSnapshot =
        repoDao.findById(repoId).toStateSnapshot(repoId)

    override suspend fun startLog(repoId: Long, trigger: SyncTrigger, now: Instant): Long =
        logDao.insert(
            SyncLogEntity(
                repoId = repoId,
                startedAt = now.toEpochMilli(),
                endedAt = null,
                trigger = trigger.name,
                result = null,
            ),
        )

    override suspend fun tryStartRun(repoId: Long, trigger: SyncTrigger, now: Instant): Long? =
        db.withTransaction {
            val claimed = repoDao.compareAndSetSyncState(
                id = repoId,
                expectedState = SyncState.IDLE.name,
                nextState = SyncState.RUNNING.name,
            )
            if (claimed != 1) return@withTransaction null
            logDao.insert(
                SyncLogEntity(
                    repoId = repoId,
                    startedAt = now.toEpochMilli(),
                    endedAt = null,
                    trigger = trigger.name,
                    result = null,
                ),
            )
        }

    override suspend fun recoverStaleRunning(
        repoId: Long,
        staleBefore: Instant,
        endedAt: Instant,
    ): Boolean = db.withTransaction {
        val endedAtMillis = endedAt.toEpochMilli()
        var recovered = false
        val state = repoDao.findById(repoId)?.syncState
        val staleRows = logDao.staleOpenWorkerRuns(
            repoId = repoId,
            staleBeforeMillis = staleBefore.toEpochMilli(),
        )
        staleRows.forEach { row ->
            logDao.update(
                row.copy(
                    endedAt = endedAtMillis,
                    result = SyncResult.ABORTED.name,
                    errorMsg = row.errorMsg ?: STALE_RUNNING_ERROR_MSG,
                    errorType = row.errorType ?: STALE_RUNNING_ERROR_TYPE,
                ),
            )
            recovered = true
        }

        val openWorkerRuns = logDao.openWorkerRunCount(repoId)
        if (state == SyncState.RUNNING.name && openWorkerRuns == 0) {
            if (staleRows.isNotEmpty()) {
                repoDao.updateLastSync(repoId, endedAtMillis, SyncResult.ABORTED.name)
            }
            repoDao.compareAndSetSyncState(
                id = repoId,
                expectedState = SyncState.RUNNING.name,
                nextState = SyncState.IDLE.name,
            )
            recovered = true
        }
        recovered
    }

    override suspend fun abortRun(
        repoId: Long,
        logId: Long,
        endedAt: Instant,
        errorMsg: String?,
        errorType: String?,
    ) {
        db.withTransaction {
            val endedAtMillis = endedAt.toEpochMilli()
            val row = logDao.findById(logId)
            if (row != null) {
                logDao.update(
                    row.copy(
                        endedAt = endedAtMillis,
                        result = SyncResult.ABORTED.name,
                        errorMsg = errorMsg,
                        errorType = errorType,
                    ),
                )
                repoDao.updateLastSync(row.repoId, endedAtMillis, SyncResult.ABORTED.name)
            }
            if (logDao.openWorkerRunCount(repoId) == 0) {
                repoDao.compareAndSetSyncState(
                    id = repoId,
                    expectedState = SyncState.RUNNING.name,
                    nextState = SyncState.IDLE.name,
                )
            }
        }
    }

    override suspend fun finishLog(
        logId: Long,
        result: SyncResult,
        endedAt: Instant,
        commitsPulled: Int,
        commitsPushed: Int,
        filesChanged: Int,
        conflictClass: ConflictClass?,
        errorMsg: String?,
        errorType: String?,
    ) {
        db.withTransaction {
            val row = logDao.findById(logId) ?: return@withTransaction
            logDao.update(
                row.copy(
                    endedAt = endedAt.toEpochMilli(),
                    result = result.name,
                    commitsPulled = commitsPulled,
                    commitsPushed = commitsPushed,
                    filesChanged = filesChanged,
                    conflictClass = conflictClass?.name,
                    errorMsg = errorMsg,
                    errorType = errorType,
                ),
            )
            repoDao.updateLastSync(row.repoId, endedAt.toEpochMilli(), result.name)
        }
    }

    override suspend fun updateSyncState(repoId: Long, state: SyncState) {
        repoDao.updateSyncState(repoId, state.name)
    }

    override suspend fun pauseAndFinish(
        repoId: Long,
        logId: Long,
        state: SyncState,
        result: SyncResult,
        endedAt: Instant,
        conflictClass: ConflictClass?,
        errorMsg: String?,
        errorType: String?,
    ) {
        db.withTransaction {
            val row = logDao.findById(logId)
            if (row != null) {
                logDao.update(
                    row.copy(
                        endedAt = endedAt.toEpochMilli(),
                        result = result.name,
                        conflictClass = conflictClass?.name,
                        errorMsg = errorMsg,
                        errorType = errorType,
                    ),
                )
                repoDao.updateLastSync(row.repoId, endedAt.toEpochMilli(), result.name)
            }
            repoDao.updateSyncState(repoId, state.name)
        }
    }

    override suspend fun recentConsecutiveFailures(repoId: Long): Int {
        val recent = logDao.recentFinishedResults(repoId = repoId, scanLimit = FAILURE_SCAN_LIMIT)
            .mapNotNull { name -> runCatching { SyncResult.valueOf(name) }.getOrNull() }
            .filter { it != SyncResult.SKIPPED_DEBOUNCE && it != SyncResult.SKIPPED_PAUSED }
        // Iteration 3: CONFLICT_RESOLVED is a user-driven success outcome and
        // must NOT count toward the BROKEN-streak budget.
        return recent.takeWhile { it != SyncResult.OK && it != SyncResult.CONFLICT_RESOLVED }
            .count()
    }

    override suspend fun pruneExpired(now: Instant) {
        val cutoff = now.minus(Duration.ofDays(RETENTION_DAYS.toLong())).toEpochMilli()
        logDao.pruneExpired(cutoffMillis = cutoff, maxRows = MAX_ROWS)
    }

    override suspend fun loadRecentForExport(limit: Int): List<SyncLogModel> =
        logDao.recentAll(limit).map { it.toModel() }

    private companion object {
        const val STALE_RUNNING_ERROR_MSG = "stale worker run exceeded running lease"
        const val STALE_RUNNING_ERROR_TYPE = "StaleRunningRun"
        const val FAILURE_SCAN_LIMIT = 10
        const val RETENTION_DAYS = 7
        const val MAX_ROWS = 500
    }
}

internal fun SyncLogEntity.toModel(): SyncLogModel = SyncLogModel(
    id = id,
    repoId = repoId,
    startedAt = Instant.ofEpochMilli(startedAt),
    endedAt = endedAt?.let { Instant.ofEpochMilli(it) },
    trigger = runCatching { SyncTrigger.valueOf(trigger) }.getOrDefault(SyncTrigger.PERIODIC),
    result = result?.let { runCatching { SyncResult.valueOf(it) }.getOrNull() },
    commitsPulled = commitsPulled,
    commitsPushed = commitsPushed,
    filesChanged = filesChanged,
    conflictClass = conflictClass?.let { runCatching { ConflictClass.valueOf(it) }.getOrNull() },
    errorMsg = errorMsg,
    errorType = errorType,
)

internal fun RepositoryEntity?.toStateSnapshot(repoId: Long): RepositoryStateSnapshot {
    if (this == null) {
        return RepositoryStateSnapshot(
            repoId = repoId,
            syncState = SyncState.IDLE,
            lastSyncAt = null,
            lastSyncResult = null,
        )
    }
    val state = runCatching { SyncState.valueOf(syncState) }.getOrDefault(SyncState.IDLE)
    val result = lastSyncResult?.let { runCatching { SyncResult.valueOf(it) }.getOrNull() }
    return RepositoryStateSnapshot(
        repoId = id,
        syncState = state,
        lastSyncAt = lastSyncAt?.let { Instant.ofEpochMilli(it) },
        lastSyncResult = result,
    )
}
