package com.example.simplygit.domain.model

/**
 * Six-way conflict classification (SPEC §4.2 / §4.5 Iteration 2).
 *
 * Mapped from JGit `MergeResult.MergeStatus` inside `ConflictClassifier`
 * (Data layer). Domain / UI consume this as a pure value — no JGit types.
 */
enum class ConflictClass {
    FAST_FORWARD,
    AUTO_MERGED,
    TEXT_LINE_CONFLICT,
    BINARY_CONFLICT,
    DELETE_MODIFY,
    REMOTE_REWRITE,
}

/** Classifications that force the worker to pause and notify (SPEC §4.5). */
val UNRESOLVABLE_CONFLICTS: Set<ConflictClass> = setOf(
    ConflictClass.TEXT_LINE_CONFLICT,
    ConflictClass.BINARY_CONFLICT,
    ConflictClass.DELETE_MODIFY,
    ConflictClass.REMOTE_REWRITE,
)
