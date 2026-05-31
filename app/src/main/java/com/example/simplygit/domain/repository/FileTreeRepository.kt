package com.example.simplygit.domain.repository

import com.example.simplygit.domain.model.FileTreeNode
import com.example.simplygit.domain.model.RescanOutcome
import kotlinx.coroutines.flow.Flow

/**
 * File-tree view over the local Vault (SPEC §4.1.1 Iteration 3).
 *
 * Rescan triggers (SPEC §4.1.1 "触发时机"):
 *  1. `RepoBrowserScreen` first open when the cache is empty.
 *  2. `GitSyncWorker.doWork()` success branch (wrapped in `runCatching`).
 *  3. Manual pull-to-refresh in `RepoBrowserScreen`.
 *  4. After a successful `ClearConflictPauseUseCase` run.
 *
 * Every trigger is idempotent — the implementation upserts `(repoId, path)`
 * rows and drops stale rows not seen this pass, so concurrent triggers can
 * only produce a consistent snapshot.
 */
interface FileTreeRepository {
    /** Returns the direct children of [parentPath], directories first then files, alphabetical. */
    suspend fun listChildren(repoId: Long, parentPath: String): List<FileTreeNode>

    /** Full rescan: SAF enumeration + JGit `status()` + DB upsert. */
    suspend fun rescan(repoId: Long): RescanOutcome

    /** Observes a single node's current cached state. */
    fun observe(repoId: Long, path: String): Flow<FileTreeNode?>
}
