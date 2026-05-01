package com.example.simplygit.data.git

import com.example.simplygit.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.MergeCommand
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.transport.RemoteRefUpdate
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
 */
@Singleton
class JGitDataSource @Inject constructor(
    @IoDispatcher private val io: CoroutineDispatcher,
    private val sanitizer: JGitExceptionSanitizer,
) {

    suspend fun clone(
        remoteUrl: String,
        localDir: File,
        username: String,
        pat: CharArray,
    ): Result<Unit> = withContext(io) {
        runCatching {
            val provider = UsernamePasswordCredentialsProvider(username, pat)
            Git.cloneRepository()
                .setURI(remoteUrl)
                .setDirectory(localDir)
                .setCredentialsProvider(provider)
                .call()
                .use { /* Git.close() via use */ }
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
                val provider = UsernamePasswordCredentialsProvider(username, pat)
                val result = git.pull()
                    .setCredentialsProvider(provider)
                    .setFastForward(MergeCommand.FastForwardMode.FF)
                    .call()
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

    suspend fun push(
        localDir: File,
        username: String,
        pat: CharArray,
    ): Result<Unit> = withContext(io) {
        runCatching {
            Git.open(localDir).use { git ->
                val provider = UsernamePasswordCredentialsProvider(username, pat)
                git.push().setCredentialsProvider(provider).call().forEach { pr ->
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
}
