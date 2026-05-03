package com.example.simplygit.domain.model

/**
 * Which two revisions a diff request compares (SPEC §4.2.1 Iteration 3).
 *
 * P2-1 closed: `COMMIT_VS_COMMIT` was dropped — there is no consumer yet and
 * the enum surface stays minimal.
 */
enum class DiffSource {
    /** Working tree vs HEAD — shows uncommitted modifications. */
    WORKING_VS_HEAD,

    /** HEAD (ours) vs MERGE_HEAD (theirs) — used by the conflict-resolve path. */
    OURS_VS_THEIRS,
}
