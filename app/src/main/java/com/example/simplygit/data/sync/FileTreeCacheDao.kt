package com.example.simplygit.data.sync

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * DAO for `file_tree_cache` (SPEC §4.1.1 Iteration 3).
 *
 * Write contract:
 *  - `upsertAll` replaces on `(repo_id, path)` conflict — matches the rescan
 *    pattern "collect all visible nodes then upsert".
 *  - `deletePathsNotInBatch` drops rows that were NOT in the last rescan so
 *    deleted files/dirs disappear from the cache. We pass the scanned paths
 *    in manageable chunks (SQLite's `IN(?,?,...)` host parameter limit is 999
 *    on older runtimes — the Repository Impl handles chunking).
 */
@Dao
interface FileTreeCacheDao {

    @Query(
        """
        SELECT * FROM file_tree_cache
        WHERE repo_id = :repoId AND parent_path = :parentPath
        ORDER BY type DESC, path ASC
        """
    )
    suspend fun findChildren(repoId: Long, parentPath: String): List<FileTreeCacheEntity>

    @Query(
        """
        SELECT * FROM file_tree_cache
        WHERE repo_id = :repoId AND path = :path
        LIMIT 1
        """
    )
    fun observeNode(repoId: Long, path: String): Flow<FileTreeCacheEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<FileTreeCacheEntity>)

    @Query("DELETE FROM file_tree_cache WHERE repo_id = :repoId")
    suspend fun clearRepo(repoId: Long)

    @Query("SELECT COUNT(*) FROM file_tree_cache WHERE repo_id = :repoId")
    suspend fun countForRepo(repoId: Long): Int

    @Query("SELECT path FROM file_tree_cache WHERE repo_id = :repoId")
    suspend fun allPaths(repoId: Long): List<String>

    @Query("DELETE FROM file_tree_cache WHERE repo_id = :repoId AND path IN (:paths)")
    suspend fun deletePaths(repoId: Long, paths: List<String>)
}
