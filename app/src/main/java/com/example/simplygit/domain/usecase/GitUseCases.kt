package com.example.simplygit.domain.usecase

import android.net.Uri
import com.example.simplygit.data.diagnostics.DiagnosticsLogger
import com.example.simplygit.data.git.JGitExceptionSanitizer
import com.example.simplygit.data.git.SafPermissionRevokedException
import com.example.simplygit.data.saf.SafPathResolver
import com.example.simplygit.domain.model.GitOp
import com.example.simplygit.domain.model.GitOpResult
import com.example.simplygit.domain.model.RepoBinding
import com.example.simplygit.domain.repository.CredentialPublicView
import com.example.simplygit.domain.repository.CredentialRepository
import com.example.simplygit.domain.repository.GitRepository
import com.example.simplygit.domain.repository.RepoBindingRepository
import kotlinx.coroutines.flow.first
import java.util.Arrays
import javax.inject.Inject

/** Signals that the user has not saved a PAT yet (SPEC §4.5). */
class MissingCredentialException : RuntimeException("credential not configured")

/** Signals that the user has not finished the full binding flow yet (SPEC §4.5). */
class MissingBindingException : RuntimeException("repo binding not configured")

/**
 * Shared bootstrap used by every PAT-consuming Git UseCase (SPEC §4.3 / §4.5 / §6.3):
 *  - verifies SAF permission is still alive;
 *  - resolves the binding + credential view;
 *  - surfaces failures through [JGitExceptionSanitizer] so UI / logs only ever see a
 *    [com.example.simplygit.data.git.SanitizedGitException].
 */
class GitOpPreflight @Inject constructor(
    private val bindingRepo: RepoBindingRepository,
    private val credRepo: CredentialRepository,
    private val resolver: SafPathResolver,
    private val sanitizer: JGitExceptionSanitizer,
    private val diagnostics: DiagnosticsLogger,
) {

    data class Ready(val binding: RepoBinding, val credential: CredentialPublicView)

    suspend fun prepare(op: GitOp): Result<Ready> {
        val binding = runCatching { bindingRepo.requireCurrent() }
            .getOrElse {
                return domainFailure(op, MissingBindingException())
            }

        // SPEC §4.3: check that the persisted SAF permission has not been revoked by
        // the system (e.g. Android's long-unused permission auto-reset). If so, surface
        // SafPermissionRevokedException so the UI can guide the user to re-grant.
        val treeUri = runCatching { Uri.parse(binding.treeUri) }.getOrNull()
        if (treeUri == null || !resolver.hasPersistedPermission(treeUri)) {
            return domainFailure(op, SafPermissionRevokedException())
        }

        val view = credRepo.observe().first()
            ?: return domainFailure(op, MissingCredentialException())

        return Result.success(Ready(binding, view))
    }

    suspend fun afterOp(op: GitOp, result: GitOpResult): GitOpResult {
        return when (result) {
            is GitOpResult.Failure -> {
                // SPEC §6.2 Iteration 3: SshHostKeyFirstConnectException is
                // the single exception white-listed to bypass sanitization —
                // UI captures the exact type and opens a TOFU confirmation
                // dialog. All other Throwables must go through the
                // sanitizer before reaching UI/logs.
                if (result.cause is com.example.simplygit.data.ssh.SshHostKeyFirstConnectException) {
                    diagnostics.logGitOpFailure(op.name, result.cause)
                    return result
                }
                // SPEC §6.3: every Throwable coming back from JGit must be sanitized
                // before reaching UI/logs. GitRepositoryImpl already routes through
                // JGitExceptionSanitizer, so cause is usually SanitizedGitException —
                // but re-run the filter defensively in case new call sites forget.
                val sanitized = if (result.cause is com.example.simplygit.data.git.SanitizedGitException) {
                    result.cause
                } else {
                    sanitizer.sanitize(result.cause)
                }
                diagnostics.logGitOpFailure(op.name, sanitized)
                GitOpResult.Failure(op, sanitized)
            }
            else -> result
        }
    }

    /**
     * Pre-JGit domain failures (missing credential / binding / revoked SAF permission)
     * preserve their original type so [ErrorKind] dispatching in the ViewModel can
     * surface localized messages. They never carry PAT/URL text, so the sanitizer
     * detour would only obscure the i18n mapping.
     */
    private suspend fun domainFailure(op: GitOp, cause: Throwable): Result<Ready> {
        diagnostics.logGitOpFailure(op.name, cause)
        return Result.failure(cause)
    }
}

/**
 * PAT-aware UseCase template (SPEC §4.5):
 *  1. loads the PAT via [CredentialRepository.loadPatOnce];
 *  2. invokes [block];
 *  3. zeroes the buffer in `finally`.
 *
 * Returns [GitOpResult.Failure] with [MissingCredentialException] when no PAT exists.
 */
private suspend inline fun runWithPat(
    credRepo: CredentialRepository,
    op: GitOp,
    block: (pat: CharArray) -> GitOpResult,
): GitOpResult {
    val pat = credRepo.loadPatOnce()
        ?: return GitOpResult.Failure(op, MissingCredentialException())
    return try {
        block(pat)
    } finally {
        Arrays.fill(pat, '\u0000')
    }
}

/** SPEC §4.5: PAT-involving UseCase template. */
class CloneRepoUseCase @Inject constructor(
    private val credRepo: CredentialRepository,
    private val gitRepo: GitRepository,
    private val preflight: GitOpPreflight,
) {
    suspend operator fun invoke(): GitOpResult {
        val ready = preflight.prepare(GitOp.CLONE)
            .getOrElse { return GitOpResult.Failure(GitOp.CLONE, it) }
        val result = runWithPat(credRepo, GitOp.CLONE) { pat ->
            gitRepo.clone(ready.binding, ready.credential.username, pat)
        }
        return preflight.afterOp(GitOp.CLONE, result)
    }
}

class PullRepoUseCase @Inject constructor(
    private val credRepo: CredentialRepository,
    private val gitRepo: GitRepository,
    private val preflight: GitOpPreflight,
) {
    suspend operator fun invoke(): GitOpResult {
        val ready = preflight.prepare(GitOp.PULL)
            .getOrElse { return GitOpResult.Failure(GitOp.PULL, it) }
        val result = runWithPat(credRepo, GitOp.PULL) { pat ->
            gitRepo.pull(ready.binding, ready.credential.username, pat)
        }
        return preflight.afterOp(GitOp.PULL, result)
    }
}

class PushRepoUseCase @Inject constructor(
    private val credRepo: CredentialRepository,
    private val gitRepo: GitRepository,
    private val preflight: GitOpPreflight,
) {
    suspend operator fun invoke(): GitOpResult {
        val ready = preflight.prepare(GitOp.PUSH)
            .getOrElse { return GitOpResult.Failure(GitOp.PUSH, it) }
        val result = runWithPat(credRepo, GitOp.PUSH) { pat ->
            gitRepo.push(ready.binding, ready.credential.username, pat)
        }
        return preflight.afterOp(GitOp.PUSH, result)
    }
}

/** Commit does not touch the PAT; it only needs `author.name` / `author.email`
 *  from the credential store (SPEC §4.5 / §6.3). */
class CommitLocalUseCase @Inject constructor(
    private val gitRepo: GitRepository,
    private val preflight: GitOpPreflight,
) {
    suspend operator fun invoke(message: String): GitOpResult {
        val ready = preflight.prepare(GitOp.COMMIT)
            .getOrElse { return GitOpResult.Failure(GitOp.COMMIT, it) }
        val result = gitRepo.commitAll(
            ready.binding,
            message,
            ready.credential.username,
            ready.credential.email,
        )
        return preflight.afterOp(GitOp.COMMIT, result)
    }
}
