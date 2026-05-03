@file:Suppress("TooManyFunctions")

package com.example.simplygit.data.sync

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * DAO for `repository` (SPEC §4.6 Iteration 2).
 *
 * Iteration 2 N4: only 1 row expected. `updateSyncState` / `updateLastSync`
 * are narrow-scoped updates so the FK constraint is not triggered by full-row
 * replacement on a column-only write.
 */
@Dao
interface RepositoryDao {

    @Query("SELECT * FROM repository ORDER BY id ASC LIMIT 1")
    fun observeFirst(): Flow<RepositoryEntity?>

    @Query("SELECT * FROM repository ORDER BY id ASC LIMIT 1")
    suspend fun findFirst(): RepositoryEntity?

    @Query("SELECT * FROM repository WHERE id = :id")
    suspend fun findById(id: Long): RepositoryEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: RepositoryEntity): Long

    @Update
    suspend fun update(entity: RepositoryEntity)

    @Query("UPDATE repository SET syncState = :state WHERE id = :id")
    suspend fun updateSyncState(id: Long, state: String)

    @Query(
        """
        UPDATE repository
        SET lastSyncAt = :ts, lastSyncResult = :result
        WHERE id = :id
        """
    )
    suspend fun updateLastSync(id: Long, ts: Long, result: String)

    @Query("UPDATE repository SET remoteUrl = :url WHERE id = :id")
    suspend fun updateRemoteUrl(id: Long, url: String)

    /**
     * SPEC §6.1 Iteration 3: switch a repo between PAT / SSH.
     * `authRef` = `"github_pat"` (PAT) or `"ssh_<keyId>"` (SSH).
     */
    @Query("UPDATE repository SET auth_type = :authType, authRef = :authRef WHERE id = :id")
    suspend fun updateAuth(id: Long, authType: String, authRef: String)

    @Query(
        """
        UPDATE repository
        SET localTreeUri = :treeUri, localAbsPath = :absPath
        WHERE id = :id
        """
    )
    suspend fun updateVaultPath(id: Long, treeUri: String, absPath: String)

    @Query("DELETE FROM repository")
    suspend fun clear()

    @Query("SELECT COUNT(*) FROM repository")
    suspend fun count(): Int

    @Query(
        """
        SELECT COUNT(*) FROM repository
        WHERE syncState IN ('PAUSED_CONFLICT', 'PAUSED_AUTH', 'PAUSED_FS', 'BROKEN')
        """
    )
    fun observePausedCount(): Flow<Int>
}
