package com.example.simplygit.data.git

import com.example.simplygit.di.IoDispatcher
import com.example.simplygit.domain.model.ConflictFile
import com.example.simplygit.domain.model.ConflictFileKind
import com.example.simplygit.domain.model.ResolutionChoice
import com.example.simplygit.domain.repository.ConflictRepository
import com.example.simplygit.domain.repository.RepoBindingRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.CheckoutCommand
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.diff.RawText
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.treewalk.TreeWalk
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Data-layer [ConflictRepository] (SPEC §4.3 Iteration 3, P6).
 *
 * All JGit handles are scoped to a single `Git.open(dir).use{}`.
 *  - [listConflicts] consults `git.status()` and classifies each conflicting
 *    path into TEXT / BINARY / DELETE_VS_MODIFY.
 *  - [checkoutStage] invokes `CheckoutCommand.setStage(OURS|THEIRS)` and
 *    then `add` so the path is re-staged as a normal blob.
 *  - [commitResolved] executes a single `CommitCommand` with
 *    the given author identity.
 */
@Singleton
internal class ConflictRepositoryImpl @Inject constructor(
    @IoDispatcher private val io: CoroutineDispatcher,
    private val bindingRepo: RepoBindingRepository,
    private val sanitizer: JGitExceptionSanitizer,
) : ConflictRepository {

    override suspend fun listConflicts(repoId: Long): List<ConflictFile> = withContext(io) {
        val binding = bindingRepo.currentOrNull() ?: return@withContext emptyList()
        if (binding.id != repoId) return@withContext emptyList()
        val root = File(binding.localAbsPath)
        if (!root.exists()) return@withContext emptyList()

        runCatching {
            Git.open(root).use { git ->
                val repo = git.repository
                val status = git.status().call()
                status.conflicting.sorted().map { path ->
                    val kind = classifyConflict(repo, path)
                    val (oursSize, theirsSize) = measureSides(repo, path)
                    ConflictFile(
                        path = path,
                        kind = kind,
                        oursSize = oursSize,
                        theirsSize = theirsSize,
                    )
                }
            }
        }.getOrElse { emptyList() }
    }

    override suspend fun checkoutStage(
        repoId: Long,
        path: String,
        choice: ResolutionChoice,
    ) {
        require(choice != ResolutionChoice.SKIP) { "SKIP must not reach checkoutStage" }
        withContext(io) {
            val binding = bindingRepo.currentOrNull()
                ?: throw ConflictResolutionFailedException(listOf(path), IllegalStateException("no binding"))
            val root = File(binding.localAbsPath)
            runCatching {
                Git.open(root).use { git ->
                    val stage = when (choice) {
                        ResolutionChoice.KEEP_OURS -> CheckoutCommand.Stage.OURS
                        ResolutionChoice.TAKE_THEIRS -> CheckoutCommand.Stage.THEIRS
                        ResolutionChoice.SKIP -> error("unreachable")
                    }
                    git.checkout()
                        .setStage(stage)
                        .addPath(path)
                        .call()
                    git.add().addFilepattern(path).call()
                    Unit
                }
            }.getOrElse { t ->
                throw ConflictResolutionFailedException(listOf(path), t)
            }
        }
    }

    override suspend fun commitResolved(
        repoId: Long,
        message: String,
        authorName: String,
        authorEmail: String,
    ): Int = withContext(io) {
        val binding = bindingRepo.currentOrNull()
            ?: throw ConflictResolutionFailedException(emptyList(), IllegalStateException("no binding"))
        val root = File(binding.localAbsPath)
        runCatching {
            Git.open(root).use { git ->
                // Stage changes look like: added/changed/modified/removed on the
                // paths we `add`-ed in checkoutStage. Count them before commit.
                val status = git.status().call()
                val staged = status.added.size +
                    status.changed.size +
                    status.removed.size
                val ident = PersonIdent(authorName, authorEmail)
                git.commit()
                    .setMessage(message)
                    .setAuthor(ident)
                    .setCommitter(ident)
                    .call()
                staged
            }
        }.getOrElse { t ->
            throw sanitizer.sanitize(t)
        }
    }

    /** Distinguish TEXT / BINARY / DELETE_VS_MODIFY by probing HEAD and MERGE_HEAD. */
    private fun classifyConflict(
        repo: org.eclipse.jgit.lib.Repository,
        path: String,
    ): ConflictFileKind {
        val headId = runCatching { repo.resolve("HEAD") }.getOrNull()
        val mergeId = runCatching { repo.resolve("MERGE_HEAD") }.getOrNull()
        val existsOnHead = headId?.let { hasPath(repo, it, path) } ?: false
        val existsOnMerge = mergeId?.let { hasPath(repo, it, path) } ?: false
        if (!existsOnHead || !existsOnMerge) return ConflictFileKind.DELETE_VS_MODIFY

        val bytes = readHeadBlob(repo, headId, path) ?: return ConflictFileKind.TEXT
        return if (RawText.isBinary(bytes.take(BINARY_SNIFF_BYTES).toByteArray())) {
            ConflictFileKind.BINARY
        } else {
            ConflictFileKind.TEXT
        }
    }

    private fun hasPath(
        repo: org.eclipse.jgit.lib.Repository,
        commitId: org.eclipse.jgit.lib.ObjectId,
        path: String,
    ): Boolean {
        return runCatching {
            RevWalk(repo).use { rw ->
                val tree = rw.parseCommit(commitId).tree
                TreeWalk.forPath(repo, path, tree)?.use { true } ?: false
            }
        }.getOrDefault(false)
    }

    private fun readHeadBlob(
        repo: org.eclipse.jgit.lib.Repository,
        headId: org.eclipse.jgit.lib.ObjectId?,
        path: String,
    ): ByteArray? {
        if (headId == null) return null
        return runCatching {
            RevWalk(repo).use { rw ->
                val tree = rw.parseCommit(headId).tree
                TreeWalk.forPath(repo, path, tree)?.use { tw ->
                    repo.newObjectReader().use { reader ->
                        val loader = reader.open(tw.getObjectId(0), Constants.OBJ_BLOB)
                        loader.bytes
                    }
                }
            }
        }.getOrNull()
    }

    private fun measureSides(
        repo: org.eclipse.jgit.lib.Repository,
        path: String,
    ): Pair<Long, Long> {
        val head = runCatching { repo.resolve("HEAD") }.getOrNull()
        val merge = runCatching { repo.resolve("MERGE_HEAD") }.getOrNull()
        val ours = blobSize(repo, head, path)
        val theirs = blobSize(repo, merge, path)
        return ours to theirs
    }

    private fun blobSize(
        repo: org.eclipse.jgit.lib.Repository,
        commitId: org.eclipse.jgit.lib.ObjectId?,
        path: String,
    ): Long {
        if (commitId == null) return 0L
        return runCatching {
            RevWalk(repo).use { rw ->
                val tree = rw.parseCommit(commitId).tree
                TreeWalk.forPath(repo, path, tree)?.use { tw ->
                    repo.newObjectReader().use { reader ->
                        reader.getObjectSize(tw.getObjectId(0), Constants.OBJ_BLOB)
                    }
                } ?: 0L
            }
        }.getOrDefault(0L)
    }

    private companion object {
        const val BINARY_SNIFF_BYTES = 8 * 1024
    }
}
