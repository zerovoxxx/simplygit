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
 *   CONFLICT > MODIFIED > STAGED > UNTRACKED > CLEAN
 * See `FileTreeRepositoryImpl.aggregateStatus`.
 */
enum class GitFileStatus {
    CLEAN,
    MODIFIED,
    UNTRACKED,
    STAGED,
    CONFLICT,
}
