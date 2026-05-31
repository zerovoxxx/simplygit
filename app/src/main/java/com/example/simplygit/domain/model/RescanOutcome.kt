package com.example.simplygit.domain.model

/**
 * Outcome of a single file-tree scan (SPEC §4.1.1 Iteration 3).
 *
 *  - [totalEntries] counts every file + directory seen; `-1` means "aborted
 *    because the repo exceeded [SCAN_HARD_LIMIT]"; the UI should surface
 *    `R.string.browser_repo_too_large` in that case.
 *  - [classified] is a breakdown by [GitFileStatus] (file-level only, so
 *    callers can show "3 files conflicting" without re-scanning).
 */
data class RescanOutcome(
    val totalEntries: Int,
    val durationMs: Long,
    val classified: Map<GitFileStatus, Int>,
) {
    companion object {
        /** SPEC §4.1.1: single rescan upper bound. */
        const val SCAN_HARD_LIMIT: Int = 30_000
    }
}
