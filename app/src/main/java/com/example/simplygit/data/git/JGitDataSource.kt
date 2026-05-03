package com.example.simplygit.data.git

import com.example.simplygit.data.ssh.GitSshSessionFactoryProvider
import com.example.simplygit.di.IoDispatcher
import com.example.simplygit.domain.model.CommitOutcome
import com.example.simplygit.domain.model.PullOutcomeClassified
import com.example.simplygit.domain.model.PushOutcome
import com.example.simplygit.domain.repository.RepoBindingRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.MergeCommand
import org.eclipse.jgit.api.TransportCommand
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.transport.RemoteRefUpdate
import org.eclipse.jgit.transport.SshTransport
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class PullOutcome(val commitsPulled: Int, val mergeStatus: String?)

class PullConflictException(val status: String) : RuntimeException("pull conflict: $status")
class PushRejectedException(val code: String, val msg: String) :
    RuntimeException("push rejected: $code $msg")
class NoChangesException : RuntimeException("no changes to commit")
class SafPermissionRevokedException : RuntimeException("SAF permission revoked")

/**
 * Atomic JGit operations (SPEC §4.4). All JGit calls are dispatched to [IoDispatcher];
 * calling any method on the main thread is a bug in the caller.
 *
 * PAT lifetime: this class takes PATs as method parameters only and never stores them.
 * JGit's [UsernamePasswordCredentialsProvider] keeps its own `char[]` copy until GC,
 * so callers must still zero the buffer they passed in.
 *
 * Iteration 3 (SPEC §4.4.2 / P0-1): method signatures (`clone/pull/push/...`)
 * remain PAT-shaped; callers pass a `CharArray(0)` placeholder for SSH-bound
 * repos. Inside [applyAuth] we dispatch on `binding.authType`:
 *  - `PAT` → standard [UsernamePasswordCredentialsProvider].
 *  - `SSH` → per-repo `SshdSessionFactory` from [GitSshSessionFactoryProvider].
 */
@Singleton
internal class JGitDataSource @Inject constructor(
    @IoDispatcher private val io: CoroutineDispatcher,
    private val sanitizer: JGitExceptionSanitizer,
    private val conflictClassifier: ConflictClassifier,
    private val sshSessionFactoryProvider: GitSshSessionFactoryProvider,
    private val bindingRepository: RepoBindingRepository,
) {

    suspend fun clone(
        remoteUrl: String,
        localDir: File,
        username: String,
        pat: CharArray,
    ): Result<Unit> = withContext(io) {
        runCatching {
            val clone = Git.cloneRepository()
                .setURI(remoteUrl)
                .setDirectory(localDir)
            applyAuth(clone, username, pat)
            clone.call().use { /* Git.close() via use */ }
            Unit
        }.mapException(sanitizer)
    }

    suspend fun pull(
        localDir: File,
        username: String,
        pat: CharArray,
    ): Result<PullOutcome> = withContext(io) {
        runCatching {
            Git.open(localDir).use { git ->
                val pullCmd = git.pull().setFastForward(MergeCommand.FastForwardMode.FF)
                applyAuth(pullCmd, username, pat)
                val result = pullCmd.call()
                if (!result.isSuccessful) {
                    throw PullConflictException(
                        result.mergeResult?.mergeStatus?.name ?: "unknown"
                    )
                }
                PullOutcome(
                    commitsPulled = result.fetchResult.trackingRefUpdates.size,
                    mergeStatus = result.mergeResult?.mergeStatus?.name,
                )
            }
        }.mapException(sanitizer)
    }

    suspend fun pullAndClassify(
        localDir: File,
        username: String,
        pat: CharArray,
    ): Result<PullOutcomeClassified> = withContext(io) {
        runCatching {
            Git.open(localDir).use { git ->
                val pullCmd = git.pull().setFastForward(MergeCommand.FastForwardMode.FF)
                applyAuth(pullCmd, username, pat)
                val raw = pullCmd.call()
                val classification = conflictClassifier.classify(raw, git.repository)
                val conflictPaths = conflictClassifier.conflictPaths(raw)
                PullOutcomeClassified(
                    classification = classification,
                    commitsPulled = raw.fetchResult?.trackingRefUpdates?.size ?: 0,
                    conflictPaths = conflictPaths,
                    mergeStatusName = raw.mergeResult?.mergeStatus?.name,
                )
            }
        }.mapException(sanitizer)
    }

    /**
     * Fix for bug_report_20260503 "审计统计恒为 0": surface `filesChanged`
     * so manual-Commit audit rows (HomeViewModel path) report the real
     * count instead of a hard-coded 0. Deduplication logic mirrors
     * [commitAllIfDirty] (BUG-006 fix — take the set union, not sum).
     */
    suspend fun commitAll(
        localDir: File,
        message: String,
        authorName: String,
        authorEmail: String,
    ): Result<CommitOutcome> = withContext(io) {
        runCatching {
            Git.open(localDir).use { git ->
                git.add().addFilepattern(".").call()
                val status = git.status().call()
                if (status.isClean) throw NoChangesException()
                val filesChanged = (
                    status.added +
                        status.changed +
                        status.modified +
                        status.removed +
                        status.missing +
                        status.untracked
                    ).size
                val ident = PersonIdent(authorName, authorEmail)
                val commit = git.commit()
                    .setMessage(message)
                    .setAuthor(ident)
                    .setCommitter(ident)
                    .call()
                CommitOutcome(objectId = commit.id.name, filesChanged = filesChanged)
            }
        }.mapException(sanitizer)
    }

    suspend fun commitAllIfDirty(
        localDir: File,
        message: String,
        authorName: String,
        authorEmail: String,
    ): Result<CommitOutcome?> = withContext(io) {
        runCatching {
            Git.open(localDir).use { git ->
                git.add().addFilepattern(".").call()
                val status = git.status().call()
                if (status.isClean) return@use null
                // BUG-006 fix (bug_report_20260503_snao): take the set **union**
                // rather than summing `.size`. JGit's status buckets can overlap
                // in transient states (e.g. a path can sit in both `modified`
                // and `missing` when the working-tree file is deleted while the
                // index still shows earlier edits). Summing inflates the audit
                // count; the deduplicated path set is the honest answer.
                val filesChanged = (
                    status.added +
                        status.changed +
                        status.modified +
                        status.removed +
                        status.missing +
                        status.untracked
                    ).size
                val ident = PersonIdent(authorName, authorEmail)
                val commit = git.commit()
                    .setMessage(message)
                    .setAuthor(ident)
                    .setCommitter(ident)
                    .call()
                CommitOutcome(objectId = commit.id.name, filesChanged = filesChanged)
            }
        }.mapException(sanitizer)
    }

    /**
     * Fix for bug_report_20260503 "审计统计恒为 0": return a [PushOutcome]
     * whose `commitsPushed` counts commits actually advanced on the remote.
     *
     * For every `RemoteRefUpdate` with `status == OK` we walk
     * `expectedOldObjectId..newObjectId` and sum the commits. Up-to-date
     * refs contribute 0 (honest "nothing to push"). `forcedUpdate` fallback
     * just surfaces 1 because the history has been rewritten and counting
     * is ambiguous.
     */
    suspend fun push(
        localDir: File,
        username: String,
        pat: CharArray,
    ): Result<PushOutcome> = withContext(io) {
        runCatching {
            Git.open(localDir).use { git ->
                val pushCmd = git.push()
                applyAuth(pushCmd, username, pat)
                var commitsPushed = 0
                pushCmd.call().forEach { pr ->
                    pr.remoteUpdates.forEach { up ->
                        when (up.status) {
                            RemoteRefUpdate.Status.OK -> {
                                commitsPushed += countAdvancedCommits(git, up)
                            }
                            RemoteRefUpdate.Status.UP_TO_DATE -> Unit
                            else -> throw PushRejectedException(
                                up.status.name,
                                up.message.orEmpty(),
                            )
                        }
                    }
                }
                PushOutcome(commitsPushed = commitsPushed)
            }
        }.mapException(sanitizer)
    }

    /**
     * Walk `expectedOldObjectId..newObjectId` to count commits advanced by a
     * successful [RemoteRefUpdate]. Defensive fallbacks:
     *  - brand-new branch (old = zeroId): we cannot cheaply bound the walk,
     *    so count 1 (at least one tip commit landed).
     *  - force-update / non-linear: also fall back to 1 because the diff
     *    isn't a simple commit range.
     *  - any RevWalk failure: swallow and return 1 — better to report at
     *    least "something was pushed" than to regress to hard-coded 0.
     */
    private fun countAdvancedCommits(
        git: Git,
        up: RemoteRefUpdate,
    ): Int {
        val newId = up.newObjectId ?: return 0
        val oldId = up.expectedOldObjectId
        if (oldId == null || oldId.equals(org.eclipse.jgit.lib.ObjectId.zeroId())) {
            return 1
        }
        return runCatching {
            org.eclipse.jgit.revwalk.RevWalk(git.repository).use { walk ->
                walk.markStart(walk.parseCommit(newId))
                walk.markUninteresting(walk.parseCommit(oldId))
                val n = walk.count()
                if (n == 0) 1 else n
            }
        }.getOrDefault(1)
    }

    /**
     * SPEC §4.4.2 (P0-1 closure): dispatch on `binding.authType`. Signature
     * stays PAT-shaped so callers don't need to branch — SSH-bound repos
     * pass `CharArray(0)` for [pat] and this method ignores it.
     *
     * Unknown [RepoBinding.authType] values raise [IllegalStateException] —
     * silently falling back to PAT would hide a data-migration bug where a
     * future enum value reaches Data layer without being dispatched here.
     */
    private suspend fun applyAuth(
        cmd: TransportCommand<*, *>,
        username: String,
        pat: CharArray,
    ) {
        val binding = bindingRepository.currentOrNull()
        when (val authType = binding?.authType) {
            null, "PAT" -> cmd.setCredentialsProvider(
                UsernamePasswordCredentialsProvider(username, pat),
            )
            "SSH" -> cmd.setTransportConfigCallback { transport ->
                (transport as? SshTransport)?.sshSessionFactory =
                    sshSessionFactoryProvider.buildFactory(binding.id, binding.authRef)
            }
            else -> error("unknown authType=$authType")
        }
    }
}
