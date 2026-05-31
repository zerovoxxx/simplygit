package com.example.simplygit.domain.repository

import com.example.simplygit.domain.model.ConflictFile
import com.example.simplygit.domain.model.ResolutionChoice

/**
 * Conflict-resolution surface (SPEC §4.3 Iteration 3).
 *
 * Implementation lives in Data and keeps JGit `CheckoutCommand.Stage` /
 * `CommitCommand` inside a `Git.open(dir).use{}` scope — Domain never holds
 * JGit types (SPEC P6).
 */
interface ConflictRepository {

    /**
     * Enumerates the currently-conflicting paths by reading `git.status()`
     * inside a short-lived JGit scope.
     */
    suspend fun listConflicts(repoId: Long): List<ConflictFile>

    /**
     * Resolves a single path to either ours or theirs and re-stages it via
     * `git add`. [choice] MUST be [ResolutionChoice.KEEP_OURS] or
     * [ResolutionChoice.TAKE_THEIRS]; callers filter out SKIP upstream.
     */
    suspend fun checkoutStage(repoId: Long, path: String, choice: ResolutionChoice)

    /**
     * Commits the staged conflict resolutions with [message] using [authorName]
     * / [authorEmail]. Returns the number of files committed.
     */
    suspend fun commitResolved(
        repoId: Long,
        message: String,
        authorName: String,
        authorEmail: String,
    ): Int
}
