package com.example.simplygit.data.sync

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * DAO for `sync_policy` (SPEC §4.6 Iteration 2).
 *
 * Iteration 2 N4: only 1 row expected. `observeFirst` / `findFirst` are
 * convenience wrappers returning that row (or `null` when not seeded yet).
 */
@Dao
interface SyncPolicyDao {

    @Query("SELECT * FROM sync_policy ORDER BY id ASC LIMIT 1")
    fun observeFirst(): Flow<SyncPolicyEntity?>

    @Query("SELECT * FROM sync_policy ORDER BY id ASC LIMIT 1")
    suspend fun findFirst(): SyncPolicyEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: SyncPolicyEntity): Long

    @Update
    suspend fun update(entity: SyncPolicyEntity)

    @Query("SELECT COUNT(*) FROM sync_policy")
    suspend fun count(): Int
}
