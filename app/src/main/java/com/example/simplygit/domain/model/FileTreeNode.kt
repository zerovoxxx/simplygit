package com.example.simplygit.domain.model

/**
 * A single row of the file-tree view surfaced to Presentation layer
 * (SPEC §4.1.1 Iteration 3).
 *
 *  - [gitStatus] is the node's own status (file's own state; for directories
 *    this is always [GitFileStatus.CLEAN] — directories don't carry Git state
 *    on their own, only their aggregate).
 *  - [aggregatedStatus] is the max-priority status among the node's subtree.
 *    For files it equals [gitStatus]. For directories it is pre-computed during
 *    rescan and persisted into `file_tree_cache.git_status` so UI does not have
 *    to aggregate on every render.
 *
 * Marked `@Stable` (no mutable state, every field is immutable) so Compose
 * `LazyColumn` with `key = { it.path }` can skip recomposition when an
 * unchanged row scrolls back on screen.
 */
@androidx.compose.runtime.Stable
data class FileTreeNode(
    val repoId: Long,
    val path: String,
    val name: String,
    val type: FileType,
    val gitStatus: GitFileStatus,
    val aggregatedStatus: GitFileStatus,
    val size: Long,
    val lastModified: Long,
)
