package com.example.simplygit.domain.repository

import com.example.simplygit.domain.model.CommitOutcome
import com.example.simplygit.domain.model.GitOpResult
import com.example.simplygit.domain.model.PullOutcomeClassified
import com.example.simplygit.domain.model.RepoBinding

/**
 * Git operations exposed to the Domain layer (SPEC §6.2).
 *
 * PAT is always passed as [CharArray] at method boundaries rather than wrapped in a
 * Credential object — this keeps its lifecycle visible on the call chain so callers
 * never forget the `try/finally { Arrays.fill(pat, '\u0000') }` pattern.
 *
 * SPEC Iteration 2 (fix I-2 / I-3): two new methods ([pullAndClassify],
 * [commitAllIfDirty]) coexist with the Iteration 1 throw-style methods.
 *  - [clone] / [pull] / [commitAll] / [push] are kept untouched for Home's
 *    manual-diagnostic buttons (SPEC §4.7 I-7).
 *  - [pullAndClassify] / [commitAllIfDirty] are consumed by
 *    [com.example.simplygit.domain.usecase.RunSyncUseCase] and return plain
 *    data (no JGit native references).
 */
interface GitRepository {
    suspend fun clone(binding: RepoBinding, username: String, pat: CharArray): GitOpResult
    suspend fun pull(binding: RepoBinding, username: String, pat: CharArray): GitOpResult
    suspend fun commitAll(
        binding: RepoBinding,
        message: String,
        authorName: String,
        authorEmail: String,
    ): GitOpResult
    suspend fun push(binding: RepoBinding, username: String, pat: CharArray): GitOpResult

    /**
     * Pull and classify the merge outcome inside the same `Git.open(dir).use{}`
     * scope (SPEC §4.2 / §4.9 Iteration 2). Returns a pure DTO; any failure is
     * thrown as [com.example.simplygit.data.git.SanitizedGitException] whose
     * `kind` field drives `RunSyncUseCase`'s dispatcher.
     */
    suspend fun pullAndClassify(
        binding: RepoBinding,
        username: String,
        pat: CharArray,
    ): PullOutcomeClassified

    /**
     * Commit all changes iff the working tree is dirty (SPEC §4.3 Iteration 2).
     * Returns `null` when `status().isClean` — supports idempotent retries by
     * `RunSyncUseCase` after a failed push.
     */
    suspend fun commitAllIfDirty(
        binding: RepoBinding,
        message: String,
        authorName: String,
        authorEmail: String,
    ): CommitOutcome?
}
