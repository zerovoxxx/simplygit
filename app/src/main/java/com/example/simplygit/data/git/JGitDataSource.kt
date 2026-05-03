package com.example.simplygit.data.git

import com.example.simplygit.data.ssh.GitSshSessionFactoryProvider
import com.example.simplygit.di.IoDispatcher
import com.example.simplygit.domain.model.CommitOutcome
import com.example.simplygit.domain.model.PullOutcomeClassified
import com.example.simplygit.domain.repository.RepoBindingRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.MergeCommand
import org.eclipse.jgit.api.TransportCommand
import org.eclipse.jgit.lib.ObjectId
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

    suspend fun commitAll(
        localDir: File,
        message: String,
        authorName: String,
        authorEmail: String,
    ): Result<ObjectId> = withContext(io) {
        runCatching {
            Git.open(localDir).use { git ->
                git.add().addFilepattern(".").call()
                val status = git.status().call()
                if (status.isClean) throw NoChangesException()
                val ident = PersonIdent(authorName, authorEmail)
                git.commit()
                    .setMessage(message)
                    .setAuthor(ident)
                    .setCommitter(ident)
                    .call()
                    .id
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
                val filesChanged = status.added.size +
                    status.changed.size +
                    status.modified.size +
                    status.removed.size +
                    status.missing.size +
                    status.untracked.size
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

    suspend fun push(
        localDir: File,
        username: String,
        pat: CharArray,
    ): Result<Unit> = withContext(io) {
        runCatching {
            Git.open(localDir).use { git ->
                val pushCmd = git.push()
                applyAuth(pushCmd, username, pat)
                pushCmd.call().forEach { pr ->
                    pr.remoteUpdates.forEach { up ->
                        if (up.status != RemoteRefUpdate.Status.OK &&
                            up.status != RemoteRefUpdate.Status.UP_TO_DATE
                        ) {
                            throw PushRejectedException(
                                up.status.name,
                                up.message.orEmpty()
                            )
                        }
                    }
                }
            }
        }.mapException(sanitizer)
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
