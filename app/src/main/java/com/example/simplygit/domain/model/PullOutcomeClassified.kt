package com.example.simplygit.domain.model

/**
 * Pure-data DTO returned by [com.example.simplygit.domain.repository.GitRepository.pullAndClassify]
 * (SPEC §4.2 / §6.1 Iteration 2, fix I-3).
 *
 * Contains **no** JGit native references (`Repository` / `PullResult` / `MergeResult`):
 * the classifier runs inside the `Git.open(dir).use { }` scope in the Data layer and
 * produces this flattened DTO. Domain / UI can keep the result across coroutine
 * resumptions without risking pack-file mmap leaks.
 */
data class PullOutcomeClassified(
    val classification: ConflictClass,
    val commitsPulled: Int,
    val conflictPaths: List<String>,
    /** JGit `MergeStatus.name()` for diagnostics only (non-enum, forward-compat). */
    val mergeStatusName: String?,
)
