package com.example.simplygit.domain.model

/**
 * Per-path Git status (SPEC §4.1.1 Iteration 3).
 *
 * Derived from `org.eclipse.jgit.api.Status`'s set views (added / changed /
 * modified / missing / untracked / conflicting / removed). The Data layer
 * dispatcher maps each set to one of these enum values before crossing the
 * Domain boundary (P6: JGit native types don't leak out of `data/`).
 *
 * Aggregation priority for directory nodes (highest wins):
 *   CONFLICT > DELETED > MODIFIED > STAGED > UNTRACKED > CLEAN
 * See `FileTreeRepositoryImpl.aggregateStatus`.
 *
 * [DELETED] corresponds to JGit's `status.missing` (tracked file gone from
 * the working tree but not yet `git rm`-ed). It used to be collapsed into
 * [MODIFIED], which mis-represented the intent on the file-tree screen; the
 * bug scan on 2026-05-03 (`docs/bugreport/20260503/...`) pulled it out.
 */
enum class GitFileStatus {
    CLEAN,
    MODIFIED,
    UNTRACKED,
    STAGED,
    DELETED,
    CONFLICT,
}
