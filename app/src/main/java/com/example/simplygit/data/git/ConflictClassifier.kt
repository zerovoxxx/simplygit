package com.example.simplygit.data.git

import com.example.simplygit.domain.model.ConflictClass
import org.eclipse.jgit.api.MergeResult
import org.eclipse.jgit.api.PullResult
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.treewalk.TreeWalk
import javax.inject.Inject

/**
 * Maps JGit's [MergeResult.MergeStatus] onto SPEC §4.2 / §4.5 Iteration 2's
 * six-way [ConflictClass] enum.
 *
 * SPEC Iteration 2 (fix I-3 / I-9 / P6): this class lives in the Data layer
 * (`data.git` package). It MUST only be invoked inside a
 * `Git.open(dir).use { git -> ... }` scope held by
 * [GitRepositoryImpl.pullAndClassify]; the passed [Repository] reference is
 * tied to that `use{}` block and must not be cached.
 *
 * The architectural boundary (Domain / UI do not hold JGit types) is enforced
 * by the A11d CI `grep` rule; the class itself is module-visible so Hilt can
 * inject it into [JGitDataSource] without tripping Kotlin's "exposes
 * internal type" rule.
 */
internal class ConflictClassifier @Inject constructor() {

    fun classify(pullResult: PullResult, repo: Repository): ConflictClass {
        val merge = pullResult.mergeResult
            ?: return ConflictClass.FAST_FORWARD // fetch-only: no merge attempted.
        return when (merge.mergeStatus) {
            MergeResult.MergeStatus.FAST_FORWARD,
            MergeResult.MergeStatus.FAST_FORWARD_SQUASHED,
            MergeResult.MergeStatus.ALREADY_UP_TO_DATE,
            -> ConflictClass.FAST_FORWARD

            MergeResult.MergeStatus.MERGED,
            MergeResult.MergeStatus.MERGED_SQUASHED,
            MergeResult.MergeStatus.MERGED_SQUASHED_NOT_COMMITTED,
            MergeResult.MergeStatus.MERGED_NOT_COMMITTED,
            -> ConflictClass.AUTO_MERGED

            MergeResult.MergeStatus.CONFLICTING -> resolveConflictKind(merge, repo)

            MergeResult.MergeStatus.FAILED -> detectDeleteModifyOrRemoteRewrite(repo)

            MergeResult.MergeStatus.NOT_SUPPORTED,
            MergeResult.MergeStatus.ABORTED,
            -> ConflictClass.REMOTE_REWRITE

            else -> ConflictClass.TEXT_LINE_CONFLICT
        }
    }

    /** Conflict paths (git index paths) for audit logging (SPEC §6.3). */
    fun conflictPaths(pullResult: PullResult): List<String> =
        pullResult.mergeResult?.conflicts?.keys.orEmpty().toList()

    private fun resolveConflictKind(merge: MergeResult, repo: Repository): ConflictClass {
        val paths = merge.conflicts?.keys.orEmpty()
        if (paths.isEmpty()) {
            // R-8: CONFLICTING with empty map is unexpected — stay on the
            // conservative pause path rather than auto-merged.
            return ConflictClass.TEXT_LINE_CONFLICT
        }
        val hasBinary = paths.any { path ->
            isBinaryByAttribute(repo, path) || isBinaryByFirst8KB(repo, path)
        }
        return if (hasBinary) ConflictClass.BINARY_CONFLICT else ConflictClass.TEXT_LINE_CONFLICT
    }

    private fun detectDeleteModifyOrRemoteRewrite(repo: Repository): ConflictClass {
        val fetchHead = runCatching { repo.resolve("FETCH_HEAD") }.getOrNull()
            ?: return ConflictClass.REMOTE_REWRITE
        val localHead = runCatching { repo.resolve("HEAD") }.getOrNull()
            ?: return ConflictClass.REMOTE_REWRITE
        val localAncestorOfRemote = isAncestor(repo, localHead, fetchHead)
        val remoteAncestorOfLocal = isAncestor(repo, fetchHead, localHead)
        // Diverged history → force-push / rebase on the remote side.
        return if (!localAncestorOfRemote && !remoteAncestorOfLocal) {
            ConflictClass.REMOTE_REWRITE
        } else {
            ConflictClass.DELETE_MODIFY
        }
    }

    private fun isBinaryByAttribute(repo: Repository, path: String): Boolean = runCatching {
        val head = repo.resolve("HEAD") ?: return@runCatching false
        RevWalk(repo).use { rw ->
            val tree = rw.parseCommit(head).tree
            val tw = TreeWalk.forPath(repo, path, tree) ?: return@runCatching false
            tw.use {
                // SPEC §4.2: ask JGit to resolve .gitattributes for this path via the
                // repo-wide provider. Without setAttributesNodeProvider the resolved
                // Attributes collection is always empty.
                it.attributesNodeProvider = repo.createAttributesNodeProvider()
                val attrs = it.attributes
                attrs.isSet("binary") || attrs.isUnset("text")
            }
        }
    }.getOrDefault(false)

    private fun isBinaryByFirst8KB(repo: Repository, path: String): Boolean = runCatching {
        val head = repo.resolve("HEAD") ?: return@runCatching false
        RevWalk(repo).use { rw ->
            val tree = rw.parseCommit(head).tree
            TreeWalk.forPath(repo, path, tree)?.use { tw ->
                val id = tw.getObjectId(0)
                repo.newObjectReader().use { reader ->
                    reader.open(id).openStream().use { input ->
                        val buf = ByteArray(SNIFF_BYTES)
                        val n = input.read(buf).coerceAtLeast(0)
                        (0 until n).any { buf[it] == NUL_BYTE }
                    }
                }
            } ?: false
        }
    }.getOrDefault(false)

    private fun isAncestor(repo: Repository, base: ObjectId, tip: ObjectId): Boolean = runCatching {
        RevWalk(repo).use { rw -> rw.isMergedInto(rw.parseCommit(base), rw.parseCommit(tip)) }
    }.getOrDefault(false)

    private companion object {
        const val SNIFF_BYTES = 8 * 1024
        const val NUL_BYTE: Byte = 0
    }
}
