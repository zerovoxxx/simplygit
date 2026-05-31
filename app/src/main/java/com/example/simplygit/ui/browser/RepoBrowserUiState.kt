package com.example.simplygit.ui.browser

import com.example.simplygit.domain.model.FileTreeNode

/** UI state for [RepoBrowserScreen] (SPEC §4.1.2 Iteration 3). */
data class RepoBrowserUiState(
    val repoId: Long = 0L,
    val currentPath: String = "",
    val currentEntries: List<FileTreeNode> = emptyList(),
    val breadcrumb: List<BreadcrumbSegment> = emptyList(),
    val isRescanning: Boolean = false,
    val lastRescanAt: Long? = null,
    /**
     * When true, the last rescan hit the `SCAN_HARD_LIMIT` — UI shows a
     * "repo too large" banner.
     */
    val repoTooLarge: Boolean = false,
    /** `true` when binding is missing; UI shows an empty-state CTA back to Home. */
    val noBinding: Boolean = false,
)

/** One segment of the navigation breadcrumb (SPEC §4.1.2). */
data class BreadcrumbSegment(val label: String, val path: String)
