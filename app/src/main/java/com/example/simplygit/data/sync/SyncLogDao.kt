package com.example.simplygit.data.sync

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * DAO for `sync_log` (SPEC §4.6 Iteration 2).
 *
 * `pruneExpired` runs after every [finish]-style update and enforces the
 * "≤ 500 rows ∧ ≤ 7 days" cap (SPEC G5). `recentConsecutiveFailures` walks
 * the tail of the log to drive the 3-strike BROKEN transition (§4.5).
 */
@Dao
interface SyncLogDao {

    @Insert
    suspend fun insert(entity: SyncLogEntity): Long

    @Update
    suspend fun update(entity: SyncLogEntity)

    @Query("SELECT * FROM sync_log WHERE id = :id")
    suspend fun findById(id: Long): SyncLogEntity?

    @Query(
        """
        SELECT * FROM sync_log
        WHERE repoId = :repoId
        ORDER BY startedAt DESC
        LIMIT :limit
        """
    )
    fun observeRecent(repoId: Long, limit: Int): Flow<List<SyncLogEntity>>

    @Query(
        """
        SELECT * FROM sync_log
        ORDER BY startedAt DESC
        LIMIT :limit
        """
    )
    fun observeRecentAll(limit: Int): Flow<List<SyncLogEntity>>

    @Query(
        """
        SELECT * FROM sync_log
        ORDER BY startedAt DESC
        LIMIT :limit
        """
    )
    suspend fun recentAll(limit: Int): List<SyncLogEntity>

    /**
     * Walks the most recent [scanLimit] rows for [repoId] in descending
     * `startedAt` order and counts the leading streak of non-OK
     * finished results. Rows whose `result` is null (i.e. still running)
     * or SKIPPED_* are ignored — they neither reset nor extend the streak.
     */
    @Query(
        """
        SELECT result FROM sync_log
        WHERE repoId = :repoId AND endedAt IS NOT NULL
        ORDER BY startedAt DESC
        LIMIT :scanLimit
        """
    )
    suspend fun recentFinishedResults(repoId: Long, scanLimit: Int = 10): List<String>

    @Transaction
    @Query(
        """
        DELETE FROM sync_log
        WHERE startedAt < :cutoffMillis
           OR id NOT IN (
               SELECT id FROM sync_log
               ORDER BY startedAt DESC
               LIMIT :maxRows
           )
        """
    )
    suspend fun pruneExpired(cutoffMillis: Long, maxRows: Int)

    @Query("SELECT COUNT(*) FROM sync_log")
    suspend fun count(): Int
}
