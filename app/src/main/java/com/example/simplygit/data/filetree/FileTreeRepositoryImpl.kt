package com.example.simplygit.data.filetree

import com.example.simplygit.data.sync.FileTreeCacheDao
import com.example.simplygit.data.sync.FileTreeCacheEntity
import com.example.simplygit.di.IoDispatcher
import com.example.simplygit.domain.model.FileTreeNode
import com.example.simplygit.domain.model.FileType
import com.example.simplygit.domain.model.GitFileStatus
import com.example.simplygit.domain.model.RescanOutcome
import com.example.simplygit.domain.repository.FileTreeRepository
import com.example.simplygit.domain.repository.RepoBindingRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import java.io.File
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Data-layer implementation of [FileTreeRepository] (SPEC §4.1.1 Iteration 3).
 *
 * Architecture notes:
 *  - JGit `Status` is consumed inside `Git.open(dir).use{}` and never leaks out
 *    of this class (SPEC P6 "JGit native types don't exit Data").
 *  - Directory enumeration uses `java.io.File.listFiles()` rather than
 *    `DocumentFile.listFiles()` — a deliberate narrow deviation from the
 *    SPEC wording aligned with the project's existing JGit access pattern
 *    ([com.example.simplygit.data.git.GitRepositoryImpl] and
 *    [com.example.simplygit.data.saf.SafPathResolver] already resolve the
 *    SAF tree URI to an absolute path and operate on `java.io.File`). Walking
 *    the same absolute path avoids duplicating the SAF tree resolution for
 *    every scan and is still gated by `SafPathResolver.hasPersistedPermission`
 *    at the sync entry point. Legal inside the app-private writable subtree.
 *  - All IO runs on [IoDispatcher]; the contract forbids calling anything on
 *    this class from the main thread.
 *  - Aggregation priority (SPEC §4.1.1): `CONFLICT > MODIFIED > STAGED >
 *    UNTRACKED > CLEAN`.
 *  - Rescan is idempotent: every pass collects all visible paths, upserts
 *    them, then deletes rows not seen this pass. A hard cap of
 *    [RescanOutcome.SCAN_HARD_LIMIT] prevents runaway walks on giant vaults.
 */
@Singleton
internal class FileTreeRepositoryImpl @Inject constructor(
    private val dao: FileTreeCacheDao,
    private val bindingRepo: RepoBindingRepository,
    @IoDispatcher private val io: CoroutineDispatcher,
    private val clock: Clock,
) : FileTreeRepository {

    override suspend fun listChildren(repoId: Long, parentPath: String): List<FileTreeNode> =
        withContext(io) {
            dao.findChildren(repoId, parentPath).map { it.toNode() }
        }

    override fun observe(repoId: Long, path: String): Flow<FileTreeNode?> =
        dao.observeNode(repoId, path).map { it?.toNode() }

    @Suppress("LongMethod", "ComplexMethod")
    override suspend fun rescan(repoId: Long): RescanOutcome = withContext(io) {
        val binding = bindingRepo.currentOrNull()
            ?: return@withContext RescanOutcome(0, 0L, emptyMap())
        if (binding.id != repoId) {
            return@withContext RescanOutcome(0, 0L, emptyMap())
        }

        val start = clock.millis()
        val root = File(binding.localAbsPath)
        if (!root.exists() || !root.canRead()) {
            return@withContext RescanOutcome(0, 0L, emptyMap())
        }

        // (1) Collect path → own status from JGit inside one `Git.open().use{}`.
        val statusByPath: Map<String, GitFileStatus> = runCatching {
            Git.open(root).use { git ->
                val status = git.status().call()
                buildMap {
                    // Priority-ordered writes: later entries override earlier ones,
                    // so write lower priority first then escalate.
                    status.untracked.forEach { put(it, GitFileStatus.UNTRACKED) }
                    status.modified.forEach { put(it, GitFileStatus.MODIFIED) }
                    status.missing.forEach { put(it, GitFileStatus.MODIFIED) }
                    status.added.forEach { put(it, GitFileStatus.STAGED) }
                    status.changed.forEach { put(it, GitFileStatus.STAGED) }
                    status.removed.forEach { put(it, GitFileStatus.STAGED) }
                    status.conflicting.forEach { put(it, GitFileStatus.CONFLICT) }
                }
            }
        }.getOrDefault(emptyMap())

        // (2) Walk the directory tree, collecting every node we see.
        val rows = mutableListOf<FileTreeCacheEntity>()
        val seenPaths = HashSet<String>()
        val classified = mutableMapOf<GitFileStatus, Int>()
        var aborted = false

        // Depth-first walk using an explicit stack; skip `.git` subtree.
        val stack = ArrayDeque<Pair<File, String>>()
        stack.addLast(root to "")

        // Map from dir path → best (highest-priority) descendant file status.
        val dirAggregate = HashMap<String, GitFileStatus>()

        while (stack.isNotEmpty()) {
            if (rows.size >= RescanOutcome.SCAN_HARD_LIMIT) {
                aborted = true
                break
            }
            val (file, rel) = stack.removeLast()
            if (rel.isNotEmpty()) {
                val parent = parentOf(rel)
                val isDir = file.isDirectory
                val ownStatus = when {
                    isDir -> GitFileStatus.CLEAN // directories aggregate only
                    else -> statusByPath[rel] ?: GitFileStatus.CLEAN
                }
                if (!isDir) {
                    classified[ownStatus] = (classified[ownStatus] ?: 0) + 1
                    // Propagate to all ancestors.
                    var p = parent
                    while (true) {
                        dirAggregate[p] = higherPriority(dirAggregate[p], ownStatus)
                        if (p.isEmpty()) break
                        p = parentOf(p)
                    }
                }
                rows.add(
                    FileTreeCacheEntity(
                        repoId = repoId,
                        path = rel,
                        parentPath = parent,
                        type = if (isDir) FileType.DIR.name else FileType.FILE.name,
                        gitStatus = ownStatus.name, // dirs patched in pass (3) below
                        size = if (isDir) 0L else file.length(),
                        lastModified = file.lastModified(),
                    )
                )
                seenPaths.add(rel)
            }
            if (file.isDirectory) {
                val children = file.listFiles() ?: continue
                for (child in children) {
                    if (rel.isEmpty() && child.name == ".git") continue
                    val childRel = if (rel.isEmpty()) child.name else "$rel/${child.name}"
                    stack.addLast(child to childRel)
                }
            }
        }

        // (3) Patch directory rows with the aggregated status.
        val patched = rows.map { row ->
            if (row.type == FileType.DIR.name) {
                val agg = dirAggregate[row.path] ?: GitFileStatus.CLEAN
                row.copy(gitStatus = agg.name)
            } else row
        }

        // (4) Upsert in chunks; then delete rows not seen this pass.
        patched.chunked(DB_UPSERT_CHUNK).forEach { dao.upsertAll(it) }
        val existingPaths = dao.allPaths(repoId)
        val stale = existingPaths.filterNot { seenPaths.contains(it) }
        stale.chunked(DB_DELETE_CHUNK).forEach { dao.deletePaths(repoId, it) }

        val durationMs = clock.millis() - start
        val total = if (aborted) -1 else patched.size
        RescanOutcome(totalEntries = total, durationMs = durationMs, classified = classified)
    }

    private fun parentOf(path: String): String {
        val idx = path.lastIndexOf('/')
        return if (idx < 0) "" else path.substring(0, idx)
    }

    private fun higherPriority(a: GitFileStatus?, b: GitFileStatus): GitFileStatus {
        val prev = a ?: return b
        return if (priority(b) > priority(prev)) b else prev
    }

    private fun priority(s: GitFileStatus): Int = when (s) {
        GitFileStatus.CONFLICT -> 4
        GitFileStatus.MODIFIED -> 3
        GitFileStatus.STAGED -> 2
        GitFileStatus.UNTRACKED -> 1
        GitFileStatus.CLEAN -> 0
    }

    private fun FileTreeCacheEntity.toNode(): FileTreeNode {
        val resolvedType = runCatching { FileType.valueOf(type) }.getOrDefault(FileType.FILE)
        val storedStatus = runCatching { GitFileStatus.valueOf(gitStatus) }
            .getOrDefault(GitFileStatus.CLEAN)
        // SPEC §4.1.1 / G6: `gitStatus` = node's OWN status (directories carry
        // no Git state on their own → always CLEAN); `aggregatedStatus` =
        // subtree max-priority (for files equal to own status). The cache
        // stores the aggregated value in a single column; project it back to
        // the two-field shape here so UI consumers that rely on the semantic
        // split keep working.
        val ownStatus = if (resolvedType == FileType.DIR) GitFileStatus.CLEAN else storedStatus
        return FileTreeNode(
            repoId = repoId,
            path = path,
            name = if (path.isEmpty()) "" else path.substringAfterLast('/'),
            type = resolvedType,
            gitStatus = ownStatus,
            aggregatedStatus = storedStatus,
            size = size,
            lastModified = lastModified,
        )
    }

    private companion object {
        const val DB_UPSERT_CHUNK = 500
        const val DB_DELETE_CHUNK = 500
    }
}
